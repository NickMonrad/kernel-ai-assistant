package com.kernel.ai.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// ── Date / timestamp helpers ─────────────────────────────────────────────────────────────────────

private val dayMonthFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")

private fun epochToLocalDate(epochMs: Long): LocalDate =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()

private fun formatRelativeDate(epochMs: Long): String {
    val date = epochToLocalDate(epochMs)
    val today = LocalDate.now()
    return when {
        date == today -> "today"
        date == today.minusDays(1) -> "yesterday"
        else -> date.format(dayMonthFormatter)
    }
}

private fun formatTimestamp(item: ListItemEntity): String =
    if (item.updatedAt > item.createdAt) {
        "Updated ${formatRelativeDate(item.updatedAt)}"
    } else {
        "Added ${formatRelativeDate(item.createdAt)}"
    }

private fun formatDueDate(dueAt: Long): String {
    val date = epochToLocalDate(dueAt)
    val today = LocalDate.now()
    return when {
        date.isBefore(today) -> "Overdue"
        date == today -> "Due today"
        date == today.plusDays(1) -> "Due tomorrow"
        else -> "Due ${date.format(dayMonthFormatter)}"
    }
}

private fun isOverdue(dueAt: Long, checked: Boolean): Boolean =
    !checked && epochToLocalDate(dueAt).isBefore(LocalDate.now())

// ── Sort / filter label helpers ──────────────────────────────────────────────────────────────────

private fun ItemSort.label(): String = when (this) {
    ItemSort.CREATED_NEWEST -> "Created (newest first)"
    ItemSort.CREATED_OLDEST -> "Created (oldest first)"
    ItemSort.UPDATED_NEWEST -> "Updated (newest first)"
    ItemSort.NAME_ASC -> "Name A→Z"
    ItemSort.NAME_DESC -> "Name Z→A"
    ItemSort.DUE_SOONEST -> "Due date (soonest first)"
    ItemSort.FAVOURITES_FIRST -> "Favourites first"
}

private fun ItemFilter.label(): String = when (this) {
    ItemFilter.ALL -> "All items"
    ItemFilter.FAVOURITES_ONLY -> "Favourites only"
    ItemFilter.ACTIVE_ONLY -> "Active only"
    ItemFilter.COMPLETED_ONLY -> "Completed only"
}

// ── Screen ───────────────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListItemsScreen(
    listId: Long,
    onBack: () -> Unit = {},
    onNavigateToVoiceActions: () -> Unit = {},
    viewModel: ListsViewModel = hiltViewModel(),
) {
    val displayedItems by viewModel.observeDisplayedItems(listId).collectAsStateWithLifecycle()
    val listEntities by viewModel.listEntities.collectAsStateWithLifecycle()
    val searchQuery by viewModel.itemSearchQuery.collectAsStateWithLifecycle()

    val displayName = listEntities.firstOrNull { it.id == listId }?.name ?: ""

    // Apply text search on top of sorted/filtered results from the ViewModel
    val (sortedActive, sortedCompleted) = displayedItems
    val filteredActive = if (searchQuery.isBlank()) sortedActive
    else sortedActive.filter { it.text.contains(searchQuery, ignoreCase = true) }
    val filteredCompleted = if (searchQuery.isBlank()) sortedCompleted
    else sortedCompleted.filter { it.text.contains(searchQuery, ignoreCase = true) }

    val allItems = sortedActive + sortedCompleted  // for empty-state check

    var showAddDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    val renameInitialValue = remember(showRenameDialog) { if (showRenameDialog) displayName else "" }
    var completedExpanded by rememberSaveable { mutableStateOf(viewModel.itemFilter == ItemFilter.COMPLETED_ONLY) }
    var showSortMenu by remember { mutableStateOf(false) }
    var editingItem by remember { mutableStateOf<ListItemEntity?>(null) }

    LaunchedEffect(viewModel.itemFilter) {
        if (viewModel.itemFilter == ItemFilter.COMPLETED_ONLY) {
            completedExpanded = true
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.clearItemSearchQuery() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = displayName.replaceFirstChar { it.uppercase() },
                        modifier = Modifier.clickable { showRenameDialog = true },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (sortedCompleted.isNotEmpty()) {
                        TextButton(onClick = { viewModel.clearChecked(listId) }) {
                            Text("Clear done")
                        }
                    }
                    // Sort / filter menu
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Sort and filter")
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false },
                        ) {
                            // ── Sort section ──────────────────────────────────────────
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Sort by",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                onClick = {},
                                enabled = false,
                            )
                            ItemSort.entries.forEach { sort ->
                                DropdownMenuItem(
                                    text = { Text(sort.label()) },
                                    onClick = {
                                        viewModel.itemSort = sort
                                        showSortMenu = false
                                    },
                                    trailingIcon = if (viewModel.itemSort == sort) {
                                        { Icon(Icons.Default.Check, contentDescription = null) }
                                    } else null,
                                )
                            }
                            HorizontalDivider()
                            // ── Filter section ────────────────────────────────────────
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Filter",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                onClick = {},
                                enabled = false,
                            )
                            ItemFilter.entries.forEach { filter ->
                                DropdownMenuItem(
                                    text = { Text(filter.label()) },
                                    onClick = {
                                        viewModel.itemFilter = filter
                                        showSortMenu = false
                                    },
                                    trailingIcon = if (viewModel.itemFilter == filter) {
                                        { Icon(Icons.Default.Check, contentDescription = null) }
                                    } else null,
                                )
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SmallFloatingActionButton(
                    onClick = onNavigateToVoiceActions,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Icon(Icons.Default.Mic, contentDescription = "Voice input")
                }
                FloatingActionButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add item")
                }
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
                            onDelete = { viewModel.deleteItem(item) },
                            onEdit = { editingItem = item },
                            onToggleFavourite = { viewModel.toggleFavourite(item) },
                        )
                        HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    }

                    // Completed section header
                    if (sortedCompleted.isNotEmpty()) {
                        item(key = "completed_header") {
                            ListItem(
                                modifier = Modifier.fillMaxWidth(),
                                headlineContent = {
                                    Text(
                                        "Completed (${sortedCompleted.size})",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                trailingContent = {
                                    IconButton(onClick = { completedExpanded = !completedExpanded }) {
                                        Icon(
                                            if (completedExpanded) Icons.Default.ExpandLess
                                            else Icons.Default.ExpandMore,
                                            contentDescription = if (completedExpanded) "Collapse"
                                            else "Expand",
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
                                onDelete = { viewModel.deleteItem(item) },
                                onEdit = { editingItem = item },
                                onToggleFavourite = { viewModel.toggleFavourite(item) },
                            )
                            HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                        }
                    }

                    item { Spacer(modifier = Modifier.height(88.dp)) } // FAB clearance
                }
            }
        }
    }

    // ── Edit bottom sheet ────────────────────────────────────────────────────────────────────────
    editingItem?.let { item ->
        EditItemSheet(
            item = item,
            onSave = { updated ->
                viewModel.updateItem(updated)
                editingItem = null
            },
            onDismiss = { editingItem = null },
        )
    }

    // ── Rename dialog ────────────────────────────────────────────────────────────────────────────
    if (showRenameDialog) {
        NameInputDialog(
            title = "Rename list",
            confirmLabel = "Save",
            initialValue = renameInitialValue,
            onConfirm = { newName ->
                viewModel.renameList(listId, newName)
                showRenameDialog = false
            },
            onDismiss = { showRenameDialog = false },
        )
    }

    // ── Add item dialog ──────────────────────────────────────────────────────────────────────────
    if (showAddDialog) {
        AddItemDialog(
            onConfirm = { text ->
                showAddDialog = false
                viewModel.addItem(listId, text)
            },
            onDismiss = { showAddDialog = false },
        )
    }
}

