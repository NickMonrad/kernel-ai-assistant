package com.kernel.ai.feature.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kernel.ai.core.inference.PersonaMode
import com.kernel.ai.core.inference.download.DownloadState
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.model.availability.ModelCard
import com.kernel.ai.core.model.availability.ModelAvailabilityState
import com.kernel.ai.core.model.availability.toAvailability
import com.kernel.ai.core.model.availability.ConversationModelReadiness


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelManagementScreen(
    onBack: () -> Unit = {},
    scrollToConversationModel: Boolean = false,
    viewModel: ModelManagementViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val listState = rememberLazyListState()

    // Scroll to "Conversation model" section when requested (e.g. from Settings "Preferred model" item).
    val visibleModelCount = uiState.models.size
    LaunchedEffect(scrollToConversationModel, visibleModelCount) {
        if (scrollToConversationModel && visibleModelCount > 0) {
            listState.animateScrollToItem(index = 2 + visibleModelCount)
        }
    }

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
            state = listState,
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
            // ── Conversation-model readiness banner ───────────────────────────
            item(key = "readiness_banner") {
                val readiness = uiState.conversationReadiness
                if (readiness != null) {
                    ConversationReadinessBanner(
                        readiness = readiness,
                        onDownload = { model -> viewModel.downloadModel(model) },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
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


            // Skip EMBEDDING_GEMMA_300M_SM8550 (already filtered by isDeprecated in VM)
            val visibleModels = uiState.models
            items(visibleModels) { rowState ->
                val availabilityState = rowState.downloadState.toAvailability(
                    model = rowState.model,
                    hfAuth = uiState.hfAuthenticated,
                    source = rowState.downloadSource,
                )
                val canDelete = availabilityState is ModelAvailabilityState.Ready &&
                    !rowState.model.isBundled &&
                    rowState.model != uiState.preferredModel
                val description = buildString {
                    append("%.1f MB".format(rowState.model.approxSizeBytes / 1_000_000f))
                    when (rowState.model) {
                        KernelModel.GEMMA_4_E4B -> {
                            if (uiState.deviceTier == com.kernel.ai.core.inference.hardware.HardwareTier.FLAGSHIP) {
                                append(" · Recommended")
                            }
                        }
                        KernelModel.GEMMA_4_E2B -> {
                            if (uiState.conversationReadiness is ConversationModelReadiness.ActionRequired ||
                                uiState.conversationReadiness is ConversationModelReadiness.FallbackActive
                            ) {
                                append(" · Fallback")
                            }
                        }
                        else -> { /* no label for other models */ }
                    }
                }
                ModelCard(
                    title = rowState.model.displayName,
                    description = description,
                    state = availabilityState,
                    showLock = rowState.model.isGated && rowState.downloadState is DownloadState.NotDownloaded,
                    onPrimaryAction = {
                        when (val state = rowState.downloadState) {
                            is DownloadState.Downloading -> viewModel.cancelDownload(rowState.model)
                            is DownloadState.Downloaded -> viewModel.updateModel(rowState.model)
                            is DownloadState.NotDownloaded -> {
                                if (!uiState.hfAuthenticated && rowState.model.isGated) {
                                    viewModel.startAuth()
                                } else {
                                    viewModel.downloadModel(rowState.model)
                                }
                            }
                            is DownloadState.Error -> {
                                if (state.licenceRequired) {
                                    rowState.model.licenceUrl?.let { url ->
                                        CustomTabsIntent.Builder().build().launchUrl(context, url.toUri())
                                    }
                                } else {
                                    viewModel.downloadModel(rowState.model)
                                }
                            }
                        }
                    },
                    onSecondaryAction = if (canDelete) {
                        { viewModel.deleteModel(rowState.model) }
                    } else {
                        null
                    },
                    secondaryActionLabel = if (canDelete) "Delete" else null,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                Spacer(modifier = Modifier.height(4.dp))
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

            item {
                Text(
                    text = "Personality mode",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            items(PersonaMode.entries) { mode ->
                ListItem(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.setPersonaMode(mode) },
                    headlineContent = { Text(modeTitle(mode)) },
                    supportingContent = { Text(modeDescription(mode)) },
                    leadingContent = {
                        RadioButton(
                            selected = uiState.personaMode == mode,
                            onClick = { viewModel.setPersonaMode(mode) },
                        )
                    },
                )
                HorizontalDivider()
            }

            item {
                Text(
                    text = "Half a Jandal is the default: still Kiwi, but less likely to force slang or extra NZ context into ordinary chat.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

private fun modeTitle(mode: PersonaMode): String = when (mode) {
    PersonaMode.FULL -> "Full Jandal"
    PersonaMode.HALF -> "Half a Jandal"
    PersonaMode.BORING -> "Boring AI Mode"
}

private fun modeDescription(mode: PersonaMode): String = when (mode) {
    PersonaMode.FULL -> "Maximum Kiwi flavour, slang, and cultural references."
    PersonaMode.HALF -> "Default. Keep the Kiwi tone, but only when it naturally fits."
    PersonaMode.BORING -> "Neutral, practical replies with no extra Kiwi flavour."
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
private fun ConversationReadinessBanner(
    readiness: ConversationModelReadiness,
    onDownload: (KernelModel) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (readiness) {
        is ConversationModelReadiness.ActionRequired -> {
            Card(
                modifier = modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Chat needs a conversation model",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Download ${readiness.recommendedModel.displayName} for best performance on this device, " +
                            "or ${readiness.fallbackModel.displayName} as a smaller fallback.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { onDownload(readiness.recommendedModel) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Download ${readiness.recommendedModel.displayName}")
                    }
                    if (readiness.fallbackModel != readiness.recommendedModel) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "or download the smaller fallback:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { onDownload(readiness.fallbackModel) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Download ${readiness.fallbackModel.displayName}")
                        }
                    }
                }
            }
        }
        is ConversationModelReadiness.FallbackActive -> {
            Card(
                modifier = modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f),
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Chat is ready",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "${KernelModel.GEMMA_4_E2B.displayName} is installed. " +
                            "Chat is usable, but downloading ${readiness.recommendedModel.displayName} " +
                            "will give you the best experience on this device.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { onDownload(readiness.recommendedModel) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Download ${readiness.recommendedModel.displayName}")
                    }
                }
            }
        }
        is ConversationModelReadiness.FallbackPreparing -> {
            Card(
                modifier = modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f),
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Chat is ready",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "${readiness.fallbackModel.displayName} is installed. " +
                            "${readiness.downloadingModel.displayName} is downloading — " +
                            "it will be available once finished.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        is ConversationModelReadiness.Preparing -> {
            Card(
                modifier = modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f),
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Preparing conversation model",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "${readiness.downloadingModel.displayName} is downloading. " +
                            "Chat will be ready once it finishes.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        is ConversationModelReadiness.Ready -> {
            // No banner needed when everything is fine
        }
    }
}


private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1_000_000_000L -> "${"%.1f".format(bytes / 1_000_000_000.0)} GB"
        bytes >= 1_000_000L -> "${"%.0f".format(bytes / 1_000_000.0)} MB"
        bytes >= 1_000L -> "${"%.0f".format(bytes / 1_000.0)} KB"
        else -> "$bytes B"
    }
}

internal fun openInAppBrowser(context: android.content.Context, url: String) {
    val uri = url.toUri()
    val customTabsIntent = CustomTabsIntent.Builder().build()
    runCatching {
        customTabsIntent.launchUrl(context, uri)
    }.getOrElse {
        val fallbackIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(fallbackIntent)
    }
}
