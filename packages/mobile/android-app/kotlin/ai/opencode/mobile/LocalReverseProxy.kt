package ai.opencode.mobile

import android.util.Log
import androidx.webkit.WebViewAssetLoader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Serves `https://opencode.local/*` WebView requests by transparently
 * forwarding them to `http://127.0.0.1:4096/*` (where the Bun server is
 * listening).
 *
 * Phase 2 built a direct cleartext loopback instead (`capacitor.config.ts`
 * uses scheme http + hostname 127.0.0.1), so this class is currently NOT
 * wired into [MainActivity]. Phase 3 kept that arrangement: the SPA loads
 * from `http://127.0.0.1` and talks to the server on `ws://127.0.0.1:4096` —
 * same-origin, no CORS, no proxy hop.
 *
 * If a future phase returns to `https://opencode.local`, note that
 * [WebViewAssetLoader.PathHandler.handle] only receives the requested path
 * (GET semantics): WebSockets, POST bodies, and request headers cannot pass
 * through a PathHandler. Those need a WebViewClient shouldInterceptRequest
 * with a streaming URLConnection mirroring method/body/headers.
 */
class LocalReverseProxy(private val upstreamPort: Int) : WebViewAssetLoader.PathHandler {

    override fun handle(path: String): WebViewAssetLoader.WebResource? = runCatching {
        val upstream = URL("http", "127.0.0.1", upstreamPort, "/" + path.removePrefix("/"))
        val conn = upstream.openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 30_000
        conn.requestMethod = "GET"
        conn.connect()
        WebViewAssetLoader.WebResource(
            conn.inputStream,
            conn.contentType ?: "application/octet-stream",
            null,
        )
    }.getOrElse { e ->
        Log.w("LocalReverseProxy", "Failed to fetch $path: ${e.message}")
        null
    }
}