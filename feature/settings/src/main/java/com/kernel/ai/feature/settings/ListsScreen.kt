package com.kernel.ai.feature.settings

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kernel.ai.core.memory.entity.ListNameEntity
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ListsScreen(
    onBack: () -> Unit = {},
    onOpenList: (Long) -> Unit = {},
    onNavigateToVoiceActions: () -> Unit = {},
    viewModel: ListsViewModel = hiltViewModel(),
) {
    val displayedLists by viewModel.displayedLists.collectAsStateWithLifecycle()
    val listEntities by viewModel.listEntities.collectAsStateWithLifecycle()
    val itemCounts by viewModel.itemCounts.collectAsStateWithLifecycle()
    val searchQuery by viewModel.listSearchQuery.collectAsStateWithLifecycle()
    val selectedListIds = viewModel.selectedListIds
    val isMultiSelect = viewModel.isListMultiSelectMode
    val scope = rememberCoroutineScope()

    var showCreateDialog by remember { mutableStateOf(false) }
    var pendingDeleteEntity by remember { mutableStateOf<ListNameEntity?>(null) }
    var pendingRenameEntity by remember { mutableStateOf<ListNameEntity?>(null) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showBulkDeleteDialog by remember { mutableStateOf(false) }

    // Apply search on top of the already sort/filter-applied displayedLists
    val filtered = if (searchQuery.isBlank()) displayedLists
    else displayedLists.filter { it.name.contains(searchQuery, ignoreCase = true) }

    val pinnedItems = filtered.filter { it.pinned }
    val unpinnedItems = filtered.filter { !it.pinned }

    // ── Drag-and-drop local state ────────────────────────────────────────────────────────────────
    // Maintain local copies for optimistic UI updates during drag; sync from ViewModel when idle.
    var localPinned by remember { mutableStateOf(pinnedItems) }
    var localUnpinned by remember { mutableStateOf(unpinnedItems) }
    var dragInProgress by remember { mutableStateOf(false) }

    LaunchedEffect(pinnedItems) { if (!dragInProgress) localPinned = pinnedItems }
    LaunchedEffect(unpinnedItems) { if (!dragInProgress) localUnpinned = unpinnedItems }

    val lazyListState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        // Only handle moves between real list entities (keys are Long IDs); skip headers
        val fromKey = from.key as? Long ?: return@rememberReorderableLazyListState
        val toKey = to.key as? Long ?: return@rememberReorderableLazyListState
        // Enforce pinned/unpinned group boundary
        val fromInPinned = localPinned.any { it.id == fromKey }
        val toInPinned = localPinned.any { it.id == toKey }
        if (fromInPinned != toInPinned) return@rememberReorderableLazyListState
        // Optimistic reorder within the correct group
        if (fromInPinned) {
            val fi = localPinned.indexOfFirst { it.id == fromKey }
            val ti = localPinned.indexOfFirst { it.id == toKey }
            localPinned = localPinned.toMutableList().apply { add(ti, removeAt(fi)) }
        } else {
            val fi = localUnpinned.indexOfFirst { it.id == fromKey }
            val ti = localUnpinned.indexOfFirst { it.id == toKey }
            localUnpinned = localUnpinned.toMutableList().apply { add(ti, removeAt(fi)) }
        }
    }

    Scaffold(
        topBar = {
            if (isMultiSelect) {
                // ── Contextual multi-select top bar ──────────────────────────────────────────────
                TopAppBar(
                    title = { Text("${selectedListIds.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.exitListMultiSelect() }) {
                            Icon(Icons.Default.Close, contentDescription = "Exit selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showBulkDeleteDialog = true }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete selected",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text("Lists") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Sort and filter")
                            }
                            SortFilterMenu(
                                expanded = showSortMenu,
                                currentSort = viewModel.listSort,
                                currentFilter = viewModel.listFilter,
                                onSortSelected = { viewModel.listSort = it },
                                onFilterSelected = { viewModel.listFilter = it },
                                onDismiss = { showSortMenu = false },
                            )
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!isMultiSelect) {
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
                    FloatingActionButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Create list")
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // Search bar (hidden in multi-select mode to keep UI clean)
            if (!isMultiSelect) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = viewModel::setListSearchQuery,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text("Search lists") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setListSearchQuery("") }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear")
                            }
                        }
                    },
                    singleLine = true,
                )
            }

            if (filtered.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(modifier = Modifier.height(32.dp))
                    Text(
                        text = when {
                            listEntities.isEmpty() -> "No lists yet."
                            searchQuery.isNotBlank() -> "No lists match \"$searchQuery\"."
                            viewModel.listFilter == ListFilter.PINNED_ONLY -> "No pinned lists."
                            else -> "No lists found."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (listEntities.isEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Tap + to create a list, or ask Jandal — e.g. \"Add milk to my shopping list\".",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = lazyListState,
                ) {
                    // ── Pinned section ──────────────────────────────────────────────────────
                    if (localPinned.isNotEmpty()) {
                        stickyHeader(key = "header_pinned") {
                            ListSectionHeader(label = "Pinned")
                        }
                        items(localPinned, key = { it.id }) { entity ->
                            ReorderableItem(reorderState, key = entity.id) { isDragging ->
                                val elevation by animateDpAsState(
                                    if (isDragging) 6.dp else 0.dp,
                                    label = "drag_elevation_pinned",
                                )
                                Surface(shadowElevation = elevation) {
                                    ListOverviewRow(
                                        entity = entity,
                                        count = itemCounts[entity.id] ?: 0,
                                        isSelected = entity.id in selectedListIds,
                                        isMultiSelectMode = isMultiSelect,
                                        dragHandleModifier = if (!isMultiSelect) {
                                            Modifier.draggableHandle(
                                                onDragStarted = { dragInProgress = true },
                                                onDragStopped = {
                                                    dragInProgress = false
                                                    viewModel.onListsReordered(
                                                        localPinned.map { it.id },
                                                        localUnpinned.map { it.id },
                                                    )
                                                },
                                            )
                                        } else Modifier,
                                        onOpen = {
                                            if (isMultiSelect) viewModel.toggleListSelection(entity.id)
                                            else onOpenList(entity.id)
                                        },
                                        onLongClick = { viewModel.enterListMultiSelect(entity.id) },
                                        onPin = { viewModel.togglePin(entity.id) },
                                        onRename = { pendingRenameEntity = entity },
                                        onDelete = { pendingDeleteEntity = entity },
                                    )
                                }
                            }
                        }
                    }

                    // ── Unpinned section ────────────────────────────────────────────────────
                    if (localUnpinned.isNotEmpty()) {
                        if (localPinned.isNotEmpty()) {
                            stickyHeader(key = "header_other") {
                                ListSectionHeader(label = "Other")
                            }
                        }
                        items(localUnpinned, key = { it.id }) { entity ->
                            ReorderableItem(reorderState, key = entity.id) { isDragging ->
                                val elevation by animateDpAsState(
                                    if (isDragging) 6.dp else 0.dp,
                                    label = "drag_elevation_unpinned",
                                )
                                Surface(shadowElevation = elevation) {
                                    ListOverviewRow(
                                        entity = entity,
                                        count = itemCounts[entity.id] ?: 0,
                                        isSelected = entity.id in selectedListIds,
                                        isMultiSelectMode = isMultiSelect,
                                        dragHandleModifier = if (!isMultiSelect) {
                                            Modifier.draggableHandle(
                                                onDragStarted = { dragInProgress = true },
                                                onDragStopped = {
                                                    dragInProgress = false
                                                    viewModel.onListsReordered(
                                                        localPinned.map { it.id },
                                                        localUnpinned.map { it.id },
                                                    )
                                                },
                                            )
                                        } else Modifier,
                                        onOpen = {
                                            if (isMultiSelect) viewModel.toggleListSelection(entity.id)
                                            else onOpenList(entity.id)
                                        },
                                        onLongClick = { viewModel.enterListMultiSelect(entity.id) },
                                        onPin = { viewModel.togglePin(entity.id) },
                                        onRename = { pendingRenameEntity = entity },
                                        onDelete = { pendingDeleteEntity = entity },
                                    )
                                }
                            }
                        }
                    }

                    item { Spacer(modifier = Modifier.height(88.dp)) } // FAB clearance
                }
            }
        }
    }

    // ── Create list dialog ────────────────────────────────────────────────────────────────────
    if (showCreateDialog) {
        NameInputDialog(
            title = "New list",
            confirmLabel = "Create",
            initialValue = "",
            onConfirm = { name ->
                showCreateDialog = false
                if (name.isNotBlank()) {
                    scope.launch {
                        val newId = viewModel.createList(name)
                        if (newId > 0L) onOpenList(newId)
                    }
                }
            },
            onDismiss = { showCreateDialog = false },
        )
    }

    // ── Rename dialog ─────────────────────────────────────────────────────────────────────────
    pendingRenameEntity?.let { entity ->
        NameInputDialog(
            title = "Rename list",
            confirmLabel = "Save",
            initialValue = entity.name,
            onConfirm = { newName ->
                viewModel.renameList(entity.id, newName)
                pendingRenameEntity = null
            },
            onDismiss = { pendingRenameEntity = null },
        )
    }

    // ── Single-list delete confirmation dialog ────────────────────────────────────────────────
    pendingDeleteEntity?.let { entity ->
        val count = itemCounts[entity.id] ?: 0
        AlertDialog(
            onDismissRequest = { pendingDeleteEntity = null },
            title = { Text("Delete \"${entity.name.replaceFirstChar { it.uppercase() }}\"?") },
            text = {
                if (count > 0) Text("This will permanently delete $count item${if (count == 1) "" else "s"}.")
                else Text("The list is empty and will be deleted.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteList(entity.id)
                        pendingDeleteEntity = null
                    },
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteEntity = null }) { Text("Cancel") }
            },
        )
    }

    // ── Bulk delete confirmation dialog ───────────────────────────────────────────────────────
    if (showBulkDeleteDialog) {
        val count = selectedListIds.size
        AlertDialog(
            onDismissRequest = { showBulkDeleteDialog = false },
            title = { Text("Delete $count list${if (count == 1) "" else "s"}?") },
            text = {
                Text("This will also delete all items in ${if (count == 1) "that list" else "those lists"}.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSelectedLists()
                        showBulkDeleteDialog = false
                    },
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showBulkDeleteDialog = false }) { Text("Cancel") }
            },
        )
    }
}

