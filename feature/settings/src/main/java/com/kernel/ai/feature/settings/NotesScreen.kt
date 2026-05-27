package com.kernel.ai.feature.settings

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Note
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.outlined.PushPin
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
import androidx.compose.ui.platform.LocalClipboardManager
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import sh.calvin.reorderable.ReorderableItem
import androidx.compose.ui.text.AnnotatedString
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
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboardManager = LocalClipboardManager.current

    // Binder transaction limit is ~1MB; stay well under it. Notes above 200KB are
    // truncated for sharing with a warning — full content is still stored locally.
    val shareNote = { note: NoteEntity ->
        val full = viewModel.buildShareText(note)
        val maxBytes = 200_000
        val truncated = full.encodeToByteArray().let { bytes ->
            if (bytes.size <= maxBytes) full
            else bytes.take(maxBytes).toByteArray().decodeToString() +
                "\n\n[Note truncated — open in Kernel to view the full content]"
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, truncated)
            putExtra(Intent.EXTRA_TITLE, note.title ?: "Note")
        }
        if (truncated !== full) {
            scope.launch { snackbarHostState.showSnackbar("Note was too large and has been truncated for sharing") }
        }
        context.startActivity(Intent.createChooser(intent, "Share note"))
    }

    var showCreateDialog by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var noteToDelete by remember { mutableStateOf<NoteEntity?>(null) }
    var pendingArchiveNote by remember { mutableStateOf<NoteEntity?>(null) }
    var showBulkDeleteDialog by remember { mutableStateOf(false) }
    var showBulkArchiveDialog by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { viewModel.exitMultiSelect() }
    }

    // ── Pinned / unpinned split ──────────────────────────────────────────────────────────────
    val pinnedNotes = if (showArchived) emptyList() else displayedNotes.filter { it.pinned }
    val unpinnedNotes = if (showArchived) displayedNotes else displayedNotes.filter { !it.pinned }

    // ── Drag-and-drop local state ────────────────────────────────────────────────────────────
    var localPinned by remember { mutableStateOf(pinnedNotes) }
    var localUnpinned by remember { mutableStateOf(unpinnedNotes) }
    var dragInProgress by remember { mutableStateOf(false) }

    LaunchedEffect(pinnedNotes) { if (!dragInProgress) localPinned = pinnedNotes }
    LaunchedEffect(unpinnedNotes) { if (!dragInProgress) localUnpinned = unpinnedNotes }

    val lazyListState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val fromKey = from.key as? Long ?: return@rememberReorderableLazyListState
        val toKey = to.key as? Long ?: return@rememberReorderableLazyListState
        // Enforce section boundary — pinned items cannot cross into unpinned and vice versa
        val fromInPinned = localPinned.any { it.id == fromKey }
        val toInPinned = localPinned.any { it.id == toKey }
        if (fromInPinned != toInPinned) return@rememberReorderableLazyListState
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
                TopAppBar(
                    title = { Text("${selectedNoteIds.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.exitMultiSelect() }) {
                            Icon(Icons.Default.Close, contentDescription = "Exit selection")
                        }
                    },
                    actions = {
                        TextButton(onClick = {
                            val allIds = displayedNotes.map { it.id }
                            viewModel.selectAllNotes(allIds)
                        }) {
                            Text("Select All")
                        }
                        if (!showArchived) {
                            IconButton(onClick = { showBulkArchiveDialog = true }) {
                                Icon(Icons.Default.Archive, contentDescription = "Archive selected")
                            }
                        } else {
                            IconButton(onClick = { viewModel.bulkRestoreSelected() }) {
                                Icon(Icons.Default.Unarchive, contentDescription = "Restore selected")
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
        Column(modifier = Modifier.padding(padding)) {
            // Search bar (hidden in multi-select mode and archived view)
            if (!isMultiSelect && !showArchived) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = viewModel::setSearchQuery,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text("Search notes") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear")
                            }
                        }
                    },
                    singleLine = true,
                )
            }
            if (displayedNotes.isEmpty()) {
                EmptyState(showArchived = showArchived)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = lazyListState,
                ) {
                    // ── Pinned section ──────────────────────────────────────────────────────
                    if (localPinned.isNotEmpty()) {
                        stickyHeader(key = "header_pinned") {
                            NoteSectionHeader(label = "Pinned")
                        }
                        items(localPinned, key = { it.id }) { note ->
                            ReorderableItem(reorderState, key = note.id) { isDragging ->
                                val elevation by animateDpAsState(
                                    if (isDragging) 6.dp else 0.dp,
                                    label = "drag_elevation_pinned",
                                )
                                Surface(shadowElevation = elevation) {
                                    NoteCard(
                                        note = note,
                                        isSelected = selectedNoteIds.contains(note.id),
                                        isArchivedView = false,
                                        isMultiSelect = isMultiSelect,
                                        onEdit = {
                                            if (isMultiSelect) viewModel.toggleNoteSelection(note.id)
                                            else onEditNote(note.id)
                                        },
                                        onLongPress = {
                                            viewModel.enterMultiSelect()
                                            viewModel.toggleNoteSelection(note.id)
                                        },
                                        onTogglePin = {
                                            if (note.pinned) viewModel.unpinNote(note.id)
                                            else viewModel.pinNote(note.id)
                                        },
                                        onArchive = { pendingArchiveNote = note },
                                        onRestore = {},
                                        onDelete = { noteToDelete = note },
                                        onShare = { shareNote(note) },
                                        onCopy = {
                                            scope.launch {
                                                clipboardManager.setText(AnnotatedString(viewModel.buildShareText(note)))
                                                snackbarHostState.showSnackbar("Note copied to clipboard")
                                            }
                                        },
                                        dragHandleModifier = if (!isMultiSelect) {
                                            Modifier.draggableHandle(
                                                onDragStarted = { dragInProgress = true },
                                                onDragStopped = {
                                                    dragInProgress = false
                                                    viewModel.onNotesReordered(
                                                        localPinned.map { it.id },
                                                        localUnpinned.map { it.id },
                                                    )
                                                },
                                            )
                                        } else Modifier,
                                        onToggleSelect = { viewModel.toggleNoteSelection(note.id) },
                                    )
                                }
                            }
                        }
                    }

                    // ── Unpinned / archived section ──────────────────────────────────────────
                    if (localUnpinned.isNotEmpty()) {
                        if (localPinned.isNotEmpty()) {
                            stickyHeader(key = "header_other") {
                                NoteSectionHeader(label = "Other")
                            }
                        }
                        items(localUnpinned, key = { it.id }) { note ->
                            ReorderableItem(reorderState, key = note.id) { isDragging ->
                                val elevation by animateDpAsState(
                                    if (isDragging) 6.dp else 0.dp,
                                    label = "drag_elevation_unpinned",
                                )
                                Surface(shadowElevation = elevation) {
                                    NoteCard(
                                        note = note,
                                        isSelected = selectedNoteIds.contains(note.id),
                                        isArchivedView = showArchived,
                                        isMultiSelect = isMultiSelect,
                                        onEdit = {
                                            if (isMultiSelect) viewModel.toggleNoteSelection(note.id)
                                            else onEditNote(note.id)
                                        },
                                        onLongPress = {
                                            viewModel.enterMultiSelect()
                                            viewModel.toggleNoteSelection(note.id)
                                        },
                                        onTogglePin = {
                                            if (note.pinned) viewModel.unpinNote(note.id)
                                            else viewModel.pinNote(note.id)
                                        },
                                        onArchive = { pendingArchiveNote = note },
                                        onRestore = { viewModel.unarchiveNote(note.id) },
                                        onDelete = { noteToDelete = note },
                                        onShare = { shareNote(note) },
                                        onCopy = {
                                            scope.launch {
                                                clipboardManager.setText(AnnotatedString(viewModel.buildShareText(note)))
                                                snackbarHostState.showSnackbar("Note copied to clipboard")
                                            }
                                        },
                                        dragHandleModifier = if (!isMultiSelect && !showArchived) {
                                            Modifier.draggableHandle(
                                                onDragStarted = { dragInProgress = true },
                                                onDragStopped = {
                                                    dragInProgress = false
                                                    viewModel.onNotesReordered(
                                                        localPinned.map { it.id },
                                                        localUnpinned.map { it.id },
                                                    )
                                                },
                                            )
                                        } else Modifier,
                                        onToggleSelect = { viewModel.toggleNoteSelection(note.id) },
                                    )
                                }
                            }
                        }
                    }

                    item { Spacer(modifier = Modifier.height(88.dp)) }
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
    isArchivedView: Boolean,
    isMultiSelect: Boolean,
    onEdit: () -> Unit,
    onLongPress: () -> Unit,
    onTogglePin: () -> Unit,
    onArchive: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    dragHandleModifier: Modifier = Modifier,
    onToggleSelect: () -> Unit,
) {
    var showOverflow by remember { mutableStateOf(false) }

    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isArchivedView) Modifier.graphicsLayer { alpha = 0.6f } else Modifier)
            .combinedClickable(onClick = onEdit, onLongClick = onLongPress),
        leadingContent = {
            if (isMultiSelect) {
                Checkbox(checked = isSelected, onCheckedChange = { onToggleSelect() })
            } else {
                Icon(
                    Icons.AutoMirrored.Filled.Note,
                    contentDescription = null,
                    tint = if (note.pinned) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        headlineContent = {
            Text(
                text = note.title ?: "Untitled",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                text = note.content,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!isMultiSelect) {
                    if (!isArchivedView) {
                        // Pin toggle
                        IconButton(onClick = onTogglePin) {
                            Icon(
                                if (note.pinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                                contentDescription = if (note.pinned) "Unpin" else "Pin",
                                tint = if (note.pinned) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    // Overflow menu (archive, delete)
                    Box {
                        IconButton(onClick = { showOverflow = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(
                            expanded = showOverflow,
                            onDismissRequest = { showOverflow = false },
                        ) {
                            if (!isArchivedView) {
                                DropdownMenuItem(
                                    text = { Text("Archive") },
                                    onClick = { showOverflow = false; onArchive() },
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text("Restore") },
                                    onClick = { showOverflow = false; onRestore() },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Share") },
                                onClick = { showOverflow = false; onShare() },
                            )
                            DropdownMenuItem(
                                text = { Text("Copy to clipboard") },
                                onClick = { showOverflow = false; onCopy() },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                onClick = { showOverflow = false; onDelete() },
                            )
                        }
                    }
                }
                // Drag handle — visible in active view, hidden in archived/multi-select
                if (!isMultiSelect && !isArchivedView) {
                    Icon(
                        Icons.Default.DragHandle,
                        contentDescription = "Drag to reorder",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = dragHandleModifier
                            .pointerInput(Unit) {
                                detectTapGestures(onLongPress = { /* absorb */ })
                            }
                            .padding(horizontal = 4.dp),
                    )
                }
            }
        },
    )
    HorizontalDivider()
}

// ── Section Header ───────────────────────────────────────────────────────────────────────────

@Composable
private fun NoteSectionHeader(label: String) {
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