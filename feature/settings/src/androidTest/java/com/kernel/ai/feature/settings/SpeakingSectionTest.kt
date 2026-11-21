package com.kernel.ai.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.kernel.ai.core.model.availability.ModelAvailabilityState
import com.kernel.ai.core.ui.theme.KernelAITheme
import com.kernel.ai.core.voice.SherpaPiperVoice
import com.kernel.ai.core.voice.VoiceOutputEngine
import com.kernel.ai.core.voice.VoicePackDownloadState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Pins that the Speaking cards keep their preference and download calls (#1972). Callbacks are recorded, so each
 * test asserts the exact sequence a tap or button press produces.
 */
class SpeakingSectionTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val calls = mutableListOf<String>()

    @Test
    fun tappingDownloadedPiperCardSelectsVoiceThenEngine() {
        setSection(
            VoiceUiState(
                availableOutputEngines = listOf(VoiceOutputEngine.SherpaExperimental, VoiceOutputEngine.AndroidTts),
                selectedOutputEngine = VoiceOutputEngine.AndroidTts,
                sherpaVoices = listOf(
                    SherpaVoiceRowUiState(
                        SherpaPiperVoice.CoriHigh,
                        VoicePackDownloadState.Downloaded(""),
                    ),
                ),
            ),
        )

        tapCard(piperCardTag(SherpaPiperVoice.CoriHigh))

        assertEquals(listOf("sherpaVoice(CoriHigh)", "engine(SherpaExperimental)"), calls)
    }

    @Test
    fun tappingNotDownloadedPiperCardOnlySelectsEngine() {
        setSection(
            VoiceUiState(
                availableOutputEngines = listOf(VoiceOutputEngine.SherpaExperimental, VoiceOutputEngine.AndroidTts),
                selectedOutputEngine = VoiceOutputEngine.AndroidTts,
                sherpaVoices = listOf(SherpaVoiceRowUiState(SherpaPiperVoice.CoriHigh)),
            ),
        )

        tapCard(piperCardTag(SherpaPiperVoice.CoriHigh))

        assertEquals(listOf("engine(SherpaExperimental)"), calls)
    }

    @Test
    fun downloadButtonInsidePiperCardOnlyStartsDownload() {
        setSection(
            VoiceUiState(
                availableOutputEngines = listOf(VoiceOutputEngine.SherpaExperimental, VoiceOutputEngine.AndroidTts),
                selectedOutputEngine = VoiceOutputEngine.SherpaExperimental,
                sherpaVoices = listOf(SherpaVoiceRowUiState(SherpaPiperVoice.CoriHigh)),
            ),
        )

        cardButton(piperCardTag(SherpaPiperVoice.CoriHigh), "Download").performScrollTo().performClick()

        assertEquals(listOf("download(CoriHigh)"), calls)
    }

    @Test
    fun piperCardActionsHiddenWhileAnotherEngineSelected() {
        setSection(
            VoiceUiState(
                availableOutputEngines = listOf(VoiceOutputEngine.SherpaExperimental, VoiceOutputEngine.AndroidTts),
                selectedOutputEngine = VoiceOutputEngine.AndroidTts,
                selectedSherpaVoice = SherpaPiperVoice.CoriHigh,
                sherpaVoices = listOf(
                    SherpaVoiceRowUiState(SherpaPiperVoice.CoriHigh, VoicePackDownloadState.Downloaded("")),
                ),
            ),
        )

        cardButton(piperCardTag(SherpaPiperVoice.CoriHigh), "Delete").assertDoesNotExist()
        cardButton(piperCardTag(SherpaPiperVoice.CoriHigh), "Play sample").assertDoesNotExist()
    }

    @Test
    fun playSampleRecordsSampleWhilePiperSelected() {
        setSection(
            VoiceUiState(
                availableOutputEngines = listOf(VoiceOutputEngine.SherpaExperimental, VoiceOutputEngine.AndroidTts),
                selectedOutputEngine = VoiceOutputEngine.SherpaExperimental,
                selectedSherpaVoice = SherpaPiperVoice.CoriHigh,
                sherpaVoices = listOf(
                    SherpaVoiceRowUiState(SherpaPiperVoice.CoriHigh, VoicePackDownloadState.Downloaded("")),
                ),
            ),
        )

        cardButton(piperCardTag(SherpaPiperVoice.CoriHigh), "Play sample").performScrollTo().performClick()

        assertEquals(listOf("sample(CoriHigh)"), calls)
    }

    @Test
    fun coriDeleteAndPlaySampleHiddenWhileInflectSelected() {
        setSection(
            VoiceUiState(
                availableOutputEngines = listOf(VoiceOutputEngine.SherpaExperimental, VoiceOutputEngine.InflectMicroExperimental),
                selectedOutputEngine = VoiceOutputEngine.InflectMicroExperimental,
                selectedSherpaVoice = SherpaPiperVoice.CoriHigh,
                sherpaVoices = listOf(
                    SherpaVoiceRowUiState(SherpaPiperVoice.CoriHigh, VoicePackDownloadState.Downloaded("")),
                ),
                inflectMicroAvailability = ModelAvailabilityState.Ready,
            ),
        )

        cardButton(piperCardTag(SherpaPiperVoice.CoriHigh), "Delete").assertDoesNotExist()
        cardButton(piperCardTag(SherpaPiperVoice.CoriHigh), "Play sample").assertDoesNotExist()
    }

    @Test
    fun inflectPreparingWithPiperSelectedKeepsCoriCancel() {
        setSection(
            VoiceUiState(
                availableOutputEngines = listOf(VoiceOutputEngine.SherpaExperimental, VoiceOutputEngine.InflectMicroExperimental),
                selectedOutputEngine = VoiceOutputEngine.SherpaExperimental,
                selectedSherpaVoice = SherpaPiperVoice.CoriHigh,
                sherpaVoices = listOf(
                    SherpaVoiceRowUiState(SherpaPiperVoice.CoriHigh, VoicePackDownloadState.Downloading(progress = 0.3f)),
                ),
                inflectMicroAvailability = ModelAvailabilityState.Preparing(progress = 0.4f),
            ),
        )

        cardButton(piperCardTag(SherpaPiperVoice.CoriHigh), "Cancel").performScrollTo().performClick()

        assertEquals(listOf("cancel(CoriHigh)"), calls)
    }

    @Test
    fun inflectPreparingWithPiperNotSelectedLetsInflectOwnCoriCancel() {
        setSection(
            VoiceUiState(
                availableOutputEngines = listOf(VoiceOutputEngine.SherpaExperimental, VoiceOutputEngine.InflectMicroExperimental),
                selectedOutputEngine = VoiceOutputEngine.InflectMicroExperimental,
                selectedSherpaVoice = SherpaPiperVoice.CoriHigh,
                sherpaVoices = listOf(
                    SherpaVoiceRowUiState(SherpaPiperVoice.CoriHigh, VoicePackDownloadState.Downloading(progress = 0.3f)),
                ),
                inflectMicroAvailability = ModelAvailabilityState.Preparing(progress = 0.4f),
            ),
        )

        composeTestRule.onNode(hasText("Preparing 40%"), useUnmergedTree = true).assertExists()
        cardButton(piperCardTag(SherpaPiperVoice.CoriHigh), "Cancel").assertDoesNotExist()
        cardButton(inflectCardTag, "Cancel").performScrollTo().performClick()

        assertEquals(listOf("cancelInflect"), calls)
    }

    @Test
    fun useMobileDataHiddenWhileInflectOwnsCoriDownload() {
        setSection(
            VoiceUiState(
                availableOutputEngines = listOf(VoiceOutputEngine.SherpaExperimental, VoiceOutputEngine.InflectMicroExperimental),
                selectedOutputEngine = VoiceOutputEngine.InflectMicroExperimental,
                selectedSherpaVoice = SherpaPiperVoice.CoriHigh,
                sherpaVoices = listOf(
                    SherpaVoiceRowUiState(
                        SherpaPiperVoice.CoriHigh,
                        VoicePackDownloadState.Downloading(progress = 0.3f, waitingForWifi = true),
                    ),
                ),
                inflectMicroAvailability = ModelAvailabilityState.Preparing(progress = 0.4f),
            ),
        )

        cardButton(piperCardTag(SherpaPiperVoice.CoriHigh), "Use mobile data").assertDoesNotExist()
    }

    @Test
    fun soundLabelShownOnlyWhenPiperIsSelected() {
        setSection(
            VoiceUiState(
                availableOutputEngines = listOf(VoiceOutputEngine.SherpaExperimental, VoiceOutputEngine.AndroidTts),
                selectedOutputEngine = VoiceOutputEngine.SherpaExperimental,
                sherpaVoices = listOf(SherpaVoiceRowUiState(SherpaPiperVoice.CoriHigh)),
            ),
        )

        composeTestRule.onNodeWithContentDescription("Sound").performScrollTo().assertExists()
    }

    @Test
    fun soundLabelAbsentWhenPhoneVoiceIsSelected() {
        setSection(
            VoiceUiState(
                availableOutputEngines = listOf(VoiceOutputEngine.SherpaExperimental, VoiceOutputEngine.AndroidTts),
                selectedOutputEngine = VoiceOutputEngine.AndroidTts,
                sherpaVoices = listOf(SherpaVoiceRowUiState(SherpaPiperVoice.CoriHigh)),
            ),
        )

        composeTestRule.onNodeWithContentDescription("Sound").assertDoesNotExist()
    }

    private fun piperCardTag(voice: SherpaPiperVoice): String =
        speakingCardTestTag(SpeakingOption.PiperVoice(SherpaVoiceRowUiState(voice)))

    private fun tapCard(tag: String) {
        composeTestRule.onNode(hasTestTag(tag)).performScrollTo().performClick()
    }

    private val inflectCardTag = speakingCardTestTag(SpeakingOption.InflectMicro)

    private fun cardButton(tag: String, text: String) =
        composeTestRule.onNode(hasText(text) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)

    private fun setSection(uiState: VoiceUiState) {
        calls.clear()
        composeTestRule.setContent {
            KernelAITheme(darkTheme = true, dynamicColor = false) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    SpeakingSection(
                        uiState = uiState,
                        onOutputEngineSelected = { calls += "engine(${it.name})" },
                        onSherpaVoiceSelected = { calls += "sherpaVoice(${it.name})" },
                        onSherpaSpeedChanged = {},
                        onSherpaPitchChanged = {},
                        onSherpaGainChanged = {},
                        onActiveSpeakerIdChanged = {},
                        onKokoroVoiceSelected = { calls += "kokoroVoice(${it.name})" },
                        onKokoroActiveSpeakerIdChanged = {},
                        onDownloadSherpaVoice = { calls += "download(${it.name})" },
                        onCancelSherpaVoice = { calls += "cancel(${it.name})" },
                        onDeleteSherpaVoice = { calls += "delete(${it.name})" },
                        onPlaySherpaSample = { calls += "sample(${it.name})" },
                        onUseMobileDataForVoice = { calls += "mobileData(${it.name})" },
                        onDownloadKokoroVoice = { calls += "downloadKokoro(${it.name})" },
                        onCancelKokoroVoice = { calls += "cancelKokoro(${it.name})" },
                        onDeleteKokoroVoice = { calls += "deleteKokoro(${it.name})" },
                        onDownloadInflectMicro = { calls += "downloadInflect" },
                        onCancelInflectMicro = { calls += "cancelInflect" },
                        onDeleteInflectMicro = { calls += "deleteInflect" },
                    )
                }
            }
        }
    }
}
