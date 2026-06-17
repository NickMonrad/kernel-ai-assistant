package com.kernel.ai.feature.settings

import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kernel.ai.core.memory.clock.ClockAlertConfig

private val TIMER_DURATION_OPTIONS = listOf(
    15_000L to "15 seconds",
    30_000L to "30 seconds",
    60_000L to "60 seconds",
    120_000L to "2 minutes",
    300_000L to "5 minutes",
)

private val ALARM_RING_DURATION_OPTIONS = listOf(
    30_000L to "30 seconds",
    60_000L to "60 seconds",
    120_000L to "2 minutes",
    300_000L to "5 minutes",
)

private val SNOOZE_DURATION_OPTIONS = listOf(
    300_000L to "5 minutes",
    600_000L to "10 minutes",
    900_000L to "15 minutes",
    1_800_000L to "30 minutes",
)

private val MAX_AUTO_SNOOZE_OPTIONS = listOf(
    0 to "0 — auto-stop on first ring",
    1 to "1 — snooze once, then stop",
    2 to "2 — snooze twice, then stop",
    3 to "3 — snooze three times, then stop",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClockSettingsScreen(
    onBack: () -> Unit = {},
    viewModel: ClockSettingsViewModel = hiltViewModel(),
) {
    val config by viewModel.alertConfig.collectAsStateWithLifecycle()
    val soundConfig by viewModel.soundConfig.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val alarmSoundPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        @Suppress("DEPRECATION")
        val pickedUri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        viewModel.setDefaultAlarmSoundUri(normalizePickedClockSoundUri(pickedUri))
    }
    val timerSoundPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        @Suppress("DEPRECATION")
        val pickedUri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        viewModel.setTimerSoundUri(normalizePickedClockSoundUri(pickedUri))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Clock settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        modifier = Modifier.testTag("clock_settings_screen"),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            // ── Section: Alert behaviour ─────────────────────────
            Text(
                text = "Alert behaviour",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            DurationSetting(
                label = "Timer sound duration",
                value = config.timerAutoStopDurationMs,
                options = TIMER_DURATION_OPTIONS,
                onValueChange = viewModel::setTimerAutoStopDurationMs,
                testTag = "clock_settings_timer_sound_duration",
            )

            Spacer(Modifier.height(12.dp))

            DurationSetting(
                label = "Alarm ring duration",
                value = config.alarmRingDurationMs,
                options = ALARM_RING_DURATION_OPTIONS,
                onValueChange = viewModel::setAlarmRingDurationMs,
                testTag = "clock_settings_alarm_ring_duration",
            )

            Spacer(Modifier.height(12.dp))

            DurationSetting(
                label = "Snooze duration",
                value = config.snoozeDurationMs,
                options = SNOOZE_DURATION_OPTIONS,
                onValueChange = viewModel::setSnoozeDurationMs,
                testTag = "clock_settings_snooze_duration",
            )

            Spacer(Modifier.height(12.dp))

            MaxAutoSnoozeSetting(
                value = config.maxAutoSnoozes,
                onValueChange = viewModel::setMaxAutoSnoozes,
                testTag = "clock_settings_max_auto_snoozes",
            )

            Spacer(Modifier.height(24.dp))

            // ── Section: Sounds ──────────────────────────────────
            Text(
                text = "Sounds",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            SoundSetting(
                title = "Alarm sound",
                currentSoundUri = soundConfig.defaultAlarmSoundUri,
                onSoundSelected = viewModel::setDefaultAlarmSoundUri,
                onClick = { alarmSoundPickerLauncher.launch(createClockSoundPickerIntent(soundConfig.defaultAlarmSoundUri)) },
                testTag = "clock_settings_alarm_sound",
            )

            Spacer(Modifier.height(12.dp))

            SoundSetting(
                title = "Timer sound",
                currentSoundUri = soundConfig.timerSoundUri,
                onSoundSelected = viewModel::setTimerSoundUri,
                onClick = { timerSoundPickerLauncher.launch(createClockSoundPickerIntent(soundConfig.timerSoundUri)) },
                testTag = "clock_settings_timer_sound",
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DurationSetting(
    label: String,
    value: Long,
    options: List<Pair<Long, String>>,
    onValueChange: (Long) -> Unit,
    testTag: String,
) {
    val selectedLabel = options.firstOrNull { it.first == value }?.second
        ?: options.first().second

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
            ) {
                OutlinedTextField(
                    value = selectedLabel,
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    options.forEach { (optionValue, optionLabel) ->
                        DropdownMenuItem(
                            text = { Text(optionLabel) },
                            onClick = {
                                onValueChange(optionValue)
                                expanded = false
                            },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MaxAutoSnoozeSetting(
    value: Int,
    onValueChange: (Int) -> Unit,
    testTag: String,
) {
    val selectedLabel = MAX_AUTO_SNOOZE_OPTIONS.firstOrNull { it.first == value }?.second
        ?: MAX_AUTO_SNOOZE_OPTIONS.first().second

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Automatic snoozes",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
            ) {
                OutlinedTextField(
                    value = selectedLabel,
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    MAX_AUTO_SNOOZE_OPTIONS.forEach { (optionValue, optionLabel) ->
                        DropdownMenuItem(
                            text = { Text(optionLabel) },
                            onClick = {
                                onValueChange(optionValue)
                                expanded = false
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SoundSetting(
    title: String,
    currentSoundUri: String?,
    onSoundSelected: (String?) -> Unit,
    onClick: () -> Unit,
    testTag: String,
) {
    val context = LocalContext.current
    val soundLabel = remember(currentSoundUri) { clockSoundLabel(context, currentSoundUri) }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = soundLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = onClick,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Choose sound")
                }
                if (currentSoundUri != null) {
                    TextButton(
                        onClick = { onSoundSelected(null) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("System default")
                    }
                }
            }
        }
    }
}

private fun defaultClockSoundUri(): Uri =
    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)

private fun normalizePickedClockSoundUri(uri: Uri?): String? =
    uri?.toString()?.takeUnless { it == defaultClockSoundUri().toString() }

private fun clockSoundLabel(context: android.content.Context, soundUri: String?): String =
    if (soundUri == null) {
        "System default"
    } else {
        runCatching { RingtoneManager.getRingtone(context, Uri.parse(soundUri))?.getTitle(context) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "Custom sound"
    }

private fun createClockSoundPickerIntent(currentSoundUri: String?): Intent =
    Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
        putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
        putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, defaultClockSoundUri())
        if (currentSoundUri != null) {
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(currentSoundUri))
        }
    }
