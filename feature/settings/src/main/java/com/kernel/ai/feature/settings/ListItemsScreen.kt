package com.kernel.ai.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kernel.ai.core.memory.entity.ListItemEntity
import androidx.compose.material3.AlertDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListItemsScreen(
    listName: String,
    onBack: () -> Unit = {},
    viewModel: ListsViewModel = hiltViewModel(),
) {
    val grouped by viewModel.groupedItems.collectAsStateWithLifecycle()
    val searchQuery by viewModel.itemSearchQuery.collectAsStateWithLifecycle()
    val allItems = grouped[listName] ?: emptyList()

    // Split active vs completed
    val activeItems = allItems.filter { !it.checked }
    val completedItems = allItems.filter { it.checked }

    // Apply search filter
    val filteredActive = if (searchQuery.isBlank()) activeItems
    else activeItems.filter { it.item.contains(searchQuery, ignoreCase = true) }
    val filteredCompleted = if (searchQuery.isBlank()) completedItems
    else completedItems.filter { it.item.contains(searchQuery, ignoreCase = true) }

    var showAddDialog by remember { mutableStateOf(false) }
    var completedExpanded by rememberSaveable { mutableStateOf(false) }

    // Clear item search when leaving the screen
    DisposableEffect(Unit) {
        onDispose { viewModel.clearItemSearchQuery() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(listName.replaceFirstChar { it.uppercase() }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (completedItems.isNotEmpty()) {
                        TextButton(onClick = { viewModel.clearChecked(listName) }) {
                            Text("Clear done")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add item")
            }
        },
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
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    // Active items
                    items(filteredActive, key = { it.id }) { item ->
                        ListItemRow(
                            item = item,
                            onToggle = { viewModel.toggleChecked(item) },
                            onDelete = { viewModel.deleteItem(item.id) },
                        )
                        HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    }

                    // Completed section header (only if there are completed items)
                    if (completedItems.isNotEmpty()) {
                        item(key = "completed_header") {
                            ListItem(
                                modifier = Modifier.fillMaxWidth(),
                                headlineContent = {
                                    Text(
                                        "Completed (${completedItems.size})",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                trailingContent = {
                                    IconButton(onClick = { completedExpanded = !completedExpanded }) {
                                        Icon(
                                            if (completedExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                            contentDescription = if (completedExpanded) "Collapse" else "Expand",
                                        )
                                    }
                                },
                            )
                            HorizontalDivider()
                        }
                    }

                    // Completed items (collapsible)
                    if (completedExpanded) {
                        items(filteredCompleted, key = { "done_${it.id}" }) { item ->
                            ListItemRow(
                                item = item,
                                onToggle = { viewModel.toggleChecked(item) },
                                onDelete = { viewModel.deleteItem(item.id) },
                            )
                            HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                        }
                    }

                    item { Spacer(modifier = Modifier.height(88.dp)) } // FAB clearance
                }
            }
        }
    }

    if (showAddDialog) {
        AddItemDialog(
            onConfirm = { text ->
                showAddDialog = false
                viewModel.addItem(listName, text)
            },
            onDismiss = { showAddDialog = false },
        )
    }
}

@Composable
private fun ListItemRow(
    item: ListItemEntity,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(
                text = item.item,
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
        leadingContent = {
            Checkbox(
                checked = item.checked,
                onCheckedChange = { onToggle() },
            )
        },
        trailingContent = {
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete item",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        },
    )
}

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
