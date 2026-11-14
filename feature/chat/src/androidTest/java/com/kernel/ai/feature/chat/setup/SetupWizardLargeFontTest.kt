package com.kernel.ai.feature.chat.setup

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.kernel.ai.core.inference.download.DownloadNetworkChoice
import com.kernel.ai.core.inference.setup.SetupStep
import org.junit.Rule
import org.junit.Test

/**
 * Large-font layout for the setup wizard (#1903). At font scale 2.0 the step content must scroll and the
 * Continue/Finish, Back and Skip buttons must stay on screen in portrait and landscape.
 */
class SetupWizardLargeFontTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun howItWorksButtonsStayVisibleAtLargeFontPortrait() {
        setWizard(step = SetupStep.HOW_IT_WORKS, widthDp = 360, heightDp = 640)

        composeRule.onNodeWithText("Continue").assertIsDisplayed()
        composeRule.onNodeWithText("Back").assertIsDisplayed()
        composeRule.onNodeWithText("Skip for now").assertIsDisplayed()
    }

    @Test
    fun downloadStepScrollsToNetworkChoiceAtLargeFontPortrait() {
        setWizard(step = SetupStep.DOWNLOAD, widthDp = 360, heightDp = 640)

        composeRule.onNodeWithTag(SETUP_WIZARD_CONTENT_TAG)
            .performScrollToNode(hasText("Allow mobile data"))
        composeRule.onNodeWithText("Allow mobile data").assertIsDisplayed()
        composeRule.onNodeWithText("Finish").assertIsDisplayed()
        composeRule.onNodeWithText("Back").assertIsDisplayed()
        composeRule.onNodeWithText("Skip for now").assertIsDisplayed()
    }

    @Test
    fun downloadStepButtonsStayVisibleAtLargeFontLandscape() {
        setWizard(step = SetupStep.DOWNLOAD, widthDp = 640, heightDp = 360)

        composeRule.onNodeWithText("Finish").assertIsDisplayed()
        composeRule.onNodeWithText("Back").assertIsDisplayed()
        composeRule.onNodeWithText("Skip for now").assertIsDisplayed()
    }

    @Test
    fun rerunWithNothingLeftSaysNothingToDownloadNotAboutZeroBytes() {
        setWizard(step = SetupStep.DOWNLOAD, widthDp = 360, heightDp = 640)

        composeRule.onNodeWithText("Nothing left to download", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("About 0 B", substring = true).assertDoesNotExist()
    }

    private fun setWizard(step: SetupStep, widthDp: Int, heightDp: Int) {
        composeRule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale = LARGE_FONT_SCALE)) {
                Box(Modifier.size(widthDp.dp, heightDp.dp)) {
                    WizardUnderTest(step)
                }
            }
        }
    }

    @Composable
    private fun WizardUnderTest(step: SetupStep) {
        SetupWizardContent(
            state = SetupWizardUiState(
                loaded = true,
                step = step,
                choice = DownloadNetworkChoice.WIFI_ONLY,
                choiceRecorded = true,
                plannedModels = emptyList(),
                plannedVoices = emptyList(),
            ),
            onChoiceSelected = {},
            onPrimary = {},
            onBack = {},
            onSkip = {},
        )
    }

    private companion object {
        const val LARGE_FONT_SCALE = 2f
    }
}
