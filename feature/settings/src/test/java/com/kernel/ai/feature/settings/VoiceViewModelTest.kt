package com.kernel.ai.feature.settings

import com.kernel.ai.core.voice.AndroidNativeRecognitionAvailability
import com.kernel.ai.core.voice.AndroidNativeRecognitionLocaleStatus
import com.kernel.ai.core.voice.AndroidNativeRecognitionSupport
import com.kernel.ai.core.voice.SherpaPiperVoice
import com.kernel.ai.core.voice.SherpaVoicePackDownloadManager
import com.kernel.ai.core.voice.VoiceInputEngine
import com.kernel.ai.core.voice.VoiceInputPreferences
import com.kernel.ai.core.voice.VoicePackDownloadState
import com.kernel.ai.core.voice.VoiceOutputEngine
import com.kernel.ai.core.voice.VoiceOutputPreferences
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val androidNativeRecognitionSupport: AndroidNativeRecognitionSupport = mockk()
    private val voiceInputPreferences: VoiceInputPreferences = mockk()
    private val voiceOutputPreferences: VoiceOutputPreferences = mockk()
    private val sherpaVoicePackDownloadManager: SherpaVoicePackDownloadManager = mockk()
    private val selectedInputEngine = MutableStateFlow(VoiceInputEngine.Vosk)
    private val autoStartAlertVoiceCommandsEnabled = MutableStateFlow(true)
    private val spokenResponsesEnabled = MutableStateFlow(true)
    private val selectedOutputEngine = MutableStateFlow(VoiceOutputEngine.AndroidTts)
    private val selectedSherpaVoice = MutableStateFlow(SherpaPiperVoice.JennyDioco)
    private val sherpaDownloadStates: MutableStateFlow<Map<SherpaPiperVoice, VoicePackDownloadState>> =
        MutableStateFlow(
            SherpaPiperVoice.entries.associateWith {
                VoicePackDownloadState.NotDownloaded
            },
        )

    private lateinit var viewModel: VoiceViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        coEvery { androidNativeRecognitionSupport.getAvailability() } returns
            AndroidNativeRecognitionAvailability(
                isRecognitionAvailable = true,
                isOnDeviceRecognitionAvailable = true,
                languageTag = "en-NZ",
                languageDisplayName = "English (New Zealand)",
                localeStatus = AndroidNativeRecognitionLocaleStatus.Ready,
            )
        every { voiceInputPreferences.selectedEngine } returns selectedInputEngine
        every { voiceInputPreferences.autoStartAlertVoiceCommandsEnabled } returns autoStartAlertVoiceCommandsEnabled
        coEvery { voiceInputPreferences.setSelectedEngine(any()) } just Runs
        coEvery { voiceInputPreferences.setAutoStartAlertVoiceCommandsEnabled(any()) } just Runs
        every { voiceOutputPreferences.spokenResponsesEnabled } returns spokenResponsesEnabled
        every { voiceOutputPreferences.selectedEngine } returns selectedOutputEngine
        every { voiceOutputPreferences.selectedSherpaVoice } returns selectedSherpaVoice
        coEvery { voiceOutputPreferences.setSpokenResponsesEnabled(any()) } just Runs
        coEvery { voiceOutputPreferences.setSelectedEngine(any()) } just Runs
        coEvery { voiceOutputPreferences.setSelectedSherpaVoice(any()) } just Runs
        every { sherpaVoicePackDownloadManager.downloadStates } returns sherpaDownloadStates
        every { sherpaVoicePackDownloadManager.startDownload(any()) } just Runs
        every { sherpaVoicePackDownloadManager.cancelDownload(any()) } just Runs
        every { sherpaVoicePackDownloadManager.deleteVoice(any()) } just Runs
        viewModel = VoiceViewModel(
            androidNativeRecognitionSupport,
            voiceInputPreferences,
            voiceOutputPreferences,
            sherpaVoicePackDownloadManager,
        )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `alert voice auto start defaults to enabled when preference flow emits true`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.autoStartAlertVoiceCommandsEnabled)
    }


    @Test
    fun `spoken responses default to enabled when preference flow emits true`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.spokenResponsesEnabled)
    }

    @Test
    fun `voice input engine defaults to vosk when preference flow emits vosk`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(VoiceInputEngine.Vosk, viewModel.uiState.value.selectedInputEngine)
    }

    @Test
    fun `android native language summary is exposed from recognizer support`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            "English (New Zealand) (en-NZ)",
            viewModel.uiState.value.androidNativeLanguageSummary,
        )
    }

    @Test
    fun `android native availability message is exposed when on-device recognizer is unavailable`() = runTest {
        coEvery { androidNativeRecognitionSupport.getAvailability() } returns
            AndroidNativeRecognitionAvailability(
                isRecognitionAvailable = true,
                isOnDeviceRecognitionAvailable = false,
                languageTag = "en-US",
                languageDisplayName = "English (United States)",
                localeStatus = AndroidNativeRecognitionLocaleStatus.Unknown,
            )

        viewModel = VoiceViewModel(
            androidNativeRecognitionSupport,
            voiceInputPreferences,
            voiceOutputPreferences,
            sherpaVoicePackDownloadManager,
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            "On-device Android speech recognition is unavailable for the current setup. Install the required language pack or keep using Vosk for guaranteed local voice input.",
            viewModel.uiState.value.androidNativeAvailabilityMessage,
        )
    }

    @Test
    fun `android native availability message is exposed when locale is unsupported`() = runTest {
        coEvery { androidNativeRecognitionSupport.getAvailability() } returns
            AndroidNativeRecognitionAvailability(
                isRecognitionAvailable = true,
                isOnDeviceRecognitionAvailable = true,
                languageTag = "en-NZ",
                languageDisplayName = "English (New Zealand)",
                localeStatus = AndroidNativeRecognitionLocaleStatus.NotSupported,
            )

        viewModel = VoiceViewModel(
            androidNativeRecognitionSupport,
            voiceInputPreferences,
            voiceOutputPreferences,
            sherpaVoicePackDownloadManager,
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            "English (New Zealand) is not supported by Android native speech recognition on this device.",
            viewModel.uiState.value.androidNativeAvailabilityMessage,
        )
    }

    @Test
    fun `android native availability message is exposed when locale support cannot be verified`() = runTest {
        coEvery { androidNativeRecognitionSupport.getAvailability() } returns
            AndroidNativeRecognitionAvailability(
                isRecognitionAvailable = true,
                isOnDeviceRecognitionAvailable = true,
                languageTag = "en-NZ",
                languageDisplayName = "English (New Zealand)",
                localeStatus = AndroidNativeRecognitionLocaleStatus.Unknown,
            )

        viewModel = VoiceViewModel(
            androidNativeRecognitionSupport,
            voiceInputPreferences,
            voiceOutputPreferences,
            sherpaVoicePackDownloadManager,
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            "Android native speech recognition could not verify on-device support for English (New Zealand) on this device. It may fail unless that language is supported and installed locally.",
            viewModel.uiState.value.androidNativeAvailabilityMessage,
        )
    }

    @Test
    fun `setVoiceInputEngine updates ui state immediately`() = runTest {
        viewModel.setVoiceInputEngine(VoiceInputEngine.AndroidNative)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(VoiceInputEngine.AndroidNative, viewModel.uiState.value.selectedInputEngine)
        coVerify { voiceInputPreferences.setSelectedEngine(VoiceInputEngine.AndroidNative) }
    }

    @Test
    fun `setAutoStartAlertVoiceCommandsEnabled updates ui state immediately`() = runTest {
        viewModel.setAutoStartAlertVoiceCommandsEnabled(false)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(false, viewModel.uiState.value.autoStartAlertVoiceCommandsEnabled)
        coVerify { voiceInputPreferences.setAutoStartAlertVoiceCommandsEnabled(false) }
    }


    @Test
    fun `setSpokenResponsesEnabled updates ui state immediately`() = runTest {
        viewModel.setSpokenResponsesEnabled(false)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(false, viewModel.uiState.value.spokenResponsesEnabled)
        coVerify { voiceOutputPreferences.setSpokenResponsesEnabled(false) }
    }

    @Test
    fun `voice output engine defaults to Android TTS when preference flow emits Android TTS`() =
        runTest {
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(
                VoiceOutputEngine.AndroidTts,
                viewModel.uiState.value.selectedOutputEngine,
            )
        }

    @Test
    fun `selected Sherpa voice defaults to Jenny when preference flow emits Jenny`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(SherpaPiperVoice.JennyDioco, viewModel.uiState.value.selectedSherpaVoice)
    }

    @Test
    fun `Sherpa voice download states are exposed for each voice row`() = runTest {
        sherpaDownloadStates.value = mapOf(
            SherpaPiperVoice.JennyDioco to VoicePackDownloadState.Downloaded("/voices/jenny"),
            SherpaPiperVoice.SouthernEnglishFemale to VoicePackDownloadState.Downloading(progress = 0.5f),
        )

        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            VoicePackDownloadState.Downloaded("/voices/jenny"),
            viewModel.uiState.value.sherpaVoices.first { it.voice == SherpaPiperVoice.JennyDioco }.downloadState,
        )
        assertEquals(
            VoicePackDownloadState.Downloading(progress = 0.5f),
            viewModel.uiState.value.sherpaVoices.first { it.voice == SherpaPiperVoice.SouthernEnglishFemale }.downloadState,
        )
        assertEquals(
            VoicePackDownloadState.NotDownloaded,
            viewModel.uiState.value.sherpaVoices.first { it.voice == SherpaPiperVoice.NorthernEnglishMale }.downloadState,
        )
    }

    @Test
    fun `setVoiceOutputEngine updates ui state immediately`() = runTest {
        viewModel.setVoiceOutputEngine(VoiceOutputEngine.SherpaExperimental)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            VoiceOutputEngine.SherpaExperimental,
            viewModel.uiState.value.selectedOutputEngine,
        )
        coVerify { voiceOutputPreferences.setSelectedEngine(VoiceOutputEngine.SherpaExperimental) }
    }

    @Test
    fun `setSherpaVoice updates ui state immediately`() = runTest {
        sherpaDownloadStates.value = mapOf(
            SherpaPiperVoice.NorthernEnglishMale to VoicePackDownloadState.Downloaded("/voices/northern"),
        )
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.setSherpaVoice(SherpaPiperVoice.NorthernEnglishMale)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            SherpaPiperVoice.NorthernEnglishMale,
            viewModel.uiState.value.selectedSherpaVoice,
        )
        coVerify { voiceOutputPreferences.setSelectedSherpaVoice(SherpaPiperVoice.NorthernEnglishMale) }
    }

    @Test
    fun `setSherpaVoice ignores undownloaded voices`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.setSherpaVoice(SherpaPiperVoice.NorthernEnglishMale)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(SherpaPiperVoice.JennyDioco, viewModel.uiState.value.selectedSherpaVoice)
        coVerify(exactly = 0) {
            voiceOutputPreferences.setSelectedSherpaVoice(SherpaPiperVoice.NorthernEnglishMale)
        }
    }

    @Test
    fun `Sherpa download flags reflect available and selected voices`() = runTest {
        sherpaDownloadStates.value = mapOf(
            SherpaPiperVoice.JennyDioco to VoicePackDownloadState.Downloaded("/voices/jenny"),
            SherpaPiperVoice.AlanMedium to VoicePackDownloadState.Downloaded("/voices/alan"),
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.hasDownloadedSherpaVoice)
        assertTrue(viewModel.uiState.value.isSelectedSherpaVoiceDownloaded)
    }

    @Test
    fun `downloadSherpaVoice delegates to the voice pack download manager`() = runTest {
        viewModel.downloadSherpaVoice(SherpaPiperVoice.JennyDioco)

        io.mockk.verify { sherpaVoicePackDownloadManager.startDownload(SherpaPiperVoice.JennyDioco) }
    }

    @Test
    fun `cancelSherpaVoiceDownload delegates to the voice pack download manager`() = runTest {
        viewModel.cancelSherpaVoiceDownload(SherpaPiperVoice.SouthernEnglishFemale)

        io.mockk.verify {
            sherpaVoicePackDownloadManager.cancelDownload(SherpaPiperVoice.SouthernEnglishFemale)
        }
    }

    @Test
    fun `deleteSherpaVoice delegates to the voice pack download manager`() = runTest {
        viewModel.deleteSherpaVoice(SherpaPiperVoice.NorthernEnglishMale)

        io.mockk.verify { sherpaVoicePackDownloadManager.deleteVoice(SherpaPiperVoice.NorthernEnglishMale) }
    }
}
