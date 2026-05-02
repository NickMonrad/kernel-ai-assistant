package com.kernel.ai.core.voice

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private const val TAG = "VoiceInputPrefs"
private val Context.voiceInputPrefsDataStore by preferencesDataStore(name = "voice_input_preferences")

@Singleton
class VoiceInputPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val selectedEngineKey = stringPreferencesKey("selected_voice_input_engine")

    val selectedEngine: Flow<VoiceInputEngine> = context.voiceInputPrefsDataStore.data
        .catch { e ->
            if (e is IOException) {
                Log.e(TAG, "Failed reading voice input preferences; using defaults", e)
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map { prefs -> VoiceInputEngine.fromStorage(prefs[selectedEngineKey]) }

    suspend fun setSelectedEngine(engine: VoiceInputEngine) {
        context.voiceInputPrefsDataStore.edit { prefs ->
            prefs[selectedEngineKey] = engine.name
        }
    }
}
