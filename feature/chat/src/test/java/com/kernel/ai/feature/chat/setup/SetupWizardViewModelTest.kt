package com.kernel.ai.feature.chat.setup

import androidx.lifecycle.SavedStateHandle
import com.kernel.ai.core.inference.download.DownloadNetworkChoice
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.download.ModelDownloadManager
import com.kernel.ai.core.inference.hardware.HardwareTier
import com.kernel.ai.core.inference.prefs.DownloadNetworkPreferences
import com.kernel.ai.core.inference.setup.SetupStep
import com.kernel.ai.core.inference.setup.SetupWizardPreferences
import com.kernel.ai.core.inference.setup.SetupWizardProgress
import com.kernel.ai.core.voice.PiperVoiceSetup
import com.kernel.ai.core.voice.SherpaPiperVoice
import com.kernel.ai.core.voice.SherpaVoicePackDownloadManager
import com.kernel.ai.core.voice.VoiceDefaultsProvisioner
import com.kernel.ai.core.voice.VoiceInputEngine
import com.kernel.ai.core.voice.VoiceInputPreferences
import com.kernel.ai.core.voice.VoiceOutputEngine
import com.kernel.ai.core.voice.VoiceOutputPreferences
import com.kernel.ai.core.voice.VoicePackDownloadState
import com.kernel.ai.core.voice.WakeWordPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SetupWizardViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val downloadManager: ModelDownloadManager = mockk(relaxed = true)
    private val downloadNetworkPreferences: DownloadNetworkPreferences = mockk(relaxed = true)
    private val setupWizardPreferences: SetupWizardPreferences = mockk(relaxed = true)
    private val voicePackDownloadManager: SherpaVoicePackDownloadManager = mockk(relaxed = true)
    private val voiceOutputPreferences: VoiceOutputPreferences = mockk(relaxed = true)
    private val voiceInputPreferences: VoiceInputPreferences = mockk(relaxed = true)
    private val setupVoiceDevice: SetupVoiceDevice = mockk(relaxed = true)
    private val voiceDefaultsProvisioner: VoiceDefaultsProvisioner = mockk(relaxed = true)
    private val wakeWordPreferences: WakeWordPreferences = mockk(relaxed = true)
    private val platformReader: MakeYoursPlatformReader = mockk(relaxed = true)

    @BeforeEach
    fun setUp() {
        every { voiceOutputPreferences.selectedSherpaVoice } returns flowOf(SherpaPiperVoice.CoriHigh)
        every { voicePackDownloadManager.downloadStates } returns kotlinx.coroutines.flow.MutableStateFlow(emptyMap())
        Dispatchers.setMain(dispatcher)
        every { setupWizardPreferences.progress } returns flowOf(SetupWizardProgress(decided = true, completed = false))
        every { voicePackDownloadManager.downloadStates } returns MutableStateFlow(emptyMap())
        every { downloadNetworkPreferences.choice } returns flowOf(null)
        coEvery { downloadManager.plannedFirstRunModels() } returns emptyList()
        // Fresh install: nothing stored for speech input or output.
        coEvery { voiceInputPreferences.hasStoredEngine() } returns false
        coEvery { voiceOutputPreferences.hasStoredEngine() } returns false
        every { wakeWordPreferences.heyJandalEnabled } returns flowOf(false)
        every { voiceInputPreferences.selectedEngine } returns flowOf(VoiceInputEngine.Auto)
        coEvery { platformReader.read() } returns MakeYoursPlatform(
            hasMicrophone = true,
            micGranted = true,
            wakeModelAvailable = true,
            assistantRoleAvailable = true,
            assistantRoleHeld = true,
        )
        device(nativeReady = true, hasTts = true, tier = HardwareTier.FLAGSHIP)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun device(nativeReady: Boolean, hasTts: Boolean, tier: HardwareTier) {
        coEvery { setupVoiceDevice.androidNativeInputReady() } returns nativeReady
        every { setupVoiceDevice.hasSystemTtsEngine() } returns hasTts
        every { setupVoiceDevice.hardwareTier() } returns tier
    }

    /** Stores a speech input choice as an earlier run would have. */
    private fun storedInput(engine: VoiceInputEngine) {
        coEvery { voiceInputPreferences.hasStoredEngine() } returns true
        every { voiceInputPreferences.selectedEngine } returns flowOf(engine)
    }

    /** Stores a speech output choice as an earlier run would have. */
    private fun storedOutput(engine: VoiceOutputEngine) {
        coEvery { voiceOutputPreferences.hasStoredEngine() } returns true
        every { voiceOutputPreferences.selectedEngine } returns flowOf(engine)
    }

    private fun viewModel(handle: SavedStateHandle = SavedStateHandle()): SetupWizardViewModel =
        SetupWizardViewModel(
            downloadManager = downloadManager,
            downloadNetworkPreferences = downloadNetworkPreferences,
            setupWizardPreferences = setupWizardPreferences,
            voicePackDownloadManager = voicePackDownloadManager,
            voiceOutputPreferences = voiceOutputPreferences,
            voiceInputPreferences = voiceInputPreferences,
            piperVoiceSetup = PiperVoiceSetup(voiceOutputPreferences, voicePackDownloadManager),
            setupVoiceDevice = setupVoiceDevice,
            voiceDefaultsProvisioner = voiceDefaultsProvisioner,
            wakeWordPreferences = wakeWordPreferences,
            platformReader = platformReader,
            savedStateHandle = handle,
        )

    /** Loads the wizard and moves it from the first step to the speech step. */
    private fun SetupWizardViewModel.atSpeechStep() {
        dispatcher.scheduler.advanceUntilIdle()
        onContinue {}
        dispatcher.scheduler.advanceUntilIdle()
    }

    /** Loads the wizard and moves it to the download step. */
    private fun SetupWizardViewModel.atDownloadStep() {
        atSpeechStep()
        onContinue {}
        dispatcher.scheduler.advanceUntilIdle()
    }

    // ── Skip ─────────────────────────────────────────────────────────────────────

    @Test
    fun `skipping with no saved choice saves Wi-Fi only so models still download`() {
        var done = false

        viewModel().onSkip { done = true }
        dispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { downloadNetworkPreferences.setChoice(DownloadNetworkChoice.WIFI_ONLY) }
        coVerify(exactly = 1) { setupWizardPreferences.markCompleted() }
        assertTrue(done)
    }

    @Test
    fun `skipping keeps an explicit earlier download choice`() {
        every { downloadNetworkPreferences.choice } returns flowOf(DownloadNetworkChoice.MOBILE_DATA_ALLOWED)

        viewModel().onSkip {}
        dispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { downloadNetworkPreferences.setChoice(any()) }
        coVerify(exactly = 1) { setupWizardPreferences.markCompleted() }
    }

    @Test
    fun `skipping a re-run writes no speech or network choice and starts no voice`() {
        every { downloadNetworkPreferences.choice } returns flowOf(DownloadNetworkChoice.WIFI_ONLY)
        storedInput(VoiceInputEngine.SherpaParaformer)
        storedOutput(VoiceOutputEngine.AndroidTts)
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        vm.onSkip {}
        dispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { voiceInputPreferences.setSelectedEngine(any()) }
        coVerify(exactly = 0) { voiceOutputPreferences.setSelectedEngine(any()) }
        coVerify(exactly = 0) { downloadNetworkPreferences.setChoice(any()) }
        verify(exactly = 0) { voicePackDownloadManager.startDownload(any(), any(), any(), any()) }
        coVerify(exactly = 1) { setupWizardPreferences.markCompleted() }
    }

    @Test
    fun `skipping after choosing Cori saves it and starts no download`() {
        val vm = viewModel()
        vm.atSpeechStep()
        vm.onOutputSelected(VoiceOutputEngine.SherpaExperimental)

        vm.onSkip {}
        dispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { voiceOutputPreferences.setSelectedEngine(VoiceOutputEngine.SherpaExperimental) }
        verify(exactly = 0) { voicePackDownloadManager.startDownload(any(), any(), any(), any()) }
    }

    @Test
    fun `skipping queues no speech input model, even for Automatic`() {
        val vm = viewModel()
        vm.atSpeechStep()
        vm.onInputSelected(VoiceInputEngine.Auto)

        vm.onSkip {}
        dispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { voiceDefaultsProvisioner.queueSpeechInput(any()) }
    }

    @Test
    fun `skipping saves the speech choices made so far`() {
        val vm = viewModel()
        vm.atSpeechStep()
        vm.onInputSelected(VoiceInputEngine.AndroidNative)
        vm.onOutputSelected(VoiceOutputEngine.AndroidTts)

        vm.onSkip {}
        dispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { voiceInputPreferences.setSelectedEngine(VoiceInputEngine.AndroidNative) }
        coVerify(exactly = 1) { voiceOutputPreferences.setSelectedEngine(VoiceOutputEngine.AndroidTts) }
    }

    // ── Process death and re-run ─────────────────────────────────────────────────

    @Test
    fun `a selection not yet saved survives process death through SavedStateHandle`() {
        val handle = SavedStateHandle()
        val before = viewModel(handle)
        dispatcher.scheduler.advanceUntilIdle()
        before.onChoiceSelected(DownloadNetworkChoice.MOBILE_DATA_ALLOWED)
        before.onInputSelected(VoiceInputEngine.AndroidNative)

        val restored = viewModel(handle)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(DownloadNetworkChoice.MOBILE_DATA_ALLOWED, restored.uiState.value.choice)
        assertFalse(restored.uiState.value.choiceRecorded)
        assertEquals(VoiceInputEngine.AndroidNative, restored.uiState.value.inputEngine)
    }

    @Test
    fun `an output selected but not saved is restored after process death`() {
        storedOutput(VoiceOutputEngine.SherpaExperimental)
        val handle = SavedStateHandle()
        val before = viewModel(handle)
        dispatcher.scheduler.advanceUntilIdle()
        before.onOutputSelected(VoiceOutputEngine.AndroidTts)

        val restored = viewModel(handle)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(VoiceOutputEngine.AndroidTts, restored.uiState.value.outputEngine)
        assertEquals(emptyList<SherpaPiperVoice>(), restored.uiState.value.plannedVoices)
    }

    @Test
    fun `a saved choice is shown as recorded and preselected`() {
        every { downloadNetworkPreferences.choice } returns flowOf(DownloadNetworkChoice.MOBILE_DATA_ALLOWED)

        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(DownloadNetworkChoice.MOBILE_DATA_ALLOWED, vm.uiState.value.choice)
        assertTrue(vm.uiState.value.choiceRecorded)
    }

    @Test
    fun `a voice pack already on disk is not in the planned voices`() {
        every { downloadNetworkPreferences.choice } returns flowOf(DownloadNetworkChoice.WIFI_ONLY)
        every { voicePackDownloadManager.downloadStates } returns MutableStateFlow(
            mapOf(SherpaPiperVoice.CoriHigh to VoicePackDownloadState.Downloaded("/cori")),
        )

        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(emptyList<SherpaPiperVoice>(), vm.uiState.value.plannedVoices)
    }

    // ── Stored choices are kept unless the user changes them (#1868 review B1) ───

    @Test
    fun `a stored Android voice on a flagship phone stays and Finish writes nothing`() {
        storedOutput(VoiceOutputEngine.AndroidTts)
        val vm = viewModel()
        vm.atDownloadStep()
        assertEquals(VoiceOutputEngine.AndroidTts, vm.uiState.value.outputEngine)

        vm.onContinue {}
        vm.onContinue {}
        dispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { voiceOutputPreferences.setSelectedEngine(any()) }
        verify(exactly = 0) { voicePackDownloadManager.startDownload(any(), any(), any(), any()) }
    }

    @Test
    fun `a stored Android voice on a mid-range phone stays, not replaced by Cori`() {
        storedOutput(VoiceOutputEngine.AndroidTts)
        device(nativeReady = true, hasTts = true, tier = HardwareTier.MID_RANGE)

        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(VoiceOutputEngine.AndroidTts, vm.uiState.value.outputEngine)
        assertEquals(emptyList<SherpaPiperVoice>(), vm.uiState.value.plannedVoices)
    }

    @Test
    fun `a low-power phone keeps Android TTS as its default output`() {
        storedOutput(VoiceOutputEngine.AndroidTts)
        device(nativeReady = true, hasTts = true, tier = HardwareTier.LOW_POWER)

        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(VoiceOutputEngine.AndroidTts, vm.uiState.value.outputEngine)
        assertEquals(emptyList<SherpaPiperVoice>(), vm.uiState.value.plannedVoices)
    }

    @Test
    fun `a stored Sherpa input that is not offered stays, shows nothing selected, and Finish keeps it`() {
        storedInput(VoiceInputEngine.SherpaParaformer)
        val vm = viewModel()
        vm.atDownloadStep()

        assertNull(vm.uiState.value.inputEngine)

        vm.onContinue {}
        dispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { voiceInputPreferences.setSelectedEngine(any()) }
    }

    @Test
    fun `a stored output that is no longer offered stays unwritten and highlights nothing`() {
        storedOutput(VoiceOutputEngine.AndroidTts)
        device(nativeReady = true, hasTts = false, tier = HardwareTier.FLAGSHIP)

        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.uiState.value.outputEngine)
        vm.onContinue {}
        dispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 0) { voiceOutputPreferences.setSelectedEngine(any()) }
    }

    @Test
    fun `choosing Cori on a re-run saves it and starts the download after consent`() {
        storedOutput(VoiceOutputEngine.AndroidTts)
        every { downloadNetworkPreferences.choice } returns flowOf(DownloadNetworkChoice.WIFI_ONLY)
        val vm = viewModel()
        vm.atDownloadStep()
        vm.onOutputSelected(VoiceOutputEngine.SherpaExperimental)

        vm.onContinue {}
        vm.onContinue {}
        dispatcher.scheduler.advanceUntilIdle()

        coVerifyOrder {
            voiceOutputPreferences.setSelectedEngine(VoiceOutputEngine.SherpaExperimental)
            voicePackDownloadManager.startDownload(SherpaPiperVoice.CoriHigh)
        }
    }

    // ── Speech options by device capability (#1855 part 2) ──────────────────────

    @Test
    fun `a phone where Android's listener is not ready offers Automatic and Cori only`() {
        device(nativeReady = false, hasTts = false, tier = HardwareTier.FLAGSHIP)

        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(VoiceInputEngine.Auto), vm.uiState.value.inputOptions)
        assertEquals(listOf(VoiceOutputEngine.SherpaExperimental), vm.uiState.value.outputOptions)
        assertTrue(vm.uiState.value.inputReason != null)
    }

    @Test
    fun `a phone where Android's listener is ready offers both inputs and no reason`() {
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(VoiceInputEngine.Auto, VoiceInputEngine.AndroidNative), vm.uiState.value.inputOptions)
        assertEquals(null, vm.uiState.value.inputReason)
        assertEquals(listOf(VoiceOutputEngine.SherpaExperimental, VoiceOutputEngine.AndroidTts), vm.uiState.value.outputOptions)
    }

    // ── Defaults ─────────────────────────────────────────────────────────────────

    @Test
    fun `defaults are Automatic input and the tier output on a fresh install`() {
        device(nativeReady = true, hasTts = true, tier = HardwareTier.MID_RANGE)

        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(VoiceInputEngine.Auto, vm.uiState.value.inputEngine)
        assertEquals(VoiceOutputEngine.SherpaExperimental, vm.uiState.value.outputEngine)
    }

    @Test
    fun `a fresh install writes the defaults on Finish`() {
        val vm = viewModel()
        vm.atDownloadStep()

        vm.onContinue {}
        vm.onContinue {}
        dispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { voiceInputPreferences.setSelectedEngine(VoiceInputEngine.Auto) }
        coVerify(exactly = 1) { voiceOutputPreferences.setSelectedEngine(VoiceOutputEngine.SherpaExperimental) }
    }

    @Test
    fun `a stored choice Automatic-only device no longer offers stays off the list`() {
        storedInput(VoiceInputEngine.AndroidNative)
        device(nativeReady = false, hasTts = true, tier = HardwareTier.FLAGSHIP)

        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(VoiceInputEngine.Auto), vm.uiState.value.inputOptions)
        assertNull(vm.uiState.value.inputEngine)
    }

    @Test
    fun `choosing Android TTS removes Cori from the planned voices`() {
        val vm = viewModel()
        vm.atDownloadStep()
        assertEquals(listOf(SherpaPiperVoice.CoriHigh), vm.uiState.value.plannedVoices)

        vm.onOutputSelected(VoiceOutputEngine.AndroidTts)

        assertEquals(emptyList<SherpaPiperVoice>(), vm.uiState.value.plannedVoices)
    }

    // ── Zipformer files follow the speech input (#1855 part 2 review S1) ─────────

    @Test
    fun `Zipformer files are planned for Automatic and dropped for Android's listener`() {
        val zipformer = VoiceDefaultsProvisioner.zipformerModels()
        val other = KernelModel.entries.first { it !in zipformer }
        coEvery { downloadManager.plannedFirstRunModels() } returns zipformer + other
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(zipformer + other, vm.uiState.value.plannedModels)

        vm.onInputSelected(VoiceInputEngine.AndroidNative)

        assertEquals(listOf(other), vm.uiState.value.plannedModels)
    }

    // ── Storage and consent ──────────────────────────────────────────────────────

    @Test
    fun `Back from the speech step stores no speech or network choice`() {
        val vm = viewModel()
        vm.atSpeechStep()
        vm.onInputSelected(VoiceInputEngine.AndroidNative)
        vm.onOutputSelected(VoiceOutputEngine.AndroidTts)

        vm.onBack {}
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(SetupStep.HOW_IT_WORKS, vm.uiState.value.step)
        coVerify(exactly = 0) { voiceInputPreferences.setSelectedEngine(any()) }
        coVerify(exactly = 0) { voiceOutputPreferences.setSelectedEngine(any()) }
        coVerify(exactly = 0) { downloadNetworkPreferences.setChoice(any()) }
    }

    @Test
    fun `nothing downloads before the download step is finished`() {
        val vm = viewModel()
        vm.atDownloadStep()
        vm.onChoiceSelected(DownloadNetworkChoice.MOBILE_DATA_ALLOWED)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(SetupStep.DOWNLOAD, vm.uiState.value.step)
        coVerify(exactly = 0) { downloadNetworkPreferences.setChoice(any()) }
        verify(exactly = 0) { voicePackDownloadManager.startDownload(any(), any(), any(), any()) }
    }

    @Test
    fun `Finish saves speech choices before the network choice, then starts Cori`() {
        val vm = viewModel()
        vm.atDownloadStep()
        vm.onChoiceSelected(DownloadNetworkChoice.MOBILE_DATA_ALLOWED)

        vm.onContinue {}
        vm.onContinue {}
        dispatcher.scheduler.advanceUntilIdle()

        coVerifyOrder {
            voiceInputPreferences.setSelectedEngine(VoiceInputEngine.Auto)
            voiceOutputPreferences.setSelectedEngine(VoiceOutputEngine.SherpaExperimental)
            downloadNetworkPreferences.setChoice(DownloadNetworkChoice.MOBILE_DATA_ALLOWED)
            voicePackDownloadManager.startDownload(SherpaPiperVoice.CoriHigh)
        }
        coVerify(exactly = 1) { setupWizardPreferences.markCompleted() }
    }

    @Test
    fun `Finish with Automatic queues Zipformer after the network choice is saved`() {
        val vm = viewModel()
        vm.atDownloadStep()
        vm.onChoiceSelected(DownloadNetworkChoice.MOBILE_DATA_ALLOWED)

        vm.onContinue {}
        vm.onContinue {}
        dispatcher.scheduler.advanceUntilIdle()

        coVerifyOrder {
            downloadNetworkPreferences.setChoice(DownloadNetworkChoice.MOBILE_DATA_ALLOWED)
            voiceDefaultsProvisioner.queueSpeechInput(VoiceInputEngine.Auto)
        }
    }

    @Test
    fun `Finish with a stored Automatic queues Zipformer again so deleted files come back`() {
        storedInput(VoiceInputEngine.Auto)
        val vm = viewModel()
        vm.atDownloadStep()

        vm.onContinue {}
        vm.onContinue {}
        dispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { voiceDefaultsProvisioner.queueSpeechInput(VoiceInputEngine.Auto) }
    }

    @Test
    fun `Finish with Sherpa Zipformer queues the Zipformer files`() {
        val vm = viewModel()
        vm.atDownloadStep()
        vm.onInputSelected(VoiceInputEngine.SherpaZipformer)

        vm.onContinue {}
        vm.onContinue {}
        dispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { voiceDefaultsProvisioner.queueSpeechInput(VoiceInputEngine.SherpaZipformer) }
    }

    @Test
    fun `Finish with Android TTS saves it and starts no voice download`() {
        val vm = viewModel()
        vm.atDownloadStep()
        vm.onOutputSelected(VoiceOutputEngine.AndroidTts)

        vm.onContinue {}
        vm.onContinue {}
        dispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { voiceOutputPreferences.setSelectedEngine(VoiceOutputEngine.AndroidTts) }
        verify(exactly = 0) { voicePackDownloadManager.startDownload(any(), any(), any(), any()) }
    }

    // ── Make Jandal yours (#1855 part 3) ────────────────────────────────────

    /** Loads the wizard and moves it to the Make Jandal yours step. */
    private fun SetupWizardViewModel.atMakeYoursStep() {
        atDownloadStep()
        onContinue {}
        dispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `skip on the make Jandal yours step writes no Hey Jandal choice`() {
        val vm = viewModel()
        vm.atMakeYoursStep()
        vm.onWakeWordChoiceChanged(true)
        vm.onSkip {}
        dispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 0) { wakeWordPreferences.setHeyJandalEnabled(any()) }
    }

    @Test
    fun `finish applies a changed Hey Jandal choice when it can run`() {
        val vm = viewModel()
        vm.atMakeYoursStep()
        vm.onWakeWordChoiceChanged(true)
        vm.onContinue {}
        dispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 1) { wakeWordPreferences.setHeyJandalEnabled(true) }
    }

    @Test
    fun `finish turns Hey Jandal on without the default assistant, which does not block it`() {
        coEvery { platformReader.read() } returns MakeYoursPlatform(
            hasMicrophone = true,
            micGranted = true,
            wakeModelAvailable = true,
            assistantRoleAvailable = true,
            assistantRoleHeld = false,
        )
        val vm = viewModel()
        vm.atMakeYoursStep()
        vm.onWakeWordChoiceChanged(true)
        vm.onContinue {}
        dispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 1) { wakeWordPreferences.setHeyJandalEnabled(true) }
    }

    @Test
    fun `finish does not write a blocked Hey Jandal On and keeps the stored value`() {
        coEvery { platformReader.read() } returns MakeYoursPlatform(
            hasMicrophone = true,
            micGranted = false,
            wakeModelAvailable = true,
        )
        val vm = viewModel()
        vm.atMakeYoursStep()
        vm.onWakeWordChoiceChanged(true)
        vm.onContinue {}
        dispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 0) { wakeWordPreferences.setHeyJandalEnabled(any()) }
    }

    @Test
    fun `finish with Hey Jandal on queues its wake model after the network choice is saved`() {
        val vm = viewModel()
        vm.atMakeYoursStep()
        vm.onWakeWordChoiceChanged(true)
        vm.onContinue {}
        dispatcher.scheduler.advanceUntilIdle()
        coVerifyOrder {
            downloadNetworkPreferences.setChoice(any())
            downloadManager.startDownload(any())
        }
        coVerify(exactly = 1) { wakeWordPreferences.setHeyJandalEnabled(true) }
    }

    @Test
    fun `finish keeps a stored Hey Jandal On when a new On is blocked`() {
        every { wakeWordPreferences.heyJandalEnabled } returns flowOf(true)
        coEvery { platformReader.read() } returns MakeYoursPlatform(
            hasMicrophone = true,
            micGranted = false,
            wakeModelAvailable = true,
        )
        val vm = viewModel()
        vm.atMakeYoursStep()
        vm.onWakeWordChoiceChanged(true)
        vm.onContinue {}
        dispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 0) { wakeWordPreferences.setHeyJandalEnabled(any()) }
    }

    @Test
    fun `re-run shows stored Hey Jandal and finishing without a change writes nothing`() {
        every { setupWizardPreferences.progress } returns flowOf(
            SetupWizardProgress(decided = true, completed = true),
        )
        every { wakeWordPreferences.heyJandalEnabled } returns flowOf(true)
        val vm = viewModel()
        vm.atMakeYoursStep()
        assertTrue(vm.uiState.value.wakeWordStored)
        assertTrue(vm.uiState.value.wakeWordChoice)
        vm.onContinue {}
        dispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 0) { wakeWordPreferences.setHeyJandalEnabled(any()) }
    }

    @Test
    fun `refreshing the phone state reads it and writes nothing`() {
        val vm = viewModel()
        vm.atMakeYoursStep()
        coEvery { platformReader.read() } returns MakeYoursPlatform(notificationsGranted = true)
        vm.refreshPlatform()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.platform.notificationsGranted)
        coVerify(exactly = 0) { wakeWordPreferences.setHeyJandalEnabled(any()) }
    }

    @Test
    fun `notification prompt already asked in this run survives process death`() {
        val handle = SavedStateHandle()
        val first = viewModel(handle)
        first.atMakeYoursStep()
        first.onNotificationsRequested()
        assertTrue(first.uiState.value.notificationsAsked)

        val restored = viewModel(handle)
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(restored.uiState.value.notificationsAsked)
    }
}
