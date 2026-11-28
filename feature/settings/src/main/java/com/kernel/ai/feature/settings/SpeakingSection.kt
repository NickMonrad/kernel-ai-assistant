package com.kernel.ai.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
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
import com.kernel.ai.core.ui.terms.TermsOfUseLinkWithSheet
import com.kernel.ai.core.ui.theme.JandalTheme
import com.kernel.ai.core.voice.kokoro.KokoroSetup
import com.kernel.ai.core.voice.kokoro.KokoroVoice
import com.kernel.ai.core.voice.SherpaPiperVoice
import com.kernel.ai.core.voice.VoiceOutputCopy
import com.kernel.ai.core.voice.VoiceOutputEngine
import com.kernel.ai.core.voice.VoicePackDownloadState
import kotlin.math.roundToInt

/** One choice card in Settings › Voice "Speaking" (#1972). */
internal sealed interface SpeakingOption {
    data class PiperVoice(val row: SherpaVoiceRowUiState) : SpeakingOption
    data object InflectMicro : SpeakingOption
    data object KokoroPack : SpeakingOption
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
    if (VoiceOutputEngine.KokoroExperimental in engines) add(SpeakingOption.KokoroPack)
    if (VoiceOutputEngine.AndroidTts in engines) add(SpeakingOption.PhoneVoice)
}

