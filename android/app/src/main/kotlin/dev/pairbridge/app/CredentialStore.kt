package dev.pairbridge.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Pairing details, encrypted at rest with an Android Keystore key. */
class CredentialStore(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "pairbridge_credentials",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var host: String?
        get() = prefs.getString(KEY_HOST, null)
        set(value) = prefs.edit().putString(KEY_HOST, value).apply()

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    /** Identifies the laptop on mDNS, so it can be found again if its address changes. */
    var serverId: String?
        get() = prefs.getString(KEY_SERVER_ID, null)
        set(value) = prefs.edit().putString(KEY_SERVER_ID, value).apply()

    /** The PC's name as the laptop reports it; titles the storage root in file pickers. */
    var serverName: String?
        get() = prefs.getString(KEY_SERVER_NAME, null)
        set(value) = prefs.edit().putString(KEY_SERVER_NAME, value).apply()

    val isPaired: Boolean
        get() = !host.isNullOrBlank() && !token.isNullOrBlank()

    var sharingEnabled: Boolean
        get() = prefs.getBoolean(KEY_SHARING_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_SHARING_ENABLED, value).apply()

    fun savePairing(host: String, port: Int, token: String, serverId: String?, serverName: String?) {
        prefs.edit()
            .putString(KEY_HOST, host)
            .putInt(KEY_PORT, port)
            .putString(KEY_TOKEN, token)
            .putString(KEY_SERVER_ID, serverId)
            .putString(KEY_SERVER_NAME, serverName)
            .apply()
    }

    companion object {
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_TOKEN = "token"
        private const val KEY_SERVER_ID = "server_id"
        private const val KEY_SERVER_NAME = "server_name"
        private const val KEY_SHARING_ENABLED = "sharing_enabled"
        const val DEFAULT_PORT = 8765
    }
}
