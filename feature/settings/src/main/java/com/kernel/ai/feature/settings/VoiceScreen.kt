package com.kernel.ai.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kernel.ai.core.voice.SherpaPiperVoice
import com.kernel.ai.core.voice.VoiceInputEngine
import com.kernel.ai.core.voice.VoiceOutputEngine
import com.kernel.ai.core.voice.VoicePackDownloadState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceScreen(
    onBack: () -> Unit,
    viewModel: VoiceViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    VoiceScreenContent(
        uiState = uiState,
        onBack = onBack,
        onVoiceInputEngineSelected = viewModel::setVoiceInputEngine,
        onAutoStartAlertVoiceCommandsEnabledChanged = viewModel::setAutoStartAlertVoiceCommandsEnabled,
        onSpokenResponsesEnabledChanged = viewModel::setSpokenResponsesEnabled,
        onVoiceOutputEngineSelected = viewModel::setVoiceOutputEngine,
        onSherpaVoiceSelected = viewModel::setSherpaVoice,
        onDownloadVoice = viewModel::downloadSherpaVoice,
        onCancelVoiceDownload = viewModel::cancelSherpaVoiceDownload,
        onDeleteVoice = viewModel::deleteSherpaVoice,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceScreenContent(
    uiState: VoiceUiState,
    onBack: () -> Unit,
    onVoiceInputEngineSelected: (VoiceInputEngine) -> Unit,
    onAutoStartAlertVoiceCommandsEnabledChanged: (Boolean) -> Unit,
    onSpokenResponsesEnabledChanged: (Boolean) -> Unit,
    onVoiceOutputEngineSelected: (VoiceOutputEngine) -> Unit,
    onSherpaVoiceSelected: (SherpaPiperVoice) -> Unit,
    onDownloadVoice: (SherpaPiperVoice) -> Unit,
    onCancelVoiceDownload: (SherpaPiperVoice) -> Unit,
    onDeleteVoice: (SherpaPiperVoice) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Voice") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "Quick Actions",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            VoiceInputEngine.entries.forEach { engine ->
                val warning = when (engine) {
                    VoiceInputEngine.AndroidNative ->
                        uiState.androidNativeAvailabilityMessage ?: engine.warning
                    else -> engine.warning
                }
                val languageSummary = when (engine) {
                    VoiceInputEngine.AndroidNative -> uiState.androidNativeLanguageSummary
                    else -> null
                }
                ListItem(
                    modifier = Modifier.fillMaxWidth(),
                    headlineContent = { Text(engine.displayName) },
                    supportingContent = {
                        Column {
                            Text(engine.description)
                            if (languageSummary != null) {
                                Text(
                                    text = "Language: $languageSummary",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            if (engine == VoiceInputEngine.AndroidNative && warning != null) {
                                VoiceWarningCard(
                                    message = warning,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        }
                    },
                    trailingContent = {
                        RadioButton(
                            selected = uiState.selectedInputEngine == engine,
                            onClick = { onVoiceInputEngineSelected(engine) },
                        )
                    },
                )
                if (
                    engine != VoiceInputEngine.AndroidNative &&
                    uiState.selectedInputEngine == engine &&
                    warning != null
                ) {
                    Text(
                        text = warning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                HorizontalDivider()
            }

            Text(
                text = "Clock alerts",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            ListItem(
                modifier = Modifier.fillMaxWidth(),
                headlineContent = { Text("Automatically listen when alarms or timers ring") },
                supportingContent = {
                    Text("Start local voice command capture for stop, dismiss, snooze, or add one minute as soon as an alert begins")
                },
                trailingContent = {
                    Switch(
                        checked = uiState.autoStartAlertVoiceCommandsEnabled,
                        onCheckedChange = onAutoStartAlertVoiceCommandsEnabledChanged,
                    )
                },
            )
            HorizontalDivider()

            Text(
                text = "Quick Actions output",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            ListItem(
                modifier = Modifier.fillMaxWidth(),
                headlineContent = { Text("Speak Quick Actions voice responses") },
                supportingContent = {
                    Text("Read spoken prompts and results aloud for voice-triggered quick actions")
                },
                trailingContent = {
                    Switch(
                        checked = uiState.spokenResponsesEnabled,
                        onCheckedChange = onSpokenResponsesEnabledChanged,
                    )
                },
            )
            HorizontalDivider()

            VoiceOutputEngine.entries.forEach { engine ->
                ListItem(
                    modifier = Modifier.fillMaxWidth(),
                    headlineContent = { Text(engine.displayName) },
                    supportingContent = { Text(engine.description) },
                    trailingContent = {
                        RadioButton(
                            selected = uiState.selectedOutputEngine == engine,
                            onClick = { onVoiceOutputEngineSelected(engine) },
                        )
                    },
                )
                HorizontalDivider()
            }

            Text(
                text = "Choose one spoken output engine. Android TTS and Sherpa Piper are alternative playback paths, so only the selected engine will speak.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            if (uiState.selectedOutputEngine == VoiceOutputEngine.SherpaExperimental) {
                Text(
                    text = "Sherpa Piper voice",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )

                val sherpaHelpText = when {
                    !uiState.hasDownloadedSherpaVoice ->
                        "Download a Sherpa voice pack below before Sherpa Piper can speak."
                    !uiState.isSelectedSherpaVoiceDownloaded ->
                        "Your saved Sherpa voice is not downloaded on this device. Download it again or choose another installed voice below."
                    else ->
                        "Only downloaded voices can be selected. Android TTS is disabled while Sherpa Piper is selected above."
                }
                Text(
                    text = sherpaHelpText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )

                uiState.sherpaVoices.forEach { voiceRow ->
                    SherpaVoiceRow(
                        rowState = voiceRow,
                        isSelected = uiState.selectedSherpaVoice == voiceRow.voice,
                        onSelect = { onSherpaVoiceSelected(voiceRow.voice) },
                        onDownload = { onDownloadVoice(voiceRow.voice) },
                        onCancel = { onCancelVoiceDownload(voiceRow.voice) },
                        onDelete = { onDeleteVoice(voiceRow.voice) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

/**
 * A single Sherpa Piper voice row with download/cancel/delete controls and progress indicator.
 * Mirrors [ModelRow] in [ModelManagementScreen] for visual consistency.
 */
@Composable
private fun SherpaVoiceRow(
    rowState: SherpaVoiceRowUiState,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val voice = rowState.voice
    val state = rowState.downloadState
    val isDownloaded = state is VoicePackDownloadState.Downloaded

    ListItem(
        modifier = modifier.fillMaxWidth(),
        headlineContent = { Text(voice.displayName) },
        supportingContent = {
            Column {
                Text(
                    text = voice.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatBytes(voice.approxDownloadBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!isDownloaded) {
                    Text(
                        text = when (state) {
                            is VoicePackDownloadState.Downloading -> "Selectable after download completes"
                            is VoicePackDownloadState.Error -> "Retry download before selecting this voice"
                            else -> "Download to make this voice selectable"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when (state) {
                    is VoicePackDownloadState.Downloading -> {
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        val pct = (state.progress * 100).toInt()
                        val mbps = state.bytesPerSecond / 1_000_000.0
                        val etaSec = state.remainingMs / 1000
                        Text(
                            text = buildString {
                                if (pct >= 90) append("Extracting…")
                                else {
                                    append("$pct%")
                                    if (state.bytesPerSecond > 0) append(" · ${"%.1f".format(mbps)} MB/s")
                                    if (etaSec > 0) append(" · ${etaSec}s remaining")
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    is VoicePackDownloadState.Error -> {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    else -> Unit
                }
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (state) {
                    is VoicePackDownloadState.NotDownloaded, is VoicePackDownloadState.Error -> {
                        TextButton(onClick = onDownload) { Text("Download") }
                    }
                    is VoicePackDownloadState.Downloading -> {
                        TextButton(onClick = onCancel) { Text("Cancel") }
                    }
                    is VoicePackDownloadState.Downloaded -> {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "Downloaded",
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        RadioButton(selected = isSelected, onClick = onSelect)
                        TextButton(onClick = onDelete) {
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun VoiceWarningCard(
    message: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Warning",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "${"%.1f".format(bytes / 1_000_000_000.0)} GB"
    bytes >= 1_000_000L -> "${"%.0f".format(bytes / 1_000_000.0)} MB"
    bytes >= 1_000L -> "${"%.0f".format(bytes / 1_000.0)} KB"
    else -> "$bytes B"
}

@Preview(showBackground = true)
@Composable
private fun VoiceScreenPreview() {
    MaterialTheme {
        VoiceScreenContent(
            uiState = VoiceUiState(
                selectedOutputEngine = VoiceOutputEngine.SherpaExperimental,
                selectedSherpaVoice = SherpaPiperVoice.NorthernEnglishMale,
                hasDownloadedSherpaVoice = true,
                isSelectedSherpaVoiceDownloaded = false,
                sherpaVoices = listOf(
                    SherpaVoiceRowUiState(
                        voice = SherpaPiperVoice.JennyDioco,
                        downloadState = VoicePackDownloadState.Downloaded("/data/user/0/com.kernel.ai.debug/files/sherpa-tts/vits-piper-en_GB-jenny_dioco-medium"),
                    ),
                    SherpaVoiceRowUiState(
                        voice = SherpaPiperVoice.SouthernEnglishFemale,
                        downloadState = VoicePackDownloadState.Downloading(progress = 0.42f, bytesPerSecond = 3_500_000L, remainingMs = 15_000L),
                    ),
                    SherpaVoiceRowUiState(
                        voice = SherpaPiperVoice.NorthernEnglishMale,
                        downloadState = VoicePackDownloadState.NotDownloaded,
                    ),
                ),
            ),
            onBack = {},
            onVoiceInputEngineSelected = {},
            onAutoStartAlertVoiceCommandsEnabledChanged = {},
            onSpokenResponsesEnabledChanged = {},
            onVoiceOutputEngineSelected = {},
            onSherpaVoiceSelected = {},
            onDownloadVoice = {},
            onCancelVoiceDownload = {},
            onDeleteVoice = {},
        )
    }
}
