// Copies the built packages/app dist into the Capacitor webDir.
// Run by `bun run copy:web` (called automatically by `bun run prebuild`).
// Used by CI in place of `cap sync` because the android/ project may not
// exist on first run; this script is a no-op if the destination is missing.

import { cp, mkdir, stat } from "node:fs/promises"
import { resolve } from "node:path"

const source = resolve(import.meta.dirname, "..", "..", "app", "dist")
const destination = resolve(import.meta.dirname, "..", "android", "app", "src", "main", "assets", "public")

const exists = async (path) => {
  try {
    await stat(path)
    return true
  } catch {
    return false
  }
}

if (!(await exists(source))) {
  console.error(`[copy-web] source missing: ${source}`)
  console.error("[copy-web] did `bun --cwd packages/app build` run successfully?")
  process.exit(1)
}

if (!(await exists(resolve(destination, "..", "..", "..", "..", "..")))) {
  console.log(`[copy-web] android/ project not present yet; skipping copy.`)
  console.log(`[copy-web] run \`bun --cwd packages/mobile x cap add android\` once to scaffold.`)
  process.exit(0)
}

await mkdir(destination, { recursive: true })
await cp(source, destination, { recursive: true })
console.log(`[copy-web] ${source} -> ${destination}`)