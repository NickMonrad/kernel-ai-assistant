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
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kernel.ai.core.memory.entity.ScheduledAlarmEntity
import java.time.Instant
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
                    text = "Alarms scheduled via Jandal for a specific date appear here. You can cancel them before they fire.",
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
                    AlarmRow(alarm = alarm, onCancel = { pendingCancel = alarm })
                    HorizontalDivider()
                }
            }
        }
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

@Composable
private fun AlarmRow(
    alarm: ScheduledAlarmEntity,
    onCancel: () -> Unit,
) {
    val formatted = remember(alarm.triggerAtMillis) {
        Instant.ofEpochMilli(alarm.triggerAtMillis)
            .let { DateTimeFormatter.ofPattern("EEE d MMM 'at' h:mma").withZone(ZoneId.systemDefault()).format(it) }
    }
    ListItem(
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
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Delete, contentDescription = "Cancel alarm")
            }
        },
    )
}
