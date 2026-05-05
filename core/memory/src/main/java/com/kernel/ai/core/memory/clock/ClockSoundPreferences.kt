package com.kernel.ai.core.memory.clock

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

private const val TAG = "ClockSoundPrefs"
private val Context.clockSoundPrefsDataStore by preferencesDataStore(name = "clock_sound_preferences")

@Singleton
class ClockSoundPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val defaultAlarmSoundUriKey = stringPreferencesKey("default_alarm_sound_uri")
    private val timerSoundUriKey = stringPreferencesKey("timer_sound_uri")

    val soundConfig: Flow<ClockSoundConfig> = context.clockSoundPrefsDataStore.data
        .catch { e ->
            if (e is IOException) {
                Log.e(TAG, "Failed reading clock sound preferences; using defaults", e)
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map { prefs ->
            ClockSoundConfig(
                defaultAlarmSoundUri = prefs[defaultAlarmSoundUriKey],
                timerSoundUri = prefs[timerSoundUriKey],
            )
        }

    suspend fun setDefaultAlarmSoundUri(soundUri: String?) {
        context.clockSoundPrefsDataStore.edit { prefs ->
            if (soundUri.isNullOrBlank()) prefs.remove(defaultAlarmSoundUriKey)
            else prefs[defaultAlarmSoundUriKey] = soundUri
        }
    }

    suspend fun setTimerSoundUri(soundUri: String?) {
        context.clockSoundPrefsDataStore.edit { prefs ->
            if (soundUri.isNullOrBlank()) prefs.remove(timerSoundUriKey)
            else prefs[timerSoundUriKey] = soundUri
        }
    }
}