// ── Item row ─────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun ListItemRow(
    item: ListItemEntity,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onToggleFavourite: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(
                text = item.text,
                modifier = Modifier.clickable(onClick = onEdit),
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
        supportingContent = {
            Column {
                val dueAtMs = item.dueAt
                if (dueAtMs != null) {
                    val overdue = isOverdue(dueAtMs, item.checked)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable(onClick = onEdit),
                    ) {
                        Icon(
                            Icons.Default.Event,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 2.dp),
                            tint = if (overdue) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = formatDueDate(dueAtMs),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (overdue) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                Text(
                    text = formatTimestamp(item),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        },
        leadingContent = {
            Checkbox(
                checked = item.checked,
                onCheckedChange = { onToggle() },
            )
        },
        trailingContent = {
            Row {
                IconButton(onClick = onToggleFavourite) {
                    Icon(
                        imageVector = if (item.isFavourite) Icons.Default.Star
                        else Icons.Default.StarBorder,
                        contentDescription = if (item.isFavourite) "Unfavourite" else "Favourite",
                        tint = if (item.isFavourite) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete item",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
    )
}

// ── Edit bottom sheet ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditItemSheet(
    item: ListItemEntity,
    onSave: (ListItemEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by remember(item.id) { mutableStateOf(item.text) }
    var dueAt by remember(item.id) { mutableStateOf(item.dueAt) }
    var isFavourite by remember(item.id) { mutableStateOf(item.isFavourite) }
    var showDatePicker by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        ) {
            Text("Edit item", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(16.dp))

            // Text field
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Item") },
                singleLine = true,
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Due date row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Event,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(8.dp))
                if (dueAt != null) {
                    Text(
                        text = formatDueDate(dueAt!!),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { showDatePicker = true },
                    )
                    IconButton(onClick = { dueAt = null }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear due date")
                    }
                } else {
                    TextButton(onClick = { showDatePicker = true }) {
                        Text("Set due date")
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Favourite switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (isFavourite) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = null,
                    tint = if (isFavourite) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Mark as favourite",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(
                    checked = isFavourite,
                    onCheckedChange = { isFavourite = it },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Save / Cancel
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        onSave(item.copy(text = text.trim(), dueAt = dueAt, isFavourite = isFavourite))
                    },
                    enabled = text.isNotBlank(),
                ) { Text("Save") }
            }

            // Navigation bar clearance
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // Date picker rendered as a separate dialog that floats over the bottom sheet
    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = dueAt?.let { localMs ->
                Instant.ofEpochMilli(localMs)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant()
                    .toEpochMilli()
            } ?: System.currentTimeMillis(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        dueAt = datePickerState.selectedDateMillis?.let { utcMs ->
                            Instant.ofEpochMilli(utcMs)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                                .atStartOfDay(ZoneId.systemDefault())
                                .toInstant()
                                .toEpochMilli()
                        }
                        showDatePicker = false
                    },
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

// ── Add item dialog ───────────────────────────────────────────────────────────────────────────────

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
