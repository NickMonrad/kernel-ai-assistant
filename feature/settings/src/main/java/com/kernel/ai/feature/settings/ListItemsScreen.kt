package com.kernel.ai.feature.settings

import android.content.Intent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.drop
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kernel.ai.core.memory.entity.ListItemEntity
import com.kernel.ai.core.memory.lists.EffectiveHierarchyGroup
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

// ── Date / timestamp helpers ─────────────────────────────────────────────────────────────────────

private val dayMonthFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")

private fun epochToLocalDate(epochMs: Long): LocalDate =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()

private fun formatRelativeDate(epochMs: Long): String {
    val date = epochToLocalDate(epochMs)
    val today = LocalDate.now()
    return when {
        date == today -> "today"
        date == today.minusDays(1) -> "yesterday"
        else -> date.format(dayMonthFormatter)
    }
}

private fun formatTimestamp(item: ListItemEntity): String =
    if (item.updatedAt > item.createdAt) {
        "Updated ${formatRelativeDate(item.updatedAt)}"
    } else {
        "Added ${formatRelativeDate(item.createdAt)}"
    }

private fun formatDueDate(dueAt: Long): String {
    val date = epochToLocalDate(dueAt)
    val today = LocalDate.now()
    return when {
        date.isBefore(today) -> "Overdue"
        date == today -> "Due today"
        date == today.plusDays(1) -> "Due tomorrow"
        else -> "Due ${date.format(dayMonthFormatter)}"
    }
}

private fun isOverdue(dueAt: Long, checked: Boolean): Boolean =
    !checked && epochToLocalDate(dueAt).isBefore(LocalDate.now())

// ── Sort / filter label helpers ──────────────────────────────────────────────────────────────────

private fun ItemSort.label(): String = when (this) {
    ItemSort.MANUAL -> "Manual order"
    ItemSort.CREATED_NEWEST -> "Created (newest first)"
    ItemSort.CREATED_OLDEST -> "Created (oldest first)"
    ItemSort.UPDATED_NEWEST -> "Updated (newest first)"
    ItemSort.NAME_ASC -> "Name A→Z"
    ItemSort.NAME_DESC -> "Name Z→A"
    ItemSort.DUE_SOONEST -> "Due date (soonest first)"
    ItemSort.FAVOURITES_FIRST -> "Favourites first"
}

private fun ItemFilter.label(): String = when (this) {
    ItemFilter.ALL -> "All items"
    ItemFilter.FAVOURITES_ONLY -> "Favourites only"
    ItemFilter.ACTIVE_ONLY -> "Active only"
    ItemFilter.COMPLETED_ONLY -> "Completed only"
}

internal sealed interface AddedItemRevealDecision {
    object AwaitingProjection : AddedItemRevealDecision
    object HiddenBySearchOrFilter : AddedItemRevealDecision
    object AlreadyVisible : AddedItemRevealDecision
    data class ScrollToIndex(val index: Int) : AddedItemRevealDecision
}

internal fun decideAddedItemReveal(
    sourceItemExists: Boolean,
    displayedActiveIndex: Int,
    renderedActiveIndex: Int,
    itemIsVisible: Boolean,
    currentLayoutItemCount: Int,
    expectedLayoutItemCount: Int,
): AddedItemRevealDecision {
    if (!sourceItemExists) return AddedItemRevealDecision.AwaitingProjection
    if (displayedActiveIndex < 0) return AddedItemRevealDecision.HiddenBySearchOrFilter
    if (renderedActiveIndex != displayedActiveIndex || currentLayoutItemCount != expectedLayoutItemCount) {
        return AddedItemRevealDecision.AwaitingProjection
    }
    return if (itemIsVisible) {
        AddedItemRevealDecision.AlreadyVisible
    } else {
        AddedItemRevealDecision.ScrollToIndex(displayedActiveIndex)
    }
}

private sealed interface ActiveHierarchyEntry {
    val key: Any

    data class Item(
        val row: ListItemEntity,
        val isChild: Boolean,
        val dropTarget: HierarchyDropTarget?,
    ) : ActiveHierarchyEntry {
        override val key: Any get() = row.id
    }

    data class Insertion(
        val target: HierarchyDropTarget,
        val isChild: Boolean,
    ) : ActiveHierarchyEntry {
        override val key: Any = target.stableKey()
    }
}

/**
 * Frozen at drag start so every target recomputes from the original projected hierarchy, not the
 * previous preview.
 */
private data class HierarchyDragBaseline(
    val visibleGroups: List<EffectiveHierarchyGroup<ListItemEntity>>,
    val completeGroups: List<EffectiveHierarchyGroup<ListItemEntity>>,
    val collapsedParentItemIds: Set<String>,
)

private fun HierarchyDropTarget.stableKey(): String = when (this) {
    is HierarchyDropTarget.TopLevelInsertion ->
        "drop_top_${beforeParentRowId ?: "end"}"
    is HierarchyDropTarget.ChildInsertion ->
        "drop_child_${parentRowId}_${beforeChildRowId ?: "end"}"
    is HierarchyDropTarget.ParentRow -> "drop_parent_$parentRowId"
}

private fun activeHierarchyEntries(
    groups: List<EffectiveHierarchyGroup<ListItemEntity>>,
    hierarchyEditingEnabled: Boolean,
    activeParentRowTargetId: Long?,
): List<ActiveHierarchyEntry> = buildList {
    groups.forEach { group ->
        if (hierarchyEditingEnabled) {
            add(
                ActiveHierarchyEntry.Insertion(
                    HierarchyDropTarget.TopLevelInsertion(group.parent.id),
                    isChild = false,
                ),
            )
        }
        add(
            ActiveHierarchyEntry.Item(
                row = group.parent,
                isChild = false,
                dropTarget = if (hierarchyEditingEnabled) {
                    HierarchyDropTarget.ParentRow(group.parent.id)
                } else null,
            ),
        )
        group.children.forEach { child ->
            if (hierarchyEditingEnabled) {
                add(
                    ActiveHierarchyEntry.Insertion(
                        HierarchyDropTarget.ChildInsertion(group.parent.id, child.id),
                        isChild = true,
                    ),
                )
            }
            add(
                ActiveHierarchyEntry.Item(
                    row = child,
                    isChild = true,
                    dropTarget = null,
                ),
            )
        }
        if (hierarchyEditingEnabled) {
            val finalChildTarget = if (activeParentRowTargetId == group.parent.id) {
                HierarchyDropTarget.ParentRow(group.parent.id)
            } else {
                HierarchyDropTarget.ChildInsertion(group.parent.id, beforeChildRowId = null)
            }
            add(ActiveHierarchyEntry.Insertion(finalChildTarget, isChild = true))
        }
    }
    if (hierarchyEditingEnabled && groups.isNotEmpty()) {
        add(
            ActiveHierarchyEntry.Insertion(
                HierarchyDropTarget.TopLevelInsertion(beforeParentRowId = null),
                isChild = false,
            ),
        )
    }
}

