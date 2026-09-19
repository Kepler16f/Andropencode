package ai.opencode.mobile

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted storage for API keys and small secrets behind
 * Android Keystore-backed master key (AES-256-GCM). Used by
 * [BiometricPlugin] on the JS side and by [OpencodeService] when seeding the
 * Bun server's environment.
 */
object SecureStore {

    private const val TAG = "SecureStore"
    private const val PREFS = "opencode_secure"
    private const val PREFIX = "env:"

    private fun prefs(context: Context) = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        Log.e(TAG, "EncryptedSharedPreferences init failed", e)
        null
    }

    fun set(context: Context, key: String, value: String): Boolean {
        val prefs = prefs(context) ?: return false
        return prefs.edit().putString(PREFIX + key, value).commit()
    }

    fun get(context: Context, key: String): String? = prefs(context)?.getString(PREFIX + key, null)

    fun remove(context: Context, key: String) {
        prefs(context)?.edit()?.remove(PREFIX + key)?.apply()
    }

    fun keys(context: Context): Set<String> =
        (prefs(context)?.all?.keys.orEmpty()).filter { it.startsWith(PREFIX) }.map { it.removePrefix(PREFIX) }.toSet()

    fun envForService(context: Context): Map<String, String> =
        keys(context).mapNotNull { key -> get(context, key)?.let { value -> key to value } }.toMap()
}