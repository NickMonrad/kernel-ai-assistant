package com.kernel.ai.feature.chat.setup

import com.kernel.ai.core.voice.VoiceDefaultsProvisioner
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kernel.ai.core.inference.download.DownloadConsent
import com.kernel.ai.core.inference.download.DownloadNetworkChoice
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.setup.SetupStep
import com.kernel.ai.core.model.availability.ModelRoleCopy
import com.kernel.ai.core.ui.rememberDownloadNotificationGate
import com.kernel.ai.core.voice.SherpaPiperVoice
import com.kernel.ai.core.voice.VoiceInputEngine
import com.kernel.ai.core.voice.VoiceOutputEngine
import com.kernel.ai.feature.chat.formatBytesShort

private const val FINISH_ID = "finish"
private const val SKIP_ID = "skip"

/** Test tag on the scrolling container of the step content (#1903 large-font test). In compact mode it wraps the whole page. */
internal const val SETUP_WIZARD_CONTENT_TAG = "setup_wizard_content"

/** Below this window height the wizard scrolls as one page instead of pinning its buttons (#1903). */
private val COMPACT_HEIGHT = 480.dp

/**
 * First-run setup wizard (#1855). Step 1 explains how Jandal works; step 2 chooses speech input and output;
 * step 3 is download consent. [onExit] leaves without completing (back on the first step); [onDone] runs after
 * finishing or skipping.
 */
@Composable
fun SetupWizardScreen(
    onExit: () -> Unit,
    onDone: () -> Unit,
    viewModel: SetupWizardViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val requestWizardAction = rememberDownloadNotificationGate { id ->
        when (id) {
            FINISH_ID -> viewModel.onContinue(onDone)
            SKIP_ID -> viewModel.onSkip { choice ->
                Toast.makeText(context, skipNote(choice), Toast.LENGTH_LONG).show()
                onDone()
            }
            else -> Unit
        }
    }
    BackHandler(enabled = state.loaded && state.step != SetupStep.HOW_IT_WORKS) {
        viewModel.onBack(onExit)
    }
    if (!state.loaded) return
    SetupWizardContent(
        state = state,
        onInputSelected = viewModel::onInputSelected,
        onOutputSelected = viewModel::onOutputSelected,
        onChoiceSelected = viewModel::onChoiceSelected,
        onPrimary = {
            if (state.step == SetupStep.entries.last()) requestWizardAction(FINISH_ID) else viewModel.onContinue(onDone)
        },
        onBack = { viewModel.onBack(onExit) },
        onSkip = { requestWizardAction(SKIP_ID) },
    )
}

/**
 * Stateless wizard layout (#1903). Portrait: step content scrolls above a pinned button row. Short windows
 * (landscape phones): the whole page scrolls so the buttons keep a 48 dp minimum instead of being squeezed.
 */
@Composable
internal fun SetupWizardContent(
    state: SetupWizardUiState,
    onInputSelected: (VoiceInputEngine) -> Unit,
    onOutputSelected: (VoiceOutputEngine) -> Unit,
    onChoiceSelected: (DownloadNetworkChoice) -> Unit,
    onPrimary: () -> Unit,
    onBack: () -> Unit,
    onSkip: () -> Unit,
) {
    val isLast = state.step == SetupStep.entries.last()
    val scrollState = rememberScrollState()
    BoxWithConstraints(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        if (maxHeight < COMPACT_HEIGHT) {
            // Short windows (landscape phones at large font): a pinned button row cannot keep its 48 dp minimum,
            // so the whole page scrolls and the buttons stay reachable at the end of it.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .testTag(SETUP_WIZARD_CONTENT_TAG),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                StepContent(Modifier, state, onInputSelected, onOutputSelected, onChoiceSelected)
                WizardButtons(isLast, onPrimary, onBack, onSkip)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .testTag(SETUP_WIZARD_CONTENT_TAG),
                ) {
                    StepContent(Modifier, state, onInputSelected, onOutputSelected, onChoiceSelected)
                }
                WizardButtons(isLast, onPrimary, onBack, onSkip)
            }
        }
    }
}

