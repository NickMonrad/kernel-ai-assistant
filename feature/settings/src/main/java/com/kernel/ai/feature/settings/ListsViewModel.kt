package com.kernel.ai.feature.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ListSort { LAST_MODIFIED, NAME_ASC, NAME_DESC, CREATED_ASC, CREATED_DESC }
enum class ListFilter { ALL, PINNED_ONLY }

@HiltViewModel
class ListsViewModel @Inject constructor(
    private val dao: ListItemDao,
    private val listNameDao: ListNameDao,
) : ViewModel() {

    /** Full list entities — exposes id, name, pinned, updatedAt for the overview screen. */
    val listEntities: StateFlow<List<ListNameEntity>> =
        listNameDao.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Sort / filter state (ViewModel-scoped, survives recomposition) ──────────────────────────

    /** Current sort order selected by the user on the overview screen. */
    var listSort by mutableStateOf(ListSort.LAST_MODIFIED)

    /** Current filter selected by the user on the overview screen. */
    var listFilter by mutableStateOf(ListFilter.ALL)

    /**
     * Derived list for the overview screen — pinned group first, then unpinned; each group sorted
     * independently per [listSort]; optionally narrowed by [listFilter].
     */
    val displayedLists: StateFlow<List<ListNameEntity>> = combine(
        listEntities,
        snapshotFlow { listSort },
        snapshotFlow { listFilter },
    ) { entities, sort, filter ->
        val base = when (filter) {
            ListFilter.ALL -> entities
            ListFilter.PINNED_ONLY -> entities.filter { it.pinned }
        }
        val comparator: Comparator<ListNameEntity> = when (sort) {
            ListSort.LAST_MODIFIED -> compareByDescending { it.updatedAt }
            ListSort.NAME_ASC -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            ListSort.NAME_DESC -> compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.name }
            ListSort.CREATED_ASC -> compareBy { it.createdAt }
            ListSort.CREATED_DESC -> compareByDescending { it.createdAt }
        }
        val pinned = base.filter { it.pinned }.sortedWith(comparator)
        val unpinned = base.filter { !it.pinned }.sortedWith(comparator)
        pinned + unpinned
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Derived helpers ──────────────────────────────────────────────────────────────────────────

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

    // ── Search ───────────────────────────────────────────────────────────────────────────────────

    /** Search query for the list overview screen. */
    private val _listSearchQuery = MutableStateFlow("")
    val listSearchQuery: StateFlow<String> = _listSearchQuery.asStateFlow()

    /** Search query for the drill-in item screen. */
    private val _itemSearchQuery = MutableStateFlow("")
    val itemSearchQuery: StateFlow<String> = _itemSearchQuery.asStateFlow()

    fun setListSearchQuery(q: String) { _listSearchQuery.value = q }
    fun setItemSearchQuery(q: String) { _itemSearchQuery.value = q }
    fun clearItemSearchQuery() { _itemSearchQuery.value = "" }

    // ── List mutations ───────────────────────────────────────────────────────────────────────────

    /**
     * Creates a new list entry (idempotent — IGNORE conflict) without returning the id.
     * Prefer [createList] when you need to navigate into the newly created list.
     */
    fun addList(name: String) {
        val trimmed = name.trim().lowercase()
        if (trimmed.isBlank()) return
        val now = System.currentTimeMillis()
        viewModelScope.launch {
            listNameDao.insert(ListNameEntity(name = trimmed, createdAt = now, updatedAt = now))
        }
    }

    /**
     * Creates a new list and returns the auto-generated [ListNameEntity.id] so the caller can
     * navigate directly into the new list.  Returns -1 if [name] is blank.
     * Falls back to [ListNameDao.getByName] when the name already exists (IGNORE conflict).
     */
    suspend fun createList(name: String): Long {
        val trimmed = name.trim().lowercase()
        if (trimmed.isBlank()) return -1L
        val now = System.currentTimeMillis()
        val id = listNameDao.insertAndGet(
            ListNameEntity(name = trimmed, createdAt = now, updatedAt = now),
        )
        return if (id > 0L) id else listNameDao.getByName(trimmed)?.id ?: -1L
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

    /** Renames a list, bumping the updatedAt timestamp. */
    fun renameList(id: Long, newName: String) {
        val trimmed = newName.trim().lowercase()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            listNameDao.updateName(id, trimmed, System.currentTimeMillis())
        }
    }

    /** Toggles the pinned state of a list atomically, bumping the updatedAt timestamp. */
    fun togglePin(id: Long) {
        viewModelScope.launch {
            listNameDao.togglePinned(id, System.currentTimeMillis())
        }
    }

    /**
     * Resolves a display name to the list entity, for use by handlers that receive
     * a name string (e.g. NativeIntentHandler at the skill boundary).
     */
    suspend fun resolveListByName(name: String): ListNameEntity? = listNameDao.getByName(name)
}


