package com.kernel.ai.feature.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.kernel.ai.core.memory.dao.ListItemDao
import com.kernel.ai.core.memory.dao.ListNameDao
import com.kernel.ai.core.memory.entity.ListItemEntity
import com.kernel.ai.core.memory.entity.ListNameEntity
import com.kernel.ai.core.memory.lists.CheckedStateMutation
import com.kernel.ai.core.memory.notification.ListNotificationScheduler
import com.kernel.ai.core.memory.repository.ListMutationRepository
import com.kernel.ai.core.memory.lists.EffectiveHierarchyGroup
import com.kernel.ai.core.memory.lists.EffectiveHierarchyProjection
import com.kernel.ai.core.memory.lists.ListLifecycle
import com.kernel.ai.core.memory.lists.ListsDataChanged
import com.kernel.ai.core.memory.lists.OrderKey
import com.kernel.ai.core.memory.lists.VersionStamp
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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import dagger.hilt.android.qualifiers.ApplicationContext
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
    @ApplicationContext private val appContext: Context,
    private val listMutations: ListMutationRepository,
) : ViewModel() {
    init {
        // Keep the Lists home-screen widget in sync with in-app list mutations. A single combined
        // flow over the item DAO and the list-name DAO emits once on subscription (the initial Room
        // replay); we skip that first emission explicitly so a cold-start VM never broadcasts. Every
        // subsequent real write (item OR list metadata: rename, archive, pin, reorder, delete) emits
        // exactly one widget-refresh broadcast, and a failed persistence never triggers one.
        viewModelScope.launch {
            var isFirst = true
            combine(dao.observeAll(), listNameDao.observeActiveLists()) { _, _ -> }
                .collect {
                    if (isFirst) {
                        isFirst = false
                        return@collect
                    }
                    ListsDataChanged.broadcast(appContext)
                }
        }
    }

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
            ) { items: List<ListItemEntity>, sort: ItemSort, filter: ItemFilter ->
                val filtered = when (filter) {
                    ItemFilter.ALL -> items
                    ItemFilter.FAVOURITES_ONLY -> items.filter { it.isFavourite }
                    ItemFilter.ACTIVE_ONLY -> items.filter { !it.checked }
                    ItemFilter.COMPLETED_ONLY -> items.filter { it.checked }
                }
                val active = filtered.filter { !it.checked }
                val completed = filtered.filter { it.checked }
                val comparator: Comparator<ListItemEntity> = when (sort) {
                    ItemSort.MANUAL -> Comparator { left, right ->
                        OrderKey.compare(left.orderKey, right.orderKey)
                            .takeIf { it != 0 }
                            ?: left.itemId.compareTo(right.itemId).takeIf { it != 0 }
                            ?: left.id.compareTo(right.id)
                    }
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

    private val hierarchyFlowCache =
        mutableMapOf<Long, StateFlow<Pair<List<EffectiveHierarchyGroup<ListItemEntity>>, List<EffectiveHierarchyGroup<ListItemEntity>>>>>()

    fun observeDisplayedHierarchy(
        listId: Long,
    ): StateFlow<Pair<List<EffectiveHierarchyGroup<ListItemEntity>>, List<EffectiveHierarchyGroup<ListItemEntity>>>> =
        hierarchyFlowCache.getOrPut(listId) {
            combine(
                dao.observeByList(listId),
                snapshotFlow { itemSort },
                snapshotFlow { itemFilter },
                itemSearchQuery,
            ) { items, sort, filter, query ->
                val comparator = itemComparator(sort)
                val groups = EffectiveHierarchyProjection.derive(
                    items,
                    itemId = { it.itemId },
                    parentItemId = { it.parentItemId },
                    orderKey = { it.orderKey },
                    placementStamp = { VersionStamp(it.placementLogicalClock, it.placementStampActorId) },
                )
                val matching = groups.mapNotNull { group ->
                    val parentMatches = query.isBlank() || group.parent.text.contains(query, true)
                    val childMatches = group.children.filter { query.isBlank() || it.text.contains(query, true) }
                    val favouriteParent = group.parent.isFavourite
                    val favouriteChildren = group.children.filter { it.isFavourite }
                    val children = when {
                        query.isNotBlank() && !parentMatches -> childMatches
                        filter == ItemFilter.FAVOURITES_ONLY && !favouriteParent -> favouriteChildren
                        else -> group.children
                    }
                    val filterMatches = when (filter) {
                        ItemFilter.ALL -> true
                        ItemFilter.FAVOURITES_ONLY -> favouriteParent || favouriteChildren.isNotEmpty()
                        ItemFilter.ACTIVE_ONLY -> !group.parent.checked
                        ItemFilter.COMPLETED_ONLY -> group.parent.checked
                    }
                    if (filterMatches && (parentMatches || children.isNotEmpty())) {
                        EffectiveHierarchyGroup(group.parent, children)
                    } else null
                }
                val active = groupsForStatus(matching, checked = false, comparator)
                val completed = groupsForStatus(matching, checked = true, comparator)
                Pair(active, completed)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Pair(emptyList(), emptyList()))
        }

    private fun itemComparator(sort: ItemSort): Comparator<ListItemEntity> = when (sort) {
        ItemSort.MANUAL -> Comparator { left, right ->
            OrderKey.compare(left.orderKey, right.orderKey).takeIf { it != 0 }
                ?: left.itemId.compareTo(right.itemId).takeIf { it != 0 }
                ?: left.id.compareTo(right.id)
        }
        ItemSort.CREATED_NEWEST -> compareByDescending { it.createdAt }
        ItemSort.CREATED_OLDEST -> compareBy { it.createdAt }
        ItemSort.UPDATED_NEWEST -> compareByDescending { it.updatedAt }
        ItemSort.NAME_ASC -> Comparator { a, b -> String.CASE_INSENSITIVE_ORDER.compare(a.text, b.text) }
        ItemSort.NAME_DESC -> Comparator { a, b -> String.CASE_INSENSITIVE_ORDER.compare(b.text, a.text) }
        ItemSort.DUE_SOONEST -> compareBy<ListItemEntity> { it.dueAt == null }.thenBy { it.dueAt ?: Long.MAX_VALUE }
        ItemSort.FAVOURITES_FIRST -> compareByDescending<ListItemEntity> { it.isFavourite }.thenByDescending { it.createdAt }
    }

    private fun groupsForStatus(
        groups: List<EffectiveHierarchyGroup<ListItemEntity>>,
        checked: Boolean,
        comparator: Comparator<ListItemEntity>,
    ): List<EffectiveHierarchyGroup<ListItemEntity>> {
        val matching = groups.filter { it.parent.checked == checked }
        val parentComparator = comparator
        val childComparator = Comparator { left: ListItemEntity, right: ListItemEntity ->
            OrderKey.compare(left.orderKey, right.orderKey).takeIf { it != 0 }
                ?: left.itemId.compareTo(right.itemId)
        }
        return matching.sortedWith(Comparator { a, b -> parentComparator.compare(a.parent, b.parent) })
            .map { it.copy(children = it.children.sortedWith(childComparator)) }
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
     * Called when the user finishes dragging a list row. Cancels any in-flight reorder job and
     * persists the new display order atomically for both groups.
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

    /** Bulk-deletes selected lists by recording collection and item tombstones. */
    fun deleteSelectedLists() {
        val ids = selectedListIds.toList()
        selectedListIds = emptySet()
        viewModelScope.launch(Dispatchers.IO) {
            ids.forEach { listId ->
                dao.getAllWithNotification(listId).forEach { scheduler.cancel(it.id) }
                listMutations.deleteCollection(listId)
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
            listMutations.deleteItems(ids)
        }
    }

    private suspend fun applyCheckedStateReminderTransitions(mutation: CheckedStateMutation) {
        mutation.checkedIds.forEach(scheduler::cancel)
        val now = System.currentTimeMillis()
        mutation.uncheckedIds.forEach { id ->
            val item = dao.getById(id) ?: return@forEach
            val triggerAtMs = item.notificationTime?.takeIf { it > now } ?: return@forEach
            val listName = listNameDao.getById(item.listId)?.name ?: return@forEach
            scheduler.schedule(
                itemId = item.id,
                itemText = item.text,
                listId = item.listId,
                listName = listName,
                triggerAtMs = triggerAtMs,
            )
        }
    }

    /**
     * Marks all currently selected list items as complete.
     * Routes each checked-state mutation through the sync-aware repository.
     */
    fun markSelectedItemsComplete() {
        val ids = selectedItemIds.toList()
        selectedItemIds = emptySet()
        viewModelScope.launch(Dispatchers.IO) {
            applyCheckedStateReminderTransitions(listMutations.setItemsChecked(ids, true))
        }
    }

    /**
     * Unmarks all currently selected list items (sets checked=false).
     * Re-schedules future notification alarms for every item actually restored to active.
     */
    fun unmarkSelectedItemsComplete() {
        val ids = selectedItemIds.toList()
        selectedItemIds = emptySet()
        viewModelScope.launch(Dispatchers.IO) {
            applyCheckedStateReminderTransitions(listMutations.setItemsChecked(ids, false))
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

    fun splitItem(item: ListItemEntity) {
        viewModelScope.launch(Dispatchers.IO) { listMutations.splitItem(item.id) }
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

    // ── Item drag-to-reparent/reorder (#928) ─────────────────────────────────────────────────────
    fun moveItemFromDrag(
        visibleIds: List<Long>,
        draggedId: Long,
        targetId: Long,
        requestedIntent: ItemDropIntent,
    ) {
        if (draggedId == targetId || draggedId !in visibleIds || targetId !in visibleIds) return
        itemSort = ItemSort.MANUAL
        viewModelScope.launch(Dispatchers.IO) {
            val dragged = dao.getById(draggedId) ?: return@launch
            val target = dao.getById(targetId) ?: return@launch
            if (dragged.listId != target.listId) return@launch
            val all = dao.getAllByListUnordered(dragged.listId)
                .filter { it.lifecycle == ListLifecycle.ACTIVE.name }
            val groups = EffectiveHierarchyProjection.derive(
                all,
                itemId = { it.itemId },
                parentItemId = { it.parentItemId },
                orderKey = { it.orderKey },
                placementStamp = { VersionStamp(it.placementLogicalClock, it.placementStampActorId) },
            )
            val groupByParent = groups.associateBy { it.parent.itemId }
            val parentByChild = groups.flatMap { group ->
                group.children.map { child -> child.itemId to group.parent.itemId }
            }.toMap()
            val draggedHasChildren = groupByParent[dragged.itemId]?.children?.isNotEmpty() == true
            val targetIsChild = target.itemId in parentByChild
            val intent = resolveItemDropIntent(
                requested = requestedIntent,
                sourceHasChildren = draggedHasChildren,
                targetIsChild = targetIsChild,
            ) ?: return@launch
            val destinationParentId = when (intent) {
                ItemDropIntent.NEST -> {
                    if (targetIsChild) return@launch
                    target.itemId
                }
                ItemDropIntent.INSERT_BEFORE,
                ItemDropIntent.INSERT_AFTER,
                -> if (draggedHasChildren) null else parentByChild[target.itemId]
            }
            val siblings = if (destinationParentId == null) {
                groups.map { it.parent }
            } else {
                groupByParent[destinationParentId]?.children.orEmpty()
            }
            val sourceIsGroup = draggedHasChildren
            val anchorId = if (sourceIsGroup) {
                groups.firstOrNull { group -> group.children.any { it.itemId == target.itemId } }?.parent?.itemId
                    ?: target.itemId
            } else {
                target.itemId
            }
            val anchorIndex = siblings.indexOfFirst { it.itemId == anchorId }
            if (intent != ItemDropIntent.NEST && anchorIndex < 0) return@launch
            val sourceIndex = siblings.indexOfFirst { it.itemId == dragged.itemId }
            val insertionIndex = when (intent) {
                ItemDropIntent.NEST -> siblings.size
                ItemDropIntent.INSERT_BEFORE -> anchorIndex
                ItemDropIntent.INSERT_AFTER -> anchorIndex + 1
            }
            val remaining = siblings.filter { it.id != dragged.id }
            val adjustedIndex = (insertionIndex - if (sourceIndex in 0 until insertionIndex) 1 else 0)
                .coerceIn(0, remaining.size)
            val lower = remaining.getOrNull(adjustedIndex - 1)?.orderKey
            val upper = remaining.getOrNull(adjustedIndex)?.orderKey
            listMutations.moveItem(dragged.id, destinationParentId, OrderKey.between(lower, upper))
        }
    }
    private var itemReorderJob: Job? = null

    fun reorderItems(orderedIds: List<Long>) {
        itemSort = ItemSort.MANUAL
        itemReorderJob?.cancel()
        itemReorderJob = viewModelScope.launch(Dispatchers.IO) {
            val listId = orderedIds.firstOrNull()?.let { dao.getById(it)?.listId } ?: return@launch
            val all = dao.getAllByListUnordered(listId).filter { it.lifecycle == ListLifecycle.ACTIVE.name }
            val groups = EffectiveHierarchyProjection.derive(
                all,
                itemId = { it.itemId },
                parentItemId = { it.parentItemId },
                orderKey = { it.orderKey },
                placementStamp = { VersionStamp(it.placementLogicalClock, it.placementStampActorId) },
            )
            val completedTop = groups.filter { it.parent.checked }.map { it.parent.id }
            val desired = orderedIds + completedTop.filterNot { it in orderedIds }
            listMutations.reorderItems(listId, desired)
        }
    }


    // ── Item drag-to-reorder (#917) ───────────────────────────────────────────────────────────────

    // ── List mutations ───────────────────────────────────────────────────────────────────────────

    /**
     * Creates a new list entry (idempotent — IGNORE conflict) without returning the id.
     * Prefer [createList] when you need to navigate into the newly created list.
     */
    fun addList(name: String) {
        val trimmed = name.trim().lowercase()
        if (trimmed.isBlank()) return
        viewModelScope.launch { listMutations.createCollection(trimmed) }
    }

    /**
     * Creates a new list and returns the auto-generated [ListNameEntity.id] so the caller can
     * navigate directly into the new list.  Returns -1 if [name] is blank.
     * Falls back to [ListNameDao.getByName] when the name already exists (IGNORE conflict).
     */
    suspend fun createList(name: String): Long {
        val trimmed = name.trim().lowercase()
        if (trimmed.isBlank()) return -1L
        return listMutations.createCollection(trimmed)
    }

    fun toggleChecked(item: ListItemEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            applyCheckedStateReminderTransitions(
                listMutations.setItemChecked(item.id, !item.checked),
            )
        }
    }

    fun addItem(listId: Long, itemText: String) {
        val trimmed = itemText.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch { listMutations.addItem(listId, trimmed) }
    }

    fun deleteItem(id: Long) {
        scheduler.cancel(id)
        viewModelScope.launch { listMutations.deleteItem(id) }
    }

    /** Entity overload — preferred from the item screen. */
    fun deleteItem(item: ListItemEntity) {
        scheduler.cancel(item.id)
        viewModelScope.launch { listMutations.deleteItem(item.id) }
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
        val listName = listEntities.value.firstOrNull { it.id == item.listId }?.name ?: ""
        viewModelScope.launch {
            listMutations.updateItem(item.id, item.text, item.dueAt, item.isFavourite, item.notificationTime)
            val nt = item.notificationTime
            if (nt != null) scheduler.schedule(itemId = item.id, itemText = item.text, listId = item.listId, listName = listName, triggerAtMs = nt)
            else scheduler.cancel(item.id)
        }
    }

    fun clearChecked(listId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val checked = dao.getAllByList(listId).filter { it.checked }
            checked.forEach { scheduler.cancel(it.id) }
            listMutations.deleteItems(checked.map { it.id })
        }
    }

    /** Deletes a list by recording tombstones; rows remain for sync convergence. */
    fun deleteList(listId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.getAllWithNotification(listId).forEach { scheduler.cancel(it.id) }
            listMutations.deleteCollection(listId)
        }
    }

    /** Renames a list, bumping the updatedAt timestamp. */
    fun renameList(id: Long, newName: String) {
        val trimmed = newName.trim().lowercase()
        if (trimmed.isBlank()) return
        viewModelScope.launch { listMutations.renameCollection(id, trimmed) }
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