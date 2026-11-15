package com.kernel.ai.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kernel.ai.core.inference.download.DownloadState
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.model.availability.ConversationModelReadiness
import kotlin.math.roundToInt

/** What the Chats empty state shows. [Loading] renders nothing until preferences are known. */
internal sealed interface ChatsEmptyState {
    data object Loading : ChatsEmptyState

    /** Returning user, or the welcome has been hidden: the original empty state. */
    data object Plain : ChatsEmptyState

    /** First run: the welcome, with the assistant-files status it should explain. */
    data class Welcome(val download: WelcomeDownload) : ChatsEmptyState

    /** First run, but the wizard was left unfinished (Back on its first step): a card reopens it. */
    data object SetupWizardResume : ChatsEmptyState
}

/** Assistant-files status as shown on the welcome. */
internal sealed interface WelcomeDownload {
    /** Assistant can chat now, and no download is pending. */
    data object Ready : WelcomeDownload

    /**
     * Assistant can chat now (the fallback model is installed), and the preferred model is downloading to replace it.
     * [progress] is 0.0–1.0, or null when unknown.
     */
    data class Upgrading(
        val model: KernelModel,
        val progress: Float?,
        /** True when the better model is queued for Wi-Fi: the card says so and offers mobile data. */
        val waitingForWifi: Boolean = false,
    ) : WelcomeDownload

    /** Assistant files are downloading and the assistant cannot chat yet. [progress] is 0.0–1.0, or null when unknown. */
    data class InProgress(
        val model: KernelModel,
        val progress: Float?,
        /** True when the download is queued for Wi-Fi: the card says so and offers mobile data. */
        val waitingForWifi: Boolean = false,
    ) : WelcomeDownload

    /**
     * Nothing is downloading, and the assistant cannot chat until files arrive. Offers a download action.
     * [failed] is true when the last attempt errored, so the card says so and offers a retry.
     */
    data class Waiting(
        val model: KernelModel,
        val failed: Boolean = false,
    ) : WelcomeDownload
}

/**
 * Chooses the Chats empty state.
 *
 * @param welcomeDismissed null while the preference or conversation count is loading; otherwise true when the
 *   welcome is hidden or any conversation exists.
 * @param readiness Current conversation-model readiness.
 * @param downloadStates Per-model download states, used to flag a failed recommended-model download.
 * @param setupWizardPending null until loaded; true while the first-run setup wizard is not finished. The welcome
 *   waits for the wizard, so it never shows underneath it (#1855). Null also covers "not decided yet", so a fresh
 *   install shows neither the welcome nor the Loading state's content.
 * @param setupWizardExited true once the wizard was opened and left without finishing. The list then shows
 *   [ChatsEmptyState.SetupWizardResume] instead of a blank screen.
 */
internal fun chatsEmptyState(
    welcomeDismissed: Boolean?,
    readiness: ConversationModelReadiness,
    downloadStates: Map<KernelModel, DownloadState> = emptyMap(),
    waitingForWifi: Set<KernelModel> = emptySet(),
    setupWizardPending: Boolean? = false,
    setupWizardExited: Boolean = false,
): ChatsEmptyState = when {
    welcomeDismissed == null -> ChatsEmptyState.Loading
    welcomeDismissed -> ChatsEmptyState.Plain
    setupWizardPending == null -> ChatsEmptyState.Loading
    setupWizardPending -> if (setupWizardExited) ChatsEmptyState.SetupWizardResume else ChatsEmptyState.Loading
    else -> ChatsEmptyState.Welcome(welcomeDownloadFor(readiness, downloadStates, waitingForWifi))
}

internal fun welcomeDownloadFor(
    readiness: ConversationModelReadiness,
    downloadStates: Map<KernelModel, DownloadState> = emptyMap(),
    waitingForWifi: Set<KernelModel> = emptySet(),
): WelcomeDownload = when (readiness) {
    is ConversationModelReadiness.Ready,
    is ConversationModelReadiness.FallbackActive -> WelcomeDownload.Ready
    // A download queued for Wi-Fi is still reported as Downloading, so it arrives as Preparing or FallbackPreparing.
    is ConversationModelReadiness.FallbackPreparing -> WelcomeDownload.Upgrading(
        model = readiness.downloadingModel,
        progress = readiness.progress,
        waitingForWifi = readiness.downloadingModel in waitingForWifi,
    )
    is ConversationModelReadiness.Preparing -> WelcomeDownload.InProgress(
        model = readiness.downloadingModel,
        progress = readiness.progress,
        waitingForWifi = readiness.downloadingModel in waitingForWifi,
    )
    is ConversationModelReadiness.ActionRequired -> WelcomeDownload.Waiting(
        model = readiness.recommendedModel,
        failed = downloadStates[readiness.recommendedModel] is DownloadState.Error,
    )
}

