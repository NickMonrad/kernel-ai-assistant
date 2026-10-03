package com.kernel.ai.feature.settings

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import com.kernel.ai.core.memory.dao.ListNameDao

private const val TAG = "ListsUiPreferences"

/** Item sort used when a list has no saved preference, or a saved value that cannot be decoded. */
val DEFAULT_ITEM_SORT = ItemSort.CREATED_NEWEST

/**
 * Device-local presentation preferences for the Lists drill-in screen.
 *
 * These are UI preferences only. They are deliberately kept out of the synced list/item state:
 * no change record is emitted, nothing is reconciled, and nothing is sent to remote providers.
 */
@Singleton
class ListsUiPreferences @Inject constructor(
    @Named("lists") private val dataStore: DataStore<Preferences>,
    private val listNameDao: ListNameDao,
) {
    /** Dispatcher for DataStore IO; replaced by the test scheduler in unit tests. */
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    /**
     * Saved item sort for [listId], or [DEFAULT_ITEM_SORT] when unset or undecodable.
     *
     * When a list with an initial sort is recreated under the same canonical title and default,
     * its explicit preference is carried forward from the newest deleted row.
     */
    suspend fun itemSortFor(listId: Long): ItemSort = withContext(ioDispatcher) {
        val storedPreferences = preferences()
        val stored = storedPreferences[itemSortKeyOf(listId)]
        if (stored != null) return@withContext decodeItemSort(stored)

        val list = listNameDao.getById(listId) ?: return@withContext DEFAULT_ITEM_SORT
        val previousStored = list.defaultItemSort?.let { defaultSort ->
            listNameDao.getDeletedByCanonicalTitleAndDefaultItemSort(list.canonicalTitle, defaultSort)
                .firstNotNullOfOrNull { previous -> storedPreferences[itemSortKeyOf(previous.id)] }
        }
        if (previousStored != null) {
            dataStore.edit { it[itemSortKeyOf(listId)] = previousStored }
            return@withContext decodeItemSort(previousStored)
        }

        list.defaultItemSort?.let(::decodeItemSort) ?: DEFAULT_ITEM_SORT
    }

    /** Persists [sort] as the item sort for [listId]. */
    suspend fun setItemSort(listId: Long, sort: ItemSort) {
        withContext(ioDispatcher) { dataStore.edit { it[itemSortKeyOf(listId)] = sort.name } }
    }

    private suspend fun preferences(): Preferences = dataStore.data
        .catch { error ->
            if (error is IOException) {
                Log.e(TAG, "Lists preferences read failed, falling back to defaults", error)
                emit(emptyPreferences())
            } else throw error
        }
        .first()
}

/** Preference key holding one list's saved [ItemSort] name. */
internal fun itemSortKeyOf(listId: Long) = stringPreferencesKey("item_sort_$listId")
private fun decodeItemSort(value: String): ItemSort =
    ItemSort.entries.firstOrNull { it.name == value } ?: DEFAULT_ITEM_SORT
