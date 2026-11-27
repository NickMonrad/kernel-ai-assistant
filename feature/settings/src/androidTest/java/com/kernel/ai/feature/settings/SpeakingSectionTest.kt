package com.kernel.ai.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.kernel.ai.core.model.availability.ActionReason
import com.kernel.ai.core.model.availability.ModelAvailabilityState
import com.kernel.ai.core.ui.theme.KernelAITheme
import com.kernel.ai.core.inference.download.DownloadState
import com.kernel.ai.core.voice.kokoro.KokoroSetup
import com.kernel.ai.core.voice.kokoro.KokoroVoice
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
    fun tappingNotDownloadedPiperCardSelectsEngineAndStartsDownload() {
        setSection(
            VoiceUiState(
                availableOutputEngines = listOf(VoiceOutputEngine.SherpaExperimental, VoiceOutputEngine.AndroidTts),
                selectedOutputEngine = VoiceOutputEngine.AndroidTts,
                sherpaVoices = listOf(SherpaVoiceRowUiState(SherpaPiperVoice.CoriHigh)),
            ),
        )

        tapCard(piperCardTag(SherpaPiperVoice.CoriHigh))

        assertEquals(listOf("engine(SherpaExperimental)", "download(CoriHigh)"), calls)
    }

    @Test
    fun tappingDownloadingPiperCardOnlySelectsEngine() {
        setSection(
            VoiceUiState(
                availableOutputEngines = listOf(VoiceOutputEngine.SherpaExperimental, VoiceOutputEngine.AndroidTts),
                selectedOutputEngine = VoiceOutputEngine.AndroidTts,
                sherpaVoices = listOf(
                    SherpaVoiceRowUiState(SherpaPiperVoice.CoriHigh, VoicePackDownloadState.Downloading(progress = 0.3f)),
                ),
            ),
        )

        tapCard(piperCardTag(SherpaPiperVoice.CoriHigh))

        assertEquals(listOf("engine(SherpaExperimental)"), calls)
    }

    @Test
    fun tappingFailedPiperCardRetriesDownload() {
        setSection(
            VoiceUiState(
                availableOutputEngines = listOf(VoiceOutputEngine.SherpaExperimental, VoiceOutputEngine.AndroidTts),
                selectedOutputEngine = VoiceOutputEngine.AndroidTts,
                sherpaVoices = listOf(
                    SherpaVoiceRowUiState(SherpaPiperVoice.CoriHigh, VoicePackDownloadState.Error("network")),
                ),
            ),
        )

        tapCard(piperCardTag(SherpaPiperVoice.CoriHigh))

        assertEquals(listOf("engine(SherpaExperimental)", "download(CoriHigh)"), calls)
    }

    @Test
    fun tappingSelectedNotDownloadedPiperCardLeavesStartToEngineSwitch() {
        setSection(
            VoiceUiState(
                availableOutputEngines = listOf(VoiceOutputEngine.SherpaExperimental, VoiceOutputEngine.AndroidTts),
                selectedOutputEngine = VoiceOutputEngine.AndroidTts,
                selectedSherpaVoice = SherpaPiperVoice.CoriHigh,
                sherpaVoices = listOf(SherpaVoiceRowUiState(SherpaPiperVoice.CoriHigh)),
            ),
        )

        tapCard(piperCardTag(SherpaPiperVoice.CoriHigh))

        // The engine switch starts the selected voice (PiperVoiceSetup.onEngineChosen); the tap must not repeat it.
        assertEquals(listOf("engine(SherpaExperimental)"), calls)
    }

    @Test
    fun tappingSelectedFailedPiperCardLeavesStartToEngineSwitch() {
        setSection(
            VoiceUiState(
                availableOutputEngines = listOf(VoiceOutputEngine.SherpaExperimental, VoiceOutputEngine.AndroidTts),
                selectedOutputEngine = VoiceOutputEngine.AndroidTts,
                selectedSherpaVoice = SherpaPiperVoice.CoriHigh,
                sherpaVoices = listOf(
                    SherpaVoiceRowUiState(SherpaPiperVoice.CoriHigh, VoicePackDownloadState.Error("network")),
                ),
            ),
        )

        tapCard(piperCardTag(SherpaPiperVoice.CoriHigh))

        assertEquals(listOf("engine(SherpaExperimental)"), calls)
    }

    @Test
    fun tappingSelectedNotDownloadedPiperCardWhilePiperSelectedStartsDownload() {
        setSection(
            VoiceUiState(
                availableOutputEngines = listOf(VoiceOutputEngine.SherpaExperimental, VoiceOutputEngine.AndroidTts),
                selectedOutputEngine = VoiceOutputEngine.SherpaExperimental,
                selectedSherpaVoice = SherpaPiperVoice.CoriHigh,
                sherpaVoices = listOf(SherpaVoiceRowUiState(SherpaPiperVoice.CoriHigh)),
            ),
        )

        tapCard(piperCardTag(SherpaPiperVoice.CoriHigh))

        // No engine switch here, so nothing else starts the download and the tap must.
        assertEquals(listOf("engine(SherpaExperimental)", "download(CoriHigh)"), calls)
    }

    @Test
    fun tappingNotReadyInflectMicroCardSelectsEngineAndStartsDownload() {
        setSection(
            VoiceUiState(
                availableOutputEngines = listOf(VoiceOutputEngine.SherpaExperimental, VoiceOutputEngine.InflectMicroExperimental),
                selectedOutputEngine = VoiceOutputEngine.SherpaExperimental,
                sherpaVoices = listOf(SherpaVoiceRowUiState(SherpaPiperVoice.CoriHigh)),
            ),
        )

        tapCard(inflectCardTag)

        assertEquals(listOf("engine(InflectMicroExperimental)", "downloadInflect"), calls)
    }

    @Test
    fun tappingReadyInflectMicroCardOnlySelectsEngine() {
        setSection(
            VoiceUiState(
                availableOutputEngines = listOf(VoiceOutputEngine.SherpaExperimental, VoiceOutputEngine.InflectMicroExperimental),
                selectedOutputEngine = VoiceOutputEngine.SherpaExperimental,
                sherpaVoices = listOf(SherpaVoiceRowUiState(SherpaPiperVoice.CoriHigh, VoicePackDownloadState.Downloaded(""))),
                inflectMicroAvailability = ModelAvailabilityState.Ready,
            ),
        )

        tapCard(inflectCardTag)

        assertEquals(listOf("engine(InflectMicroExperimental)"), calls)
    }

    @Test
    fun tappingPreparingInflectMicroCardOnlySelectsEngine() {
        setSection(
            VoiceUiState(
                availableOutputEngines = listOf(VoiceOutputEngine.SherpaExperimental, VoiceOutputEngine.InflectMicroExperimental),
                selectedOutputEngine = VoiceOutputEngine.SherpaExperimental,
                sherpaVoices = listOf(SherpaVoiceRowUiState(SherpaPiperVoice.CoriHigh, VoicePackDownloadState.Downloading(progress = 0.3f))),
                inflectMicroAvailability = ModelAvailabilityState.Preparing(progress = 0.4f),
            ),
        )

        tapCard(inflectCardTag)

        assertEquals(listOf("engine(InflectMicroExperimental)"), calls)
    }

    @Test
    fun tappingFailedInflectMicroCardRetriesDownload() {
        setSection(
            VoiceUiState(
                availableOutputEngines = listOf(VoiceOutputEngine.SherpaExperimental, VoiceOutputEngine.InflectMicroExperimental),
                selectedOutputEngine = VoiceOutputEngine.SherpaExperimental,
                sherpaVoices = listOf(SherpaVoiceRowUiState(SherpaPiperVoice.CoriHigh)),
                inflectMicroAvailability = ModelAvailabilityState.ActionRequired(ActionReason.DownloadFailed("network")),
            ),
        )

        tapCard(inflectCardTag)

        assertEquals(listOf("engine(InflectMicroExperimental)", "downloadInflect"), calls)
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

        composeTestRule.onNode(
            hasContentDescription("Play Cori sample") and hasAnyAncestor(hasTestTag(piperCardTag(SherpaPiperVoice.CoriHigh))),
            useUnmergedTree = true,
        ).performScrollTo().performClick()

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

    @Test
    fun tappingDownloadedKokoroCardOnlySelectsEngine() {
        setSection(
            VoiceUiState(
                availableOutputEngines = listOf(VoiceOutputEngine.SherpaExperimental, VoiceOutputEngine.KokoroExperimental),
                sherpaVoices = emptyList(),
                selectedOutputEngine = VoiceOutputEngine.SherpaExperimental,
                kokoroStates = kokoroStates(DownloadState.Downloaded("")),
            ),
        )

        tapCard(kokoroCardTag)

        assertEquals(listOf("engine(KokoroExperimental)"), calls)
    }

    @Test
    fun tappingNotDownloadedKokoroCardSelectsEngineAndStartsDownload() {
        setSection(
            VoiceUiState(
                availableOutputEngines = listOf(VoiceOutputEngine.SherpaExperimental, VoiceOutputEngine.KokoroExperimental),
                sherpaVoices = emptyList(),
                selectedOutputEngine = VoiceOutputEngine.SherpaExperimental,
            ),
        )

        tapCard(kokoroCardTag)

        assertEquals(listOf("engine(KokoroExperimental)", "downloadKokoro"), calls)
    }

    @Test
    fun tappingFailedKokoroCardRetriesDownload() {
        setSection(
            VoiceUiState(
                availableOutputEngines = listOf(VoiceOutputEngine.SherpaExperimental, VoiceOutputEngine.KokoroExperimental),
                sherpaVoices = emptyList(),
                selectedOutputEngine = VoiceOutputEngine.SherpaExperimental,
                kokoroStates = kokoroStates(DownloadState.Error("network")),
            ),
        )

        tapCard(kokoroCardTag)

        assertEquals(listOf("engine(KokoroExperimental)", "downloadKokoro"), calls)
    }

    @Test
    fun tappingDownloadingKokoroCardOnlySelectsEngine() {
        setSection(
            VoiceUiState(
                availableOutputEngines = listOf(VoiceOutputEngine.SherpaExperimental, VoiceOutputEngine.KokoroExperimental),
                selectedOutputEngine = VoiceOutputEngine.SherpaExperimental,
                sherpaVoices = emptyList(),
                kokoroStates = kokoroStates(DownloadState.Downloading(progress = 0.3f)),
            ),
        )

        tapCard(kokoroCardTag)

        assertEquals(listOf("engine(KokoroExperimental)"), calls)
    }

    @Test
    fun kokoroDownloadStartsWhileKokoroSelected() {
        setSection(
            VoiceUiState(
                availableOutputEngines = listOf(VoiceOutputEngine.SherpaExperimental, VoiceOutputEngine.KokoroExperimental),
                sherpaVoices = emptyList(),
                selectedOutputEngine = VoiceOutputEngine.KokoroExperimental,
            ),
        )

        cardButton(kokoroCardTag, "Download").performScrollTo().performClick()

        assertEquals(listOf("downloadKokoro"), calls)
    }

    @Test
    fun kokoroCardUsesExperimentalLabelAndDescription() {
        setSection(
            VoiceUiState(
                availableOutputEngines = listOf(VoiceOutputEngine.KokoroExperimental),
                sherpaVoices = emptyList(),
            ),
        )

        composeTestRule.onNode(hasText("Kokoro (experimental)") and hasAnyAncestor(hasTestTag(kokoroCardTag)))
            .assertExists()
        composeTestRule.onNode(
            hasText("Kokoro-82M speech on this device.", substring = true) and hasAnyAncestor(hasTestTag(kokoroCardTag)),
        ).assertExists()
    }

    @Test
    fun kokoroPackProgressCombinesAllFileBytes() {
        val models = KokoroSetup.kernelModels()
        val states = models.associateWith { DownloadState.NotDownloaded }.toMutableMap()
        states[models.first()] = DownloadState.Downloaded("first")
        states[models[1]] = DownloadState.Downloading(progress = 0.5f)
        val expectedBytes = models.first().approxSizeBytes + (models[1].approxSizeBytes * 0.5f).toLong()
        val expectedPercent = (expectedBytes.toFloat() / models.sumOf { it.approxSizeBytes } * 100).toInt()
        setSection(
            VoiceUiState(
                availableOutputEngines = listOf(VoiceOutputEngine.KokoroExperimental),
                selectedOutputEngine = VoiceOutputEngine.KokoroExperimental,
                sherpaVoices = emptyList(),
                kokoroStates = states,
            ),
        )

        composeTestRule.onNodeWithText("Downloading $expectedPercent%").assertExists()
        composeTestRule.onNodeWithText("Downloading 100%").assertDoesNotExist()
    }

    @Test
    fun kokoroWaitingForWifiOffersPackOnlyMobileData() {
        setSection(
            VoiceUiState(
                availableOutputEngines = listOf(VoiceOutputEngine.KokoroExperimental),
                selectedOutputEngine = VoiceOutputEngine.KokoroExperimental,
                sherpaVoices = emptyList(),
                kokoroStates = kokoroStates(DownloadState.Downloading(progress = 0f)),
                kokoroWaitingForWifi = true,
            ),
        )

        composeTestRule.onNodeWithText("Waiting for Wi-Fi. Keep Wi-Fi on, or use mobile data for this pack only.")
            .assertExists()
        cardButton(kokoroCardTag, "Use mobile data").performScrollTo().performClick()
        assertEquals(listOf("mobileDataKokoro"), calls)
    }

    @Test
    fun kokoroDownloadHiddenWhilePiperSelected() {
        setSection(
            VoiceUiState(
                availableOutputEngines = listOf(VoiceOutputEngine.SherpaExperimental, VoiceOutputEngine.KokoroExperimental),
                sherpaVoices = emptyList(),
                selectedOutputEngine = VoiceOutputEngine.SherpaExperimental,
            ),
        )

        cardButton(kokoroCardTag, "Download").assertDoesNotExist()
    }

    @Test
    fun kokoroDeleteHiddenWhilePiperSelected() {
        setSection(
            VoiceUiState(
                availableOutputEngines = listOf(VoiceOutputEngine.SherpaExperimental, VoiceOutputEngine.KokoroExperimental),
                sherpaVoices = emptyList(),
                selectedOutputEngine = VoiceOutputEngine.SherpaExperimental,
                kokoroStates = kokoroStates(DownloadState.Downloaded("")),
            ),
        )

        cardButton(kokoroCardTag, "Delete").assertDoesNotExist()
    }

    @Test
    fun kokoroDownloadFirstHintShownOnlyWhileKokoroSelectedAndNotDownloaded() {
        setSection(
            VoiceUiState(
                availableOutputEngines = listOf(VoiceOutputEngine.SherpaExperimental, VoiceOutputEngine.KokoroExperimental),
                sherpaVoices = emptyList(),
                selectedOutputEngine = VoiceOutputEngine.KokoroExperimental,
            ),
        )

        composeTestRule.onNode(hasText("Download the pack first", substring = true), useUnmergedTree = true).assertExists()
    }

    @Test
    fun kokoroDownloadFirstHintHiddenWhilePiperSelected() {
        setSection(
            VoiceUiState(
                availableOutputEngines = listOf(VoiceOutputEngine.SherpaExperimental, VoiceOutputEngine.KokoroExperimental),
                sherpaVoices = emptyList(),
                selectedOutputEngine = VoiceOutputEngine.SherpaExperimental,
            ),
        )

        composeTestRule.onNode(hasText("Download the pack first", substring = true), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun kokoroVoicePickerShownWhileKokoroSelectedAndDownloaded() {
        setSection(
            VoiceUiState(
                availableOutputEngines = listOf(VoiceOutputEngine.SherpaExperimental, VoiceOutputEngine.KokoroExperimental),
                sherpaVoices = emptyList(),
                selectedOutputEngine = VoiceOutputEngine.KokoroExperimental,
                kokoroStates = kokoroStates(DownloadState.Downloaded("")),
            ),
        )

        KokoroVoice.entries.forEach { voice ->
            composeTestRule.onNodeWithText(voice.displayName, substring = true).assertExists()
        }
    }

    @Test
    fun kokoroVoicePickerHiddenWhilePiperSelected() {
        setSection(
            VoiceUiState(
                availableOutputEngines = listOf(VoiceOutputEngine.SherpaExperimental, VoiceOutputEngine.KokoroExperimental),
                sherpaVoices = emptyList(),
                selectedOutputEngine = VoiceOutputEngine.SherpaExperimental,
                kokoroStates = kokoroStates(DownloadState.Downloaded("")),
            ),
        )

        composeTestRule.onNodeWithText(KokoroVoice.EMMA.displayName, substring = true).assertDoesNotExist()
    }

    @Test
    fun piperSpeakerSelectorShownForSelectedDownloadedMultiSpeakerVoice() {
        setSection(
            VoiceUiState(
                availableOutputEngines = listOf(VoiceOutputEngine.SherpaExperimental, VoiceOutputEngine.AndroidTts),
                selectedOutputEngine = VoiceOutputEngine.SherpaExperimental,
                selectedSherpaVoice = SherpaPiperVoice.VctkMedium,
                sherpaVoices = listOf(
                    SherpaVoiceRowUiState(SherpaPiperVoice.VctkMedium, VoicePackDownloadState.Downloaded("")),
                ),
            ),
        )

        composeTestRule.onNodeWithContentDescription("Collapse speaker selector").assertExists()
    }

    @Test
    fun piperSpeakerSelectorHiddenWhilePhoneVoiceSelected() {
        setSection(
            VoiceUiState(
                availableOutputEngines = listOf(VoiceOutputEngine.SherpaExperimental, VoiceOutputEngine.AndroidTts),
                selectedOutputEngine = VoiceOutputEngine.AndroidTts,
                selectedSherpaVoice = SherpaPiperVoice.VctkMedium,
                sherpaVoices = listOf(
                    SherpaVoiceRowUiState(SherpaPiperVoice.VctkMedium, VoicePackDownloadState.Downloaded("")),
                ),
            ),
        )

        composeTestRule.onAllNodesWithContentDescription("Collapse speaker selector").assertCountEquals(0)
    }

    @Test
    fun piperSpeakerSelectorHiddenForVoiceNotSelected() {
        setSection(
            VoiceUiState(
                availableOutputEngines = listOf(VoiceOutputEngine.SherpaExperimental, VoiceOutputEngine.AndroidTts),
                selectedOutputEngine = VoiceOutputEngine.SherpaExperimental,
                selectedSherpaVoice = SherpaPiperVoice.CoriHigh,
                sherpaVoices = listOf(
                    SherpaVoiceRowUiState(SherpaPiperVoice.CoriHigh, VoicePackDownloadState.Downloaded("")),
                    SherpaVoiceRowUiState(SherpaPiperVoice.VctkMedium, VoicePackDownloadState.Downloaded("")),
                ),
            ),
        )

        composeTestRule.onAllNodesWithContentDescription("Collapse speaker selector").assertCountEquals(0)
    }

    @Test
    fun waitingForWifiShownOnSelectedCoriCardWhilePhoneVoiceSelected() {
        setSection(
            VoiceUiState(
                availableOutputEngines = listOf(VoiceOutputEngine.SherpaExperimental, VoiceOutputEngine.AndroidTts),
                selectedOutputEngine = VoiceOutputEngine.AndroidTts,
                selectedSherpaVoice = SherpaPiperVoice.CoriHigh,
                sherpaVoices = listOf(
                    SherpaVoiceRowUiState(
                        SherpaPiperVoice.CoriHigh,
                        VoicePackDownloadState.Downloading(progress = 0.3f, waitingForWifi = true),
                    ),
                ),
            ),
        )
        val coriTag = piperCardTag(SherpaPiperVoice.CoriHigh)

        composeTestRule.onNode(
            hasText("Waiting for Wi-Fi", substring = true) and hasAnyAncestor(hasTestTag(coriTag)),
            useUnmergedTree = true,
        ).assertExists()
        composeTestRule.onNode(
            hasContentDescription("Use mobile data for Cori") and hasAnyAncestor(hasTestTag(coriTag)),
            useUnmergedTree = true,
        ).performScrollTo().performClick()

        assertEquals(listOf("mobileData(CoriHigh)"), calls)
    }

    private fun piperCardTag(voice: SherpaPiperVoice): String =
        speakingCardTestTag(SpeakingOption.PiperVoice(SherpaVoiceRowUiState(voice)))

    private fun tapCard(tag: String) {
        composeTestRule.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag(tag))).onFirst().performScrollTo().performClick()
    }

    private val inflectCardTag = speakingCardTestTag(SpeakingOption.InflectMicro)
    private val kokoroCardTag = speakingCardTestTag(SpeakingOption.KokoroPack)

    private fun kokoroStates(state: DownloadState) = KokoroSetup.kernelModels().associateWith { state }

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
                        onDownloadSherpaVoice = { calls += "download(${it.name})" },
                        onCancelSherpaVoice = { calls += "cancel(${it.name})" },
                        onDeleteSherpaVoice = { calls += "delete(${it.name})" },
                        onPlaySherpaSample = { calls += "sample(${it.name})" },
                        onUseMobileDataForVoice = { calls += "mobileData(${it.name})" },
                        onDownloadKokoroPack = { calls += "downloadKokoro" },
                        onCancelKokoroPack = { calls += "cancelKokoro" },
                        onDeleteKokoroPack = { calls += "deleteKokoro" },
                        onUseMobileDataForKokoro = { calls += "mobileDataKokoro" },
                        onDownloadInflectMicro = { calls += "downloadInflect" },
                        onCancelInflectMicro = { calls += "cancelInflect" },
                        onDeleteInflectMicro = { calls += "deleteInflect" },
                    )
                }
            }
        }
    }
}
