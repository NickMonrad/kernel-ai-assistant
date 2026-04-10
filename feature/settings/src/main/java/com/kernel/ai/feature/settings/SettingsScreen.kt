package com.kernel.ai.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kernel.ai.core.inference.download.KernelModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit = {},
    onNavigateToUserProfile: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.saveError.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // ── Preferred Model Info ──────────────────────────────────────────
            if (uiState.activeModelLabel.isNotEmpty()) {
                ListItem(
                    headlineContent = { Text("Preferred model") },
                    supportingContent = {
                        Text(
                            text = "${uiState.activeModelLabel} · ${uiState.activeBackend} · ${uiState.activeTier} (takes effect on next launch)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    leadingContent = {
                        Icon(Icons.Default.SmartToy, contentDescription = null)
                    },
                )
                HorizontalDivider()
            }

            // ── Conversation Model Selection ──────────────────────────────────
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Conversation model",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            // Auto option
            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.setPreferredModel(null) },
                headlineContent = { Text("Auto") },
                supportingContent = { Text("Select best model for your hardware") },
                leadingContent = {
                    RadioButton(
                        selected = uiState.preferredModel == null,
                        onClick = { viewModel.setPreferredModel(null) },
                    )
                },
            )

            // E2B option
            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.setPreferredModel(KernelModel.GEMMA_4_E2B) },
                headlineContent = { Text("E2B — Gemma 4 E-2B") },
                supportingContent = { Text("2.4 GB · Efficient, runs on all devices") },
                leadingContent = {
                    RadioButton(
                        selected = uiState.preferredModel == KernelModel.GEMMA_4_E2B,
                        onClick = { viewModel.setPreferredModel(KernelModel.GEMMA_4_E2B) },
                    )
                },
            )

            // E4B option
            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (uiState.e4bDownloaded) {
                            viewModel.setPreferredModel(KernelModel.GEMMA_4_E4B)
                        } else {
                            scope.launch {
                                snackbarHostState.showSnackbar("E4B not downloaded — using E2B")
                            }
                        }
                    },
                headlineContent = {
                    Text(
                        text = "E4B — Gemma 4 E-4B",
                        color = if (uiState.e4bDownloaded)
                            MaterialTheme.colorScheme.onSurface
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                supportingContent = {
                    Text(
                        text = if (uiState.e4bDownloaded) "3.4 GB · Higher quality, flagship devices"
                               else "Not downloaded",
                        color = if (uiState.e4bDownloaded)
                            MaterialTheme.colorScheme.onSurfaceVariant
                        else
                            MaterialTheme.colorScheme.error,
                    )
                },
                leadingContent = {
                    RadioButton(
                        selected = uiState.preferredModel == KernelModel.GEMMA_4_E4B,
                        onClick = {
                            if (uiState.e4bDownloaded) {
                                viewModel.setPreferredModel(KernelModel.GEMMA_4_E4B)
                            } else {
                                scope.launch {
                                    snackbarHostState.showSnackbar("E4B not downloaded — using E2B")
                                }
                            }
                        },
                        enabled = uiState.e4bDownloaded,
                    )
                },
            )

            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            // ── User Profile ──────────────────────────────────────────────────
            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToUserProfile() },
                headlineContent = { Text("User Profile") },
                supportingContent = { Text("Tell Kernel about yourself") },
                leadingContent = { Icon(Icons.Default.Person, contentDescription = null) },
                trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
            )
            HorizontalDivider()
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    MaterialTheme {
        Scaffold(
            topBar = {
                @OptIn(ExperimentalMaterial3Api::class)
                TopAppBar(title = { Text("Settings") })
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                ListItem(
                    headlineContent = { Text("Preferred model") },
                    supportingContent = { Text("Gemma 4 E-4B · GPU · FLAGSHIP (takes effect on next launch)") },
                    leadingContent = { Icon(Icons.Default.SmartToy, contentDescription = null) },
                )
                HorizontalDivider()
            }
        }
    }
}

