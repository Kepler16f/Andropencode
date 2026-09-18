#!/usr/bin/env node
// Copies our Phase 1+ source from packages/mobile/android-app/ into the
// Capacitor-generated android/ tree. Run after `bunx cap add android` so the
// Kotlin code, AndroidManifest template, and res/ resources land in the
// right places.
//
// This script is idempotent — re-running it after the initial cap add just
// overwrites the same files.

import { cp, mkdir, readdir, writeFile, stat } from "node:fs/promises"
import { existsSync } from "node:fs"
import { resolve, dirname, join } from "node:path"

// Resolve from packages/mobile/scripts/ up to the repo root.
// import.meta.dirname is ".../packages/mobile/scripts", so we go up three
// levels (scripts → mobile → packages → <root>).
const projectRoot = resolve(import.meta.dirname, "..", "..", "..")
const androidApp = resolve(projectRoot, "packages", "mobile", "android-app")
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

  console.log("[patch-android] done.")
}

main().catch((e) => {
  console.error("[patch-android] fatal:", e)
  process.exit(1)
})