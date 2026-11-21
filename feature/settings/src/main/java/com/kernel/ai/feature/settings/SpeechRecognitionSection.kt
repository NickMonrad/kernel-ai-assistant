package com.kernel.ai.feature.settings

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.kernel.ai.core.model.availability.ModelCardCompact
import com.kernel.ai.core.ui.ChipStatus
import com.kernel.ai.core.ui.ChoiceCard
import com.kernel.ai.core.ui.ChoiceGroup
import com.kernel.ai.core.ui.ExplanationSheet
import com.kernel.ai.core.ui.InfoTag
import com.kernel.ai.core.ui.InfoTagStyle
import com.kernel.ai.core.ui.SectionLabel
import com.kernel.ai.core.ui.StatusChip
import com.kernel.ai.core.ui.SwitchSubSetting
import com.kernel.ai.core.ui.theme.JandalTheme
import com.kernel.ai.core.ui.theme.KernelAITheme
import com.kernel.ai.core.voice.AndroidNativeWarning
import com.kernel.ai.core.voice.OnlineSpeechCopy
import com.kernel.ai.core.voice.SherpaSttModelSpec
import com.kernel.ai.core.voice.asr4all.Asr4AllSize
import com.kernel.ai.core.voice.VoiceCapabilities
import com.kernel.ai.core.voice.VoiceInputEngine
import com.kernel.ai.core.voice.androidNativeWarning
import com.kernel.ai.core.voice.onlineSpeechToggleVisible

/** Test tag on each engine's [ChoiceCard], formatted as `speech_engine_<enum name>`. */
internal fun speechEngineTestTag(engine: VoiceInputEngine): String = "speech_engine_${engine.name}"

/**
 * Settings › Voice "Speech recognition" section (#1937). Stateless apart from the More sheet, so previews
 * and tests can drive it. The online switch and the Sherpa download card sit inside their engine's card.
 */
