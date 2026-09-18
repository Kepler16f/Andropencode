package ai.opencode.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import java.io.File
import java.net.ServerSocket

/**
 * Foreground service that hosts the Bun runtime for OpenCode Mobile.
 *
 * Lifecycle:
 *   1. [start] is invoked from [MainActivity.onCreate]. Promotes to a foreground
 *      service so Android does not kill us when the activity backgrounds.
 *   2. On a worker coroutine we (a) ensure the Bun binary is present on disk
 *      via [BunDownloader], (b) spawn the Bun process, and (c) wait for the
 *      server socket to bind on 127.0.0.1:4096.
 *   3. While the service is alive, the [LocalReverseProxy] inside the WebView
 *      forwards `https://opencode.local/*` requests to 127.0.0.1:4096.
 *
 * Phase 1 keeps the implementation minimal — process supervision, restart on
 * crash, and PTY plumbing land in Phase 2.
 */
class OpencodeService : Service() {

    companion object {
        private const val TAG = "OpencodeService"
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
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var bunProcess: Process? = null
    private var serverSocket: ServerSocket? = null

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
        runCatching { serverSocket?.close() }
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

        // 2. Pre-flight: bind 127.0.0.1:4096 so the WebView proxy has somewhere
        //    to forward to before Bun is ready.
        try {
            serverSocket = ServerSocket(SERVER_PORT, 0, java.net.InetAddress.getByName("127.0.0.1"))
        } catch (e: Exception) {
            Log.e(TAG, "Could not bind 127.0.0.1:$SERVER_PORT: ${e.message}")
            updateNotification("Port $SERVER_PORT unavailable")
            return@runCatching
        }

        // 3. Spawn Bun with the opencode server entry. The actual script path is
        //    wired up later; for now we run `bun --version` to prove the
        //    binary launches under Bionic.
        val pb = ProcessBuilder(bunBinary.absolutePath, "--version")
            .redirectErrorStream(true)
        bunProcess = pb.start()
        val out = bunProcess!!.inputStream.bufferedReader().readText()
        Log.i(TAG, "Bun launched: ${out.trim()}")

        updateNotification("Bun ${out.trim().lines().lastOrNull().orEmpty()}")
    }.onFailure { e ->
        Log.e(TAG, "runtimeJob failed", e)
        updateNotification("Runtime error: ${e.message}")
    }.let { /* Job done — service stays alive via START_STICKY */ }
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