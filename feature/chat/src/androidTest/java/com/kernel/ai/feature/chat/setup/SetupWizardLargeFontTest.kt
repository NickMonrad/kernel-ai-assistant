package com.kernel.ai.feature.chat.setup

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.kernel.ai.core.inference.download.DownloadNetworkChoice
import com.kernel.ai.core.inference.setup.SetupStep
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Large-font layout for the setup wizard (#1903), at font scale 2.0 in forced portrait (360x640 dp) and
 * landscape (915x412 dp) windows.
 *
 * Checks use unclipped geometry (positionInRoot and size), not boundsInRoot, which is clipped to parents and
 * so cannot show content pushed outside the window. The step content must scroll: its container ends above the
 * primary button. Each button must be at least 48 dp tall and lie inside the window.
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
        assertInside(paragraph.unclippedBounds(), contentBounds())
    }

    @Test
    fun step1ButtonsReachableAtLargeFontPortrait() {
        setWizard(step = SetupStep.HOW_IT_WORKS, size = PORTRAIT)

        assertButtonsReachable(primary = "Continue")
    }

    @Test
    fun step2ScrollsToNetworkChoiceAndButtonsReachableAtLargeFontPortrait() {
        setWizard(step = SetupStep.DOWNLOAD, size = PORTRAIT)

        composeRule.onNodeWithTag(SETUP_WIZARD_CONTENT_TAG)
            .performScrollToNode(hasText("Allow mobile data"))
        val option = composeRule.onNodeWithText("Allow mobile data")
        option.assertIsDisplayed()
        assertInside(option.unclippedBounds(), contentBounds())
        assertButtonsReachable(primary = "Finish")
    }

    @Test
    fun step2ButtonsReachableAtLargeFontLandscape() {
        setWizard(step = SetupStep.DOWNLOAD, size = LANDSCAPE)

        assertButtonsReachable(primary = "Finish")
    }

    @Test
    fun step2WithNothingLeftShowsNothingToDownloadLine() {
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

    /**
     * The content container must end at or above the primary button's top, so the buttons sit below the
     * scrolling area rather than over it. Each button must be at least 48 dp tall and lie inside the window.
     */
    private fun assertButtonsReachable(primary: String) {
        val window = composeRule.onRoot().unclippedBounds()
        val content = contentBounds()
        val primaryBounds = composeRule.onNodeWithText(primary).unclippedBounds()
        assertTrue(
            "content bottom ${content.bottom} must be at or above $primary top ${primaryBounds.top}",
            content.bottom <= primaryBounds.top + TOLERANCE_PX,
        )
        val minHeightPx = MIN_TOUCH_DP * InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.displayMetrics.density
        listOf(primary, "Back", "Skip for now").forEach { label ->
            val bounds = composeRule.onNodeWithText(label).unclippedBounds()
            assertTrue(
                "$label height ${bounds.height} px must be at least $minHeightPx px (48 dp)",
                bounds.height >= minHeightPx,
            )
            assertInside(bounds, window)
        }
    }

    private fun contentBounds(): Rect = composeRule.onNodeWithTag(SETUP_WIZARD_CONTENT_TAG).unclippedBounds()

    private fun SemanticsNodeInteraction.unclippedBounds(): Rect {
        val node = fetchSemanticsNode()
        val topLeft = node.positionInRoot
        return Rect(
            left = topLeft.x,
            top = topLeft.y,
            right = topLeft.x + node.size.width,
            bottom = topLeft.y + node.size.height,
        )
    }

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
        const val MIN_TOUCH_DP = 48f
        const val TOLERANCE_PX = 1f
        val PORTRAIT = DpSize(360.dp, 640.dp)
        val LANDSCAPE = DpSize(915.dp, 412.dp)
    }
}
