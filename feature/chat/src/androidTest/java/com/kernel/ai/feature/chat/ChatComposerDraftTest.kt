package com.kernel.ai.feature.chat

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test

class ChatComposerDraftTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun delayedTextEchoDoesNotOverwriteNewerTyping() {
        val text = mutableStateOf("")
        val revision = mutableLongStateOf(0L)
        composeTestRule.setContent {
            var draft by rememberChatComposerDraft(text.value, revision.longValue)
            BasicTextField(
                value = draft,
                onValueChange = {
                    draft = it
                    text.value = it.text
                },
                modifier = Modifier.testTag("composer"),
            )
        }

        composeTestRule.onNodeWithTag("composer").performTextInput("Remember that my favourite ")
        composeTestRule.onNodeWithTag("composer").performTextInput("movies are Alien and Heat")
        composeTestRule.runOnIdle { text.value = "Remember that my favourite" }

        composeTestRule.onNodeWithTag("composer").assertTextEquals("Remember that my favourite movies are Alien and Heat")
    }

    @Test
    fun explicitDraftReplacementsUpdateComposerAndClearIt() {
        val text = mutableStateOf("")
        val revision = mutableLongStateOf(0L)
        composeTestRule.setContent {
            var draft by rememberChatComposerDraft(text.value, revision.longValue)
            BasicTextField(
                value = draft,
                onValueChange = {
                    draft = it
                    text.value = it.text
                },
                modifier = Modifier.testTag("composer"),
            )
        }

        composeTestRule.runOnIdle {
            text.value = "External draft"
            revision.longValue++
        }
        composeTestRule.onNodeWithTag("composer").assertTextEquals("External draft")

        composeTestRule.runOnIdle {
            text.value = "Smart reply"
            revision.longValue++
        }
        composeTestRule.onNodeWithTag("composer").assertTextEquals("Smart reply")

        composeTestRule.runOnIdle {
            text.value = ""
            revision.longValue++
        }
        composeTestRule.onNodeWithTag("composer").assertTextEquals("")
    }
}
