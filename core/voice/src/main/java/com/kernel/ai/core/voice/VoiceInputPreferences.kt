package com.kernel.ai.core.voice

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.kernel.ai.core.voice.asr4all.Asr4AllSize
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private const val TAG = "VoiceInputPrefs"
private val Context.voiceInputPrefsDataStore by preferencesDataStore(name = "voice_input_preferences")

@Singleton
class VoiceInputPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val isReleaseBuild: Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0

    /** v0.1 release excludes the custom Sherpa/Piper/eSpeak runtime; debug retains it. */
    internal fun allowsSherpaRuntime(): Boolean = !isReleaseBuild
    private val selectedEngineKey = stringPreferencesKey("selected_voice_input_engine")
    private val micUseCountKey = intPreferencesKey("mic_use_count")
    private val autoStartAlertVoiceCommandsEnabledKey =
        booleanPreferencesKey("auto_start_alert_voice_commands_enabled")
    private val allowOnlineSpeechFallbackKey =
        booleanPreferencesKey("allow_online_speech_fallback")
    private val asr4allSizeKey = stringPreferencesKey("asr4all_model_size")
    private val asr4allPendingSizeKey = stringPreferencesKey("asr4all_pending_model_size")

    val selectedEngine: Flow<VoiceInputEngine> = context.voiceInputPrefsDataStore.data
        .catch { e ->
            if (e is IOException) {
                Log.e(TAG, "Failed reading voice input preferences; using defaults", e)
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map { prefs -> VoiceInputEngine.resolveForBuild(prefs[selectedEngineKey], isReleaseBuild) }

    /** True once an input engine has been stored, by the user or an earlier build (#1855 part 2). */
    suspend fun hasStoredEngine(): Boolean =
        context.voiceInputPrefsDataStore.data.first()[selectedEngineKey] != null

    val autoStartAlertVoiceCommandsEnabled: Flow<Boolean> = context.voiceInputPrefsDataStore.data
        .catch { e ->
            if (e is IOException) {
                Log.e(TAG, "Failed reading voice input preferences; using defaults", e)
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map { prefs -> prefs[autoStartAlertVoiceCommandsEnabledKey] ?: true }

    /**
     * Whether Android native recognition may fall back to the platform (Google) recognition
     * service, which can send audio off the device. Off by default so voice input stays local.
     */
    val allowOnlineSpeechFallback: Flow<Boolean> = context.voiceInputPrefsDataStore.data
        .catch { e ->
            if (e is IOException) {
                Log.e(TAG, "Failed reading voice input preferences; using defaults", e)
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map { prefs -> prefs[allowOnlineSpeechFallbackKey] ?: false }

    suspend fun setAllowOnlineSpeechFallback(allowed: Boolean) {
        context.voiceInputPrefsDataStore.edit { prefs ->
            prefs[allowOnlineSpeechFallbackKey] = allowed
        }
    }

    /** The asr4all size the user chose; [Asr4AllInstall.effectiveSize] applies the tier policy. */
    val asr4allSize: Flow<Asr4AllSize> = context.voiceInputPrefsDataStore.data
        .catch { e ->
            if (e is IOException) {
                Log.e(TAG, "Failed reading voice input preferences; using defaults", e)
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map { prefs ->
            prefs[asr4allSizeKey]?.let { stored -> Asr4AllSize.entries.firstOrNull { it.name == stored } }
                ?: Asr4AllInstall.defaultSize
        }

    suspend fun setAsr4allSize(size: Asr4AllSize) {
        context.voiceInputPrefsDataStore.edit { prefs ->
            prefs[asr4allSizeKey] = size.name
        }
    }

    /** A size whose download is pending a switch; null when none. Survives a process restart. */
    val asr4allPendingSize: Flow<Asr4AllSize?> = context.voiceInputPrefsDataStore.data
        .catch { e ->
            if (e is IOException) {
                Log.e(TAG, "Failed reading voice input preferences; using defaults", e)
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map { prefs ->
            prefs[asr4allPendingSizeKey]?.let { stored -> Asr4AllSize.entries.firstOrNull { it.name == stored } }
        }

    suspend fun setAsr4allPendingSize(size: Asr4AllSize?) {
        context.voiceInputPrefsDataStore.edit { prefs ->
            if (size == null) prefs.remove(asr4allPendingSizeKey) else prefs[asr4allPendingSizeKey] = size.name
        }
    }

    suspend fun setSelectedEngine(engine: VoiceInputEngine) {
        val effective = VoiceInputEngine.resolveForBuild(engine.name, isReleaseBuild)
        context.voiceInputPrefsDataStore.edit { prefs ->
            prefs[selectedEngineKey] = effective.name
        }
    }

    suspend fun setAutoStartAlertVoiceCommandsEnabled(enabled: Boolean) {
        context.voiceInputPrefsDataStore.edit { prefs ->
            prefs[autoStartAlertVoiceCommandsEnabledKey] = enabled
        }
    }

    /**
     * Increments and returns the persisted count of mic uses (#1850), once per user tap. The
     * count saturates at [MicHintPolicy.MAX_HINT_USES] + 1, so the hint is shown for uses
     * 1..[MicHintPolicy.MAX_HINT_USES] and hidden from then on; no writes after that.
     */
    suspend fun recordMicUseStarted(): Int {
        var count = 0
        context.voiceInputPrefsDataStore.edit { prefs ->
            val stored = prefs[micUseCountKey] ?: 0
            count = MicHintPolicy.nextUseCount(stored)
            if (count != stored) prefs[micUseCountKey] = count
        }
        return count
    }
}
