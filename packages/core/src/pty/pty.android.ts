import { spawn as create, type ChildProcess } from "child_process"
import type { Exit, Opts, Proc } from "./pty"

export type { Disp, Exit, Opts, Proc } from "./pty"

type NativePty = {
  spawn(file: string, args: string[], opts: NativeOpts): NativeProc
}

type NativeOpts = {
  name?: string
  cols?: number
  rows?: number
  cwd?: string
  env?: Record<string, string>
}

type NativeProc = {
  pid: number
  onData(listener: (data: string) => void): { dispose(): void }
  onExit(listener: (event: Exit) => void): { dispose(): void }
  write(data: string): void
  resize(cols: number, rows: number): void
  kill(signal?: string): void
}

type PtyProcess = {
  dlopen(module: { exports: object }, filename: string): void
}

let native: NativePty | undefined

function loadNative(): NativePty | undefined {
  if (native) return native
  const path = process.env.OPENCODE_NODE_PTY_PATH
  if (!path) return
  try {
    const module = { exports: {} }
    ;(process as PtyProcess).dlopen(module, path)
    native = module.exports as NativePty
  } catch (error) {
    console.error(`[pty.android] failed to load native addon at ${path}`, error)
  }
  return native
}

export function spawn(file: string, args: string[], opts: Opts): Proc {
  const native = loadNative()
  if (native) return spawnNative(native, file, args, opts)
  return spawnPipe(file, args, opts)
}

function spawnNative(pty: NativePty, file: string, args: string[], opts: Opts): Proc {
  const proc = pty.spawn(file, args, { ...opts })
  return {
    pid: proc.pid,
    onData(listener) {
      return proc.onData(listener)
    },
    onExit(listener) {
      return proc.onExit(listener)
    },
    write(data) {
      proc.write(data)
    },
    resize(cols, rows) {
      proc.resize(cols, rows)
    },
    kill(signal) {
      proc.kill(signal)
    },
  }
}

// Pipe fallback: plain stdio with no TTY. Used when the native node-pty
// prebuild is not staged in the APK (non-arm64 ABIs, or the first run before
// bundled assets land). The shell runs non-interactively: output is real but
// there is no prompt, and resize() is a no-op.
function spawnPipe(file: string, args: string[], opts: Opts): Proc {
  const child = create(file, args, {
    cwd: opts.cwd,
    env: opts.env,
    stdio: ["pipe", "pipe", "pipe"],
  })
  const dataListeners = new Set<(data: string) => void>()
  const exitListeners = new Set<(event: Exit) => void>()
  let settled = false
  const fire = (event: Exit) => {
    if (settled) return
    settled = true
    for (const listener of exitListeners) listener(event)
  }
  child.on("error", (error) => {
    console.error(`[pty.android] failed to spawn ${file}`, error)
    fire({ exitCode: 1 })
  })
  child.on("exit", (code, signal) => fire({ exitCode: code ?? -1, signal: signal ?? undefined }))
  child.stdout.on("data", (chunk: Buffer) => emit(chunk.toString()))
  child.stderr.on("data", (chunk: Buffer) => emit(chunk.toString()))

  function emit(data: string) {
    for (const listener of dataListeners) listener(data)
  }

  return {
    pid: child.pid ?? -1,
    onData(listener) {
      dataListeners.add(listener)
      return {
        dispose() {
          dataListeners.delete(listener)
        },
      }
    },
    onExit(listener) {
      exitListeners.add(listener)
      return {
        dispose() {
          exitListeners.delete(listener)
        },
      }
    },
    write(data) {
      if (child.stdin.destroyed) return
      try {
        child.stdin.write(data)
      } catch {
        // stdin was already closed (child exited)
      }
    },
    resize() {
      // no TTY in pipe mode
    },
    kill(signal) {
      if (child.exitCode !== null || child.signalCode !== null) return
      try {
        child.kill(signal as NodeJS.Signals | undefined)
      } catch {
        // process already gone
      }
    },
  }
}