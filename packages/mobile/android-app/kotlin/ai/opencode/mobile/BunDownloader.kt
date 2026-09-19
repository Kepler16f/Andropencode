package ai.opencode.mobile

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Ensures the pinned Bun binary (`bun-v1.3.14`, matching packageManager) is
 * present under `<filesDir>/bun/`. If missing we download the official
 * android archive (`bun-linux-aarch64-android.zip`) from oven-sh/bun and
 * extract it. The device's own `/system/bin/unzip` handles extraction; the
 * archive unzips as `bun-linux-aarch64-android/bun`, so we walk the tree
 * rather than match a single member name.
 *
 * Phase 1 keeps it simple (no resume, no checksum verification). Phase 3 note:
 * this is a real dependency of an ONLINE first launch — a user in airplane
 * mode gets a "Bun not available" notification, which the README documents.
 */
object BunDownloader {

    private const val TAG = "BunDownloader"
    private const val VERSION = "1.3.14"
    private const val TARGET_DIR = "bun"
    private const val FILE_NAME = "bun"

    /**
     * @return path to a Bun executable, or `null` when the binary could not
     *         be obtained (no network, archive missing, etc.).
     */
    suspend fun ensureBun(filesDir: File): File? = withContext(Dispatchers.IO) {
        val target = File(filesDir, "$TARGET_DIR/$FILE_NAME")
        if (target.exists() && target.canExecute()) {
            Log.i(TAG, "Bun already present: ${target.absolutePath}")
            return@withContext target
        }

        target.parentFile?.mkdirs()
        val archive = File(filesDir, "bun-${VERSION}.zip")
        if (!archive.exists()) {
            val ok = download(archive)
            if (!ok) return@withContext null
        }

        if (!extract(archive, target.parentFile!!)) {
            return@withContext null
        }
        target.setExecutable(true)
        target
    }

    private fun download(dest: File): Boolean {
        val src = "https://github.com/oven-sh/bun/releases/download/bun-v$VERSION/bun-linux-aarch64-android.zip"
        Log.i(TAG, "Downloading Bun from $src")
        return runCatching {
            val conn = URL(src).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            }
            true
        }.getOrElse {
            Log.e(TAG, "Bun download failed: ${it.message}")
            false
        }
    }

    private fun extract(archive: File, intoDir: File): Boolean {
        // The official android archive extracts to `bun-linux-aarch64-android/bun`,
        // so unzip everything and locate the binary instead of matching one
        // member name. We rely on the system `unzip` binary present on all
        // Android devices (BunDownloader already leans on it for Phase 1).
        return runCatching {
            val pb = ProcessBuilder("/system/bin/unzip", "-o", "-q", archive.absolutePath, "-d", intoDir.absolutePath)
                .redirectErrorStream(true)
            val p = pb.start()
            if (p.waitFor() != 0) {
                Log.e(TAG, "unzip exited with ${p.exitValue()}")
                return@runCatching false
            }
            val found = walkForBun(intoDir)
            if (found == null) {
                Log.e(TAG, "no bun binary found under ${intoDir.absolutePath}")
                return@runCatching false
            }
            val target = File(intoDir, FILE_NAME)
            if (found.absolutePath != target.absolutePath) {
                found.copyTo(target, overwrite = true)
                found.delete()
            }
            target.setExecutable(true)
            true
        }.getOrElse {
            Log.e(TAG, "unzip failed: ${it.message}")
            false
        }
    }

    private fun walkForBun(dir: File, depth: Int = 0): File? {
        if (depth > 3) return null
        dir.listFiles()?.forEach { entry ->
            if (entry.isFile && entry.name == FILE_NAME) return entry
            if (entry.isDirectory) walkForBun(entry, depth + 1)?.let { return it }
        }
        return null
    }
}