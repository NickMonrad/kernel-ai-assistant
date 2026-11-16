package com.kernel.ai.feature.chat.setup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.kernel.ai.core.inference.download.DownloadNetworkChoice
import com.kernel.ai.core.inference.setup.SetupStep
import com.kernel.ai.core.voice.VoiceInputEngine
import com.kernel.ai.core.voice.VoiceOutputEngine
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Large-font layout for the setup wizard (#1903, #1936), at font scale 2.0 in forced portrait (360x640 dp) and
 * landscape (915x412 dp) windows.
 *
 * Checks use unclipped geometry (positionInRoot and size), not boundsInRoot, which is clipped to parents and
 * so cannot show content pushed outside the window. Each button must be at least 48 dp tall and lie inside the
 * forced window (a box of the forced size at the root origin). In pinned portrait the content container must end
 * above the footer, the last item of each step must be fully visible above the footer once scrolled to the end, and
 * the "More below" hint must show while content is hidden. In compact landscape the whole page scrolls, so each
 * button is scrolled into view first.
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
            .performScrollToNode(hasText("Chat needs a one-time download", substring = true))
        val paragraph = composeRule.onNodeWithText("Chat needs a one-time download", substring = true)
        paragraph.assertIsDisplayed()
        assertInside(paragraph.unclippedBounds(), contentBounds())
    }

    @Test
    fun step1ButtonsReachableAtLargeFontPortrait() {
        setWizard(step = SetupStep.HOW_IT_WORKS, size = PORTRAIT)

        assertButtonsReachable(primary = "Continue", skip = "Skip setup", size = PORTRAIT, compact = false)
    }

    @Test
    fun step2ScrollsToNetworkChoiceAndButtonsReachableAtLargeFontPortrait() {
        setWizard(step = SetupStep.DOWNLOAD, size = PORTRAIT)

        composeRule.onNodeWithTag(SETUP_WIZARD_CONTENT_TAG)
            .performScrollToNode(hasText("Wi-Fi or mobile data"))
        val option = composeRule.onNodeWithText("Wi-Fi or mobile data")
        option.assertIsDisplayed()
        assertInside(option.unclippedBounds(), contentBounds())
        assertButtonsReachable(primary = "Continue", skip = "Skip setup", size = PORTRAIT, compact = false)
    }

    @Test
    fun step2ButtonsReachableAtLargeFontLandscape() {
        setWizard(step = SetupStep.DOWNLOAD, size = LANDSCAPE)

        assertButtonsReachable(primary = "Continue", skip = "Skip setup", size = LANDSCAPE, compact = true)
    }

    @Test
    fun step2WithNothingLeftShowsAlreadyDownloaded() {
        setWizard(step = SetupStep.DOWNLOAD, size = PORTRAIT)

        composeRule.onNodeWithText("Already downloaded", substring = true).assertIsDisplayed()
        assertTrue(
            "About 0 B must never be shown",
            composeRule.onAllNodesWithText("About 0 B", substring = true).fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun voiceStepButtonsReachableAtLargeFontPortrait() {
        setWizard(SetupStep.VOICE, PORTRAIT)
        assertButtonsReachable(primary = "Continue", skip = "Skip setup", size = PORTRAIT, compact = false)
    }

    @Test
    fun voiceStepButtonsReachableAtLargeFontLandscape() {
        setWizard(SetupStep.VOICE, LANDSCAPE)
        assertButtonsReachable(primary = "Continue", skip = "Skip setup", size = LANDSCAPE, compact = true)
    }

    @Test
    fun makeJandalYoursButtonsReachableAtLargeFontPortrait() {
        setWizard(SetupStep.MAKE_JANDAL_YOURS, PORTRAIT)
        assertButtonsReachable(primary = "Finish", skip = "Skip these", size = PORTRAIT, compact = false)
    }

    @Test
    fun makeJandalYoursButtonsReachableAtLargeFontLandscape() {
        setWizard(SetupStep.MAKE_JANDAL_YOURS, LANDSCAPE)
        assertButtonsReachable(primary = "Finish", skip = "Skip these", size = LANDSCAPE, compact = true)
    }

    @Test
    fun makeJandalYoursLastRowScrollsIntoViewAtLargeFont() {
        setWizard(SetupStep.MAKE_JANDAL_YOURS, PORTRAIT)
        composeRule.onNodeWithTag(SETUP_WIZARD_CONTENT_TAG)
            .performScrollToNode(hasText("Alarms and timers"))
        val row = composeRule.onNodeWithText("Alarms and timers", useUnmergedTree = true)
        row.assertIsDisplayed()
        assertInside(row.unclippedBounds(), contentBounds())
    }

    @Test
    fun makeJandalYoursTallestStateButtonsReachableAtLargeFontPortrait() {
        setWizard(SetupStep.MAKE_JANDAL_YOURS, PORTRAIT, tallMakeYours = true)
        composeRule.onNodeWithTag(SETUP_WIZARD_CONTENT_TAG)
            .performScrollToNode(hasText("Alarms and timers"))
        assertButtonsReachable(primary = "Finish", skip = "Skip these", size = PORTRAIT, compact = false)
    }

    @Test
    fun makeJandalYoursTallestStateButtonsReachableAtLargeFontLandscape() {
        setWizard(SetupStep.MAKE_JANDAL_YOURS, LANDSCAPE, tallMakeYours = true)
        composeRule.onNodeWithTag(SETUP_WIZARD_CONTENT_TAG)
            .performScrollToNode(hasText("Alarms and timers"))
        assertButtonsReachable(primary = "Finish", skip = "Skip these", size = LANDSCAPE, compact = true)
    }

    // ── Last item above the footer, "More below" and scroll reset (#1936, #1929) ──

    @Test
    fun lastItemFullyVisibleAboveFooter_welcome() =
        assertLastItemAboveFooter(SetupStep.HOW_IT_WORKS, hasText("Chat needs a one-time download", substring = true))

    @Test
    fun lastItemFullyVisibleAboveFooter_voice() =
        assertLastItemAboveFooter(SetupStep.VOICE, hasText("Phone's built-in voice", substring = true))

    @Test
    fun lastItemFullyVisibleAboveFooter_download() =
        assertLastItemAboveFooter(SetupStep.DOWNLOAD, hasText("Wi-Fi or mobile data", substring = true))

    @Test
    fun lastItemFullyVisibleAboveFooter_makeJandalYours() =
        assertLastItemAboveFooter(
            SetupStep.MAKE_JANDAL_YOURS,
            hasClickAction() and hasText("Open settings"),
            tallMakeYours = true,
        )

    /** Needs access is the widest chip; the title must stay on one line, with the chip moving under it if needed. */
    @Test
    fun heyJandalTitleStaysOnOneLineWithNeedsAccessAtLargeFont() {
        setWizard(SetupStep.MAKE_JANDAL_YOURS, PORTRAIT, tallMakeYours = true)

        val title = composeRule.onNode(hasText("Hey Jandal"), useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        title.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        assertTrue("Hey Jandal must lay out on one line, got ${layouts.firstOrNull()?.lineCount}", layouts.single().lineCount == 1)
    }

    @Test
    fun moreBelowHintShowsWhileContentOverflows() {
        setWizard(SetupStep.MAKE_JANDAL_YOURS, PORTRAIT, tallMakeYours = true)

        composeRule.onNodeWithTag(SETUP_MORE_BELOW_TAG).assertExists()
    }

    @Test
    fun eachStepOpensScrolledToTheTop() {
        val state = mutableStateOf(wizardState(SetupStep.MAKE_JANDAL_YOURS, tallMakeYours = true))
        setWizard(PORTRAIT, state)
        swipeToEnd()

        state.value = wizardState(SetupStep.DOWNLOAD, tallMakeYours = true)
        composeRule.waitForIdle()

        val heading = composeRule.onNodeWithText("Download Jandal's models")
        heading.assertIsDisplayed()
        val top = heading.unclippedBounds().top
        val contentTop = contentBounds().top
        assertTrue("heading top $top must be at or below content top $contentTop", top >= contentTop - TOLERANCE_PX)
    }

    private fun assertLastItemAboveFooter(step: SetupStep, last: SemanticsMatcher, tallMakeYours: Boolean = false) {
        setWizard(step, PORTRAIT, tallMakeYours)
        swipeToEnd()

        composeRule.onNodeWithTag(SETUP_MORE_BELOW_TAG).assertDoesNotExist()
        val lastBounds = composeRule.onAllNodes(last).onLast().unclippedBounds()
        val footerTop = composeRule.onNodeWithTag(SETUP_FOOTER_TAG).unclippedBounds().top
        assertTrue(
            "last bottom ${lastBounds.bottom} must be at or above footer top $footerTop",
            lastBounds.bottom <= footerTop + TOLERANCE_PX,
        )
        assertInside(lastBounds, contentBounds())
    }

    private fun swipeToEnd() {
        composeRule.onNodeWithTag(SETUP_WIZARD_CONTENT_TAG).performTouchInput { repeat(10) { swipeUp() } }
        composeRule.waitForIdle()
    }

    private fun setWizard(step: SetupStep, size: DpSize, tallMakeYours: Boolean = false) =
        setWizard(size, mutableStateOf(wizardState(step, tallMakeYours)))

    private fun setWizard(size: DpSize, state: MutableState<SetupWizardUiState>) {
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(size).then(
                    DeviceConfigurationOverride.FontScale(LARGE_FONT_SCALE),
                ),
            ) {
                WizardUnderTest(state.value)
            }
        }
    }

    /** Bounds of the clickable Button/TextButton that carries [label], not the label Text inside it. */
    private fun buttonBounds(label: String): Rect =
        composeRule.onNode(hasClickAction() and hasText(label))
            .unclippedBounds()

    /**
     * Each button must be at least 48 dp tall and lie inside the forced window. Pinned layout (compact false):
     * the scrolling content must end at or above the footer's top. Compact layout: scroll each button into view
     * first, since the whole page scrolls and the content container includes the buttons.
     */
    private fun assertButtonsReachable(primary: String, skip: String, size: DpSize, compact: Boolean) {
        val window = Rect(0f, 0f, size.width.value * layoutDensity, size.height.value * layoutDensity)
        if (!compact) {
            val content = contentBounds()
            val footerTop = composeRule.onNodeWithTag(SETUP_FOOTER_TAG).unclippedBounds().top
            assertTrue(
                "content bottom ${content.bottom} must be at or above the footer top $footerTop",
                content.bottom <= footerTop + TOLERANCE_PX,
            )
        }
        val minHeightPx = MIN_TOUCH_DP * layoutDensity
        listOf(primary, "Back", skip).forEach { label ->
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

    private fun wizardState(step: SetupStep, tallMakeYours: Boolean) = SetupWizardUiState(
        loaded = true,
        step = step,
        inputOptions = listOf(VoiceInputEngine.Auto, VoiceInputEngine.AndroidNative),
        inputEngine = VoiceInputEngine.Auto,
        outputOptions = listOf(
            VoiceOutputEngine.SherpaExperimental,
            VoiceOutputEngine.InflectMicroExperimental,
            VoiceOutputEngine.AndroidTts,
        ),
        outputEngine = VoiceOutputEngine.SherpaExperimental,
        choice = DownloadNetworkChoice.WIFI_ONLY,
        choiceRecorded = true,
        plannedModels = emptyList(),
        plannedVoices = emptyList(),
        platform = if (tallMakeYours) TALLEST_PLATFORM else MakeYoursPlatform(),
        wakeWordChoice = tallMakeYours,
        allowOnlineSpeech = false,
        outputSizes = mapOf(
            VoiceOutputEngine.SherpaExperimental to 0L,
            VoiceOutputEngine.InflectMicroExperimental to 0L,
        ),
        heyJandalExtraBytes = 0L,
    )

    @Composable
    private fun WizardUnderTest(state: SetupWizardUiState) {
        layoutDensity = LocalDensity.current.density
        SetupWizardContent(
            state = state,
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
