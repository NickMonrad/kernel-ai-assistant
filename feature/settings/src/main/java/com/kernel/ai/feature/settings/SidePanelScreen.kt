package com.kernel.ai.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kernel.ai.core.memory.entity.ScheduledAlarmEntity
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SidePanelScreen(
    onBack: () -> Unit = {},
    viewModel: SidePanelViewModel = hiltViewModel(),
) {
    val alarms by viewModel.alarms.collectAsStateWithLifecycle()
    val timers by viewModel.timers.collectAsStateWithLifecycle()
    val filterType by viewModel.filterType.collectAsStateWithLifecycle()
    val isInSelectionMode by viewModel.isInSelectionMode.collectAsStateWithLifecycle()
    val selectedIds by viewModel.selectedIds.collectAsStateWithLifecycle()
    val showBulkDeleteConfirmation by viewModel.showBulkDeleteConfirmation.collectAsStateWithLifecycle()
    var pendingDismiss by remember { mutableStateOf<ScheduledAlarmEntity?>(null) }
    var pendingCancel by remember { mutableStateOf<ScheduledAlarmEntity?>(null) }

    // Tick every second so countdown labels stay live
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            nowMs = System.currentTimeMillis()
        }
    }

    BackHandler(enabled = isInSelectionMode) {
        viewModel.clearSelection()
    }

    val visibleTimers = if (filterType != AlarmTimerFilter.ALARMS) timers else emptyList()
    val visibleAlarms = if (filterType != AlarmTimerFilter.TIMERS) alarms else emptyList()
    val allVisibleIds = (visibleTimers + visibleAlarms).map { it.id }

    Scaffold(
        topBar = {
            if (isInSelectionMode) {
                TopAppBar(
                    title = { Text("${selectedIds.size} / ${allVisibleIds.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = viewModel::clearSelection) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel selection")
                        }
                    },
                    actions = {
                        TextButton(onClick = { viewModel.selectAll(allVisibleIds) }) {
                            Text("Select All")
                        }
                        Button(
                            onClick = viewModel::requestBulkDelete,
                            enabled = selectedIds.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                        ) {
                            Text("Delete (${selectedIds.size})")
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text("Active Timers & Alarms") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // Filter chips
            LazyRow(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    FilterChip(
                        selected = filterType == AlarmTimerFilter.ALL,
                        onClick = { viewModel.setFilter(AlarmTimerFilter.ALL) },
                        label = { Text("All") },
                    )
                }
                item {
                    FilterChip(
                        selected = filterType == AlarmTimerFilter.TIMERS,
                        onClick = { viewModel.setFilter(AlarmTimerFilter.TIMERS) },
                        label = { Text("Timers") },
                        leadingIcon = {
                            Icon(Icons.Default.Timer, contentDescription = null)
                        },
                    )
                }
                item {
                    FilterChip(
                        selected = filterType == AlarmTimerFilter.ALARMS,
                        onClick = { viewModel.setFilter(AlarmTimerFilter.ALARMS) },
                        label = { Text("Alarms") },
                        leadingIcon = {
                            Icon(Icons.Default.Alarm, contentDescription = null)
                        },
                    )
                }
            }

            val hasContent = visibleTimers.isNotEmpty() || visibleAlarms.isNotEmpty()
            if (!hasContent) {
                Spacer(modifier = Modifier.height(32.dp))
                Text(
                    text = when (filterType) {
                        AlarmTimerFilter.ALL -> "No active timers or alarms."
                        AlarmTimerFilter.ALARMS -> "No active alarms."
                        AlarmTimerFilter.TIMERS -> "No active timers."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Ask Jandal to set a timer or alarm — they'll appear here while running.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (visibleTimers.isNotEmpty()) {
                        item { SectionHeader(title = "Timers") }
                        items(visibleTimers, key = { it.id }) { timer ->
                            TimerRow(
                                timer = timer,
                                nowMs = nowMs,
                                inSelectionMode = isInSelectionMode,
                                isSelected = timer.id in selectedIds,
                                onTap = {
                                    if (isInSelectionMode) {
                                        viewModel.toggleSelection(timer.id)
                                    } else {
                                        pendingCancel = timer
                                    }
                                },
                                onLongPress = {
                                    if (!isInSelectionMode) viewModel.enterSelectionMode(timer.id)
                                },
                                onCancel = { pendingCancel = timer },
                            )
                            HorizontalDivider()
                        }
                    }

                    if (visibleAlarms.isNotEmpty()) {
                        item {
                            if (visibleTimers.isNotEmpty()) Spacer(modifier = Modifier.height(8.dp))
                            SectionHeader(title = "Alarms")
                        }
                        items(visibleAlarms, key = { it.id }) { alarm ->
                            AlarmPanelRow(
                                alarm = alarm,
                                inSelectionMode = isInSelectionMode,
                                isSelected = alarm.id in selectedIds,
                                onTap = {
                                    if (isInSelectionMode) {
                                        viewModel.toggleSelection(alarm.id)
                                    } else {
                                        pendingDismiss = alarm
                                    }
                                },
                                onLongPress = {
                                    if (!isInSelectionMode) viewModel.enterSelectionMode(alarm.id)
                                },
                                onDismiss = { pendingDismiss = alarm },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    pendingCancel?.let { timer ->
        AlertDialog(
            onDismissRequest = { pendingCancel = null },
            icon = { Icon(Icons.Default.Timer, contentDescription = null) },
            title = { Text("Cancel timer?") },
            text = { Text("Cancel \"${timer.label ?: "Timer"}\"?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.cancelTimer(timer)
                    pendingCancel = null
                }) { Text("Cancel timer") }
            },
            dismissButton = {
                TextButton(onClick = { pendingCancel = null }) { Text("Keep") }
            },
        )
    }

    pendingDismiss?.let { alarm ->
        val formatted = remember(alarm.triggerAtMillis) {
            DateTimeFormatter.ofPattern("EEE d MMM 'at' h:mma")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(alarm.triggerAtMillis))
        }
        AlertDialog(
            onDismissRequest = { pendingDismiss = null },
            icon = { Icon(Icons.Default.Alarm, contentDescription = null) },
            title = { Text("Dismiss alarm?") },
            text = { Text("Dismiss \"${alarm.label ?: "Alarm"}\" scheduled for $formatted?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.dismissAlarm(alarm)
                    pendingDismiss = null
                }) { Text("Dismiss") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDismiss = null }) { Text("Keep") }
            },
        )
    }

    if (showBulkDeleteConfirmation) {
        val count = selectedIds.size
        AlertDialog(
            onDismissRequest = viewModel::dismissBulkDeleteConfirmation,
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text("Delete $count ${if (count == 1) "item" else "items"}?") },
            text = { Text("This will permanently cancel the selected timers and alarms.") },
            confirmButton = {
                TextButton(onClick = viewModel::deleteSelected) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
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
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimerRow(
    timer: ScheduledAlarmEntity,
    nowMs: Long,
    inSelectionMode: Boolean,
    isSelected: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onCancel: () -> Unit,
) {
    val startedAtMs = timer.startedAtMs
    val durationMs = timer.durationMs
    val remainingMs = if (startedAtMs != null && durationMs != null) {
        startedAtMs + durationMs - nowMs
    } else {
        -1L
    }
    val countdownText = if (remainingMs <= 0) {
        "Time's up!"
    } else {
        val totalSeconds = remainingMs / 1_000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%d:%02d".format(minutes, seconds)
    }

    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onTap, onLongClick = onLongPress),
        headlineContent = { Text(timer.label ?: "Timer") },
        supportingContent = {
            Text(
                text = countdownText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (remainingMs <= 0) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = {
            if (inSelectionMode) {
                Checkbox(checked = isSelected, onCheckedChange = { onTap() })
            } else {
                Icon(
                    Icons.Default.Timer,
                    contentDescription = null,
                    tint = if (remainingMs <= 0) MaterialTheme.colorScheme.error
                           else MaterialTheme.colorScheme.primary,
                )
            }
        },
        trailingContent = {
            if (!inSelectionMode) {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Delete, contentDescription = "Cancel timer")
                }
            }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlarmPanelRow(
    alarm: ScheduledAlarmEntity,
    inSelectionMode: Boolean,
    isSelected: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onDismiss: () -> Unit,
) {
    val formatted = remember(alarm.triggerAtMillis) {
        DateTimeFormatter.ofPattern("EEE d MMM 'at' h:mma")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(alarm.triggerAtMillis))
    }
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onTap, onLongClick = onLongPress),
        headlineContent = { Text(alarm.label ?: "Alarm") },
        supportingContent = { Text(formatted) },
        leadingContent = {
            if (inSelectionMode) {
                Checkbox(checked = isSelected, onCheckedChange = { onTap() })
            } else {
                Icon(
                    Icons.Default.Alarm,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
        trailingContent = {
            if (!inSelectionMode) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Delete, contentDescription = "Dismiss alarm")
                }
            }
        },
    )
}
