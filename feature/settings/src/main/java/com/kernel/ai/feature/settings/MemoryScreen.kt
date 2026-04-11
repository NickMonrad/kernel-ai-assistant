package com.kernel.ai.feature.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kernel.ai.core.memory.entity.EpisodicMemoryEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
    onBack: () -> Unit,
    viewModel: MemoryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Memory") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            if (!uiState.isInSelectionMode) {
                FloatingActionButton(
                    onClick = { if (!uiState.isSubmitting) viewModel.openAddDialog() },
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add core memory")
                }
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 88.dp), // avoid FAB overlap
        ) {
            // ── Stats ──────────────────────────────────────────────────────
            item {
                SectionHeader("Stats")
            }
            item {
                val total = uiState.coreMemories.size + uiState.episodicCount
                ListItem(
                    headlineContent = {
                        Text("Total: $total (Core: ${uiState.coreMemories.size}, Episodic: ${uiState.episodicCount})")
                    },
                )
                HorizontalDivider()
            }

            // ── Core Memories ──────────────────────────────────────────────
            item {
                CoreMemoriesSectionHeader(
                    isInSelectionMode = uiState.isInSelectionMode,
                    selectedCount = uiState.selectedCoreIds.size,
                    totalCount = uiState.coreMemories.size,
                    onSelectAll = { viewModel.selectAll(uiState.coreMemories.map { it.id }) },
                    onDeleteSelected = viewModel::requestBulkDelete,
                    onCancelSelection = viewModel::clearSelection,
                )
            }

            if (uiState.coreMemories.isEmpty()) {
                item {
                    Text(
                        text = "No core memories yet. Tap + to add one.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            } else {
                items(uiState.coreMemories, key = { it.id }) { memory ->
                    CoreMemoryItem(
                        content = memory.content,
                        source = memory.source,
                        accessCount = memory.accessCount,
                        isInSelectionMode = uiState.isInSelectionMode,
                        isSelected = memory.id in uiState.selectedCoreIds,
                        onLongPress = { viewModel.enterSelectionMode(memory.id) },
                        onToggleSelect = { viewModel.toggleSelection(memory.id) },
                        onDelete = { viewModel.requestDeleteCoreMemory(memory.id) },
                    )
                    HorizontalDivider()
                }
            }

            // ── Episodic Memories ──────────────────────────────────────────
            item {
                Spacer(Modifier.height(8.dp))
                EpisodicSectionHeader(count = uiState.episodicMemories.size)
            }
            item {
                Text(
                    text = "Short-term memories from past conversations. Pruned automatically after 30 days.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (uiState.episodicMemories.isNotEmpty()) {
                item {
                    val oldest = uiState.episodicMemories.lastOrNull()?.createdAt
                    val oldestLabel = if (oldest != null) formatEpisodicDate(oldest) else "—"
                    Text(
                        text = "${uiState.episodicMemories.size} memories · Oldest: $oldestLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                items(uiState.episodicMemories, key = { it.id }) { memory ->
                    val conversationTitle = uiState.conversationTitles[memory.conversationId]
                        ?: "Unknown conversation"
                    EpisodicMemoryItem(
                        memory = memory,
                        conversationTitle = conversationTitle,
                        onDelete = { viewModel.requestDeleteEpisodicMemory(memory.id) },
                    )
                    HorizontalDivider()
                }
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        OutlinedButton(onClick = viewModel::showClearEpisodicConfirmation) {
                            Text("Clear all episodic")
                        }
                    }
                }
            } else {
                item {
                    Text(
                        text = "No episodic memories yet. They'll appear here after you have a few conversations.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

            // ── Message History (RAG) ──────────────────────────────────────
            item {
                Spacer(Modifier.height(8.dp))
                SectionHeader("Message History (RAG)")
            }
            item {
                MessageEmbeddingStatsSection(
                    messageCount = uiState.embeddingStats.messageCount,
                    conversationCount = uiState.embeddingStats.conversationCount,
                )
            }
        }
    }

    // ── Delete Core Memory Confirmation Dialog ─────────────────────────────
    uiState.pendingDeleteId?.let { pendingId ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDeleteConfirmation,
            title = { Text("Delete memory?") },
            text = { Text("This memory will be permanently removed. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteCoreMemory(pendingId) }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDeleteConfirmation) { Text("Cancel") }
            },
        )
    }

    // ── Delete Episodic Memory Confirmation Dialog ─────────────────────────
    uiState.pendingDeleteEpisodicId?.let { pendingId ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDeleteEpisodicConfirmation,
            title = { Text("Delete memory?") },
            text = { Text("This episodic memory will be permanently removed. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteEpisodicMemory(pendingId) }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDeleteEpisodicConfirmation) { Text("Cancel") }
            },
        )
    }

    // ── Add Core Memory Dialog ─────────────────────────────────────────────
    if (uiState.isAddDialogOpen) {
        AlertDialog(
            onDismissRequest = viewModel::dismissAddDialog,
            title = { Text("Add core memory") },
            text = {
                OutlinedTextField(
                    value = uiState.addDialogText,
                    onValueChange = viewModel::onAddDialogTextChange,
                    label = { Text("Memory") },
                    placeholder = { Text("e.g. I prefer short answers") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    minLines = 2,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = viewModel::addCoreMemory,
                    enabled = uiState.addDialogText.isNotBlank() && !uiState.isSubmitting,
                ) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissAddDialog) { Text("Cancel") }
            },
        )
    }

    // ── Clear Episodic Confirmation Dialog ─────────────────────────────────
    if (uiState.showClearConfirmation) {
        AlertDialog(
            onDismissRequest = viewModel::dismissClearConfirmation,
            title = { Text("Clear episodic memories?") },
            text = { Text("This will delete all episodic memories. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = viewModel::clearEpisodicMemories) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissClearConfirmation) { Text("Cancel") }
            },
        )
    }

    // ── Bulk Delete Confirmation Dialog ────────────────────────────────────
    if (uiState.showBulkDeleteConfirmation) {
        val count = uiState.selectedCoreIds.size
        AlertDialog(
            onDismissRequest = viewModel::dismissBulkDeleteConfirmation,
            title = { Text("Delete $count ${if (count == 1) "memory" else "memories"}?") },
            text = { Text("These memories will be permanently removed. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = viewModel::deleteSelected) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissBulkDeleteConfirmation) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * Section header for Core Memories. In normal mode shows just the label.
 * In selection mode shows "Select All", "Delete Selected (N)", and "Cancel" actions.
 */
@Composable
private fun CoreMemoriesSectionHeader(
    isInSelectionMode: Boolean,
    selectedCount: Int,
    totalCount: Int,
    onSelectAll: () -> Unit,
    onDeleteSelected: () -> Unit,
    onCancelSelection: () -> Unit,
) {
    if (isInSelectionMode) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "$selectedCount / $totalCount selected",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            )
            TextButton(onClick = onSelectAll) {
                Text("Select All")
            }
            Button(
                onClick = onDeleteSelected,
                enabled = selectedCount > 0,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Text("Delete ($selectedCount)")
            }
            TextButton(onClick = onCancelSelection) {
                Text("Cancel")
            }
        }
    } else {
        SectionHeader("Core Memories")
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CoreMemoryItem(
    content: String,
    source: String,
    accessCount: Int,
    isInSelectionMode: Boolean,
    isSelected: Boolean,
    onLongPress: () -> Unit,
    onToggleSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(content) },
        supportingContent = {
            Text(
                text = "Source: $source · Accessed ${accessCount}×",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = if (isInSelectionMode) {
            {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelect() },
                )
            }
        } else {
            null
        },
        trailingContent = if (isInSelectionMode) {
            null
        } else {
            {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete memory")
                }
            }
        },
        modifier = Modifier.combinedClickable(
            onClick = { if (isInSelectionMode) onToggleSelect() },
            onLongClick = { if (!isInSelectionMode) onLongPress() },
        ),
    )
}

@Composable
private fun EpisodicSectionHeader(count: Int) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Episodic Memories",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (count > 0) {
            Badge(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                Text(
                    text = count.toString(),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun EpisodicMemoryItem(
    memory: EpisodicMemoryEntity,
    conversationTitle: String,
    onDelete: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(
                text = memory.content,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = conversationTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${formatEpisodicDate(memory.createdAt)} · accessed ${memory.accessCount}×",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = {
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete episodic memory")
            }
        },
    )
}

/** Informational read-only stats card for the RAG message index (#164). */
@Composable
private fun MessageEmbeddingStatsSection(
    messageCount: Int,
    conversationCount: Int,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = "Indexed messages: $messageCount",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "Conversations indexed: $conversationCount",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Message embeddings are created automatically for every message and are scoped to each conversation.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }
}

private val EPISODIC_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter
    .ofPattern("MMM d, yyyy", Locale.getDefault())
    .withZone(ZoneId.systemDefault())

private fun formatEpisodicDate(epochMs: Long): String =
    EPISODIC_DATE_FORMATTER.format(Instant.ofEpochMilli(epochMs))

@Preview(showBackground = true)
@Composable
private fun EpisodicMemoryItemPreview() {
    MaterialTheme {
        EpisodicMemoryItem(
            memory = EpisodicMemoryEntity(
                rowId = 1L,
                id = "preview-id",
                conversationId = "conv-1",
                content = "The user prefers concise answers and dislikes long explanations.",
                createdAt = System.currentTimeMillis(),
                accessCount = 3,
            ),
            conversationTitle = "Chat about Kotlin",
            onDelete = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun CoreMemoryItemSelectionPreview() {
    MaterialTheme {
        CoreMemoryItem(
            content = "I prefer concise, direct answers.",
            source = "user",
            accessCount = 5,
            isInSelectionMode = true,
            isSelected = true,
            onLongPress = {},
            onToggleSelect = {},
            onDelete = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun MessageEmbeddingStatsSectionPreview() {
    MaterialTheme {
        MessageEmbeddingStatsSection(messageCount = 142, conversationCount = 7)
    }
}

