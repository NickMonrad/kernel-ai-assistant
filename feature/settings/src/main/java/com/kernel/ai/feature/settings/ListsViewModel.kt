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
import kotlinx.coroutines.CoroutineDispatcher
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
import kotlinx.coroutines.withContext
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
    private val listsUiPreferences: ListsUiPreferences,
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

    /**
     * Sort order for the drill-in item screen of the currently bound list.
     *
     * Restored per list by [bindItemList]; stays [DEFAULT_ITEM_SORT] until a list is bound.
     */
    var itemSort by mutableStateOf(DEFAULT_ITEM_SORT)
        private set

    /** List whose drill-in sort preference is bound, or null when no drill-in screen is open. */
    private var boundItemListId: Long? = null

    private var itemSortLoadJob: Job? = null

    /**
     * True while a hierarchy interaction is materialising the visible order and switching to
     * Manual.
     *
     * The screen holds its optimistic projection for the duration, so the list cannot flash the
     * previous persisted order between the placement write and the sort switch.
     */
    var isHierarchyTransitionPending by mutableStateOf(false)
        private set

    /** Dispatcher for repository work; replaced by the test scheduler in unit tests. */
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    /**
     * Binds the drill-in screen to [listId] and restores that list's saved sort.
     *
     * Binding by identity is what stops a list inheriting another list's sort, and keeps a late
     * restore for a previously opened list from overwriting the list that is open now.
     */
    fun bindItemList(listId: Long) {
        if (boundItemListId == listId) return
        boundItemListId = listId
        itemSortLoadJob?.cancel()
        itemSortLoadJob = viewModelScope.launch {
            val saved = listsUiPreferences.itemSortFor(listId)
            if (boundItemListId == listId) itemSort = saved
        }
    }

    /**
     * Applies the user's explicit sort choice and persists it for the bound list.
     *
     * This preference is local presentation state: it never emits a sync change record.
     */
    fun selectItemSort(sort: ItemSort) {
        if (itemSort == sort) return
        // An explicit choice supersedes any restore still in flight for this list.
        itemSortLoadJob?.cancel()
        itemSort = sort
        val listId = boundItemListId ?: return
        viewModelScope.launch { listsUiPreferences.setItemSort(listId, sort) }
    }

    /**
     * Enters the explicit hierarchy editing mode exposed by the Lists overflow menu.
     *
     * Manual order is persisted for the bound list, and the filter and search that would hide or
     * reorder rows are cleared so the drag handles are usable immediately.
     */
    fun enterManualHierarchyEditing() {
        selectItemSort(ItemSort.MANUAL)
        itemFilter = ItemFilter.ALL
        clearItemSearchQuery()
    }

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
        reorderJob = viewModelScope.launch(ioDispatcher) {
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
        viewModelScope.launch(ioDispatcher) {
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
        viewModelScope.launch(ioDispatcher) {
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
        viewModelScope.launch(ioDispatcher) {
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
        viewModelScope.launch(ioDispatcher) {
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
        viewModelScope.launch(ioDispatcher) {
            ids.forEach { dao.setFavourite(it, true, now) }
        }
    }

    /**
     * Makes [item] a sub-item of the group that [precedingRow] belongs to. No-op when not eligible.
     *
     * A top-level parent moves as its whole group and is flattened beneath the destination, so the
     * two-level invariant holds and the moved parent keeps its own checked state.
     *
     * Under an automatic sort the visible order becomes the Manual baseline in the same repository
     * transaction, so the item is placed relative to the group the user could actually see above it
     * and a failed change commits nothing.
     */
    fun makeSubItem(visibleRowIds: List<Long>, item: ListItemEntity, precedingRow: ListItemEntity) {
        viewModelScope.launch {
            isHierarchyTransitionPending = true
            try {
                val baseline = withContext(ioDispatcher) { visibleOrderBaseline(visibleRowIds) }
                if (baseline == null && itemSort != ItemSort.MANUAL) return@launch
                val mutation = withContext(ioDispatcher) {
                    listMutations.makeSubItem(item.id, precedingRow.itemId, baseline)
                }
                applyCheckedStateReminderTransitions(mutation)
                if (baseline != null) selectItemSort(ItemSort.MANUAL)
            } finally {
                isHierarchyTransitionPending = false
            }
        }
    }

    /**
     * Moves [item] to top level, leaving its former siblings under the old parent.
     *
     * Under an automatic sort the visible order becomes the Manual baseline in the same repository
     * transaction, so the promoted item lands next to the group the user could actually see above it
     * and a failed change commits nothing.
     */
    fun moveToTopLevel(visibleRowIds: List<Long>, item: ListItemEntity) {
        viewModelScope.launch {
            isHierarchyTransitionPending = true
            try {
                val baseline = withContext(ioDispatcher) { visibleOrderBaseline(visibleRowIds) }
                if (baseline == null && itemSort != ItemSort.MANUAL) return@launch
                val mutation = withContext(ioDispatcher) { listMutations.moveToTopLevel(item.id, baseline) }
                applyCheckedStateReminderTransitions(mutation)
                if (baseline != null) selectItemSort(ItemSort.MANUAL)
            } finally {
                isHierarchyTransitionPending = false
            }
        }
    }

    /**
     * Removes favourite from all currently selected list items.
     */
    fun unfavouriteSelectedItems() {
        val ids = selectedItemIds.toList()
        selectedItemIds = emptySet()
        val now = System.currentTimeMillis()
        viewModelScope.launch(ioDispatcher) {
            ids.forEach { dao.setFavourite(it, false, now) }
        }
    }

    // ── Item drag ordering (#928) ────────────────────────────────────────────────────────────────

    /**
     * Persists the placement of a dragged row from the projection it was released in.
     *
     * Drag never changes depth: a top-level row stays top-level, and a child stays a child of the
     * group it was dropped into. [orderedRowIds] is the projected order after the move.
     *
     * Only a drag whose effective owner actually changes is a reparent. A top-level reorder of a row
     * whose requested parent is currently suppressed stays an order-only change, so the retained
     * requested placement survives it.
     *
     * Under an automatic sort that projection also becomes the Manual baseline, in one bounded
     * mutation, so switching sorts cannot reorder the rows the user did not move.
     */
    fun moveItemFromDrag(orderedRowIds: List<Long>, draggedId: Long) {
        if (draggedId !in orderedRowIds) return
        viewModelScope.launch {
            isHierarchyTransitionPending = true
            try {
                val dragged = withContext(ioDispatcher) { dao.getById(draggedId) }
                val rows = withContext(ioDispatcher) { orderedRowIds.mapNotNull { dao.getById(it) } }
                if (dragged == null || rows.size != orderedRowIds.size) return@launch
                if (rows.any { it.listId != dragged.listId }) return@launch
                val groups = withContext(ioDispatcher) { effectiveGroups(dragged.listId) } ?: return@launch
                val placement = dragPlacementFor(rows, topLevelRowIds(groups), draggedId) ?: return@launch
                val desiredOwnerRowId = if (placement.parentItemId == null) {
                    null
                } else {
                    rows.firstOrNull { it.itemId == placement.parentItemId }?.id ?: return@launch
                }
                // Effective owners on both sides, with top-level normalised to null. The Manual
                // branch below keeps its single-placement call: only the materialisation path can
                // carry a retained requested parent, and the repository rejects a placement whose
                // parent is not an effective top-level row.
                val currentOwnerRowId = owningRowId(groups, draggedId)?.takeIf { it != draggedId }
                val reparentedRow = if (currentOwnerRowId == desiredOwnerRowId) {
                    null
                } else {
                    draggedId to desiredOwnerRowId
                }
                if (itemSort != ItemSort.MANUAL) {
                    val baseline = visibleOrderBaseline(orderedRowIds, reparentedRow) ?: return@launch
                    val mutation = withContext(ioDispatcher) {
                        listMutations.applyVisibleHierarchyOrder(baseline.listId, baseline.rows)
                    }
                    applyCheckedStateReminderTransitions(mutation)
                    selectItemSort(ItemSort.MANUAL)
                    return@launch
                }
                val mutation = withContext(ioDispatcher) {
                    listMutations.moveItem(
                        dragged.id,
                        placement.parentItemId,
                        OrderKey.between(placement.lowerOrderKey, placement.upperOrderKey),
                    )
                }
                applyCheckedStateReminderTransitions(mutation)
            } finally {
                isHierarchyTransitionPending = false
            }
        }
    }

    /**
     * The order the user is currently looking at, as a baseline the repository can materialise.
     *
     * Owners come from the effective hierarchy, which is what the visible projection shows for an
     * indent, an outdent, or a drag that only reordered rows. [reparentedRow] names the single row a
     * drag dropped into another group, paired with the row that now owns it.
     *
     * Returns null when the list is already on Manual, where the visible order is the persisted
     * order and nothing needs materialising.
     */
    private suspend fun visibleOrderBaseline(
        visibleRowIds: List<Long>,
        reparentedRow: Pair<Long, Long?>? = null,
    ): ListMutationRepository.VisibleOrderBaseline? {
        if (itemSort == ItemSort.MANUAL) return null
        val listId = withContext(ioDispatcher) {
            visibleRowIds.firstOrNull()?.let { dao.getById(it)?.listId }
        } ?: return null
        val groups = withContext(ioDispatcher) { effectiveGroups(listId) } ?: return null
        val rows = visibleRowIds.mapNotNull { rowId ->
            val isReparented = reparentedRow?.first == rowId
            val owner = if (isReparented) {
                reparentedRow.second
            } else {
                owningRowId(groups, rowId) ?: return@mapNotNull null
            }
            ListMutationRepository.VisibleHierarchyRow(
                rowId = rowId,
                parentRowId = owner.takeIf { it != rowId },
                reparent = isReparented,
            )
        }
        if (rows.isEmpty()) return null
        return ListMutationRepository.VisibleOrderBaseline(listId, rows)
    }

    private suspend fun effectiveGroups(listId: Long): List<EffectiveHierarchyGroup<ListItemEntity>>? {
        val all = dao.getAllByListUnordered(listId).filter { it.lifecycle == ListLifecycle.ACTIVE.name }
        if (all.isEmpty()) return null
        return EffectiveHierarchyProjection.derive(
            all,
            itemId = { it.itemId },
            parentItemId = { it.parentItemId },
            orderKey = { it.orderKey },
            placementStamp = { VersionStamp(it.placementLogicalClock, it.placementStampActorId) },
        )
    }
    private var itemReorderJob: Job? = null

    fun reorderItems(orderedIds: List<Long>) {
        selectItemSort(ItemSort.MANUAL)
        itemReorderJob?.cancel()
        itemReorderJob = viewModelScope.launch(ioDispatcher) {
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
        viewModelScope.launch(ioDispatcher) {
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

    /** Toggles isFavourite and bumps updatedAt + parent list updatedAt. */
    fun toggleFavourite(item: ListItemEntity) {
        val now = System.currentTimeMillis()
        viewModelScope.launch(ioDispatcher) {
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
        viewModelScope.launch(ioDispatcher) {
            val checked = dao.getAllByList(listId).filter { it.checked }
            checked.forEach { scheduler.cancel(it.id) }
            listMutations.deleteItems(checked.map { it.id })
        }
    }

    /** Deletes a list by recording tombstones; rows remain for sync convergence. */
    fun deleteList(listId: Long) {
        viewModelScope.launch(ioDispatcher) {
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
        viewModelScope.launch(ioDispatcher) {
            dao.getAllWithNotification(id).forEach { scheduler.cancel(it.id) }
            listNameDao.archiveList(id = id, archivedAt = now, updatedAt = now)
        }
    }

    /** Restores an archived list back to the active view, re-scheduling any future alarms. */
    fun restoreList(id: Long) {
        viewModelScope.launch(ioDispatcher) {
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
        viewModelScope.launch(ioDispatcher) {
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