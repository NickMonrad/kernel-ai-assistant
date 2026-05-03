package com.kernel.ai.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kernel.ai.core.memory.clock.ClockAlarm
import com.kernel.ai.core.memory.clock.ClockTimer
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
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
    val recentCompletedTimers by viewModel.recentCompletedTimers.collectAsStateWithLifecycle()
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val isInSelectionMode by viewModel.isInSelectionMode.collectAsStateWithLifecycle()
    val selectedIds by viewModel.selectedIds.collectAsStateWithLifecycle()
    val showBulkDeleteConfirmation by viewModel.showBulkDeleteConfirmation.collectAsStateWithLifecycle()
    var pendingDismiss by remember { mutableStateOf<ClockAlarm?>(null) }
    var pendingCancel by remember { mutableStateOf<ClockTimer?>(null) }
    var pendingDeleteCompleted by remember { mutableStateOf<ClockTimer?>(null) }
    var pendingClearCompleted by remember { mutableStateOf(false) }
    var editingAlarm by remember { mutableStateOf<ClockAlarm?>(null) }
    var showCreateAlarmDialog by remember { mutableStateOf(false) }
    var showCreateTimerDialog by remember { mutableStateOf(false) }
    var schedulingError by remember { mutableStateOf<String?>(null) }

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

    val visibleSelectionIds = when (selectedTab) {
        ClockSurfaceTab.TIMERS -> timers.map { it.id }
        ClockSurfaceTab.ALARMS -> alarms.map { it.id }
    }

    fun onTimerScheduled(success: Boolean, closeDialog: Boolean = false) {
        if (success) {
            schedulingError = null
            if (closeDialog) showCreateTimerDialog = false
        } else {
            schedulingError = "Couldn't schedule the timer."
        }
    }

    Scaffold(
        topBar = {
            if (isInSelectionMode) {
                TopAppBar(
                    title = {
                        val n = selectedIds.size
                        Text("$n / ${visibleSelectionIds.size} ${if (n == 1) "item" else "items"} selected")
                    },
                    navigationIcon = {
                        IconButton(onClick = viewModel::clearSelection) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel selection")
                        }
                    },
                    actions = {
                        TextButton(onClick = { viewModel.selectAll(visibleSelectionIds) }) {
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
                    title = { Text("Timers & Alarms") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!isInSelectionMode && selectedTab == ClockSurfaceTab.ALARMS) {
                ExtendedFloatingActionButton(
                    text = { Text("New Alarm") },
                    icon = { Icon(Icons.Default.Alarm, contentDescription = null) },
                    onClick = { showCreateAlarmDialog = true },
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = selectedTab == ClockSurfaceTab.TIMERS,
                    onClick = { viewModel.setTab(ClockSurfaceTab.TIMERS) },
                    label = { Text("Timers") },
                    leadingIcon = { Icon(Icons.Default.Timer, contentDescription = null) },
                )
                FilterChip(
                    selected = selectedTab == ClockSurfaceTab.ALARMS,
                    onClick = { viewModel.setTab(ClockSurfaceTab.ALARMS) },
                    label = { Text("Alarms") },
                    leadingIcon = { Icon(Icons.Default.Alarm, contentDescription = null) },
                )
            }

            when (selectedTab) {
                ClockSurfaceTab.TIMERS -> TimerDashboard(
                    timers = timers,
                    recentCompletedTimers = recentCompletedTimers,
                    nowMs = nowMs,
                    inSelectionMode = isInSelectionMode,
                    selectedIds = selectedIds,
                    onCreateCustomTimer = { showCreateTimerDialog = true },
                    onPresetTimer = { durationMs ->
                        viewModel.scheduleTimer(durationMs, null) { success ->
                            onTimerScheduled(success)
                        }
                    },
                    onTimerTap = { timer ->
                        if (isInSelectionMode) viewModel.toggleSelection(timer.id) else pendingCancel = timer
                    },
                    onTimerLongPress = { timer ->
                        if (!isInSelectionMode) viewModel.enterSelectionMode(timer.id)
                    },
                    onCancelTimer = { timer -> pendingCancel = timer },
                    onRestartTimer = { timer ->
                        viewModel.restartTimer(timer) { success ->
                            onTimerScheduled(success)
                        }
                    },
                    onDeleteCompletedTimer = { timer -> pendingDeleteCompleted = timer },
                    onClearCompletedTimers = { pendingClearCompleted = true },
                )

                ClockSurfaceTab.ALARMS -> AlarmDashboard(
                    alarms = alarms,
                    inSelectionMode = isInSelectionMode,
                    selectedIds = selectedIds,
                    onNewAlarm = { showCreateAlarmDialog = true },
                    onAlarmTap = { alarm ->
                        if (isInSelectionMode) viewModel.toggleSelection(alarm.id) else editingAlarm = alarm
                    },
                    onAlarmLongPress = { alarm ->
                        if (!isInSelectionMode) viewModel.enterSelectionMode(alarm.id)
                    },
                    onDismissAlarm = { alarm -> pendingDismiss = alarm },
                    onToggleAlarm = { alarm ->
                        viewModel.toggleEnabled(alarm) { success ->
                            if (!success) schedulingError = "Couldn't update the alarm."
                        }
                    },
                )
            }
        }
    }

    pendingCancel?.let { timer ->
        AlertDialog(
            onDismissRequest = { pendingCancel = null },
            icon = { Icon(Icons.Default.Timer, contentDescription = null) },
            title = { Text("Cancel timer?") },
            text = { Text("Cancel \"${timer.label ?: defaultTimerTitle(timer)}\"?") },
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

    pendingDeleteCompleted?.let { timer ->
        AlertDialog(
            onDismissRequest = { pendingDeleteCompleted = null },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text("Remove timer from history?") },
            text = { Text("Remove \"${timer.label ?: defaultTimerTitle(timer)}\" from Recent & Completed?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteCompletedTimer(timer)
                    pendingDeleteCompleted = null
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteCompleted = null }) { Text("Keep") }
            },
        )
    }

    if (pendingClearCompleted) {
        AlertDialog(
            onDismissRequest = { pendingClearCompleted = false },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text("Clear completed timers?") },
            text = { Text("Remove all completed timers from Recent & Completed?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearCompletedTimers()
                    pendingClearCompleted = false
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { pendingClearCompleted = false }) { Text("Cancel") }
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
            text = { Text("This will permanently cancel the selected timers or alarms.") },
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

    if (showCreateAlarmDialog) {
        AlarmCreateEditDialog(
            existingAlarm = null,
            onConfirm = { triggerAtMillis, label ->
                viewModel.scheduleAlarm(triggerAtMillis, label) { result ->
                    when (result) {
                        AlarmSaveResult.STORED -> {
                            schedulingError = null
                            showCreateAlarmDialog = false
                        }
                        AlarmSaveResult.FAILED -> schedulingError = "Couldn't save the alarm."
                    }
                }
            },
            onDismiss = { showCreateAlarmDialog = false },
        )
    }

    editingAlarm?.let { alarm ->
        AlarmCreateEditDialog(
            existingAlarm = alarm,
            onConfirm = { triggerAtMillis, label ->
                viewModel.editAlarm(alarm, triggerAtMillis, label) { result ->
                    when (result) {
                        AlarmSaveResult.STORED -> {
                            schedulingError = null
                            editingAlarm = null
                        }
                        AlarmSaveResult.FAILED -> schedulingError = "Couldn't save the alarm."
                    }
                }
            },
            onDismiss = { editingAlarm = null },
        )
    }

    if (showCreateTimerDialog) {
        TimerCreateDialog(
            onConfirm = { durationMs, label ->
                viewModel.scheduleTimer(durationMs, label) { success ->
                    onTimerScheduled(success, closeDialog = true)
                }
            },
            onDismiss = { showCreateTimerDialog = false },
        )
    }

    schedulingError?.let { message ->
        AlertDialog(
            onDismissRequest = { schedulingError = null },
            icon = { Icon(Icons.Default.Alarm, contentDescription = null) },
            title = { Text("Couldn't save timer or alarm") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { schedulingError = null }) { Text("OK") }
            },
        )
    }
}

private data class TimerPreset(
    val label: String,
    val durationMs: Long,
)

private val TIMER_PRESETS = listOf(
    TimerPreset("1 min", 60_000L),
    TimerPreset("5 min", 5 * 60_000L),
    TimerPreset("10 min", 10 * 60_000L),
    TimerPreset("15 min", 15 * 60_000L),
)

@Composable
private fun SectionHeader(
    title: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    supportingText: String? = null,
 ) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            if (supportingText != null) {
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun TimerDashboard(
    timers: List<ClockTimer>,
    recentCompletedTimers: List<ClockTimer>,
    nowMs: Long,
    inSelectionMode: Boolean,
    selectedIds: Set<String>,
    onCreateCustomTimer: () -> Unit,
    onPresetTimer: (Long) -> Unit,
    onTimerTap: (ClockTimer) -> Unit,
    onTimerLongPress: (ClockTimer) -> Unit,
    onCancelTimer: (ClockTimer) -> Unit,
    onRestartTimer: (ClockTimer) -> Unit,
    onDeleteCompletedTimer: (ClockTimer) -> Unit,
    onClearCompletedTimers: () -> Unit,
 ) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            TimerQuickStartCard(
                onCreateCustomTimer = onCreateCustomTimer,
                onPresetTimer = onPresetTimer,
            )
        }

        item {
            SectionHeader(
                title = "Active timers",
                supportingText = if (timers.isEmpty()) null else "${timers.size} running now",
            )
        }

        if (timers.isEmpty()) {
            item {
                EmptyStateCard(
                    title = "No active timers",
                    body = "Start a preset or custom timer and it will appear here with live progress.",
                )
            }
        } else {
            items(timers, key = { it.id }) { timer ->
                TimerCard(
                    timer = timer,
                    nowMs = nowMs,
                    inSelectionMode = inSelectionMode,
                    isSelected = timer.id in selectedIds,
                    onTap = { onTimerTap(timer) },
                    onLongPress = { onTimerLongPress(timer) },
                    onCancel = { onCancelTimer(timer) },
                )
            }
        }

        if (recentCompletedTimers.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "Recent & Completed",
                    supportingText = "Completed timers stay here until you restart or clear them.",
                    actionLabel = "Clear all",
                    onAction = onClearCompletedTimers,
                )
            }
            items(recentCompletedTimers, key = { it.id }) { timer ->
                CompletedTimerCard(
                    timer = timer,
                    onRestart = { onRestartTimer(timer) },
                    onDelete = { onDeleteCompletedTimer(timer) },
                )
            }
        }
    }
}

