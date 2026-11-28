package com.kernel.ai.feature.chat.setup

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kernel.ai.core.inference.setup.SetupStep
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The first setup step labels its leave button Exit, not Back (#2190); later steps keep Back. */
class SetupWizardExitLabelTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun firstStepLabelsTheLeaveButtonExitNotBack() {
        setStep(SetupStep.HOW_IT_WORKS)

        composeRule.onNodeWithText("Exit").assertIsDisplayed()
        composeRule.onAllNodesWithText("Back").assertCountEquals(0)
    }

    @Test
    fun laterStepsKeepBack() {
        setStep(SetupStep.VOICE)

        composeRule.onNodeWithText("Back").assertIsDisplayed()
        composeRule.onAllNodesWithText("Exit").assertCountEquals(0)
    }

    @Test
    fun exitOnTheFirstStepLeavesSetup() {
        var exits = 0
        setStep(SetupStep.HOW_IT_WORKS, onBack = { exits++ })

        composeRule.onNodeWithText("Exit").performClick()

        assertEquals(1, exits)
    }

    private fun setStep(step: SetupStep, onBack: () -> Unit = {}) {
        composeRule.setContent {
            SetupWizardContent(
                state = SetupWizardUiState(loaded = true, step = step, termsAccepted = true),
                onInputSelected = {},
                onOutputSelected = {},
                onChoiceSelected = {},
                makeYours = MakeYoursActions(),
                onPrimary = {},
                onBack = onBack,
                onSkip = {},
            )
        }
    }
}
