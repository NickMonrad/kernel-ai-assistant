package com.kernel.ai.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.memory.dao.ListItemDao
import com.kernel.ai.core.memory.dao.ListNameDao
import com.kernel.ai.core.memory.entity.ListItemEntity
import com.kernel.ai.core.memory.entity.ListNameEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ListsViewModel @Inject constructor(
    private val dao: ListItemDao,
    private val listNameDao: ListNameDao,
) : ViewModel() {

    /** Full list entities — exposes id, name, pinned, updatedAt for the overview screen. */
    val listEntities: StateFlow<List<ListNameEntity>> =
        listNameDao.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** List names derived from listEntities — kept for search filtering. */
    val listNames: StateFlow<List<String>> =
        listEntities
            .map { it.map { e -> e.name } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** All items grouped by listId — used by the drill-in item screen. */
    val groupedItems: StateFlow<Map<Long, List<ListItemEntity>>> =
        dao.observeAll()
            .map { items -> items.groupBy { it.listId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Item counts keyed by listId — shown in the overview. */
    val itemCounts: StateFlow<Map<Long, Int>> =
        dao.observeAll()
            .map { items -> items.groupBy { it.listId }.mapValues { it.value.size } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Search query for the list overview screen. */
    private val _listSearchQuery = MutableStateFlow("")
    val listSearchQuery: StateFlow<String> = _listSearchQuery.asStateFlow()

    /** Search query for the drill-in item screen. */
    private val _itemSearchQuery = MutableStateFlow("")
    val itemSearchQuery: StateFlow<String> = _itemSearchQuery.asStateFlow()

    fun setListSearchQuery(q: String) { _listSearchQuery.value = q }
    fun setItemSearchQuery(q: String) { _itemSearchQuery.value = q }
    fun clearItemSearchQuery() { _itemSearchQuery.value = "" }

    /** Creates a new list entry in the lists table (idempotent — IGNORE conflict). */
    fun addList(name: String) {
        val trimmed = name.trim().lowercase()
        if (trimmed.isBlank()) return
        val now = System.currentTimeMillis()
        viewModelScope.launch {
            listNameDao.insert(ListNameEntity(name = trimmed, createdAt = now, updatedAt = now))
        }
    }

    fun toggleChecked(item: ListItemEntity) {
        viewModelScope.launch {
            if (item.checked) dao.markUnchecked(item.id) else dao.markChecked(item.id)
        }
    }

    fun addItem(listId: Long, itemText: String) {
        val trimmed = itemText.trim()
        if (trimmed.isBlank()) return
        val now = System.currentTimeMillis()
        viewModelScope.launch {
            dao.insert(ListItemEntity(listId = listId, text = trimmed, createdAt = now, updatedAt = now))
            listNameDao.updateTimestamp(listId, now)
        }
    }

    fun deleteItem(id: Long) {
        viewModelScope.launch { dao.deleteItem(id) }
    }

    fun clearChecked(listId: Long) {
        viewModelScope.launch { dao.deleteChecked(listId) }
    }

    /** Deletes a list by ID; cascade FK removes all child items automatically. */
    fun deleteList(listId: Long) {
        viewModelScope.launch { listNameDao.deleteById(listId) }
    }

    /** Stub — renames a list. Full implementation in slice 2. */
    fun renameList(id: Long, newName: String) {
        val trimmed = newName.trim().lowercase()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            listNameDao.updateName(id, trimmed, System.currentTimeMillis())
        }
    }

    /** Stub — toggles the pinned state of a list. Full implementation in slice 2. */
    fun togglePin(id: Long) {
        viewModelScope.launch {
            val entity = listEntities.value.firstOrNull { it.id == id } ?: return@launch
            listNameDao.updatePinned(id, !entity.pinned, System.currentTimeMillis())
        }
    }

    /**
     * Resolves a display name to the list entity, for use by handlers that receive
     * a name string (e.g. NativeIntentHandler at the skill boundary).
     */
    suspend fun resolveListByName(name: String): ListNameEntity? = listNameDao.getByName(name)
}

