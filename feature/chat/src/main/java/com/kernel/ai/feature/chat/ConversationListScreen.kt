package com.kernel.ai.feature.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kernel.ai.core.memory.entity.ConversationEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val isInSelectionMode by viewModel.isInSelectionMode.collectAsStateWithLifecycle()
    val selectedConversationIds by viewModel.selectedConversationIds.collectAsStateWithLifecycle()
    val showBulkDeleteConfirmation by viewModel.showBulkDeleteConfirmation.collectAsStateWithLifecycle()
    val showArchived by viewModel.showArchived.collectAsStateWithLifecycle()

    var pendingDelete by remember { mutableStateOf<ConversationEntity?>(null) }
    var pendingRenameId by rememberSaveable { mutableStateOf<String?>(null) }
    val pendingRename = pendingRenameId?.let { id -> conversations.find { it.id == id } }
    var contextMenuTarget by remember { mutableStateOf<ConversationEntity?>(null) }
    var showOverflowMenu by remember { mutableStateOf(false) }

    // Exit selection mode on system back instead of popping the screen
    BackHandler(enabled = isInSelectionMode) {
        viewModel.clearSelection()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isInSelectionMode) {
                        Text("${selectedConversationIds.size} / ${conversations.size} selected")
                    } else if (showArchived) {
                        Text("Archived")
                    } else {
                        Text("Jandal")
                    }
                },
                navigationIcon = {
                    if (!isInSelectionMode) {
                        IconButton(onClick = onOpenDrawer) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                    }
                },
                actions = {
                    if (isInSelectionMode) {
                        TextButton(onClick = { viewModel.selectAll(conversations.map { it.id }) }) {
                            Text("Select All")
                        }
                        if (showArchived) {
                            // Archived view: Restore + Delete
                            Button(
                                onClick = viewModel::restoreSelected,
                                enabled = selectedConversationIds.isNotEmpty(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                ),
                            ) {
                                Text("Restore (${selectedConversationIds.size})")
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                        } else {
                            // Active view: Archive + Delete
                            Button(
                                onClick = viewModel::archiveSelected,
                                enabled = selectedConversationIds.isNotEmpty(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                ),
                            ) {
                                Text("Archive (${selectedConversationIds.size})")
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Button(
                            onClick = viewModel::requestBulkDelete,
                            enabled = selectedConversationIds.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                        ) {
                            Text("Delete (${selectedConversationIds.size})")
                        }
                        TextButton(onClick = viewModel::clearSelection) {
                            Text("Cancel")
                        }
                    } else {
                        // ⋮ overflow menu
                        Box {
                            IconButton(onClick = { showOverflowMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More options")
                            }
                            DropdownMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(if (showArchived) "Show Active" else "Show Archived") },
                                    onClick = {
                                        showOverflowMenu = false
                                        viewModel.toggleShowArchived()
                                    },
                                )
                            }
                        }
                    }
                },
            )
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
                    contentPadding = PaddingValues(bottom = innerPadding.calculateBottomPadding()),
                ) {
                    items(conversations, key = { it.id }) { conversation ->
                        SwipeableConversationRow(
                            conversation = conversation,
                            isInSelectionMode = isInSelectionMode,
                            isSelected = conversation.id in selectedConversationIds,
                            showArchived = showArchived,
                            onOpen = {
                                if (isInSelectionMode) viewModel.toggleSelection(conversation.id)
                                else onOpenConversation(conversation.id)
                            },
                            onLongClick = {
                                if (!isInSelectionMode) contextMenuTarget = conversation
                            },
                            onToggleSelect = { viewModel.toggleSelection(conversation.id) },
                            onArchive = { viewModel.archiveConversation(conversation.id) },
                            onRestore = { viewModel.restoreConversation(conversation.id) },
                            onDeleteRequest = { pendingDelete = conversation },
                        )
                        // Context menu — only when NOT in selection mode
                        if (!isInSelectionMode) {
                            DropdownMenu(
                                expanded = contextMenuTarget?.id == conversation.id,
                                onDismissRequest = { contextMenuTarget = null },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Select") },
                                    onClick = {
                                        contextMenuTarget = null
                                        viewModel.enterSelectionMode(conversation.id)
                                    },
                                )
                                if (showArchived) {
                                    DropdownMenuItem(
                                        text = { Text("Restore") },
                                        onClick = {
                                            viewModel.restoreConversation(conversation.id)
                                            contextMenuTarget = null
                                        },
                                    )
                                } else {
                                    DropdownMenuItem(
                                        text = { Text("Rename") },
                                        onClick = {
                                            pendingRenameId = conversation.id
                                            contextMenuTarget = null
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Archive") },
                                        onClick = {
                                            viewModel.archiveConversation(conversation.id)
                                            contextMenuTarget = null
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        pendingDelete = conversation
                                        contextMenuTarget = null
                                    },
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        } // end Column
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun SwipeableConversationRow(
    conversation: ConversationEntity,
    isInSelectionMode: Boolean,
    isSelected: Boolean,
    showArchived: Boolean,
    onOpen: () -> Unit,
    onLongClick: () -> Unit,
    onToggleSelect: () -> Unit,
    onArchive: () -> Unit,
    onRestore: () -> Unit,
    onDeleteRequest: () -> Unit,
) {
    // Track pending delete to show dialog after resetting dismiss state
    var pendingSwipeDelete by remember { mutableStateOf(false) }

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.EndToStart -> {
                    // Swipe left: archive (active) or restore (archived)
                    if (showArchived) onRestore() else onArchive()
                    false // Don't dismiss the item, just trigger action & snap back
                }
                SwipeToDismissBoxValue.StartToEnd -> {
                    // Swipe right: request delete (show dialog via LaunchedEffect)
                    pendingSwipeDelete = true
                    false // Don't dismiss — dialog will handle it
                }
                SwipeToDismissBoxValue.Settled -> false
            }
        }
    )

    // Snap-back is automatic: confirmValueChange always returns false,
    // so AnchoredDraggable refuses the transition and animates back to Settled.

    // When a swipe-right delete is pending, trigger the delete dialog
    LaunchedEffect(pendingSwipeDelete) {
        if (pendingSwipeDelete) {
            pendingSwipeDelete = false
            onDeleteRequest()
        }
    }

    if (isInSelectionMode) {
        // In selection mode, skip swipe — just show the list item
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
                                        else MaterialTheme.colorScheme.tertiaryContainer
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
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
            },
            enableDismissFromStartToEnd = !isInSelectionMode,
            enableDismissFromEndToStart = !isInSelectionMode,
            content = {
                ConversationListItem(
                    conversation = conversation,
                    isInSelectionMode = false,
                    isSelected = false,
                    showArchived = showArchived,
                    onOpen = onOpen,
                    onLongClick = onLongClick,
                )
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationListItem(
    conversation: ConversationEntity,
    isInSelectionMode: Boolean,
    isSelected: Boolean,
    showArchived: Boolean,
    onOpen: () -> Unit,
    onLongClick: () -> Unit,
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
        trailingContent = if (!isInSelectionMode) {
            {
                Icon(
                    imageVector = Icons.Default.PushPin,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                )
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

private fun formatTimestamp(millis: Long): String {
    val sdf = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
    return sdf.format(Date(millis))
}
