package com.kernel.ai.core.memory.clock

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private const val TAG = "ClockAlertPrefs"
private val Context.clockAlertPrefsDataStore by preferencesDataStore(name = "clock_alert_preferences")

@Singleton
class ClockAlertPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val timerAutoStopDurationMsKey = longPreferencesKey("timer_auto_stop_duration_ms")
    private val alarmRingDurationMsKey = longPreferencesKey("alarm_ring_duration_ms")
    private val snoozeDurationMsKey = longPreferencesKey("snooze_duration_ms")
    private val maxAutoSnoozesKey = intPreferencesKey("max_auto_snoozes")

    val alertConfig: Flow<ClockAlertConfig> = context.clockAlertPrefsDataStore.data
        .catch { e ->
            if (e is IOException) {
                Log.e(TAG, "Failed reading clock alert preferences; using defaults", e)
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map { prefs ->
            ClockAlertConfig(
                timerAutoStopDurationMs = prefs[timerAutoStopDurationMsKey] ?: 60_000L,
                alarmRingDurationMs = prefs[alarmRingDurationMsKey] ?: 60_000L,
                snoozeDurationMs = prefs[snoozeDurationMsKey] ?: 600_000L,
                maxAutoSnoozes = prefs[maxAutoSnoozesKey] ?: 1,
            )
        }

    suspend fun setTimerAutoStopDurationMs(value: Long) {
        context.clockAlertPrefsDataStore.edit { prefs ->
            prefs[timerAutoStopDurationMsKey] = value
        }
    }

    suspend fun setAlarmRingDurationMs(value: Long) {
        context.clockAlertPrefsDataStore.edit { prefs ->
            prefs[alarmRingDurationMsKey] = value
        }
    }

    suspend fun setSnoozeDurationMs(value: Long) {
        context.clockAlertPrefsDataStore.edit { prefs ->
            prefs[snoozeDurationMsKey] = value
        }
    }

    suspend fun setMaxAutoSnoozes(value: Int) {
        context.clockAlertPrefsDataStore.edit { prefs ->
            prefs[maxAutoSnoozesKey] = value
        }
    }
}