// ── Screen ───────────────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ListItemsScreen(
    listId: Long,
    onBack: () -> Unit = {},
    onNavigateToVoiceActions: () -> Unit = {},
    onNavigateToNextcloudList: (Long?, String?) -> Unit = { _, _ -> },
    onNavigateToNextcloudSharing: (String) -> Unit = {},
    externalMessage: String? = null,
    onExternalMessageShown: () -> Unit = {},
    viewModel: ListsViewModel = hiltViewModel(),
) {
    val displayedHierarchy by viewModel.observeDisplayedHierarchy(listId).collectAsStateWithLifecycle()
    val listEntities by viewModel.listEntities.collectAsStateWithLifecycle()
    val searchQuery by viewModel.itemSearchQuery.collectAsStateWithLifecycle()
    val nextcloudBindings by viewModel.nextcloudBindings.collectAsStateWithLifecycle()
    val nextcloudAccountConfigured by viewModel.nextcloudAccountConfigured.collectAsStateWithLifecycle()

    // Restores this list's saved sort so reopening never falls back to the default.
    LaunchedEffect(listId) { viewModel.bindItemList(listId) }
    val itemSortReady = viewModel.itemSortReadyForListId == listId
    val collapsedPreferencesReady = viewModel.collapsedParentPreferencesReadyForListId == listId
    val hierarchyPreferencesReady = itemSortReady && collapsedPreferencesReady

    val displayName = listEntities.firstOrNull { it.id == listId }?.name ?: ""
    val collectionId = listEntities.firstOrNull { it.id == listId }?.collectionId
    val nextcloudBinding = collectionId?.let { nextcloudBindings[it] }
    val nextcloudState = nextcloudBinding?.state
    val nextcloudActions = NextcloudRowActions(
        state = nextcloudState,
        remoteWritable = nextcloudBinding?.remoteWritable ?: true,
        remoteAvailable = nextcloudBinding?.remoteAvailable ?: true,
        unsyncedChanges = nextcloudBinding?.unsyncedChanges == true,
        onSyncWithNextcloud = {
            if (nextcloudAccountConfigured) {
                viewModel.syncListWithNextcloud(listId)
            } else {
                onNavigateToNextcloudList(listId, displayName)
            }
        },
        onStopSync = { collectionId?.let(viewModel::stopListNextcloudSync) },
        onResumeSync = { collectionId?.let(viewModel::resumeListNextcloudSync) },
        onManageSharing = { collectionId?.let(onNavigateToNextcloudSharing) },
        onSaveLocalCopy = { collectionId?.let(viewModel::saveListAsLocalCopy) },
        onKeepLocalCopy = { collectionId?.let(viewModel::keepUnsyncedListAsLocalCopy) },
        onDiscardLocalChanges = { collectionId?.let(viewModel::discardUnsyncedListChanges) },
        // Opening the bound-list state must never look like a new contextual setup.
        onOpenNextcloud = { onNavigateToNextcloudList(null, null) },
    )
    val activeGroups = displayedHierarchy.activeGroups
    val completedGroups = displayedHierarchy.completedGroups
    val completeGroups = remember(displayedHierarchy.sourceItems) {
        completeEffectiveGroups(displayedHierarchy.sourceItems)
    }
    val completeActiveGroups = completeGroups.filterNot { it.parent.checked }
    val completeParentItemIds = completeGroups
        .filter { it.children.isNotEmpty() }
        .map { it.parent.itemId }
        .toSet()
    val collapsedParentItemIds = if (collapsedPreferencesReady) {
        viewModel.collapsedParentItemIds
    } else {
        emptySet()
    }
    val visibleActiveGroups = visibleHierarchyGroups(activeGroups, collapsedParentItemIds, searchQuery)
    val visibleCompletedGroups = visibleHierarchyGroups(completedGroups, collapsedParentItemIds, searchQuery)
    val sortedActive = visibleActiveGroups.flatMap { listOf(it.parent) + it.children }
    val sortedCompleted = completedGroups.flatMap { listOf(it.parent) + it.children }
    val visibleSortedCompleted = visibleCompletedGroups.flatMap { listOf(it.parent) + it.children }
    val allItems = activeGroups.flatMap { listOf(it.parent) + it.children } + sortedCompleted

    var showAddDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    val renameInitialValue = remember(showRenameDialog) { if (showRenameDialog) displayName else "" }
    var completedExpanded by rememberSaveable { mutableStateOf(viewModel.itemFilter == ItemFilter.COMPLETED_ONLY) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var editingItem by remember { mutableStateOf<ListItemEntity?>(null) }
    var addSubItemParentId by remember(listId) { mutableStateOf<String?>(null) }
    var showItemBulkDeleteDialog by remember { mutableStateOf(false) }

    fun visibleChildRowIdsToHide(parentItemId: String? = null): Set<Long> {
        if (searchQuery.isNotBlank()) return emptySet()
        val renderedGroups = visibleActiveGroups +
            if (completedExpanded) visibleCompletedGroups else emptyList()
        return renderedGroups.asSequence()
            .filter { parentItemId == null || it.parent.itemId == parentItemId }
            .flatMap { it.children.asSequence() }
            .map { it.id }
            .toSet()
    }

    val selectedItemIds = viewModel.selectedItemIds
    val isItemMultiSelectMode = viewModel.isItemMultiSelectMode

    LaunchedEffect(collapsedParentItemIds, searchQuery, selectedItemIds, completeGroups) {
        if (searchQuery.isNotBlank()) return@LaunchedEffect
        val collapsedChildIds = completeGroups.asSequence()
            .filter { it.parent.itemId in collapsedParentItemIds }
            .flatMap { it.children.asSequence() }
            .map { it.id }
            .toSet()
        viewModel.deselectItems(selectedItemIds intersect collapsedChildIds)
    }
    // Content changes are unavailable whenever the provider refuses them: a read-only share, a
    // removed share, or local work already stranded by either (#1548). Local-only fields stay usable.
    val isRemoteReadOnly = nextcloudState != null &&
        (nextcloudBinding?.remoteWritable == false || nextcloudBinding?.remoteAvailable == false)
    val hasUnsyncedChanges = nextcloudBinding?.unsyncedChanges == true
    LaunchedEffect(isRemoteReadOnly, hasUnsyncedChanges) {
        if (isRemoteReadOnly || hasUnsyncedChanges) {
            viewModel.exitItemMultiSelect()
            editingItem = null
            showAddDialog = false
            addSubItemParentId = null
            showRenameDialog = false
            showItemBulkDeleteDialog = false
        }
    }
    val hierarchyEditingEnabled = isHierarchyEditingEnabled(
        itemFilter = viewModel.itemFilter,
        searchQuery = searchQuery,
        isMultiSelectMode = isItemMultiSelectMode,
    ) && !isRemoteReadOnly

    var showSelectAllMenu by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val pendingCreatedItemIds = remember(listId) { mutableStateListOf<Long>() }

    LaunchedEffect(viewModel.nextcloudMessage, externalMessage) {
        val message = viewModel.nextcloudMessage ?: externalMessage
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.clearNextcloudMessage()
            onExternalMessageShown()
        }
    }
    // ── Explicit drag targets and shared preview/commit placement (#1581) ─────────────────────────
    var itemDragInProgress by remember { mutableStateOf(false) }
    var dragSourceId by remember { mutableStateOf<Long?>(null) }
    var pendingPlacement by remember(listId) { mutableStateOf<PendingHierarchyPlacement?>(null) }
    var dragBaseline by remember(listId) { mutableStateOf<HierarchyDragBaseline?>(null) }
    val previewActiveGroups = hierarchyGroupsForDragPreview(
        placement = pendingPlacement,
        visibleGroups = visibleActiveGroups,
        collapsedParentItemIds = collapsedParentItemIds,
        searchQuery = searchQuery,
    )
    val renderedActiveRows = previewActiveGroups.flatMap { listOf(it.parent) + it.children }
    val highlightedParentRowId = hierarchyDropTargetHighlightParentRowId(pendingPlacement)
    val activeParentRowTargetId =
        (pendingPlacement?.target as? HierarchyDropTarget.ParentRow)?.parentRowId
    val activeEntries = activeHierarchyEntries(
        previewActiveGroups,
        hierarchyEditingEnabled,
        activeParentRowTargetId,
    )

    val lazyListState = rememberLazyListState()
    val currentDisplayedHierarchy = rememberUpdatedState(displayedHierarchy)
    val currentSortedActive = rememberUpdatedState(sortedActive)
    val currentSearchQuery = rememberUpdatedState(searchQuery)
    val currentActiveEntries = rememberUpdatedState(activeEntries)
    val currentVisibleActiveGroups = rememberUpdatedState(visibleActiveGroups)
    val currentCompleteActiveGroups = rememberUpdatedState(completeActiveGroups)
    val currentCollapsedParentItemIds = rememberUpdatedState(collapsedParentItemIds)
    val currentCompletedExpanded = rememberUpdatedState(completedExpanded)
    val currentPendingPlacement = rememberUpdatedState(pendingPlacement)

    LaunchedEffect(pendingPlacement, itemDragInProgress) {
        val committedPreview = pendingPlacement ?: return@LaunchedEffect
        if (itemDragInProgress || !committedPreview.changed) return@LaunchedEffect
        withTimeoutOrNull(10_000) {
            snapshotFlow {
                val persistedGroups = completeEffectiveGroups(
                    currentDisplayedHierarchy.value.sourceItems,
                ).filterNot { it.parent.checked }
                !viewModel.isHierarchyTransitionPending &&
                    viewModel.itemSort == ItemSort.MANUAL &&
                    persistedGroups.map { it.parent.id to it.children.map(ListItemEntity::id) } ==
                    committedPreview.resultGroups.map { it.parent.id to it.children.map(ListItemEntity::id) }
            }.first { it }
        }
        if (pendingPlacement == committedPreview) pendingPlacement = null
    }

    LaunchedEffect(listId, pendingCreatedItemIds.firstOrNull()) {
        val itemId = pendingCreatedItemIds.firstOrNull() ?: return@LaunchedEffect
        val decision = snapshotFlow {
            val displayed = currentDisplayedHierarchy.value
            val activeItems = currentSortedActive.value
            val entries = currentActiveEntries.value
            val completedGroups = displayed.completedGroups
            val visibleCompletedGroups = visibleHierarchyGroups(
                completedGroups,
                currentCollapsedParentItemIds.value,
                currentSearchQuery.value,
            )
            val expectedItemCount =
                entries.size +
                    (if (completedGroups.isNotEmpty()) 1 else 0) +
                    (if (currentCompletedExpanded.value) visibleCompletedGroups.size else 0) +
                    1
            val activeIndex = activeItems.indexOfFirst { it.id == itemId }
            val renderedIndex = entries.indexOfFirst {
                it is ActiveHierarchyEntry.Item && it.row.id == itemId
            }
            decideAddedItemReveal(
                sourceItemExists = displayed.sourceItems.any { it.id == itemId },
                displayedActiveIndex = if (activeIndex < 0) -1 else renderedIndex,
                renderedActiveIndex = renderedIndex,
                itemIsVisible = lazyListState.layoutInfo.visibleItemsInfo.any { it.key == itemId },
                currentLayoutItemCount = lazyListState.layoutInfo.totalItemsCount,
                expectedLayoutItemCount = expectedItemCount,
            )
        }.first { it != AddedItemRevealDecision.AwaitingProjection }

        when (decision) {
            AddedItemRevealDecision.AwaitingProjection ->
                error("A pending list-item reveal cannot be handled")
            AddedItemRevealDecision.HiddenBySearchOrFilter ->
                snackbarHostState.showSnackbar("Added, but hidden by the current search or filter.")
            AddedItemRevealDecision.AlreadyVisible -> Unit
            is AddedItemRevealDecision.ScrollToIndex ->
                lazyListState.animateScrollToItem(decision.index)
        }
        pendingCreatedItemIds.removeAt(0)
    }

    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        if (!hierarchyEditingEnabled) return@rememberReorderableLazyListState
        val draggedId = from.key as? Long ?: return@rememberReorderableLazyListState
        val targetEntry = currentActiveEntries.value.firstOrNull { it.key == to.key }
        if (targetEntry == null) {
            if (pendingPlacement != null) {
                pendingPlacement = null
                awaitLayoutChange(lazyListState)
            }
            return@rememberReorderableLazyListState
        }
        val target = when (targetEntry) {
            is ActiveHierarchyEntry.Item -> targetEntry.dropTarget
            is ActiveHierarchyEntry.Insertion -> targetEntry.target
        }
        if (target == null) {
            if (pendingPlacement != null) {
                pendingPlacement = null
                awaitLayoutChange(lazyListState)
            }
            return@rememberReorderableLazyListState
        }
        val baseline = dragBaseline ?: return@rememberReorderableLazyListState
        val placement = pendingHierarchyPlacement(
            completeGroups = baseline.completeGroups,
            visibleGroups = baseline.visibleGroups,
            collapsedParentItemIds = baseline.collapsedParentItemIds,
            draggedRowId = draggedId,
            target = target,
        )
        if (placement == null) {
            if (pendingPlacement != null) {
                pendingPlacement = null
                awaitLayoutChange(lazyListState)
            }
            return@rememberReorderableLazyListState
        }
        if (placement != pendingPlacement) {
            pendingPlacement = placement
            // Recompose the exact target slot before the reorderable modifier applies its layout
            // compensation, preserving smooth auto-scroll and avoiding a one-frame snap.
            awaitLayoutChange(lazyListState)
        }
    }

    LaunchedEffect(Unit) {
        snapshotFlow { viewModel.itemFilter }
            .drop(1) // skip initial emission; rememberSaveable owns first-run state
            .collect { filter ->
                if (filter == ItemFilter.COMPLETED_ONLY) completedExpanded = true
            }
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.clearItemSearchQuery()
            viewModel.exitItemMultiSelect()
        }
    }

    Scaffold(
        topBar = {
            if (isItemMultiSelectMode) {
                // ── Contextual multi-select bar ─────────────────────────────────────────────
                TopAppBar(
                    title = { Text("${selectedItemIds.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.exitItemMultiSelect() }) {
                            Icon(Icons.Default.Close, contentDescription = "Exit selection")
                        }
                    },
                    actions = {
                        // Mark selected items complete
                        IconButton(onClick = { viewModel.markSelectedItemsComplete() }) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = "Mark complete",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        // Unmark selected items (set unchecked)
                        IconButton(onClick = { viewModel.unmarkSelectedItemsComplete() }) {
                            Icon(
                                Icons.Default.RadioButtonUnchecked,
                                contentDescription = "Unmark complete",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        // Bulk-favourite / unfavourite selected items
                        val allSelectedFavourited = selectedItemIds.isNotEmpty() &&
                            selectedItemIds.all { id ->
                                allItems.firstOrNull { it.id == id }?.isFavourite == true
                            }
                        IconButton(
                            onClick = {
                                if (allSelectedFavourited) viewModel.unfavouriteSelectedItems()
                                else viewModel.favouriteSelectedItems()
                            },
                        ) {
                            Icon(
                                if (allSelectedFavourited) Icons.Default.StarBorder else Icons.Default.Star,
                                contentDescription = if (allSelectedFavourited) "Remove from favourites" else "Add to favourites",
                                tint = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        // Delete selected items
                        IconButton(
                            onClick = { showItemBulkDeleteDialog = true },
                            enabled = selectedItemIds.isNotEmpty(),
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete selected",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                        // Overflow: selected sharing and selection controls
                        Box {
                            IconButton(onClick = { showSelectAllMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More options")
                            }
                            DropdownMenu(
                                expanded = showSelectAllMenu,
                                onDismissRequest = { showSelectAllMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Share selected") },
                                    onClick = {
                                        showSelectAllMenu = false
                                        coroutineScope.launch {
                                            val text = viewModel.buildShareText(listId, selectedItemIds)
                                            val intent = Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_TEXT, text)
                                                putExtra(Intent.EXTRA_TITLE, displayName.replaceFirstChar { it.uppercase() })
                                            }
                                            context.startActivity(Intent.createChooser(intent, "Share selected items"))
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Select none") },
                                    onClick = {
                                        showSelectAllMenu = false
                                        viewModel.exitItemMultiSelect()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Select all") },
                                    onClick = {
                                        showSelectAllMenu = false
                                        viewModel.selectAllItems(
                                            visibleSelectableItemIds(
                                                activeRows = sortedActive,
                                                completedRows = visibleSortedCompleted,
                                                completedExpanded = completedExpanded,
                                            ),
                                        )
                                    },
                                )
                            }
                        }
                    },
                )
            } else {
                // ── Normal top app bar ───────────────────────────────────────────────────────
                TopAppBar(
                    title = {
                        Text(
                            text = displayName.replaceFirstChar { it.uppercase() },
                            modifier = Modifier.clickable(
                                enabled = !isRemoteReadOnly,
                                onClick = { showRenameDialog = true },
                            ),
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        if (sortedCompleted.isNotEmpty()) {
                            TextButton(
                                onClick = { viewModel.clearChecked(listId) },
                                enabled = !isRemoteReadOnly,
                            ) {
                                Text("Clear done")
                            }
                        }
                        // Sort / filter menu
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Sort and filter")
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Reorder & group") },
                                    enabled = !isRemoteReadOnly,
                                    onClick = {
                                        viewModel.enterManualHierarchyEditing()
                                        showSortMenu = false
                                    },
                                    trailingIcon = if (viewModel.itemSort == ItemSort.MANUAL) {
                                        { Icon(Icons.Default.Check, contentDescription = null) }
                                    } else null,
                                )
                                if (completeParentItemIds.isNotEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("Expand all sub-items") },
                                        enabled = hierarchyPreferencesReady,
                                        onClick = {
                                            viewModel.expandAllSubItems(listId)
                                            showSortMenu = false
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Collapse all sub-items") },
                                        enabled = hierarchyPreferencesReady,
                                        onClick = {
                                            viewModel.collapseAllSubItems(
                                                listId,
                                                parentItemIds = completeParentItemIds,
                                                hiddenChildRowIds = visibleChildRowIdsToHide(),
                                            )
                                            showSortMenu = false
                                        },
                                    )
                                }
                                HorizontalDivider()
                                // ── Sort section ──────────────────────────────────────────
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "Sort by",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    },
                                    onClick = {},
                                    enabled = false,
                                )
                                ItemSort.entries.forEach { sort ->
                                    DropdownMenuItem(
                                        text = { Text(sort.label()) },
                                        onClick = {
                                            viewModel.selectItemSort(sort)
                                            showSortMenu = false
                                        },
                                        trailingIcon = if (viewModel.itemSort == sort) {
                                            { Icon(Icons.Default.Check, contentDescription = null) }
                                        } else null,
                                    )
                                }
                                HorizontalDivider()
                                // ── Filter section ────────────────────────────────────────
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "Filter",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    },
                                    onClick = {},
                                    enabled = false,
                                )
                                ItemFilter.entries.forEach { filter ->
                                    DropdownMenuItem(
                                        text = { Text(filter.label()) },
                                        onClick = {
                                            viewModel.itemFilter = filter
                                            showSortMenu = false
                                        },
                                        trailingIcon = if (viewModel.itemFilter == filter) {
                                            { Icon(Icons.Default.Check, contentDescription = null) }
                                        } else null,
                                    )
                                }
                                HorizontalDivider()
                                // ── Share / Copy section ──────────────────────────────────
                                DropdownMenuItem(
                                    text = { Text("Share as text") },
                                    onClick = {
                                        showSortMenu = false
                                        coroutineScope.launch {
                                            val text = viewModel.buildShareText(listId)
                                            val intent = Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_TEXT, text)
                                                putExtra(Intent.EXTRA_TITLE, displayName.replaceFirstChar { it.uppercase() })
                                            }
                                            context.startActivity(Intent.createChooser(intent, "Share list"))
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Copy to clipboard") },
                                    onClick = {
                                        showSortMenu = false
                                        coroutineScope.launch {
                                            val text = viewModel.buildShareText(listId)
                                            clipboardManager.setText(AnnotatedString(text))
                                            snackbarHostState.showSnackbar("List copied to clipboard")
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Export Jandal file") },
                                    onClick = {
                                        showSortMenu = false
                                        showExportDialog = true
                                    },
                                    modifier = Modifier.testTag("list_detail_export"),
                                )
                                NextcloudOverflowItems(
                                    actions = nextcloudActions,
                                    onDismiss = { showSortMenu = false },
                                    testTagPrefix = "list_detail",
                                )
                            }
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SmallFloatingActionButton(
                    onClick = { if (!isRemoteReadOnly) onNavigateToVoiceActions() },
                    containerColor = if (isRemoteReadOnly) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                    contentColor = if (isRemoteReadOnly) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                ) {
                    Icon(Icons.Default.Mic, contentDescription = "Voice input")
                }
                FloatingActionButton(
                    onClick = {
                        if (!isRemoteReadOnly) {
                            addSubItemParentId = null
                            showAddDialog = true
                        }
                    },
                    containerColor = if (isRemoteReadOnly) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    },
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add item")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (nextcloudState != null &&
                (
                    nextcloudBinding?.remoteWritable == false ||
                        nextcloudBinding?.remoteAvailable == false ||
                        hasUnsyncedChanges
                    )
            ) {
                NextcloudAccessBanner(
                    unsyncedChanges = hasUnsyncedChanges,
                    available = nextcloudBinding?.remoteAvailable != false,
                    onKeepLocalCopy = nextcloudActions.onKeepLocalCopy,
                    onDiscardLocalChanges = nextcloudActions.onDiscardLocalChanges,
                    onSaveLocalCopy = nextcloudActions.onSaveLocalCopy,
                )
            }
            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = viewModel::setItemSearchQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search items") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = viewModel::clearItemSearchQuery) {
                            Icon(Icons.Default.Close, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
            )

            if (!hierarchyPreferencesReady) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else if (allItems.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(modifier = Modifier.height(32.dp))
                    Text(
                        text = "No items yet.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Tap + to add an item, or ask Jandal.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = lazyListState,
                ) {
                    items(activeEntries, key = { it.key }) { entry ->
                        ReorderableItem(
                            reorderState,
                            key = entry.key,
                            animateItemModifier = Modifier.animateItem(
                                placementSpec = tween(
                                    durationMillis = 250,
                                    easing = FastOutSlowInEasing,
                                ),
                            ),
                        ) { isDragging ->
                            when (entry) {
                                is ActiveHierarchyEntry.Insertion -> {
                                    HierarchyDropIndicator(
                                        target = entry.target,
                                        isChild = entry.isChild,
                                        isActive = pendingPlacement?.target == entry.target,
                                    )
                                }
                                is ActiveHierarchyEntry.Item -> {
                                    val item = entry.row
                                    val isChild = entry.isChild
                                    val isDragHandlePressed = remember(entry.key) { mutableStateOf(false) }
                                    val rowColor by animateColorAsState(
                                        when {
                                            highlightedParentRowId == item.id ->
                                                MaterialTheme.colorScheme.secondaryContainer
                                            isDragging -> MaterialTheme.colorScheme.surfaceVariant
                                            else -> MaterialTheme.colorScheme.surface
                                        },
                                        label = "item_drag_color",
                                    )
                                    val elevation by animateDpAsState(
                                        if (isDragging) 4.dp else 0.dp,
                                        label = "item_drag_elevation",
                                    )
                                    val rowIndex = renderedActiveRows.indexOfFirst { it.id == item.id }
                                    val precedingRow = renderedActiveRows.getOrNull(rowIndex - 1)
                                    val depthGesturesEnabled = hierarchyEditingEnabled && !itemDragInProgress
                                    Surface(
                                        // A translucent overlay keeps the highlighted destination
                                        // and insertion target visible under the moving row.
                                        modifier = Modifier.graphicsLayer {
                                            alpha = if (isDragging) 0.68f else 1f
                                        },
                                        color = rowColor,
                                        shadowElevation = elevation,
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .background(rowColor)
                                                .then(if (isChild) Modifier.padding(start = 24.dp) else Modifier),
                                        ) {
                                            SwipeToChangeDepthRow(
                                                handleGesturesEnabled = depthGesturesEnabled,
                                                canMakeSubItem = depthGesturesEnabled &&
                                                    canMakeSubItemRow(renderedActiveRows, previewActiveGroups, item.id),
                                                canMoveToTopLevel = depthGesturesEnabled &&
                                                    canMoveToTopLevelRow(previewActiveGroups, item.id),
                                                onHandlePointerChanged = { isDragHandlePressed.value = it },
                                                onMakeSubItem = {
                                                    precedingRow?.let {
                                                        viewModel.makeSubItem(
                                                            visibleRowIds = renderedActiveRows.map(ListItemEntity::id),
                                                            item = item,
                                                            precedingRow = it,
                                                        )
                                                    }
                                                },
                                                onMoveToTopLevel = {
                                                    viewModel.moveToTopLevel(
                                                        visibleRowIds = renderedActiveRows.map(ListItemEntity::id),
                                                        item = item,
                                                    )
                                                },
                                            ) { handleGestureModifier ->
                                                ListItemRow(
                                                    item = item,
                                                    isDragHandlePressed = isDragHandlePressed.value,
                                                    isMultiSelectMode = isItemMultiSelectMode,
                                                    isSelected = item.id in selectedItemIds,
                                                    interactionsEnabled = !isRemoteReadOnly,
                                                    showDragHandle = hierarchyEditingEnabled,
                                                    containerColor = rowColor,
                                                    showDisclosure = item.itemId in completeParentItemIds,
                                                    isExpanded = item.itemId !in collapsedParentItemIds,
                                                    onToggleExpanded = {
                                                        val collapse = item.itemId !in collapsedParentItemIds
                                                        viewModel.setParentCollapsed(
                                                            listId = listId,
                                                            parentItemId = item.itemId,
                                                            collapsed = collapse,
                                                            hiddenChildRowIds = if (collapse) {
                                                                visibleChildRowIdsToHide(item.itemId)
                                                            } else {
                                                                emptySet()
                                                            },
                                                        )
                                                    },
                                                    dragHandleModifier = if (hierarchyEditingEnabled) {
                                                        handleGestureModifier
                                                            .draggableHandle(
                                                            onDragStarted = {
                                                                dragBaseline = HierarchyDragBaseline(
                                                                    visibleGroups = currentVisibleActiveGroups.value,
                                                                    completeGroups = currentCompleteActiveGroups.value,
                                                                    collapsedParentItemIds =
                                                                        currentCollapsedParentItemIds.value,
                                                                )
                                                                itemDragInProgress = true
                                                                dragSourceId = item.id
                                                                pendingPlacement = null
                                                            },
                                                            onDragStopped = {
                                                                val source = dragSourceId
                                                                val placement = currentPendingPlacement.value
                                                                itemDragInProgress = false
                                                                if (source != null &&
                                                                    placement?.draggedRowId == source &&
                                                                    placement.changed
                                                                ) {
                                                                    viewModel.moveItemFromDrag(listId, placement)
                                                                } else {
                                                                    pendingPlacement = null
                                                                }
                                                                dragSourceId = null
                                                                dragBaseline = null
                                                            },
                                                        )
                                                    } else Modifier,
                                                    onToggle = { viewModel.toggleChecked(item) },
                                                    onEdit = { editingItem = item },
                                                    onToggleFavourite = { viewModel.toggleFavourite(item) },
                                                    onLongClick = { viewModel.enterItemMultiSelect(item.id) },
                                                    onSelectToggle = { viewModel.toggleItemSelection(item.id) },
                                                )
                                            }
                                        }
                                    }
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = if (isChild) 88.dp else 64.dp),
                                    )
                                }
                            }
                        }
                    }

                    // Completed section header
                    if (sortedCompleted.isNotEmpty()) {
                        item(key = "completed_header") {
                            ListItem(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { completedExpanded = !completedExpanded },
                                headlineContent = {
                                    Text(
                                        "Completed (${sortedCompleted.size})",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                trailingContent = {
                                    Icon(
                                        if (completedExpanded) Icons.Default.ExpandLess
                                        else Icons.Default.ExpandMore,
                                        contentDescription = if (completedExpanded) "Collapse" else "Expand",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                            )
                            HorizontalDivider()
                        }
                    }

                    // Completed groups (collapsible); child rows retain their parent context.
                    if (completedExpanded) {
                        items(visibleCompletedGroups, key = { "done_group_${it.parent.id}" }) { group ->
                            ListItemRow(
                                item = group.parent,
                                isMultiSelectMode = isItemMultiSelectMode,
                                isSelected = group.parent.id in selectedItemIds,
                                interactionsEnabled = !isRemoteReadOnly,
                                showDragHandle = false,
                                showDisclosure = group.parent.itemId in completeParentItemIds,
                                isExpanded = group.parent.itemId !in collapsedParentItemIds,
                                onToggleExpanded = {
                                    val collapse =
                                        group.parent.itemId !in collapsedParentItemIds
                                    viewModel.setParentCollapsed(
                                        listId = listId,
                                        parentItemId = group.parent.itemId,
                                        collapsed = collapse,
                                        hiddenChildRowIds = if (collapse) {
                                            visibleChildRowIdsToHide(group.parent.itemId)
                                        } else {
                                            emptySet()
                                        },
                                    )
                                },
                                onToggle = { viewModel.toggleChecked(group.parent) },
                                onEdit = { editingItem = group.parent },
                                onToggleFavourite = { viewModel.toggleFavourite(group.parent) },
                                onLongClick = { viewModel.enterItemMultiSelect(group.parent.id) },
                                onSelectToggle = { viewModel.toggleItemSelection(group.parent.id) },
                            )
                            group.children.forEach { child ->
                                // Same shape as the active list: the indent is painted with the
                                // row colour so it cannot expose the background behind the row.
                                Box(
                                    modifier = Modifier
                                        .background(ListItemDefaults.containerColor)
                                        .padding(start = 24.dp),
                                ) {
                                    ListItemRow(
                                        item = child,
                                        isMultiSelectMode = isItemMultiSelectMode,
                                        isSelected = child.id in selectedItemIds,
                                        interactionsEnabled = !isRemoteReadOnly,
                                        showDragHandle = false,
                                        onToggle = { viewModel.toggleChecked(child) },
                                        onEdit = { editingItem = child },
                                        onToggleFavourite = { viewModel.toggleFavourite(child) },
                                        onLongClick = { viewModel.enterItemMultiSelect(child.id) },
                                        onSelectToggle = { viewModel.toggleItemSelection(child.id) },
                                    )
                                }
                            }
                            HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                        }
                    }

                    item { Spacer(modifier = Modifier.height(88.dp)) } // FAB clearance
                }
            }
        }
    }

    // ── Edit bottom sheet ────────────────────────────────────────────────────────────────────────
    editingItem?.let { item ->
        EditItemSheet(
            item = item,
            canAddSubItem = !isRemoteReadOnly && completeGroups.any { it.parent.id == item.id },
            onAddSubItem = {
                addSubItemParentId = item.itemId
                editingItem = null
                showAddDialog = true
            },
            onSave = { updated ->
                viewModel.updateItem(updated)
                editingItem = null
            },
            onDismiss = { editingItem = null },
        )
    }

    // ── Rename dialog ────────────────────────────────────────────────────────────────────────────
    if (showRenameDialog) {
        NameInputDialog(
            title = "Rename list",
            confirmLabel = "Save",
            initialValue = renameInitialValue,
            onConfirm = { newName ->
                viewModel.renameList(listId, newName)
                showRenameDialog = false
            },
            onDismiss = { showRenameDialog = false },
        )
    }

    // ── Add item dialog ──────────────────────────────────────────────────────────────────────────
    if (showAddDialog) {
        AddItemDialog(
            title = if (addSubItemParentId == null) "Add item" else "Add sub-item",
            placeholder = if (addSubItemParentId == null) "Item name" else "Sub-item name",
            onConfirm = { text ->
                val parentItemId = addSubItemParentId
                showAddDialog = false
                addSubItemParentId = null
                if (parentItemId == null) {
                    viewModel.addItem(listId, text) { createdItemId ->
                        pendingCreatedItemIds.add(createdItemId)
                    }
                } else {
                    viewModel.addSubItem(listId, parentItemId, text) { createdItemId ->
                        pendingCreatedItemIds.add(createdItemId)
                    }
                }
            },
            onDismiss = {
                showAddDialog = false
                addSubItemParentId = null
            },
        )
    }
    // ── Bulk delete dialog ───────────────────────────────────────────────────────────────────────
    if (showItemBulkDeleteDialog) {
        val count = selectedItemIds.size
        AlertDialog(
            onDismissRequest = { showItemBulkDeleteDialog = false },
            title = { Text("Delete $count item${if (count == 1) "" else "s"}?") },
            text = { Text("This will permanently delete the selected item${if (count == 1) "" else "s"}.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSelectedItems()
                        showItemBulkDeleteDialog = false
                    },
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showItemBulkDeleteDialog = false }) { Text("Cancel") }
            },
        )
    }

    // ── Export shared list dialog (#1493) ────────────────────────────────────────────────────────
    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("Export Jandal file?") },
            text = {
                Text(
                    "Jandal writes an encrypted package for this list that another Jandal app can " +
                        "import. The file carries its own key, so anyone you send it to can open and " +
                        "change the shared list.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExportDialog = false
                        coroutineScope.launch {
                            runCatching { viewModel.exportPackageIntent(listId) }
                                .onSuccess {
                                    context.startActivity(Intent.createChooser(it, "Export Jandal file"))
                                }
                                .onFailure {
                                    snackbarHostState.showSnackbar("Could not export this list")
                                }
                        }
                    },
                ) { Text("Export") }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) { Text("Cancel") }
            },
        )
    }

}

