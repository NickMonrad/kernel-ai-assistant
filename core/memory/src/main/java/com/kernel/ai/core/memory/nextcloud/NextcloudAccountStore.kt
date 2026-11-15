package com.kernel.ai.core.memory.nextcloud

import android.content.Context
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Non-secret account information exposed to connection/sync code. */
data class NextcloudAccount(
    val serverUrl: String,
    val username: String,
) {
    /**
     * True for an account saved before Nextcloud became HTTPS-only (#1678), when an `http://`
     * server was allowed. Such an account is never contacted or rewritten to HTTPS; the user must
     * reconnect it with an `https://` address, and local lists stay on the device meanwhile.
     */
    val requiresHttpsReconfiguration: Boolean get() = !NextcloudAddress.isHttps(serverUrl)
}

data class NextcloudAccountCredentials(
    val account: NextcloudAccount,
    val appPassword: String,
)

interface NextcloudCredentialStore {
    fun read(): NextcloudAccountCredentials?
    fun save(serverUrl: String, username: String, appPassword: String)
    fun clear()
}

/**
 * Stores the one supported Nextcloud account entirely in Android Keystore-backed preferences.
 * Nothing from this store is included in logs, Room rows, sync reports, or WorkManager input.
 */
@Singleton
class NextcloudAccountStore @Inject constructor(
    @ApplicationContext context: Context,
) : NextcloudCredentialStore {
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun read(): NextcloudAccountCredentials? {
        val serverUrl = prefs.getString(KEY_SERVER_URL, null)?.trim().orEmpty()
        val username = prefs.getString(KEY_USERNAME, null)?.trim().orEmpty()
        val appPassword = prefs.getString(KEY_APP_PASSWORD, null).orEmpty()
        if (serverUrl.isBlank() || username.isBlank() || appPassword.isBlank()) return null
        return NextcloudAccountCredentials(NextcloudAccount(serverUrl, username), appPassword)
    }

    override fun save(serverUrl: String, username: String, appPassword: String) {
        require(serverUrl.isNotBlank() && username.isNotBlank() && appPassword.isNotBlank())
        require(NextcloudAddress.isHttps(serverUrl)) { "Nextcloud accounts must use HTTPS" }
        prefs.edit {
            putString(KEY_SERVER_URL, serverUrl.trim().removeSuffix("/"))
            putString(KEY_USERNAME, username.trim())
            putString(KEY_APP_PASSWORD, appPassword)
            // Drop the retired #1551 insecure-HTTP opt-in left by older installs.
            remove(KEY_LEGACY_ALLOW_INSECURE_HTTP)
        }
    }
    override fun clear() {
        prefs.edit { clear() }
    }

    private companion object {
        const val PREFS_NAME = "nextcloud_account_secure"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_USERNAME = "username"
        const val KEY_APP_PASSWORD = "app_password"
        const val KEY_LEGACY_ALLOW_INSECURE_HTTP = "allow_insecure_http"
    }
}
