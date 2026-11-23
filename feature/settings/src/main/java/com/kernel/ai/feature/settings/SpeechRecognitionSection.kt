package com.kernel.ai.feature.settings

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.kernel.ai.core.inference.terms.TERMS_NOT_ACCEPTED_MESSAGE
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
import com.kernel.ai.core.ui.terms.TermsOfUseLinkWithSheet
import com.kernel.ai.core.ui.theme.JandalTheme
import com.kernel.ai.core.ui.theme.KernelAITheme
import com.kernel.ai.core.ui.terms.TermsAcceptRow
import com.kernel.ai.core.ui.terms.TermsSheet
import com.kernel.ai.core.ui.terms.rememberBundledTerms
import com.kernel.ai.core.voice.AndroidNativeWarning
import com.kernel.ai.core.voice.OnlineSpeechCopy
import com.kernel.ai.core.voice.SherpaSttModelSpec
import com.kernel.ai.core.voice.Asr4AllInstall
import com.kernel.ai.core.voice.asr4all.Asr4AllSize
import com.kernel.ai.core.voice.VoiceCapabilities
import com.kernel.ai.core.voice.VoiceInputEngine
import com.kernel.ai.core.voice.androidNativeWarning
import com.kernel.ai.core.voice.onlineSpeechToggleVisible

/** Test tag on each engine's [ChoiceCard], formatted as `speech_engine_<enum name>`. */
internal fun speechEngineTestTag(engine: VoiceInputEngine): String = "speech_engine_${engine.name}"

/**
 * Automatic's card note while the offline asr4all model is not on disk and not downloading: Automatic cannot use
 * it until it is downloaded from the asr4all row. Null when it is downloaded, downloading, or its state is unknown (#2051).
 */
