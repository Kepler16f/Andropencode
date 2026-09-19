package ai.opencode.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Foreground service that hosts the Bun runtime for OpenCode Mobile.
 *
 * Lifecycle:
 *   1. [start] is invoked from [MainActivity.onCreate]. Promotes to a foreground
 *      service so Android does not kill us when the activity backgrounds.
 *   2. On a worker coroutine we (a) ensure the Bun binary is present on disk
 *      via [BunDownloader], (b) spawn the Bun process with HOME=/ XDG_* env
 *      pointing at the app's filesDir, (c) bind 127.0.0.1:4096, (d) stream
 *      the server's stdout/stderr into logcat so we can diagnose Android
 *      runtime issues without a USB cable.
 *   3. While the service is alive, the WebView at
 *      http://127.0.0.1:4096 talks to the server directly.
 *
 * Phase 3: stages native assets (rg / libc++ / pty.node), seeds provider
 * keys from EncryptedSharedPreferences into the Bun environment, and serves
 * the PTY WebSocket + event stream on the same loopback.
 */
class OpencodeService : Service() {

    companion object {
        private const val TAG = "OpencodeService"
        private const val SERVER_TAG = "OpencodeServer"
        private const val CHANNEL_ID = "opencode-runtime"
        private const val NOTIF_ID = 0x10C42
        const val SERVER_PORT = 4096

        fun start(context: Context) {
            val intent = Intent(context, OpencodeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun readVersionName(context: Context): String = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (e: PackageManager.NameNotFoundException) {
            "?"
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var bunProcess: Process? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        promoteToForeground()
        scope.launch { runtimeJob() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // If the system kills us, restart with the same null intent.
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { bunProcess?.destroy() }
        super.onDestroy()
    }

    private fun promoteToForeground() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "OpenCode runtime",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the OpenCode server alive while the app is open."
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenCode")
            .setContentText("Runtime starting…")
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private suspend fun runtimeJob(): Unit = runCatching {
        // 1. Make sure the Bun binary exists locally.
        val bunBinary = BunDownloader.ensureBun(filesDir)
        if (bunBinary == null) {
            Log.e(TAG, "Bun binary unavailable — runtime not started")
            updateNotification("Bun not available")
            return@runCatching
        }

        // 2. Materialise the bundled server JS on disk.
        val serverScript = OpencodeServerInitializer.ensureServerBundle(this, filesDir)
        if (serverScript == null) {
            Log.e(TAG, "Server bundle unavailable — runtime not started")
            updateNotification("Server bundle missing")
            return@runCatching
        }

        // 2b. Stage native assets (ripgrep, pcre2, libc++, node-pty addon).
        val native = RuntimeAssets.ensureStaged(this, filesDir)

        // 3. Spawn Bun with the opencode server entry. Android's `os.homedir()`
        //    resolves to "/" or "/data" which makes opencode store config +
        //    sessions in unreachable places; we force HOME / XDG_* to the app's
        //    private filesDir. Bun is the sole listener on 127.0.0.1:4096 and
        //    the WebView connects to it directly (no proxy socket in between —
        //    a reserved ServerSocket would make Bun's bind fail with EADDRINUSE).
        val tmpDir = File(filesDir, "tmp").apply { mkdirs() }
        val pb = ProcessBuilder(
            bunBinary.absolutePath,
            "run",
            serverScript.absolutePath,
            "serve",
            "--port", SERVER_PORT.toString(),
            "--hostname", "127.0.0.1"
        ).apply {
            redirectErrorStream(true)
            // opencode relies on Bun's stdio to surface boot errors. Android's
            // Bun build drops stdio if we don't keep stdin / stdout / stderr
            // attached to something readable.
            environment()["HOME"] = filesDir.absolutePath
            environment()["TMPDIR"] = tmpDir.absolutePath
            environment()["XDG_DATA_HOME"] = "${filesDir.absolutePath}/.local/share"
            environment()["XDG_CONFIG_HOME"] = "${filesDir.absolutePath}/.config"
            environment()["XDG_STATE_HOME"] = "${filesDir.absolutePath}/.local/state"
            environment()["XDG_CACHE_HOME"] = "${filesDir.absolutePath}/.cache"
            // Android PATH has no /usr/bin; restore a sane search path and
            // include Global.Path.bin (`.cache/opencode/bin`) so `which()`
            // finds the staged rg before trying to download ripgrep.
            environment()["PATH"] = buildList {
                addAll(
                    arrayOf(
                        "/system/bin",
                        "/system/xbin",
                        "/vendor/bin",
                        "/sbin",
                        "/apex/com.android.runtime/bin",
                    ),
                )
                native.rg?.parentFile?.let { add(it.absolutePath) }
            }.joinToString(":")
            // Dynamic libs for the staged rg / node-pty addon.
            native.libDir?.let { environment()["LD_LIBRARY_PATH"] = it.absolutePath }
            // Native PTY addon (arm64-v8a only); absent -> pty.android.ts pipe fallback.
            native.ptyNode?.let { environment()["OPENCODE_NODE_PTY_PATH"] = it.absolutePath }
            // Signal to core (ripgrep/binary.ts, pty layer, watcher) that this
            // process is the Android runtime.
            environment()["OPENCODE_ANDROID"] = "1"
            // Disable optional features that touch the network or filesystem in
            // ways Android can't satisfy. mDNS in particular needs the
            // unprivileged port range + privileges we don't have.
            environment()["OPENCODE_DISABLE_MDNS"] = "1"
            environment()["OPENCODE_DISABLE_UPDATE_CHECK"] = "1"
            environment()["OPENCODE_SERVER_PASSWORD"] = "" // disable auth in dev
            // Provider API keys stored via BiometricPlugin are seeded verbatim
            // so sessions authenticate without editing app code.
            environment().putAll(SecureStore.envForService(this@OpencodeService))
        }
        bunProcess = pb.start()
        Log.i(TAG, "Bun launching opencode server (pid=${bunProcess!!.pid()})…")
        updateNotification("Server starting…")

        // Drain Bun's combined stdout+stderr into logcat so we can debug
        // failures (`adb logcat -s OpencodeServer`) without a USB shell.
        scope.launch { pumpServerLog(bunProcess!!.inputStream) }

        // Block (with timeout) until the server actually accepts TCP
        // connections on 127.0.0.1:4096. Without this the notification
        // would just say "Server starting…" forever and the user would
        // not know whether the Bun process is healthy, slow, or dead.
        try {
            waitForServerReady(SERVER_PORT, timeoutMs = 30_000)
            Log.i(TAG, "Server ready on 127.0.0.1:$SERVER_PORT (pid=${bunProcess!!.pid()})")
            updateNotification("Server ready · v" + readVersionName(this))
        } catch (e: Exception) {
            Log.e(TAG, "Server failed to start listening on 127.0.0.1:$SERVER_PORT within 30s", e)
            updateNotification("Server start failed")
            // Keep the process around so the user can read logcat; the
            // service remains alive (START_STICKY) so they can pull logs.
        }
    }.onFailure { e ->
        Log.e(TAG, "runtimeJob failed", e)
        updateNotification("Runtime error: ${e.message}")
    }.let { /* Job done — service stays alive via START_STICKY */ }

    private fun pumpServerLog(stream: java.io.InputStream) {
        BufferedReader(InputStreamReader(stream)).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                Log.i(SERVER_TAG, line)
            }
        }
    }

    /**
     * Repeatedly try to open a TCP socket to 127.0.0.1:port. Returns when
     * the socket connects; throws after [timeoutMs]. This tells us the
     * Bun process really did manage to call `listen()`, which the
     * "Server starting…" notification alone cannot distinguish from
     * "Bun is hung during module evaluation".
     */
    private suspend fun waitForServerReady(port: Int, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var attempt = 0
        while (System.currentTimeMillis() < deadline) {
            attempt++
            try {
                Socket().use { sock ->
                    sock.connect(InetSocketAddress("127.0.0.1", port), 1_000)
                }
                Log.i(TAG, "waitForServerReady: connected on attempt $attempt")
                return
            } catch (e: Exception) {
                if (attempt % 10 == 1) {
                    Log.d(TAG, "waitForServerReady: attempt $attempt, $e")
                }
                delay(500)
            }
        }
        throw RuntimeException("Server did not accept connections on 127.0.0.1:$port within ${timeoutMs}ms")
    }
}

private suspend fun OpencodeService.updateNotification(text: String) {
    val n = Notification.Builder(this, "opencode-runtime")
        .setContentTitle("OpenCode")
        .setContentText(text)
        .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
        .setOngoing(true)
        .build()
    val nm = getSystemService(NotificationManager::class.java)
    nm.notify(0x10C42, n)
}