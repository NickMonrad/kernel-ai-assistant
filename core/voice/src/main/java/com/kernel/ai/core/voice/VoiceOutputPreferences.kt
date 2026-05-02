package com.kernel.ai.core.voice

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private const val TAG = "VoiceOutputPrefs"
private val Context.voiceOutputPrefsDataStore by preferencesDataStore(name = "voice_output_preferences")

@Singleton
class VoiceOutputPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val spokenResponsesEnabledKey =
        booleanPreferencesKey("quick_actions_spoken_responses_enabled")

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

    suspend fun setSpokenResponsesEnabled(enabled: Boolean) {
        context.voiceOutputPrefsDataStore.edit { prefs ->
            prefs[spokenResponsesEnabledKey] = enabled
        }
    }
}
