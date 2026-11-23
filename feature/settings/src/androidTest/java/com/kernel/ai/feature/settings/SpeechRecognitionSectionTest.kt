package com.kernel.ai.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.kernel.ai.core.ui.theme.KernelAITheme
import com.kernel.ai.core.voice.OnlineSpeechCopy
import com.kernel.ai.core.voice.VoiceInputEngine
import com.kernel.ai.core.voice.asr4all.Asr4AllSize
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SpeechRecognitionSectionTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun onlineSwitchSitsInsideSelectedAutomaticCard() {
        setSection(selected = VoiceInputEngine.Auto)

        assertSwitchInsideCard(selected = VoiceInputEngine.Auto, otherCard = VoiceInputEngine.AndroidNative)
    }

    @Test
    fun onlineSwitchSitsInsideSelectedAndroidNativeCard() {
        setSection(selected = VoiceInputEngine.AndroidNative)

        assertSwitchInsideCard(selected = VoiceInputEngine.AndroidNative, otherCard = VoiceInputEngine.SherpaWhisper)
    }

    @Test
    fun androidNativeWarningAndUseAutomaticSitInsideTheAndroidNativeCard() {
        setSection(
            selected = VoiceInputEngine.AndroidNative,
            nativeMessage = "Android speech recognition is not available on this device.",
            whisperDownloaded = false,
        )

        composeTestRule
            .onNode(
                hasText("Use Automatic") and
                    hasAnyAncestor(hasTestTag(speechEngineTestTag(VoiceInputEngine.AndroidNative))),
                useUnmergedTree = true,
            )
            .assertExists()
    }

    @Test
    fun onlineSwitchIsAbsentWhenSherpaEngineSelected() {
        setSection(selected = VoiceInputEngine.SherpaWhisper)

        composeTestRule
            .onAllNodesWithText(OnlineSpeechCopy.TITLE, useUnmergedTree = true)
            .assertCountEquals(0)
    }

    @Test
    fun termsRowScrollsIntoViewWhenDownloadStatesArriveAfterFirstFrame() {
        var uiState by mutableStateOf(
            VoiceUiState(sherpaVoices = emptyList(), selectedInputEngine = VoiceInputEngine.Auto, termsAccepted = false),
        )
        composeTestRule.setContent {
            KernelAITheme(darkTheme = true, dynamicColor = false) {
                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    SpeechRecognitionSection(
                        uiState = uiState,
                        onSelect = {},
                        onAllowOnline = {},
                        onDownload = {},
                        onCancel = {},
                        onDelete = {},
                        onUseMobileData = {},
                        onAsr4allSize = {},
                        scrollToTermsRow = true,
                    )
                }
            }
        }
        // The ViewModel's first frame has no download states; they arrive later and grow the cards above asr4all.
        composeTestRule.runOnIdle {
            uiState = uiState.copy(
                sherpaSttStates = VoiceInputEngine.entries
                    .filter { it.hasDownloadableModel }
                    .associateWith { SherpaSttDownloadState(isDownloaded = false) },
            )
        }
        composeTestRule
            .onNode(
                hasText("Read the Terms of Use") and
                    hasAnyAncestor(hasTestTag(speechEngineTestTag(VoiceInputEngine.Asr4all))),
                useUnmergedTree = true,
            )
            .assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun asr4allSizeChipIsAtLeastHalfRootWidthAndAtLeast48dpAtLargeFont() {
        var density: Density? = null
        composeTestRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                density = LocalDensity.current
                KernelAITheme(darkTheme = true, dynamicColor = false) {
                    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        SpeechRecognitionSection(
                            uiState = VoiceUiState(
                                sherpaVoices = emptyList(),
                                selectedInputEngine = VoiceInputEngine.Asr4all,
                                termsAccepted = true,
                                asr4allSize = Asr4AllSize.M,
                                asr4allSizes = listOf(Asr4AllSize.M, Asr4AllSize.L),
                                sherpaSttStates = mapOf(
                                    VoiceInputEngine.Asr4all to SherpaSttDownloadState(isDownloaded = true),
                                ),
                            ),
                            onSelect = {},
                            onAllowOnline = {},
                            onDownload = {},
                            onCancel = {},
                            onDelete = {},
                            onUseMobileData = {},
                            onAsr4allSize = {},
                        )
                    }
                }
            }
        }
        val chipNode = composeTestRule.onNode(hasText("L · about 101 MB") and hasClickAction())
        chipNode.performScrollTo().assertIsDisplayed()
        val chip = chipNode.fetchSemanticsNode()
        val root = composeTestRule.onRoot().fetchSemanticsNode()
        assertTrue("root has no layout; test would pass vacuously", root.size.width > 0)
        val minTouchPx = with(requireNotNull(density)) { 48.dp.roundToPx() }
        // Proxy for "not squeezed": the chip spans at least half the root width. Chips size to content, so this is not a full-width check.
        assertTrue("chip ${chip.size} squeezed in root ${root.size}", chip.size.width >= root.size.width / 2)
        assertTrue("chip height ${chip.size.height}px below 48dp (${minTouchPx}px)", chip.size.height >= minTouchPx)
    }

    private fun setSection(
        selected: VoiceInputEngine,
        nativeMessage: String? = null,
        whisperDownloaded: Boolean = true,
    ) {
        composeTestRule.setContent {
            KernelAITheme(darkTheme = true, dynamicColor = false) {
                SpeechRecognitionSection(
                    uiState = VoiceUiState(
                        sherpaVoices = emptyList(),
                        selectedInputEngine = selected,
                        allowOnlineSpeechFallback = false,
                        androidNativeAvailabilityMessage = nativeMessage,
                        androidNativeLanguageName = "English (Australia)",
                        sherpaSttStates = mapOf(
                            VoiceInputEngine.SherpaWhisper to SherpaSttDownloadState(isDownloaded = whisperDownloaded),
                        ),
                    ),
                    onSelect = {},
                    onAllowOnline = {},
                    onDownload = {},
                    onCancel = {},
                    onDelete = {},
                    onUseMobileData = {},
                    onAsr4allSize = {},
                )
            }
        }
    }

    private fun assertSwitchInsideCard(selected: VoiceInputEngine, otherCard: VoiceInputEngine) {
        composeTestRule
            .onNode(
                hasText(OnlineSpeechCopy.TITLE) and hasAnyAncestor(hasTestTag(speechEngineTestTag(selected))),
                useUnmergedTree = true,
            )
            .assertExists()
        composeTestRule
            .onNode(
                hasText(OnlineSpeechCopy.TITLE) and hasAnyAncestor(hasTestTag(speechEngineTestTag(otherCard))),
                useUnmergedTree = true,
            )
            .assertDoesNotExist()
    }
}
