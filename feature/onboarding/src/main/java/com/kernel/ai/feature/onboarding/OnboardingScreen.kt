package com.kernel.ai.feature.onboarding

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kernel.ai.core.inference.download.DownloadState
import com.kernel.ai.core.inference.download.KernelModel

/** Amber / HuggingFace brand colour. */
private val HfOrange = Color(0xFFFF9D00)

@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Launcher for the AppAuth Chrome Custom Tab OAuth flow
    val authLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.let { viewModel.handleAuthResponse(it) }
        }
    }

    // Consume one-shot events
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is OnboardingViewModel.OnboardingEvent.AuthError ->
                    snackbarHostState.showSnackbar(event.message)

                is OnboardingViewModel.OnboardingEvent.AuthSuccess ->
                    snackbarHostState.showSnackbar("Signed in to HuggingFace ✓")

                is OnboardingViewModel.OnboardingEvent.GatedModelRequiresAuth ->
                    snackbarHostState.showSnackbar(
                        "\"${event.model.displayName}\" requires a HuggingFace account. Sign in to continue."
                    )
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "Welcome to Kernel AI",
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "On-device AI — private by design.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(32.dp))

            // ── HuggingFace sign-in card ──────────────────────────────────
            HuggingFaceAuthCard(
                isAuthenticated = uiState.isAuthenticated,
                username = uiState.username,
                onSignIn = { authLauncher.launch(viewModel.buildAuthIntent()) },
                onSignOut = { viewModel.signOut() },
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ── Download progress ─────────────────────────────────────────
            val downloadState = uiState.downloadState
            when (downloadState) {
                is DownloadState.NotDownloaded -> {
                    Button(
                        onClick = { viewModel.startDownload(KernelModel.GEMMA_4_E2B) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Download Gemma 4 E-2B (2.4 GB)")
                    }
                }

                is DownloadState.Downloading -> {
                    Text(
                        text = "Downloading model…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    CircularProgressIndicator(
                        progress = { downloadState.progress },
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${(downloadState.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                is DownloadState.Downloaded -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Model ready",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                is DownloadState.Error -> {
                    Text(
                        text = "Download error: ${downloadState.message}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { viewModel.startDownload(KernelModel.GEMMA_4_E2B) }) {
                        Text("Retry")
                    }
                }
            }
        }
    }
}

@Composable
private fun HuggingFaceAuthCard(
    isAuthenticated: Boolean,
    username: String?,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "HuggingFace Account",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (isAuthenticated) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.AccountCircle,
                        contentDescription = null,
                        tint = HfOrange,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (username != null) "Signed in as @$username ✓"
                               else "Signed in ✓",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onSignOut) {
                        Text("Sign out", color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Gated models (Gemma 4, EmbeddingGemma) are unlocked.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = "Sign in to download gated models (Gemma 4, EmbeddingGemma).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onSignIn,
                    colors = ButtonDefaults.buttonColors(containerColor = HfOrange),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Default.AccountCircle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Sign in with HuggingFace", color = Color.Black)
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OnboardingScreenNotSignedInPreview() {
    MaterialTheme {
        HuggingFaceAuthCard(
            isAuthenticated = false,
            username = null,
            onSignIn = {},
            onSignOut = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun OnboardingScreenSignedInPreview() {
    MaterialTheme {
        HuggingFaceAuthCard(
            isAuthenticated = true,
            username = "kerneluser",
            onSignIn = {},
            onSignOut = {},
        )
    }
}

