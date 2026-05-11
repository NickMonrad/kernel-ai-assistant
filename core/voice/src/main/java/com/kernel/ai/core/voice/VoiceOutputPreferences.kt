package com.kernel.ai.core.voice

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
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
    private val selectedKokoroVoiceKey = stringPreferencesKey("selected_sherpa_kokoro_voice")
    private val sherpaSpeedKey = floatPreferencesKey("sherpa_speed")
    private val voicePitchKey = floatPreferencesKey("voice_pitch")
    private val voiceGainKey = floatPreferencesKey("voice_gain")
    private val autoSpeakKey = booleanPreferencesKey("voice_auto_speak")
    private val maxSpokenSentencesKey = intPreferencesKey("voice_max_spoken_sentences")
    private val activeSpeakerIdKey = intPreferencesKey("voice_active_speaker_id")
    private val kokoroActiveSpeakerIdKey = intPreferencesKey("kokoro_active_speaker_id")

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

    val selectedKokoroVoice: Flow<SherpaKokoroVoice> = context.voiceOutputPrefsDataStore.data
        .catch { e ->
            if (e is IOException) {
                Log.e(TAG, "Failed reading voice output preferences; using defaults", e)
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map { prefs -> SherpaKokoroVoice.fromStorage(prefs[selectedKokoroVoiceKey]) }

    val sherpaSpeed: Flow<Float> = context.voiceOutputPrefsDataStore.data
        .catch { e ->
            if (e is IOException) {
                Log.e(TAG, "Failed reading voice output preferences; using defaults", e)
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map { prefs -> (prefs[sherpaSpeedKey] ?: 0.85f).coerceIn(0.5f, 1.5f) }

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

    suspend fun setSelectedKokoroVoice(voice: SherpaKokoroVoice) {
        context.voiceOutputPrefsDataStore.edit { prefs ->
            prefs[selectedKokoroVoiceKey] = voice.name
        }
    }

    suspend fun setSherpaSpeed(speed: Float) {
        context.voiceOutputPrefsDataStore.edit { prefs ->
            prefs[sherpaSpeedKey] = speed.coerceIn(0.5f, 1.5f)
        }
    }

    val voicePitch: Flow<Float> = context.voiceOutputPrefsDataStore.data
        .catch { e ->
            if (e is IOException) {
                Log.e(TAG, "Failed reading voice output preferences; using defaults", e)
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map { prefs -> (prefs[voicePitchKey] ?: 1.0f).coerceIn(0.5f, 2.0f) }

    val autoSpeak: Flow<Boolean> = context.voiceOutputPrefsDataStore.data
        .catch { e ->
            if (e is IOException) {
                Log.e(TAG, "Failed reading voice output preferences; using defaults", e)
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map { prefs -> prefs[autoSpeakKey] ?: true }

    val maxSpokenSentences: Flow<Int> = context.voiceOutputPrefsDataStore.data
        .catch { e ->
            if (e is IOException) {
                Log.e(TAG, "Failed reading voice output preferences; using defaults", e)
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map { prefs -> (prefs[maxSpokenSentencesKey] ?: 0).coerceIn(0, 10) }

    suspend fun setVoicePitch(pitch: Float) {
        context.voiceOutputPrefsDataStore.edit { prefs ->
            prefs[voicePitchKey] = pitch.coerceIn(0.5f, 2.0f)
        }
    }

    val voiceGain: Flow<Float> = context.voiceOutputPrefsDataStore.data
        .catch { e ->
            if (e is IOException) {
                Log.e(TAG, "Failed reading voice output preferences; using defaults", e)
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map { prefs -> (prefs[voiceGainKey] ?: 1.5f).coerceIn(0.5f, 3.0f) }

    suspend fun setVoiceGain(gain: Float) {
        context.voiceOutputPrefsDataStore.edit { prefs ->
            prefs[voiceGainKey] = gain.coerceIn(0.5f, 3.0f)
        }
    }

    suspend fun setAutoSpeak(enabled: Boolean) {
        context.voiceOutputPrefsDataStore.edit { prefs ->
            prefs[autoSpeakKey] = enabled
        }
    }

    suspend fun setMaxSpokenSentences(count: Int) {
        context.voiceOutputPrefsDataStore.edit { prefs ->
            prefs[maxSpokenSentencesKey] = count.coerceIn(0, 10)
        }
    }

    val activeSpeakerId: Flow<Int> = context.voiceOutputPrefsDataStore.data
        .catch { e ->
            if (e is IOException) {
                Log.e(TAG, "Failed reading voice output preferences; using defaults", e)
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map { prefs -> (prefs[activeSpeakerIdKey] ?: 0).coerceIn(0, 108) }

    suspend fun setActiveSpeakerId(sid: Int) {
        context.voiceOutputPrefsDataStore.edit { prefs ->
            prefs[activeSpeakerIdKey] = sid.coerceIn(0, 108)
        }
    }

    /** Active Kokoro speaker ID (sid 0–102 matching the 103-speaker model). Default 0. */
    val kokoroActiveSpeakerId: Flow<Int> = context.voiceOutputPrefsDataStore.data
        .catch { e ->
            if (e is IOException) {
                Log.e(TAG, "Failed reading voice output preferences; using defaults", e)
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map { prefs -> (prefs[kokoroActiveSpeakerIdKey] ?: 0).coerceIn(0, 102) }

    suspend fun setKokoroActiveSpeakerId(sid: Int) {
        context.voiceOutputPrefsDataStore.edit { prefs ->
            prefs[kokoroActiveSpeakerIdKey] = sid.coerceIn(0, 102)
        }
    }

    private val verboseLoggingKey = booleanPreferencesKey("verbose_logging_enabled")

    private val defaultVerboseLogging: Boolean
        get() = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    val verboseLogging: Flow<Boolean> = context.voiceOutputPrefsDataStore.data
        .catch { e ->
            if (e is IOException) {
                Log.e(TAG, "Failed reading voice output preferences; using defaults", e)
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map { prefs -> prefs[verboseLoggingKey] ?: defaultVerboseLogging }

    suspend fun setVerboseLogging(enabled: Boolean) {
        context.voiceOutputPrefsDataStore.edit { prefs ->
            prefs[verboseLoggingKey] = enabled
        }
    }
}
