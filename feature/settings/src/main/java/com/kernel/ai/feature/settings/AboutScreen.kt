package com.kernel.ai.feature.settings

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onNavigateToLegalPrivacy: () -> Unit,
    onNavigateToGettingStarted: () -> Unit,
    versionName: String,
    versionCode: Int,
    buildType: String,
    gitSha: String,
    buildTimestamp: String,
    viewModel: AboutViewModel = hiltViewModel(),
) {

    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showSupportSheet by rememberSaveable { mutableStateOf(false) }
    val buildInfo = DiagnosticBuildInfo(
        versionName = versionName,
        versionCode = versionCode,
        buildType = buildType,
        gitSha = gitSha,
        buildTimestamp = buildTimestamp,
    )

    // Launch only while resumed: a result that became ready in the background waits for the screen.
    LifecycleResumeEffect(uiState.exportState) {
        val state = uiState.exportState
        if (state is ExportState.Ready) {
            context.startActivity(state.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            viewModel.clearExportState()
        }
        onPauseOrDispose {}
    }

    // Launch only while resumed: a result that became ready in the background waits for the screen.
    LifecycleResumeEffect(uiState.supportEmailState) {
        val state = uiState.supportEmailState
        if (state is ExportState.Ready) {
            // User-mediated: the chooser opens the user's email app; nothing is sent from here.
            context.startActivity(state.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            viewModel.clearSupportEmailState()
            showSupportSheet = false
        }
        onPauseOrDispose {}
    }

    if (showSupportSheet) {
        EmailSupportSheet(
            isPreparing = uiState.supportEmailState is ExportState.Loading,
            errorMessage = (uiState.supportEmailState as? ExportState.Error)?.message,
            onSend = { description, includeUserContent ->
                viewModel.emailSupport(buildInfo, description, includeUserContent)
            },
            onDismiss = {
                showSupportSheet = false
                viewModel.clearSupportEmailState()
            },
        )
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
                supportingContent = { Text("Jandal AI 🩴 · v$versionName ($versionCode)") },
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

            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onNavigateToLegalPrivacy),
                headlineContent = { Text("Legal & privacy") },
                supportingContent = { Text("Privacy policy, terms, and open-source licences") },
                trailingContent = {
                    Icon(Icons.Default.ChevronRight, contentDescription = null)
                },
            )
            HorizontalDivider()

            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onNavigateToGettingStarted),
                headlineContent = { Text("Getting started") },
                supportingContent = { Text("Tips, example prompts and assistant download status") },
                trailingContent = {
                    Icon(Icons.Default.ChevronRight, contentDescription = null)
                },
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
                supportingContent = {
                    Text("Share recent crash information and current logs. Chats and messages are redacted (pattern-based; may be incomplete).")
                },
                trailingContent = {
                    val isLoading = uiState.exportState is ExportState.Loading
                    Button(
                        onClick = {
                            if (!isLoading) {
                                viewModel.exportLogs(buildInfo)
                            }
                        },
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
            HorizontalDivider()

            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = "Open email support") { showSupportSheet = true },
                headlineContent = { Text("Email support") },
                supportingContent = { Text("Tell us what happened; your email app opens with logs attached") },
                trailingContent = {
                    Icon(Icons.Default.ChevronRight, contentDescription = null)
                },
            )
        }
    }
}

/**
 * Email support sheet (#1840): a description, an opt-in for user content (off by default),
 * and Send, which prepares a pre-filled email for the user to review in their email app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmailSupportSheet(
    isPreparing: Boolean,
    errorMessage: String?,
    onSend: (description: String, includeUserContent: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var description by rememberSaveable { mutableStateOf("") }
    var includeUserContent by rememberSaveable { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Email support", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Your email app opens with this message and a log file attached. " +
                    "Nothing is sent until you press Send there.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("What happened?") },
                minLines = 3,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .toggleable(
                        value = includeUserContent,
                        role = Role.Checkbox,
                        onValueChange = { includeUserContent = it },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = includeUserContent, onCheckedChange = null)
                Text(
                    text = "Include my chats and messages (helps us find the problem)",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            if (errorMessage != null) {
                Text(
                    text = "Error: $errorMessage",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Cancel")
                }
                Button(
                    onClick = { if (!isPreparing) onSend(description, includeUserContent) },
                    enabled = !isPreparing,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    if (isPreparing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text("Send")
                    }
                }
            }
        }
    }
}
