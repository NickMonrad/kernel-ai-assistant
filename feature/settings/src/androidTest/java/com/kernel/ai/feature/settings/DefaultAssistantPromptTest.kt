package com.kernel.ai.feature.settings

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kernel.ai.core.ui.permissions.DefaultAssistantPrompt
import org.junit.Rule
import org.junit.Test

/**
 * Instrumented Compose UI test for [DefaultAssistantPrompt].
 *
 * Verifies the dialog rendering, title, body, and action callbacks.
 *
 * OS-boundary notes:
 *   - The "Set as default" CTA triggers [onGrant], which launches the
 *     assistant role setup intent via [ActivityResultContracts.StartActivityForResult].
 *     The actual intent launch is handled in [VoiceScreen] and is not directly
 *     verified at the Compose UI layer — it is covered by the ViewModel unit test
 *     (`refreshAssistantStatus updates isDefaultAssistant to true when role granted`).
 *   - Full grant/revoke automation (toggling the assistant role in Android settings)
 *     is OEM-specific and inherently unstable across Samsung One UI vs AOSP.
 */
class DefaultAssistantPromptTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun showsPromptWithExpectedTitleAndBody() {
        var grantClicked = false
        var cancelClicked = false

        composeTestRule.setContent {
            DefaultAssistantPrompt(
                onGrant = { grantClicked = true },
                onCancel = { cancelClicked = true },
                dialogTestTag = "default_assistant_prompt",
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Set Hey Jandal as default assistant")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "Hey Jandal needs to be set as your default assistant to respond to the wake word."
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText("Set as default").assertIsDisplayed()
        composeTestRule.onNodeWithText("Not now").assertIsDisplayed()
    }

    @Test
    fun grantButtonTriggersOnGrantCallback() {
        var grantClicked = false
        var cancelClicked = false

        composeTestRule.setContent {
            DefaultAssistantPrompt(
                onGrant = { grantClicked = true },
                onCancel = { cancelClicked = true },
                dialogTestTag = "default_assistant_prompt",
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Set as default").performClick()

        assert(grantClicked) { "Expected onGrant to be called" }
        assert(!cancelClicked) { "Expected onCancel to not be called" }
    }

    @Test
    fun cancelButtonTriggersOnCancelCallback() {
        var grantClicked = false
        var cancelClicked = false

        composeTestRule.setContent {
            DefaultAssistantPrompt(
                onGrant = { grantClicked = true },
                onCancel = { cancelClicked = true },
                dialogTestTag = "default_assistant_prompt",
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Not now").performClick()

        assert(cancelClicked) { "Expected onCancel to be called" }
        assert(!grantClicked) { "Expected onGrant to not be called" }
    }
}
