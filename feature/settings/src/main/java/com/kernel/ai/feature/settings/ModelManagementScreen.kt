package com.kernel.ai.feature.settings

import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kernel.ai.core.inference.PersonaMode
import com.kernel.ai.core.inference.download.DownloadState
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.model.availability.ActionReason
import com.kernel.ai.core.model.availability.ModelAvailabilityState
import com.kernel.ai.core.model.availability.ModelRole
import com.kernel.ai.core.model.availability.ModelRoleCopy
import com.kernel.ai.core.model.availability.ModelRoles
import com.kernel.ai.core.model.availability.RoleSectionView
import com.kernel.ai.core.model.availability.StatusSectionView
import com.kernel.ai.core.model.availability.StillNeededCard
import com.kernel.ai.core.model.availability.toAvailability
import com.kernel.ai.core.ui.rememberDownloadNotificationGate

/**
 * Index of the first role section in the list. Items before it are the storage card and the
 * Still-needed card. "Scroll to conversation model" lands on the chat brain section.
 */
private const val FIRST_ROLE_ITEM_INDEX = 2

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelManagementScreen(
    onBack: () -> Unit = {},
    onOpenVoiceSettings: () -> Unit = {},
    scrollToConversationModel: Boolean = false,
    viewModel: ModelManagementViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val listState = rememberLazyListState()

    val downloadStates: Map<KernelModel, DownloadState> =
        uiState.models.associate { it.model to it.downloadState }
    val availabilityOf: (KernelModel) -> ModelAvailabilityState = { model ->
        val row = uiState.models.firstOrNull { it.model == model }
        if (row == null) {
            ModelAvailabilityState.NotDisplayed
        } else {
            row.downloadState.toAvailability(
                model = model,
                hfAuth = uiState.hfAuthenticated,
                source = row.downloadSource,
                gated = row.gatedStatus,
            )
        }
    }
    val chatUsing = uiState.roleSections.firstOrNull { it.role == ModelRole.CHAT_BRAIN }?.using

    // Name of a model waiting for delete confirmation. Stored as a name so it survives rotation.
    var pendingDeleteName by rememberSaveable { mutableStateOf<String?>(null) }
    val pendingDelete = pendingDeleteName?.let { name -> KernelModel.entries.firstOrNull { it.name == name } }

    LaunchedEffect(scrollToConversationModel, uiState.roleSections.size) {
        if (scrollToConversationModel && uiState.roleSections.isNotEmpty()) {
            listState.animateScrollToItem(index = FIRST_ROLE_ITEM_INDEX)
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
        val startModelDownload = rememberDownloadNotificationGate { id ->
            id?.let { viewModel.downloadModel(KernelModel.valueOf(it)) }
        }
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

            // ── Still needed ──────────────────────────────────────────────────
            item(key = "still_needed") {
                StillNeededCard(
                    items = uiState.stillNeeded,
                    onDownload = { model -> startModelDownload(model.name) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            // ── Role sections ─────────────────────────────────────────────────
            items(uiState.roleSections, key = { it.role.name }) { section ->
                val isChat = section.role == ModelRole.CHAT_BRAIN
                RoleSectionView(
                    section = section,
                    availabilityOf = availabilityOf,
                    onSelect = if (isChat) {
                        { model -> viewModel.setPreferredModel(model) }
                    } else {
                        null
                    },
                    onAutoSelect = if (isChat) {
                        { viewModel.setPreferredModel(null) }
                    } else {
                        null
                    },
                    onPrimary = { model ->
                        when (val avail = availabilityOf(model)) {
                            ModelAvailabilityState.Ready -> viewModel.updateModel(model)
                            is ModelAvailabilityState.ActionRequired -> when (avail.reason) {
                                is ActionReason.SignInRequired -> viewModel.startAuth()
                                is ActionReason.LicenseRequired ->
                                    model.licenceUrl?.let { url -> openInAppBrowser(context, url) }
                                is ActionReason.DownloadFailed -> viewModel.downloadModel(model)
                                is ActionReason.ApprovalPending -> { /* no action — label is null */ }
                                is ActionReason.AccessApprovalRequired ->
                                    model.licenceUrl?.let { url -> openInAppBrowser(context, url) }
                                is ActionReason.InsufficientStorage -> { /* no direct action */ }
                            }
                            is ModelAvailabilityState.Preparing -> {
                                if (!avail.isAutoQueued) viewModel.cancelDownload(model)
                            }
                            else -> viewModel.downloadModel(model)
                        }
                    },
                    canDelete = { model ->
                        availabilityOf(model) is ModelAvailabilityState.Ready &&
                            !model.isBundled &&
                            model != chatUsing
                    },
                    onDelete = { model ->
                        val tier = uiState.deviceTier
                        if (tier != null && ModelRoles.deletionBreaksApp(model, tier, downloadStates)) {
                            pendingDeleteName = model.name
                        } else {
                            viewModel.deleteModel(model)
                        }
                    },
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                HorizontalDivider()
            }

            // ── Voice sections (read-only; managed in Voice settings) ─────────
            item(key = "speech_recognition") {
                StatusSectionView(
                    title = ModelRoleCopy.STT_SECTION_TITLE,
                    summary = "Managed in Voice settings.",
                    statusText = "Status: ${voiceStatusText(uiState.speechRecognitionStatus)}",
                    actionLabel = ModelRoleCopy.MANAGE_VOICE_ACTION,
                    onAction = onOpenVoiceSettings,
                )
                HorizontalDivider()
            }
            item(key = "voice_output") {
                StatusSectionView(
                    title = ModelRoleCopy.TTS_SECTION_TITLE,
                    summary = "Managed in Voice settings.",
                    statusText = "Status: ${voiceStatusText(uiState.voiceOutputStatus)}",
                    actionLabel = ModelRoleCopy.MANAGE_VOICE_ACTION,
                    onAction = onOpenVoiceSettings,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                HorizontalDivider()
            }

            // ── Personality mode ──────────────────────────────────────────────
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

    pendingDelete?.let { model ->
        AlertDialog(
            onDismissRequest = { pendingDeleteName = null },
            title = { Text(ModelRoleCopy.DELETE_WARNING_TITLE) },
            text = { Text(ModelRoleCopy.deleteWarningBody(model)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteModel(model)
                        pendingDeleteName = null
                    },
                ) { Text(ModelRoleCopy.DELETE_ACTION) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteName = null }) { Text(ModelRoleCopy.CANCEL_ACTION) }
            },
        )
    }
}

private fun voiceStatusText(status: VoiceSectionStatus): String = when (status) {
    VoiceSectionStatus.READY -> ModelRoleCopy.VOICE_READY
    VoiceSectionStatus.DOWNLOADING -> ModelRoleCopy.VOICE_DOWNLOADING
    VoiceSectionStatus.NOT_DOWNLOADED -> ModelRoleCopy.VOICE_NOT_DOWNLOADED
    VoiceSectionStatus.BUILT_IN -> ModelRoleCopy.VOICE_BUILT_IN
    VoiceSectionStatus.OTHER_ENGINE -> ModelRoleCopy.VOICE_OTHER_ENGINE
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

private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1_000_000_000L -> "${"%.1f".format(bytes / 1_000_000_000.0)} GB"
        bytes >= 1_000_000L -> "${"%.0f".format(bytes / 1_000_000.0)} MB"
        bytes >= 1_000L -> "${"%.0f".format(bytes / 1_000.0)} KB"
        else -> "$bytes B"
    }
}

internal fun openInAppBrowser(context: android.content.Context, url: String): Boolean {
    val uri = runCatching { url.toUri() }.getOrNull() ?: return false
    val customTabsIntent = CustomTabsIntent.Builder().build()
    return runCatching {
        customTabsIntent.launchUrl(context, uri)
        true
    }.getOrElse {
        runCatching {
            val fallbackIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallbackIntent)
            true
        }.getOrDefault(false)
    }
}
