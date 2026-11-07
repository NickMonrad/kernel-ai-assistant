package com.kernel.ai.feature.chat

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
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

    @Test
    fun keystrokeBetweenReplacementAndEffectStillEndsInSync() {
        val text = mutableStateOf("")
        val revision = mutableLongStateOf(0L)
        val replacement = mutableStateOf("")
        composeTestRule.setContent {
            var draft by rememberChatComposerDraft(text.value, revision.longValue, replacement.value) { text.value = it }
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
            // The ViewModel publishes a replacement, then a keystroke echo lands before the
            // composer applies it.
            replacement.value = "Smart reply"
            revision.longValue++
            text.value = "typed x"
        }
        composeTestRule.onNodeWithTag("composer").assertTextEquals("Smart reply")
        composeTestRule.runOnIdle { assertEquals("Smart reply", text.value) }
    }

    @Test
    fun typingInTheMiddleKeepsTheCursorThere() {
        val text = mutableStateOf("")
        val revision = mutableLongStateOf(0L)
        lateinit var draftState: MutableState<TextFieldValue>
        composeTestRule.setContent {
            draftState = rememberChatComposerDraft(text.value, revision.longValue)
            BasicTextField(
                value = draftState.value,
                onValueChange = {
                    draftState.value = it
                    text.value = it.text
                },
                modifier = Modifier.testTag("composer"),
            )
        }
        composeTestRule.onNodeWithTag("composer").performTextInput("Give me ideas")
        composeTestRule.runOnIdle {
            draftState.value = draftState.value.copy(selection = TextRange("Give me ".length))
        }
        composeTestRule.onNodeWithTag("composer").performTextInput("three ")
        composeTestRule.runOnIdle { text.value = "Give me ideas" } // stale echo
        composeTestRule.onNodeWithTag("composer").assertTextEquals("Give me three ideas")
        composeTestRule.runOnIdle { assertEquals(TextRange("Give me three ".length), draftState.value.selection) }
    }

    @Test
    fun restoredDraftIsPushedBackToTheViewModel() {
        val restoration = StateRestorationTester(composeTestRule)
        val text = mutableStateOf("")
        restoration.setContent {
            var draft by rememberChatComposerDraft(text.value, 0L, "") { text.value = it }
            BasicTextField(
                value = draft,
                onValueChange = {
                    draft = it
                    text.value = it.text
                },
                modifier = Modifier.testTag("composer"),
            )
        }
        composeTestRule.onNodeWithTag("composer").performTextInput("hello")
        composeTestRule.runOnIdle { text.value = "" } // a fresh ViewModel after process death
        restoration.emulateSavedInstanceStateRestore()
        composeTestRule.onNodeWithTag("composer").assertTextEquals("hello")
        composeTestRule.runOnIdle { assertEquals("hello", text.value) }
    }
}