/** A tappable prompt on the welcome. [needsAssistantFiles] is false when the quick-intent router handles it. */
internal data class WelcomeExamplePrompt(val text: String, val needsAssistantFiles: Boolean)

internal val welcomeExamplePrompts: List<WelcomeExamplePrompt> = listOf(
    WelcomeExamplePrompt("What's the weather?", needsAssistantFiles = false),
    WelcomeExamplePrompt("Add milk to my shopping list", needsAssistantFiles = false),
    WelcomeExamplePrompt("Set a 5 minute timer", needsAssistantFiles = false),
    WelcomeExamplePrompt("Tell me a kiwi fact", needsAssistantFiles = true),
)

/** Prompts that work in [download]. Until the assistant can chat, only quick-intent prompts are offered. */
internal fun welcomeExamplePromptsFor(download: WelcomeDownload): List<WelcomeExamplePrompt> = when (download) {
    WelcomeDownload.Ready,
    is WelcomeDownload.Upgrading -> welcomeExamplePrompts
    is WelcomeDownload.InProgress,
    is WelcomeDownload.Waiting -> welcomeExamplePrompts.filterNot { it.needsAssistantFiles }
}

/**
 * The Chats FAB column is hidden while the first-run welcome or the wizard resume card shows: their content is
 * centred and spans the full width, so no FAB clearance is needed at any scroll position or font scale. Selection
 * and archive views hide it too.
 */
internal fun showChatsFab(welcomeState: ChatsEmptyState, isInSelectionMode: Boolean, showArchived: Boolean): Boolean =
    !isInSelectionMode &&
        !showArchived &&
        welcomeState !is ChatsEmptyState.Welcome &&
        welcomeState != ChatsEmptyState.SetupWizardResume