// ── Internal composables ──────────────────────────────────────────────────────────────────────────

/** Sticky section header — surface background so it occludes scrolled-under rows. */
@Composable
private fun ListSectionHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

/** A single row in the lists overview. Supports multi-select and drag-and-drop. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListOverviewRow(
    entity: ListNameEntity,
    count: Int,
    isSelected: Boolean,
    isMultiSelectMode: Boolean,
    dragHandleModifier: Modifier,
    onOpen: () -> Unit,
    onLongClick: () -> Unit,
    onPin: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var showOverflow by remember { mutableStateOf(false) }

    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onOpen,
                onLongClick = onLongClick,
            ),
        headlineContent = {
            Text(entity.name.replaceFirstChar { it.uppercase() })
        },
        supportingContent = {
            Text("$count item${if (count == 1) "" else "s"}")
        },
        leadingContent = {
            if (isMultiSelectMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onOpen() },
                )
            } else {
                Icon(
                    Icons.Default.Checklist,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
        trailingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                if (!isMultiSelectMode) {
                    // Pin toggle
                    IconButton(onClick = onPin) {
                        Icon(
                            if (entity.pinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                            contentDescription = if (entity.pinned) "Unpin" else "Pin",
                            tint = if (entity.pinned) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Overflow: Rename / Delete
                    Box {
                        IconButton(onClick = { showOverflow = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(
                            expanded = showOverflow,
                            onDismissRequest = { showOverflow = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Rename") },
                                onClick = { showOverflow = false; onRename() },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                onClick = { showOverflow = false; onDelete() },
                            )
                        }
                    }
                }
                // Drag handle — always rendered but hidden in multi-select mode
                if (!isMultiSelectMode) {
                    Icon(
                        Icons.Default.DragHandle,
                        contentDescription = "Drag to reorder",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = dragHandleModifier.padding(horizontal = 4.dp),
                    )
                }
            }
        },
    )
    HorizontalDivider()
}

/** Sort & filter drop-down menu anchored on the top-bar ⋮ button. */
@Composable
private fun SortFilterMenu(
    expanded: Boolean,
    currentSort: ListSort,
    currentFilter: ListFilter,
    onSortSelected: (ListSort) -> Unit,
    onFilterSelected: (ListFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        // ── Sort section ────────────────────────────────────────────────────
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
        SortItem(
            label = "Manual (drag to reorder)",
            selected = currentSort == ListSort.MANUAL,
            onClick = { onSortSelected(ListSort.MANUAL); onDismiss() },
        )
        SortItem(
            label = "Last modified",
            selected = currentSort == ListSort.LAST_MODIFIED,
            onClick = { onSortSelected(ListSort.LAST_MODIFIED); onDismiss() },
        )
        SortItem(
            label = "Name A → Z",
            selected = currentSort == ListSort.NAME_ASC,
            onClick = { onSortSelected(ListSort.NAME_ASC); onDismiss() },
        )
        SortItem(
            label = "Name Z → A",
            selected = currentSort == ListSort.NAME_DESC,
            onClick = { onSortSelected(ListSort.NAME_DESC); onDismiss() },
        )
        SortItem(
            label = "Created (newest)",
            selected = currentSort == ListSort.CREATED_DESC,
            onClick = { onSortSelected(ListSort.CREATED_DESC); onDismiss() },
        )
        SortItem(
            label = "Created (oldest)",
            selected = currentSort == ListSort.CREATED_ASC,
            onClick = { onSortSelected(ListSort.CREATED_ASC); onDismiss() },
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // ── Filter section ──────────────────────────────────────────────────
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
        SortItem(
            label = "All",
            selected = currentFilter == ListFilter.ALL,
            onClick = { onFilterSelected(ListFilter.ALL); onDismiss() },
        )
        SortItem(
            label = "Pinned only",
            selected = currentFilter == ListFilter.PINNED_ONLY,
            onClick = { onFilterSelected(ListFilter.PINNED_ONLY); onDismiss() },
        )
    }
}

@Composable
private fun SortItem(label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        trailingIcon = {
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
    )
}

/**
 * Reusable single-field dialog for both "New list" (create) and "Rename list".
 * Exposed as [internal] so [ListItemsScreen] can use it without a separate file.
 */
@Composable
internal fun NameInputDialog(
    title: String,
    confirmLabel: String,
    initialValue: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(initialValue) { mutableStateOf(initialValue) }
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
                placeholder = { Text("List name") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank(),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}


