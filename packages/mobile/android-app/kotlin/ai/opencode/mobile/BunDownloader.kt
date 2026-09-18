package ai.opencode.mobile

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Ensures a Bun binary matching the device's ABI is present under
 * `<filesDir>/bun/`. If the file is missing we download the latest published
 * `bun-linux-aarch64` tarball from GitHub.
 *
 * Phase 1 keeps it simple (no resume, no checksum verification). Phase 2
 * should switch to an integrity-checked flow: SHA-256 the archive, optionally
 * pin the version, surface download progress to the user.
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
        val src = "https://github.com/oven-sh/bun/releases/download/bun-v$VERSION/bun-linux-aarch64.zip"
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
        // We rely on the system `unzip` binary present on all Android devices.
        // The alternative (java.util.zip.ZipInputStream) works too but is more
        // verbose for a single-file archive.
        return runCatching {
            val pb = ProcessBuilder("/system/bin/unzip", "-o", "-q", archive.absolutePath, FILE_NAME, "-d", intoDir.absolutePath)
                .redirectErrorStream(true)
            val p = pb.start()
            val exited = p.waitFor()
            if (exited != 0) {
                Log.e(TAG, "unzip exited with $exited")
                return@runCatching false
            }
            true
        }.getOrElse {
            Log.e(TAG, "unzip failed: ${it.message}")
            false
        }
    }
}