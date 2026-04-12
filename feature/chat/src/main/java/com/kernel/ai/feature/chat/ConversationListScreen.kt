package com.kernel.ai.feature.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    onNavigateToSettings: () -> Unit = {},
    viewModel: ConversationListViewModel = hiltViewModel(),
) {
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val isInSelectionMode by viewModel.isInSelectionMode.collectAsStateWithLifecycle()
    val selectedConversationIds by viewModel.selectedConversationIds.collectAsStateWithLifecycle()
    val showBulkDeleteConfirmation by viewModel.showBulkDeleteConfirmation.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<ConversationEntity?>(null) }
    // Store ID only (String is Parcelable-safe) to survive configuration changes.
    var pendingRenameId by rememberSaveable { mutableStateOf<String?>(null) }
    val pendingRename = pendingRenameId?.let { id -> conversations.find { it.id == id } }
    var contextMenuTarget by remember { mutableStateOf<ConversationEntity?>(null) }

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
                    } else {
                        Text("Jandal")
                    }
                },
                actions = {
                    if (isInSelectionMode) {
                        TextButton(onClick = { viewModel.selectAll(conversations.map { it.id }) }) {
                            Text("Select All")
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
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (!isInSelectionMode) {
                FloatingActionButton(onClick = onNewConversation) {
                    Icon(Icons.Default.Add, contentDescription = "New conversation")
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // Search bar — hidden in selection mode
            if (!isInSelectionMode) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.onSearchQueryChanged(it) },
                    placeholder = { Text("Search conversations") },
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
                ) {
                    items(conversations, key = { it.id }) { conversation ->
                        Box {
                            ListItem(
                                headlineContent = {
                                    Text(
                                        text = conversation.title ?: "New conversation",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        text = formatTimestamp(conversation.updatedAt),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                },
                                leadingContent = if (isInSelectionMode) {
                                    {
                                        Checkbox(
                                            checked = conversation.id in selectedConversationIds,
                                            onCheckedChange = { viewModel.toggleSelection(conversation.id) },
                                        )
                                    }
                                } else {
                                    null
                                },
                                trailingContent = if (!isInSelectionMode) {
                                    {
                                        IconButton(onClick = { pendingDelete = conversation }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete")
                                        }
                                    }
                                } else {
                                    null
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            if (isInSelectionMode) {
                                                viewModel.toggleSelection(conversation.id)
                                            } else {
                                                onOpenConversation(conversation.id)
                                            }
                                        },
                                        onLongClick = {
                                            if (!isInSelectionMode) {
                                                contextMenuTarget = conversation
                                            }
                                        },
                                    ),
                            )
                            // Long-press context menu — only when NOT in selection mode
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
                                    DropdownMenuItem(
                                        text = { Text("Rename") },
                                        onClick = {
                                            pendingRenameId = conversation.id
                                            contextMenuTarget = null
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Delete") },
                                        onClick = {
                                            pendingDelete = conversation
                                            contextMenuTarget = null
                                        },
                                    )
                                }
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

private fun formatTimestamp(millis: Long): String {
    val sdf = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
    return sdf.format(Date(millis))
}
