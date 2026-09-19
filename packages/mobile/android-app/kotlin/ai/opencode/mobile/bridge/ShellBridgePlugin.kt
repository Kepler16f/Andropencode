package ai.opencode.mobile.bridge

import android.util.Log
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Process bridge. Lets the SPA enumerate the device's shells and run one-shot
 * commands (used by the non-interactive shell fallback tooling and by
 * diagnostics). Keep the surface minimal: short commands, bounded output,
 * explicit timeout.
 *
 * JS contract:
 *   shells()                    -> { shells: [{ path, name }] }
 *   exec({ command, cwd, timeoutMs })  -> { code, stdout, stderr, durationMs }
 */
@CapacitorPlugin(name = "ShellBridge")
class ShellBridgePlugin : Plugin() {

    companion object {
        private const val TAG = "ShellBridge"
        private const val MAX_OUTPUT = 1_048_576 // 1 MiB cap per stream
        private val CANDIDATES =
            listOf(
                "/system/bin/sh" to "sh",
                "/system/bin/mksh" to "mksh",
                "/bin/sh" to "sh",
                "/vendor/bin/sh" to "sh",
                "/system/bin/bash" to "bash",
            )
    }

    @PluginMethod
    fun shells(call: PluginCall) {
        val shells = JSArray()
        for ((path, name) in CANDIDATES) {
            if (File(path).isFile) {
                shells.put(JSObject().apply { put("path", path); put("name", name) })
            }
        }
        call.resolve(JSObject().put("shells", shells))
    }

    @PluginMethod
    fun exec(call: PluginCall) {
        val command = call.getString("command")
        if (command.isNullOrBlank()) {
            call.reject("command is required")
            return
        }
        val cwd = call.getString("cwd")
        val timeoutMs = call.getInt("timeoutMs") ?: 30_000
        val builder = ProcessBuilder("/system/bin/sh", "-c", command)
            .redirectErrorStream(false)
        cwd?.let { runCatching { builder.directory(File(it)) } }
        builder.environment().put("TMPDIR", File(context.cacheDir, "sh").apply { mkdirs() }.absolutePath)

        Thread {
            val started = System.currentTimeMillis()
            runCatching {
                val process = builder.start()
                val stdout = process.inputStream
                val stderr = process.errorStream
                val finished = process.waitFor(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    process.waitFor()
                }
                val out = readBounded(stdout)
                val err = readBounded(stderr)
                val durationMs = System.currentTimeMillis() - started
                call.resolve(
                    JSObject()
                        .put("code", process.exitValue())
                        .put("stdout", out)
                        .put("stderr", err)
                        .put("durationMs", durationMs.toDouble()),
                )
            }.onFailure { e ->
                Log.w(TAG, "exec failed: ${e.message}", e)
                call.reject("exec failed: ${e.message}")
            }
        }.apply { isDaemon = true }.start()
    }

    private fun readBounded(stream: java.io.InputStream): String {
        val buffer = java.io.ByteArrayOutputStream(8192)
        val chunk = ByteArray(4096)
        while (buffer.size() < MAX_OUTPUT) {
            val read = stream.read(chunk)
            if (read < 0) break
            val space = MAX_OUTPUT - buffer.size()
            buffer.write(chunk, 0, minOf(read, space))
        }
        return buffer.toString(Charsets.UTF_8.name())
    }
}