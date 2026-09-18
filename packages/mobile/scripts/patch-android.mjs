#!/usr/bin/env node
// Copies our Phase 1+ source from packages/mobile/android-app/ into the
// Capacitor-generated android/ tree. Run after `bunx cap add android` so the
// Kotlin code, AndroidManifest template, and res/ resources land in the
// right places.
//
// This script is idempotent — re-running it after the initial cap add just
// overwrites the same files.

import { cp, mkdir, readdir, writeFile, stat, readFile } from "node:fs/promises"
import { existsSync } from "node:fs"
import { resolve, dirname, join } from "node:path"

// Resolve from packages/mobile/scripts/ up to the repo root.
// import.meta.dirname is ".../packages/mobile/scripts", so we go up three
// levels (scripts → mobile → packages → <root>).
const projectRoot = resolve(import.meta.dirname, "..", "..", "..")
const androidApp = resolve(projectRoot, "packages", "mobile", "android-app")

// Hard-coded for now — Phase 3 should read these from package.json or a
// dedicated version manifest that the user maintains alongside the source.
const VERSION_NAME = "0.1.1"
const VERSION_CODE = 101
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

  // 4. Copy assets/ additions (e.g. assets/server/opencode-server.js).
  //    We only copy specific subdirectories to avoid clobbering the web SPA
  //    that `cap sync` writes under assets/public.
  const assetsSrc = resolve(androidApp, "assets")
  if (await exists(assetsSrc)) {
    await copyDir(assetsSrc, resolve(androidOut, "app", "src", "main", "assets"))
  }

  // 5. Inject versionName / versionCode into the generated app/build.gradle
  //    so the APK reports the user-facing version. Capacitor's default
  //    template ships versionCode = 1, versionName = "1.0" which is what
  //    Google Play would publish if we forgot to override it.
  const appGradle = resolve(androidOut, "app", "build.gradle")
  if (await exists(appGradle)) {
    const before = await readFile(appGradle, "utf8")
    let after = before
    after = after.replace(/versionCode\s*=\s*\d+/, `versionCode = ${VERSION_CODE}`)
    after = after.replace(/versionName\s*=\s*"[^"]*"/, `versionName = "${VERSION_NAME}"`)
    if (after !== before) {
      await writeFile(appGradle, after, "utf8")
      console.log(`[patch-android] versionCode=${VERSION_CODE}, versionName="${VERSION_NAME}"`)
    } else {
      console.log("[patch-android] versionCode/versionName not found in app/build.gradle (template may have changed)")
    }
  }

  console.log("[patch-android] done.")
}

main().catch((e) => {
  console.error("[patch-android] fatal:", e)
  process.exit(1)
})