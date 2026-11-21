package com.kernel.ai.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kernel.ai.core.inference.download.DownloadState
import com.kernel.ai.core.model.availability.ModelAvailabilityState
import com.kernel.ai.core.ui.ChipStatus
import com.kernel.ai.core.ui.ChoiceCard
import com.kernel.ai.core.ui.ChoiceGroup
import com.kernel.ai.core.ui.InfoTag
import com.kernel.ai.core.ui.InfoTagStyle
import com.kernel.ai.core.ui.SectionLabel
import com.kernel.ai.core.ui.StatusChip
import com.kernel.ai.core.ui.theme.JandalTheme
import com.kernel.ai.core.voice.SherpaKokoroVoice
import com.kernel.ai.core.voice.SherpaPiperVoice
import com.kernel.ai.core.voice.VoiceOutputCopy
import com.kernel.ai.core.voice.VoiceOutputEngine
import com.kernel.ai.core.voice.VoicePackDownloadState
import kotlin.math.roundToInt

/** One choice card in Settings › Voice "Speaking" (#1972). */
internal sealed interface SpeakingOption {
    data class PiperVoice(val row: SherpaVoiceRowUiState) : SpeakingOption
    data object InflectMicro : SpeakingOption
    data class KokoroVoice(val row: KokoroVoiceRowUiState) : SpeakingOption
    data object PhoneVoice : SpeakingOption
}

/**
 * Cards this build and phone offer, in display order. `sherpaVoices` is already filtered by build
 * (release shows Cori only), and an engine's cards appear only when the engine is in `availableOutputEngines`.
 */
internal fun speakingOptions(state: VoiceUiState): List<SpeakingOption> = buildList {
    val engines = state.availableOutputEngines
    if (VoiceOutputEngine.SherpaExperimental in engines) {
        state.sherpaVoices.forEach { add(SpeakingOption.PiperVoice(it)) }
    }
    if (VoiceOutputEngine.InflectMicroExperimental in engines) add(SpeakingOption.InflectMicro)
    if (VoiceOutputEngine.KokoroExperimental in engines) {
        state.kokoroVoices.forEach { add(SpeakingOption.KokoroVoice(it)) }
    }
    if (VoiceOutputEngine.AndroidTts in engines) add(SpeakingOption.PhoneVoice)
}

/** Test tag on each speaking card, formatted as `speaking_<kind>_<voice>`. */
internal fun speakingCardTestTag(option: SpeakingOption): String = when (option) {
    is SpeakingOption.PiperVoice -> "speaking_piper_${option.row.voice.name}"
    SpeakingOption.InflectMicro -> "speaking_inflect_micro"
    is SpeakingOption.KokoroVoice -> "speaking_kokoro_${option.row.voice.name}"
    SpeakingOption.PhoneVoice -> "speaking_phone_voice"
}

/**
 * Settings › Voice "Speaking" section (#1972). One choice card per option from [speakingOptions]. Tapping a card
 * selects its engine and, for a downloaded Piper or Kokoro voice, that voice. Download, cancel, delete, retry and
 * sample buttons stay inside their card. Sliders sit under one "Sound" label. Stateless apart from the speaker
 * list expansion, so previews and tests can drive it.
 */
