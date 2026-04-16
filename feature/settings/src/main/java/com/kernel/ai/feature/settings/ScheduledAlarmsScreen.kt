package com.kernel.ai.feature.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kernel.ai.core.memory.entity.ScheduledAlarmEntity
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledAlarmsScreen(
    onBack: () -> Unit = {},
    viewModel: ScheduledAlarmsViewModel = hiltViewModel(),
) {
    val alarms by viewModel.alarms.collectAsStateWithLifecycle()
    var pendingCancel by remember { mutableStateOf<ScheduledAlarmEntity?>(null) }
    var editingAlarm by remember { mutableStateOf<ScheduledAlarmEntity?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scheduled Alarms") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateDialog = true },
                modifier = Modifier.testTag("alarm_fab"),
            ) {
                Icon(Icons.Default.Add, contentDescription = "Create alarm")
            }
        },
    ) { innerPadding ->
        if (alarms.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
            ) {
                Spacer(modifier = Modifier.height(32.dp))
                Text(
                    text = "No upcoming alarms.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Alarms scheduled via Jandal for a specific date appear here. You can also create alarms here using the + button.",
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
                items(alarms, key = { it.id }) { alarm ->
                    AlarmRow(
                        alarm = alarm,
                        onTap = { editingAlarm = alarm },
                        onCancel = { pendingCancel = alarm },
                        onToggle = { viewModel.toggleEnabled(alarm) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (showCreateDialog) {
        AlarmCreateEditDialog(
            existingAlarm = null,
            onConfirm = { triggerAtMillis, label ->
                viewModel.scheduleAlarm(triggerAtMillis, label)
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false },
        )
    }

    editingAlarm?.let { alarm ->
        AlarmCreateEditDialog(
            existingAlarm = alarm,
            onConfirm = { triggerAtMillis, label ->
                viewModel.editAlarm(alarm, triggerAtMillis, label)
                editingAlarm = null
            },
            onDismiss = { editingAlarm = null },
        )
    }

    pendingCancel?.let { alarm ->
        AlertDialog(
            onDismissRequest = { pendingCancel = null },
            icon = { Icon(Icons.Default.Alarm, contentDescription = null) },
            title = { Text("Cancel alarm?") },
            text = {
                val label = alarm.label ?: "Alarm"
                val formatted = Instant.ofEpochMilli(alarm.triggerAtMillis)
                    .let { DateTimeFormatter.ofPattern("EEE d MMM 'at' h:mma").withZone(ZoneId.systemDefault()).format(it) }
                Text("Cancel \"$label\" scheduled for $formatted?")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.cancelAlarm(alarm)
                    pendingCancel = null
                }) { Text("Cancel alarm") }
            },
            dismissButton = {
                TextButton(onClick = { pendingCancel = null }) { Text("Keep") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmCreateEditDialog(
    existingAlarm: ScheduledAlarmEntity?,
    onConfirm: (triggerAtMillis: Long, label: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val isEdit = existingAlarm != null

    val defaultTrigger = remember {
        if (existingAlarm != null) {
            Instant.ofEpochMilli(existingAlarm.triggerAtMillis)
        } else {
            LocalDate.now().plusDays(1).atTime(8, 0)
                .atZone(ZoneId.systemDefault()).toInstant()
        }
    }
    val defaultZdt = defaultTrigger.atZone(ZoneId.systemDefault())

    var label by remember { mutableStateOf(existingAlarm?.label ?: "") }

    // Step: "date" → "time" → "confirm"
    var step by remember { mutableStateOf("date") }

    val dateState = rememberDatePickerState(
        initialSelectedDateMillis = defaultZdt.toLocalDate()
            .atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli(),
    )
    val timeState = rememberTimePickerState(
        initialHour = defaultZdt.hour,
        initialMinute = defaultZdt.minute,
        is24Hour = false,
    )

    when (step) {
        "date" -> {
            DatePickerDialog(
                onDismissRequest = onDismiss,
                confirmButton = {
                    TextButton(
                        onClick = { step = "time" },
                        enabled = dateState.selectedDateMillis != null,
                    ) { Text("Next") }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                },
            ) {
                DatePicker(state = dateState)
            }
        }
        "time" -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(if (isEdit) "Edit alarm time" else "Set time") },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        TimePicker(state = timeState)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { step = "label" }) { Text("Next") }
                },
                dismissButton = {
                    TextButton(onClick = { step = "date" }) { Text("Back") }
                },
            )
        }
        "label" -> {
            val selectedDateUtcMillis = dateState.selectedDateMillis ?: return
            val triggerAtMillis = remember(selectedDateUtcMillis, timeState.hour, timeState.minute) {
                // selectedDateMillis is midnight UTC — convert to local date then apply local time
                val localDate = Instant.ofEpochMilli(selectedDateUtcMillis)
                    .atZone(ZoneId.of("UTC")).toLocalDate()
                localDate.atTime(LocalTime.of(timeState.hour, timeState.minute))
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }
            val formatted = remember(triggerAtMillis) {
                DateTimeFormatter.ofPattern("EEE d MMM 'at' h:mma")
                    .withZone(ZoneId.systemDefault())
                    .format(Instant.ofEpochMilli(triggerAtMillis))
            }
            AlertDialog(
                onDismissRequest = onDismiss,
                modifier = Modifier.testTag("alarm_dialog"),
                icon = { Icon(Icons.Default.Alarm, contentDescription = null) },
                title = { Text(if (isEdit) "Edit alarm" else "New alarm") },
                text = {
                    Column {
                        Text(
                            text = formatted,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = label,
                            onValueChange = { label = it },
                            modifier = Modifier.fillMaxWidth().testTag("alarm_label_input"),
                            placeholder = { Text("Label (optional)") },
                            singleLine = true,
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { onConfirm(triggerAtMillis, label.takeIf { it.isNotBlank() }) },
                        modifier = Modifier.testTag("alarm_save_button"),
                    ) {
                        Text(if (isEdit) "Save" else "Set alarm")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { step = "time" }) { Text("Back") }
                },
            )
        }
    }
}

@Composable
private fun AlarmRow(
    alarm: ScheduledAlarmEntity,
    onTap: () -> Unit,
    onCancel: () -> Unit,
    onToggle: () -> Unit,
) {
    val formatted = remember(alarm.triggerAtMillis) {
        Instant.ofEpochMilli(alarm.triggerAtMillis)
            .let { DateTimeFormatter.ofPattern("EEE d MMM 'at' h:mma").withZone(ZoneId.systemDefault()).format(it) }
    }
    val dimAlpha = if (alarm.enabled) 1f else 0.4f
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap),
        headlineContent = {
            Text(
                text = alarm.label ?: "Alarm",
                textDecoration = if (alarm.enabled) null else TextDecoration.LineThrough,
                modifier = Modifier.alpha(dimAlpha),
            )
        },
        supportingContent = {
            Text(
                text = formatted,
                modifier = Modifier.alpha(dimAlpha),
            )
        },
        leadingContent = {
            Icon(
                Icons.Default.Alarm,
                contentDescription = null,
                tint = if (alarm.enabled) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = alarm.enabled,
                    onCheckedChange = { onToggle() },
                )
                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Delete, contentDescription = "Cancel alarm")
                }
            }
        },
    )
}

