package com.kernel.ai.core.inference.prefs

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kernel.ai.core.inference.download.DownloadNetworkChoice
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private const val TAG = "KernelAI"
private val Context.downloadNetworkDataStore: DataStore<Preferences> by preferencesDataStore(name = "download_network_prefs")

/**
 * The user's download network choice. Null until the setup wizard (or Settings) records one; null means
 * no auto-queued download may start (#1855).
 */
class DownloadNetworkPreferences internal constructor(private val dataStore: DataStore<Preferences>) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(context.downloadNetworkDataStore)

    private val choiceKey = stringPreferencesKey("download_network_choice")

    val choice: Flow<DownloadNetworkChoice?> = dataStore.data
        .catch { e ->
            if (e is IOException) {
                Log.e(TAG, "DownloadNetworkPreferences: DataStore read error, treating as not chosen", e)
                emit(emptyPreferences())
            } else throw e
        }
        .map { prefs -> DownloadNetworkChoice.fromStorageName(prefs[choiceKey]) }

    suspend fun setChoice(choice: DownloadNetworkChoice) {
        dataStore.edit { prefs -> prefs[choiceKey] = choice.name }
    }
}
