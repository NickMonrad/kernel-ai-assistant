package com.kernel.ai.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Amber / HuggingFace brand colour — same as in OnboardingScreen. */
private val HfOrange = Color(0xFFFF9D00)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit = {},
    onNavigateToUserProfile: () -> Unit = {},
    onNavigateToMemory: () -> Unit = {},
    onNavigateToModelSettings: () -> Unit = {},
    onNavigateToModelManagement: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val uriHandler = LocalUriHandler.current

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
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            // ── Models ────────────────────────────────────────────────────────
            Text(
                text = "Models",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            if (uiState.activeModelLabel.isNotEmpty()) {
                ListItem(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToModelManagement() },
                    headlineContent = { Text("Preferred model") },
                    supportingContent = {
                        val label = uiState.preferredModel?.displayName ?: "Auto"
                        Text(
                            text = "$label · ${uiState.activeBackend} · ${uiState.activeTier}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    leadingContent = { Icon(Icons.Default.SmartToy, contentDescription = null) },
                    trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                )
                HorizontalDivider()
            }

            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToModelManagement() },
                headlineContent = { Text("Model management") },
                supportingContent = { Text("Downloads, storage and model preferences") },
                leadingContent = { Icon(Icons.Default.Download, contentDescription = null) },
                trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
            )
            HorizontalDivider()

            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToModelSettings() },
                headlineContent = { Text("Model settings") },
                supportingContent = { Text("Inference parameters for Gemma 4 models") },
                leadingContent = { Icon(Icons.Default.Tune, contentDescription = null) },
                trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
            )
            HorizontalDivider()

            // HuggingFace account grouped with models — needed to unlock gated HF models
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
                onViewLicence = { uriHandler.openUri("https://huggingface.co/litert-community/embeddinggemma-300m") },
            )
            HorizontalDivider()

            // ── Personal ──────────────────────────────────────────────────────
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Personal",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

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

            // ── App ───────────────────────────────────────────────────────────
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "App",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

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
    onViewLicence: () -> Unit = {},
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
            supportingContent = {
                Column {
                    Text("Gated models unlocked")
                    TextButton(onClick = onViewLicence, contentPadding = PaddingValues(0.dp)) {
                        Text("View licence →", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
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
            supportingContent = {
                Column {
                    Text("Required to download EmbeddingGemma (gated). Accept licence before downloading.")
                    TextButton(onClick = onViewLicence, contentPadding = PaddingValues(0.dp)) {
                        Text("View licence →", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
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


