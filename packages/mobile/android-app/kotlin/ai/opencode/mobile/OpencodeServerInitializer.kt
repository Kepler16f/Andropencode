package ai.opencode.mobile

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Copies the bundled server JS out of the APK's read-only assets/ into the
 * app's writable `filesDir/` so Bun can `bun run` it. APK assets cannot be
 * executed in place on Android — they have to be materialised on disk.
 *
 * Phase 2 ships the server as a single-file Bun bundle that the CI workflow
 * produces with `bun build packages/opencode/src/cli/cmd/serve.ts
 * --target=bun --outfile=…`. The result lives at `assets/server/
 * opencode-server.js` inside the APK.
 *
 * Phase 3+ should switch to downloading the bundle on first launch (smaller
 * APK, faster hotfix rollouts) and verify its SHA-256 before executing.
 */
object OpencodeServerInitializer {

    private const val TAG = "OpencodeServerInit"
    private const val ASSET_PATH = "server/opencode-server.js"
    private const val TARGET_DIR = "server"
    private const val TARGET_FILE = "opencode-server.js"

    /**
     * @return path to the materialized server JS, or `null` on extraction
     *         failure (missing asset, write error, etc.).
     */
    fun ensureServerBundle(context: Context, filesDir: File): File? {
        val target = File(filesDir, "$TARGET_DIR/$TARGET_FILE")
        if (target.exists() && target.length() > 0) {
            Log.i(TAG, "Server bundle already present: ${target.absolutePath} (${target.length()} bytes)")
            return target
        }

        target.parentFile?.mkdirs()

        try {
            context.assets.open(ASSET_PATH).use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
            Log.i(TAG, "Extracted server bundle: ${target.absolutePath} (${target.length()} bytes)")
            return target
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract server bundle: ${e.message}")
            return null
        }
    }
}