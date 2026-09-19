package com.kernel.ai.feature.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory [DataStore] covering the preference surface the Lists screen uses.
 *
 * A real DataStore writes files, so unit tests substitute this deterministic stand-in.
 */
open class FakePreferencesDataStore(
    initial: Preferences = emptyPreferences(),
) : DataStore<Preferences> {
    val state = MutableStateFlow(initial)

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}

/**
 * [ListsUiPreferences] over [store], with preference IO pinned to [dispatcher].
 *
 * Pinning matters: viewModelScope already runs on the same test dispatcher, so preference reads
 * and writes complete inline instead of racing a real IO thread.
 */
fun testListsUiPreferences(
    dispatcher: CoroutineDispatcher,
    store: DataStore<Preferences> = FakePreferencesDataStore(),
): ListsUiPreferences = ListsUiPreferences(store).apply { ioDispatcher = dispatcher }
