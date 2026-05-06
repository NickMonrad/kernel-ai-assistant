package com.kernel.ai.core.voice

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private const val TAG = "KernelAI"
private val Context.voiceOutputPrefsDataStore by preferencesDataStore(name = "voice_output_preferences")

@Singleton
class VoiceOutputPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val spokenResponsesEnabledKey =
        booleanPreferencesKey("quick_actions_spoken_responses_enabled")
    private val selectedEngineKey = stringPreferencesKey("selected_voice_output_engine")
    private val selectedSherpaVoiceKey = stringPreferencesKey("selected_sherpa_piper_voice")
    private val sherpaSpeedKey = floatPreferencesKey("sherpa_speed")

    val spokenResponsesEnabled: Flow<Boolean> = context.voiceOutputPrefsDataStore.data
        .catch { e ->
            if (e is IOException) {
                Log.e(TAG, "Failed reading voice output preferences; using defaults", e)
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map { prefs -> prefs[spokenResponsesEnabledKey] ?: true }

    val selectedEngine: Flow<VoiceOutputEngine> = context.voiceOutputPrefsDataStore.data
        .catch { e ->
            if (e is IOException) {
                Log.e(TAG, "Failed reading voice output preferences; using defaults", e)
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map { prefs -> VoiceOutputEngine.fromStorage(prefs[selectedEngineKey]) }

    val selectedSherpaVoice: Flow<SherpaPiperVoice> = context.voiceOutputPrefsDataStore.data
        .catch { e ->
            if (e is IOException) {
                Log.e(TAG, "Failed reading voice output preferences; using defaults", e)
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map { prefs -> SherpaPiperVoice.fromStorage(prefs[selectedSherpaVoiceKey]) }

    val sherpaSpeed: Flow<Float> = context.voiceOutputPrefsDataStore.data
        .catch { e ->
            if (e is IOException) {
                Log.e(TAG, "Failed reading voice output preferences; using defaults", e)
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map { prefs -> prefs[sherpaSpeedKey] ?: 0.85f }

    suspend fun setSpokenResponsesEnabled(enabled: Boolean) {
        context.voiceOutputPrefsDataStore.edit { prefs ->
            prefs[spokenResponsesEnabledKey] = enabled
        }
    }

    suspend fun setSelectedEngine(engine: VoiceOutputEngine) {
        context.voiceOutputPrefsDataStore.edit { prefs ->
            prefs[selectedEngineKey] = engine.name
        }
    }

    suspend fun setSelectedSherpaVoice(voice: SherpaPiperVoice) {
        context.voiceOutputPrefsDataStore.edit { prefs ->
            prefs[selectedSherpaVoiceKey] = voice.name
        }
    }

    suspend fun setSherpaSpeed(speed: Float) {
        context.voiceOutputPrefsDataStore.edit { prefs ->
            prefs[sherpaSpeedKey] = speed.coerceIn(0.5f, 1.5f)
        }
    }
}
