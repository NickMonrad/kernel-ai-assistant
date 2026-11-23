package com.kernel.ai.feature.settings

import android.content.Context
import com.kernel.ai.core.inference.download.DownloadState
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.hardware.HardwareProfileDetector
import com.kernel.ai.core.inference.hardware.HardwareTier
import com.kernel.ai.core.inference.download.ModelDownloadManager
import com.kernel.ai.core.voice.WakeWordDetector
import com.kernel.ai.core.voice.Asr4AllInstall
import com.kernel.ai.core.voice.asr4all.Asr4AllSize
import com.kernel.ai.core.voice.WakeWordPreferences
import com.kernel.ai.core.voice.WAKE_WORD_DEFAULT_THRESHOLD
import com.kernel.ai.core.voice.AndroidNativeRecognitionAvailability
import com.kernel.ai.core.voice.AndroidNativeRecognitionLocaleStatus
import com.kernel.ai.core.voice.AndroidNativeRecognitionSupport
import com.kernel.ai.core.voice.InflectMicroModelSpec
import com.kernel.ai.core.voice.SherpaKokoroVoice
import com.kernel.ai.core.voice.PiperVoiceSetup
import com.kernel.ai.core.voice.Asr4allSizeSwitcher
import com.kernel.ai.core.voice.Asr4allSwitchFailure
import com.kernel.ai.core.voice.Asr4allSwitchState
import com.kernel.ai.core.voice.SherpaOnnxVoiceOutputController
import com.kernel.ai.core.voice.VoiceOutputResult
import com.kernel.ai.core.voice.SherpaPiperVoice
import com.kernel.ai.core.voice.SherpaVoicePackDownloadManager
import com.kernel.ai.core.voice.VoiceInputEngine
import com.kernel.ai.core.voice.VoiceDefaultsProvisioner
import com.kernel.ai.core.voice.wakeVerifierKernelModels
import com.kernel.ai.core.voice.VoiceInputPreferences
import com.kernel.ai.core.voice.VoiceOutputEngine
import com.kernel.ai.core.voice.VoiceOutputPreferences
import com.kernel.ai.core.voice.VoicePackDownloadState
import com.kernel.ai.core.permissions.MicrophoneReadiness
import com.kernel.ai.core.model.availability.ActionReason
import com.kernel.ai.core.model.availability.ModelAvailabilityState
import io.mockk.Runs
import com.kernel.ai.core.inference.download.localFile
import com.kernel.ai.core.inference.terms.TERMS_NOT_ACCEPTED_MESSAGE
import com.kernel.ai.core.inference.terms.TERMS_VERSION
import com.kernel.ai.core.inference.terms.TermsAcceptancePreferences
import com.kernel.ai.core.inference.terms.TermsAcceptanceRecord
 import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import android.content.pm.ApplicationInfo

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val androidNativeRecognitionSupport: AndroidNativeRecognitionSupport = mockk()
    private val voiceInputPreferences: VoiceInputPreferences = mockk()
    private val voiceOutputPreferences: VoiceOutputPreferences = mockk()
    private val sherpaVoicePackDownloadManager: SherpaVoicePackDownloadManager = mockk()
    private val wakeWordPreferences: WakeWordPreferences = mockk(relaxed = true)
    private val wakeWordDetector: WakeWordDetector = mockk()
    private val modelDownloadManager: ModelDownloadManager = mockk()
    private val hardwareProfileDetector: HardwareProfileDetector = mockk(relaxed = true)
    private val context: Context = mockk(relaxed = true)
    private val heyJandalEnabled = MutableStateFlow(false)
    private val wakeWordThreshold = MutableStateFlow(WAKE_WORD_DEFAULT_THRESHOLD)
    private val selectedInputEngine = MutableStateFlow(VoiceInputEngine.Auto)
    private val autoStartAlertVoiceCommandsEnabled = MutableStateFlow(true)
    private val allowOnlineSpeechFallback = MutableStateFlow(false)
    private val asr4allSize = MutableStateFlow(Asr4AllSize.M)
    private val spokenResponsesEnabled = MutableStateFlow(true)
    private val selectedOutputEngine = MutableStateFlow(VoiceOutputEngine.AndroidTts)
    private val selectedSherpaVoice = MutableStateFlow(SherpaPiperVoice.JennyDioco)
    private val sherpaSpeed = MutableStateFlow(0.85f)
    private val voicePitch = MutableStateFlow(1.0f)
    private val voiceGain = MutableStateFlow(1.5f)
    private val autoSpeak = MutableStateFlow(true)
    private val maxSpokenSentences = MutableStateFlow(0)
    private val activeSpeakerId = MutableStateFlow(0)
    private val selectedKokoroVoice = MutableStateFlow(SherpaKokoroVoice.KokoroMultiLangInt8)
    private val kokoroActiveSpeakerId = MutableStateFlow(0)
    private val sherpaDownloadStates: MutableStateFlow<Map<SherpaPiperVoice, VoicePackDownloadState>> =
        MutableStateFlow(
            SherpaPiperVoice.entries.associateWith {
                VoicePackDownloadState.NotDownloaded
            },
        )
    private val kokoroDownloadStates: MutableStateFlow<Map<SherpaKokoroVoice, VoicePackDownloadState>> =
        MutableStateFlow(
            SherpaKokoroVoice.entries.associateWith {
                VoicePackDownloadState.NotDownloaded
            },
        )
    private val modelDownloadStates: MutableStateFlow<Map<KernelModel, DownloadState>> =
        MutableStateFlow(
            KernelModel.entries.associateWith { DownloadState.NotDownloaded as DownloadState },
        )
    private lateinit var viewModel: VoiceViewModel
    private val sherpaVoiceOutputController: SherpaOnnxVoiceOutputController = mockk(relaxed = true)
    private val piperVoiceSetup: PiperVoiceSetup = mockk(relaxed = true)
    private val asr4allSwitchState = MutableStateFlow(Asr4allSwitchState())
    private val termsRecord = MutableStateFlow(TermsAcceptanceRecord())
    private val termsAcceptancePreferences: TermsAcceptancePreferences = mockk(relaxed = true)
    private val asr4allSizeSwitcher: Asr4allSizeSwitcher = mockk(relaxed = true) {
        every { state } returns asr4allSwitchState
    }
    @org.junit.jupiter.api.io.TempDir
    lateinit var tempDir: java.nio.file.Path

    @Test
    fun `play sample speaks through Sherpa only for a downloaded voice`() = runTest {
        sherpaDownloadStates.value = mapOf(
            SherpaPiperVoice.JennyDioco to VoicePackDownloadState.Downloaded("/voices/jenny"),
        )
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.playSherpaSample(SherpaPiperVoice.JennyDioco)
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 1) { sherpaVoiceOutputController.speak(any()) }

        viewModel.playSherpaSample(SherpaPiperVoice.NorthernEnglishMale)
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 1) { sherpaVoiceOutputController.speak(any()) }
    }

    @Test
    fun `failed Play sample shows a retry message instead of the engine error`() = runTest {
        sherpaDownloadStates.value = mapOf(
            SherpaPiperVoice.JennyDioco to VoicePackDownloadState.Downloaded("/voices/jenny"),
        )
        coEvery { sherpaVoiceOutputController.speak(any()) } returns
            VoiceOutputResult.Unavailable("Sherpa generate failed: test")
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.playSherpaSample(SherpaPiperVoice.JennyDioco)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(SAMPLE_FAILED_MESSAGE, viewModel.uiState.value.sherpaSampleError)
    }

    private fun newViewModel() = VoiceViewModel(
        androidNativeRecognitionSupport,
        voiceInputPreferences,
        voiceOutputPreferences,
        sherpaVoicePackDownloadManager,
        wakeWordPreferences,
        wakeWordDetector,
        modelDownloadManager,
        hardwareProfileDetector,
        context,
        sherpaVoiceOutputController,
        piperVoiceSetup,
        asr4allSizeSwitcher,
        termsAcceptancePreferences,
    )

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
        every { voiceInputPreferences.allowOnlineSpeechFallback } returns allowOnlineSpeechFallback
        every { voiceInputPreferences.asr4allSize } returns asr4allSize
        coEvery { voiceInputPreferences.setAsr4allSize(any()) } just Runs
        coEvery { voiceInputPreferences.setSelectedEngine(any()) } just Runs
        coEvery { voiceInputPreferences.setAutoStartAlertVoiceCommandsEnabled(any()) } just Runs
        every { voiceOutputPreferences.spokenResponsesEnabled } returns spokenResponsesEnabled
        every { voiceOutputPreferences.selectedEngine } returns selectedOutputEngine
        every { voiceOutputPreferences.selectedSherpaVoice } returns selectedSherpaVoice
        every { voiceOutputPreferences.sherpaSpeed } returns sherpaSpeed
        every { voiceOutputPreferences.voicePitch } returns voicePitch
        every { voiceOutputPreferences.voiceGain } returns voiceGain
        every { voiceOutputPreferences.autoSpeak } returns autoSpeak
        every { voiceOutputPreferences.maxSpokenSentences } returns maxSpokenSentences
        every { voiceOutputPreferences.activeSpeakerId } returns activeSpeakerId
        every { voiceOutputPreferences.selectedKokoroVoice } returns selectedKokoroVoice
        every { voiceOutputPreferences.kokoroActiveSpeakerId } returns kokoroActiveSpeakerId
        coEvery { voiceOutputPreferences.setSpokenResponsesEnabled(any()) } just Runs
        coEvery { voiceOutputPreferences.setSelectedEngine(any()) } just Runs
        coEvery { voiceOutputPreferences.setSelectedSherpaVoice(any()) } just Runs
        coEvery { voiceOutputPreferences.setSherpaSpeed(any()) } just Runs
        coEvery { voiceOutputPreferences.setVoicePitch(any()) } just Runs
        coEvery { voiceOutputPreferences.setVoiceGain(any()) } just Runs
        coEvery { voiceOutputPreferences.setAutoSpeak(any()) } just Runs
        coEvery { voiceOutputPreferences.setMaxSpokenSentences(any()) } just Runs
        coEvery { voiceOutputPreferences.setActiveSpeakerId(any()) } just Runs
        coEvery { voiceOutputPreferences.setSelectedKokoroVoice(any()) } just Runs
        coEvery { voiceOutputPreferences.setKokoroActiveSpeakerId(any()) } just Runs
        every { wakeWordPreferences.heyJandalEnabled } returns heyJandalEnabled
        every { wakeWordPreferences.confidenceThreshold } returns wakeWordThreshold
        coEvery { wakeWordPreferences.setConfidenceThreshold(any()) } coAnswers {
            delay(1)
            wakeWordThreshold.value = firstArg()
        }
        every { sherpaVoicePackDownloadManager.downloadStates } returns sherpaDownloadStates
        every { wakeWordDetector.isAvailable } returns false
        every { sherpaVoicePackDownloadManager.kokoroDownloadStates } returns kokoroDownloadStates
        every { modelDownloadManager.downloadStates } returns modelDownloadStates
        every { modelDownloadManager.waitingForWifi } returns MutableStateFlow(emptySet())
        every { termsAcceptancePreferences.record } returns termsRecord
        every { hardwareProfileDetector.profile.tier } returns HardwareTier.FLAGSHIP
        every { modelDownloadManager.startDownload(any()) } just Runs
        every { modelDownloadManager.cancelDownload(any()) } just Runs
        every { sherpaVoicePackDownloadManager.startDownload(any()) } just Runs
        every { sherpaVoicePackDownloadManager.cancelDownload(any()) } just Runs
        every { sherpaVoicePackDownloadManager.deleteVoice(any()) } just Runs
        every { sherpaVoicePackDownloadManager.startKokoroDownload(any()) } just Runs
        every { sherpaVoicePackDownloadManager.cancelKokoroDownload(any()) } just Runs
        every { sherpaVoicePackDownloadManager.deleteKokoroVoice(any()) } just Runs
        every { context.applicationInfo } returns ApplicationInfo().apply {
            flags = ApplicationInfo.FLAG_DEBUGGABLE
        }
        viewModel = newViewModel()
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
    fun `voice input engine defaults to auto when preference flow emits auto`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(VoiceInputEngine.Auto, viewModel.uiState.value.selectedInputEngine)
    }

    @Test
    fun `online speech fallback is off by default and follows the preference`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(false, viewModel.uiState.value.allowOnlineSpeechFallback)

        allowOnlineSpeechFallback.value = true
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(true, viewModel.uiState.value.allowOnlineSpeechFallback)
    }

    @Test
    fun `Hey Jandal size with Android native and nothing downloaded is the verifier`() = runTest {
        selectedInputEngine.value = VoiceInputEngine.AndroidNative
        testDispatcher.scheduler.advanceUntilIdle()

        val expected = wakeVerifierKernelModels().sumOf { it.approxSizeBytes }
        assertEquals(expected, viewModel.uiState.value.heyJandalDownloadBytes)
    }

    @Test
    fun `Hey Jandal size drops files as they finish downloading`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        modelDownloadStates.value = KernelModel.entries.associateWith {
            DownloadState.Downloaded("/models/${it.fileName}") as DownloadState
        }
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(0L, viewModel.uiState.value.heyJandalDownloadBytes)
    }

    @Test
    fun `android native language name is exposed from recognizer support`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            "English (New Zealand)",
            viewModel.uiState.value.androidNativeLanguageName,
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
            wakeWordPreferences,
            wakeWordDetector,
            modelDownloadManager,
            hardwareProfileDetector,
            context,
            sherpaVoiceOutputController,
            piperVoiceSetup,
            asr4allSizeSwitcher,
            termsAcceptancePreferences,
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            "On-device Android speech recognition is unavailable for the current setup. Install the required language pack, or use Automatic for guaranteed local voice input.",
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
            wakeWordPreferences,
            wakeWordDetector,
            modelDownloadManager,
            hardwareProfileDetector,
            context,
            sherpaVoiceOutputController,
            piperVoiceSetup,
            asr4allSizeSwitcher,
            termsAcceptancePreferences,
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            "English (New Zealand) is not supported by Android native speech recognition on this device.",
            viewModel.uiState.value.androidNativeAvailabilityMessage,
        )
    }

    @Test
    fun `android native availability message stays empty when locale support cannot be verified`() = runTest {
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
            wakeWordPreferences,
            wakeWordDetector,
            modelDownloadManager,
            hardwareProfileDetector,
            context,
            sherpaVoiceOutputController,
            piperVoiceSetup,
            asr4allSizeSwitcher,
            termsAcceptancePreferences,
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.androidNativeAvailabilityMessage)
    }

    @Test
    fun `resolveAndroidNativeAvailabilityMessage returns platform warning`() {
        val availability = AndroidNativeRecognitionAvailability(
            isRecognitionAvailable = true,
            isOnDeviceRecognitionAvailable = false,
            languageTag = "en-US",
            languageDisplayName = "English (United States)",
            localeStatus = AndroidNativeRecognitionLocaleStatus.Unknown,
        )

        assertEquals(
            "On-device Android speech recognition is unavailable for the current setup. Install the required language pack, or use Automatic for guaranteed local voice input.",
            resolveAndroidNativeAvailabilityMessage(availability),
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
    fun `release builds filter non-release Sherpa voices from ui state`() = runTest {
        val releaseContext: Context = mockk(relaxed = true)
        every { releaseContext.applicationInfo } returns ApplicationInfo().apply { flags = 0 }
        val releaseViewModel = VoiceViewModel(
            androidNativeRecognitionSupport,
            voiceInputPreferences,
            voiceOutputPreferences,
            sherpaVoicePackDownloadManager,
            wakeWordPreferences,
            wakeWordDetector,
            modelDownloadManager,
            hardwareProfileDetector,
            releaseContext,
            sherpaVoiceOutputController,
            piperVoiceSetup,
            asr4allSizeSwitcher,
            termsAcceptancePreferences,
        )

        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            listOf(SherpaPiperVoice.CoriHigh),
            releaseViewModel.uiState.value.sherpaVoices.map { it.voice },
        )
    }
    @Test
    fun `release builds expose Android TTS and Sherpa while debug builds keep Inflect`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(
            viewModel.uiState.value.availableOutputEngines.contains(
                VoiceOutputEngine.InflectMicroExperimental,
            ),
        )

        val releaseContext: Context = mockk(relaxed = true)
        every { releaseContext.applicationInfo } returns ApplicationInfo().apply { flags = 0 }
        val releaseViewModel = VoiceViewModel(
            androidNativeRecognitionSupport,
            voiceInputPreferences,
            voiceOutputPreferences,
            sherpaVoicePackDownloadManager,
            wakeWordPreferences,
            wakeWordDetector,
            modelDownloadManager,
            hardwareProfileDetector,
            releaseContext,
            sherpaVoiceOutputController,
            piperVoiceSetup,
            asr4allSizeSwitcher,
            termsAcceptancePreferences,
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            listOf(VoiceOutputEngine.AndroidTts, VoiceOutputEngine.SherpaExperimental),
            releaseViewModel.uiState.value.availableOutputEngines,
        )
    }

    @Test
    fun `release builds offer asr4all and start its model downloads`() = runTest {
        val releaseContext: Context = mockk(relaxed = true)
        every { releaseContext.applicationInfo } returns ApplicationInfo().apply { flags = 0 }
        val releaseViewModel = VoiceViewModel(
            androidNativeRecognitionSupport,
            voiceInputPreferences,
            voiceOutputPreferences,
            sherpaVoicePackDownloadManager,
            wakeWordPreferences,
            wakeWordDetector,
            modelDownloadManager,
            hardwareProfileDetector,
            releaseContext,
            sherpaVoiceOutputController,
            piperVoiceSetup,
            asr4allSizeSwitcher,
            termsAcceptancePreferences,
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(VoiceInputEngine.Asr4all in releaseViewModel.uiState.value.availableInputEngines)

        releaseViewModel.downloadSherpaStt(VoiceInputEngine.Asr4all)

        Asr4AllInstall.models(Asr4AllSize.M).forEach { model ->
            verify { modelDownloadManager.startDownload(model) }
        }
    }

    @Test
    fun `both Inflect graphs missing map to one downloadable logical model`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            ModelAvailabilityState.NotDisplayed,
            viewModel.uiState.value.inflectMicroAvailability,
        )
    }

    @Test
    fun `either Inflect graph downloading maps to Preparing`() = runTest {
        modelDownloadStates.value = modelDownloadStates.value.toMutableMap().apply {
            put(KernelModel.INFLECT_MICRO_DURATION, DownloadState.Downloading(progress = 0.4f))
            put(KernelModel.INFLECT_MICRO_DECODE, DownloadState.Error("stale partial file"))
        }
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(
            viewModel.uiState.value.inflectMicroAvailability
                is ModelAvailabilityState.Preparing,
        )
    }

    @Test
    fun `either Inflect graph error maps to retryable logical model`() = runTest {
        modelDownloadStates.value = modelDownloadStates.value.toMutableMap().apply {
            put(KernelModel.INFLECT_MICRO_DURATION, DownloadState.Error("network timeout"))
            put(KernelModel.INFLECT_MICRO_DECODE, DownloadState.Downloaded("/models/decode.onnx"))
        }
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            ModelAvailabilityState.ActionRequired(
                ActionReason.DownloadFailed("network timeout"),
            ),
            viewModel.uiState.value.inflectMicroAvailability,
        )
    }

    @Test
    fun `Inflect is ready once both graphs and the selected Cori frontend are downloaded`() = runTest {
        modelDownloadStates.value = modelDownloadStates.value.toMutableMap().apply {
            put(KernelModel.INFLECT_MICRO_DURATION, DownloadState.Downloaded("/models/duration.onnx"))
            put(KernelModel.INFLECT_MICRO_DECODE, DownloadState.Downloaded("/models/decode.onnx"))
        }
        sherpaDownloadStates.value = sherpaDownloadStates.value.toMutableMap().apply {
            put(SherpaPiperVoice.JennyDioco, VoicePackDownloadState.Downloaded("/voices/jenny"))
        }
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isInflectMicroReady)
    }

    @Test
    fun `partial Inflect graph availability never maps to ready`() = runTest {
        modelDownloadStates.value = modelDownloadStates.value.toMutableMap().apply {
            put(
                KernelModel.INFLECT_MICRO_DURATION,
                DownloadState.Downloaded("/models/duration.onnx"),
            )
            put(KernelModel.INFLECT_MICRO_DECODE, DownloadState.NotDownloaded)
        }
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            ModelAvailabilityState.NotDisplayed,
            viewModel.uiState.value.inflectMicroAvailability,
        )
        assertFalse(viewModel.uiState.value.isInflectMicroReady)
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
    fun `setVoiceOutputEngine accepts Inflect before prerequisites and starts no downloads`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.setVoiceOutputEngine(VoiceOutputEngine.InflectMicroExperimental)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            VoiceOutputEngine.InflectMicroExperimental,
            viewModel.uiState.value.selectedOutputEngine,
        )
        assertFalse(viewModel.uiState.value.isInflectMicroReady)
        coVerify { voiceOutputPreferences.setSelectedEngine(VoiceOutputEngine.InflectMicroExperimental) }
        io.mockk.verify(exactly = 0) {
            modelDownloadManager.startDownload(any())
            sherpaVoicePackDownloadManager.startDownload(any())
        }
    }

    @Test
    fun `selecting Inflect while its bundle downloads keeps Inflect selected`() = runTest {
        modelDownloadStates.value = modelDownloadStates.value.toMutableMap().apply {
            put(KernelModel.INFLECT_MICRO_DURATION, DownloadState.Downloading(progress = 0.4f))
            put(KernelModel.INFLECT_MICRO_DECODE, DownloadState.Error("stale partial file"))
        }
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.setVoiceOutputEngine(VoiceOutputEngine.InflectMicroExperimental)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            VoiceOutputEngine.InflectMicroExperimental,
            viewModel.uiState.value.selectedOutputEngine,
        )
        assertTrue(viewModel.uiState.value.inflectMicroAvailability is ModelAvailabilityState.Preparing)
    }

    @Test
    fun `selecting Inflect after a failed bundle keeps retryable state reachable`() = runTest {
        modelDownloadStates.value = modelDownloadStates.value.toMutableMap().apply {
            put(KernelModel.INFLECT_MICRO_DURATION, DownloadState.Error("network timeout"))
            put(KernelModel.INFLECT_MICRO_DECODE, DownloadState.Downloaded("/models/decode.onnx"))
        }
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.setVoiceOutputEngine(VoiceOutputEngine.InflectMicroExperimental)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            VoiceOutputEngine.InflectMicroExperimental,
            viewModel.uiState.value.selectedOutputEngine,
        )
        assertEquals(
            ModelAvailabilityState.ActionRequired(ActionReason.DownloadFailed("network timeout")),
            viewModel.uiState.value.inflectMicroAvailability,
        )
    }

    @Test
    fun `Inflect logical download retries missing frontend when graphs are ready`() = runTest {
        modelDownloadStates.value = modelDownloadStates.value.toMutableMap().apply {
            InflectMicroModelSpec.requiredModels.forEach { required ->
                put(
                    KernelModel.entries.first { it.fileName == required.fileName },
                    DownloadState.Downloaded("/models/${required.fileName}"),
                )
            }
        }
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            ModelAvailabilityState.NotDisplayed,
            viewModel.uiState.value.inflectMicroAvailability,
        )
        assertFalse(viewModel.uiState.value.isInflectMicroReady)
        viewModel.setVoiceOutputEngine(VoiceOutputEngine.InflectMicroExperimental)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            VoiceOutputEngine.InflectMicroExperimental,
            viewModel.uiState.value.selectedOutputEngine,
        )
        io.mockk.verify(exactly = 0) {
            modelDownloadManager.startDownload(any())
            sherpaVoicePackDownloadManager.startDownload(any())
        }

        viewModel.downloadInflectMicro()

        io.mockk.verify(exactly = 0) {
            modelDownloadManager.startDownload(any())
        }
        io.mockk.verify(exactly = 1) {
            sherpaVoicePackDownloadManager.startDownload(SherpaPiperVoice.JennyDioco)
        }
    }

    @Test
    fun `setVoiceOutputEngine accepts Inflect when all required assets are ready`() = runTest {
        modelDownloadStates.value = modelDownloadStates.value.toMutableMap().apply {
            InflectMicroModelSpec.requiredModels.forEach { required ->
                put(
                    KernelModel.entries.first { it.fileName == required.fileName },
                    DownloadState.Downloaded("/models/${required.fileName}"),
                )
            }
        }
        sherpaDownloadStates.value = sherpaDownloadStates.value.toMutableMap().apply {
            put(SherpaPiperVoice.JennyDioco, VoicePackDownloadState.Downloaded("/voices/jenny"))
        }
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isInflectMicroReady)
        assertEquals(ModelAvailabilityState.Ready, viewModel.uiState.value.inflectMicroAvailability)
        viewModel.setVoiceOutputEngine(VoiceOutputEngine.InflectMicroExperimental)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            VoiceOutputEngine.InflectMicroExperimental,
            viewModel.uiState.value.selectedOutputEngine,
        )
        coVerify {
            voiceOutputPreferences.setSelectedEngine(VoiceOutputEngine.InflectMicroExperimental)
        }
    }

    @Test
    fun `changing selected Sherpa voice recomputes Inflect readiness`() = runTest {
        modelDownloadStates.value = modelDownloadStates.value.toMutableMap().apply {
            InflectMicroModelSpec.requiredModels.forEach { required ->
                put(
                    KernelModel.entries.first { it.fileName == required.fileName },
                    DownloadState.Downloaded("/models/${required.fileName}"),
                )
            }
        }
        sherpaDownloadStates.value = mapOf(
            SherpaPiperVoice.SemaineMedium to VoicePackDownloadState.Downloaded("/voices/semaine"),
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(SherpaPiperVoice.JennyDioco, viewModel.uiState.value.selectedSherpaVoice)
        assertEquals(false, viewModel.uiState.value.isInflectMicroReady)

        selectedSherpaVoice.value = SherpaPiperVoice.SemaineMedium
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isSelectedSherpaVoiceDownloaded)
        assertTrue(viewModel.uiState.value.isInflectMicroReady)
    }

    @Test
    fun `keeps Inflect selected when a required graph becomes unavailable`() = runTest {
        modelDownloadStates.value = modelDownloadStates.value.toMutableMap().apply {
            InflectMicroModelSpec.requiredModels.forEach { required ->
                put(
                    KernelModel.entries.first { it.fileName == required.fileName },
                    DownloadState.Downloaded("/models/${required.fileName}"),
                )
            }
        }
        sherpaDownloadStates.value = sherpaDownloadStates.value.toMutableMap().apply {
            put(SherpaPiperVoice.JennyDioco, VoicePackDownloadState.Downloaded("/voices/jenny"))
        }
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setVoiceOutputEngine(VoiceOutputEngine.InflectMicroExperimental)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(VoiceOutputEngine.InflectMicroExperimental, viewModel.uiState.value.selectedOutputEngine)

        val durationModel = KernelModel.entries.first {
            it.fileName == InflectMicroModelSpec.requiredModels.first().fileName
        }
        modelDownloadStates.value = modelDownloadStates.value.toMutableMap().apply {
            put(durationModel, DownloadState.NotDownloaded)
        }
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(VoiceOutputEngine.InflectMicroExperimental, viewModel.uiState.value.selectedOutputEngine)
        assertFalse(viewModel.uiState.value.isInflectMicroReady)
        coVerify(exactly = 0) {
            voiceOutputPreferences.setSelectedEngine(VoiceOutputEngine.AndroidTts)
        }
    }

    @Test
    fun `keeps Inflect selected when the selected Sherpa voice becomes unavailable`() = runTest {
        modelDownloadStates.value = modelDownloadStates.value.toMutableMap().apply {
            InflectMicroModelSpec.requiredModels.forEach { required ->
                put(
                    KernelModel.entries.first { it.fileName == required.fileName },
                    DownloadState.Downloaded("/models/${required.fileName}"),
                )
            }
        }
        sherpaDownloadStates.value = sherpaDownloadStates.value.toMutableMap().apply {
            put(SherpaPiperVoice.JennyDioco, VoicePackDownloadState.Downloaded("/voices/jenny"))
        }
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setVoiceOutputEngine(VoiceOutputEngine.InflectMicroExperimental)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(VoiceOutputEngine.InflectMicroExperimental, viewModel.uiState.value.selectedOutputEngine)

        sherpaDownloadStates.value = sherpaDownloadStates.value.toMutableMap().apply {
            put(SherpaPiperVoice.JennyDioco, VoicePackDownloadState.NotDownloaded)
        }
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(VoiceOutputEngine.InflectMicroExperimental, viewModel.uiState.value.selectedOutputEngine)
        assertFalse(viewModel.uiState.value.isInflectMicroReady)
        coVerify(exactly = 0) {
            voiceOutputPreferences.setSelectedEngine(VoiceOutputEngine.AndroidTts)
        }
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
    fun `release builds ignore setSherpaVoice for hidden voices`() = runTest {
        val releaseContext: Context = mockk(relaxed = true)
        every { releaseContext.applicationInfo } returns ApplicationInfo().apply { flags = 0 }
        sherpaDownloadStates.value = mapOf(
            SherpaPiperVoice.SemaineMedium to VoicePackDownloadState.Downloaded("/voices/semaine"),
        )
        val releaseViewModel = VoiceViewModel(
            androidNativeRecognitionSupport,
            voiceInputPreferences,
            voiceOutputPreferences,
            sherpaVoicePackDownloadManager,
            wakeWordPreferences,
            wakeWordDetector,
            modelDownloadManager,
            hardwareProfileDetector,
            releaseContext,
            sherpaVoiceOutputController,
            piperVoiceSetup,
            asr4allSizeSwitcher,
            termsAcceptancePreferences,
        )
        testDispatcher.scheduler.advanceUntilIdle()

        releaseViewModel.setSherpaVoice(SherpaPiperVoice.SemaineMedium)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            SherpaPiperVoice.JennyDioco,
            releaseViewModel.uiState.value.selectedSherpaVoice,
        )
        coVerify(exactly = 0) {
            voiceOutputPreferences.setSelectedSherpaVoice(SherpaPiperVoice.SemaineMedium)
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
    fun `release builds ignore downloadSherpaVoice for hidden voices`() = runTest {
        val releaseContext: Context = mockk(relaxed = true)
        every { releaseContext.applicationInfo } returns ApplicationInfo().apply { flags = 0 }
        val releaseViewModel = VoiceViewModel(
            androidNativeRecognitionSupport,
            voiceInputPreferences,
            voiceOutputPreferences,
            sherpaVoicePackDownloadManager,
            wakeWordPreferences,
            wakeWordDetector,
            modelDownloadManager,
            hardwareProfileDetector,
            releaseContext,
            sherpaVoiceOutputController,
            piperVoiceSetup,
            asr4allSizeSwitcher,
            termsAcceptancePreferences,
        )
        testDispatcher.scheduler.advanceUntilIdle()

        releaseViewModel.downloadSherpaVoice(SherpaPiperVoice.SemaineMedium)

        io.mockk.verify(exactly = 0) {
            sherpaVoicePackDownloadManager.startDownload(SherpaPiperVoice.SemaineMedium)
        }
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

    @Test
    fun `downloadInflectMicro starts each missing graph and frontend exactly once`() = runTest {
        viewModel.downloadInflectMicro()

        io.mockk.verify(exactly = 1) {
            modelDownloadManager.startDownload(KernelModel.INFLECT_MICRO_DURATION)
            modelDownloadManager.startDownload(KernelModel.INFLECT_MICRO_DECODE)
            sherpaVoicePackDownloadManager.startDownload(SherpaPiperVoice.JennyDioco)
        }
    }

    @Test
    fun `downloadInflectMicro skips downloaded frontend and starts missing graphs`() = runTest {
        sherpaDownloadStates.value = sherpaDownloadStates.value.toMutableMap().apply {
            put(SherpaPiperVoice.JennyDioco, VoicePackDownloadState.Downloaded("/voices/jenny"))
        }
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.downloadInflectMicro()

        io.mockk.verify(exactly = 1) {
            modelDownloadManager.startDownload(KernelModel.INFLECT_MICRO_DURATION)
            modelDownloadManager.startDownload(KernelModel.INFLECT_MICRO_DECODE)
        }
        io.mockk.verify(exactly = 0) {
            sherpaVoicePackDownloadManager.startDownload(any())
        }
    }

    @Test
    fun `cancelInflectMicroDownload stops a running Cori pack shown on the Inflect row when Piper is not selected`() = runTest {
        io.mockk.every { sherpaVoicePackDownloadManager.cancelDownload(any()) } just Runs
        sherpaDownloadStates.value = sherpaDownloadStates.value.toMutableMap().apply {
            put(SherpaPiperVoice.JennyDioco, VoicePackDownloadState.Downloading(progress = 0.2f))
        }
        modelDownloadStates.value = modelDownloadStates.value.toMutableMap().apply {
            put(KernelModel.INFLECT_MICRO_DURATION, DownloadState.Downloading(progress = 0.4f))
        }
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.cancelInflectMicroDownload()

        io.mockk.verify(exactly = 1) {
            modelDownloadManager.cancelDownload(KernelModel.INFLECT_MICRO_DURATION)
            sherpaVoicePackDownloadManager.cancelDownload(SherpaPiperVoice.JennyDioco)
        }
    }

    @Test
    fun `cancelInflectMicroDownload still stops Cori after the view model is recreated`() = runTest {
        io.mockk.every { sherpaVoicePackDownloadManager.cancelDownload(any()) } just Runs
        sherpaDownloadStates.value = sherpaDownloadStates.value.toMutableMap().apply {
            put(SherpaPiperVoice.JennyDioco, VoicePackDownloadState.Downloading(progress = 0.2f))
        }
        modelDownloadStates.value = modelDownloadStates.value.toMutableMap().apply {
            put(KernelModel.INFLECT_MICRO_DURATION, DownloadState.Downloading(progress = 0.4f))
        }
        val recreated = newViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        recreated.cancelInflectMicroDownload()

        io.mockk.verify(exactly = 1) { sherpaVoicePackDownloadManager.cancelDownload(SherpaPiperVoice.JennyDioco) }
    }

    @Test
    fun `cancelInflectMicroDownload leaves the Cori pack running when Piper is the selected engine`() = runTest {
        io.mockk.every { sherpaVoicePackDownloadManager.cancelDownload(any()) } just Runs
        selectedOutputEngine.value = VoiceOutputEngine.SherpaExperimental
        sherpaDownloadStates.value = sherpaDownloadStates.value.toMutableMap().apply {
            put(SherpaPiperVoice.JennyDioco, VoicePackDownloadState.Downloading(progress = 0.2f))
        }
        modelDownloadStates.value = modelDownloadStates.value.toMutableMap().apply {
            put(KernelModel.INFLECT_MICRO_DURATION, DownloadState.Downloading(progress = 0.4f))
        }
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.cancelInflectMicroDownload()

        io.mockk.verify(exactly = 0) { sherpaVoicePackDownloadManager.cancelDownload(any()) }
    }

    @Test
    fun `setWakeWordThreshold persists selected value`() = runTest {
        viewModel.setWakeWordThreshold(0.75f)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(0.75f, viewModel.uiState.value.wakeWordThreshold, 0.0001f)
        coVerify(exactly = 1) { wakeWordPreferences.setConfidenceThreshold(0.75f) }
    }

    @Test
    fun `threshold persistence completes after view model cancellation`() = runTest {

        viewModel.setWakeWordThreshold(0.75f)
        viewModel.viewModelScope.cancel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(0.75f, wakeWordThreshold.value, 0.0001f)
    }

    @Test
    fun `persisted threshold is observed by a fresh voice consumer`() = runTest {
        viewModel.setWakeWordThreshold(0.75f)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(0.75f, wakeWordThreshold.value, 0.0001f)
        val recreatedViewModel = newViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(0.75f, recreatedViewModel.uiState.value.wakeWordThreshold, 0.0001f)
    }

    @Test
    fun `missing persisted threshold keeps 65 percent default`() = runTest {
        wakeWordThreshold.value = WAKE_WORD_DEFAULT_THRESHOLD
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(WAKE_WORD_DEFAULT_THRESHOLD, viewModel.uiState.value.wakeWordThreshold, 0.0001f)
    }

    @Test
    fun `initially unfocused slider field does not commit its default`() {
        assertFalse(shouldCommitTextOnFocusLost(wasFocused = false, isFocused = false))
        assertTrue(shouldCommitTextOnFocusLost(wasFocused = true, isFocused = false))
    }

    @Test
    fun `setHeyJandalEnabled true updates state and persists`() = runTest {
        viewModel.setHeyJandalEnabled(true)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(true, viewModel.uiState.value.heyJandalEnabled)
        coVerify { wakeWordPreferences.setHeyJandalEnabled(true) }
    }

    @Test
    fun `setHeyJandalEnabled false updates state and persists`() = runTest {
        viewModel.setHeyJandalEnabled(false)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(false, viewModel.uiState.value.heyJandalEnabled)
        coVerify { wakeWordPreferences.setHeyJandalEnabled(false) }
    }

    // ── Hey Jandal mic readiness enforcement ─────────────────────────────

    @Test
    fun `enforce with Granted leaves enabled Hey Jandal alone`() = runTest {
        viewModel.setHeyJandalEnabled(true)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(true, viewModel.uiState.value.heyJandalEnabled)

        viewModel.enforceHeyJandalMicReadiness(MicrophoneReadiness.Granted)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(true, viewModel.uiState.value.heyJandalEnabled)
    }

    @Test
    fun `enforce with NotGranted disables Hey Jandal`() = runTest {
        viewModel.setHeyJandalEnabled(true)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(true, viewModel.uiState.value.heyJandalEnabled)

        viewModel.enforceHeyJandalMicReadiness(MicrophoneReadiness.NotGranted)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(false, viewModel.uiState.value.heyJandalEnabled)
    }

    @Test
    fun `enforce with Unknown disables Hey Jandal`() = runTest {
        viewModel.setHeyJandalEnabled(true)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.enforceHeyJandalMicReadiness(MicrophoneReadiness.Unknown)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(false, viewModel.uiState.value.heyJandalEnabled)
    }

    @Test
    fun `enforce does nothing when Hey Jandal is already disabled`() = runTest {
        viewModel.enforceHeyJandalMicReadiness(MicrophoneReadiness.NotGranted)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(false, viewModel.uiState.value.heyJandalEnabled)
    }
    @Test
    fun `refreshAssistantStatus updates isDefaultAssistant to true when role granted`() {
        viewModel.refreshAssistantStatus(isRoleHeld = true)

        assertTrue(viewModel.uiState.value.isDefaultAssistant)
    }

    @Test
    fun `refreshAssistantStatus updates isDefaultAssistant to false when role denied`() {
        viewModel.refreshAssistantStatus(isRoleHeld = false)

        assertTrue(!viewModel.uiState.value.isDefaultAssistant)
    }

    @Test
    fun `refreshAssistantStatus is independent of microphone permission state`() {
        // Set mic permission to denied
        viewModel.enforceHeyJandalMicReadiness(MicrophoneReadiness.NotGranted)
        testDispatcher.scheduler.advanceUntilIdle()

        // Grant assistant role — should not be affected by mic permission state
        viewModel.refreshAssistantStatus(isRoleHeld = true)

        assertTrue(viewModel.uiState.value.isDefaultAssistant)
    }

    @Test
    fun `setAsr4allSize hands the choice to the switcher`() = runTest {
        viewModel.setAsr4allSize(Asr4AllSize.L)
        verify(exactly = 1) { asr4allSizeSwitcher.select(Asr4AllSize.L) }
    }

    @Test
    fun `the asr4all card shows the size being downloaded, not the installed one`() = runTest {
        modelDownloadStates.value = modelDownloadStates.value +
            Asr4AllInstall.models(Asr4AllSize.M).associateWith { DownloadState.Downloaded("/models/${it.fileName}") }
        viewModel.uiState.first { it.sherpaSttStates[VoiceInputEngine.Asr4all]?.isDownloaded == true }

        asr4allSwitchState.value = Asr4allSwitchState(pendingSize = Asr4AllSize.L)
        viewModel.uiState.first { it.asr4allPendingSize == Asr4AllSize.L }
        assertTrue(
            viewModel.uiState.value.sherpaSttStates[VoiceInputEngine.Asr4all]?.isDownloaded == false,
            "the card shows the size being downloaded, not the installed one",
        )
    }

    @Test
    fun `a failed size switch shows its message while the installed size stays ready`() = runTest {
        modelDownloadStates.value = modelDownloadStates.value +
            Asr4AllInstall.models(Asr4AllSize.M).associateWith { DownloadState.Downloaded("/models/${it.fileName}") }
        viewModel.uiState.first { it.sherpaSttStates[VoiceInputEngine.Asr4all]?.isDownloaded == true }

        asr4allSwitchState.value = Asr4allSwitchState(
            failure = Asr4allSwitchFailure(Asr4AllSize.L, "Could not switch to L."),
        )
        viewModel.uiState.first { it.asr4allSwitchFailure != null }

        assertEquals("Could not switch to L.", viewModel.uiState.value.asr4allSwitchFailure)
        assertTrue(viewModel.uiState.value.sherpaSttStates[VoiceInputEngine.Asr4all]?.isDownloaded == true)
    }

    @Test
    fun `cancelling an asr4all download cancels the switcher`() = runTest {
        viewModel.cancelSherpaSttDownload(VoiceInputEngine.Asr4all)
        verify(exactly = 1) { asr4allSizeSwitcher.cancel() }
    }

    @Test
    fun `deleting asr4all converges the selection to Auto once the switcher deletes`() = runTest {
        coEvery { asr4allSizeSwitcher.deleteActive() } returns true
        viewModel.setVoiceInputEngine(VoiceInputEngine.Asr4all)
        viewModel.deleteSherpaStt(VoiceInputEngine.Asr4all)
        viewModel.uiState.first { it.selectedInputEngine == VoiceInputEngine.Auto }
        testDispatcher.scheduler.advanceUntilIdle() // the Auto preference write is launched on Main after the state flip
        coVerify(exactly = 1) { voiceInputPreferences.setSelectedEngine(VoiceInputEngine.Auto) }
    }

    @Test
    fun `a stale Terms refusal is hidden once Terms are accepted, even if it arrives first`() = runTest {
        asr4allSwitchState.value = Asr4allSwitchState(
            failure = Asr4allSwitchFailure(Asr4AllSize.L, TERMS_NOT_ACCEPTED_MESSAGE),
        )
        viewModel.uiState.first { it.asr4allSwitchFailure == TERMS_NOT_ACCEPTED_MESSAGE }

        termsRecord.value = TermsAcceptanceRecord(acceptedVersion = TERMS_VERSION)
        viewModel.uiState.first { it.termsAccepted == true && it.asr4allSwitchFailure == null }
    }

    @Test
    fun `a Terms refusal stays visible while Terms are not accepted`() = runTest {
        asr4allSwitchState.value = Asr4allSwitchState(
            failure = Asr4allSwitchFailure(Asr4AllSize.L, TERMS_NOT_ACCEPTED_MESSAGE),
        )
        viewModel.uiState.first { it.asr4allSwitchFailure == TERMS_NOT_ACCEPTED_MESSAGE }
        assertEquals(false, viewModel.uiState.value.termsAccepted)
    }
}