@Composable
private fun TimerQuickStartCard(
    onCreateCustomTimer: () -> Unit,
    onPresetTimer: (Long) -> Unit,
 ) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Timers", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = "Start a timer fast, keep multiple timers running, and revisit finished timers without retyping durations.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TIMER_PRESETS.take(2).forEach { preset ->
                    Button(
                        onClick = { onPresetTimer(preset.durationMs) },
                        modifier = Modifier.weight(1f),
                    ) { Text(preset.label) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TIMER_PRESETS.drop(2).forEach { preset ->
                    Button(
                        onClick = { onPresetTimer(preset.durationMs) },
                        modifier = Modifier.weight(1f),
                    ) { Text(preset.label) }
                }
            }
            Button(
                onClick = onCreateCustomTimer,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            ) {
                Text("Custom timer")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimerCard(
    timer: ClockTimer,
    nowMs: Long,
    inSelectionMode: Boolean,
    isSelected: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onCancel: () -> Unit,
 ) {
    val remainingMs = (timer.triggerAtMillis - nowMs).coerceAtLeast(0L)
    val elapsedMs = (nowMs - timer.startedAtMillis).coerceAtLeast(0L)
    val progress = if (timer.durationMs <= 0) 0f else (elapsedMs.toFloat() / timer.durationMs.toFloat()).coerceIn(0f, 1f)

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .combinedClickable(onClick = onTap, onLongClick = onLongPress),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (inSelectionMode) {
                    Checkbox(checked = isSelected, onCheckedChange = { onTap() })
                } else {
                    Icon(Icons.Default.Timer, contentDescription = null)
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(timer.label ?: defaultTimerTitle(timer), style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "${formatDuration(timer.durationMs)} total · ends ${formatClockTime(timer.triggerAtMillis)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (!inSelectionMode) {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Delete, contentDescription = "Cancel timer")
                    }
                }
            }

            Text(formatCountdown(remainingMs), style = MaterialTheme.typography.headlineMedium)
            LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
            Text(
                text = if (remainingMs > 0) "${formatDuration(remainingMs)} remaining" else "Time's up!",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CompletedTimerCard(
    timer: ClockTimer,
    onRestart: () -> Unit,
    onDelete: () -> Unit,
 ) {
    val completedAt = timer.completedAtMillis ?: timer.triggerAtMillis
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(timer.label ?: defaultTimerTitle(timer), style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Completed ${formatRelativeTimestamp(completedAt)} · ${formatDuration(timer.durationMs)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onRestart,
                    modifier = Modifier.weight(1f),
                ) { Text("Restart") }
                TextButton(
                    onClick = onDelete,
                    modifier = Modifier.weight(1f),
                ) { Text("Remove") }
            }
        }
    }
}

@Composable
private fun AlarmDashboard(
    alarms: List<ClockAlarm>,
    inSelectionMode: Boolean,
    selectedIds: Set<String>,
    onNewAlarm: () -> Unit,
    onAlarmTap: (ClockAlarm) -> Unit,
    onAlarmLongPress: (ClockAlarm) -> Unit,
    onDismissAlarm: (ClockAlarm) -> Unit,
    onToggleAlarm: (ClockAlarm) -> Unit,
 ) {
    if (alarms.isEmpty()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            SectionHeader(title = "Alarms")
            EmptyStateCard(
                title = "No active alarms",
                body = "Create an alarm and it will appear here for edit, toggle, and dismissal.",
                actionLabel = "New alarm",
                onAction = onNewAlarm,
            )
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            SectionHeader(
                title = "Alarms",
                supportingText = "${alarms.size} scheduled",
            )
        }
        items(alarms, key = { it.id }) { alarm ->
            AlarmPanelRow(
                alarm = alarm,
                inSelectionMode = inSelectionMode,
                isSelected = alarm.id in selectedIds,
                onTap = { onAlarmTap(alarm) },
                onLongPress = { onAlarmLongPress(alarm) },
                onDismiss = { onDismissAlarm(alarm) },
                onToggle = { onToggleAlarm(alarm) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun EmptyStateCard(
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
 ) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (actionLabel != null && onAction != null) {
                Button(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

private fun defaultTimerTitle(timer: ClockTimer): String =
    formatDuration(timer.durationMs).removeSuffix(" remaining")

private fun formatCountdown(remainingMs: Long): String {
    val totalSeconds = remainingMs / 1_000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1_000).coerceAtLeast(0L)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0L && seconds == 0L -> "%dh %02dm".format(hours, minutes)
        hours > 0L -> "%dh %02dm %02ds".format(hours, minutes, seconds)
        minutes > 0L && seconds == 0L -> "%dm".format(minutes)
        minutes > 0L -> "%dm %02ds".format(minutes, seconds)
        else -> "%ds".format(seconds)
    }
}

private fun formatClockTime(epochMillis: Long): String =
    DateTimeFormatter.ofPattern("h:mma")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMillis))

private fun formatRelativeTimestamp(epochMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    val deltaMs = (nowMillis - epochMillis).coerceAtLeast(0L)
    val totalMinutes = deltaMs / 60_000L
    return when {
        totalMinutes <= 0L -> "just now"
        totalMinutes < 60L -> "$totalMinutes min ago"
        totalMinutes < 1_440L -> "${totalMinutes / 60L} hr ago"
        else -> "${totalMinutes / 1_440L} day ago"
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlarmPanelRow(
    alarm: ClockAlarm,
    inSelectionMode: Boolean,
    isSelected: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onDismiss: () -> Unit,
    onToggle: () -> Unit,
) {
    val formatted = remember(alarm.triggerAtMillis) {
        DateTimeFormatter.ofPattern("EEE d MMM 'at' h:mma")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(alarm.triggerAtMillis))
    }
    val dimAlpha = if (alarm.enabled) 1f else 0.4f
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onTap, onLongClick = onLongPress),
        headlineContent = {
            Text(
                text = alarm.label ?: "Alarm",
                textDecoration = if (alarm.enabled) null else TextDecoration.LineThrough,
                modifier = Modifier.alpha(dimAlpha),
            )
        },
        supportingContent = { Text(formatted, modifier = Modifier.alpha(dimAlpha)) },
        leadingContent = {
            if (inSelectionMode) {
                Checkbox(checked = isSelected, onCheckedChange = { onTap() })
            } else {
                Icon(
                    Icons.Default.Alarm,
                    contentDescription = null,
                    tint = if (alarm.enabled) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = {
            if (!inSelectionMode) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = alarm.enabled, onCheckedChange = { onToggle() })
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Delete, contentDescription = "Dismiss alarm")
                    }
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmCreateEditDialog(
    existingAlarm: ClockAlarm?,
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
                dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
            ) { DatePicker(state = dateState) }
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
                confirmButton = { TextButton(onClick = { step = "label" }) { Text("Next") } },
                dismissButton = { TextButton(onClick = { step = "date" }) { Text("Back") } },
            )
        }
        "label" -> {
            val selectedDateUtcMillis = dateState.selectedDateMillis ?: return
            val triggerAtMillis = remember(selectedDateUtcMillis, timeState.hour, timeState.minute) {
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
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Label (optional)") },
                            singleLine = true,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { onConfirm(triggerAtMillis, label.takeIf { it.isNotBlank() }) }) {
                        Text(if (isEdit) "Save" else "Set alarm")
                    }
                },
                dismissButton = { TextButton(onClick = { step = "time" }) { Text("Back") } },
            )
        }
    }
}

