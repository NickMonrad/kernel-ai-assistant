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
import com.kernel.ai.core.memory.notification.ListNotificationScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ListItemCounts(val active: Int, val completed: Int) {
    val total: Int get() = active + completed
}

enum class ListSort { MANUAL, LAST_MODIFIED, NAME_ASC, NAME_DESC, CREATED_ASC, CREATED_DESC }
enum class ListFilter { ALL, PINNED_ONLY }

enum class ItemSort {
    MANUAL,
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
    private val scheduler: ListNotificationScheduler,
) : ViewModel() {

    /** Full list entities — exposes id, name, pinned, updatedAt for the overview screen. */
    val listEntities: StateFlow<List<ListNameEntity>> =
        listNameDao.observeActiveLists()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Archived lists, newest-archived-first — exposed when [showArchived] is true. */
    val archivedLists: StateFlow<List<ListNameEntity>> =
        listNameDao.observeArchivedLists()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** When true the UI shows the archived lists view instead of the active lists. */
    var showArchived by mutableStateOf(false)

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
                    ItemSort.MANUAL -> compareBy { it.displayOrder }
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
                // Completed items: in MANUAL mode sort by updatedAt DESC (recently completed first)
                // so the completed section doesn't use displayOrder (only active items are reorderable).
                val completedComparator: Comparator<ListItemEntity> =
                    if (sort == ItemSort.MANUAL) compareByDescending { it.updatedAt } else comparator
                Pair(active.sortedWith(comparator), completed.sortedWith(completedComparator))
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

    /** Item counts keyed by listId — shows active/completed breakdown in the overview. */
    val itemCounts: StateFlow<Map<Long, ListItemCounts>> =
        dao.observeAll()
            .map { items ->
                items.groupBy { it.listId }.mapValues { (_, listItems) ->
                    ListItemCounts(
                        active = listItems.count { !it.checked },
                        completed = listItems.count { it.checked },
                    )
                }
            }
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

    private var reorderJob: Job? = null

    /**
     * Called when the user finishes dragging a list row.  Cancels any in-flight reorder job and
     * persists the new display order atomically for both groups, automatically switching the sort
     * mode to [ListSort.MANUAL].
     *
     * @param pinnedIds  Ordered list of pinned entity IDs after the drag.
     * @param unpinnedIds  Ordered list of unpinned entity IDs after the drag.
     */
    fun onListsReordered(pinnedIds: List<Long>, unpinnedIds: List<Long>) {
        listSort = ListSort.MANUAL
        val now = System.currentTimeMillis()
        reorderJob?.cancel()
        reorderJob = viewModelScope.launch(Dispatchers.IO) {
            val updates = pinnedIds.mapIndexed { i, id -> id to i } +
                          unpinnedIds.mapIndexed { i, id -> id to i }
            listNameDao.updateDisplayOrders(updates, now)
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

    fun selectAllLists(ids: List<Long>) { selectedListIds = ids.toSet() }

    /** Bulk-deletes the currently selected lists (cascade removes all child items via FK). */
    fun deleteSelectedLists() {
        val ids = selectedListIds.toList()
        selectedListIds = emptySet()
        viewModelScope.launch(Dispatchers.IO) {
            ids.forEach { listId ->
                dao.getAllWithNotification(listId).forEach { scheduler.cancel(it.id) }
                listNameDao.deleteById(listId)
            }
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

    fun selectAllItems(ids: List<Long>) { selectedItemIds = ids.toSet() }

    /** Bulk-deletes the currently selected list items. */
    fun deleteSelectedItems() {
        val ids = selectedItemIds.toList()
        selectedItemIds = emptySet()
        ids.forEach { scheduler.cancel(it) }
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
        ids.forEach { scheduler.cancel(it) }
        viewModelScope.launch(Dispatchers.IO) {
            ids.forEach { dao.setChecked(it, true, now) }
        }
    }

    /**
     * Unmarks all currently selected list items (sets checked=false).
     * Re-schedules any future notification alarms that were cancelled when the item was completed.
     */
    fun unmarkSelectedItemsComplete() {
        val ids = selectedItemIds.toList()
        selectedItemIds = emptySet()
        val now = System.currentTimeMillis()
        val allItems = groupedItems.value.values.flatten()
        val listNames = listEntities.value.associateBy { it.id }
        viewModelScope.launch(Dispatchers.IO) {
            ids.forEach { id -> dao.setChecked(id, false, now) }
            // Re-schedule any future notifications that were cancelled on completion
            ids.forEach { id ->
                val item = allItems.firstOrNull { it.id == id } ?: return@forEach
                val nt = item.notificationTime ?: return@forEach
                if (nt > now) {
                    val listName = listNames[item.listId]?.name ?: return@forEach
                    scheduler.schedule(
                        itemId = id,
                        itemText = item.text,
                        listId = item.listId,
                        listName = listName,
                        triggerAtMs = nt,
                    )
                }
            }
        }
    }

    /**
     * Marks all currently selected list items as favourite.
     * Uses [ListItemDao.setFavourite] to set isFavourite=true atomically.
     */
    fun favouriteSelectedItems() {
        val ids = selectedItemIds.toList()
        selectedItemIds = emptySet()
        val now = System.currentTimeMillis()
        viewModelScope.launch(Dispatchers.IO) {
            ids.forEach { dao.setFavourite(it, true, now) }
        }
    }

    /**
     * Removes favourite from all currently selected list items.
     */
    fun unfavouriteSelectedItems() {
        val ids = selectedItemIds.toList()
        selectedItemIds = emptySet()
        val now = System.currentTimeMillis()
        viewModelScope.launch(Dispatchers.IO) {
            ids.forEach { dao.setFavourite(it, false, now) }
        }
    }

    // ── Item drag-to-reorder (#917) ───────────────────────────────────────────────────────────────

    private var itemReorderJob: Job? = null

    /**
     * Persists a new manual display order for active list items after a drag-to-reorder gesture.
     * Cancels any in-flight reorder job and switches [itemSort] to [ItemSort.MANUAL].
     *
     * @param orderedIds  Ordered list of active item IDs after the drag (active section only).
     */
    fun reorderItems(orderedIds: List<Long>) {
        itemSort = ItemSort.MANUAL
        val now = System.currentTimeMillis()
        itemReorderJob?.cancel()
        itemReorderJob = viewModelScope.launch(Dispatchers.IO) {
            val updates = orderedIds.mapIndexed { index, id -> id to index.toLong() }
            dao.replaceItemOrders(updates, now)
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
            // When an item is being checked (completing), cancel any pending notification
            if (!item.checked) scheduler.cancel(item.id)
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
        scheduler.cancel(id)
        viewModelScope.launch { dao.deleteItem(id) }
    }

    /** Entity overload — preferred from the item screen. */
    fun deleteItem(item: ListItemEntity) {
        scheduler.cancel(item.id)
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

    /** Persists edits made in the edit bottom sheet (text, dueAt, isFavourite, notificationTime). */
    fun updateItem(item: ListItemEntity) {
        val now = System.currentTimeMillis()
        val listName = listEntities.value.firstOrNull { it.id == item.listId }?.name ?: ""
        viewModelScope.launch {
            dao.updateItem(
                id = item.id,
                text = item.text,
                dueAt = item.dueAt,
                isFavourite = item.isFavourite,
                notificationTime = item.notificationTime,
                updatedAt = now,
            )
            listNameDao.updateTimestamp(item.listId, now)
            // Schedule or cancel the notification alarm
            val nt = item.notificationTime
            if (nt != null) {
                scheduler.schedule(
                    itemId = item.id,
                    itemText = item.text,
                    listId = item.listId,
                    listName = listName,
                    triggerAtMs = nt,
                )
            } else {
                scheduler.cancel(item.id)
            }
        }
    }

    fun clearChecked(listId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.getCheckedWithNotification(listId).forEach { scheduler.cancel(it.id) }
            dao.deleteChecked(listId)
        }
    }

    /** Deletes a list by ID; cascade FK removes all child items automatically. */
    fun deleteList(listId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.getAllWithNotification(listId).forEach { scheduler.cancel(it.id) }
            listNameDao.deleteById(listId)
        }
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

    // ── Archive / restore (#903) ──────────────────────────────────────────────────────────────────

    /** Archives a single list, removing it from the active view. */
    fun archiveList(id: Long) {
        selectedListIds = selectedListIds - id
        val now = System.currentTimeMillis()
        viewModelScope.launch(Dispatchers.IO) {
            dao.getAllWithNotification(id).forEach { scheduler.cancel(it.id) }
            listNameDao.archiveList(id = id, archivedAt = now, updatedAt = now)
        }
    }

    /** Restores an archived list back to the active view, re-scheduling any future alarms. */
    fun restoreList(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            listNameDao.restoreList(id = id, updatedAt = System.currentTimeMillis())
            val listName = listNameDao.getById(id)?.name ?: return@launch
            val now = System.currentTimeMillis()
            dao.getAllWithNotification(id).forEach { item ->
                    val triggerAtMs = item.notificationTime?.takeIf { it > now } ?: return@forEach
                    scheduler.schedule(
                        itemId = item.id,
                        itemText = item.text,
                        listId = id,
                        listName = listName,
                        triggerAtMs = triggerAtMs,
                    )
                }
        }
    }

    /** Archives all currently selected lists and clears the selection. */
    fun bulkArchiveSelected() {
        val ids = selectedListIds.toList()
        selectedListIds = emptySet()
        val now = System.currentTimeMillis()
        viewModelScope.launch(Dispatchers.IO) {
            ids.forEach { listId ->
                dao.getAllWithNotification(listId).forEach { scheduler.cancel(it.id) }
                listNameDao.archiveList(id = listId, archivedAt = now, updatedAt = now)
            }
        }
    }

    /**
     * Builds a plain-text representation of the list for sharing/copying.
     * Active items (unchecked) appear first with "• " prefix; completed with "✓ ".
     * Both groups are ordered by creation date ascending.
     */
    suspend fun buildShareText(listId: Long): String {
        val listName = listNameDao.getById(listId)?.name?.replaceFirstChar { it.uppercase() } ?: "List"
        val items = dao.getAllByList(listId)
        if (items.isEmpty()) return listName
        val lines = buildList {
            add(listName)
            add("")
            items.forEach { item ->
                add("${if (item.checked) "✓" else "•"} ${item.text}")
            }
        }
        return lines.joinToString("\n")
    }
}