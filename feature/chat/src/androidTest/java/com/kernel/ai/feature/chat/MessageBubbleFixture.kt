package com.kernel.ai.feature.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.kernel.ai.feature.chat.model.ChatMessage

/** Renders the production message bubble within the app theme for isolated UI assertions. */
@Composable
fun MessageBubbleFixture(
    message: ChatMessage,
    showThinkingProcess: Boolean = true,
) {
    MaterialTheme {
        MessageBubble(
            message = message,
            onCopy = {},
            onReport = {},
            showThinkingProcess = showThinkingProcess,
        )
    }
}
