# OpenCode Mobile (Android)

Runs the full opencode agent on Android: a Bun runtime hosts the opencode
**serve** entry inside a Kotlin foreground `Service`, and the SolidJS
`packages/app` SPA runs in a Capacitor WebView. Sessions, tools, and the PTY
all talk to the local server over loopback.

Distribution is **sideload / GitHub Releases** — no Play Store, no Google
Play services.

## What happens on first launch

1. `OpencodeService` starts. It downloads the **official Bun Android binary**
   (`bun-v1.3.14/bun-linux-aarch64-android.zip`, ~33 MB) from
   `github.com/oven-sh/bun` into the app's files dir. This is a one-time
   download; the binary is reused afterwards.
2. Bundled native assets are staged from the APK into app storage:
   `rg` (Termux ripgrep 15.2.0), `libpcre2-8.so`, `libc++_shared.so`, and the
   `pty.node` NAPI addon. Ripgrep lands in `Global.Path.bin`
   (`.cache/opencode/bin/rg`) so the agent never tries to download it.
3. The server bundle (committed SPA build + bundled `serve.ts`) is copied to
   disk and Bun starts `serve --port 4096 --hostname 127.0.0.1`.
4. First boots can take 20–60 s on a phone: download + extract + Bun startup.
   The service shows a foreground notification.

## arm64-v8a only (and why)

The heavy native assets ship **only for arm64-v8a**:

- Bun Android binary (official `aarch64-linux-android` build)
- `node-pty-android-arm64` NAPI addon
- Termux ripgrep

On other ABIs (`armeabi-v7a`, `x86_64`) the APK still runs, but:

- PTY falls back to a **non-interactive shell pipe** (`child_process.spawn`):
  output streams correctly, but there is no TTY size / ANSI control, so
  interactive full-screen tools (ghostty-web) degrade to scrollback-only.
- ripgrep falls back to a JS implementation (or a no-op) instead of the
  native binary.

This is a documented limitation, not a parity promise. Refreshing the ABI
matrix is one workflow-matrix change plus staged assets per ABI; do that after
the arm64 path is proven on a device.

## Setting up API keys

1. Open **Settings → Keys** in the app.
2. Tap **Unlock** — Android prompts for your device credential / biometric
   (fingerprint, face, PIN). The prompt is a system `KeyguardManager` flow, so
   it works without any biometric-permission dance.
3. Keys are written to `EncryptedSharedPreferences` (Android Keystore-backed
   master key). On the next server start they are merged verbatim into the
   Bun process environment, so agents can authenticate without code changes.

## Granting a workspace folder (SAF)

1. Open **Settings → Workspace Folder**.
2. Pick a directory in the system document picker.
3. OpenCode retains the grant via `takePersistableUriPermission`, so you are
   not re-prompted until the app is reinstalled or Android clears the grant.

The JS bridge (`FsBridgePlugin`) exposes list/read/write/create/remove over
the `content://` tree URI (see the plugin KDoc for the exact call contract).

## Architecture

```
┌─────────────────────────────┐
│  Capacitor WebView (SPA)    │
│  http(s)://127.0.0.1:4096   │
│  ws://127.0.0.1:4096        │  ← PTY / event stream (same-origin)
└──────────┬──────────────────┘
           │ cleartext loopback (network_security_config.xml)
┌──────────▼──────────────────┐
│  OpencodeService (Bun)      │
│  HOME/XDG_* → filesDir      │
│  PATH += .cache/opencode/bin│
│  env += EncryptedSharedPrefs│
└───┬────────────┬────────────┘
    │ pty.node   │ rg
┌───▼───────┐ ┌──▼────────┐
│ node-pty  │ │ ripgrep   │  staged from APK assets/native/
└───────────┘ └───────────┘
```

- **Direct loopback, no reverse proxy.** `capacitor.config.ts` uses scheme
  `http` + hostname `127.0.0.1`; the app-level `network_security_config.xml`
  permits cleartext for loopback only. The SPA and the server are
  same-origin, so WebSockets and REST all Just Work with zero CORS handling.
- `LocalReverseProxy` (the `WebViewAssetLoader` / `https://opencode.local`
  path) is kept but **not wired** — `PathHandler` can't stream WebSockets or
  request bodies, so it would be a dead end for the PTY channel anyway.
- Core-side Android backends live in `packages/core/src/pty/pty.android.ts`
  and `packages/core/src/filesystem/watcher.android.ts`; ripgrep platform
  selection in `packages/core/src/ripgrep/binary.ts`. Everything else is
  unmodified opencode core, reused 100%.

## Building

Only CI builds a real APK (see `.github/workflows/android-port.yml`):

```bash
# push to android-port → APK uploaded, or tag v*-android*
git push origin android-port
```

Local workflows are source-only: no Gradle, no emulator on the dev box.

`packages/mobile/scripts/stage-native-assets.sh` downloads the pinned Termux
debs + npm prebuild and stages them under `android-app/assets/native/` (that
directory is gitignored). `patch-android.mjs` copies the Kotlin sidecar,
manifest, res/, assets, and native binaries into the generated `android/`
tree, and injects `versionName` / `versionCode` / applicationId plus the
`androidx.security:security-crypto` dependency.

## Troubleshooting

- **Server never reaches "running"**: `OpencodeService` logs every
  boot-message line to logcat (`adb logcat | grep OpencodeService`). Bun's
  stderr is captured there too.
- **Terminal is blank / no output**: check the arm64 note above; on a
  non-arm64 device the pipe fallback still streams output but cannot resize.
- **`libc++_shared.so` mismatch**: the node-pty addon is built against
  Termux's libc++; we stage the matching `libc++_29` shared lib. If a future
  `node-pty-android-arm64` bump changes its ABI, update `stage-native-assets.sh`.