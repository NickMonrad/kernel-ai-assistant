package com.kernel.ai.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kernel.ai.core.memory.entity.ListItemEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListsScreen(
    onBack: () -> Unit = {},
    viewModel: ListsViewModel = hiltViewModel(),
) {
    val grouped by viewModel.groupedItems.collectAsStateWithLifecycle()

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
    ) { innerPadding ->
        if (grouped.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
            ) {
                Spacer(modifier = Modifier.height(32.dp))
                Text(
                    text = "No lists yet.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Ask Jandal to add something to a list — e.g. \"Add milk to my shopping list\".",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                grouped.forEach { (listName, items) ->
                    item(key = "header_$listName") {
                        ListSectionHeader(
                            listName = listName,
                            hasChecked = items.any { it.checked },
                            onClearChecked = { viewModel.clearChecked(listName) },
                            onDeleteList = { viewModel.deleteList(listName) },
                        )
                    }
                    items(items, key = { it.id }) { listItem ->
                        ListItemRow(
                            item = listItem,
                            onToggle = { viewModel.toggleChecked(listItem) },
                        )
                        HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    }
                    item(key = "spacer_$listName") {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ListSectionHeader(
    listName: String,
    hasChecked: Boolean,
    onClearChecked: () -> Unit,
    onDeleteList: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(
                text = listName.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        },
        leadingContent = {
            Icon(
                Icons.Default.Checklist,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        trailingContent = {
            if (hasChecked) {
                TextButton(onClick = onClearChecked) { Text("Clear done") }
            } else {
                IconButton(onClick = onDeleteList) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete list",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
    )
    HorizontalDivider()
}

@Composable
private fun ListItemRow(
    item: ListItemEntity,
    onToggle: () -> Unit,
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
    )
}