/**
 * Explains why local content changes are unavailable for a bound Nextcloud list and offers the
 * explicit resolutions #1548 requires.
 *
 * Unsynced work that provider access stranded can be preserved as a local copy or discarded;
 * a read-only or removed share can be copied out proactively so the user keeps editing locally.
 */
@Composable
internal fun NextcloudAccessBanner(
    unsyncedChanges: Boolean,
    available: Boolean,
    onKeepLocalCopy: () -> Unit,
    onDiscardLocalChanges: () -> Unit,
    onSaveLocalCopy: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("nextcloud_list_access_banner"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = when {
                unsyncedChanges && !available ->
                    "Nextcloud access to this list was removed. Your local changes are not synced."
                unsyncedChanges ->
                    "This list became read-only in Nextcloud. Your local changes are not synced."
                !available -> "Nextcloud access to this list was removed."
                else -> "This Nextcloud list is read-only. Changes are unavailable."
            },
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (unsyncedChanges) {
                Button(
                    onClick = onKeepLocalCopy,
                    modifier = Modifier.testTag("nextcloud_list_keep_local_copy"),
                ) { Text("Keep as local copy") }
                OutlinedButton(
                    onClick = onDiscardLocalChanges,
                    modifier = Modifier.testTag("nextcloud_list_discard_local_changes"),
                ) { Text("Discard local changes") }
            } else {
                Button(
                    onClick = onSaveLocalCopy,
                    modifier = Modifier.testTag("nextcloud_list_save_local_copy"),
                ) { Text("Save as local copy") }
            }
        }
    }
}

