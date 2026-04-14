package com.kernel.ai.feature.settings

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    versionName: String,
    versionCode: Int,
    buildType: String,
    gitSha: String,
    buildTimestamp: String,
    viewModel: AboutViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(uiState.exportState) {
        if (uiState.exportState is ExportState.Ready) {
            val intent = (uiState.exportState as ExportState.Ready).intent
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            viewModel.clearExportState()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
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
            // ── Build ─────────────────────────────────────────────────────────
            Text(
                text = "Build",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            ListItem(
                modifier = Modifier.fillMaxWidth(),
                headlineContent = { Text("Version") },
                supportingContent = { Text("$versionName ($versionCode)") },
            )
            HorizontalDivider()

            ListItem(
                modifier = Modifier.fillMaxWidth(),
                headlineContent = { Text("Build type") },
                supportingContent = { Text(buildType) },
            )
            HorizontalDivider()

            ListItem(
                modifier = Modifier.fillMaxWidth(),
                headlineContent = { Text("Commit") },
                supportingContent = { Text(gitSha) },
            )
            HorizontalDivider()

            ListItem(
                modifier = Modifier.fillMaxWidth(),
                headlineContent = { Text("Built") },
                supportingContent = { Text(buildTimestamp) },
            )
            HorizontalDivider()

            // ── Logging ───────────────────────────────────────────────────────
            Text(
                text = "Logging",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            ListItem(
                modifier = Modifier.fillMaxWidth(),
                headlineContent = { Text("Verbose logging") },
                supportingContent = { Text("Log detailed debug output") },
                trailingContent = {
                    Switch(
                        checked = uiState.verboseLogging,
                        onCheckedChange = { viewModel.setVerboseLogging(it) },
                    )
                },
            )
            HorizontalDivider()

            ListItem(
                modifier = Modifier.fillMaxWidth(),
                headlineContent = { Text("Export logs") },
                supportingContent = { Text("Share recent logcat output as a text file") },
                trailingContent = {
                    val isLoading = uiState.exportState is ExportState.Loading
                    Button(
                        onClick = { if (!isLoading) viewModel.exportLogs() },
                        enabled = !isLoading,
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(horizontal = 8.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text("Export")
                        }
                    }
                },
            )

            if (uiState.exportState is ExportState.Error) {
                Text(
                    text = "Error: ${(uiState.exportState as ExportState.Error).message}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
    }
}
