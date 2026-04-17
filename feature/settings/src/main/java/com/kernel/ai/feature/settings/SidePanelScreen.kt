package com.kernel.ai.feature.settings

import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kernel.ai.core.memory.entity.ScheduledAlarmEntity
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SidePanelScreen(
    onBack: () -> Unit = {},
    viewModel: SidePanelViewModel = hiltViewModel(),
) {
    val alarms by viewModel.alarms.collectAsStateWithLifecycle()
    val timers by viewModel.timers.collectAsStateWithLifecycle()
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Active Timers & Alarms") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        val hasContent = timers.isNotEmpty() || alarms.isNotEmpty()
        if (!hasContent) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
            ) {
                Spacer(modifier = Modifier.height(32.dp))
                Text(
                    text = "No active timers or alarms.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Ask Jandal to set a timer or alarm — they'll appear here while running.",
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
                if (timers.isNotEmpty()) {
                    item {
                        SectionHeader(title = "Timers")
                    }
                    items(timers, key = { it.id }) { timer ->
                        TimerRow(
                            timer = timer,
                            nowMs = nowMs,
                            onCancel = { pendingCancel = timer },
                        )
                        HorizontalDivider()
                    }
                }

                if (alarms.isNotEmpty()) {
                    item {
                        if (timers.isNotEmpty()) Spacer(modifier = Modifier.height(8.dp))
                        SectionHeader(title = "Alarms")
                    }
                    items(alarms, key = { it.id }) { alarm ->
                        AlarmPanelRow(
                            alarm = alarm,
                            onDismiss = { pendingDismiss = alarm },
                        )
                        HorizontalDivider()
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

@Composable
private fun TimerRow(
    timer: ScheduledAlarmEntity,
    nowMs: Long,
    onCancel: () -> Unit,
) {
    val remainingMs = if (timer.startedAtMs != null && timer.durationMs != null) {
        timer.startedAtMs + timer.durationMs - nowMs
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
        modifier = Modifier.fillMaxWidth(),
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
            Icon(
                Icons.Default.Timer,
                contentDescription = null,
                tint = if (remainingMs <= 0) MaterialTheme.colorScheme.error
                       else MaterialTheme.colorScheme.primary,
            )
        },
        trailingContent = {
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Delete, contentDescription = "Cancel timer")
            }
        },
    )
}

@Composable
private fun AlarmPanelRow(
    alarm: ScheduledAlarmEntity,
    onDismiss: () -> Unit,
) {
    val formatted = remember(alarm.triggerAtMillis) {
        DateTimeFormatter.ofPattern("EEE d MMM 'at' h:mma")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(alarm.triggerAtMillis))
    }
    ListItem(
        modifier = Modifier.fillMaxWidth(),
        headlineContent = { Text(alarm.label ?: "Alarm") },
        supportingContent = { Text(formatted) },
        leadingContent = {
            Icon(
                Icons.Default.Alarm,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        trailingContent = {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Delete, contentDescription = "Dismiss alarm")
            }
        },
    )
}
