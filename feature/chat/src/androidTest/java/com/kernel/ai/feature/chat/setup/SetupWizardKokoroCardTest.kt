package com.kernel.ai.feature.chat.setup

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.kernel.ai.core.inference.setup.SetupStep
import com.kernel.ai.core.voice.VoiceOutputCopy
import com.kernel.ai.core.voice.VoiceOutputEngine
import com.kernel.ai.core.voice.kokoro.KokoroVoice
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Kokoro card on the speech step (#2191): voice picker, sample, and the copy about the current voice. */
class SetupWizardKokoroCardTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun pickerAndSampleShowWhenKokoroIsDownloadedAndPicked() {
        setCard(outputEngine = VoiceOutputEngine.KokoroExperimental, kokoroReady = true)

        composeRule.onNodeWithText(EMMA_ROW).performScrollTo().assertIsSelected()
        composeRule.onNodeWithText(HEART_ROW).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription(SAMPLE).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun pickerAndSampleHideUntilKokoroIsDownloaded() {
        setCard(outputEngine = VoiceOutputEngine.KokoroExperimental, kokoroReady = false)

        composeRule.onAllNodesWithText(EMMA_ROW).assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription(SAMPLE).assertCountEquals(0)
    }

    @Test
    fun sampleShowsForADownloadedKokoroButPickerWaitsUntilItIsPicked() {
        setCard(outputEngine = VoiceOutputEngine.SherpaExperimental, kokoroReady = true)

        composeRule.onAllNodesWithText(EMMA_ROW).assertCountEquals(0)
        composeRule.onNodeWithContentDescription(SAMPLE).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun pickingAVoiceReportsIt() {
        var picked: KokoroVoice? = null
        setCard(
            outputEngine = VoiceOutputEngine.KokoroExperimental,
            kokoroReady = true,
            onKokoroVoiceSelected = { picked = it },
        )

        composeRule.onNodeWithText(HEART_ROW).performScrollTo().performClick()

        assertEquals(KokoroVoice.HEART, picked)
    }

    @Test
    fun sampleButtonReportsEachTap() {
        var taps = 0
        setCard(
            outputEngine = VoiceOutputEngine.KokoroExperimental,
            kokoroReady = true,
            onKokoroSampleToggled = { taps++ },
        )

        composeRule.onNodeWithContentDescription(SAMPLE).performScrollTo().performClick()

        assertEquals(1, taps)
    }

    @Test
    fun stayLineShowsWhileKokoroIsPickedWithoutACachedPass() {
        setCard(outputEngine = VoiceOutputEngine.KokoroExperimental, kokoroCheckPassed = false)

        composeRule.onNodeWithText(STAYS_LINE, substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun stayLineHidesOnceACachedPassExists() {
        setCard(outputEngine = VoiceOutputEngine.KokoroExperimental, kokoroCheckPassed = true)

        composeRule.onAllNodesWithText(STAYS_LINE, substring = true).assertCountEquals(0)
    }

    @Test
    fun stayLineHidesWhenAnotherVoiceIsPicked() {
        setCard(outputEngine = VoiceOutputEngine.SherpaExperimental, kokoroCheckPassed = false)

        composeRule.onAllNodesWithText(STAYS_LINE, substring = true).assertCountEquals(0)
    }

    private fun setCard(
        outputEngine: VoiceOutputEngine,
        kokoroReady: Boolean = false,
        kokoroCheckPassed: Boolean = false,
        onKokoroVoiceSelected: (KokoroVoice) -> Unit = {},
        onKokoroSampleToggled: () -> Unit = {},
    ) {
        composeRule.setContent {
            SetupWizardContent(
                state = SetupWizardUiState(
                    loaded = true,
                    step = SetupStep.VOICE,
                    termsAccepted = true,
                    outputOptions = listOf(
                        VoiceOutputEngine.SherpaExperimental,
                        VoiceOutputEngine.InflectMicroExperimental,
                        VoiceOutputEngine.KokoroExperimental,
                        VoiceOutputEngine.AndroidTts,
                    ),
                    outputEngine = outputEngine,
                    kokoroReady = kokoroReady,
                    kokoroVoice = KokoroVoice.EMMA,
                    kokoroCheckPassed = kokoroCheckPassed,
                ),
                onInputSelected = {},
                onOutputSelected = {},
                onChoiceSelected = {},
                makeYours = MakeYoursActions(),
                onPrimary = {},
                onBack = {},
                onSkip = {},
                onKokoroVoiceSelected = onKokoroVoiceSelected,
                onKokoroSampleToggled = onKokoroSampleToggled,
            )
        }
    }

    private companion object {
        val EMMA_ROW = "${KokoroVoice.EMMA.displayName}: ${KokoroVoice.EMMA.description}"
        val HEART_ROW = "${KokoroVoice.HEART.displayName}: ${KokoroVoice.HEART.description}"
        val SAMPLE = "Hear a sample of ${VoiceOutputCopy.title(VoiceOutputEngine.KokoroExperimental)}"
        const val STAYS_LINE = "Your current voice stays until the check passes."
    }
}
