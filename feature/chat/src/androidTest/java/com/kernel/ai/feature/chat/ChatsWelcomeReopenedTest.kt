package com.kernel.ai.feature.chat

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class ChatsWelcomeReopenedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun reopenedGettingStartedHasNoHideOrGotItControls() {
        composeRule.setContent {
            GettingStartedContent(
                download = WelcomeDownload.Ready,
                onStartChatting = {},
                onExamplePrompt = {},
                onDownloadModel = {},
            )
        }
        composeRule.onNodeWithTag("chats_welcome_tab_hints").assertExists()
        composeRule.onNodeWithText("Got it").assertDoesNotExist()
        composeRule.onNodeWithText("Hide welcome").assertDoesNotExist()
    }

    @Test
    fun firstRunWelcomeKeepsHideAndGotItControls() {
        composeRule.setContent {
            ChatsWelcome(
                download = WelcomeDownload.Ready,
                showTabHints = true,
                onStartChatting = {},
                onExamplePrompt = {},
                onDownloadModel = {},
                onDismissTabHints = {},
                onHideWelcome = {},
            )
        }
        composeRule.onNodeWithText("Got it").assertExists()
        composeRule.onNodeWithText("Hide welcome").assertExists()
    }
}