@Composable
internal fun SpeechRecognitionSection(
    uiState: VoiceUiState,
    onSelect: (VoiceInputEngine) -> Unit,
    onAllowOnline: (Boolean) -> Unit,
    onDownload: (VoiceInputEngine) -> Unit,
    onCancel: (VoiceInputEngine) -> Unit,
    onDelete: (VoiceInputEngine) -> Unit,
    onAsr4allSize: (Asr4AllSize) -> Unit,
    modifier: Modifier = Modifier,
) {
    var onlineSheetOpen by rememberSaveable { mutableStateOf(false) }
    val zipformerAvailable = VoiceInputEngine.SherpaZipformer in uiState.availableInputEngines
    val zipformerDownloaded = zipformerAvailable &&
        uiState.sherpaSttStates[VoiceInputEngine.SherpaZipformer]?.isDownloaded == true
    val accentOutlinedColors = ButtonDefaults.outlinedButtonColors(contentColor = JandalTheme.colors.accent)
    val accentTextColors = ButtonDefaults.textButtonColors(contentColor = JandalTheme.colors.accent)

    Column(modifier = modifier.fillMaxWidth()) {
        SectionLabel(
            text = "Speech recognition",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        ChoiceGroup(
            count = uiState.availableInputEngines.size,
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            uiState.availableInputEngines.forEachIndexed { index, engine ->
                val selected = uiState.selectedInputEngine == engine
                val sttState = uiState.sherpaSttStates[engine]
                val isReady = !engine.hasDownloadableModel || sttState?.isDownloaded == true
                val nativeWarning = if (engine == VoiceInputEngine.AndroidNative && selected) {
                    androidNativeSettingsWarning(
                        blockingMessage = uiState.androidNativeAvailabilityMessage,
                        capabilities = uiState.androidNativeCapabilities,
                        localeReady = uiState.androidNativeLocaleReady,
                        onlineAllowed = uiState.allowOnlineSpeechFallback,
                        zipformerDownloaded = zipformerDownloaded,
                        zipformerAvailable = zipformerAvailable,
                    )
                } else {
                    null
                }
                val engineWarning = if (engine != VoiceInputEngine.AndroidNative && selected) engine.warning else null
                val showOnline = onlineSpeechToggleVisible(selected = uiState.selectedInputEngine, row = engine)
                val showDownloadCard = engine.hasDownloadableModel && sttState != null && (!sttState.isDownloaded || selected)
                val hasSub = nativeWarning != null || engineWarning != null || showOnline || showDownloadCard
                val tag: (@Composable () -> Unit)? = if (engine == VoiceInputEngine.Auto) {
                    { InfoTag(text = "Recommended", style = InfoTagStyle.Success) }
                } else if (sttState != null && engine.hasDownloadableModel) {
                    {
                        if (sttState.isDownloaded) {
                            StatusChip(status = ChipStatus.On, label = "Downloaded")
                        } else {
                            StatusChip(status = ChipStatus.Off, label = "Not downloaded")
                        }
                    }
                } else {
                    null
                }
                val subContent: (@Composable ColumnScope.() -> Unit)? = if (hasSub) {
                    {
                        if (nativeWarning != null) {
                            VoiceWarningCard(message = nativeWarning)
                            if (VoiceInputEngine.Auto in uiState.availableInputEngines) {
                                TextButton(
                                    onClick = { onSelect(VoiceInputEngine.Auto) },
                                    colors = accentTextColors,
                                ) {
                                    Text("Use Automatic")
                                }
                            }
                        }
                        if (engineWarning != null) {
                            Text(
                                text = engineWarning,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (showOnline) {
                            SwitchSubSetting(
                                title = OnlineSpeechCopy.TITLE,
                                description = OnlineSpeechCopy.LINE,
                                checked = uiState.allowOnlineSpeechFallback,
                                onCheckedChange = onAllowOnline,
                                onMore = { onlineSheetOpen = true },
                                moreDescription = "More about online speech recognition",
                            )
                        }
                        if (showDownloadCard) {
                            if (engine == VoiceInputEngine.Asr4all) {
                                Asr4AllSizeRow(
                                    selected = uiState.asr4allSize,
                                    sizes = uiState.asr4allSizes,
                                    onSelect = onAsr4allSize,
                                )
                            }
                            val spec = SherpaSttModelSpec.forEngine(engine)
                            ModelCardCompact(
                                title = spec?.displayLabel ?: engine.displayName,
                                description = spec?.subtitle.orEmpty(),
                                state = sttState.toModelAvailability(),
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                            when {
                                sttState.isDownloading -> {
                                    OutlinedButton(
                                        modifier = Modifier.padding(vertical = 2.dp),
                                        colors = accentOutlinedColors,
                                        onClick = { onCancel(engine) },
                                    ) {
                                        Text("Cancel")
                                    }
                                }
                                sttState.isDownloaded -> {
                                    OutlinedButton(
                                        modifier = Modifier.padding(vertical = 2.dp),
                                        colors = accentOutlinedColors,
                                        onClick = { onDelete(engine) },
                                    ) {
                                        Text("Delete")
                                    }
                                }
                                sttState.issue != null -> {
                                    OutlinedButton(
                                        modifier = Modifier.padding(vertical = 2.dp),
                                        colors = accentOutlinedColors,
                                        onClick = { onDownload(engine) },
                                    ) {
                                        Text("Retry")
                                    }
                                }
                                else -> {
                                    OutlinedButton(
                                        modifier = Modifier.padding(vertical = 2.dp),
                                        colors = accentOutlinedColors,
                                        onClick = { onDownload(engine) },
                                    ) {
                                        Icon(imageVector = Icons.Outlined.Download, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Download")
                                    }
                                }
                            }
                        }
                    }
                } else {
                    null
                }
                ChoiceCard(
                    title = if (engine == VoiceInputEngine.Auto) "Automatic" else engine.displayName,
                    description = shortDescription(engine),
                    selected = selected,
                    onSelect = { onSelect(engine) },
                    index = index,
                    modifier = Modifier.testTag(speechEngineTestTag(engine)),
                    enabled = isReady,
                    tag = tag,
                    extraLine = if (engine == VoiceInputEngine.AndroidNative) {
                        uiState.androidNativeLanguageName
                    } else {
                        null
                    },
                    subContent = subContent,
                )
            }
        }
    }
    if (onlineSheetOpen) {
        ExplanationSheet(
            title = OnlineSpeechCopy.TITLE,
            body = OnlineSpeechCopy.EXPLANATION,
            onDismiss = { onlineSheetOpen = false },
        )
    }
}

@Composable
private fun Asr4AllSizeRow(
    selected: Asr4AllSize,
    sizes: List<Asr4AllSize>,
    onSelect: (Asr4AllSize) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        sizes.forEach { size ->
            FilterChip(
                selected = size == selected,
                onClick = { onSelect(size) },
                label = { Text(if (size == Asr4AllSize.L) "L · about 101 MB" else "M · about 60 MB") },
            )
        }
    }
}

/** One-line card descriptions from the Voice settings mockup (05-settings-voice.html), keeping each meaning. */
private fun shortDescription(engine: VoiceInputEngine): String = when (engine) {
    VoiceInputEngine.Auto -> "Picks the best recognizer on this phone. Works offline."
    VoiceInputEngine.AndroidNative -> "Your phone's own recognizer. Can suit some accents better."
    VoiceInputEngine.SherpaZipformer -> "Fully offline, shows words as you speak."
    VoiceInputEngine.SherpaSenseVoice -> "Offline, English. Final result only."
    VoiceInputEngine.SherpaWhisper -> "Offline, English only."
    VoiceInputEngine.SherpaParaformer -> "Offline, shows words as you speak."
    VoiceInputEngine.Asr4all -> "Offline, shows words as you speak. Experimental."
}

/**
 * Warning for the Android native engine row, shown only while that engine is selected. A blocking
 * availability message wins; otherwise the capability warning. Online-off is not a warning when
 * Zipformer is downloaded, because a stall then hands off to Zipformer.
 */
private fun androidNativeSettingsWarning(
    blockingMessage: String?,
    capabilities: VoiceCapabilities?,
    localeReady: Boolean,
    onlineAllowed: Boolean,
    zipformerDownloaded: Boolean,
    zipformerAvailable: Boolean,
): String? {
    val fallback = when {
        zipformerDownloaded ->
            "Sherpa-ONNX Zipformer is downloaded and is used if Android speech fails to start or stalls."
        zipformerAvailable ->
            "Download Sherpa-ONNX Zipformer below as an offline fallback."
        else ->
            "This release uses Android speech recognition without a Sherpa fallback."
    }
    val capabilityWarning = capabilities?.let {
        androidNativeWarning(it, localeReady, onlineAllowed || zipformerDownloaded)
    }
    val message = blockingMessage ?: when (capabilityWarning) {
        null -> return null
        AndroidNativeWarning.NoRecognitionService ->
            "Android speech recognition is not available on this device."
        AndroidNativeWarning.NoOnDeviceRecognizer ->
            "No on-device Android speech recognizer. Offline capture cannot work."
        AndroidNativeWarning.LanguagePackMissing ->
            "The on-device language pack for this device is missing."
        AndroidNativeWarning.OnlineOff ->
            "Online speech is off. If the on-device recognizer stays silent, voice input stalls."
    }
    return "$message $fallback"
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

private fun previewUiState(
    selected: VoiceInputEngine = VoiceInputEngine.Auto,
    zipformerDownloaded: Boolean = true,
    senseVoiceDownloaded: Boolean = false,
    nativeMessage: String? = null,
): VoiceUiState = VoiceUiState(
    sherpaVoices = emptyList(),
    selectedInputEngine = selected,
    allowOnlineSpeechFallback = true,
    androidNativeAvailabilityMessage = nativeMessage,
    androidNativeLanguageName = "English (Australia)",
    sherpaSttStates = mapOf(
        VoiceInputEngine.SherpaZipformer to SherpaSttDownloadState(isDownloaded = zipformerDownloaded),
        VoiceInputEngine.SherpaSenseVoice to SherpaSttDownloadState(isDownloaded = senseVoiceDownloaded),
    ),
)

@Composable
private fun SectionPreview(uiState: VoiceUiState) {
    KernelAITheme(darkTheme = isSystemInDarkTheme(), dynamicColor = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            SpeechRecognitionSection(
                uiState = uiState,
                onSelect = {},
                onAllowOnline = {},
                onDownload = {},
                onCancel = {},
                onDelete = {},
                onAsr4allSize = {},
            )
        }
    }
}

/** Android native selected with a blocking message and Zipformer not downloaded: warning card + Use Automatic. */
@PreviewLightDark
@Composable
private fun SpeechRecognitionNativeWarningPreview() = SectionPreview(
    previewUiState(
        selected = VoiceInputEngine.AndroidNative,
        zipformerDownloaded = false,
        nativeMessage = "On-device Android speech recognition is unavailable for the current setup.",
    ),
)

/** SenseVoice selected: its engine warning sits inside its own card. */
@PreviewLightDark
@Composable
private fun SpeechRecognitionEngineWarningPreview() =
    SectionPreview(previewUiState(selected = VoiceInputEngine.SherpaSenseVoice, senseVoiceDownloaded = true))

/** Automatic selected, Zipformer downloaded, SenseVoice not (compose-notes §5). */
@PreviewLightDark
@Composable
private fun SpeechRecognitionSectionPreview() = SectionPreview(previewUiState())

@Preview(name = "Speech recognition, font 2.0", fontScale = 2f, widthDp = 360, heightDp = 640)
@Composable
private fun SpeechRecognitionSectionLargeFontPreview() = SectionPreview(previewUiState())
