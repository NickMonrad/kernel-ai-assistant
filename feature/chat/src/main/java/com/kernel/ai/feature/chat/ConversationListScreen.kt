package com.kernel.ai.feature.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Unarchive
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
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kernel.ai.core.memory.entity.ConversationEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ConversationListScreen(
    onOpenConversation: (String) -> Unit,
    onNewConversation: () -> Unit,
    onNavigateToActions: () -> Unit = {},
    onNavigateToVoiceActions: () -> Unit = {},
    onOpenDrawer: () -> Unit = {},
    viewModel: ConversationListViewModel = hiltViewModel(),
) {
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val pinnedConversations by viewModel.pinnedConversations.collectAsStateWithLifecycle()
    val unpinnedConversations by viewModel.unpinnedConversations.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val isInSelectionMode by viewModel.isInSelectionMode.collectAsStateWithLifecycle()
    val selectedConversationIds by viewModel.selectedConversationIds.collectAsStateWithLifecycle()
    val showBulkDeleteConfirmation by viewModel.showBulkDeleteConfirmation.collectAsStateWithLifecycle()
    val showArchived by viewModel.showArchived.collectAsStateWithLifecycle()

    var pendingDelete by remember { mutableStateOf<ConversationEntity?>(null) }
    var pendingArchive by remember { mutableStateOf<ConversationEntity?>(null) }
    var pendingRenameId by rememberSaveable { mutableStateOf<String?>(null) }
    val pendingRename = pendingRenameId?.let { id -> conversations.find { it.id == id } }
    var contextMenuTarget by remember { mutableStateOf<ConversationEntity?>(null) }
    var showOverflowMenu by remember { mutableStateOf(false) }

    // ── Drag-and-drop local state (optimistic UI) ──────────────────────────
    var localPinned by remember { mutableStateOf(pinnedConversations) }
    var localUnpinned by remember { mutableStateOf(unpinnedConversations) }
    var dragInProgress by remember { mutableStateOf(false) }
    LaunchedEffect(pinnedConversations) { if (!dragInProgress) localPinned = pinnedConversations }
    LaunchedEffect(unpinnedConversations) { if (!dragInProgress) localUnpinned = unpinnedConversations }

    val lazyListState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val fromKey = from.key as? String ?: return@rememberReorderableLazyListState
        val toKey = to.key as? String ?: return@rememberReorderableLazyListState
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

    // Exit selection mode on system back instead of popping the screen
    BackHandler(enabled = isInSelectionMode) {
        viewModel.clearSelection()
    }

    Scaffold(
        topBar = {
            if (isInSelectionMode) {
                // ── Contextual multi-select TopAppBar ──────────────────────
                TopAppBar(
                    title = { Text("${selectedConversationIds.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = viewModel::clearSelection) {
                            Icon(Icons.Default.Close, contentDescription = "Exit selection")
                        }
                    },
                    actions = {
                        TextButton(onClick = { viewModel.selectAll(conversations.map { it.id }) }) {
                            Text("Select All")
                        }
                        if (showArchived) {
                            IconButton(onClick = viewModel::restoreSelected) {
                                Icon(Icons.Default.Unarchive, contentDescription = "Restore selected")
                            }
                        } else {
                            IconButton(onClick = viewModel::archiveSelected) {
                                Icon(Icons.Default.Archive, contentDescription = "Archive selected")
                            }
                        }
                        IconButton(onClick = viewModel::requestBulkDelete) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete selected",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    },
                )
            } else {
                // ── Normal TopAppBar ────────────────────────────────────────
                TopAppBar(
                    title = {
                        if (showArchived) Text("Archived") else Text("Jandal")
                    },
                    navigationIcon = {
                        IconButton(onClick = onOpenDrawer) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { showOverflowMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More options")
                            }
                            DropdownMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (showArchived) "Show active conversations"
                                            else "Show archived conversations",
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            if (showArchived) Icons.Default.Unarchive else Icons.Default.Archive,
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        viewModel.toggleShowArchived()
                                    },
                                )
                            }
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!isInSelectionMode && !showArchived) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    SmallFloatingActionButton(
                        onClick = onNavigateToVoiceActions,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = "Voice action")
                    }
                    SmallFloatingActionButton(
                        onClick = onNavigateToActions,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ) {
                        Icon(Icons.Default.Bolt, contentDescription = "Quick action")
                    }
                    FloatingActionButton(
                        onClick = onNewConversation,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "New conversation")
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding()),
        ) {
            // Search bar — hidden in selection mode
            if (!isInSelectionMode) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.onSearchQueryChanged(it) },
                    placeholder = { Text(if (showArchived) "Search archived" else "Search conversations") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotBlank()) {
                            IconButton(onClick = { viewModel.clearSearch() }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear search")
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            if (conversations.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (searchQuery.isNotBlank()) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("🔍", style = MaterialTheme.typography.displayMedium)
                            Text(
                                text = "No conversations match \"$searchQuery\"",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    } else if (showArchived) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("📦", style = MaterialTheme.typography.displayMedium)
                            Text(
                                text = "No archived conversations",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("🧵", style = MaterialTheme.typography.displayMedium)
                            Text(
                                text = "No conversations yet",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            Text(
                                text = "Tap + to start chatting",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = lazyListState,
                    contentPadding = PaddingValues(bottom = innerPadding.calculateBottomPadding()),
                ) {
                    // ── Pinned section (active view only) ───────────────────
                    if (!showArchived && localPinned.isNotEmpty()) {
                        stickyHeader(key = "header_pinned") {
                            ConversationSectionHeader(label = "Pinned")
                        }
                        items(localPinned, key = { it.id }) { conversation ->
                            ReorderableItem(reorderState, key = conversation.id) { isDragging ->
                                val elevation by animateDpAsState(
                                    if (isDragging) 6.dp else 0.dp,
                                    label = "drag_elevation_pinned",
                                )
                                Surface(shadowElevation = elevation) {
                                    SwipeableConversationRow(
                                        conversation = conversation,
                                        isInSelectionMode = isInSelectionMode,
                                        isSelected = conversation.id in selectedConversationIds,
                                        showArchived = false,
                                        dragHandleModifier = if (!isInSelectionMode) {
                                            Modifier.draggableHandle(
                                                onDragStarted = { dragInProgress = true },
                                                onDragStopped = {
                                                    dragInProgress = false
                                                    viewModel.onConversationsReordered(
                                                        localPinned.map { it.id },
                                                        localUnpinned.map { it.id },
                                                    )
                                                },
                                            )
                                        } else Modifier,
                                        onOpen = {
                                            if (isInSelectionMode) viewModel.toggleSelection(conversation.id)
                                            else onOpenConversation(conversation.id)
                                        },
                                        onLongClick = {
                                            if (!isInSelectionMode) contextMenuTarget = conversation
                                        },
                                        onToggleSelect = { viewModel.toggleSelection(conversation.id) },
                                        onArchiveRequest = { pendingArchive = conversation },
                                        onRestore = { viewModel.restoreConversation(conversation.id) },
                                        onDeleteRequest = { pendingDelete = conversation },
                                        onTogglePin = { viewModel.togglePin(conversation.id) },
                                    )
                                }
                            }
                            if (!isInSelectionMode) {
                                DropdownMenu(
                                    expanded = contextMenuTarget?.id == conversation.id,
                                    onDismissRequest = { contextMenuTarget = null },
                                ) {
                                    ConversationContextMenuItems(
                                        showArchived = false,
                                        onSelect = {
                                            contextMenuTarget = null
                                            viewModel.enterSelectionMode(conversation.id)
                                        },
                                        onRename = {
                                            pendingRenameId = conversation.id
                                            contextMenuTarget = null
                                        },
                                        onArchiveRequest = {
                                            pendingArchive = conversation
                                            contextMenuTarget = null
                                        },
                                        onRestore = {},
                                        onDelete = {
                                            pendingDelete = conversation
                                            contextMenuTarget = null
                                        },
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }

                    // ── Unpinned section (active view only) ─────────────────
                    if (!showArchived) {
                        if (localPinned.isNotEmpty() && localUnpinned.isNotEmpty()) {
                            stickyHeader(key = "header_other") {
                                ConversationSectionHeader(label = "Other")
                            }
                        }
                        items(localUnpinned, key = { it.id }) { conversation ->
                            ReorderableItem(reorderState, key = conversation.id) { isDragging ->
                                val elevation by animateDpAsState(
                                    if (isDragging) 6.dp else 0.dp,
                                    label = "drag_elevation_unpinned",
                                )
                                Surface(shadowElevation = elevation) {
                                    SwipeableConversationRow(
                                        conversation = conversation,
                                        isInSelectionMode = isInSelectionMode,
                                        isSelected = conversation.id in selectedConversationIds,
                                        showArchived = false,
                                        dragHandleModifier = if (!isInSelectionMode) {
                                            Modifier.draggableHandle(
                                                onDragStarted = { dragInProgress = true },
                                                onDragStopped = {
                                                    dragInProgress = false
                                                    viewModel.onConversationsReordered(
                                                        localPinned.map { it.id },
                                                        localUnpinned.map { it.id },
                                                    )
                                                },
                                            )
                                        } else Modifier,
                                        onOpen = {
                                            if (isInSelectionMode) viewModel.toggleSelection(conversation.id)
                                            else onOpenConversation(conversation.id)
                                        },
                                        onLongClick = {
                                            if (!isInSelectionMode) contextMenuTarget = conversation
                                        },
                                        onToggleSelect = { viewModel.toggleSelection(conversation.id) },
                                        onArchiveRequest = { pendingArchive = conversation },
                                        onRestore = { viewModel.restoreConversation(conversation.id) },
                                        onDeleteRequest = { pendingDelete = conversation },
                                        onTogglePin = { viewModel.togglePin(conversation.id) },
                                    )
                                }
                            }
                            if (!isInSelectionMode) {
                                DropdownMenu(
                                    expanded = contextMenuTarget?.id == conversation.id,
                                    onDismissRequest = { contextMenuTarget = null },
                                ) {
                                    ConversationContextMenuItems(
                                        showArchived = false,
                                        onSelect = {
                                            contextMenuTarget = null
                                            viewModel.enterSelectionMode(conversation.id)
                                        },
                                        onRename = {
                                            pendingRenameId = conversation.id
                                            contextMenuTarget = null
                                        },
                                        onArchiveRequest = {
                                            pendingArchive = conversation
                                            contextMenuTarget = null
                                        },
                                        onRestore = {},
                                        onDelete = {
                                            pendingDelete = conversation
                                            contextMenuTarget = null
                                        },
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }

                    // ── Archived section ──────────────────────────────────────
                    if (showArchived) {
                        items(conversations, key = { it.id }) { conversation ->
                            ConversationListItem(
                                conversation = conversation,
                                isInSelectionMode = isInSelectionMode,
                                isSelected = conversation.id in selectedConversationIds,
                                showArchived = true,
                                onOpen = {
                                    if (isInSelectionMode) viewModel.toggleSelection(conversation.id)
                                    else onOpenConversation(conversation.id)
                                },
                                onLongClick = {
                                    if (!isInSelectionMode) contextMenuTarget = conversation
                                },
                            )
                            if (!isInSelectionMode) {
                                DropdownMenu(
                                    expanded = contextMenuTarget?.id == conversation.id,
                                    onDismissRequest = { contextMenuTarget = null },
                                ) {
                                    ConversationContextMenuItems(
                                        showArchived = true,
                                        onSelect = {
                                            contextMenuTarget = null
                                            viewModel.enterSelectionMode(conversation.id)
                                        },
                                        onRename = {},
                                        onArchiveRequest = {},
                                        onRestore = {
                                            viewModel.restoreConversation(conversation.id)
                                            contextMenuTarget = null
                                        },
                                        onDelete = {
                                            pendingDelete = conversation
                                            contextMenuTarget = null
                                        },
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }

                    item { Spacer(modifier = Modifier.height(88.dp)) }
                }
            }
        } // end Column
    }

    // Archive confirmation dialog
    pendingArchive?.let { conversation ->
        AlertDialog(
            onDismissRequest = { pendingArchive = null },
            title = { Text("Archive conversation?") },
            text = {
                Text("\"${conversation.title ?: "New conversation"}\" will be moved to archive.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.archiveConversation(conversation.id)
                        pendingArchive = null
                    }
                ) { Text("Archive") }
            },
            dismissButton = {
                TextButton(onClick = { pendingArchive = null }) { Text("Cancel") }
            },
        )
    }

    // Delete confirmation dialog
    pendingDelete?.let { conversation ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete conversation?") },
            text = {
                Text("\"${conversation.title ?: "New conversation"}\" will be permanently deleted.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteConversation(conversation)
                        pendingDelete = null
                    }
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }

    // Rename dialog
    pendingRename?.let { conversation ->
        var renameText by rememberSaveable(conversation.id) {
            mutableStateOf(conversation.title ?: "")
        }
        AlertDialog(
            onDismissRequest = { pendingRenameId = null },
            title = { Text("Rename conversation") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    placeholder = { Text("Enter a title…") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = renameText.trim()
                        if (trimmed.isNotBlank()) {
                            viewModel.renameConversation(conversation.id, trimmed)
                        }
                        pendingRenameId = null
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRenameId = null }) { Text("Cancel") }
            },
        )
    }

    // Bulk delete confirmation dialog
    if (showBulkDeleteConfirmation) {
        val count = selectedConversationIds.size
        AlertDialog(
            onDismissRequest = viewModel::dismissBulkDeleteConfirmation,
            title = { Text("Delete $count ${if (count == 1) "conversation" else "conversations"}?") },
            text = { Text("These conversations will be permanently deleted. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = viewModel::deleteSelected) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissBulkDeleteConfirmation) { Text("Cancel") }
            },
        )
    }
}

// ── Shared context menu items ────────────────────────────────────────────────────────────────────

@Composable
private fun ConversationContextMenuItems(
    showArchived: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onArchiveRequest: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    DropdownMenuItem(text = { Text("Select") }, onClick = onSelect)
    if (showArchived) {
        DropdownMenuItem(text = { Text("Restore") }, onClick = onRestore)
    } else {
        DropdownMenuItem(text = { Text("Rename") }, onClick = onRename)
        DropdownMenuItem(text = { Text("Archive") }, onClick = onArchiveRequest)
    }
    DropdownMenuItem(
        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
        onClick = onDelete,
    )
}

// ── SwipeableConversationRow ─────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun SwipeableConversationRow(
    conversation: ConversationEntity,
    isInSelectionMode: Boolean,
    isSelected: Boolean,
    showArchived: Boolean,
    dragHandleModifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onLongClick: () -> Unit,
    onToggleSelect: () -> Unit,
    onArchiveRequest: () -> Unit,
    onRestore: () -> Unit,
    onDeleteRequest: () -> Unit,
    onTogglePin: () -> Unit,
) {
    var pendingSwipeDelete by remember { mutableStateOf(false) }
    var pendingSwipeArchive by remember { mutableStateOf(false) }

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.EndToStart -> {
                    if (showArchived) onRestore() else { pendingSwipeArchive = true }
                    false
                }
                SwipeToDismissBoxValue.StartToEnd -> {
                    pendingSwipeDelete = true
                    false
                }
                SwipeToDismissBoxValue.Settled -> false
            }
        },
    )

    LaunchedEffect(pendingSwipeDelete) {
        if (pendingSwipeDelete) {
            pendingSwipeDelete = false
            onDeleteRequest()
        }
    }

    LaunchedEffect(pendingSwipeArchive) {
        if (pendingSwipeArchive) {
            pendingSwipeArchive = false
            onArchiveRequest()
        }
    }

    if (isInSelectionMode) {
        ConversationListItem(
            conversation = conversation,
            isInSelectionMode = true,
            isSelected = isSelected,
            showArchived = showArchived,
            onOpen = onOpen,
            onLongClick = onLongClick,
        )
    } else {
        SwipeToDismissBox(
            state = dismissState,
            backgroundContent = {
                val direction = dismissState.dismissDirection
                val isStartToEnd = direction == SwipeToDismissBoxValue.StartToEnd
                val isEndToStart = direction == SwipeToDismissBoxValue.EndToStart

                val backgroundColor by animateColorAsState(
                    targetValue = when {
                        isStartToEnd -> MaterialTheme.colorScheme.errorContainer
                        isEndToStart -> if (showArchived) MaterialTheme.colorScheme.secondaryContainer
                                        else Color(0xFF2E7D32)
                        else -> Color.Transparent
                    },
                    label = "swipe_bg",
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(backgroundColor)
                        .padding(horizontal = 20.dp),
                    contentAlignment = if (isStartToEnd) Alignment.CenterStart else Alignment.CenterEnd,
                ) {
                    when {
                        isStartToEnd -> Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        isEndToStart && showArchived -> Icon(
                            Icons.Default.Unarchive,
                            contentDescription = "Restore",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        isEndToStart -> Icon(
                            Icons.Default.Archive,
                            contentDescription = "Archive",
                            tint = Color.White,
                        )
                    }
                }
            },
            enableDismissFromStartToEnd = true,
            enableDismissFromEndToStart = true,
            content = {
                ConversationListItem(
                    conversation = conversation,
                    isInSelectionMode = false,
                    isSelected = false,
                    showArchived = showArchived,
                    dragHandleModifier = dragHandleModifier,
                    onOpen = onOpen,
                    onLongClick = onLongClick,
                    onTogglePin = onTogglePin,
                )
            },
        )
    }
}

// ── ConversationListItem ─────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationListItem(
    conversation: ConversationEntity,
    isInSelectionMode: Boolean,
    isSelected: Boolean,
    showArchived: Boolean,
    dragHandleModifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onLongClick: () -> Unit,
    onTogglePin: (() -> Unit)? = null,
) {
    ListItem(
        headlineContent = {
            Text(
                text = conversation.title ?: "New conversation",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (showArchived) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
            )
        },
        supportingContent = {
            Text(
                text = if (showArchived && conversation.archivedAt != null)
                    "Archived ${formatTimestamp(conversation.archivedAt ?: conversation.updatedAt)}"
                else
                    formatTimestamp(conversation.updatedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        },
        leadingContent = if (isInSelectionMode) {
            {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = null,
                )
            }
        } else {
            null
        },
        trailingContent = if (!isInSelectionMode && !showArchived) {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onTogglePin != null) {
                        IconButton(onClick = onTogglePin) {
                            Icon(
                                imageVector = if (conversation.pinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                                contentDescription = if (conversation.pinned) "Unpin" else "Pin",
                                tint = if (conversation.pinned) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                    Icon(
                        Icons.Default.DragHandle,
                        contentDescription = "Drag to reorder",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = dragHandleModifier
                            .pointerInput(Unit) {
                                detectTapGestures(onLongPress = { /* absorb — prevent combinedClickable from firing */ })
                            }
                            .padding(horizontal = 4.dp),
                    )
                }
            }
        } else {
            null
        },
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onOpen,
                onLongClick = onLongClick,
            ),
    )
}

/** Sticky section header — surface background so it occludes scrolled-under rows. */
@Composable
private fun ConversationSectionHeader(label: String) {
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

private fun formatTimestamp(millis: Long): String {
    val sdf = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
    return sdf.format(Date(millis))
}
