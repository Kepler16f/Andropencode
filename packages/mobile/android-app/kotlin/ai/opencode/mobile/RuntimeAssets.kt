package ai.opencode.mobile

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Stages the native assets that CI bundles into the APK under assets/native/:
 *
 *   * rg                     – Termux ripgrep (aarch64-linux-android)
 *   * libpcre2-8.so          – Termux pcre2 shared lib, needed by rg
 *   * libc++_shared.so       – needed by the node-pty NAPI addon
 *   * pty.node               – node-pty-android-arm64 prebuild (NAPI)
 *
 * The runtime layout mirrors what the Bun server expects:
 *   * `<filesDir>/.cache/opencode/bin/rg` matches `Global.Path.bin` so the
 *     `which("rg")` check in RipgrepBinary finds the bundled binary and never
 *     attempts a GitHub download (upstream publishes no Android artifact).
 *   * `LD_LIBRARY_PATH` points at `<filesDir>/lib`.
 *   * `OPENCODE_NODE_PTY_PATH` points at the staged addon for pty.android.ts.
 *
 * Assets are arm64-v8a only. On other ABIs the assets are absent and the
 * server falls back to a non-interactive shell pipe (see README).
 */
object RuntimeAssets {

    private const val TAG = "RuntimeAssets"
    private const val ASSET_DIR = "native"

    data class StagedAssets(
        val rg: File?,
        val libDir: File?,
        val ptyNode: File?,
    )

    fun ensureStaged(context: Context, filesDir: File): StagedAssets {
        val binDir = File(filesDir, ".cache/opencode/bin").apply { mkdirs() }
        val libDir = File(filesDir, "lib").apply { mkdirs() }
        val nativeDir = File(filesDir, "native").apply { mkdirs() }

        val rg = stageExecutable(context, File(binDir, "rg"))
        stageAsset(context, "libpcre2-8.so", File(libDir, "libpcre2-8.so"))
        stageAsset(context, "libc++_shared.so", File(libDir, "libc++_shared.so"))
        val ptyNode = stageAsset(context, "pty.node", File(nativeDir, "pty.node"))

        if (rg == null) Log.i(TAG, "no staged rg binary (non-arm64 build or missing asset) — will fall back to download/no-op")
        if (ptyNode == null) Log.i(TAG, "no staged pty.node (non-arm64 build or missing asset) — PTY falls back to pipe mode")

        return StagedAssets(rg = rg, libDir = if (libDir.exists()) libDir else null, ptyNode = ptyNode)
    }

    private fun stageAsset(context: Context, name: String, target: File): File? = runCatching {
        context.assets.open("$ASSET_DIR/$name").use { input ->
            if (target.isFile && target.length() > 0) {
                return@runCatching target
            }
            target.parentFile?.mkdirs()
            FileOutputStream(target).use { output -> input.copyTo(output) }
            Log.i(TAG, "staged $name -> ${target.absolutePath} (${target.length()} bytes)")
            target
        }
    }.getOrElse {
        Log.w(TAG, "failed to stage $name: ${it.message}")
        null
    }

    private fun stageExecutable(context: Context, target: File): File? =
        stageAsset(context, target.name, target)?.apply { setExecutable(true) }
}