package com.kernel.ai.feature.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.kernel.ai.core.ui.theme.KernelAITheme
import com.kernel.ai.core.voice.OnlineSpeechCopy
import com.kernel.ai.core.voice.VoiceInputEngine
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

        assertSwitchInsideCard(selected = VoiceInputEngine.AndroidNative, otherCard = VoiceInputEngine.SherpaZipformer)
    }

    @Test
    fun androidNativeWarningAndUseAutomaticSitInsideTheAndroidNativeCard() {
        setSection(
            selected = VoiceInputEngine.AndroidNative,
            nativeMessage = "Android speech recognition is not available on this device.",
            zipformerDownloaded = false,
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
        setSection(selected = VoiceInputEngine.SherpaZipformer)

        composeTestRule
            .onAllNodesWithText(OnlineSpeechCopy.TITLE, useUnmergedTree = true)
            .assertCountEquals(0)
    }

    private fun setSection(
        selected: VoiceInputEngine,
        nativeMessage: String? = null,
        zipformerDownloaded: Boolean = true,
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
                            VoiceInputEngine.SherpaZipformer to SherpaSttDownloadState(isDownloaded = zipformerDownloaded),
                        ),
                    ),
                    onSelect = {},
                    onAllowOnline = {},
                    onDownload = {},
                    onCancel = {},
                    onDelete = {},
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
