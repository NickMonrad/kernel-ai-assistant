package com.kernel.ai.feature.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Event
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportantDatesScreen(
    onBack: () -> Unit,
    viewModel: ImportantDatesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var editingDate by remember { mutableStateOf<ImportantDateListItem?>(null) }
    var pendingDelete by remember { mutableStateOf<ImportantDateListItem?>(null) }
    var createRequested by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Important dates") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { createRequested = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add important date")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 88.dp),
        ) {
            if (!uiState.calendarPermissionGranted) {
                item {
                    ListItem(
                        headlineContent = { Text("Calendar birthdays are off") },
                        supportingContent = {
                            Text("Enable Calendar birthdays in Settings to show synced birthdays here. Taught dates still work without it.")
                        },
                        leadingContent = { Icon(Icons.Default.Event, contentDescription = null) },
                    )
                    HorizontalDivider()
                }
            }

            if (uiState.isRefreshingCalendarBirthdays) {
                item {
                    ListItem(
                        headlineContent = { Text("Refreshing synced birthdays…") },
                        supportingContent = { Text("Pulling the latest read-only birthdays from Android Calendar.") },
                    )
                    HorizontalDivider()
                }
            }

            if (!uiState.hasAnyDates) {
                item {
                    EmptyImportantDatesState(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    )
                }
            } else {
                if (uiState.upcomingDates.isNotEmpty()) {
                    item {
                        ImportantDatesSectionHeader("Upcoming (next 90 days)")
                    }
                    items(uiState.upcomingDates, key = { it.stableKey }) { item ->
                        ImportantDateRow(
                            item = item,
                            onEdit = { editingDate = item },
                            onDelete = { pendingDelete = item },
                        )
                        HorizontalDivider()
                    }
                }

                if (uiState.laterDates.isNotEmpty()) {
                    item {
                        ImportantDatesSectionHeader(
                            if (uiState.upcomingDates.isEmpty()) "All dates" else "Later",
                        )
                    }
                    items(uiState.laterDates, key = { it.stableKey }) { item ->
                        ImportantDateRow(
                            item = item,
                            onEdit = { editingDate = item },
                            onDelete = { pendingDelete = item },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (createRequested) {
        ImportantDateEditorSheet(
            existingItem = null,
            onDismiss = { createRequested = false },
            onSave = { label, month, day, year ->
                coroutineScope.launch {
                    val saved = viewModel.saveTaughtDate(
                        existingLabel = null,
                        label = label,
                        month = month,
                        day = day,
                        year = year,
                    )
                    if (saved) createRequested = false
                }
            },
        )
    }

    editingDate?.let { item ->
        ImportantDateEditorSheet(
            existingItem = item,
            onDismiss = { editingDate = null },
            onSave = { label, month, day, year ->
                coroutineScope.launch {
                    val saved = viewModel.saveTaughtDate(
                        existingLabel = item.label,
                        label = label,
                        month = month,
                        day = day,
                        year = year,
                    )
                    if (saved) editingDate = null
                }
            },
        )
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text("Delete ${item.label}?") },
            text = { Text("This only removes the taught date. Synced calendar birthdays stay read-only.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        coroutineScope.launch {
                            viewModel.deleteTaughtDate(item.label)
                            pendingDelete = null
                        }
                    },
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun ImportantDatesSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun EmptyImportantDatesState(modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "No important dates yet.",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Tap + to add one here, or teach Jandal by saying something like 'remember mum's birthday is 15 March'.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ImportantDateRow(
    item: ImportantDateListItem,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val dateSummary = formatStoredDate(item.month, item.day, item.year)
    val nextSummary = formatNextOccurrence(item.nextOccurrence)

    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !item.isReadOnly, onClick = onEdit),
        headlineContent = { Text(item.label) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(dateSummary)
                Text(
                    if (item.isReadOnly) {
                        "$nextSummary · Synced from Calendar"
                    } else {
                        "$nextSummary · Taught by you"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        leadingContent = {
            Icon(Icons.Default.Event, contentDescription = null)
        },
        trailingContent = {
            if (item.isReadOnly) {
                Text(
                    text = "Read-only",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit ${item.label}")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete ${item.label}")
                    }
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportantDateEditorSheet(
    existingItem: ImportantDateListItem?,
    onDismiss: () -> Unit,
    onSave: (label: String, month: Int, day: Int, year: Int?) -> Unit,
) {
    val today = LocalDate.now()
    var label by remember(existingItem) { mutableStateOf(existingItem?.label ?: "") }
    var selectedDate by remember(existingItem) {
        mutableStateOf(
            LocalDate.of(
                existingItem?.year ?: today.year,
                existingItem?.month ?: today.monthValue,
                existingItem?.day ?: today.dayOfMonth,
            ),
        )
    }
    var yearText by remember(existingItem) { mutableStateOf(existingItem?.year?.toString().orEmpty()) }
    var localError by remember(existingItem) { mutableStateOf<String?>(null) }
    var showDatePicker by remember(existingItem) { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (existingItem == null) "Add important date" else "Edit important date",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = "Month and day are required. Leave year blank to repeat every year.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = label,
                onValueChange = {
                    label = it
                    localError = null
                },
                label = { Text("Label") },
                placeholder = { Text("e.g. Mum's birthday") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = { showDatePicker = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Date: ${formatStoredDate(selectedDate.monthValue, selectedDate.dayOfMonth, null)}")
            }
            OutlinedTextField(
                value = yearText,
                onValueChange = {
                    yearText = it.filter(Char::isDigit).take(4)
                    localError = null
                },
                label = { Text("Year (optional)") },
                placeholder = { Text("Leave blank for recurring dates") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            localError?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = {
                        val parsedYear = when {
                            yearText.isBlank() -> null
                            yearText.length != 4 -> {
                                localError = "Year must be four digits or left blank."
                                return@Button
                            }
                            else -> yearText.toIntOrNull()?.also {
                                if (it < 1) {
                                    localError = "Year must be a valid positive number."
                                    return@Button
                                }
                            } ?: run {
                                localError = "Year must be a valid number."
                                return@Button
                            }
                        }
                        onSave(label, selectedDate.monthValue, selectedDate.dayOfMonth, parsedYear)
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (existingItem == null) "Add" else "Save")
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = selectedDate.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val selectedMillis = datePickerState.selectedDateMillis ?: return@TextButton
                        selectedDate = Instant.ofEpochMilli(selectedMillis)
                            .atZone(ZoneId.of("UTC"))
                            .toLocalDate()
                        showDatePicker = false
                    },
                ) {
                    Text("Use date")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel")
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

private fun formatStoredDate(month: Int, day: Int, year: Int?): String {
    val date = LocalDate.of(year ?: 2000, month, day)
    val pattern = if (year == null) "d MMMM" else "d MMMM yyyy"
    return date.format(DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH))
}

private fun formatNextOccurrence(date: LocalDate): String =
    "Next: ${date.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH))}"
