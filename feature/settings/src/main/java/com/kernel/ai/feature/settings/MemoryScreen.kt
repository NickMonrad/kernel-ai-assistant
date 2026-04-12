package com.kernel.ai.feature.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kernel.ai.core.memory.entity.CoreMemoryEntity
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
            if (!uiState.isInSelectionMode && !uiState.isInEpisodicSelectionMode) {
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
            // ── Global search ──────────────────────────────────────────────
            item {
                OutlinedTextField(
                    value = uiState.globalSearch,
                    onValueChange = viewModel::updateGlobalSearch,
                    placeholder = { Text("Search all memories…") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (uiState.globalSearch.isNotBlank()) {
                            IconButton(onClick = { viewModel.updateGlobalSearch("") }) {
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
                if (uiState.isInSelectionMode) {
                    CoreMemoriesSectionHeader(
                        isInSelectionMode = true,
                        selectedCount = uiState.selectedCoreIds.size,
                        totalCount = uiState.coreMemories.size,
                        onSelectAll = { viewModel.selectAll(uiState.coreMemories.map { it.id }) },
                        onDeleteSelected = viewModel::requestBulkDelete,
                        onCancelSelection = viewModel::clearSelection,
                    )
                } else {
                    CollapsibleSectionHeader(
                        title = "Core Memories",
                        count = uiState.coreMemories.size,
                        isExpanded = MemorySection.CORE in uiState.expandedSections,
                        onToggle = { viewModel.toggleSection(MemorySection.CORE) },
                    )
                }
            }

            if (MemorySection.CORE in uiState.expandedSections || uiState.isInSelectionMode) {
                // Core search bar (only when expanded and not in selection mode)
                if (!uiState.isInSelectionMode) {
                    item {
                        OutlinedTextField(
                            value = uiState.coreSearch,
                            onValueChange = viewModel::updateCoreSearch,
                            placeholder = { Text("Search core memories…") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                if (uiState.coreSearch.isNotBlank()) {
                                    IconButton(onClick = { viewModel.updateCoreSearch("") }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                                    }
                                }
                            },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }

                if (uiState.coreMemories.isEmpty()) {
                    item {
                        Text(
                            text = if (uiState.coreSearch.isNotBlank() || uiState.globalSearch.isNotBlank())
                                "No core memories match your search."
                            else
                                "No core memories yet. Tap + to add one.",
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
                            onTap = { viewModel.openCoreMemoryDetail(memory) },
                            onDelete = { viewModel.requestDeleteCoreMemory(memory.id) },
                        )
                        HorizontalDivider()
                    }
                }
            }

            // ── Episodic Memories ──────────────────────────────────────────
            item {
                Spacer(Modifier.height(8.dp))
                EpisodicSectionHeader(
                    count = uiState.episodicMemories.size,
                    isInEpisodicSelectionMode = uiState.isInEpisodicSelectionMode,
                    selectedCount = uiState.selectedEpisodicIds.size,
                    isExpanded = MemorySection.EPISODIC in uiState.expandedSections,
                    onToggle = { viewModel.toggleSection(MemorySection.EPISODIC) },
                    onSelectAll = { viewModel.selectAllEpisodic(uiState.episodicMemories.map { it.id }) },
                    onDeleteSelected = viewModel::requestEpisodicBulkDelete,
                    onCancelSelection = viewModel::clearEpisodicSelection,
                )
            }

            if (MemorySection.EPISODIC in uiState.expandedSections || uiState.isInEpisodicSelectionMode) {
                item {
                    Text(
                        text = "Short-term memories from past conversations. Pruned automatically after 30 days.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }

                // Episodic search bar (only when not in selection mode)
                if (!uiState.isInEpisodicSelectionMode) {
                    item {
                        OutlinedTextField(
                            value = uiState.episodicSearch,
                            onValueChange = viewModel::updateEpisodicSearch,
                            placeholder = { Text("Search episodic memories…") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                if (uiState.episodicSearch.isNotBlank()) {
                                    IconButton(onClick = { viewModel.updateEpisodicSearch("") }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                                    }
                                }
                            },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
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
                            isInEpisodicSelectionMode = uiState.isInEpisodicSelectionMode,
                            isEpisodicSelected = memory.id in uiState.selectedEpisodicIds,
                            onLongPress = { viewModel.enterEpisodicSelectionMode(memory.id) },
                            onToggleSelect = { viewModel.toggleEpisodicSelection(memory.id) },
                            onTap = { viewModel.openEpisodicMemoryDetail(memory) },
                            onDelete = { viewModel.requestDeleteEpisodicMemory(memory.id) },
                        )
                        HorizontalDivider()
                    }
                    if (!uiState.isInEpisodicSelectionMode) {
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
                    }
                } else {
                    item {
                        Text(
                            text = if (uiState.episodicSearch.isNotBlank() || uiState.globalSearch.isNotBlank())
                                "No episodic memories match your search."
                            else
                                "No episodic memories yet. They'll appear here after you have a few conversations.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            // ── Message History (RAG) ──────────────────────────────────────
            item {
                Spacer(Modifier.height(8.dp))
                CollapsibleSectionHeader(
                    title = "Message History (RAG)",
                    isExpanded = MemorySection.EMBEDDING_STATS in uiState.expandedSections,
                    onToggle = { viewModel.toggleSection(MemorySection.EMBEDDING_STATS) },
                )
            }

            if (MemorySection.EMBEDDING_STATS in uiState.expandedSections) {
                item {
                    MessageEmbeddingStatsSection(
                        messageCount = uiState.embeddingStats.messageCount,
                        conversationCount = uiState.embeddingStats.conversationCount,
                    )
                }
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

    // ── Bulk Delete Core Confirmation Dialog ───────────────────────────────
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

    // ── Bulk Delete Episodic Confirmation Dialog ───────────────────────────
    if (uiState.showEpisodicBulkDeleteConfirmation) {
        val count = uiState.selectedEpisodicIds.size
        AlertDialog(
            onDismissRequest = viewModel::dismissEpisodicBulkDeleteConfirmation,
            title = { Text("Delete $count episodic ${if (count == 1) "memory" else "memories"}?") },
            text = { Text("These episodic memories will be permanently removed. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = viewModel::deleteSelectedEpisodic) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissEpisodicBulkDeleteConfirmation) { Text("Cancel") }
            },
        )
    }

    // ── Core Memory Detail Bottom Sheet ────────────────────────────────────
    uiState.selectedCoreMemoryDetail?.let { memory ->
        var editText by remember(memory.id) { mutableStateOf(memory.content) }
        ModalBottomSheet(
            onDismissRequest = viewModel::closeCoreMemoryDetail,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Edit Core Memory", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Content") },
                    minLines = 3,
                )
                Text(
                    text = "Source: ${memory.source} · Accessed ${memory.accessCount}×",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = viewModel::closeCoreMemoryDetail) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val trimmed = editText.trim()
                            if (trimmed.isNotBlank()) viewModel.saveCoreMemoryEdit(memory.id, trimmed)
                            else viewModel.closeCoreMemoryDetail()
                        },
                        enabled = editText.trim().isNotBlank(),
                    ) { Text("Save") }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    // ── Episodic Memory Detail Bottom Sheet ────────────────────────────────
    uiState.selectedEpisodicMemoryDetail?.let { memory ->
        val conversationTitle = uiState.conversationTitles[memory.conversationId] ?: "Unknown conversation"
        ModalBottomSheet(
            onDismissRequest = viewModel::closeEpisodicMemoryDetail,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Episodic Memory", style = MaterialTheme.typography.titleMedium)
                Text(memory.content, style = MaterialTheme.typography.bodyMedium)
                HorizontalDivider()
                Text(
                    text = "Conversation: $conversationTitle",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Created: ${formatEpisodicDate(memory.createdAt)} · Accessed ${memory.accessCount}×",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = viewModel::closeEpisodicMemoryDetail) { Text("Close") }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
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

@Composable
private fun CollapsibleSectionHeader(
    title: String,
    count: Int? = null,
    isExpanded: Boolean,
    onToggle: () -> Unit,
) {
    val rotation by animateFloatAsState(targetValue = if (isExpanded) 180f else 0f, label = "chevron")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (count != null && count > 0) {
                Badge(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(
                        text = count.toString(),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = if (isExpanded) "Collapse" else "Expand",
            modifier = Modifier.rotate(rotation),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
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
    onTap: () -> Unit,
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
            onClick = { if (isInSelectionMode) onToggleSelect() else onTap() },
            onLongClick = { if (!isInSelectionMode) onLongPress() },
        ),
    )
}

@Composable
private fun EpisodicSectionHeader(
    count: Int,
    isInEpisodicSelectionMode: Boolean,
    selectedCount: Int,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onSelectAll: () -> Unit,
    onDeleteSelected: () -> Unit,
    onCancelSelection: () -> Unit,
) {
    if (isInEpisodicSelectionMode) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "$selectedCount / $count selected",
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
        CollapsibleSectionHeader(
            title = "Episodic Memories",
            count = count,
            isExpanded = isExpanded,
            onToggle = onToggle,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodicMemoryItem(
    memory: EpisodicMemoryEntity,
    conversationTitle: String,
    isInEpisodicSelectionMode: Boolean,
    isEpisodicSelected: Boolean,
    onLongPress: () -> Unit,
    onToggleSelect: () -> Unit,
    onTap: () -> Unit,
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
        leadingContent = if (isInEpisodicSelectionMode) {
            {
                Checkbox(
                    checked = isEpisodicSelected,
                    onCheckedChange = { onToggleSelect() },
                )
            }
        } else {
            null
        },
        trailingContent = if (isInEpisodicSelectionMode) {
            null
        } else {
            {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete episodic memory")
                }
            }
        },
        modifier = Modifier.combinedClickable(
            onClick = { if (isInEpisodicSelectionMode) onToggleSelect() else onTap() },
            onLongClick = { if (!isInEpisodicSelectionMode) onLongPress() },
        ),
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
            isInEpisodicSelectionMode = false,
            isEpisodicSelected = false,
            onLongPress = {},
            onToggleSelect = {},
            onTap = {},
            onDelete = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun CoreMemoryItemSelectionPreview() {
    MaterialTheme {
        CoreMemoryItem(
            content = "I prefer concise answers",
            source = "user",
            accessCount = 5,
            isInSelectionMode = true,
            isSelected = true,
            onLongPress = {},
            onToggleSelect = {},
            onTap = {},
            onDelete = {},
        )
    }
}