internal fun automaticOfflineModelNote(asr4all: SherpaSttDownloadState?): String? = when {
    asr4all == null || asr4all.isDownloaded -> null
    asr4all.waitingForWifi -> "The offline speech model is waiting for Wi-Fi. Automatic can use it once it has downloaded."
    asr4all.isDownloading -> null
    else -> "The offline speech model isn't downloaded yet. Download it in asr4all streaming below."
}

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
    onUseMobileData: (VoiceInputEngine) -> Unit,
    onAsr4allSize: (Asr4AllSize) -> Unit,
    onTermsAcceptedChanged: (Boolean) -> Unit = {},
    /** Scrolls the asr4all Terms row into view once it is shown, when Settings › Voice opens from a refusal (#2049). */
    scrollToTermsRow: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var onlineSheetOpen by rememberSaveable { mutableStateOf(false) }
    var termsSheetOpen by rememberSaveable { mutableStateOf(false) }
    var termsRowScrolled by rememberSaveable { mutableStateOf(false) }
    val termsRowRequester = remember { BringIntoViewRequester() }
    val sttStatesLoaded = uiState.sherpaSttStates.isNotEmpty()
    val accentOutlinedColors = ButtonDefaults.outlinedButtonColors(contentColor = JandalTheme.colors.accent)
    val accentTextColors = ButtonDefaults.textButtonColors(contentColor = JandalTheme.colors.accent)
    /** The speech model whose Delete confirmation is open. Null when none is. */
    var pendingDeleteEngine by rememberSaveable { mutableStateOf<VoiceInputEngine?>(null) }

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
                val termsGate = engine != VoiceInputEngine.Asr4all || uiState.termsAccepted == true
                val nativeWarning = if (engine == VoiceInputEngine.AndroidNative && selected) {
                    androidNativeSettingsWarning(
                        blockingMessage = uiState.androidNativeAvailabilityMessage,
                        capabilities = uiState.androidNativeCapabilities,
                        localeReady = uiState.androidNativeLocaleReady,
                        onlineAllowed = uiState.allowOnlineSpeechFallback,
                    )
                } else {
                    null
                }
                val engineWarning = if (engine != VoiceInputEngine.AndroidNative && selected) engine.warning else null
                val automaticNote = if (engine == VoiceInputEngine.Auto) {
                    automaticOfflineModelNote(uiState.sherpaSttStates[VoiceInputEngine.Asr4all])
                } else {
                    null
                }
                val showOnline = onlineSpeechToggleVisible(selected = uiState.selectedInputEngine, row = engine)
                val showDownloadCard = engine.hasDownloadableModel && sttState != null && (!sttState.isDownloaded || selected)
                val termsRowShown = engine == VoiceInputEngine.Asr4all && uiState.termsAccepted != null &&
                    (showDownloadCard || (uiState.selectedInputEngine == VoiceInputEngine.Auto && !uiState.termsAccepted))
                val hasSub = nativeWarning != null || engineWarning != null || automaticNote != null || showOnline ||
                    showDownloadCard || termsRowShown
                val tag: (@Composable () -> Unit)? = if (engine == VoiceInputEngine.Auto) {
                    { InfoTag(text = "Recommended", style = InfoTagStyle.Success) }
                } else if (sttState != null && engine.hasDownloadableModel) {
                    {
                        StatusChip(
                            status = if (sttState.isDownloaded) ChipStatus.On else ChipStatus.Off,
                            label = sttState.statusLabel(),
                        )
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
                        if (automaticNote != null) {
                            Text(
                                text = automaticNote,
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
                        if (termsRowShown) {
                            LaunchedEffect(scrollToTermsRow, sttStatesLoaded) {
                                // Once per back-stack entry, after the download states (and so the rows above the
                                // asr4all card) have loaded and one frame has laid out. Scrolling on the first
                                // composition stopped short: the placeholder state had no download cards above.
                                if (scrollToTermsRow && sttStatesLoaded && !termsRowScrolled) {
                                    withFrameNanos { }
                                    termsRowRequester.bringIntoView()
                                    termsRowScrolled = true
                                }
                            }
                            TermsAcceptRow(
                                accepted = uiState.termsAccepted == true,
                                onAcceptedChange = onTermsAcceptedChanged,
                                onOpenTerms = { termsSheetOpen = true },
                                modifier = Modifier
                                    .padding(vertical = 4.dp)
                                    .bringIntoViewRequester(termsRowRequester),
                            )
                        }
                        if (showDownloadCard) {
                            if (engine == VoiceInputEngine.Asr4all) {
                                Asr4AllSizeRow(
                                    active = uiState.asr4allSize,
                                    pending = uiState.asr4allPendingSize,
                                    sizes = uiState.asr4allSizes,
                                    onSelect = onAsr4allSize,
                                )
                            }
                            val spec = SherpaSttModelSpec.forEngine(engine)
                            ModelCardCompact(
                                title = spec?.displayLabel ?: engine.displayName,
                                description = spec?.subtitle.orEmpty(),
                                state = sttState.toModelAvailability(),
                                waitingForWifi = sttState.waitingForWifi,
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                            if (engine != VoiceInputEngine.Asr4all) {
                                TermsOfUseLinkWithSheet()
                            }
                            if (engine == VoiceInputEngine.Asr4all) {
                                uiState.asr4allSwitchFailure?.let { message ->
                                    Text(
                                        text = message,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(vertical = 4.dp),
                                    )
                                    if (message == TERMS_NOT_ACCEPTED_MESSAGE) {
                                        TermsOfUseLinkWithSheet()
                                    }
                                }
                            }
                            when {
                                sttState.isDownloading -> {
                                    if (sttState.waitingForWifi) {
                                        Text(
                                            text = "Waiting for Wi-Fi. Keep Wi-Fi on, or use mobile data for this speech model only.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Button(
                                            modifier = Modifier.padding(vertical = 2.dp),
                                            onClick = { onUseMobileData(engine) },
                                        ) {
                                            Text("Use mobile data")
                                        }
                                    }
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
                                        onClick = { pendingDeleteEngine = engine },
                                    ) {
                                        Text(
                                            if (engine == VoiceInputEngine.Asr4all) {
                                                "Delete ${deleteTarget(engine, uiState.asr4allSize)}"
                                            } else {
                                                "Delete"
                                            },
                                        )
                                    }
                                }
                                sttState.issue != null -> {
                                    OutlinedButton(
                                        modifier = Modifier.padding(vertical = 2.dp),
                                        colors = accentOutlinedColors,
                                        enabled = termsGate,
                                        onClick = { onDownload(engine) },
                                    ) {
                                        Text("Retry")
                                    }
                                }
                                else -> {
                                    OutlinedButton(
                                        modifier = Modifier.padding(vertical = 2.dp),
                                        colors = accentOutlinedColors,
                                        enabled = termsGate,
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
        if (termsSheetOpen) {
            TermsSheet(termsText = rememberBundledTerms(), onDismiss = { termsSheetOpen = false })
        }
    }
    if (onlineSheetOpen) {
        ExplanationSheet(
            title = OnlineSpeechCopy.TITLE,
            body = OnlineSpeechCopy.EXPLANATION,
            onDismiss = { onlineSheetOpen = false },
        )
    }
    pendingDeleteEngine?.let { engine ->
        DeleteSpeechModelDialog(
            target = deleteTarget(engine, uiState.asr4allSize),
            selectedEngine = uiState.selectedInputEngine == engine,
            onConfirm = {
                pendingDeleteEngine = null
                onDelete(engine)
            },
            onDismiss = { pendingDeleteEngine = null },
        )
    }
}

@Composable
private fun Asr4AllSizeRow(
    active: Asr4AllSize,
    pending: Asr4AllSize?,
    sizes: List<Asr4AllSize>,
    onSelect: (Asr4AllSize) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        sizes.forEach { size ->
            FilterChip(
                selected = size == (pending ?: active),
                onClick = { onSelect(size) },
                label = { Text("${size.name} · ${size.approxDownload()}") },
            )
            Text(
                text = size.sizeNote(active, pending),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 8.dp),
            )
        }
    }
}

private fun Asr4AllSize.approxDownload(): String = if (this == Asr4AllSize.L) "about 101 MB" else "about 60 MB"

/** What the size means in use: the switch state and the trade-off (#2075). */
private fun Asr4AllSize.sizeNote(active: Asr4AllSize, pending: Asr4AllSize?): String {
    val state = when {
        pending == this -> "Switching to $name… Still using ${active.name} until this finishes."
        this == active && pending != null -> "Using now. ${pending.name} replaces it when its download finishes."
        this == active -> "Using now."
        else -> "Downloads $name (${approxDownload()}), then replaces ${active.name}."
    }
    return "$state ${plainDescription()}"
}

private fun Asr4AllSize.plainDescription(): String {
    val trade = when (this) {
        Asr4AllSize.L -> "More accurate. Slower on low-end phones."
        else -> "Faster and smaller. Less accurate."
    }
    return if (this == Asr4AllInstall.defaultSize) "$trade Recommended." else trade
}

/** What Delete removes: the active asr4all size with its download size, or the whole speech model. */
private fun deleteTarget(engine: VoiceInputEngine, asr4allSize: Asr4AllSize): String =
    if (engine == VoiceInputEngine.Asr4all) {
        "${asr4allSize.name} (${asr4allSize.approxDownload()})"
    } else {
        SherpaSttModelSpec.forEngine(engine)?.displayLabel ?: engine.displayName
    }

/** Confirms a delete before files are removed. Says where speech input goes, as the delete does (#2075). */
@Composable
private fun DeleteSpeechModelDialog(
    target: String,
    selectedEngine: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete $target?") },
        text = {
            Text(
                if (selectedEngine) {
                    "The downloaded files are removed from this phone. Speech input goes back to Automatic."
                } else {
                    "The downloaded files are removed from this phone."
                },
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** One-line card descriptions from the Voice settings mockup (05-settings-voice.html), keeping each meaning. */
private fun shortDescription(engine: VoiceInputEngine): String = when (engine) {
    VoiceInputEngine.Auto -> VoiceInputEngine.AUTOMATIC_SUMMARY
    VoiceInputEngine.AndroidNative -> "Your phone's own recognizer. Can suit some accents better."
    VoiceInputEngine.SherpaSenseVoice -> "Offline, English. Final result only."
    VoiceInputEngine.SherpaWhisper -> "Offline, English only."
    VoiceInputEngine.SherpaParaformer -> "Offline, shows words as you speak."
    VoiceInputEngine.Asr4all -> "Offline, shows words as you speak. Experimental."
}

/**
 * Warning for the Android native engine row, shown only while that engine is selected. A blocking
 * availability message wins; otherwise the capability warning.
 */
private fun androidNativeSettingsWarning(
    blockingMessage: String?,
    capabilities: VoiceCapabilities?,
    localeReady: Boolean,
    onlineAllowed: Boolean,
): String? {
    val capabilityWarning = capabilities?.let {
        androidNativeWarning(it, localeReady, onlineAllowed)
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
    return message
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
    senseVoiceDownloaded: Boolean = false,
    nativeMessage: String? = null,
): VoiceUiState = VoiceUiState(
    sherpaVoices = emptyList(),
    selectedInputEngine = selected,
    allowOnlineSpeechFallback = true,
    androidNativeAvailabilityMessage = nativeMessage,
    androidNativeLanguageName = "English (Australia)",
    sherpaSttStates = mapOf(
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
                onUseMobileData = {},
                onAsr4allSize = {},
            )
        }
    }
}

/** Android native selected with a blocking message: warning card + Use Automatic. */
@PreviewLightDark
@Composable
private fun SpeechRecognitionNativeWarningPreview() = SectionPreview(
    previewUiState(
        selected = VoiceInputEngine.AndroidNative,
        senseVoiceDownloaded = false,
        nativeMessage = "On-device Android speech recognition is unavailable for the current setup.",
    ),
)

/** SenseVoice selected: its engine warning sits inside its own card. */
@PreviewLightDark
@Composable
private fun SpeechRecognitionEngineWarningPreview() =
    SectionPreview(previewUiState(selected = VoiceInputEngine.SherpaSenseVoice, senseVoiceDownloaded = true))

/** Automatic selected, SenseVoice not (compose-notes §5). */
@PreviewLightDark
@Composable
private fun SpeechRecognitionSectionPreview() = SectionPreview(previewUiState())

@Preview(name = "Speech recognition, font 2.0", fontScale = 2f, widthDp = 360, heightDp = 640)
@Composable
private fun SpeechRecognitionSectionLargeFontPreview() = SectionPreview(previewUiState())