/**
 * The reveal behind a hierarchy depth gesture. It names the depth change and uses the neutral
 * secondary hierarchy treatment, so it cannot be mistaken for the archive/dismiss gesture.
 */
@Composable
internal fun HierarchySwipeReveal(indenting: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 20.dp)
            .testTag(if (indenting) "hierarchy_make_sub_item_reveal" else "hierarchy_move_to_top_level_reveal"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (indenting) Arrangement.Start else Arrangement.End,
    ) {
        Icon(
            imageVector = if (indenting) Icons.AutoMirrored.Filled.FormatIndentIncrease
            else Icons.AutoMirrored.Filled.FormatIndentDecrease,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (indenting) "Make sub-item" else "Move to top level",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/** Horizontal distance a move-handle gesture must cover before it commits to a depth change. */
private val HANDLE_DEPTH_COMMIT_DISTANCE = 48.dp

/**
 * Owns every horizontal depth gesture for one row, whichever surface started it.
 *
 * The row body uses the Material [SwipeToDismissBox]; the leading move handle drives the same reveal
 * through [rememberDepthHandleGesture], so a gesture only ever has one owner and the feedback words
 * are identical.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SwipeToChangeDepthRow(
    handleGesturesEnabled: Boolean,
    canMakeSubItem: Boolean,
    canMoveToTopLevel: Boolean,
    onHandlePointerChanged: (Boolean) -> Unit,
    onMakeSubItem: () -> Unit,
    onMoveToTopLevel: () -> Unit,
    content: @Composable (handleGestureModifier: Modifier) -> Unit,
) {
    // The dismiss state is remembered for the row's lifetime, so the callbacks it captures would
    // otherwise stay frozen at first composition and a row could never change direction again.
    val makeSubItemAction by rememberUpdatedState(onMakeSubItem)
    val moveToTopLevelAction by rememberUpdatedState(onMoveToTopLevel)
    val makeSubItemEnabled by rememberUpdatedState(canMakeSubItem)
    val moveToTopLevelEnabled by rememberUpdatedState(canMoveToTopLevel)
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> if (makeSubItemEnabled) makeSubItemAction()
                SwipeToDismissBoxValue.EndToStart -> if (moveToTopLevelEnabled) moveToTopLevelAction()
                else -> Unit
            }
            false
        },
        // A hierarchy gesture is a nudge, not a full-width dismissal swipe.
        positionalThreshold = { distance -> distance * DEPTH_GESTURE_COMMIT_FRACTION },
    )

    // Live horizontal offset of a move-handle gesture, so the handle shows the same reveal while
    // the finger is still down instead of acting invisibly.
    var handleDragX by remember { mutableFloatStateOf(0f) }
    // A handle sits near the leading edge, so a fraction-of-row threshold is unreachable leftward.
    // The commit distance is a fixed nudge instead; the axis lock already stops accidental intent.
    val commitDistancePx = with(LocalDensity.current) { HANDLE_DEPTH_COMMIT_DISTANCE.toPx() }

    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = canMakeSubItem,
        enableDismissFromEndToStart = canMoveToTopLevel,
        backgroundContent = {
            HierarchySwipeReveal(
                indenting = handleDragX > 0f ||
                    state.dismissDirection == SwipeToDismissBoxValue.StartToEnd,
            )
        },
        content = {
            Box(
                modifier = Modifier.offset { IntOffset(handleDragX.roundToInt(), 0) },
            ) {
                val handleGesture = rememberDepthHandleGesture(
                    enabled = handleGesturesEnabled,
                    canMakeSubItem = canMakeSubItem,
                    canMoveToTopLevel = canMoveToTopLevel,
                    onHandlePointerChanged = onHandlePointerChanged,
                    onDelta = { delta -> handleDragX += delta },
                    onCommit = { permitted ->
                        handleDragX = 0f
                        when {
                            permitted > 0f && isDepthCommitReached(permitted, commitDistancePx) &&
                                makeSubItemEnabled -> makeSubItemAction()
                            permitted < 0f && isDepthCommitReached(permitted, commitDistancePx) &&
                                moveToTopLevelEnabled -> moveToTopLevelAction()
                        }
                    },
                )
                content(handleGesture)
            }
        },
    )
}

/**
 * Suspends until [state] publishes a new layout, so a reorder callback cannot return while the
 * reorderable library is still compensating against the pre-move layout.
 */
private suspend fun awaitLayoutChange(state: LazyListState) {
    val before = state.layoutInfo
    withTimeoutOrNull(250) {
        snapshotFlow { state.layoutInfo }.first { it !== before }
    }
}

/**
 * Classifies a move-handle gesture by its dominant axis and takes ownership of it when the axis is
 * horizontal.
 *
 * Touch slop decides the intent, and it is locked for the rest of the gesture. A vertical intent
 * consumes nothing, so the reorderable drag handle underneath still receives the same events and
 * performs the vertical reorder.
 *
 * The modifier itself never changes once composed. Enabling and disabling, and the callbacks, are
 * read through [rememberUpdatedState] so a drag that is already in flight cannot have its handle
 * removed from the modifier chain — that would cancel the reorder mid-gesture.
 */
@Composable
private fun rememberDepthHandleGesture(
    enabled: Boolean,
    canMakeSubItem: Boolean,
    canMoveToTopLevel: Boolean,
    onDelta: (Float) -> Unit,
    onCommit: (Float) -> Unit,
    onHandlePointerChanged: (Boolean) -> Unit,
): Modifier {
    val currentEnabled by rememberUpdatedState(enabled)
    val currentCanMakeSubItem by rememberUpdatedState(canMakeSubItem)
    val currentCanMoveToTopLevel by rememberUpdatedState(canMoveToTopLevel)
    val currentOnDelta by rememberUpdatedState(onDelta)
    val currentOnCommit by rememberUpdatedState(onCommit)
    val currentOnHandlePointerChanged by rememberUpdatedState(onHandlePointerChanged)
    return remember {
        Modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                currentOnHandlePointerChanged(true)
                try {
                    var totalX = 0f
                    var totalY = 0f
                    // Cumulative displacement this row is permitted to show, so a reversal retracts.
                    var depthX = 0f
                    var axis = MoveAxis.Undecided
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) break
                        val delta = change.position - change.previousPosition
                        totalX += delta.x
                        totalY += delta.y
                        if (axis == MoveAxis.Undecided) {
                            if (!currentEnabled) break
                            axis = moveAxisFor(totalX, totalY, viewConfiguration.touchSlop)
                        }
                        when (axis) {
                            MoveAxis.Horizontal -> {
                                val permitted = permittedDepthDisplacement(
                                    totalX,
                                    currentCanMakeSubItem,
                                    currentCanMoveToTopLevel,
                                )
                                if (permitted != depthX) {
                                    currentOnDelta(permitted - depthX)
                                    depthX = permitted
                                }
                                change.consume()
                            }
                            MoveAxis.Vertical -> {
                                break
                            }
                            MoveAxis.Undecided -> Unit
                        }
                    }
                    if (axis == MoveAxis.Horizontal && depthX != 0f) currentOnCommit(depthX)
                } finally {
                    currentOnHandlePointerChanged(false)
                }
            }
        }
    }
}