@Composable
internal fun ChatsWelcome(
    download: WelcomeDownload,
    showTabHints: Boolean,
    onStartChatting: () -> Unit,
    onExamplePrompt: (String) -> Unit,
    onDownloadModel: (KernelModel) -> Unit,
    onUseMobileData: () -> Unit,
    onDismissTabHints: () -> Unit,
    onHideWelcome: () -> Unit,
    /**
     * Shown on the Getting started screen (from the Chats menu, the download banner, or Settings → About), not as the
     * first-run welcome. That screen has no FAB column to clear, and the hide and "Got it" controls are first run only.
     */
    reopened: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val examples = welcomeExamplePromptsFor(download)
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                start = 24.dp,
                top = 16.dp,
                end = 24.dp,
            )
            .testTag("chats_welcome"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Kia ora, I'm Jandal",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = "I'm a private assistant that runs on this phone.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(
            onClick = onStartChatting,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp)
                .heightIn(min = 56.dp)
                .testTag("chats_welcome_start"),
        ) {
            Text("Start chatting")
        }

        when (download) {
            WelcomeDownload.Ready -> Unit
            is WelcomeDownload.Upgrading ->
                if (download.waitingForWifi) {
                    DownloadCard(
                        title = "Waiting for Wi-Fi",
                        body = "Jandal can chat now. A better version (about ${formatBytesShort(download.model.approxSizeBytes)}) " +
                            "downloads when Wi-Fi is available. Use mobile data changes the setting for all downloads.",
                        progress = null,
                        actionLabel = "Use mobile data",
                        onAction = onUseMobileData,
                    )
                } else {
                    DownloadCard(
                        title = "Getting a better Jandal",
                        body = "Jandal can chat now. A better version (about ${formatBytesShort(download.model.approxSizeBytes)}) " +
                            "is downloading. Keep Wi-Fi on until it finishes.",
                        progress = download.progress,
                    )
                }
            is WelcomeDownload.InProgress ->
                if (download.waitingForWifi) {
                    DownloadCard(
                        title = "Waiting for Wi-Fi",
                        body = "Jandal will download about ${formatBytesShort(download.model.approxSizeBytes)} " +
                            "when Wi-Fi is available. Timers, weather and shopping lists already work.",
                        progress = null,
                        actionLabel = "Use mobile data",
                        onAction = onUseMobileData,
                    )
                } else {
                    DownloadCard(
                        title = "Getting Jandal ready",
                        body = "Downloading about ${formatBytesShort(download.model.approxSizeBytes)}. " +
                            "Keep Wi-Fi on until it finishes. Timers, weather and shopping lists already work.",
                        progress = download.progress,
                    )
                }
            is WelcomeDownload.Waiting -> DownloadCard(
                title = if (download.failed) "Download failed" else "Getting Jandal ready",
                body = if (download.failed) {
                    "Jandal couldn't finish downloading about ${formatBytesShort(download.model.approxSizeBytes)}. " +
                        "Check your connection, then try again. Timers, weather and shopping lists already work."
                } else {
                    "Jandal needs to download about ${formatBytesShort(download.model.approxSizeBytes)} " +
                        "before it can chat. Keep Wi-Fi on. Timers, weather and shopping lists already work."
                },
                progress = null,
                actionLabel = if (download.failed) "Try again" else "Download now",
                onAction = { onDownloadModel(download.model) },
            )
        }

        if (examples.isNotEmpty()) {
            Text(
                text = "Try asking",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp).semantics { heading() },
            )
            examples.forEachIndexed { index, example ->
                OutlinedButton(
                    onClick = { onExamplePrompt(example.text) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .heightIn(min = 48.dp)
                        .testTag("chats_welcome_example_$index"),
                ) {
                    Text(example.text)
                }
            }
        }

        if (showTabHints) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp)
                    .testTag("chats_welcome_tab_hints"),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Where to find things",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.semantics { heading() },
                    )
                    TabHintRow(
                        label = "Actions",
                        hint = "Quick things like timers, reminders and weather, without starting a chat.",
                        prominent = true,
                    )
                    TabHintRow(label = "Chats", hint = "Talk to Jandal in a full conversation.")
                    TabHintRow(label = "Tools", hint = "Lists, notes, clock, conversions and settings, all in one place.")
                    if (!reopened) {
                        TextButton(
                            onClick = onDismissTabHints,
                            modifier = Modifier
                                .align(Alignment.Start)
                                .padding(top = 8.dp)
                                .heightIn(min = 48.dp)
                                .testTag("chats_welcome_tab_hints_dismiss"),
                        ) {
                            Text("Got it")
                        }
                    }
                }
            }
        }

        if (!reopened) {
            TextButton(
                onClick = onHideWelcome,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .heightIn(min = 48.dp)
                    .testTag("chats_welcome_hide"),
            ) {
                Text("Hide welcome")
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Chats list state after the setup wizard was left unfinished: a card that reopens the wizard (#1855). */
@Composable
internal fun SetupWizardResumeCard(onFinishSetup: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .testTag("chats_setup_resume"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Setup isn't finished",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = "Finish setup to choose your voice and how Jandal gets its files.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(
            onClick = onFinishSetup,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp)
                .heightIn(min = 56.dp),
        ) {
            Text("Finish setting up")
        }
    }
}

/**
 * Getting started reopened from the Chats menu or Settings → About. Shows the same welcome content as first run,
 * without the hide and "Got it" controls.
 */
@Composable
internal fun GettingStartedContent(
    download: WelcomeDownload,
    onStartChatting: () -> Unit,
    onExamplePrompt: (String) -> Unit,
    onDownloadModel: (KernelModel) -> Unit,
    onUseMobileData: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ChatsWelcome(
        download = download,
        showTabHints = true,
        onStartChatting = onStartChatting,
        onExamplePrompt = onExamplePrompt,
        onDownloadModel = onDownloadModel,
        onUseMobileData = onUseMobileData,
        onDismissTabHints = {},
        onHideWelcome = {},
        reopened = true,
        modifier = modifier,
    )
}

@Composable
private fun DownloadCard(
    title: String,
    body: String,
    progress: Float?,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp)
            .testTag("chats_welcome_download"),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (progress != null) {
                ConversationDownloadProgress(progress = progress)
            }
            if (actionLabel != null) {
                Button(
                    onClick = onAction,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .heightIn(min = 48.dp)
                        .testTag("chats_welcome_download_action"),
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
private fun TabHintRow(label: String, hint: String, prominent: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label,
            style = if (prominent) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
            color = if (prominent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ── Chats: download banner once the welcome is gone ────────────────────────────────────────────

/**
 * Banner text and progress. [progress] is null when unknown. [waitingModel] is set when the download is queued for
 * Wi-Fi: the banner says so and shows no progress bar.
 */
internal data class DownloadBanner(
    val title: String,
    val progress: Float?,
    val waitingModel: KernelModel? = null,
) {
    /** True when the download is queued for Wi-Fi. */
    val waitingForWifi: Boolean get() = waitingModel != null

    val text: String
        get() = when {
            waitingModel != null -> "Waiting for Wi-Fi to download ${waitingModel.displayName}"
            progress != null -> "$title: ${percentOf(progress)}%"
            else -> "$title…"
        }
}

/** Progress is shown below 100% until the download completes; a value of 1.0 while still downloading reads as 99%. */
private const val MAX_BANNER_PROGRESS = 0.99f

/**
 * Normalises raw download progress for display. Zero, negative, and NaN values mean "not started or unknown" (for
 * example, queued until Wi-Fi), so they become null and the banner shows an indeterminate bar rather than 0%.
 */
internal fun bannerProgress(raw: Float?): Float? =
    raw?.takeIf { !it.isNaN() && it > 0f }?.coerceAtMost(MAX_BANNER_PROGRESS)

private fun percentOf(progress: Float): Int = (progress * 100).roundToInt().coerceIn(1, 99)

/**
 * The Chats banner for [readiness], or null when it should not show: the welcome is still up (it shows progress
 * itself), selection mode is on, the archived view is open, or no conversation model is downloading. A download
 * queued for Wi-Fi is still reported as Downloading, so it arrives as Preparing or FallbackPreparing and shows
 * "Waiting for Wi-Fi to download <model>" instead of progress.
 */
internal fun chatsDownloadBanner(
    readiness: ConversationModelReadiness,
    welcomeState: ChatsEmptyState,
    isInSelectionMode: Boolean,
    showArchived: Boolean,
    waitingForWifi: Set<KernelModel> = emptySet(),
): DownloadBanner? {
    if (welcomeState != ChatsEmptyState.Plain || isInSelectionMode || showArchived) return null
    return when (readiness) {
        is ConversationModelReadiness.FallbackPreparing ->
            downloadBanner("Getting a better Jandal", readiness.downloadingModel, readiness.progress, waitingForWifi)
        is ConversationModelReadiness.Preparing ->
            downloadBanner("Getting Jandal ready", readiness.downloadingModel, readiness.progress, waitingForWifi)
        else -> null
    }
}

private fun downloadBanner(
    title: String,
    model: KernelModel,
    progress: Float?,
    waitingForWifi: Set<KernelModel>,
): DownloadBanner =
    if (model in waitingForWifi) DownloadBanner(title, progress = null, waitingModel = model)
    else DownloadBanner(title, bannerProgress(progress))

@Composable
internal fun ChatsDownloadBanner(banner: DownloadBanner, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("chats_download_banner")
            // One focus stop: TalkBack reads the text once; the bar is decorative (its value is in the text).
            .semantics(mergeDescendants = true) { this.onClick(label = "Open getting started", action = null) },
        onClick = onClick,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(banner.text, style = MaterialTheme.typography.bodyMedium)
            if (!banner.waitingForWifi) {
                val progress = banner.progress
                val barModifier = Modifier.fillMaxWidth().padding(top = 8.dp).clearAndSetSemantics {}
                if (progress != null) {
                    LinearProgressIndicator(progress = progress, modifier = barModifier)
                } else {
                    LinearProgressIndicator(modifier = barModifier)
                }
            }
        }
    }
}

/**
 * Example prompts shown in a short row on Chats until the user has about three conversations.
 */
internal fun showTryAskingChips(
    conversationCount: Int?,
    welcomeState: ChatsEmptyState,
    showArchived: Boolean,
    isInSelectionMode: Boolean,
    searchQuery: String,
): Boolean =
    welcomeState == ChatsEmptyState.Plain &&
        !showArchived &&
        !isInSelectionMode &&
        searchQuery.isBlank() &&
        conversationCount != null && conversationCount < TRY_ASKING_CHIPS_MAX_CONVERSATIONS

internal const val TRY_ASKING_CHIPS_MAX_CONVERSATIONS = 3

@Composable
internal fun TryAskingChips(
    prompts: List<WelcomeExamplePrompt>,
    onPrompt: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth().testTag("chats_try_asking"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(prompts) { prompt ->
            AssistChip(
                onClick = { onPrompt(prompt.text) },
                label = { Text(prompt.text) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}
