package com.kernel.ai.feature.chat.setup

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.kernel.ai.core.inference.download.DownloadNetworkChoice
import com.kernel.ai.core.voice.VoiceInputEngine
import com.kernel.ai.core.voice.VoiceOutputEngine
import com.kernel.ai.core.inference.setup.SetupStep
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Large-font layout for the setup wizard (#1903), at font scale 2.0 in forced portrait (360x640 dp) and
 * landscape (915x412 dp) windows.
 *
 * Checks use unclipped geometry (positionInRoot and size), not boundsInRoot, which is clipped to parents and
 * so cannot show content pushed outside the window. Each button must be at least 48 dp tall and lie inside the
 * forced window (a box of the forced size at the root origin). In pinned portrait the content container must end
 * above the primary button; in compact landscape the whole page scrolls, so each button is scrolled into view first.
 */
@OptIn(ExperimentalTestApi::class)
class SetupWizardLargeFontTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** Density the wizard is laid out with under DeviceConfigurationOverride (captured in [WizardUnderTest]). */
    private var layoutDensity: Float = 0f

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

        assertButtonsReachable(primary = "Continue", size = PORTRAIT, compact = false)
    }

    @Test
    fun step2ScrollsToNetworkChoiceAndButtonsReachableAtLargeFontPortrait() {
        setWizard(step = SetupStep.DOWNLOAD, size = PORTRAIT)

        composeRule.onNodeWithTag(SETUP_WIZARD_CONTENT_TAG)
            .performScrollToNode(hasText("Allow mobile data"))
        val option = composeRule.onNodeWithText("Allow mobile data")
        option.assertIsDisplayed()
        assertInside(option.unclippedBounds(), contentBounds())
        assertButtonsReachable(primary = "Continue", size = PORTRAIT, compact = false)
    }

    @Test
    fun step2ButtonsReachableAtLargeFontLandscape() {
        setWizard(step = SetupStep.DOWNLOAD, size = LANDSCAPE)

        assertButtonsReachable(primary = "Continue", size = LANDSCAPE, compact = true)
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

    @Test
    fun voiceStepButtonsReachableAtLargeFontPortrait() {
        setWizard(SetupStep.VOICE, PORTRAIT)
        assertButtonsReachable(primary = "Continue", size = PORTRAIT, compact = false)
    }

    @Test
    fun voiceStepButtonsReachableAtLargeFontLandscape() {
        setWizard(SetupStep.VOICE, LANDSCAPE)
        assertButtonsReachable(primary = "Continue", size = LANDSCAPE, compact = true)
    }

    @Test
    fun makeJandalYoursButtonsReachableAtLargeFontPortrait() {
        setWizard(SetupStep.MAKE_JANDAL_YOURS, PORTRAIT)
        assertButtonsReachable(primary = "Finish", size = PORTRAIT, compact = false)
    }

    @Test
    fun makeJandalYoursButtonsReachableAtLargeFontLandscape() {
        setWizard(SetupStep.MAKE_JANDAL_YOURS, LANDSCAPE)
        assertButtonsReachable(primary = "Finish", size = LANDSCAPE, compact = true)
    }

    @Test
    fun makeJandalYoursLastRowScrollsIntoViewAtLargeFont() {
        setWizard(SetupStep.MAKE_JANDAL_YOURS, PORTRAIT)
        composeRule.onNodeWithTag(SETUP_WIZARD_CONTENT_TAG)
            .performScrollToNode(hasText("Alarms and timers"))
        val row = composeRule.onNodeWithText("Alarms and timers")
        row.assertIsDisplayed()
        assertInside(row.unclippedBounds(), contentBounds())
    }

    @Test
    fun makeJandalYoursTallestStateButtonsReachableAtLargeFontPortrait() {
        setWizard(SetupStep.MAKE_JANDAL_YOURS, PORTRAIT, tallMakeYours = true)
        composeRule.onNodeWithTag(SETUP_WIZARD_CONTENT_TAG)
            .performScrollToNode(hasText("Alarms and timers"))
        assertButtonsReachable(primary = "Finish", size = PORTRAIT, compact = false)
    }

    @Test
    fun makeJandalYoursTallestStateButtonsReachableAtLargeFontLandscape() {
        setWizard(SetupStep.MAKE_JANDAL_YOURS, LANDSCAPE, tallMakeYours = true)
        composeRule.onNodeWithTag(SETUP_WIZARD_CONTENT_TAG)
            .performScrollToNode(hasText("Alarms and timers"))
        assertButtonsReachable(primary = "Finish", size = LANDSCAPE, compact = true)
    }

    private fun setWizard(step: SetupStep, size: DpSize, tallMakeYours: Boolean = false) {
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(size).then(
                    DeviceConfigurationOverride.FontScale(LARGE_FONT_SCALE),
                ),
            ) {
                WizardUnderTest(step, tallMakeYours)
            }
        }
    }

    /** Bounds of the clickable Button/TextButton that carries [label], not the label Text inside it. */
    private fun buttonBounds(label: String): Rect =
        composeRule.onNode(hasClickAction() and hasText(label))
            .unclippedBounds()

    /**
     * Each button must be at least 48 dp tall and lie inside the forced window. Pinned layout (compact false):
     * the scrolling content must end at or above the primary button's top. Compact layout: scroll each button
     * into view first, since the whole page scrolls and the content container includes the buttons.
     */
    private fun assertButtonsReachable(primary: String, size: DpSize, compact: Boolean) {
        val window = Rect(0f, 0f, size.width.value * layoutDensity, size.height.value * layoutDensity)
        if (!compact) {
            val content = contentBounds()
            val primaryBounds = buttonBounds(primary)
            assertTrue(
                "content bottom ${content.bottom} must be at or above $primary top ${primaryBounds.top}",
                content.bottom <= primaryBounds.top + TOLERANCE_PX,
            )
        }
        val minHeightPx = MIN_TOUCH_DP * layoutDensity
        listOf(primary, "Back", "Skip setup").forEach { label ->
            if (compact) {
                composeRule.onNode(hasClickAction() and hasText(label)).performScrollTo()
            }
            val bounds = buttonBounds(label)
            assertTrue(
                "$label height ${bounds.height} px must be at least $minHeightPx px (48 dp); bounds=$bounds window=$window",
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
    private fun WizardUnderTest(step: SetupStep, tallMakeYours: Boolean) {
        layoutDensity = LocalDensity.current.density
        SetupWizardContent(
            state = SetupWizardUiState(
                loaded = true,
                step = step,
                inputOptions = listOf(VoiceInputEngine.Auto, VoiceInputEngine.AndroidNative),
                inputEngine = VoiceInputEngine.Auto,
                outputOptions = listOf(VoiceOutputEngine.SherpaExperimental, VoiceOutputEngine.AndroidTts),
                outputEngine = VoiceOutputEngine.SherpaExperimental,
                choice = DownloadNetworkChoice.WIFI_ONLY,
                choiceRecorded = true,
                plannedModels = emptyList(),
                plannedVoices = emptyList(),
                platform = if (tallMakeYours) TALLEST_PLATFORM else MakeYoursPlatform(),
                wakeWordChoice = tallMakeYours,
            ),
            onInputSelected = {},
            onOutputSelected = {},
            onChoiceSelected = {},
            makeYours = MakeYoursActions(),
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
        /** Usage Access is the longest block text, and Hey Jandal is on, so Open Usage Access and Turn off both show. */
        val TALLEST_PLATFORM = MakeYoursPlatform(
            hasMicrophone = true,
            micGranted = true,
            wakeModelAvailable = true,
            usageAccessRequired = true,
            assistantRoleAvailable = true,
            notificationsGranted = false,
            exactAlarmAllowed = false,
        )
    }
}
