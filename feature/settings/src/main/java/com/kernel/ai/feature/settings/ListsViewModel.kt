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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ListSort { MANUAL, LAST_MODIFIED, NAME_ASC, NAME_DESC, CREATED_ASC, CREATED_DESC }
enum class ListFilter { ALL, PINNED_ONLY }

enum class ItemSort {
    CREATED_NEWEST,
    CREATED_OLDEST,
    UPDATED_NEWEST,
    NAME_ASC,
    NAME_DESC,
    DUE_SOONEST,
    FAVOURITES_FIRST,
}

enum class ItemFilter { ALL, FAVOURITES_ONLY, ACTIVE_ONLY, COMPLETED_ONLY }

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

    /** Current sort order selected by the user on the overview screen. Defaults to MANUAL. */
    var listSort by mutableStateOf(ListSort.MANUAL)

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
            ListSort.MANUAL ->
                compareBy<ListNameEntity> { it.displayOrder }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
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

    // ── Item sort / filter state ─────────────────────────────────────────────────────────────────

    /** Current sort order for the drill-in item screen. */
    var itemSort by mutableStateOf(ItemSort.CREATED_NEWEST)

    /** Current filter for the drill-in item screen. */
    var itemFilter by mutableStateOf(ItemFilter.ALL)

    /** Cache so repeated recompositions don't create duplicate StateFlows. */
    private val itemFlowCache =
        mutableMapOf<Long, StateFlow<Pair<List<ListItemEntity>, List<ListItemEntity>>>>()

    /**
     * Returns a [StateFlow] of (activeItems, completedItems) for [listId].
     * Each group is independently sorted by [itemSort] and the full set is pre-filtered by
     * [itemFilter] before splitting.  The flow is cached by listId so recompositions are cheap.
     */
    fun observeDisplayedItems(
        listId: Long,
    ): StateFlow<Pair<List<ListItemEntity>, List<ListItemEntity>>> =
        itemFlowCache.getOrPut(listId) {
            combine(
                dao.observeByList(listId),
                snapshotFlow { itemSort },
                snapshotFlow { itemFilter },
            ) { items, sort, filter ->
                val filtered = when (filter) {
                    ItemFilter.ALL -> items
                    ItemFilter.FAVOURITES_ONLY -> items.filter { it.isFavourite }
                    ItemFilter.ACTIVE_ONLY -> items.filter { !it.checked }
                    ItemFilter.COMPLETED_ONLY -> items.filter { it.checked }
                }
                val active = filtered.filter { !it.checked }
                val completed = filtered.filter { it.checked }
                val comparator: Comparator<ListItemEntity> = when (sort) {
                    ItemSort.CREATED_NEWEST -> compareByDescending { it.createdAt }
                    ItemSort.CREATED_OLDEST -> compareBy { it.createdAt }
                    ItemSort.UPDATED_NEWEST -> compareByDescending { it.updatedAt }
                    ItemSort.NAME_ASC ->
                        Comparator { a, b -> String.CASE_INSENSITIVE_ORDER.compare(a.text, b.text) }
                    ItemSort.NAME_DESC ->
                        Comparator { a, b -> String.CASE_INSENSITIVE_ORDER.compare(b.text, a.text) }
                    ItemSort.DUE_SOONEST -> Comparator { a, b ->
                        val da = a.dueAt; val db = b.dueAt
                        when {
                            da == null && db == null -> 0
                            da == null -> 1  // nulls last
                            db == null -> -1
                            else -> da.compareTo(db)
                        }
                    }
                    ItemSort.FAVOURITES_FIRST ->
                        compareByDescending<ListItemEntity> { it.isFavourite }
                            .thenByDescending { it.createdAt }
                }
                Pair(active.sortedWith(comparator), completed.sortedWith(comparator))
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                Pair(emptyList(), emptyList()),
            )
        }

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

    // ── Drag-and-drop reorder (#897) ─────────────────────────────────────────────────────────────

    /**
     * Called when the user finishes dragging a list row.  Persists the new display order for both
     * groups and automatically switches the sort mode to [ListSort.MANUAL].
     *
     * @param pinnedIds  Ordered list of pinned entity IDs after the drag.
     * @param unpinnedIds  Ordered list of unpinned entity IDs after the drag.
     */
    fun onListsReordered(pinnedIds: List<Long>, unpinnedIds: List<Long>) {
        listSort = ListSort.MANUAL
        val now = System.currentTimeMillis()
        viewModelScope.launch(Dispatchers.IO) {
            pinnedIds.forEachIndexed { index, id ->
                listNameDao.updateDisplayOrder(id, index, now)
            }
            unpinnedIds.forEachIndexed { index, id ->
                listNameDao.updateDisplayOrder(id, index, now)
            }
        }
    }

    // ── Multi-select state — lists overview (#896) ───────────────────────────────────────────────

    var selectedListIds by mutableStateOf<Set<Long>>(emptySet())
        private set

    val isListMultiSelectMode: Boolean get() = selectedListIds.isNotEmpty()

    fun enterListMultiSelect(id: Long) { selectedListIds = setOf(id) }

    fun toggleListSelection(id: Long) {
        selectedListIds = if (id in selectedListIds) selectedListIds - id else selectedListIds + id
    }

    fun exitListMultiSelect() { selectedListIds = emptySet() }

    /** Bulk-deletes the currently selected lists (cascade removes all child items via FK). */
    fun deleteSelectedLists() {
        val ids = selectedListIds.toList()
        selectedListIds = emptySet()
        viewModelScope.launch(Dispatchers.IO) {
            ids.forEach { listNameDao.deleteById(it) }
        }
    }

    // ── Multi-select state — list items (#896) ───────────────────────────────────────────────────

    var selectedItemIds by mutableStateOf<Set<Long>>(emptySet())
        private set

    val isItemMultiSelectMode: Boolean get() = selectedItemIds.isNotEmpty()

    fun enterItemMultiSelect(id: Long) { selectedItemIds = setOf(id) }

    fun toggleItemSelection(id: Long) {
        selectedItemIds = if (id in selectedItemIds) selectedItemIds - id else selectedItemIds + id
    }

    fun exitItemMultiSelect() { selectedItemIds = emptySet() }

    /** Bulk-deletes the currently selected list items. */
    fun deleteSelectedItems() {
        val ids = selectedItemIds.toList()
        selectedItemIds = emptySet()
        viewModelScope.launch(Dispatchers.IO) {
            ids.forEach { dao.deleteItem(it) }
        }
    }

    /**
     * Marks all currently selected list items as complete.
     * Uses [ListItemDao.setChecked] to atomically set checked=true without a TOCTOU risk.
     */
    fun markSelectedItemsComplete() {
        val ids = selectedItemIds.toList()
        selectedItemIds = emptySet()
        val now = System.currentTimeMillis()
        viewModelScope.launch(Dispatchers.IO) {
            ids.forEach { dao.setChecked(it, true, now) }
        }
    }

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
        val now = System.currentTimeMillis()
        viewModelScope.launch(Dispatchers.IO) {
            dao.toggleChecked(item.id, now)
            listNameDao.updateTimestamp(item.listId, now)
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

    /** Entity overload — preferred from the item screen. */
    fun deleteItem(item: ListItemEntity) {
        viewModelScope.launch { dao.deleteItem(item.id) }
    }

    /** Toggles isFavourite and bumps updatedAt + parent list updatedAt. */
    fun toggleFavourite(item: ListItemEntity) {
        val now = System.currentTimeMillis()
        viewModelScope.launch(Dispatchers.IO) {
            dao.toggleFavourite(item.id, now)
            listNameDao.updateTimestamp(item.listId, now)
        }
    }

    /** Persists edits made in the edit bottom sheet (text, dueAt, isFavourite). */
    fun updateItem(item: ListItemEntity) {
        val now = System.currentTimeMillis()
        viewModelScope.launch {
            dao.updateItem(
                id = item.id,
                text = item.text,
                dueAt = item.dueAt,
                isFavourite = item.isFavourite,
                updatedAt = now,
            )
            listNameDao.updateTimestamp(item.listId, now)
        }
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