@Composable
internal fun SpeakingSection(
    uiState: VoiceUiState,
    onOutputEngineSelected: (VoiceOutputEngine) -> Unit,
    onSherpaVoiceSelected: (SherpaPiperVoice) -> Unit,
    onSherpaSpeedChanged: (Float) -> Unit,
    onSherpaPitchChanged: (Float) -> Unit,
    onSherpaGainChanged: (Float) -> Unit,
    onActiveSpeakerIdChanged: (Int) -> Unit,
    onKokoroVoiceSelected: (SherpaKokoroVoice) -> Unit,
    onKokoroActiveSpeakerIdChanged: (Int) -> Unit,
    onDownloadSherpaVoice: (SherpaPiperVoice) -> Unit,
    onCancelSherpaVoice: (SherpaPiperVoice) -> Unit,
    onDeleteSherpaVoice: (SherpaPiperVoice) -> Unit,
    onPlaySherpaSample: (SherpaPiperVoice) -> Unit,
    onUseMobileDataForVoice: (SherpaPiperVoice) -> Unit,
    onDownloadKokoroVoice: (SherpaKokoroVoice) -> Unit,
    onCancelKokoroVoice: (SherpaKokoroVoice) -> Unit,
    onDeleteKokoroVoice: (SherpaKokoroVoice) -> Unit,
    onDownloadInflectMicro: () -> Unit,
    onCancelInflectMicro: () -> Unit,
    onDeleteInflectMicro: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Expanded again whenever the selected Piper voice changes, as the old per-voice selector was.
    var speakerSelectorExpanded by rememberSaveable(uiState.selectedSherpaVoice) { mutableStateOf(true) }
    val accentOutlinedColors = ButtonDefaults.outlinedButtonColors(contentColor = JandalTheme.colors.accent)
    val options = speakingOptions(uiState)
    val engine = uiState.selectedOutputEngine
    val sherpaSelected = engine == VoiceOutputEngine.SherpaExperimental
    val kokoroSelected = engine == VoiceOutputEngine.KokoroExperimental
    val coriDownloading = uiState.sherpaVoices
        .firstOrNull { it.voice == uiState.selectedSherpaVoice }?.downloadState is VoicePackDownloadState.Downloading
    val inflectGraphsReady = uiState.inflectMicroStates.isNotEmpty() &&
        uiState.inflectMicroStates.values.all { it is DownloadState.Downloaded }

    Column(modifier = modifier.fillMaxWidth()) {
        SectionLabel(
            text = "Speaking",
            icon = Icons.AutoMirrored.Outlined.VolumeUp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Text(
            text = "Choose how Jandal speaks. Only the selected option speaks.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        ChoiceGroup(count = options.size, modifier = Modifier.padding(horizontal = 16.dp)) {
            options.forEachIndexed { index, option ->
                when (option) {
                    is SpeakingOption.PiperVoice -> {
                        val voice = option.row.voice
                        val downloadState = option.row.downloadState
                        val downloaded = downloadState is VoicePackDownloadState.Downloaded
                        val isCori = voice == SherpaPiperVoice.CoriHigh
                        val isMultiSpeaker = voice == SherpaPiperVoice.VctkMedium ||
                            voice == SherpaPiperVoice.SemaineMedium
                        // While Inflect prepares, its card owns the Cori download and shows the only Cancel.
                        val inflectOwnsDownload =
                            uiState.inflectMicroAvailability is ModelAvailabilityState.Preparing &&
                                uiState.selectedSherpaVoice == voice
                        ChoiceCard(
                            title = if (isCori) {
                                VoiceOutputCopy.title(VoiceOutputEngine.SherpaExperimental)
                            } else {
                                voice.displayName
                            },
                            description = if (isCori) {
                                VoiceOutputCopy.body(VoiceOutputEngine.SherpaExperimental)
                            } else {
                                voice.description
                            },
                            selected = sherpaSelected && uiState.selectedSherpaVoice == voice,
                            onSelect = {
                                if (downloaded) onSherpaVoiceSelected(voice)
                                onOutputEngineSelected(VoiceOutputEngine.SherpaExperimental)
                            },
                            index = index,
                            modifier = Modifier.testTag(speakingCardTestTag(option)),
                            tag = if (downloaded) downloadedChip() else sizeTag(voice.approxDownloadBytes),
                            extraLine = downloadStatusLine(downloadState),
                            subContent = {
                                when (downloadState) {
                                    is VoicePackDownloadState.NotDownloaded -> Button(
                                        onClick = { onDownloadSherpaVoice(voice) },
                                    ) { Text("Download") }
                                    is VoicePackDownloadState.Downloading -> {
                                        if (downloadState.waitingForWifi) {
                                            Text(
                                                text = "Waiting for Wi-Fi. Keep Wi-Fi on, or use mobile data for this voice only.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Button(onClick = { onUseMobileDataForVoice(voice) }) {
                                                Text("Use mobile data")
                                            }
                                        }
                                        if (!inflectOwnsDownload) {
                                            OutlinedButton(
                                                onClick = { onCancelSherpaVoice(voice) },
                                                colors = accentOutlinedColors,
                                            ) { Text("Cancel") }
                                        }
                                    }
                                    is VoicePackDownloadState.Downloaded -> Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        if (uiState.selectedSherpaVoice == voice) {
                                            Button(onClick = { onPlaySherpaSample(voice) }) { Text("Play sample") }
                                            Spacer(modifier = Modifier.width(8.dp))
                                        }
                                        OutlinedButton(
                                            onClick = { onDeleteSherpaVoice(voice) },
                                            colors = accentOutlinedColors,
                                        ) { Text("Delete") }
                                    }
                                    is VoicePackDownloadState.Error -> OutlinedButton(
                                        onClick = { onDownloadSherpaVoice(voice) },
                                        colors = accentOutlinedColors,
                                    ) { Text("Retry") }
                                }
                                if (isMultiSpeaker && downloaded && uiState.selectedSherpaVoice == voice) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = "Speaker",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.weight(1f),
                                        )
                                        IconButton(onClick = { speakerSelectorExpanded = !speakerSelectorExpanded }) {
                                            Icon(
                                                imageVector = if (speakerSelectorExpanded) {
                                                    Icons.Default.ExpandLess
                                                } else {
                                                    Icons.Default.ExpandMore
                                                },
                                                contentDescription = if (speakerSelectorExpanded) {
                                                    "Collapse speaker selector"
                                                } else {
                                                    "Expand speaker selector"
                                                },
                                            )
                                        }
                                    }
                                    AnimatedVisibility(visible = speakerSelectorExpanded) {
                                        when (voice) {
                                            SherpaPiperVoice.VctkMedium -> VctkSpeakerSelector(
                                                activeSpeakerId = uiState.activeSpeakerId,
                                                onSpeakerSelected = onActiveSpeakerIdChanged,
                                            )
                                            SherpaPiperVoice.SemaineMedium -> SemaineSpeakerSelector(
                                                activeSpeakerId = uiState.activeSpeakerId,
                                                onSpeakerSelected = onActiveSpeakerIdChanged,
                                            )
                                            else -> Unit
                                        }
                                    }
                                }
                            },
                        )
                    }

                    SpeakingOption.InflectMicro -> {
                        val state = uiState.inflectMicroAvailability
                        val selected = engine == VoiceOutputEngine.InflectMicroExperimental
                        ChoiceCard(
                            title = VoiceOutputCopy.title(VoiceOutputEngine.InflectMicroExperimental),
                            description = "${VoiceOutputCopy.body(VoiceOutputEngine.InflectMicroExperimental)} " +
                                "Also needs the ${uiState.selectedSherpaVoice.displayName} voice pack.",
                            selected = selected,
                            onSelect = { onOutputEngineSelected(VoiceOutputEngine.InflectMicroExperimental) },
                            index = index,
                            modifier = Modifier.testTag(speakingCardTestTag(option)),
                            tag = if (state is ModelAvailabilityState.Ready) downloadedChip() else null,
                            extraLine = when {
                                state is ModelAvailabilityState.Preparing -> "Preparing"
                                state is ModelAvailabilityState.ActionRequired ->
                                    "Setup did not finish. Retry to try again."
                                selected && state !is ModelAvailabilityState.Ready -> "Selected, setup needed"
                                else -> null
                            },
                            subContent = {
                                when {
                                    state is ModelAvailabilityState.Preparing -> OutlinedButton(
                                        onClick = onCancelInflectMicro,
                                        colors = accentOutlinedColors,
                                    ) { Text("Cancel") }
                                    state is ModelAvailabilityState.Ready -> OutlinedButton(
                                        onClick = onDeleteInflectMicro,
                                        colors = accentOutlinedColors,
                                    ) { Text("Delete") }
                                    state is ModelAvailabilityState.ActionRequired -> OutlinedButton(
                                        onClick = onDownloadInflectMicro,
                                        colors = accentOutlinedColors,
                                    ) { Text("Retry") }
                                    state is ModelAvailabilityState.NotDisplayed &&
                                        inflectGraphsReady && coriDownloading && sherpaSelected -> Text(
                                        text = "The Piper voice above is still downloading. Inflect is ready once it finishes.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    else -> Button(onClick = onDownloadInflectMicro) { Text("Download") }
                                }
                            },
                        )
                    }

                    is SpeakingOption.KokoroVoice -> {
                        val voice = option.row.voice
                        val downloadState = option.row.downloadState
                        val downloaded = downloadState is VoicePackDownloadState.Downloaded
                        ChoiceCard(
                            title = voice.displayName,
                            description = voice.description,
                            selected = kokoroSelected && uiState.selectedKokoroVoice == voice,
                            onSelect = {
                                if (downloaded) onKokoroVoiceSelected(voice)
                                onOutputEngineSelected(VoiceOutputEngine.KokoroExperimental)
                            },
                            index = index,
                            modifier = Modifier.testTag(speakingCardTestTag(option)),
                            tag = if (downloaded) downloadedChip() else sizeTag(voice.approxDownloadBytes),
                            extraLine = downloadStatusLine(downloadState),
                            subContent = {
                                when (downloadState) {
                                    is VoicePackDownloadState.NotDownloaded -> Button(
                                        onClick = { onDownloadKokoroVoice(voice) },
                                    ) { Text("Download") }
                                    is VoicePackDownloadState.Downloading -> OutlinedButton(
                                        onClick = { onCancelKokoroVoice(voice) },
                                        colors = accentOutlinedColors,
                                    ) { Text("Cancel") }
                                    is VoicePackDownloadState.Downloaded -> OutlinedButton(
                                        onClick = { onDeleteKokoroVoice(voice) },
                                        colors = accentOutlinedColors,
                                    ) { Text("Delete") }
                                    is VoicePackDownloadState.Error -> OutlinedButton(
                                        onClick = { onDownloadKokoroVoice(voice) },
                                        colors = accentOutlinedColors,
                                    ) { Text("Retry") }
                                }
                                if (uiState.selectedKokoroVoice == voice && downloaded && voice.speakerCount > 1) {
                                    KokoroSpeakerSelector(
                                        voice = voice,
                                        activeSpeakerId = uiState.kokoroActiveSpeakerId,
                                        onSpeakerSelected = onKokoroActiveSpeakerIdChanged,
                                    )
                                }
                            },
                        )
                    }

                    SpeakingOption.PhoneVoice -> ChoiceCard(
                        title = VoiceOutputCopy.title(VoiceOutputEngine.AndroidTts),
                        description = VoiceOutputCopy.body(VoiceOutputEngine.AndroidTts),
                        selected = engine == VoiceOutputEngine.AndroidTts,
                        onSelect = { onOutputEngineSelected(VoiceOutputEngine.AndroidTts) },
                        index = index,
                        modifier = Modifier.testTag(speakingCardTestTag(option)),
                    )
                }
            }
        }

        uiState.sherpaSampleError?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        if (sherpaSelected || kokoroSelected) {
            SectionLabel(
                text = "Sound",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            // Speech rate 0.5–1.5 in steps of 0.05 (19 discrete steps)
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                SliderRow(
                    label = "Speech rate",
                    valueLabel = "%.2fx".format(uiState.sherpaSpeed),
                    value = uiState.sherpaSpeed,
                    valueRange = 0.5f..1.5f,
                    steps = 19,
                    onValueChangeFinished = { newVal -> onSherpaSpeedChanged((newVal * 20).roundToInt() / 20f) },
                )
            }
            // Pitch 0.5–2.0 in steps of 0.05 (29 discrete steps)
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                SliderRow(
                    label = "Pitch",
                    valueLabel = "%.2fx".format(uiState.sherpaPitch),
                    value = uiState.sherpaPitch,
                    valueRange = 0.5f..2.0f,
                    steps = 29,
                    onValueChangeFinished = { newVal -> onSherpaPitchChanged((newVal * 20).roundToInt() / 20f) },
                )
            }
            // Volume boost 0.5–3.0 in steps of 0.25 (9 discrete steps)
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                SliderRow(
                    label = "Volume boost",
                    valueLabel = "%.2fx".format(uiState.sherpaGain),
                    value = uiState.sherpaGain,
                    valueRange = 0.5f..3.0f,
                    steps = 9,
                    onValueChangeFinished = { newVal -> onSherpaGainChanged((newVal * 4).roundToInt() / 4f) },
                )
            }
        }
    }
}

private fun downloadedChip(): @Composable () -> Unit = {
    StatusChip(status = ChipStatus.On, label = "Downloaded")
}

private fun sizeTag(bytes: Long): @Composable () -> Unit = {
    InfoTag(text = formatBytes(bytes), style = InfoTagStyle.Neutral)
}

/** Progress or failure line under a voice card. Wi-Fi waiting is shown in the card's sub-content instead. */
private fun downloadStatusLine(state: VoicePackDownloadState): String? = when (state) {
    is VoicePackDownloadState.Downloading ->
        if (state.waitingForWifi) null else "Downloading ${(state.progress * 100).toInt()}%"
    is VoicePackDownloadState.Error -> "Download failed"
    else -> null
}
