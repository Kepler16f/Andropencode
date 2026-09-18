package ai.opencode.mobile

import android.os.Bundle
import com.getcapacitor.BridgeActivity

/**
 * OpenCode Mobile entry point. Extends Capacitor's BridgeActivity so the
 * existing SolidJS SPA is loaded into the WebView. We additionally start the
 * [OpencodeService] which hosts the Bun runtime + reverse proxy.
 *
 * Phase 1 (this file): only flips on the foreground service. Later phases
 * register Capacitor plugins (FsBridge, ShellBridge, Biometric) here.
 */
class MainActivity : BridgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Start (or bind to) the foreground service that runs Bun. Using
        // startForegroundService so the system lets us promote ourselves to
        // a foreground service from an Activity context — required on
        // Android 8+ (Oreo, API 26).
        OpencodeService.start(this)
    }
}