// ── Item row ─────────────────────────────────────────────────────────────────────────────────────


@Composable
internal fun HierarchyDisclosureButton(
    expanded: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (expanded) "Collapse sub-items" else "Expand sub-items",
        )
    }
}

@Composable
internal fun HierarchyDropIndicator(
    target: HierarchyDropTarget,
    isChild: Boolean,
    isActive: Boolean,
) {
    val label = when (target) {
        is HierarchyDropTarget.TopLevelInsertion -> "Top-level insertion"
        is HierarchyDropTarget.ChildInsertion -> "Insert as sub-item"
        is HierarchyDropTarget.ParentRow -> "Append as last sub-item"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (isActive) 32.dp else 6.dp)
            .padding(start = if (isChild) 72.dp else 16.dp, end = 16.dp)
            .testTag(target.stableKey()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (isActive) {
            HorizontalDivider(
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            HorizontalDivider(
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.primary,
            )
    }
}
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ListItemRow(
    item: ListItemEntity,
    dragHandleModifier: Modifier = Modifier,
    isMultiSelectMode: Boolean = false,
    isSelected: Boolean = false,
    interactionsEnabled: Boolean = true,
    showDragHandle: Boolean = false,
    isDragHandlePressed: Boolean = false,
    /** Colour the row paints itself with; callers pass the animated drag/highlight colour. */
    containerColor: Color = ListItemDefaults.containerColor,
    showDisclosure: Boolean = false,
    isExpanded: Boolean = true,
    onToggleExpanded: () -> Unit = {},
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onToggleFavourite: () -> Unit,
    onLongClick: () -> Unit = {},
    onSelectToggle: () -> Unit = {},
) {
    val descriptionUrls = remember(item.description) { findListItemUrls(item.description) }

    // An explicit Row rather than M3 ListItem: the ListItem slots add fixed 16.dp start, leading
    // and trailing padding on top of the mandatory 48.dp handle, checkbox and star targets, and
    // that reservation was squeezing the item text. Spacing here is one 8.dp step, all three
    // targets are untouched, and the text column takes every remaining pixel.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The row paints itself, edge to edge, exactly as M3 ListItem did before this row was
            // laid out explicitly. Without it the always-composed SwipeToDismissBox background
            // (the "Make sub-item" / "Move to top level" reveal) shows through every idle row, and
            // a child indent applied outside the row would expose a strip of it too.
            .background(containerColor)
            .combinedClickable(
                enabled = interactionsEnabled,
                onClick = {
                    if (isMultiSelectMode) onSelectToggle() else onEdit()
                },
                // The handle's reorder recognizer owns its press; multi-select long-press remains on row content.
                onLongClick = if (isDragHandlePressed) {
                    null
                } else {
                    {
                        if (!isMultiSelectMode) onLongClick()
                    }
                },
            )
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showDragHandle) {
            // The handle owns its whole gesture surface, and it carries vertical reorder plus both
            // horizontal depth gestures, so it keeps a 48.dp target around the 24.dp icon.
            Box(
                modifier = dragHandleModifier.size(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    // A four-way icon, because the handle owns vertical reorder and the
                    // horizontal make-sub-item / move-to-top-level gestures.
                    Icons.Default.OpenWith,
                    contentDescription = "Move item",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // The checkbox keeps its full 48.dp target: the tick stays a comfortable tap away from
        // the handle and from the text.
        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            if (isMultiSelectMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { if (interactionsEnabled) onSelectToggle() },
                    enabled = interactionsEnabled,
                )
            } else {
                Checkbox(
                    checked = item.checked,
                    onCheckedChange = { if (interactionsEnabled) onToggle() },
                    enabled = interactionsEnabled,
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        ) {
            ListItemText(
                text = item.text,
                style = if (item.checked) {
                    MaterialTheme.typography.bodyLarge.copy(
                        textDecoration = TextDecoration.LineThrough,
                    )
                } else {
                    MaterialTheme.typography.bodyLarge
                },
                color = if (item.checked) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
                activateLinks = interactionsEnabled && !isMultiSelectMode,
                onClick = {
                    if (interactionsEnabled) {
                        if (isMultiSelectMode) onSelectToggle() else onEdit()
                    }
                },
                onLongClick = {
                    if (interactionsEnabled && !isMultiSelectMode) onLongClick()
                },
            )
            if (item.description.isNotEmpty()) {
                ListItemText(
                    text = item.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    activateLinks = interactionsEnabled && !isMultiSelectMode,
                    maxLines = 3,
                    onClick = {
                        if (interactionsEnabled) {
                            if (isMultiSelectMode) onSelectToggle() else onEdit()
                        }
                    },
                    onLongClick = {
                        if (interactionsEnabled && !isMultiSelectMode) onLongClick()
                    },
                )
                DescriptionUrlActions(
                    urls = descriptionUrls,
                    activateLinks = interactionsEnabled && !isMultiSelectMode,
                    compactMultipleLinksLabel = true,
                    onInactiveClick = { if (interactionsEnabled) onSelectToggle() },
                )
            }
            val dueAtMs = item.dueAt
            if (dueAtMs != null) {
                val overdue = isOverdue(dueAtMs, item.checked)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Event,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 2.dp),
                        tint = if (overdue) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = formatDueDate(dueAtMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (overdue) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    if (item.notificationTime != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            Icons.Default.Notifications,
                            contentDescription = "Notification set",
                            modifier = Modifier.padding(end = 2.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            Text(
                text = formatTimestamp(item),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (showDisclosure) {
            HierarchyDisclosureButton(
                expanded = isExpanded,
                onClick = onToggleExpanded,
            )
        }
        // The star is the entire trailing column, so the text keeps every remaining pixel and the
        // star never moves with the text length. Deletion stays in the long-press multi-select
        // toolbar. The 8.dp gap keeps the text about 20.dp clear of the star glyph while the star
        // itself keeps its full 48.dp target.
        if (isMultiSelectMode) {
            if (item.isFavourite) {
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = {}, enabled = interactionsEnabled) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = "Favourited",
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        } else {
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = onToggleFavourite, enabled = interactionsEnabled) {
                Icon(
                    imageVector = if (item.isFavourite) Icons.Default.Star
                    else Icons.Default.StarBorder,
                    contentDescription = if (item.isFavourite) "Unfavourite" else "Favourite",
                    tint = if (item.isFavourite) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── Edit bottom sheet ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditItemSheet(
    item: ListItemEntity,
    canAddSubItem: Boolean,
    onAddSubItem: () -> Unit,
    onSave: (ListItemEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by remember(item.id) { mutableStateOf(item.text) }
    var description by remember(item.id) { mutableStateOf(item.description) }
    var dueAt by remember(item.id) { mutableStateOf(item.dueAt) }
    var isFavourite by remember(item.id) { mutableStateOf(item.isFavourite) }
    var notificationTime by remember(item.id) { mutableStateOf(item.notificationTime) }
    var notifyEnabled by remember(item.id) { mutableStateOf(item.notificationTime != null) }
    val descriptionUrls = remember(description) { findListItemUrls(description) }

    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 24.dp),
        ) {
            Text("Edit item", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(16.dp))

            // Text field
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Item") },
                minLines = 3,
                maxLines = 6,
                singleLine = false,
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Description") },
                minLines = 3,
                maxLines = 8,
                singleLine = false,
            )
            DescriptionUrlActions(
                urls = descriptionUrls,
                activateLinks = true,
            )

            if (canAddSubItem) {
                OutlinedButton(
                    onClick = onAddSubItem,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Add sub-item")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Due date row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Event,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(8.dp))
                if (dueAt != null) {
                    Text(
                        text = formatDueDate(dueAt!!),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { showDatePicker = true },
                    )
                    IconButton(onClick = {
                        dueAt = null
                        notificationTime = null
                        notifyEnabled = false
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear due date")
                    }
                } else {
                    TextButton(onClick = { showDatePicker = true }) {
                        Text("Set due date")
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Favourite switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (isFavourite) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = null,
                    tint = if (isFavourite) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Mark as favourite",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(
                    checked = isFavourite,
                    onCheckedChange = { isFavourite = it },
                )
            }

            // Notify me row (only when a due date is set)
            if (dueAt != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (notifyEnabled) Modifier.clickable { showTimePicker = true }
                            else Modifier
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (notifyEnabled) Icons.Default.Notifications
                        else Icons.Default.NotificationsNone,
                        contentDescription = null,
                        tint = if (notifyEnabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Notify me",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (notifyEnabled && notificationTime != null) {
                            val notifTime = java.time.Instant.ofEpochMilli(notificationTime!!)
                                .atZone(java.time.ZoneId.systemDefault())
                                .toLocalTime()
                            Text(
                                text = notifTime.format(
                                    java.time.format.DateTimeFormatter.ofPattern("HH:mm"),
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Switch(
                        checked = notifyEnabled,
                        onCheckedChange = { enabled ->
                            notifyEnabled = enabled
                            if (enabled) {
                                // Default: due-date day at 09:00 local
                                val base = dueAt ?: System.currentTimeMillis()
                                val localDate = Instant.ofEpochMilli(base)
                                    .atZone(ZoneId.systemDefault())
                                    .toLocalDate()
                                notificationTime = localDate
                                    .atTime(9, 0)
                                    .atZone(ZoneId.systemDefault())
                                    .toInstant()
                                    .toEpochMilli()
                                showTimePicker = true
                            } else {
                                notificationTime = null
                            }
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Save / Cancel
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        onSave(
                            item.copy(
                                text = text,
                                description = description,
                                dueAt = dueAt,
                                isFavourite = isFavourite,
                                notificationTime = if (notifyEnabled) notificationTime else null,
                            ),
                        )
                    },
                    enabled = text.isNotBlank(),
                ) { Text("Save") }
            }

            // Navigation bar clearance
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // Date picker rendered as a separate dialog that floats over the bottom sheet
    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = dueAt?.let { localMs ->
                Instant.ofEpochMilli(localMs)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant()
                    .toEpochMilli()
            } ?: System.currentTimeMillis(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        dueAt = datePickerState.selectedDateMillis?.let { utcMs ->
                            Instant.ofEpochMilli(utcMs)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                                .atStartOfDay(ZoneId.systemDefault())
                                .toInstant()
                                .toEpochMilli()
                        }
                        showDatePicker = false
                    },
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    // Time picker for notification time
    if (showTimePicker) {
        val initialTime = notificationTime?.let { epochMs ->
            Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalTime()
        } ?: LocalTime.of(9, 0)
        val timePickerState = rememberTimePickerState(
            initialHour = initialTime.hour,
            initialMinute = initialTime.minute,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Set notification time") },
            text = {
                TimePicker(state = timePickerState)
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val base = dueAt ?: System.currentTimeMillis()
                        val localDate = Instant.ofEpochMilli(base)
                            .atZone(ZoneId.systemDefault())
                            .toLocalDate()
                        notificationTime = localDate
                            .atTime(timePickerState.hour, timePickerState.minute)
                            .atZone(ZoneId.systemDefault())
                            .toInstant()
                            .toEpochMilli()
                        showTimePicker = false
                    },
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = {
                    // If toggling on for the first time and dismissed, revert the switch
                    if (notificationTime == null) notifyEnabled = false
                    showTimePicker = false
                }) { Text("Cancel") }
            },
        )
    }
}

// ── Add item dialog ───────────────────────────────────────────────────────────────────────────────

@Composable
private fun AddItemDialog(
    title: String,
    placeholder: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                placeholder = { Text(placeholder) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank(),
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
