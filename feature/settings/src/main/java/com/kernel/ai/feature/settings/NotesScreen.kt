package com.kernel.ai.feature.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kernel.ai.core.memory.entity.NoteEntity
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NotesScreen(
    onBack: () -> Unit = {},
    onEditNote: (Long) -> Unit = {},
    onNavigateToVoiceActions: () -> Unit = {},
    viewModel: NotesViewModel = hiltViewModel(),
) {
    val displayedNotes by viewModel.displayedNotes.collectAsStateWithLifecycle()
    val isMultiSelect = viewModel.isMultiSelectMode
    val selectedNoteIds = viewModel.selectedNoteIds
    val searchQuery = viewModel.noteSearchQuery
    val showArchived = viewModel.showArchived
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var showCreateDialog by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var noteToDelete by remember { mutableStateOf<NoteEntity?>(null) }
    var pendingArchiveNote by remember { mutableStateOf<NoteEntity?>(null) }
    var showBulkDeleteDialog by remember { mutableStateOf(false) }
    var showBulkArchiveDialog by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { viewModel.exitMultiSelect() }
    }

    // ── Drag-and-drop local state ────────────────────────────────────────────────────────────
    var localNotes by remember { mutableStateOf(displayedNotes) }
    var dragInProgress by remember { mutableStateOf(false) }

    LaunchedEffect(displayedNotes) {
        if (!dragInProgress) localNotes = displayedNotes
    }

    val lazyListState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val fromKey = from.key as? Long ?: return@rememberReorderableLazyListState
        val toKey = to.key as? Long ?: return@rememberReorderableLazyListState
        val fromIdx = localNotes.indexOfFirst { it.id == fromKey }
        val toIdx = localNotes.indexOfFirst { it.id == toKey }
        if (fromIdx < 0 || toIdx < 0) return@rememberReorderableLazyListState
        localNotes = localNotes.toMutableList().apply { add(toIdx, removeAt(fromIdx)) }
        val newOrder = localNotes.mapIndexed { idx, note -> note.id to idx.toDouble() }.toMap()
        viewModel.reorderNotes(newOrder)
    }

    Scaffold(
        topBar = {
            if (isMultiSelect) {
                TopAppBar(
                    title = { Text("${selectedNoteIds.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.exitMultiSelect() }) {
                            Icon(Icons.Default.Close, contentDescription = "Exit selection")
                        }
                    },
                    actions = {
                        TextButton(onClick = {
                            val allIds = (if (showArchived) viewModel.notes.value else displayedNotes).map { it.id }
                            viewModel.selectAllNotes(allIds)
                        }) {
                            Text("Select All")
                        }
                        if (!showArchived) {
                            IconButton(onClick = { showBulkArchiveDialog = true }) {
                                Icon(Icons.Default.Archive, contentDescription = "Archive selected")
                            }
                        }
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
                    title = { Text(if (showArchived) "Archived Notes" else "Notes") },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (showArchived) viewModel.toggleArchivedView() else onBack()
                        }) {
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
                                showArchived = showArchived,
                                currentSort = viewModel.listSort,
                                currentFilter = viewModel.listFilter,
                                onToggleArchived = { viewModel.toggleArchivedView(); showSortMenu = false },
                                onSortSelected = { viewModel.listSort = it; showSortMenu = false },
                                onFilterSelected = { viewModel.listFilter = it; showSortMenu = false },
                                onDismiss = { showSortMenu = false },
                            )
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!isMultiSelect && !showArchived) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SmallFloatingActionButton(
                        onClick = onNavigateToVoiceActions,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = "Voice note")
                    }
                    FloatingActionButton(
                        onClick = { showCreateDialog = true },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "New note")
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            if (displayedNotes.isEmpty()) {
                EmptyState(showArchived = showArchived)
            } else {
                LazyColumn(
                    state = lazyListState,
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(displayedNotes, key = { it.id }) { note ->
                        ReorderableItem(reorderState, key = note.id) {
                            val isSelected = selectedNoteIds.contains(note.id)
                            NoteCard(
                                note = note,
                                isSelected = isSelected,
                                onEdit = {
                                    if (isMultiSelect) {
                                        viewModel.toggleNoteSelection(note.id)
                                    } else {
                                        onEditNote(note.id)
                                    }
                                },
                                onLongPress = {
                                    viewModel.enterMultiSelect()
                                    viewModel.toggleNoteSelection(note.id)
                                },
                                onTogglePin = {
                                    if (note.pinned) viewModel.unpinNote(note.id) else viewModel.pinNote(note.id)
                                },
                                onArchive = { pendingArchiveNote = note },
                                onDelete = { noteToDelete = note },
                                dragHandleModifier = if (!isMultiSelect && viewModel.listSort == NoteSort.MANUAL) {
                                    Modifier.draggableHandle(
                                        onDragStarted = { dragInProgress = true },
                                        onDragStopped = {
                                            dragInProgress = false
                                            val newOrder = localNotes.mapIndexed { idx, n -> n.id to idx.toDouble() }.toMap()
                                            viewModel.reorderNotes(newOrder)
                                        },
                                    )
                                } else Modifier,
                            )
                        }
                    }
                }
            }
        }
    }

    // ── Dialogs ──────────────────────────────────────────────────────────────────────────────
    if (showCreateDialog) {
        CreateNoteDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { title, content ->
                viewModel.createNote(title, content)
                showCreateDialog = false
            },
        )
    }

    noteToDelete?.let { note ->
        ConfirmDeleteDialog(
            onDismiss = { noteToDelete = null },
            onConfirm = {
                viewModel.deleteNote(note)
                noteToDelete = null
            },
        )
    }

    pendingArchiveNote?.let { note ->
        ConfirmActionDialog(
            title = "Archive Note",
            message = "Are you sure you want to archive this note?",
            onDismiss = { pendingArchiveNote = null },
            onConfirm = {
                viewModel.archiveNote(note.id)
                pendingArchiveNote = null
            },
        )
    }

    if (showBulkDeleteDialog) {
        ConfirmActionDialog(
            title = "Delete ${selectedNoteIds.size} Notes",
            message = "Are you sure you want to delete the selected notes?",
            onDismiss = { showBulkDeleteDialog = false },
            onConfirm = {
                viewModel.bulkDeleteSelected()
                showBulkDeleteDialog = false
            },
        )
    }

    if (showBulkArchiveDialog) {
        ConfirmActionDialog(
            title = "Archive ${selectedNoteIds.size} Notes",
            message = "Are you sure you want to archive the selected notes?",
            onDismiss = { showBulkArchiveDialog = false },
            onConfirm = {
                viewModel.bulkArchiveSelected()
                showBulkArchiveDialog = false
            },
        )
    }
}

