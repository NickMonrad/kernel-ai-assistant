package com.kernel.ai.feature.chat.setup

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.then
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.kernel.ai.core.inference.download.DownloadNetworkChoice
import com.kernel.ai.core.inference.setup.SetupStep
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Large-font layout for the setup wizard (#1903). At font scale 2.0 the step content must scroll, and the
 * primary, Back and Skip buttons must lie inside the window in portrait and landscape.
 *
 * Sizes are forced with [DeviceConfigurationOverride] so the window really is 360x640 dp (portrait) or
 * 915x412 dp (landscape). Bounds are checked against the root window, so content laid out below the window
 * fails the test. Visibility alone is not enough: it does not catch clipping by the window.
 */
@OptIn(ExperimentalTestApi::class)
class SetupWizardLargeFontTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun step1LastParagraphScrollsIntoViewAtLargeFont() {
        setWizard(step = SetupStep.HOW_IT_WORKS, size = PORTRAIT)

        composeRule.onNodeWithTag(SETUP_WIZARD_CONTENT_TAG)
            .performScrollToNode(hasText("downloads once", substring = true))
        val paragraph = composeRule.onNodeWithText("downloads once", substring = true)
        paragraph.assertIsDisplayed()
        assertInside(paragraph.boundsInRoot(), contentBounds())
    }

    @Test
    fun step1ButtonsStayInsideWindowAtLargeFontPortrait() {
        setWizard(step = SetupStep.HOW_IT_WORKS, size = PORTRAIT)

        assertButtonsInsideWindow(primary = "Continue")
    }

    @Test
    fun step2ScrollsToNetworkChoiceAndButtonsStayInsideWindowAtLargeFontPortrait() {
        setWizard(step = SetupStep.DOWNLOAD, size = PORTRAIT)

        composeRule.onNodeWithTag(SETUP_WIZARD_CONTENT_TAG)
            .performScrollToNode(hasText("Allow mobile data"))
        val option = composeRule.onNodeWithText("Allow mobile data")
        option.assertIsDisplayed()
        assertInside(option.boundsInRoot(), contentBounds())
        assertButtonsInsideWindow(primary = "Finish")
    }

    @Test
    fun step2ButtonsStayInsideWindowAtLargeFontLandscape() {
        setWizard(step = SetupStep.DOWNLOAD, size = LANDSCAPE)

        assertButtonsInsideWindow(primary = "Finish")
    }

    @Test
    fun step2WithNothingLeftSaysNothingToDownloadNotAboutZeroBytes() {
        setWizard(step = SetupStep.DOWNLOAD, size = PORTRAIT)

        composeRule.onNodeWithText("Nothing left to download", substring = true).assertIsDisplayed()
        assertTrue(
            "About 0 B must never be shown",
            composeRule.onAllNodesWithText("About 0 B", substring = true).fetchSemanticsNodes().isEmpty(),
        )
    }

    private fun setWizard(step: SetupStep, size: DpSize) {
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(size).then(
                    DeviceConfigurationOverride.FontScale(LARGE_FONT_SCALE),
                ),
            ) {
                WizardUnderTest(step)
            }
        }
    }

    private fun assertButtonsInsideWindow(primary: String) {
        val window = composeRule.onRoot().boundsInRoot()
        assertInside(composeRule.onNodeWithText(primary).boundsInRoot(), window)
        assertInside(composeRule.onNodeWithText("Back").boundsInRoot(), window)
        assertInside(composeRule.onNodeWithText("Skip for now").boundsInRoot(), window)
    }

    private fun contentBounds(): Rect = composeRule.onNodeWithTag(SETUP_WIZARD_CONTENT_TAG).boundsInRoot()

    private fun SemanticsNodeInteraction.boundsInRoot(): Rect = fetchSemanticsNode().boundsInRoot

    private fun assertInside(child: Rect, container: Rect) {
        assertTrue(
            "child $child is not inside container $container",
            child.top >= container.top - TOLERANCE_PX &&
                child.bottom <= container.bottom + TOLERANCE_PX &&
                child.left >= container.left - TOLERANCE_PX &&
                child.right <= container.right + TOLERANCE_PX,
        )
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
        const val TOLERANCE_PX = 1f
        val PORTRAIT = DpSize(360.dp, 640.dp)
        val LANDSCAPE = DpSize(915.dp, 412.dp)
    }
}
