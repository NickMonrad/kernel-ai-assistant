package com.kernel.ai.feature.chat.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.Role
import androidx.hilt.navigation.compose.hiltViewModel
import com.kernel.ai.core.inference.download.DownloadNetworkChoice
import com.kernel.ai.core.inference.setup.SetupStep
import com.kernel.ai.core.model.availability.ModelRoleCopy
import com.kernel.ai.feature.chat.formatBytesShort
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import com.kernel.ai.core.ui.rememberDownloadNotificationGate

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
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val requestDownloadStart = rememberDownloadNotificationGate()
    if (!state.loaded) return

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (state.step) {
            SetupStep.HOW_IT_WORKS -> HowItWorksStep()
            SetupStep.DOWNLOAD -> DownloadStep(
                plannedModels = state.plannedModels,
                choice = state.choice,
                onChoiceSelected = viewModel::onChoiceSelected,
            )
        }

        val isLast = state.step == SetupStep.entries.last()
        Button(
            onClick = {
                if (isLast) requestDownloadStart { viewModel.onContinue(onDone) } else viewModel.onContinue(onDone)
            },
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
                onClick = { viewModel.onBack(onExit) },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Back") }
            TextButton(
                onClick = {
                    Toast.makeText(context, SKIP_NOTE, Toast.LENGTH_LONG).show()
                    viewModel.onSkip(onDone)
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Skip for now") }
        }
    }
}

private const val SKIP_NOTE = "Skipped. Models will download on Wi-Fi only. Change this in Settings."

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
    plannedModels: List<com.kernel.ai.core.inference.download.KernelModel>,
    choice: DownloadNetworkChoice,
    onChoiceSelected: (DownloadNetworkChoice) -> Unit,
) {
    val totalBytes = plannedModels.sumOf { it.approxSizeBytes }
    Text(
        text = "Download Jandal's models",
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.semantics { heading() },
    )
    Text(
        text = "About ${formatBytesShort(totalBytes)} in total. Nothing downloads until you finish this step.",
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
