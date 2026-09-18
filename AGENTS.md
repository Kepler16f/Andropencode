- To regenerate the legacy JavaScript SDK, run `./packages/sdk/js/script/build.ts`.
- After changing the public Protocol or Server `HttpApi`, run `bun run generate` from `packages/client`. Do not edit `src/generated` or `src/generated-effect` directly.
- Keep runtime dependencies directed from Schema to Core and Protocol, then from Core and Protocol to Server. Client runtime code may depend on Schema and Protocol but never Core or Server; `sdk-next` composes Client, Core, and Server.
- The default branch in this repo is `dev`.
- Local `main` ref may not exist; use `dev` or `origin/dev` for diffs.

## Branch Names

Use a short branch name of at most three words, separated by hyphens. Do not use slashes or type prefixes such as `feat/` or `fix/`.

Examples: `session-recovery`, `fix-scroll-state`, `regenerate-sdk`.

## Commits and PR Titles

Use conventional commit-style messages and PR titles: `type(scope): summary`.

Valid types are `feat`, `fix`, `docs`, `chore`, `refactor`, and `test`. Scopes are optional; use the affected package or area when helpful, e.g. `core`, `opencode`, `tui`, `app`, `desktop`, `sdk`, or `plugin`.

Examples: `fix(tui): simplify thinking toggle styling`, `docs: update contributing guide`, `chore(sdk): regenerate types`.

## Style Guide

### General Principles

- Keep things in one function unless composable or reusable
- Do not extract single-use helpers preemptively. Inline the logic at the call site unless the helper is reused, hides a genuinely complex boundary, or has a clear independent name that improves the caller.
- Avoid `try`/`catch` where possible
- Avoid using the `any` type
- Use Bun APIs when possible, like `Bun.file()`
- Rely on type inference when possible; avoid explicit type annotations or interfaces unless necessary for exports or clarity
- Prefer functional array methods (flatMap, filter, map) over for loops; use type guards on filter to maintain type inference downstream
- In `src/config`, follow the existing self-export pattern at the top of the file (for example `export * as ConfigAgent from "./agent"`) when adding a new config module.
- In Effect generators, bind services to named variables before calling methods. Do not use nested service yields such as `yield* (yield* Foo.Service).bar()`.

Reduce total variable count by inlining when a value is only used once.

```ts
// Good
const journal = await Bun.file(path.join(dir, "journal.json")).json()

// Bad
const journalPath = path.join(dir, "journal.json")
const journal = await Bun.file(journalPath).json()
```

### Destructuring

Avoid unnecessary destructuring. Use dot notation to preserve context.

```ts
// Good
obj.a
obj.b

// Bad
const { a, b } = obj
```

### Imports

- Never alias imports. Do not use `import { foo as bar } from "..."` or renamed imports like `resolve as pathResolve`.
- Never use star imports. Do not use `import * as Foo from "..."` or `import type * as Foo from "..."`.
- If a namespace-style value is needed, import the module's own exported namespace by name, for example `import { Project } from "@opencode-ai/core/project"`, then reference `Project.ID`.
- Prefer dynamic imports for heavy modules that are only needed in selected code paths, especially in startup-sensitive entrypoints. Destructure dynamic import bindings near the top of the narrowest scope that needs them so they read like normal imports. Avoid inline chains such as `await import("./module").then((mod) => mod.value())` or `(await import("./module")).value()`. Keep branch-specific imports inside the branch that needs them to preserve lazy loading.

### Variables

Prefer `const` over `let`. Use ternaries or early returns instead of reassignment.

```ts
// Good
const foo = condition ? 1 : 2

// Bad
let foo
if (condition) foo = 1
else foo = 2
```

### Control Flow

Avoid `else` statements. Prefer early returns.

```ts
// Good
function foo() {
  if (condition) return 1
  return 2
}

// Bad
function foo() {
  if (condition) return 1
  else return 2
}
```

### Complex Logic

When a function has several validation branches or supporting details, make the main function read as the happy path and move supporting details into small helpers below it.

```ts
// Good
export function loadThing(input: unknown) {
  const config = requireConfig(input)
  const metadata = readMetadata(input)
  return createThing({ config, metadata })
}

function requireConfig(input: unknown) {
  ...
}
```

- Keep helpers close to the code they support, below the main export when that improves readability.
- Do not over-abstract simple expressions into many single-use helpers; extract only when it names a real concept like `requireConfig` or `readMetadata`.
- Do not return `Effect` from helpers unless they actually perform effectful work. Synchronous parsing, validation, and option building should stay synchronous.
- Prefer Effect schema helpers such as `Schema.UnknownFromJsonString` and `Schema.decodeUnknownOption` over manual `JSON.parse` wrapped in `Effect.try` when parsing untrusted JSON strings.
- Add comments for non-obvious constraints and surprising behavior, not for obvious assignments or control flow.

