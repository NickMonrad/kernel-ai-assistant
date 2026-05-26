package com.kernel.ai.core.voice

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private const val TAG = "WakeWordPrefs"
private val Context.wakeWordPrefsDataStore by preferencesDataStore(name = "wake_word_preferences")

/** Default detection threshold — 80% confidence required to trigger. */
const val WAKE_WORD_DEFAULT_THRESHOLD = 0.80f

@Singleton
class WakeWordPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val enabledKey = booleanPreferencesKey("hey_jandal_enabled")
    private val thresholdKey = floatPreferencesKey("hey_jandal_threshold")

    /** Whether always-on "Hey Jandal" detection is enabled. Defaults to false. */
    val heyJandalEnabled: Flow<Boolean> = context.wakeWordPrefsDataStore.data
        .catch { e ->
            if (e is IOException) {
                Log.e(TAG, "Failed reading wake word preferences; using defaults", e)
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map { prefs -> prefs[enabledKey] ?: false }

    /** Detection confidence threshold in [0, 1]. Defaults to [WAKE_WORD_DEFAULT_THRESHOLD]. */
    val confidenceThreshold: Flow<Float> = context.wakeWordPrefsDataStore.data
        .catch { e ->
            if (e is IOException) {
                Log.e(TAG, "Failed reading wake word preferences; using defaults", e)
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map { prefs -> prefs[thresholdKey] ?: WAKE_WORD_DEFAULT_THRESHOLD }

    suspend fun setHeyJandalEnabled(enabled: Boolean) {
        context.wakeWordPrefsDataStore.edit { prefs ->
            prefs[enabledKey] = enabled
        }
    }

    suspend fun setConfidenceThreshold(threshold: Float) {
        context.wakeWordPrefsDataStore.edit { prefs ->
            prefs[thresholdKey] = threshold.coerceIn(0f, 1f)
        }
    }
}
