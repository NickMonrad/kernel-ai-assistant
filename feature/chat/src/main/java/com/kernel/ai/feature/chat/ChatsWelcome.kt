package com.kernel.ai.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.download.DownloadState
import com.kernel.ai.core.model.availability.ConversationModelReadiness

/** What the Chats empty state shows. [Loading] renders nothing until preferences are known. */
internal sealed interface ChatsEmptyState {
    data object Loading : ChatsEmptyState

    /** Returning user, or the welcome has been hidden: the original empty state. */
    data object Plain : ChatsEmptyState

    /** First run: the welcome, with the assistant-files status it should explain. */
    data class Welcome(val download: WelcomeDownload) : ChatsEmptyState
}

/** Assistant-files status as shown on the welcome. */
internal sealed interface WelcomeDownload {
    /** Assistant can chat now, and no download is pending. */
    data object Ready : WelcomeDownload

    /**
     * Assistant can chat now (the fallback model is installed), and the preferred model is downloading to replace it.
     * [progress] is 0.0–1.0, or null when unknown.
     */
    data class Upgrading(val model: KernelModel, val progress: Float?) : WelcomeDownload

    /** Assistant files are downloading and the assistant cannot chat yet. [progress] is 0.0–1.0, or null when unknown. */
    data class InProgress(val model: KernelModel, val progress: Float?) : WelcomeDownload

    /**
     * Nothing is downloading, and the assistant cannot chat until files arrive. Offers a download action.
     * [failed] is true when the last attempt errored, so the card says so and offers a retry.
     */
    data class Waiting(val model: KernelModel, val failed: Boolean = false) : WelcomeDownload
}

/**
 * Chooses the Chats empty state.
 *
 * @param welcomeDismissed null while the preference or conversation count is loading; otherwise true when the
 *   welcome is hidden or any conversation exists.
 * @param readiness Current conversation-model readiness.
 * @param downloadStates Per-model download states, used to flag a failed recommended-model download.
 */
internal fun chatsEmptyState(
    welcomeDismissed: Boolean?,
    readiness: ConversationModelReadiness,
    downloadStates: Map<KernelModel, DownloadState> = emptyMap(),
): ChatsEmptyState = when (welcomeDismissed) {
    null -> ChatsEmptyState.Loading
    true -> ChatsEmptyState.Plain
    false -> ChatsEmptyState.Welcome(welcomeDownloadFor(readiness, downloadStates))
}

internal fun welcomeDownloadFor(
    readiness: ConversationModelReadiness,
    downloadStates: Map<KernelModel, DownloadState> = emptyMap(),
): WelcomeDownload = when (readiness) {
    is ConversationModelReadiness.Ready,
    is ConversationModelReadiness.FallbackActive -> WelcomeDownload.Ready
    is ConversationModelReadiness.FallbackPreparing ->
        WelcomeDownload.Upgrading(readiness.downloadingModel, readiness.progress)
    is ConversationModelReadiness.Preparing ->
        WelcomeDownload.InProgress(readiness.downloadingModel, readiness.progress)
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

@Composable
internal fun ChatsWelcome(
    download: WelcomeDownload,
    showTabHints: Boolean,
    onStartChatting: () -> Unit,
    onExamplePrompt: (String) -> Unit,
    onDownloadModel: (KernelModel) -> Unit,
    onDismissTabHints: () -> Unit,
    onHideWelcome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val examples = welcomeExamplePromptsFor(download)
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp)
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
            is WelcomeDownload.Upgrading -> DownloadCard(
                title = "Getting a better Jandal",
                body = "Jandal can chat now. A better version of Jandal, about ${formatBytesShort(download.model.approxSizeBytes)}, " +
                    "is downloading. Keep Wi-Fi on until it finishes.",
                progress = download.progress,
            )
            is WelcomeDownload.InProgress -> DownloadCard(
                title = "Getting Jandal ready",
                body = "Downloading about ${formatBytesShort(download.model.approxSizeBytes)}. " +
                    "Keep Wi-Fi on until it finishes. Timers, weather and shopping lists already work.",
                progress = download.progress,
            )
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
                        hint = "Quick things like timers, lists and weather, without starting a chat.",
                        prominent = true,
                    )
                    TabHintRow(label = "Chats", hint = "Talk to Jandal in a full conversation.")
                    TabHintRow(label = "Tools", hint = "Lists, notes, clock, conversions and settings, all in one place.")
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

        TextButton(
            onClick = onHideWelcome,
            modifier = Modifier
                .padding(top = 8.dp)
                .heightIn(min = 48.dp)
                .testTag("chats_welcome_hide"),
        ) {
            Text("Hide welcome")
        }
        Spacer(Modifier.height(88.dp))
    }
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