### Schema Definitions (Drizzle)

Use snake_case for field names so column names don't need to be redefined as strings.

```ts
// Good
const table = sqliteTable("session", {
  id: text().primaryKey(),
  project_id: text().notNull(),
  created_at: integer().notNull(),
})

// Bad
const table = sqliteTable("session", {
  id: text("id").primaryKey(),
  projectID: text("project_id").notNull(),
  createdAt: integer("created_at").notNull(),
})
```

## Testing

- Avoid mocks as much as possible, you shouldn't be using globalThis.\* at all unless it's the only option.
- Test actual implementation, do not duplicate logic into tests
- Tests cannot run from repo root (guard: `do-not-run-tests-from-root`); run from package dirs like `packages/opencode`.

## Type Checking

- Always run `bun typecheck` from package directories (e.g., `packages/opencode`), never `tsc` directly.

## V2 Session Core

- Keep durable prompt admission separate from model execution. `SessionV2.prompt(...)` admits one durable `session_input` row before scheduling advisory `SessionExecution.wake(sessionID)` unless `resume: false` requests admit-only behavior. The serialized runner promotes admitted inputs into visible user messages at safe boundaries.
- Reusing a Session ID adopts the existing Session. Reusing a prompt message ID reconciles an exact retry only when Session, prompt, and delivery mode match; conflicting reuse fails. Historical projected prompts lazily synthesize promoted inbox records during exact retry.
- Keep `SessionExecution` process-global and Session-ID based. Its local implementation owns the process-local Session coordinator and discovers placement through `SessionStore` plus `LocationServiceMap.get(session.location)` only when a drain starts; no layer should take a Session ID. V2 interruption targets the active process-local ownership chain for that Session; idle or missing interruption is a no-op.
- Keep `SessionRunner`, model resolution, tool registry, permissions, and filesystem Location-scoped. Omitted `Location.workspaceID` means implicit-local placement; explicit workspace identity remains reserved for future placement semantics.
- Preserve one explicit `llm.stream(request)` call per provider turn and reload projected history before durable continuation. Do not bridge through legacy `SessionPrompt.loop(...)` or delegate orchestration to an in-memory tool loop.
- Keep local Session drains process-local until clustering is implemented. `SessionRunCoordinator` joins explicit same-Session resumes, coalesces prompt wakeups, and allows different Sessions to run concurrently. Advisory wakes drain eligible durable inbox rows only; post-crash continuation recovery requires a separate explicit design before it may retry provider work. A drain has no durable identity or transcript boundary.
- Keep delivery vocabulary explicit. Prompts steer by default and promote at the next safe provider-turn boundary while the current drain requires continuation. An explicit `queue` input remains pending until the Session would otherwise become idle; promote one queued input at that boundary, then reevaluate continuation before promoting another. Promoting any new user input resets the selected agent's provider-turn allowance; a batch of steers resets it once.
- Keep EventV2 replay owner claims separate from clustered Session execution ownership.
- Keep the System Context algebra, registry, and built-ins in `src/system-context`; keep Context Source producers with their observed domains, and keep Session History selection plus Context Epoch persistence Session-owned.

## Android Port (`android-port` branch only)

Target: run the full opencode agent natively on Android — Bun 1.4 server hosted by a Kotlin foreground Service, SolidJS `packages/app` SPA hosted in a Capacitor WebView. **Distribution model is sideload / F-Droid / GitHub Releases** — no Play Store, no GMS dependency. This is a personal-distribution project; keep the build self-contained, the README written for first-time users, and the maintenance surface small.

**Branch and scope**
- All Android work happens on the `android-port` branch based on `dev`. Do not commit Android-only changes outside this branch.
- **Do not modify `packages/server` or `packages/opencode` core** — they are reused 100%. Android-specific code lives in `packages/mobile/` (new), plus two new files in `packages/core/src/pty/pty.android.ts` and `packages/core/src/filesystem/watcher.android.ts`, plus one platform key in `packages/core/src/ripgrep/binary.ts`.
- Do not delete `packages/desktop`, `packages/app/src/desktop-menu`, or `packages/app/src/wsl` — gate them in `android-port` only. Other branches still depend on them.

**Runtime pinning**
- Pin Bun to the version declared in `packageManager` (currently `bun@1.3.14`) through Phase 0–2. Do not upgrade until the upstream `aarch64-linux-android` target is verified against this repo's Bun build.
- The Bun Android binary must be downloaded from official `oven-sh/bun` releases (do not vendor patches in `node_modules/`).
- `@lydell/node-pty` on Android uses the Termux fork `node-pty-android-arm64` from `guysoft/opencode-termux`, not the desktop glibc fork.

