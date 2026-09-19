#!/usr/bin/env bash
# Stage the arm64-v8a native assets into packages/mobile/android-app/assets/native.
#
# These are the ONLY heavy binaries bundled into the APK, and they ship solely
# for arm64-v8a (see the ABI matrix in AGENTS.md). Everything comes from
# published, checksum-able sources:
#
#   * ripgrep + pcre2 + libc++  -> Termux main repository (aarch64 debs)
#   * pty.node                  -> node-pty-android-arm64 @ npm
#
# The workflow runs this before patch-android.mjs so the files land in the
# generated android/ tree and get packed into the APK. Skip: `--skip` dry-runs
# nothing here; failure to download fails the build loudly (better than a
# silently empty APK).

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
NATIVE_DIR="$ROOT/packages/mobile/android-app/assets/native"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

RIPGREP_DEB="pool/main/r/ripgrep/ripgrep_15.2.0_aarch64.deb"
PCRE2_DEB="pool/main/p/pcre2/pcre2_10.47_aarch64.deb"
LIBCXX_DEB="pool/main/libc/libc++/libc++_29_aarch64.deb"
REPO="https://packages.termux.dev/apt/termux-main"
NPTY_TGZ="https://registry.npmjs.org/node-pty-android-arm64/-/node-pty-android-arm64-1.1.0.tgz"

mkdir -p "$NATIVE_DIR"

echo "[native] fetching termux ripgrep/pcre2/libc++ (aarch64)…"
curl -fsSL -o "$TMP/ripgrep.deb" "$REPO/$RIPGREP_DEB"
curl -fsSL -o "$TMP/pcre2.deb" "$REPO/$PCRE2_DEB"
curl -fsSL -o "$TMP/libcxx.deb" "$REPO/$LIBCXX_DEB"
dpkg-deb -x "$TMP/ripgrep.deb" "$TMP/rg"
dpkg-deb -x "$TMP/pcre2.deb" "$TMP/pc"
dpkg-deb -x "$TMP/libcxx.deb" "$TMP/cxx"

echo "[native] fetching node-pty android prebuild…"
curl -fsSL -o "$TMP/pty.tgz" "$NPTY_TGZ"
tar -xzf "$TMP/pty.tgz" -C "$TMP"

USR="$TMP/rg/data/data/com.termux/files/usr"
cp "$USR/bin/rg" "$NATIVE_DIR/rg"
cp "$TMP/pc/data/data/com.termux/files/usr/lib/libpcre2-8.so" "$NATIVE_DIR/libpcre2-8.so"
cp "$TMP/cxx/data/data/com.termux/files/usr/lib/libc++_shared.so" "$NATIVE_DIR/libc++_shared.so"
cp "$TMP/package/prebuilds/android-arm64/pty.node" "$NATIVE_DIR/pty.node"
chmod +x "$NATIVE_DIR/rg"

echo "[native] staged:"
ls -lh "$NATIVE_DIR"
echo "[native] integrity:"
sha256sum "$NATIVE_DIR"/*