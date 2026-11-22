package com.kernel.ai.feature.chat.setup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kernel.ai.core.inference.setup.SetupStep
import com.kernel.ai.core.ui.terms.TERMS_CHECKBOX_LABEL
import com.kernel.ai.core.ui.terms.TERMS_LINK_LABEL
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Welcome step gate (#1998): Continue and Skip stay disabled until the Terms checkbox is ticked. */
class SetupWizardTermsGateTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun continueStaysDisabledUntilTermsAreTicked() {
        var changes = 0
        composeRule.setContent {
            var accepted by remember { mutableStateOf(false) }
            SetupWizardContent(
                state = SetupWizardUiState(loaded = true, step = SetupStep.HOW_IT_WORKS, termsAccepted = accepted),
                onInputSelected = {},
                onOutputSelected = {},
                onChoiceSelected = {},
                makeYours = MakeYoursActions(),
                onPrimary = {},
                onBack = {},
                onSkip = {},
                termsText = TERMS_SAMPLE,
                onTermsAcceptedChanged = {
                    accepted = it
                    changes++
                },
            )
        }

        composeRule.onNodeWithText("Continue").assertIsNotEnabled()
        composeRule.onNodeWithText("Skip setup").assertIsNotEnabled()
        composeRule.onNodeWithText(TERMS_CHECKBOX_LABEL).performClick()
        composeRule.onNodeWithText("Continue").assertIsEnabled()
        composeRule.onNodeWithText("Skip setup").assertIsEnabled()
        assertEquals(1, changes)
    }

    @Test
    fun openingTermsKeepsTheStepAndTheUntickedBox() {
        var changes = 0
        composeRule.setContent {
            SetupWizardContent(
                state = SetupWizardUiState(loaded = true, step = SetupStep.HOW_IT_WORKS, termsAccepted = false),
                onInputSelected = {},
                onOutputSelected = {},
                onChoiceSelected = {},
                makeYours = MakeYoursActions(),
                onPrimary = {},
                onBack = {},
                onSkip = {},
                termsText = TERMS_SAMPLE,
                onTermsAcceptedChanged = { changes++ },
            )
        }

        composeRule.onNodeWithText(TERMS_LINK_LABEL).performClick()
        composeRule.onNodeWithText("Done").performClick()

        composeRule.onNodeWithText("Continue").assertIsNotEnabled()
        composeRule.onNodeWithText("Skip setup").assertIsNotEnabled()
        assertEquals(0, changes)
    }

    private companion object {
        const val TERMS_SAMPLE = "# Terms of Use\n\nSample body for the sheet."
    }
}
