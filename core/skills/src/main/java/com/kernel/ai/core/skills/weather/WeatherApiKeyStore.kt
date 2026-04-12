package com.kernel.ai.core.skills.weather

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypted storage for the OpenWeather API key.
 *
 * IMPORTANT: [getKey], [setKey], and [clearKey] must be called from a background thread
 * (e.g. Dispatchers.IO), because the lazy [prefs] initialisation calls
 * EncryptedSharedPreferences.create() which performs disk I/O.
 */
@Singleton
class WeatherApiKeyStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs by lazy {
        EncryptedSharedPreferences.create(
            context,
            "weather_api_keys",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun getKey(): String? = prefs.getString("openweather_api_key", null)?.takeIf { it.isNotBlank() }
    fun setKey(key: String) = prefs.edit().putString("openweather_api_key", key).apply()
    fun clearKey() = prefs.edit().remove("openweather_api_key").apply()
}
