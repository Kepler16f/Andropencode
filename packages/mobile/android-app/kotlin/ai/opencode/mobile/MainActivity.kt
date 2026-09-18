package ai.opencode.mobile

import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
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

        // Surface version info so the user (and adb logcat) can confirm
        // the APK they're running matches what was built. We also toast it
        // briefly so a quick visual check works without extra tooling.
        val versionName = readVersionName()
        val versionCode = readVersionCode()
        Log.i(TAG, "AndrOpencode v$versionName (build $versionCode) starting…")
        Toast.makeText(this, "AndrOpencode v$versionName ($versionCode)", Toast.LENGTH_LONG).show()

        // Start (or bind to) the foreground service that runs Bun. Using
        // startForegroundService so the system lets us promote ourselves to
        // a foreground service from an Activity context — required on
        // Android 8+ (Oreo, API 26).
        OpencodeService.start(this)
    }

    private fun readVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: PackageManager.NameNotFoundException) {
        "?"
    }

    private fun readVersionCode(): Int = try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionCode
        }
    } catch (e: PackageManager.NameNotFoundException) {
        0
    }

    private companion object {
        private const val TAG = "AndrOpencode"
    }
}