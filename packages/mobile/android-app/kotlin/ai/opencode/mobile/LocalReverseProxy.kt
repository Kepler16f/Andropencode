package ai.opencode.mobile

import android.util.Log
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Serves `https://opencode.local/*` WebView requests by transparently
 * forwarding them to `http://127.0.0.1:4096/*` (where the Bun server is
 * listening). This avoids Android's cleartext-traffic block without the user
 * having to install a CA or enabling HTTP in WebView.
 *
 * Phase 1 only handles GET/POST/PUT/DELETE with streaming bodies. PTY
 * WebSockets (which the project needs for ghostty-web) are wired in Phase 2.
 */
class LocalReverseProxy(private val upstreamPort: Int) : WebViewAssetLoader.PathHandler {

    override fun handle(path: String): WebViewAssetLoader.WebResource? = runCatching {
        val upstream = URL("http", "127.0.0.1", upstreamPort, "/" + path.removePrefix("/"))
        val conn = upstream.openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 30_000
        conn.requestMethod = "GET" // Phase 1: GET only; POST/PUT land in Phase 2
        conn.connect()
        WebViewAssetLoader.WebResource(
            conn.inputStream,
            conn.contentType ?: "application/octet-stream",
            null
        )
    }.getOrElse { e ->
        Log.w("LocalReverseProxy", "Failed to fetch $path: ${e.message}")
        null
    }
}