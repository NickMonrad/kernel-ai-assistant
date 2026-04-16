package com.kernel.ai.feature.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kernel.ai.feature.chat.model.ChatMessage
import com.kernel.ai.feature.chat.model.ToolCallInfo
import org.junit.Rule
import org.junit.Test

/**
 * Instrumented UI tests for the expandable think bubble in [MessageBubble].
 */
class ChatScreenThinkBubbleTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setContent(message: ChatMessage, showThinkingProcess: Boolean = true) {
        composeTestRule.setContent {
            // MessageBubble is private; replicate the relevant composable tree
            // by invoking the same composables from ChatScreen.kt.
            // We use the internal helper below to render the bubble standalone.
            MessageBubbleTestWrapper(message = message, showThinkingProcess = showThinkingProcess)
        }
    }

    @Test
    fun thinkBubbleVisible_whenThinkingTextPresent() {
        val message = ChatMessage(
            id = "1",
            role = ChatMessage.Role.ASSISTANT,
            content = "Hello",
            thinkingText = "Reasoning about the answer…",
        )
        setContent(message)

        composeTestRule.onNodeWithTag("think_bubble").assertIsDisplayed()
    }

    @Test
    fun thinkBubbleNotVisible_whenThinkingTextNull() {
        val message = ChatMessage(
            id = "2",
            role = ChatMessage.Role.ASSISTANT,
            content = "Hello",
            thinkingText = null,
        )
        setContent(message)

        composeTestRule.onNodeWithTag("think_bubble").assertDoesNotExist()
    }

    @Test
    fun thinkBubbleNotVisible_whenThinkingTextBlank() {
        val message = ChatMessage(
            id = "3",
            role = ChatMessage.Role.ASSISTANT,
            content = "Hello",
            thinkingText = "   ",
        )
        setContent(message)

        composeTestRule.onNodeWithTag("think_bubble").assertDoesNotExist()
    }

    @Test
    fun tapBubble_expandsContent() {
        val thinkText = "Step 1: parse the query"
        val message = ChatMessage(
            id = "4",
            role = ChatMessage.Role.ASSISTANT,
            content = "Result",
            thinkingText = thinkText,
        )
        setContent(message)

        // Initially content is collapsed
        composeTestRule.onNodeWithTag("think_bubble_content").assertDoesNotExist()

        // Tap the "Thinking…" label to expand
        composeTestRule.onNodeWithText("Thinking…").performClick()

        // Content should now be visible
        composeTestRule.onNodeWithTag("think_bubble_content").assertIsDisplayed()
    }

    @Test
    fun tapBubbleTwice_collapsesContent() {
        val thinkText = "Step 1: parse the query"
        val message = ChatMessage(
            id = "5",
            role = ChatMessage.Role.ASSISTANT,
            content = "Result",
            thinkingText = thinkText,
        )
        setContent(message)

        // Expand
        composeTestRule.onNodeWithText("Thinking…").performClick()
        composeTestRule.onNodeWithTag("think_bubble_content").assertIsDisplayed()

        // Collapse
        composeTestRule.onNodeWithText("Thinking…").performClick()
        composeTestRule.onNodeWithTag("think_bubble_content").assertDoesNotExist()
    }

    @Test
    fun thinkBubbleContent_matchesThinkingText() {
        val thinkText = "I should look up the weather API"
        val message = ChatMessage(
            id = "6",
            role = ChatMessage.Role.ASSISTANT,
            content = "The weather is sunny.",
            thinkingText = thinkText,
        )
        setContent(message)

        // Expand to reveal content
        composeTestRule.onNodeWithText("Thinking…").performClick()

        composeTestRule.onNodeWithTag("think_bubble_content")
            .assertIsDisplayed()
            .assertTextEquals(thinkText)
    }
}
