#!/usr/bin/env node
// Copies our Phase 1+ source from packages/mobile/android-app/ into the
// Capacitor-generated android/ tree. Run after `bunx cap add android` so the
// Kotlin code, AndroidManifest template, and res/ resources land in the
// right places.
//
// This script is idempotent — re-running it after the initial cap add just
// overwrites the same files.

import { cp, mkdir, readdir, writeFile, stat, readFile, rm } from "node:fs/promises"
import { existsSync } from "node:fs"
import { resolve, dirname, join } from "node:path"

// Resolve from packages/mobile/scripts/ up to the repo root.
// import.meta.dirname is ".../packages/mobile/scripts", so we go up three
// levels (scripts → mobile → packages → <root>).
const projectRoot = resolve(import.meta.dirname, "..", "..", "..")
const androidApp = resolve(projectRoot, "packages", "mobile", "android-app")

// User-supplied for the current build cycle. The agent is required (see
// AGENTS.md "Build-trigger discipline") to ask the user for these values
// before each release / dispatch / tag push rather than auto-incrementing
// from the previous run.
const VERSION_NAME = "0.3.0"
const VERSION_CODE = 300
// Mirror of capacitor.config.ts `appId`. The Capacitor CLI 6.2.0 template
// ships `namespace "com.getcapacitor.myapp"` + `applicationId
// "com.getcapacitor.app"` and does NOT rewrite them, so we patch the
// generated app/build.gradle to use our real application id. Keeping
// these two in sync (capacitor.config.ts and this constant) is a Phase 3
// chore we should automate.
const ANDROID_APP_ID = "ai.opencode.mobile"
const androidOut = resolve(projectRoot, "packages", "mobile", "android")

async function exists(p) {
  try {
    await stat(p)
    return true
  } catch {
    return false
  }
}

async function copyDir(src, dst) {
  await mkdir(dst, { recursive: true })
  for (const entry of await readdir(src, { withFileTypes: true })) {
    const s = join(src, entry.name)
    const d = join(dst, entry.name)
    if (entry.isDirectory()) {
      await copyDir(s, d)
    } else if (entry.isFile()) {
      await cp(s, d, { recursive: false })
      console.log(`[patch-android] copy ${s} -> ${d}`)
    }
  }
}

async function copyFile(src, dst) {
  await mkdir(dirname(dst), { recursive: true })
  await cp(src, dst)
  console.log(`[patch-android] copy ${src} -> ${dst}`)
}

// Remove the legacy mipmap-*dpi/ic_launcher*.png that `cap add android`
// emits. We replace them with the adaptive icon XML in res/mipmap-anydpi-v26
// (see android-app/res/). Keeping the PNGs around would shadow the adaptive
// icon on API 26+ launchers in some skins.
async function removeDefaultLauncherIcons() {
  const mipmapDir = resolve(androidOut, "app", "src", "main", "res")
  if (!(await exists(mipmapDir))) return
  const entries = await readdir(mipmapDir, { withFileTypes: true })
  for (const entry of entries) {
    if (!entry.isDirectory()) continue
    if (!/^mipmap-/.test(entry.name)) continue
    const dirPath = join(mipmapDir, entry.name)
    const files = await readdir(dirPath)
    for (const f of files) {
      if (f === "ic_launcher.png" || f === "ic_launcher_round.png" || f === "ic_launcher_foreground.png" || f === "ic_launcher_background.png") {
        await rm(join(dirPath, f), { force: true })
        console.log(`[patch-android] remove ${join(dirPath, f)}`)
      }
    }
  }
}

async function main() {
  if (!(await exists(androidOut))) {
    console.error(
      `[patch-android] android/ project missing at ${androidOut}. ` +
        `Run 'bunx cap add android' first (workflow does this).`
    )
    process.exit(1)
  }

  // 1. Copy Kotlin sources
  const kotlinSrc = resolve(androidApp, "kotlin")
  if (await exists(kotlinSrc)) {
    await copyDir(kotlinSrc, resolve(androidOut, "app", "src", "main", "kotlin"))
  }

  // 2. Replace AndroidManifest
  const manifestSrc = resolve(androidApp, "AndroidManifest.template.xml")
  if (await exists(manifestSrc)) {
    const manifestDst = resolve(androidOut, "app", "src", "main", "AndroidManifest.xml")
    await copyFile(manifestSrc, manifestDst)
  }

  // 3. Copy res/ additions (network_security_config.xml, file_paths.xml, …)
  const resSrc = resolve(androidApp, "res")
  if (await exists(resSrc)) {
    await copyDir(resSrc, resolve(androidOut, "app", "src", "main", "res"))
  }

  // 3a. Drop the legacy PNG launcher icons that `cap add android` ships.
  //     The adaptive XML we just copied under mipmap-anydpi-v26 takes over.
  await removeDefaultLauncherIcons()

  // 4. Copy assets/ additions (e.g. assets/server/opencode-server.js).
  //    We only copy specific subdirectories to avoid clobbering the web SPA
  //    that `cap sync` writes under assets/public.
  const assetsSrc = resolve(androidApp, "assets")
  if (await exists(assetsSrc)) {
    await copyDir(assetsSrc, resolve(androidOut, "app", "src", "main", "assets"))
  }

  // 5. Inject versionName / versionCode + rewrite namespace/applicationId
  //    on the generated app/build.gradle. The Capacitor 6.2.0 template ships
  //    with `namespace "com.getcapacitor.myapp"` and `applicationId
  //    "com.getcapacitor.app"` — both placeholders. `cap add` does NOT
  //    rewrite them, so the resulting APK is built with a Java R class in
  //    `com.getcapacitor.myapp` but installed under `ai.opencode.mobile`,
  //    which crashes the moment Capacitor's BridgeActivity tries to inflate
  //    any resource on Android 8+. We patch both lines so the namespace
  //    matches capacitor.config.ts `appId`.
  const appGradle = resolve(androidOut, "app", "build.gradle")
  if (await exists(appGradle)) {
    const before = await readFile(appGradle, "utf8")
    let after = before
    after = after.replace(/versionCode\s*=\s*\d+/, `versionCode = ${VERSION_CODE}`)
    after = after.replace(/versionName\s*=\s*"[^"]*"/, `versionName = "${VERSION_NAME}"`)
    after = after.replace(/namespace\s+"[^"]*"/, `namespace "${ANDROID_APP_ID}"`)
    after = after.replace(/applicationId\s+"[^"]*"/, `applicationId "${ANDROID_APP_ID}"`)
    if (after !== before) {
      await writeFile(appGradle, after, "utf8")
      console.log(
        `[patch-android] versionCode=${VERSION_CODE}, versionName="${VERSION_NAME}", ` +
          `namespace/applicationId=${ANDROID_APP_ID}`
      )
    } else {
      console.log("[patch-android] no version/namespace lines matched in app/build.gradle (template may have changed)")
    }
  }

  console.log("[patch-android] done.")
}

main().catch((e) => {
  console.error("[patch-android] fatal:", e)
  process.exit(1)
})