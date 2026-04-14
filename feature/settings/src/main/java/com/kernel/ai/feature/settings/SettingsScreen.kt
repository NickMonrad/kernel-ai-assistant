package com.kernel.ai.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kernel.ai.core.inference.download.KernelModel
import kotlinx.coroutines.launch

/** Amber / HuggingFace brand colour — same as in OnboardingScreen. */
private val HfOrange = Color(0xFFFF9D00)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit = {},
    onNavigateToUserProfile: () -> Unit = {},
    onNavigateToMemory: () -> Unit = {},
    onNavigateToModelSettings: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {},
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

    LaunchedEffect(Unit) {
        viewModel.saveSuccess.collect { message ->
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
                        val label = uiState.preferredModel?.displayName ?: "Auto"
                        Text(
                            text = "$label · ${uiState.activeBackend} · ${uiState.activeTier}",
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
                    .clickable {
                        if (uiState.e2bDownloaded) {
                            viewModel.setPreferredModel(KernelModel.GEMMA_4_E2B)
                        } else {
                            scope.launch {
                                snackbarHostState.showSnackbar("E2B not downloaded")
                            }
                        }
                    },
                headlineContent = {
                    Text(
                        text = "E2B — Gemma 4 E-2B",
                        color = if (uiState.e2bDownloaded)
                            MaterialTheme.colorScheme.onSurface
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                supportingContent = {
                    Text(
                        text = if (uiState.e2bDownloaded) "2.4 GB · Efficient, runs on all devices"
                               else "Not downloaded · 2.4 GB",
                        color = if (uiState.e2bDownloaded)
                            MaterialTheme.colorScheme.onSurfaceVariant
                        else
                            MaterialTheme.colorScheme.error,
                    )
                },
                leadingContent = {
                    RadioButton(
                        selected = uiState.preferredModel == KernelModel.GEMMA_4_E2B,
                        onClick = {
                            if (uiState.e2bDownloaded) {
                                viewModel.setPreferredModel(KernelModel.GEMMA_4_E2B)
                            } else {
                                scope.launch {
                                    snackbarHostState.showSnackbar("E2B not downloaded")
                                }
                            }
                        },
                        enabled = uiState.e2bDownloaded,
                    )
                },
                trailingContent = if (!uiState.e2bDownloaded) {
                    {
                        TextButton(onClick = { viewModel.downloadModel(KernelModel.GEMMA_4_E2B) }) {
                            Text("Download")
                        }
                    }
                } else null,
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
                                snackbarHostState.showSnackbar("E4B not downloaded")
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
                               else "Not downloaded · 3.4 GB",
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
                                    snackbarHostState.showSnackbar("E4B not downloaded")
                                }
                            }
                        },
                        enabled = uiState.e4bDownloaded,
                    )
                },
                trailingContent = if (!uiState.e4bDownloaded) {
                    {
                        TextButton(onClick = { viewModel.downloadModel(KernelModel.GEMMA_4_E4B) }) {
                            Text("Download")
                        }
                    }
                } else null,
            )

            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            // ── User Profile ──────────────────────────────────────────────────
            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToUserProfile() },
                headlineContent = { Text("User Profile") },
                supportingContent = { Text("Tell Jandal about yourself") },
                leadingContent = { Icon(Icons.Default.Person, contentDescription = null) },
                trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
            )
            HorizontalDivider()

            // ── Memory ────────────────────────────────────────────────────
            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToMemory() },
                headlineContent = { Text("Memory") },
                supportingContent = { Text("Manage stored memories") },
                leadingContent = { Icon(Icons.Default.Bookmarks, contentDescription = null) },
                trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
            )
            HorizontalDivider()

            // ── Model Settings ────────────────────────────────────────────────────
            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToModelSettings() },
                headlineContent = { Text("Model Settings") },
                supportingContent = { Text("Inference parameters for Gemma 4 models") },
                leadingContent = { Icon(Icons.Default.Tune, contentDescription = null) },
                trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
            )
            HorizontalDivider()

            // ── About ─────────────────────────────────────────────────────────
            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToAbout() },
                headlineContent = { Text("About") },
                supportingContent = { Text("Build info and debug tools") },
                leadingContent = { Icon(Icons.Default.Info, contentDescription = null) },
                trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
            )
            HorizontalDivider()

            Spacer(modifier = Modifier.height(8.dp))

            // ── HuggingFace Account ───────────────────────────────────────────────
            Text(
                text = "HuggingFace Account",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            HuggingFaceAccountRow(
                isAuthenticated = uiState.hfAuthenticated,
                username = uiState.hfUsername,
                onSignIn = { viewModel.startAuth() },
                onSignOut = { viewModel.signOutHuggingFace() },
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
                    supportingContent = { Text("Gemma 4 E-4B · GPU · FLAGSHIP") },
                    leadingContent = { Icon(Icons.Default.SmartToy, contentDescription = null) },
                )
                HorizontalDivider()
            }
        }
    }
}

/**
 * Inline row shown in the Settings screen for the HuggingFace account section.
 *
 * - Not signed in: shows an info label + "Sign in" button to launch the OAuth flow.
 * - Signed in: shows username + Sign Out button.
 */
@Composable
private fun HuggingFaceAccountRow(
    isAuthenticated: Boolean,
    username: String?,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (isAuthenticated) {
        ListItem(
            modifier = modifier.fillMaxWidth(),
            headlineContent = {
                Text(
                    text = if (username != null) "@$username" else "Signed in",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            supportingContent = { Text("Gated models unlocked") },
            leadingContent = {
                Icon(
                    imageVector = Icons.Default.AccountCircle,
                    contentDescription = null,
                    tint = HfOrange,
                )
            },
            trailingContent = {
                TextButton(onClick = onSignOut) {
                    Text("Sign out", color = MaterialTheme.colorScheme.error)
                }
            },
        )
    } else {
        ListItem(
            modifier = modifier.fillMaxWidth(),
            headlineContent = { Text("Not signed in") },
            supportingContent = { Text("Sign in to download gated models (Gemma 4, EmbeddingGemma)") },
            leadingContent = {
                Icon(
                    imageVector = Icons.Default.AccountCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingContent = {
                Button(
                    onClick = onSignIn,
                    colors = ButtonDefaults.buttonColors(containerColor = HfOrange),
                ) {
                    Text("Sign in", color = Color.Black)
                }
            },
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HuggingFaceAccountRowSignedInPreview() {
    MaterialTheme {
        HuggingFaceAccountRow(
            isAuthenticated = true,
            username = "kerneluser",
            onSignIn = {},
            onSignOut = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HuggingFaceAccountRowNotSignedInPreview() {
    MaterialTheme {
        HuggingFaceAccountRow(
            isAuthenticated = false,
            username = null,
            onSignIn = {},
            onSignOut = {},
        )
    }
}


