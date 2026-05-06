package com.kernel.ai.feature.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.kernel.ai.core.skills.slot.PendingSlotRequest
import com.kernel.ai.core.skills.slot.SlotSpec
import org.junit.Rule
import org.junit.Test

class ActionsSlotFillBottomSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `advancing to the next slot recreates the bottom sheet state`() {
        val pendingSlot: MutableState<ActionsViewModel.PendingSlotState?> = mutableStateOf(contactSlot())
        setContent(pendingSlot)

        composeTestRule.onNodeWithText("Who would you like to email?").assertIsDisplayed()
        composeTestRule.onNodeWithTag("slot_reply_input").performTextInput("Nick")
        composeTestRule.onNodeWithTag("slot_reply_submit_button").performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule
                .onAllNodesWithText("What's the subject of your email to Nick?")
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithText("What's the subject of your email to Nick?").assertIsDisplayed()
    }

    private fun setContent(pendingSlot: MutableState<ActionsViewModel.PendingSlotState?>) {
        composeTestRule.setContent {
            MaterialTheme {
                pendingSlot.value?.let { slot ->
                    PendingSlotBottomSheet(
                        slot = slot,
                        uiState = ActionsViewModel.UiState.Idle,
                        voiceCaptureState = ActionsViewModel.VoiceCaptureState.Idle,
                        autoVoiceReplyArmed = false,
                        onDismiss = { pendingSlot.value = null },
                        onSubmit = onSubmit@{ reply ->
                            val current = pendingSlot.value ?: return@onSubmit
                            pendingSlot.value = when (current.request.missingSlot.name) {
                                "contact" -> subjectSlot(reply)
                                "subject" -> bodySlot(current.request.existingParams + ("subject" to reply))
                                else -> null
                            }
                        },
                        onVoiceReply = {},
                        onStopVoiceReply = {},
                    )
                }
            }
        }
    }

    private fun contactSlot() = ActionsViewModel.PendingSlotState(
        request = PendingSlotRequest(
            intentName = "send_email",
            existingParams = emptyMap(),
            missingSlot = SlotSpec(
                name = "contact",
                promptTemplate = "Who would you like to email?",
            ),
        ),
        originalQuery = "email my wife",
        inputMode = InputMode.Text,
    )

    private fun subjectSlot(contact: String) = ActionsViewModel.PendingSlotState(
        request = PendingSlotRequest(
            intentName = "send_email",
            existingParams = mapOf("contact" to contact),
            missingSlot = SlotSpec(
                name = "subject",
                promptTemplate = "What's the subject of your email to {contact}?",
            ),
        ),
        originalQuery = "email my wife",
        inputMode = InputMode.Text,
    )

    private fun bodySlot(params: Map<String, String>) = ActionsViewModel.PendingSlotState(
        request = PendingSlotRequest(
            intentName = "send_email",
            existingParams = params,
            missingSlot = SlotSpec(
                name = "body",
                promptTemplate = "What would you like the email to say?",
            ),
        ),
        originalQuery = "email my wife",
        inputMode = InputMode.Text,
    )
}
