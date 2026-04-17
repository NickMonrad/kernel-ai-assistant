package com.kernel.ai.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kernel.ai.core.inference.download.DownloadState
import com.kernel.ai.core.inference.download.KernelModel

private val HfOrange = Color(0xFFFF9D00)
private const val EMBEDDING_GEMMA_LICENCE_URL = "https://huggingface.co/litert-community/embeddinggemma-300m"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelManagementScreen(
    onBack: () -> Unit = {},
    viewModel: ModelManagementViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Model Management") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // ── Storage summary ───────────────────────────────────────────────
            item {
                StorageSummaryCard(
                    usedBytes = uiState.totalStorageUsedBytes,
                    freeBytes = uiState.freeSpaceBytes,
                    modifier = Modifier.padding(16.dp),
                )
            }

            // ── HuggingFace account ───────────────────────────────────────────
            item {
                Text(
                    text = "HuggingFace Account",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                HuggingFaceRow(
                    isAuthenticated = uiState.hfAuthenticated,
                    username = uiState.hfUsername,
                    onSignIn = { viewModel.startAuth() },
                    onSignOut = { viewModel.signOut() },
                    onViewLicence = { uriHandler.openUri(EMBEDDING_GEMMA_LICENCE_URL) },
                )
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
            }

            // ── Model rows ────────────────────────────────────────────────────
            item {
                Text(
                    text = "Models",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            // Skip EMBEDDING_GEMMA_300M_SM8550 (disabled variant)
            val visibleModels = uiState.models.filter { it.model != KernelModel.EMBEDDING_GEMMA_300M_SM8550 }
            items(visibleModels) { rowState ->
                ModelRow(
                    rowState = rowState,
                    isAuthenticated = uiState.hfAuthenticated,
                    onDownload = { viewModel.downloadModel(rowState.model) },
                    onCancel = { viewModel.cancelDownload(rowState.model) },
                    onDelete = { viewModel.deleteModel(rowState.model) },
                    onViewLicence = { url -> uriHandler.openUri(url) },
                    onRetry = { viewModel.downloadModel(rowState.model) },
                )
                HorizontalDivider()
            }

            // ── Preferred model section ───────────────────────────────────────
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Conversation model",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            item {
                val e2bState = uiState.models.find { it.model == KernelModel.GEMMA_4_E2B }?.downloadState
                val e4bState = uiState.models.find { it.model == KernelModel.GEMMA_4_E4B }?.downloadState
                val e2bDownloaded = e2bState is DownloadState.Downloaded
                val e4bDownloaded = e4bState is DownloadState.Downloaded

                // Auto
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
                HorizontalDivider()

                // E2B
                ListItem(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (e2bDownloaded) viewModel.setPreferredModel(KernelModel.GEMMA_4_E2B)
                        },
                    headlineContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("E2B — Gemma 4 E-2B")
                            if (!e2bDownloaded) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    "(not downloaded)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    },
                    supportingContent = { Text("2.4 GB · Efficient, runs on all devices") },
                    leadingContent = {
                        RadioButton(
                            selected = uiState.preferredModel == KernelModel.GEMMA_4_E2B,
                            onClick = { if (e2bDownloaded) viewModel.setPreferredModel(KernelModel.GEMMA_4_E2B) },
                            enabled = e2bDownloaded,
                        )
                    },
                )
                HorizontalDivider()

                // E4B
                ListItem(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (e4bDownloaded) viewModel.setPreferredModel(KernelModel.GEMMA_4_E4B)
                        },
                    headlineContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("E4B — Gemma 4 E-4B")
                            if (!e4bDownloaded) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    "(not downloaded)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    },
                    supportingContent = { Text("3.4 GB · Higher quality, flagship devices") },
                    leadingContent = {
                        RadioButton(
                            selected = uiState.preferredModel == KernelModel.GEMMA_4_E4B,
                            onClick = { if (e4bDownloaded) viewModel.setPreferredModel(KernelModel.GEMMA_4_E4B) },
                            enabled = e4bDownloaded,
                        )
                    },
                )
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun StorageSummaryCard(
    usedBytes: Long,
    freeBytes: Long,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = formatBytes(usedBytes),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "used",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = formatBytes(freeBytes),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "free",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HuggingFaceRow(
    isAuthenticated: Boolean,
    username: String?,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onViewLicence: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (isAuthenticated) {
        ListItem(
            modifier = modifier.fillMaxWidth(),
            headlineContent = {
                Text(if (username != null) "@$username" else "Signed in")
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
                Icon(Icons.Default.AccountCircle, contentDescription = null, tint = HfOrange)
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
                Icon(Icons.Default.AccountCircle, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
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

@Composable
private fun ModelRow(
    rowState: ModelRowState,
    isAuthenticated: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onViewLicence: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val model = rowState.model
    val state = rowState.downloadState

    ListItem(
        modifier = modifier.fillMaxWidth(),
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(model.displayName)
                if (model.isGated) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = "Gated",
                        modifier = Modifier.size(14.dp),
                        tint = HfOrange,
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                if (model.isRequired) {
                    SuggestionChip(
                        onClick = {},
                        label = { Text("Required", style = MaterialTheme.typography.labelSmall) },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    )
                } else {
                    SuggestionChip(
                        onClick = {},
                        label = { Text("Optional", style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        },
        supportingContent = {
            Column {
                Text(
                    text = formatBytes(model.approxSizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when (state) {
                    is DownloadState.Downloading -> {
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        val pct = (state.progress * 100).toInt()
                        val mbps = state.bytesPerSecond / 1_000_000.0
                        val etaSec = state.remainingMs / 1000
                        Text(
                            text = buildString {
                                append("$pct%")
                                if (state.bytesPerSecond > 0) append(" · ${"%.1f".format(mbps)} MB/s")
                                if (etaSec > 0) append(" · ${etaSec}s remaining")
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    is DownloadState.Error -> {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    else -> Unit
                }
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (state) {
                    is DownloadState.NotDownloaded -> {
                        val gatedBlocked = model.isGated && !isAuthenticated
                        TextButton(
                            onClick = onDownload,
                            enabled = !gatedBlocked,
                        ) {
                            Text("Download")
                        }
                    }
                    is DownloadState.Downloading -> {
                        TextButton(onClick = onCancel) {
                            Text("Cancel")
                        }
                    }
                    is DownloadState.Downloaded -> {
                        if (model.isBundled) {
                            SuggestionChip(
                                onClick = {},
                                label = { Text("Built-in", style = MaterialTheme.typography.labelSmall) },
                                colors = SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                ),
                            )
                        } else {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = "Downloaded",
                                tint = Color(0xFF4CAF50),
                                modifier = Modifier.size(20.dp),
                            )
                            if (!model.isRequired) {
                                Spacer(modifier = Modifier.width(4.dp))
                                TextButton(onClick = onDelete) {
                                    Text("Delete", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                    is DownloadState.Error -> {
                        Column(horizontalAlignment = Alignment.End) {
                            val licenceUrl = model.licenceUrl
                            if (state.licenceRequired && licenceUrl != null) {
                                TextButton(onClick = { onViewLicence(licenceUrl) }) {
                                    Text("Accept licence")
                                }
                            }
                            TextButton(onClick = onRetry) {
                                Text("Retry")
                            }
                        }
                    }
                }
            }
        },
    )
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1_000_000_000L -> "${"%.1f".format(bytes / 1_000_000_000.0)} GB"
        bytes >= 1_000_000L -> "${"%.0f".format(bytes / 1_000_000.0)} MB"
        bytes >= 1_000L -> "${"%.0f".format(bytes / 1_000.0)} KB"
        else -> "$bytes B"
    }
}