@Composable
private fun TimerCreateDialog(
    onConfirm: (durationMs: Long, label: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var minutes by remember { mutableIntStateOf(5) }
    var seconds by remember { mutableIntStateOf(0) }
    var label by remember { mutableStateOf("") }
    var minutesText by remember { mutableStateOf("5") }
    var secondsText by remember { mutableStateOf("0") }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Timer, contentDescription = null) },
        title = { Text("New Timer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = minutesText,
                        onValueChange = { v ->
                            minutesText = v
                            minutes = v.toIntOrNull()?.coerceIn(0, 999) ?: 0
                        },
                        modifier = Modifier.weight(1f),
                        label = { Text("Minutes") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                        ),
                    )
                    OutlinedTextField(
                        value = secondsText,
                        onValueChange = { v ->
                            secondsText = v
                            seconds = v.toIntOrNull()?.coerceIn(0, 59) ?: 0
                        },
                        modifier = Modifier.weight(1f),
                        label = { Text("Seconds") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                        ),
                    )
                }
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Label (optional)") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            val durationMs = (minutes * 60L + seconds) * 1_000L
            TextButton(
                onClick = { onConfirm(durationMs, label.takeIf { it.isNotBlank() }) },
                enabled = durationMs > 0,
            ) { Text("Start Timer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