/** Test tag on each speaking card, formatted as `speaking_<kind>_<voice>`. */
internal fun speakingCardTestTag(option: SpeakingOption): String = when (option) {
    is SpeakingOption.PiperVoice -> "speaking_piper_${option.row.voice.name}"
    SpeakingOption.InflectMicro -> "speaking_inflect_micro"
    SpeakingOption.KokoroPack -> "speaking_kokoro"
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
    onKokoroVoiceSelected: (KokoroVoice) -> Unit,
    onDownloadSherpaVoice: (SherpaPiperVoice) -> Unit,
    onCancelSherpaVoice: (SherpaPiperVoice) -> Unit,
    onDeleteSherpaVoice: (SherpaPiperVoice) -> Unit,
    onPlaySherpaSample: (SherpaPiperVoice) -> Unit,
    onUseMobileDataForVoice: (SherpaPiperVoice) -> Unit,
    onDownloadKokoroPack: () -> Unit,
    onCancelKokoroPack: () -> Unit,
    onDeleteKokoroPack: () -> Unit,
    onUseMobileDataForKokoro: () -> Unit = {},
    onCheckKokoroSpeed: () -> Unit = {},
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
                        // Piper controls follow the old voice list: they show only while Piper is selected.
                        // Inflect Preparing owns the Cori download only while Piper is not selected.
                        val inflectOwnsDownload = !sherpaSelected &&
                            uiState.inflectMicroAvailability is ModelAvailabilityState.Preparing &&
                            uiState.selectedSherpaVoice == voice
                        val isSelectedVoice = uiState.selectedSherpaVoice == voice
                        // Wi-Fi waiting and Cancel show under any engine for the selected voice, unless Inflect owns it.
                        val cardActionsVisible = (sherpaSelected || isSelectedVoice) && !inflectOwnsDownload
                        ChoiceCard(
                            enabled = uiState.kokoroSpeed != KokoroSpeedUiState.Checking,
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
                                // A tap starts a missing or failed download, as the in-card Download button does.
                                // Switching to Piper already starts the selected voice (PiperVoiceSetup.onEngineChosen),
                                // so the tap skips its own call for that voice.
                                val engineSwitchStartsVoice = !sherpaSelected && isSelectedVoice
                                if (downloadState.needsDownloadOnTap() && !engineSwitchStartsVoice) {
                                    onDownloadSherpaVoice(voice)
                                }
                            },
                            index = index,
                            modifier = Modifier.testTag(speakingCardTestTag(option)),
                            tag = if (downloaded) downloadedChip() else sizeTag(formatBytes(voice.approxDownloadBytes)),
                            extraLine = downloadStatusLine(downloadState) ?: if (sherpaSelected && isSelectedVoice && !downloaded) {
                                "Selected. The phone's voice speaks until this downloads."
                            } else {
                                null
                            },
                            subContent = {
                                TermsOfUseLinkWithSheet()
                                if (sherpaSelected) {
                                    when (downloadState) {
                                        is VoicePackDownloadState.NotDownloaded -> Button(
                                            onClick = { onDownloadSherpaVoice(voice) },
                                            modifier = Modifier.semantics {
                                                contentDescription = "Download ${voice.displayName}"
                                            },
                                        ) { Text("Download") }
                                        is VoicePackDownloadState.Downloading -> Unit // Wi-Fi waiting shows below.
                                        is VoicePackDownloadState.Downloaded -> Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            if (isSelectedVoice) {
                                                Button(
                                                    onClick = { onPlaySherpaSample(voice) },
                                                    modifier = Modifier.semantics {
                                                        contentDescription = "Play ${voice.displayName} sample"
                                                    },
                                                ) { Text("Play sample") }
                                                Spacer(modifier = Modifier.width(8.dp))
                                            }
                                            OutlinedButton(
                                                onClick = { onDeleteSherpaVoice(voice) },
                                                colors = accentOutlinedColors,
                                                modifier = Modifier.semantics {
                                                    contentDescription = "Delete ${voice.displayName}"
                                                },
                                            ) { Text("Delete") }
                                        }
                                        is VoicePackDownloadState.Error -> OutlinedButton(
                                            onClick = { onDownloadSherpaVoice(voice) },
                                            colors = accentOutlinedColors,
                                            modifier = Modifier.semantics {
                                                contentDescription = "Retry ${voice.displayName} download"
                                            },
                                        ) { Text("Retry") }
                                    }
                                }
                                // Wi-Fi waiting and Cancel show under any engine for the selected voice, as the old status line did.
                                if (downloadState is VoicePackDownloadState.Downloading && downloadState.waitingForWifi && cardActionsVisible) {
                                    Text(
                                        text = "Waiting for Wi-Fi. Keep Wi-Fi on, or use mobile data for this voice only.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Button(
                                        onClick = { onUseMobileDataForVoice(voice) },
                                        modifier = Modifier.semantics {
                                            contentDescription = "Use mobile data for ${voice.displayName}"
                                        },
                                    ) { Text("Use mobile data") }
                                }
                                if (downloadState is VoicePackDownloadState.Downloading && cardActionsVisible) {
                                    OutlinedButton(
                                        onClick = { onCancelSherpaVoice(voice) },
                                        colors = accentOutlinedColors,
                                        modifier = Modifier.semantics {
                                            contentDescription = "Cancel ${voice.displayName} download"
                                        },
                                    ) { Text("Cancel") }
                                }
                                if (sherpaSelected && isMultiSpeaker && downloaded && isSelectedVoice) {
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
                            enabled = uiState.kokoroSpeed != KokoroSpeedUiState.Checking,
                            title = VoiceOutputCopy.title(VoiceOutputEngine.InflectMicroExperimental),
                            description = "${VoiceOutputCopy.body(VoiceOutputEngine.InflectMicroExperimental)} " +
                                "Also needs the ${uiState.selectedSherpaVoice.displayName} voice pack.",
                            selected = selected,
                            onSelect = {
                                onOutputEngineSelected(VoiceOutputEngine.InflectMicroExperimental)
                                // Inflect needs the Cori pack; its Download button starts it, so a tap does the same.
                                if (state !is ModelAvailabilityState.Ready && state !is ModelAvailabilityState.Preparing) {
                                    onDownloadInflectMicro()
                                }
                            },
                            index = index,
                            modifier = Modifier.testTag(speakingCardTestTag(option)),
                            tag = if (state is ModelAvailabilityState.Ready) downloadedChip() else null,
                            extraLine = when {
                                state is ModelAvailabilityState.Preparing ->
                                    "Preparing ${(state.progress.coerceIn(0f, 1f) * 100).toInt()}%"
                                state is ModelAvailabilityState.ActionRequired -> "Setup did not finish. Tap Retry."
                                selected && state !is ModelAvailabilityState.Ready -> "Selected, setup needed"
                                else -> null
                            },
                            subContent = {
                                TermsOfUseLinkWithSheet()
                                when {
                                    state is ModelAvailabilityState.Preparing -> OutlinedButton(
                                        onClick = onCancelInflectMicro,
                                        colors = accentOutlinedColors,
                                        modifier = Modifier.semantics { contentDescription = "Cancel Inflect Micro setup" },
                                    ) { Text("Cancel") }
                                    state is ModelAvailabilityState.Ready -> OutlinedButton(
                                        onClick = onDeleteInflectMicro,
                                        colors = accentOutlinedColors,
                                        modifier = Modifier.semantics { contentDescription = "Delete Inflect Micro" },
                                    ) { Text("Delete") }
                                    state is ModelAvailabilityState.ActionRequired -> OutlinedButton(
                                        onClick = onDownloadInflectMicro,
                                        colors = accentOutlinedColors,
                                        modifier = Modifier.semantics { contentDescription = "Retry Inflect Micro download" },
                                    ) { Text("Retry") }
                                    state is ModelAvailabilityState.NotDisplayed &&
                                        inflectGraphsReady && coriDownloading && sherpaSelected -> Text(
                                        text = "${uiState.selectedSherpaVoice.displayName} is still downloading. " +
                                            "Inflect is ready once it finishes.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    else -> Button(
                                        onClick = onDownloadInflectMicro,
                                        modifier = Modifier.semantics { contentDescription = "Download Inflect Micro" },
                                    ) { Text("Download") }
                                }
                            },
                        )
                    }

                    SpeakingOption.KokoroPack -> {
                        val states = uiState.kokoroStates
                        val models = KokoroSetup.kernelModels()
                        val ready = states.isNotEmpty() && states.values.all { it is DownloadState.Downloaded }
                        val downloading = states.values.any { it is DownloadState.Downloading }
                        val failed = states.values.any { it is DownloadState.Error }
                        val totalBytes = models.sumOf { it.approxSizeBytes }
                        val downloadedBytes = models.sumOf { model ->
                            when (val state = states[model]) {
                                is DownloadState.Downloaded -> model.approxSizeBytes
                                is DownloadState.Downloading -> (model.approxSizeBytes * state.progress.coerceIn(0f, 1f)).toLong()
                                else -> 0L
                            }
                        }
                        val progress = if (totalBytes > 0L) downloadedBytes.toFloat() / totalBytes else 0f
                        ChoiceCard(
                            title = VoiceOutputCopy.title(VoiceOutputEngine.KokoroExperimental),
                            description = VoiceOutputCopy.body(VoiceOutputEngine.KokoroExperimental),
                            selected = kokoroSelected,
                            enabled = !downloading && !uiState.kokoroWaitingForWifi && uiState.kokoroSpeed != KokoroSpeedUiState.Checking,
                            onSelect = {
                                onOutputEngineSelected(VoiceOutputEngine.KokoroExperimental)
                                // A tap starts a missing pack, as the Download button does.
                                if (!ready && !downloading) onDownloadKokoroPack()
                            },
                            index = index,
                            modifier = Modifier.testTag(speakingCardTestTag(option)),
                            tag = if (ready) downloadedChip() else sizeTag(KokoroSetup.approxSizeText()),
                            extraLine = when {
                                uiState.kokoroWaitingForWifi -> "Waiting for Wi-Fi"
                                downloading -> "Downloading ${(progress.coerceIn(0f, 1f) * 100).toInt()}%"
                                failed -> "Download failed. Tap Retry."
                                uiState.kokoroSpeed == KokoroSpeedUiState.Checking ->
                                    "Checking this phone can speak Kokoro smoothly… (about 30 s)"
                                kokoroSelected && !ready -> "Selected. Download the pack first; other voices speak until then."
                                ready && !kokoroSelected && uiState.kokoroSpeed == KokoroSpeedUiState.Idle ->
                                    "Tap to check this phone can use Kokoro"
                                else -> null
                            },
                            subContent = {
                                TermsOfUseLinkWithSheet()
                                if (ready) {
                                    when (val check = uiState.kokoroSpeed) {
                                        is KokoroSpeedUiState.Failed -> {
                                            Text(
                                                text = if (check.memoryShort) {
                                                    "This phone doesn't have enough free memory for Kokoro right now. Staying on ${check.stayingOn}."
                                                } else {
                                                    "This phone isn't fast enough for Kokoro; replies would stutter. Staying on ${check.stayingOn}."
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                            check.details?.let { CheckDetails(it) }
                                        }
                                        is KokoroSpeedUiState.CannotRun -> {
                                            Text(text = check.reason, style = MaterialTheme.typography.bodySmall)
                                            check.details?.let { CheckDetails(it) }
                                        }
                                        else -> Unit
                                    }
                                    if (uiState.kokoroSpeed is KokoroSpeedUiState.Failed ||
                                        uiState.kokoroSpeed is KokoroSpeedUiState.CannotRun
                                    ) {
                                        TextButton(
                                            onClick = onCheckKokoroSpeed,
                                            modifier = Modifier.semantics { contentDescription = "Check Kokoro again" },
                                        ) { Text("Check again") }
                                    }
                                    uiState.kokoroSampleError?.let {
                                        Text(text = it, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                when {
                                    downloading -> OutlinedButton(
                                        onClick = onCancelKokoroPack,
                                        colors = accentOutlinedColors,
                                        modifier = Modifier.semantics { contentDescription = "Cancel Kokoro download" },
                                    ) { Text("Cancel") }
                                    ready -> OutlinedButton(
                                        onClick = onDeleteKokoroPack,
                                        colors = accentOutlinedColors,
                                        modifier = Modifier.semantics { contentDescription = "Delete Kokoro" },
                                    ) { Text("Delete") }
                                    failed -> OutlinedButton(
                                        onClick = onDownloadKokoroPack,
                                        colors = accentOutlinedColors,
                                        modifier = Modifier.semantics { contentDescription = "Retry Kokoro download" },
                                    ) { Text("Retry") }
                                    else -> Button(
                                        onClick = onDownloadKokoroPack,
                                        modifier = Modifier.semantics { contentDescription = "Download Kokoro" },
                                    ) { Text("Download") }
                                }
                                if (uiState.kokoroWaitingForWifi) {
                                    Text(
                                        text = "Waiting for Wi-Fi. Keep Wi-Fi on, or use mobile data for this pack only.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Button(
                                        onClick = onUseMobileDataForKokoro,
                                        modifier = Modifier.semantics {
                                            contentDescription = "Use mobile data for Kokoro"
                                        },
                                    ) { Text("Use mobile data") }
                                }
                                if (kokoroSelected && ready) {
                                    KokoroVoicePicker(
                                        selected = uiState.selectedKokoroVoice,
                                        onSelected = onKokoroVoiceSelected,
                                    )
                                }
                            },
                        )
                    }

                    SpeakingOption.PhoneVoice -> ChoiceCard(
                        enabled = uiState.kokoroSpeed != KokoroSpeedUiState.Checking,
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

@Composable
private fun KokoroVoicePicker(
    selected: KokoroVoice,
    onSelected: (KokoroVoice) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Voice",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        KokoroVoice.entries.forEach { voice ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(
                        selected = voice == selected,
                        onClick = { onSelected(voice) },
                        role = Role.RadioButton,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = voice == selected, onClick = null)
                Text(
                    text = "${voice.displayName}: ${voice.description}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun CheckDetails(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun sizeTag(text: String): @Composable () -> Unit = {
    InfoTag(text = text, style = InfoTagStyle.Neutral)
}

/** Progress or failure line under a voice card. Wi-Fi waiting is shown in the card's sub-content instead. */
private fun downloadStatusLine(state: VoicePackDownloadState): String? = when (state) {
    is VoicePackDownloadState.Downloading ->
        if (state.waitingForWifi) null else "Downloading ${(state.progress * 100).toInt()}%"
    is VoicePackDownloadState.Error -> "Download failed"
    else -> null
}

/**
 * A tap on a card starts its download only when nothing has been downloaded or started yet; a failed download
 * retries, as its Retry button does. A downloaded or downloading voice is only selected.
 */
private fun VoicePackDownloadState.needsDownloadOnTap(): Boolean =
    this is VoicePackDownloadState.NotDownloaded || this is VoicePackDownloadState.Error
