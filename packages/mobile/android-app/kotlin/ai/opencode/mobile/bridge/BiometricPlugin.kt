package ai.opencode.mobile.bridge

import android.app.Activity
import android.app.KeyguardManager
import androidx.activity.result.ActivityResult
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import ai.opencode.mobile.SecureStore

/**
 * Key bridge. API keys and small secrets are held in EncryptedSharedPreferences
 * (Keystore-backed) and are only revealed through a device-credential /
 * biometric prompt.
 *
 * JS contract:
 *   availability()                                      -> { secure, systemUnlock }
 *   requireUnlock({ title, description })                -> { unlocked }
 *   set({ key, value })                                 -> {}
 *   get({ key })                                        -> { value } | null
 *   remove({ key })                                     -> {}
 *   listKeys()                                          -> { keys: [...] }
 *
 * Keys written here with `set` are merged verbatim into the Bun server's
 * environment on the next [OpencodeService] start (see SecureStore.envForService),
 * which is how provider API keys reach the agent without code changes.
 */
@CapacitorPlugin(name = "Biometric")
class BiometricPlugin : Plugin() {

    companion object {
        private const val TAG = "Biometric"
    }

    @PluginMethod
    fun availability(call: PluginCall) {
        val km = context.getSystemService(KeyguardManager::class.java)
        val secure = km?.isDeviceSecure ?: false
        call.resolve(JSObject().put("secure", secure))
    }

    @PluginMethod
    @Suppress("DEPRECATION")
    fun requireUnlock(call: PluginCall) {
        val km = context.getSystemService(KeyguardManager::class.java)
        if (km == null || !km.isDeviceSecure) {
            call.reject("no device credential / biometric configured")
            return
        }
        val intent = km.createConfirmDeviceCredentialIntent(
            call.getString("title") ?: "Unlock OpenCode",
            call.getString("description") ?: "Unlock to access stored API keys",
        )
        if (intent == null) {
            call.reject("device credential prompt unavailable")
            return
        }
        call.setKeepAlive(true)
        startActivityForResult(call, intent, "onUnlockResult")
    }

    @ActivityCallback
    private fun onUnlockResult(call: PluginCall, result: ActivityResult) {
        if (result.resultCode == Activity.RESULT_OK) {
            call.resolve(JSObject().put("unlocked", true))
        } else {
            call.reject("unlock failed or canceled")
        }
    }

    @PluginMethod
    fun set(call: PluginCall) {
        val key = call.getString("key")
        val value = call.getString("value")
        if (key.isNullOrBlank() || value == null) {
            call.reject("key and value are required")
            return
        }
        if (!SecureStore.set(context, key, value)) {
            call.reject("secure storage unavailable")
            return
        }
        call.resolve()
    }

    @PluginMethod
    fun get(call: PluginCall) {
        val key = call.getString("key")
        if (key.isNullOrBlank()) {
            call.reject("key is required")
            return
        }
        val value = SecureStore.get(context, key)
        if (value == null) {
            call.resolve()
        } else {
            call.resolve(JSObject().put("value", value))
        }
    }

    @PluginMethod
    fun remove(call: PluginCall) {
        val key = call.getString("key")
        if (key.isNullOrBlank()) {
            call.reject("key is required")
            return
        }
        SecureStore.remove(context, key)
        call.resolve()
    }

    @PluginMethod
    fun listKeys(call: PluginCall) {
        call.resolve(JSObject().put("keys", SecureStore.keys(context).toList()))
    }
}