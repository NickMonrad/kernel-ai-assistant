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
    /**
     * True only when the user explicitly enabled "Use insecure HTTP" for this account (#1551).
     * HTTPS stays the default and an HTTPS endpoint is never downgraded to HTTP without this.
     */
    val allowInsecureHttp: Boolean = false,
)

data class NextcloudAccountCredentials(
    val account: NextcloudAccount,
    val appPassword: String,
)

interface NextcloudCredentialStore {
    fun read(): NextcloudAccountCredentials?
    fun save(serverUrl: String, username: String, appPassword: String, allowInsecureHttp: Boolean = false)
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
        return NextcloudAccountCredentials(
            NextcloudAccount(serverUrl, username, prefs.getBoolean(KEY_ALLOW_INSECURE_HTTP, false)),
            appPassword,
        )
    }

    override fun save(serverUrl: String, username: String, appPassword: String, allowInsecureHttp: Boolean) {
        require(serverUrl.isNotBlank() && username.isNotBlank() && appPassword.isNotBlank())
        prefs.edit {
            putString(KEY_SERVER_URL, serverUrl.trim().removeSuffix("/"))
            putString(KEY_USERNAME, username.trim())
            putString(KEY_APP_PASSWORD, appPassword)
            putBoolean(KEY_ALLOW_INSECURE_HTTP, allowInsecureHttp)
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
        const val KEY_ALLOW_INSECURE_HTTP = "allow_insecure_http"
    }
}
