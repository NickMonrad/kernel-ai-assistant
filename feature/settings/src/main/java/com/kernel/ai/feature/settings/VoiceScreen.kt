package com.kernel.ai.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kernel.ai.core.voice.VoiceInputEngine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceScreen(
    onBack: () -> Unit,
    viewModel: VoiceViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

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
                                Text(
                                    text = warning,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    },
                    trailingContent = {
                        RadioButton(
                            selected = uiState.selectedInputEngine == engine,
                            onClick = { viewModel.setVoiceInputEngine(engine) },
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
                        onCheckedChange = { viewModel.setSpokenResponsesEnabled(it) },
                    )
                },
            )
            HorizontalDivider()
        }
    }
}
