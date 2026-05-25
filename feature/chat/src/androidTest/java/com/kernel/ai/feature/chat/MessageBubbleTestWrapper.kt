package com.kernel.ai.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.kernel.ai.feature.chat.model.ChatMessage
import com.kernel.ai.feature.chat.model.ToolCallInfo

/**
 * A standalone composable that mirrors the think-bubble and tool-chip portions of
 * [ChatScreen]'s private `MessageBubble` so that instrumented tests can exercise
 * them without depending on the full chat screen (which requires a ViewModel + Hilt).
 */
@Composable
fun MessageBubbleTestWrapper(
    message: ChatMessage,
    showThinkingProcess: Boolean = true,
) {
    val isUser = message.role == ChatMessage.Role.USER
    val surfacedFallbackLinks = if (!isUser && message.toolCall?.presentation == null && message.toolCall != null) {
        collectAdditionalUrls(
            visibleText = message.content,
            message.toolCall.resultText,
            message.toolCall.requestJson,
        )
    } else {
        emptyList()
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        // Think bubble — mirrors ChatScreen's think-bubble block
        if (showThinkingProcess && !message.thinkingText.isNullOrBlank()) {
            var expanded by rememberSaveable { mutableStateOf(false) }
            Column(modifier = Modifier.padding(bottom = 4.dp).testTag("think_bubble")) {
                Row(
                    modifier = Modifier.clickable { expanded = !expanded },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Thinking…",
                        style = MaterialTheme.typography.labelSmall.copy(fontStyle = FontStyle.Italic),
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Collapse thinking" else "Expand thinking",
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(14.dp),
                    )
                }
                AnimatedVisibility(visible = expanded) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .widthIn(max = 320.dp),
                    ) {
                        Text(
                            text = message.thinkingText!!,
                            style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(8.dp).testTag("think_bubble_content"),
                        )
                    }
                }
            }
        }

        // Tool call chip — mirrors ChatScreen's tool-chip block
        if (!isUser && message.toolCall != null) {
            ToolCallChipTestWrapper(
                toolCall = message.toolCall,
                modifier = Modifier
                    .padding(bottom = 4.dp)
                    .widthIn(max = 300.dp),
            )
        }

        // Minimal bubble placeholder so the layout is non-empty
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(text = message.content)
                if (surfacedFallbackLinks.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    ToolLinkList(urls = surfacedFallbackLinks)
                }
            }
        }
    }
}

@Composable
private fun ToolCallChipTestWrapper(toolCall: ToolCallInfo, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val toolLinks = remember(toolCall.requestJson, toolCall.resultText) {
        collectAdditionalUrls(
            visibleText = "",
            toolCall.resultText,
            toolCall.requestJson,
        )
    }
    val clipboardManager = LocalClipboardManager.current
    val hasRichPresentation = toolCall.presentation != null

    Column(modifier = modifier.fillMaxWidth()) {
        // Mirrors ChatScreen.ToolCallChip (#952): AssistChip with proper icons
        AssistChip(
            onClick = { expanded = !expanded },
            leadingIcon = {
                Icon(
                    imageVector = if (toolCall.isSuccess) {
                        androidx.compose.material.icons.outlined.CheckCircle
                    } else {
                        androidx.compose.material.icons.outlined.ErrorOutline
                    },
                    contentDescription = if (toolCall.isSuccess) "Tool succeeded" else "Tool failed",
                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                    tint = if (toolCall.isSuccess) {
                        Color(0xFF4CAF50)
                    } else {
                        Color(0xFFF44336)
                    },
                )
            },
            label = { Text(toolCall.skillName, style = MaterialTheme.typography.labelMedium) },
            trailingIcon = {
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier.size(16.dp),
                )
            },
            modifier = Modifier.testTag("tool_chip"),
        )

        // Expanded: rich presentation or raw text fallback
        AnimatedVisibility(visible = expanded) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (hasRichPresentation) {
                        ToolPresentationContent(
                            presentation = toolCall.presentation!!,
                            compact = true,
                        )
                    } else {
                        Text(
                            text = "Request: ${toolCall.requestJson}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Result: ${toolCall.resultText}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (toolLinks.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        ToolLinkList(urls = toolLinks)
                    }

                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        AssistChip(
                            onClick = {
                                val text = "[Tool: ${toolCall.skillName}]\n" +
                                    "Request: ${toolCall.requestJson}\n" +
                                    "Result: ${toolCall.resultText}"
                                clipboardManager.setText(AnnotatedString(text))
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            label = { Text("Copy") },
                        )
                    }
                }
            }
        }
    }
}
