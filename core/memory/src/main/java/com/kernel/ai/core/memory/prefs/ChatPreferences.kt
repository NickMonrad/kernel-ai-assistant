package com.kernel.ai.core.memory.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.chatPrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "chat_preferences")

/**
 * DataStore for chat-related user preferences.
 *
 * [archiveRetentionDays]: How many days to keep archived conversations before auto-deletion.
 *  - Positive values: number of days (1, 3, 7, 14, 30)
 *  - -1: Never auto-delete
 *  - Default: 7 days
 */
@Singleton
class ChatPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val archiveRetentionDaysKey = intPreferencesKey("archive_retention_days")

    val archiveRetentionDays: Flow<Int> = context.chatPrefsDataStore.data
        .map { prefs -> prefs[archiveRetentionDaysKey] ?: 7 }

    suspend fun setArchiveRetentionDays(days: Int) {
        context.chatPrefsDataStore.edit { prefs ->
            prefs[archiveRetentionDaysKey] = days
        }
    }
}
