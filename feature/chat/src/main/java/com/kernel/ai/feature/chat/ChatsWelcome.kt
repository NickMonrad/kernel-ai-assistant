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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kernel.ai.core.inference.download.KernelModel
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
    /** Assistant can chat now, so every example prompt works. */
    data object Ready : WelcomeDownload

    /** Assistant files are downloading. [progress] is 0.0–1.0, or null when unknown. */
    data class InProgress(val model: KernelModel, val progress: Float?) : WelcomeDownload

    /** Nothing is downloading yet, and the assistant cannot chat until files arrive. */
    data class Waiting(val model: KernelModel) : WelcomeDownload
}

/**
 * Chooses the Chats empty state.
 *
 * @param welcomeDismissed null while the preference is loading, otherwise whether the welcome is hidden.
 * @param readiness Current conversation-model readiness.
 */
internal fun chatsEmptyState(
    welcomeDismissed: Boolean?,
    readiness: ConversationModelReadiness,
): ChatsEmptyState = when (welcomeDismissed) {
    null -> ChatsEmptyState.Loading
    true -> ChatsEmptyState.Plain
    false -> ChatsEmptyState.Welcome(welcomeDownloadFor(readiness))
}

internal fun welcomeDownloadFor(readiness: ConversationModelReadiness): WelcomeDownload = when (readiness) {
    is ConversationModelReadiness.Ready,
    is ConversationModelReadiness.FallbackActive -> WelcomeDownload.Ready
    is ConversationModelReadiness.Preparing ->
        WelcomeDownload.InProgress(readiness.downloadingModel, readiness.progress)
    is ConversationModelReadiness.FallbackPreparing ->
        WelcomeDownload.InProgress(readiness.downloadingModel, readiness.progress)
    is ConversationModelReadiness.ActionRequired -> WelcomeDownload.Waiting(readiness.recommendedModel)
}

/** A tappable prompt on the welcome. [needsAssistantFiles] is false when the quick-intent router handles it. */
internal data class WelcomeExamplePrompt(val text: String, val needsAssistantFiles: Boolean)

internal val welcomeExamplePrompts: List<WelcomeExamplePrompt> = listOf(
    WelcomeExamplePrompt("What's the weather?", needsAssistantFiles = false),
    WelcomeExamplePrompt("Add milk to my shopping list", needsAssistantFiles = false),
    WelcomeExamplePrompt("Set a 5 minute timer", needsAssistantFiles = false),
    WelcomeExamplePrompt("Tell me a kiwi fact", needsAssistantFiles = true),
)

/** Prompts that work in [download]. Until the assistant is ready, only quick-intent prompts are offered. */
internal fun welcomeExamplePromptsFor(download: WelcomeDownload): List<WelcomeExamplePrompt> =
    if (download == WelcomeDownload.Ready) welcomeExamplePrompts
    else welcomeExamplePrompts.filterNot { it.needsAssistantFiles }

@Composable
internal fun ChatsWelcome(
    download: WelcomeDownload,
    showTabHints: Boolean,
    onStartChatting: () -> Unit,
    onExamplePrompt: (String) -> Unit,
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
                .heightIn(min = 56.dp)
                .padding(top = 24.dp)
                .testTag("chats_welcome_start"),
        ) {
            Text("Start chatting")
        }

        when (download) {
            WelcomeDownload.Ready -> Unit
            is WelcomeDownload.InProgress -> DownloadCard(
                body = "Downloading about ${formatBytesShort(download.model.approxSizeBytes)}. " +
                    "Keep Wi-Fi on until it finishes. Timers, weather and shopping lists already work.",
                progress = download.progress,
            )
            is WelcomeDownload.Waiting -> DownloadCard(
                body = "Jandal needs to download about ${formatBytesShort(download.model.approxSizeBytes)} " +
                    "before it can chat. Keep Wi-Fi on. Timers, weather and shopping lists already work.",
                progress = null,
            )
        }

        if (examples.isNotEmpty()) {
            Text(
                text = "Try asking",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            )
            examples.forEachIndexed { index, example ->
                OutlinedButton(
                    onClick = { onExamplePrompt(example.text) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(top = 8.dp)
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
                    Text("Where to find things", style = MaterialTheme.typography.titleSmall)
                    TabHintRow(label = "Chats", hint = "talk to Jandal")
                    TabHintRow(label = "Actions", hint = "quick commands, typed or spoken")
                    TabHintRow(label = "Tools", hint = "lists, timers and settings")
                    TextButton(
                        onClick = onDismissTabHints,
                        modifier = Modifier
                            .align(Alignment.Start)
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
                .heightIn(min = 48.dp)
                .padding(top = 8.dp)
                .testTag("chats_welcome_hide"),
        ) {
            Text("Hide welcome")
        }
        Spacer(Modifier.height(88.dp))
    }
}

@Composable
private fun DownloadCard(body: String, progress: Float?) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp)
            .testTag("chats_welcome_download"),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Getting Jandal ready", style = MaterialTheme.typography.titleSmall)
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (progress != null) {
                ConversationDownloadProgress(progress = progress)
            }
        }
    }
}

@Composable
private fun TabHintRow(label: String, hint: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
