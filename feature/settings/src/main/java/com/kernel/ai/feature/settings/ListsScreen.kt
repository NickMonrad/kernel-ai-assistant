package com.kernel.ai.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListsScreen(
    onBack: () -> Unit = {},
    onOpenList: (String) -> Unit = {},
    viewModel: ListsViewModel = hiltViewModel(),
) {
    val listNames by viewModel.listNames.collectAsStateWithLifecycle()
    val itemCounts by viewModel.itemCounts.collectAsStateWithLifecycle()
    val searchQuery by viewModel.listSearchQuery.collectAsStateWithLifecycle()

    var showCreateDialog by remember { mutableStateOf(false) }
    var pendingDeleteList by remember { mutableStateOf<String?>(null) }

    val filtered = if (searchQuery.isBlank()) listNames
    else listNames.filter { it.contains(searchQuery, ignoreCase = true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lists") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Create list")
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

            if (filtered.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(modifier = Modifier.height(32.dp))
                    Text(
                        text = if (listNames.isEmpty()) "No lists yet." else "No lists match \"$searchQuery\".",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (listNames.isEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Tap + to create a list, or ask Jandal — e.g. \"Add milk to my shopping list\".",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filtered, key = { it }) { listName ->
                        val count = itemCounts[listName] ?: 0
                        ListItem(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenList(listName) },
                            headlineContent = {
                                Text(listName.replaceFirstChar { it.uppercase() })
                            },
                            supportingContent = {
                                Text("$count item${if (count == 1) "" else "s"}")
                            },
                            leadingContent = {
                                Icon(
                                    Icons.Default.Checklist,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            trailingContent = {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    IconButton(onClick = { pendingDeleteList = listName }) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Delete list",
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                    Icon(Icons.Default.ChevronRight, contentDescription = null)
                                }
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    // Create list dialog
    if (showCreateDialog) {
        CreateListDialog(
            onConfirm = { name ->
                showCreateDialog = false
                if (name.isNotBlank()) onOpenList(name.trim())
            },
            onDismiss = { showCreateDialog = false },
        )
    }

    // Delete confirmation dialog
    pendingDeleteList?.let { listName ->
        val count = itemCounts[listName] ?: 0
        AlertDialog(
            onDismissRequest = { pendingDeleteList = null },
            title = { Text("Delete \"${listName.replaceFirstChar { it.uppercase() }}\"?") },
            text = {
                if (count > 0) Text("This will permanently delete $count item${if (count == 1) "" else "s"}.")
                else Text("The list is empty and will be deleted.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteList(listName)
                        pendingDeleteList = null
                    },
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteList = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun CreateListDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New list") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                placeholder = { Text("List name") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank(),
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
