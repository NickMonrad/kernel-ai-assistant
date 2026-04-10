package com.kernel.ai.feature.settings

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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

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
            FloatingActionButton(
                onClick = { if (!uiState.isSubmitting) viewModel.openAddDialog() },
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add core memory")
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
                SectionHeader("Core Memories")
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
                    ListItem(
                        headlineContent = { Text(memory.content) },
                        supportingContent = {
                            Text(
                                text = "Source: ${memory.source} · Accessed ${memory.accessCount}×",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingContent = {
                            IconButton(onClick = { viewModel.deleteCoreMemory(memory.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete memory")
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }

            // ── Episodic Memories ──────────────────────────────────────────
            item {
                Spacer(Modifier.height(8.dp))
                SectionHeader("Episodic Memories")
            }
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = "${uiState.episodicCount} stored memories",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        OutlinedButton(
                            onClick = viewModel::showClearEpisodicConfirmation,
                            enabled = uiState.episodicCount > 0,
                        ) {
                            Text("Clear all episodic")
                        }
                    }
                }
            }
        }
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
