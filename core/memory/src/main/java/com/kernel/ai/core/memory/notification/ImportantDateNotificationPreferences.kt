package com.kernel.ai.core.memory.notification

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
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

private const val TAG = "ImportantDateNotifPrefs"
private val Context.importantDateNotifsDataStore by preferencesDataStore(
    name = "important_date_notification_prefs",
)

/**
 * Persists the daily notification time for important-date reminders (#902).
 * Default is 09:00.
 */
@Singleton
class ImportantDateNotificationPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val hourKey = intPreferencesKey("important_dates_notification_hour")
    private val minuteKey = intPreferencesKey("important_dates_notification_minute")

    val notificationTime: Flow<Pair<Int, Int>> = context.importantDateNotifsDataStore.data
        .catch { e ->
            if (e is IOException) {
                Log.e(TAG, "Failed reading important-date notification preferences; using defaults", e)
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map { prefs ->
            Pair(prefs[hourKey] ?: DEFAULT_HOUR, prefs[minuteKey] ?: DEFAULT_MINUTE)
        }

    suspend fun getNotificationTime(): Pair<Int, Int> = notificationTime.first()

    suspend fun setNotificationTime(hour: Int, minute: Int) {
        context.importantDateNotifsDataStore.edit { prefs ->
            prefs[hourKey] = hour
            prefs[minuteKey] = minute
        }
    }

    companion object {
        const val DEFAULT_HOUR = 9
        const val DEFAULT_MINUTE = 0
    }
}
