package com.kernel.ai.feature.settings

import android.content.Context
import com.kernel.ai.core.inference.download.DownloadState
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.download.ModelDownloadManager
import com.kernel.ai.core.voice.WakeWordDetector
import com.kernel.ai.core.voice.WakeWordPreferences
import com.kernel.ai.core.voice.AndroidNativeRecognitionAvailability
import com.kernel.ai.core.voice.AndroidNativeRecognitionLocaleStatus
import com.kernel.ai.core.voice.AndroidNativeRecognitionSupport
import com.kernel.ai.core.voice.InflectMicroModelSpec
import com.kernel.ai.core.voice.SherpaKokoroVoice
import com.kernel.ai.core.voice.SherpaPiperVoice
import com.kernel.ai.core.voice.SherpaVoicePackDownloadManager
import com.kernel.ai.core.voice.VoiceInputEngine
import com.kernel.ai.core.voice.VoiceInputPreferences
import com.kernel.ai.core.voice.VoiceOutputEngine
import com.kernel.ai.core.voice.VoiceOutputPreferences
import com.kernel.ai.core.voice.VoicePackDownloadState
import com.kernel.ai.core.permissions.MicrophoneReadiness
import com.kernel.ai.core.model.availability.ActionReason
import com.kernel.ai.core.model.availability.ModelAvailabilityState
import com.kernel.ai.core.model.availability.UnavailableReason
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
    private val context: Context = mockk(relaxed = true)
    private val heyJandalEnabled = MutableStateFlow(false)
    private val wakeWordThreshold = MutableStateFlow(0.80f)
    private val selectedInputEngine = MutableStateFlow(VoiceInputEngine.Vosk)
    private val autoStartAlertVoiceCommandsEnabled = MutableStateFlow(true)
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
        every { sherpaVoicePackDownloadManager.downloadStates } returns sherpaDownloadStates
        every { wakeWordDetector.isAvailable } returns false
        every { sherpaVoicePackDownloadManager.kokoroDownloadStates } returns kokoroDownloadStates
        every { modelDownloadManager.downloadStates } returns modelDownloadStates
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
        viewModel = VoiceViewModel(
            androidNativeRecognitionSupport,
            voiceInputPreferences,
            voiceOutputPreferences,
            sherpaVoicePackDownloadManager,
            wakeWordPreferences,
            wakeWordDetector,
            modelDownloadManager,
            context,
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
            wakeWordPreferences,
            wakeWordDetector,
            modelDownloadManager,
            context,
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
            wakeWordPreferences,
            wakeWordDetector,
            modelDownloadManager,
            context,
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
            wakeWordPreferences,
            wakeWordDetector,
            modelDownloadManager,
            context,
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            "Android native speech recognition could not verify on-device support for English (New Zealand) on this device. It may fail unless that language is supported and installed locally.",
            viewModel.uiState.value.androidNativeAvailabilityMessage,
        )
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
            "On-device Android speech recognition is unavailable for the current setup. Install the required language pack or keep using Vosk for guaranteed local voice input.",
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
            releaseContext,
        )

        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(
            releaseViewModel.uiState.value.sherpaVoices.none { row ->
                row.voice == SherpaPiperVoice.SemaineMedium
            }
        )
    }
    @Test
    fun `debug builds expose Inflect while release builds hide it`() = runTest {
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
            releaseContext,
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(
            releaseViewModel.uiState.value.availableOutputEngines.contains(
                VoiceOutputEngine.InflectMicroExperimental,
            ),
        )
    }

    @Test
    fun `both Inflect graphs missing map to one downloadable logical model`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            ModelAvailabilityState.Unavailable(UnavailableReason.NotBundled),
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
    fun `both Inflect graphs downloaded map to available but readiness still requires Sherpa`() =
        runTest {
            modelDownloadStates.value = modelDownloadStates.value.toMutableMap().apply {
                put(
                    KernelModel.INFLECT_MICRO_DURATION,
                    DownloadState.Downloaded("/models/duration.onnx"),
                )
                put(
                    KernelModel.INFLECT_MICRO_DECODE,
                    DownloadState.Downloaded("/models/decode.onnx"),
                )
            }
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(
                ModelAvailabilityState.Ready,
                viewModel.uiState.value.inflectMicroAvailability,
            )
            assertFalse(viewModel.uiState.value.isInflectMicroReady)
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
            ModelAvailabilityState.Unavailable(UnavailableReason.NotBundled),
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
    fun `setVoiceOutputEngine ignores Inflect until graphs and selected voice are downloaded`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.setVoiceOutputEngine(VoiceOutputEngine.InflectMicroExperimental)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            VoiceOutputEngine.AndroidTts,
            viewModel.uiState.value.selectedOutputEngine,
        )
        coVerify(exactly = 0) {
            voiceOutputPreferences.setSelectedEngine(VoiceOutputEngine.InflectMicroExperimental)
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
    fun `persistently demotes Inflect when a required graph becomes unavailable`() = runTest {
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

        assertEquals(VoiceOutputEngine.AndroidTts, viewModel.uiState.value.selectedOutputEngine)
        coVerify(exactly = 1) {
            voiceOutputPreferences.setSelectedEngine(VoiceOutputEngine.AndroidTts)
        }
    }

    @Test
    fun `persistently demotes Inflect when the selected Sherpa voice becomes unavailable`() = runTest {
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

        assertEquals(VoiceOutputEngine.AndroidTts, viewModel.uiState.value.selectedOutputEngine)
        coVerify(exactly = 1) {
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
            releaseContext,
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
            releaseContext,
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
    fun `downloadInflectMicro delegates both graph downloads`() = runTest {
        viewModel.downloadInflectMicro()

        io.mockk.verify(exactly = 1) {
            modelDownloadManager.startDownload(KernelModel.INFLECT_MICRO_DURATION)
        }
        io.mockk.verify(exactly = 1) {
            modelDownloadManager.startDownload(KernelModel.INFLECT_MICRO_DECODE)
        }
    }

    @Test
    fun `cancelInflectMicroDownload cancels only active graph downloads`() = runTest {
        modelDownloadStates.value = modelDownloadStates.value.toMutableMap().apply {
            put(KernelModel.INFLECT_MICRO_DURATION, DownloadState.Downloading(progress = 0.4f))
            put(KernelModel.INFLECT_MICRO_DECODE, DownloadState.Downloaded("/models/decode.onnx"))
        }

        viewModel.cancelInflectMicroDownload()

        io.mockk.verify(exactly = 1) {
            modelDownloadManager.cancelDownload(KernelModel.INFLECT_MICRO_DURATION)
        }
        io.mockk.verify(exactly = 0) {
            modelDownloadManager.cancelDownload(KernelModel.INFLECT_MICRO_DECODE)
        }
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
}
