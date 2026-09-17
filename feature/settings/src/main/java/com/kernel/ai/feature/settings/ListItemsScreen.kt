package com.kernel.ai.feature.settings

import android.content.Intent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
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
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.drop
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kernel.ai.core.memory.entity.ListItemEntity
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
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

// ── Screen ───────────────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ListItemsScreen(
    listId: Long,
    onBack: () -> Unit = {},
    onNavigateToVoiceActions: () -> Unit = {},
    viewModel: ListsViewModel = hiltViewModel(),
) {
    val displayedGroups by viewModel.observeDisplayedHierarchy(listId).collectAsStateWithLifecycle()
    val listEntities by viewModel.listEntities.collectAsStateWithLifecycle()
    val searchQuery by viewModel.itemSearchQuery.collectAsStateWithLifecycle()

    // Restores this list's saved sort so reopening never falls back to the default.
    LaunchedEffect(listId) { viewModel.bindItemList(listId) }

    val displayName = listEntities.firstOrNull { it.id == listId }?.name ?: ""
    val (activeGroups, completedGroups) = displayedGroups
    val sortedActive = activeGroups.flatMap { listOf(it.parent) + it.children }
    val sortedCompleted = completedGroups.flatMap { listOf(it.parent) + it.children }
    val allItems = sortedActive + sortedCompleted

    var showAddDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    val renameInitialValue = remember(showRenameDialog) { if (showRenameDialog) displayName else "" }
    var completedExpanded by rememberSaveable { mutableStateOf(viewModel.itemFilter == ItemFilter.COMPLETED_ONLY) }
    var showSortMenu by remember { mutableStateOf(false) }
    var editingItem by remember { mutableStateOf<ListItemEntity?>(null) }

    val selectedItemIds = viewModel.selectedItemIds
    val isItemMultiSelectMode = viewModel.isItemMultiSelectMode
    var showItemBulkDeleteDialog by remember { mutableStateOf(false) }
    val hierarchyDragEnabled = isHierarchyDragEnabled(
        itemSort = viewModel.itemSort,
        itemFilter = viewModel.itemFilter,
        searchQuery = searchQuery,
        isMultiSelectMode = isItemMultiSelectMode,
    )

    var showSelectAllMenu by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    // ── Hierarchy editing state (#928) ───────────────────────────────────────────────────────────
    var localActiveItems by remember { mutableStateOf(sortedActive) }
    var itemDragInProgress by remember { mutableStateOf(false) }
    var dragSourceId by remember { mutableStateOf<Long?>(null) }
    var dragDestinationGroupId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(activeGroups) {
        if (!itemDragInProgress) localActiveItems = activeGroups.flatMap { listOf(it.parent) + it.children }
    }

    val lazyListState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        if (!hierarchyDragEnabled) return@rememberReorderableLazyListState
        val fromKey = from.key as? Long ?: return@rememberReorderableLazyListState
        val toKey = to.key as? Long ?: return@rememberReorderableLazyListState
        if (localActiveItems.none { it.id == fromKey } || localActiveItems.none { it.id == toKey }) {
            return@rememberReorderableLazyListState
        }
        val moved = moveHierarchyRows(
            current = localActiveItems,
            groups = activeGroups,
            draggedId = fromKey,
            targetId = toKey,
        )
        localActiveItems = moved
        dragDestinationGroupId = crossGroupDestinationRowId(activeGroups, moved, fromKey)
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
                        // Overflow: Select All
                        Box {
                            IconButton(onClick = { showSelectAllMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More options")
                            }
                            DropdownMenu(
                                expanded = showSelectAllMenu,
                                onDismissRequest = { showSelectAllMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Select all") },
                                    onClick = {
                                        showSelectAllMenu = false
                                        viewModel.selectAllItems(
                                            visibleSelectableItemIds(
                                                activeRows = sortedActive,
                                                completedRows = sortedCompleted,
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
                            modifier = Modifier.clickable { showRenameDialog = true },
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        if (sortedCompleted.isNotEmpty()) {
                            TextButton(onClick = { viewModel.clearChecked(listId) }) {
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
                                    onClick = {
                                        viewModel.enterManualHierarchyEditing()
                                        showSortMenu = false
                                    },
                                    trailingIcon = if (viewModel.itemSort == ItemSort.MANUAL) {
                                        { Icon(Icons.Default.Check, contentDescription = null) }
                                    } else null,
                                )
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
                                    text = { Text("Share") },
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
                    onClick = onNavigateToVoiceActions,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Icon(Icons.Default.Mic, contentDescription = "Voice input")
                }
                FloatingActionButton(onClick = { showAddDialog = true }) {
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

            if (allItems.isEmpty()) {
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
                    items(localActiveItems, key = { it.id }) { item ->
                        ReorderableItem(reorderState, key = item.id) { isDragging ->
                            val elevation by animateDpAsState(
                                if (isDragging) 6.dp else 0.dp,
                                label = "item_drag_elevation",
                            )
                            val owningId = owningRowId(activeGroups, item.id)
                            val isChild = owningId != null && owningId != item.id
                            val rowIndex = localActiveItems.indexOfFirst { it.id == item.id }
                            val precedingRow = localActiveItems.getOrNull(rowIndex - 1)
                            val depthGesturesEnabled = hierarchyDragEnabled && !itemDragInProgress
                            Surface(
                                color = when {
                                    item.id == dragDestinationGroupId ->
                                        MaterialTheme.colorScheme.secondaryContainer
                                    isDragging -> MaterialTheme.colorScheme.surfaceVariant
                                    else -> MaterialTheme.colorScheme.surface
                                },
                                shadowElevation = elevation,
                            ) {
                                SwipeToChangeDepthRow(
                                    canIndent = depthGesturesEnabled &&
                                        canIndentRow(localActiveItems, activeGroups, item.id),
                                    canOutdent = depthGesturesEnabled &&
                                        canOutdentRow(activeGroups, item.id),
                                    onIndent = { precedingRow?.let { viewModel.indentItem(item, it) } },
                                    onOutdent = { viewModel.outdentItem(item) },
                                ) {
                                    ListItemRow(
                                        item = item,
                                        isChild = isChild,
                                        isMultiSelectMode = isItemMultiSelectMode,
                                        isSelected = item.id in selectedItemIds,
                                        showDragHandle = hierarchyDragEnabled,
                                        dragHandleModifier = if (hierarchyDragEnabled) {
                                            Modifier.draggableHandle(
                                                onDragStarted = {
                                                    itemDragInProgress = true
                                                    dragSourceId = item.id
                                                    dragDestinationGroupId = null
                                                },
                                                onDragStopped = {
                                                    itemDragInProgress = false
                                                    dragSourceId?.let { source ->
                                                        viewModel.moveItemFromDrag(
                                                            orderedRowIds = localActiveItems.map { it.id },
                                                            draggedId = source,
                                                        )
                                                    }
                                                    dragSourceId = null
                                                    dragDestinationGroupId = null
                                                },
                                            )
                                        } else Modifier,
                                        onToggle = { viewModel.toggleChecked(item) },
                                        onDelete = { viewModel.deleteItem(item) },
                                        onEdit = { editingItem = item },
                                        onToggleFavourite = { viewModel.toggleFavourite(item) },
                                        onLongClick = { viewModel.enterItemMultiSelect(item.id) },
                                        onSelectToggle = { viewModel.toggleItemSelection(item.id) },
                                    )
                                }
                            }
                            HorizontalDivider(modifier = Modifier.padding(start = if (isChild) 80.dp else 56.dp))
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
                        items(completedGroups, key = { "done_group_${it.parent.id}" }) { group ->
                            ListItemRow(
                                item = group.parent,
                                isMultiSelectMode = isItemMultiSelectMode,
                                isSelected = group.parent.id in selectedItemIds,
                                showDragHandle = false,
                                onToggle = { viewModel.toggleChecked(group.parent) },
                                onDelete = { viewModel.deleteItem(group.parent) },
                                onEdit = { editingItem = group.parent },
                                onToggleFavourite = { viewModel.toggleFavourite(group.parent) },
                                onLongClick = { viewModel.enterItemMultiSelect(group.parent.id) },
                                onSelectToggle = { viewModel.toggleItemSelection(group.parent.id) },
                            )
                            group.children.forEach { child ->
                                ListItemRow(
                                    item = child,
                                    isChild = true,
                                    isMultiSelectMode = isItemMultiSelectMode,
                                    isSelected = child.id in selectedItemIds,
                                    showDragHandle = false,
                                    onToggle = { viewModel.toggleChecked(child) },
                                    onDelete = { viewModel.deleteItem(child) },
                                    onEdit = { editingItem = child },
                                    onToggleFavourite = { viewModel.toggleFavourite(child) },
                                    onLongClick = { viewModel.enterItemMultiSelect(child.id) },
                                    onSelectToggle = { viewModel.toggleItemSelection(child.id) },
                                )
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
            onConfirm = { text ->
                showAddDialog = false
                viewModel.addItem(listId, text)
            },
            onDismiss = { showAddDialog = false },
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

}
/**
 * Horizontal hierarchy gesture: swipe right indents, swipe left outdents.
 *
 * A direction is inert when the row is not eligible, so the row simply settles back and nothing
 * is mutated. The hint icon is only revealed while the row is being dragged that way.
 */
@Composable
private fun SwipeToChangeDepthRow(
    canIndent: Boolean,
    canOutdent: Boolean,
    onIndent: () -> Unit,
    onOutdent: () -> Unit,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val thresholdPx = with(LocalDensity.current) { DEPTH_SWIPE_THRESHOLD.toPx() }
    val maxOffsetPx = thresholdPx * 2f
    val offsetX = remember { Animatable(0f) }
    val swipe = offsetX.value
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .matchParentSize()
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (swipe > 0f) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            if (swipe < 0f) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = swipe }
                .then(
                    if (!canIndent && !canOutdent) {
                        Modifier
                    } else {
                        Modifier.draggable(
                            orientation = Orientation.Horizontal,
                            state = rememberDraggableState { delta ->
                                scope.launch {
                                    offsetX.snapTo((offsetX.value + delta).coerceIn(-maxOffsetPx, maxOffsetPx))
                                }
                            },
                            onDragStopped = {
                                val settled = offsetX.value
                                when {
                                    settled >= thresholdPx && canIndent -> onIndent()
                                    settled <= -thresholdPx && canOutdent -> onOutdent()
                                }
                                offsetX.animateTo(0f)
                            },
                        )
                    },
                ),
        ) {
            content()
        }
    }
}

/** Horizontal travel, in dp, before a row commits to indenting or outdenting. */
private val DEPTH_SWIPE_THRESHOLD = 56.dp

// ── Item row ─────────────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListItemRow(
    item: ListItemEntity,
    dragHandleModifier: Modifier = Modifier,
    isChild: Boolean = false,
    isMultiSelectMode: Boolean = false,
    isSelected: Boolean = false,
    showDragHandle: Boolean = false,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onToggleFavourite: () -> Unit,
    onLongClick: () -> Unit = {},
    onSelectToggle: () -> Unit = {},
) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isChild) Modifier.padding(start = 40.dp) else Modifier)
            .combinedClickable(
                onClick = {
                    if (isMultiSelectMode) onSelectToggle() else onEdit()
                },
                onLongClick = {
                    if (!isMultiSelectMode) onLongClick()
                },
            ),
        headlineContent = {
            Text(
                text = item.text,
                style = if (item.checked) {
                    MaterialTheme.typography.bodyLarge.copy(
                        textDecoration = TextDecoration.LineThrough,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    MaterialTheme.typography.bodyLarge
                },
            )
        },
        supportingContent = {
            Column {
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
        },
        leadingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showDragHandle) {
                    Icon(
                        Icons.Default.DragHandle,
                        contentDescription = "Drag to reorder",
                        // The handle owns its whole gesture surface: absorbing the long press
                        // stops the row's multi-select click from winning on the handle.
                        modifier = dragHandleModifier
                            .pointerInput(Unit) {
                                detectTapGestures(onLongPress = { /* absorb */ })
                            }
                            .padding(8.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isMultiSelectMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onSelectToggle() },
                    )
                } else {
                    Checkbox(
                        checked = item.checked,
                        onCheckedChange = { onToggle() },
                    )
                }
            }
        },
        trailingContent = if (isMultiSelectMode) {
            if (item.isFavourite) {
                {
                    IconButton(onClick = {}) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = "Favourited",
                            tint = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            } else null
        } else {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onToggleFavourite) {
                        Icon(
                            imageVector = if (item.isFavourite) Icons.Default.Star
                            else Icons.Default.StarBorder,
                            contentDescription = if (item.isFavourite) "Unfavourite" else "Favourite",
                            tint = if (item.isFavourite) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete item",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
    )
}

// ── Edit bottom sheet ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditItemSheet(
    item: ListItemEntity,
    onSave: (ListItemEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by remember(item.id) { mutableStateOf(item.text) }
    var dueAt by remember(item.id) { mutableStateOf(item.dueAt) }
    var isFavourite by remember(item.id) { mutableStateOf(item.isFavourite) }
    var notificationTime by remember(item.id) { mutableStateOf(item.notificationTime) }
    var notifyEnabled by remember(item.id) { mutableStateOf(item.notificationTime != null) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
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
                singleLine = true,
            )

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
                                text = text.trim(),
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
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add item") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                placeholder = { Text("Item name") },
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