// ── Empty States ────────────────────────────────────────────────────────────────────────────

@Composable
private fun EmptyState(showArchived: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val (icon, message) = when {
            showArchived -> Icons.Default.Archive to "No archived notes yet"
            else -> Icons.Default.Note to "No notes yet"
        }
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = message, style = MaterialTheme.typography.bodyLarge)
    }
}

// ── Sort/Filter Menu ────────────────────────────────────────────────────────────────────────

@Composable
private fun SortFilterMenu(
    expanded: Boolean,
    showArchived: Boolean,
    currentSort: NoteSort,
    currentFilter: NoteFilter,
    onToggleArchived: () -> Unit,
    onSortSelected: (NoteSort) -> Unit,
    onFilterSelected: (NoteFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        DropdownMenuItem(
            text = { Text(if (showArchived) "Show Active" else "Show Archived") },
            onClick = onToggleArchived,
        )
        Divider()
        Text("Sort by", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        NoteSort.entries.forEach { sort ->
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (sort == currentSort) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(sort.displayName)
                    }
                },
                onClick = { onSortSelected(sort) },
            )
        }
        Divider()
        Text("Filter", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        NoteFilter.entries.forEach { filter ->
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (filter == currentFilter) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(filter.displayName)
                    }
                },
                onClick = { onFilterSelected(filter) },
            )
        }
    }
}

// ── Note Card ───────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(
    note: NoteEntity,
    isSelected: Boolean,
    onEdit: () -> Unit,
    onLongPress: () -> Unit,
    onTogglePin: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    dragHandleModifier: Modifier = Modifier,
) {
    Card(
        modifier = dragHandleModifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onEdit,
                onLongClick = onLongPress,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // ── Multi-select checkbox ──────────────────────────────────────
            if (isSelected) {
                Checkbox(
                    checked = true,
                    onCheckedChange = null,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                // ── Title ────────────────────────────────────────────────────
                Text(
                    text = note.title ?: "Untitled",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))

                // ── Content preview ──────────────────────────────────────────
                Text(
                    text = note.content,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ── Actions ────────────────────────────────────────────────────
            if (!isSelected) {
                Column(horizontalAlignment = Alignment.End) {
                    IconButton(onClick = onTogglePin) {
                        Icon(
                            imageVector = Icons.Default.PushPin,
                            contentDescription = if (note.pinned) "Unpin" else "Pin",
                            tint = if (note.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(onClick = onArchive) {
                            Icon(
                                Icons.Default.Archive,
                                contentDescription = "Archive",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = onDelete) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Dialogs ─────────────────────────────────────────────────────────────────────────────────

@Composable
private fun CreateNoteDialog(
    onDismiss: () -> Unit,
    onConfirm: (String?, String) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Note") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title (optional)") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    maxLines = 2,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Content") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    maxLines = 10,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(title.takeIf { it.isNotBlank() }, content) },
                enabled = content.isNotBlank(),
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}

@Composable
private fun ConfirmDeleteDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Note") },
        text = { Text("Are you sure you want to delete this note?") },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun ConfirmActionDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            Button(onClick = onConfirm) { Text("Confirm") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}