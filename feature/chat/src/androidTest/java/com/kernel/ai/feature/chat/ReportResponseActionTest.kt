package com.kernel.ai.feature.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import com.kernel.ai.feature.chat.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ReportResponseActionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun completedAssistantReplyOffersReportActionAndReturnsOnlyThatReply() {
        val reply = "Assistant response to report"
        var reported: String? = null
        composeRule.setContent {
            MaterialTheme {
                MessageBubble(
                    message = ChatMessage("assistant-1", ChatMessage.Role.ASSISTANT, reply),
                    onCopy = {},
                    onReport = { reported = it },
                )
            }
        }

        composeRule.onNodeWithText(reply).performTouchInput { longClick() }
        composeRule.onNodeWithText("Report response").performClick()

        composeRule.runOnIdle { assertEquals(reply, reported) }
    }
}