**ABI matrix**
- Build all three ABIs: `armeabi-v7a`, `arm64-v8a`, `x86_64` via `splits.abi`, plus one universal APK fallback.
- Native heavy assets (`node-pty-android-arm64`, `ripgrep-aarch64-linux-android30`, Bun Android binary) ship **only for `arm64-v8a`** — other ABIs run in a non-interactive shell fallback. Document this limitation in the README instead of pretending parity.

**Capacitor conventions**
- Project root: `packages/mobile/`. Web assets: `packages/mobile/android/app/src/main/assets/public/` populated by `bun --cwd packages/app build`.
- Capacitor scheme `https`, hostname `opencode.local`. No cleartext — use `WebViewAssetLoader` interception to reverse-proxy to `127.0.0.1:4096`.
- Capacitor plugins live under `packages/mobile/android/app/src/main/kotlin/ai/opencode/mobile/bridge/`: `FsBridgePlugin` (SAF), `ShellBridgePlugin` (Process exec), `BiometricPlugin` (EncryptedSharedPreferences for API keys).
- The Bun process is hosted by `OpencodeService` (foreground Service, `START_STICKY`) and reverse-proxied via `LocalReverseProxy`.

**Build and verification strategy**
- Local machine writes source only — no local Gradle, no local emulator, no local APK install. The maintainer's Windows / Linux dev box is for editing code; the GitHub Actions runner is the only build host.
- Capacitor's Android project (`packages/mobile/android/`) is generated by `bun x cap add android` once and committed — treat it like vendor code. Do not hand-edit generated `android/` files unless you also update the regeneration command in `packages/mobile/scripts/scaffold.ts`.
- All APK / AAB artifacts come from `.github/workflows/android-port.yml` on push to `android-port` or on tag `v*-android*`. Nightly CI uploads APKs to a rolling `nightly-android` GitHub Release.
- Verification loop: write code → `git push` → watch CI → install the uploaded APK on a real device → iterate. Treat CI failures as the only local feedback loop.

**Patch collection**
- All Android patches live under `patches/`: `bun-android-support.patch`, `node-pty-android.patch`. Reproduce from `guysoft/opencode-termux` upstream — do not vendor patched binaries.

**Do not (during android-port work)**
- Do not introduce Sentry or any external telemetry. Logging is local-only with an "Export Logs" button in Settings.
- Do not bump the project-wide Bun version on this branch — pin in this branch's `package.json` if needed.
- Do not add CI gating, code review requirements, or multi-env release flows. Sideload distribution means a single `./gradlew assembleRelease` per ABI is the entire release pipeline.
- Do not assume the user will be online to debug. README must cover: first-launch timing (Bun + ripgrep first download), SAF directory grant, API Key entry, biometric unlock.

**Validation gates per phase**
- Phase 0 (done): environment ready, Android SDK / JDK / NDK detected, Bun installed or installation path documented.
- Phase 1: `./gradlew assembleDebug` produces a working APK from `packages/mobile`; empty Capacitor WebView loads `packages/app` build output.
- Phase 2: Kotlin sidecar starts a Bun process; reverse-proxy reaches `127.0.0.1:4096`; `packages/server` HTTP API responds from inside the WebView.
- Phase 3: PTY WebSocket reachable, ghostty-web renders ANSI output from a real shell, full session lifecycle works on a Pixel-class device.

**Build-trigger discipline — version is decided by the user, not the agent**

Before **any** action that produces an Android / iOS / desktop release artefact, the agent **must** ask the user for `versionName` and `versionCode` and wait for an explicit answer. This applies to:

- Triggering a `workflow_dispatch` workflow on `andropencode` (or any future release workflow).
- Pushing a `v*-android*` tag that releases APKs.
- Running `./gradlew assembleRelease`, `bundleRelease`, or any local release build that would emit a publishable APK / AAB / IPA / installer.
- Running `gh workflow run`, `gh release create`, or `git push` with the intent to publish.

Concretely:
1. **Do not** infer version from `git rev-parse --short HEAD`, from `bun.lock` hashes, from previous workflow runs, or from "the obvious next minor".
2. **Do not** use placeholders like `0.0.0-dev` or `0.1.0-rc1`; if the user has not spoken, the agent asks.
3. The user may answer with a `versionName` only — the agent then proposes a `versionCode` derived from that name (e.g. `0.1.1` → `101`) and confirms before triggering the build.
4. The chosen pair is then injected into `packages/mobile/scripts/patch-android.mjs` (`VERSION_NAME` / `VERSION_CODE`) for that build cycle, and committed in the same change as the workflow that consumes it.
5. After the build, the workflow must not silently overwrite the version back to a default — the `patch-android` step reads from the script's constants, and the user can change those constants before the next push.