@Composable
private fun StepContent(
    modifier: Modifier,
    state: SetupWizardUiState,
    onInputSelected: (VoiceInputEngine) -> Unit,
    onOutputSelected: (VoiceOutputEngine) -> Unit,
    onChoiceSelected: (DownloadNetworkChoice) -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (state.step) {
            SetupStep.HOW_IT_WORKS -> HowItWorksStep()
            SetupStep.VOICE -> VoiceStep(
                inputOptions = state.inputOptions,
                inputReason = state.inputReason,
                inputEngine = state.inputEngine,
                outputOptions = state.outputOptions,
                outputEngine = state.outputEngine,
                onInputSelected = onInputSelected,
                onOutputSelected = onOutputSelected,
            )
            SetupStep.DOWNLOAD -> DownloadStep(
                plannedModels = state.plannedModels,
                plannedVoices = state.plannedVoices,
                choice = state.choice,
                choiceRecorded = state.choiceRecorded,
                onChoiceSelected = onChoiceSelected,
            )
        }
    }
}

@Composable
private fun WizardButtons(isLast: Boolean, onPrimary: () -> Unit, onBack: () -> Unit, onSkip: () -> Unit) {
    Button(
        onClick = onPrimary,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Text(if (isLast) "Finish" else "Continue")
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = onBack,
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text("Back") }
        TextButton(
            onClick = onSkip,
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text("Skip for now") }
    }
}

/** Download-step size line. Zero bytes left means nothing to download, never "About 0 B" (#1903). */
internal fun downloadSizeLine(totalBytes: Long, choiceRecorded: Boolean): String = when {
    totalBytes <= 0L -> "Nothing left to download. Jandal already has every model and voice it needs on this phone."
    choiceRecorded -> "About ${formatBytesShort(totalBytes)} still to download. " +
        "Your network choice applies to downloads in progress."
    else -> "About ${formatBytesShort(totalBytes)} in total. Nothing downloads until you finish this step."
}

private fun skipNote(choice: DownloadNetworkChoice): String = when (choice) {
    DownloadNetworkChoice.WIFI_ONLY -> "Skipped. Models will download on Wi-Fi only. Change this in Settings."
    DownloadNetworkChoice.MOBILE_DATA_ALLOWED -> "Skipped. Models will download on mobile data too. Change this in Settings."
}

