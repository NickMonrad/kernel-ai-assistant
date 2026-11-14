package com.kernel.ai.feature.chat.setup

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
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
import com.kernel.ai.feature.chat.formatBytesShort

private const val FINISH_ID = "finish"
private const val SKIP_ID = "skip"

/** Test tag on the scrolling step content (#1903 large-font test). */
internal const val SETUP_WIZARD_CONTENT_TAG = "setup_wizard_content"

/**
 * First-run setup wizard (#1855, part 1). Step 1 explains how Jandal works; step 2 is download consent.
 * [onExit] leaves without completing (back on the first step); [onDone] runs after finishing or skipping.
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
        onChoiceSelected = viewModel::onChoiceSelected,
        onPrimary = {
            if (state.step == SetupStep.entries.last()) requestWizardAction(FINISH_ID) else viewModel.onContinue(onDone)
        },
        onBack = { viewModel.onBack(onExit) },
        onSkip = { requestWizardAction(SKIP_ID) },
    )
}

/**
 * Stateless wizard layout (#1903). Step content scrolls above a pinned button row, so Continue/Finish,
 * Back and Skip stay reachable at any font scale and in landscape.
 */
@Composable
internal fun SetupWizardContent(
    state: SetupWizardUiState,
    onChoiceSelected: (DownloadNetworkChoice) -> Unit,
    onPrimary: () -> Unit,
    onBack: () -> Unit,
    onSkip: () -> Unit,
) {
    val isLast = state.step == SetupStep.entries.last()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .testTag(SETUP_WIZARD_CONTENT_TAG),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (state.step) {
                SetupStep.HOW_IT_WORKS -> HowItWorksStep()
                SetupStep.DOWNLOAD -> DownloadStep(
                    plannedModels = state.plannedModels,
                    plannedVoices = state.plannedVoices,
                    choice = state.choice,
                    choiceRecorded = state.choiceRecorded,
                    onChoiceSelected = onChoiceSelected,
                )
            }
        }
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
    plannedModels.forEach { model ->
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
            Text(text = "${voice.displayName} voice (Piper)", style = MaterialTheme.typography.bodyLarge)
            Text(text = formatBytesShort(voice.approxDownloadBytes), style = MaterialTheme.typography.bodySmall)
        }
    }

    Text(
        text = "Which network should downloads use?",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 8.dp),
    )
    NetworkOption(
        title = "Wi-Fi only (recommended)",
        body = "Downloads wait until you are on Wi-Fi.",
        selected = choice == DownloadNetworkChoice.WIFI_ONLY,
        onSelect = { onChoiceSelected(DownloadNetworkChoice.WIFI_ONLY) },
    )
    NetworkOption(
        title = "Allow mobile data",
        body = "Downloads may use your mobile data plan.",
        selected = choice == DownloadNetworkChoice.MOBILE_DATA_ALLOWED,
        onSelect = { onChoiceSelected(DownloadNetworkChoice.MOBILE_DATA_ALLOWED) },
    )
}

@Composable
private fun NetworkOption(title: String, body: String, selected: Boolean, onSelect: () -> Unit) {
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
