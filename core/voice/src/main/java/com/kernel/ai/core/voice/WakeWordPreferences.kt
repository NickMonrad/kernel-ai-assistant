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

/** Detection fires immediately when confidence ≥ this value. */
const val WAKE_WORD_DEFAULT_THRESHOLD = 0.80f

/**
 * Secondary (lower) threshold for the dual-threshold verification path.
 *
 * When confidence is in [LOW_THRESHOLD, HIGH_THRESHOLD), the detector captures the
 * last [WAKE_WORD_VERIFY_WINDOW_S] seconds of PCM and passes it to the [verifyWindow]
 * callback supplied to [WakeWordDetector.start].  If no verifier is wired, this
 * secondary band is ignored.
 *
 * Default of 0.50 is a conservative starting point; tune down after real-device
 * false positive measurements (issue #986).
 */
const val WAKE_WORD_DEFAULT_LOW_THRESHOLD = 0.50f

/** Duration of PCM retained in the pre-detection ring buffer for verification (seconds). */
const val WAKE_WORD_VERIFY_WINDOW_S = 3

@Singleton
class WakeWordPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val enabledKey = booleanPreferencesKey("hey_jandal_enabled")
    private val thresholdKey = floatPreferencesKey("hey_jandal_threshold")
    private val lowThresholdKey = floatPreferencesKey("hey_jandal_low_threshold")

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

    /**
     * Lower threshold for the dual-threshold verification path.
     * Confidence in [lowConfidenceThreshold, confidenceThreshold) triggers STT verification
     * rather than immediate activation.  Defaults to [WAKE_WORD_DEFAULT_LOW_THRESHOLD].
     */
    val lowConfidenceThreshold: Flow<Float> = context.wakeWordPrefsDataStore.data
        .catch { e ->
            if (e is IOException) {
                Log.e(TAG, "Failed reading wake word preferences; using defaults", e)
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map { prefs -> prefs[lowThresholdKey] ?: WAKE_WORD_DEFAULT_LOW_THRESHOLD }

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

    suspend fun setLowConfidenceThreshold(threshold: Float) {
        context.wakeWordPrefsDataStore.edit { prefs ->
            prefs[lowThresholdKey] = threshold.coerceIn(0f, 1f)
        }
    }
}