@Composable
private fun HowItWorksStep() {
    Text(
        text = "Kia ora, welcome to Jandal",
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.semantics { heading() },
    )
    Text(
        text = "Jandal is a private assistant that runs on your phone. Your chats and the things you tell it " +
            "stay on this device.",
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        text = "Timers, weather and shopping lists work straight away. Chatting needs model files, " +
            "which Jandal downloads once.",
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun VoiceStep(
    inputOptions: List<VoiceInputEngine>,
    inputReason: String?,
    inputEngine: VoiceInputEngine?,
    outputOptions: List<VoiceOutputEngine>,
    outputEngine: VoiceOutputEngine?,
    onInputSelected: (VoiceInputEngine) -> Unit,
    onOutputSelected: (VoiceOutputEngine) -> Unit,
) {
    Text(
        text = "How should Jandal hear and talk to you?",
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.semantics { heading() },
    )
    Text(
        text = "Pick both now. Nothing downloads until you finish the next step.",
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        text = "When you talk to Jandal",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
    )
    inputReason?.let { reason ->
        Text(text = reason, style = MaterialTheme.typography.bodySmall)
    }
    if (inputEngine == null) {
        Text(text = "Your current speech input stays as it is unless you pick one below.", style = MaterialTheme.typography.bodySmall)
    }
    inputOptions.forEach { engine ->
        ChoiceOption(
            title = inputTitle(engine),
            body = inputBody(engine),
            selected = inputEngine == engine,
            onSelect = { onInputSelected(engine) },
        )
    }
    Text(
        text = "When Jandal talks back",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
    )
    outputOptions.forEach { engine ->
        ChoiceOption(
            title = outputTitle(engine),
            body = outputBody(engine),
            selected = outputEngine == engine,
            onSelect = { onOutputSelected(engine) },
        )
    }
    if (outputEngine == null) {
        Text(text = "Your current speech output stays as it is unless you pick one above.", style = MaterialTheme.typography.bodySmall)
    }
}

private fun inputTitle(engine: VoiceInputEngine): String = when (engine) {
    VoiceInputEngine.AndroidNative -> "Android's own listener"
    else -> "Automatic (recommended)"
}

private fun inputBody(engine: VoiceInputEngine): String = when (engine) {
    VoiceInputEngine.AndroidNative -> "Uses the speech recognition built into your phone."
    else -> "Works offline on this phone."
}

private fun outputTitle(engine: VoiceOutputEngine): String = when (engine) {
    VoiceOutputEngine.AndroidTts -> "Your phone's voice"
    else -> "Cori, natural voice (about ${formatBytesShort(SherpaPiperVoice.CoriHigh.approxDownloadBytes)})"
}

private fun outputBody(engine: VoiceOutputEngine): String = when (engine) {
    VoiceOutputEngine.AndroidTts -> "The voice already on your phone. Nothing to download."
    else -> "Sounds more natural and works offline. It downloads with the other files in the next step. " +
        "May be slower on older phones."
}

@Composable
private fun DownloadStep(
    plannedModels: List<KernelModel>,
    plannedVoices: List<SherpaPiperVoice>,
    choice: DownloadNetworkChoice,
    choiceRecorded: Boolean,
    onChoiceSelected: (DownloadNetworkChoice) -> Unit,
) {
    val totalBytes = DownloadConsent.firstRunEstimateBytes(plannedModels, plannedVoices.sumOf { it.approxDownloadBytes })
    Text(
        text = "Download Jandal's models",
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.semantics { heading() },
    )
    Text(
        text = downloadSizeLine(totalBytes, choiceRecorded),
        style = MaterialTheme.typography.bodyLarge,
    )
    val (zipformer, others) = plannedModels.partition { it in VoiceDefaultsProvisioner.zipformerModels() }
    if (zipformer.isNotEmpty()) {
        Column {
            Text(text = "Speech recognition (Zipformer)", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "Turns your voice into text, offline. ${formatBytesShort(zipformer.sumOf { it.approxSizeBytes })}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    others.forEach { model ->
        Column {
            Text(text = ModelRoleCopy.purpose(model), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = ModelRoleCopy.technicalLine(model),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    plannedVoices.forEach { voice ->
        Column {
            Text(text = "${voice.displayName} natural voice", style = MaterialTheme.typography.bodyLarge)
            Text(text = formatBytesShort(voice.approxDownloadBytes), style = MaterialTheme.typography.bodySmall)
        }
    }

    Text(
        text = "Which network should downloads use?",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 8.dp),
    )
    ChoiceOption(
        title = "Wi-Fi only (recommended)",
        body = "Downloads wait until you are on Wi-Fi.",
        selected = choice == DownloadNetworkChoice.WIFI_ONLY,
        onSelect = { onChoiceSelected(DownloadNetworkChoice.WIFI_ONLY) },
    )
    ChoiceOption(
        title = "Allow mobile data",
        body = "Downloads may use your mobile data plan.",
        selected = choice == DownloadNetworkChoice.MOBILE_DATA_ALLOWED,
        onSelect = { onChoiceSelected(DownloadNetworkChoice.MOBILE_DATA_ALLOWED) },
    )
}

@Composable
private fun ChoiceOption(title: String, body: String, selected: Boolean, onSelect: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null)
            Column(modifier = Modifier.padding(start = 8.dp)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                Text(text = body, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
