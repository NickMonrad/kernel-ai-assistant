package com.kernel.ai.feature.chat.setup

import androidx.lifecycle.SavedStateHandle
import com.kernel.ai.core.inference.download.DownloadNetworkChoice
import com.kernel.ai.core.inference.download.ModelDownloadManager
import com.kernel.ai.core.inference.hardware.HardwareTier
import com.kernel.ai.core.inference.prefs.DownloadNetworkPreferences
import com.kernel.ai.core.inference.setup.SetupStep
import com.kernel.ai.core.inference.setup.SetupWizardPreferences
import com.kernel.ai.core.inference.setup.SetupWizardProgress
import com.kernel.ai.core.voice.PiperVoiceSetup
import com.kernel.ai.core.voice.SherpaPiperVoice
import com.kernel.ai.core.voice.SherpaVoicePackDownloadManager
import com.kernel.ai.core.voice.VoiceCapabilities
import com.kernel.ai.core.voice.VoiceInputEngine
import com.kernel.ai.core.voice.VoiceInputPreferences
import com.kernel.ai.core.voice.VoiceOutputEngine
import com.kernel.ai.core.voice.VoiceOutputPreferences
import com.kernel.ai.core.voice.VoicePackDownloadState
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

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { setupWizardPreferences.progress } returns flowOf(SetupWizardProgress(decided = true, completed = false))
        every { voicePackDownloadManager.downloadStates } returns MutableStateFlow(emptyMap())
        every { voiceOutputPreferences.selectedEngine } returns flowOf(VoiceOutputEngine.SherpaExperimental)
        every { voiceOutputPreferences.selectedSherpaVoice } returns flowOf(SherpaPiperVoice.CoriHigh)
        every { voiceInputPreferences.selectedEngine } returns flowOf(VoiceInputEngine.Auto)
        every { downloadNetworkPreferences.choice } returns flowOf(null)
        coEvery { downloadManager.plannedFirstRunModels() } returns emptyList()
        device(caps = FULL_CAPS, hasTts = true, tier = HardwareTier.FLAGSHIP)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun device(caps: VoiceCapabilities, hasTts: Boolean, tier: HardwareTier) {
        every { setupVoiceDevice.speechInputCapabilities() } returns caps
        every { setupVoiceDevice.hasSystemTtsEngine() } returns hasTts
        every { setupVoiceDevice.hardwareTier() } returns tier
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

    @Test
    fun `skipping with no saved choice saves Wi-Fi only so models still download`() {
        var done = false

        viewModel().onSkip { done = true }
        dispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { downloadManager.setDownloadNetworkChoice(DownloadNetworkChoice.WIFI_ONLY) }
        coVerify(exactly = 1) { setupWizardPreferences.markCompleted() }
        assertTrue(done)
    }

    @Test
    fun `skipping keeps an explicit earlier download choice`() {
        every { downloadNetworkPreferences.choice } returns flowOf(DownloadNetworkChoice.MOBILE_DATA_ALLOWED)

        viewModel().onSkip {}
        dispatcher.scheduler.advanceUntilIdle()

        verify(exactly = 0) { downloadManager.setDownloadNetworkChoice(any()) }
        coVerify(exactly = 1) { setupWizardPreferences.markCompleted() }
    }

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

    // ── Speech options by device capability (#1855 part 2) ──────────────────────

    @Test
    fun `a phone without an on-device recognizer or TTS engine offers Automatic and Cori only`() {
        device(caps = VoiceCapabilities(platformRecognizerPresent = false, onDeviceRecognitionAvailable = false), hasTts = false, tier = HardwareTier.FLAGSHIP)

        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(VoiceInputEngine.Auto), vm.uiState.value.inputOptions)
        assertEquals(listOf(VoiceOutputEngine.SherpaExperimental), vm.uiState.value.outputOptions)
        assertTrue(vm.uiState.value.inputReason != null)
    }

    @Test
    fun `a phone with the on-device recognizer offers both inputs and no reason`() {
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(VoiceInputEngine.Auto, VoiceInputEngine.AndroidNative), vm.uiState.value.inputOptions)
        assertEquals(null, vm.uiState.value.inputReason)
        assertEquals(listOf(VoiceOutputEngine.SherpaExperimental, VoiceOutputEngine.AndroidTts), vm.uiState.value.outputOptions)
    }

    // ── Defaults ─────────────────────────────────────────────────────────────────

    @Test
    fun `defaults are Automatic input and the tier output on a fresh install`() {
        every { voiceOutputPreferences.selectedEngine } returns flowOf(VoiceOutputEngine.defaultForTier(HardwareTier.MID_RANGE))

        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(VoiceInputEngine.Auto, vm.uiState.value.inputEngine)
        assertEquals(VoiceOutputEngine.SherpaExperimental, vm.uiState.value.outputEngine)
    }

    @Test
    fun `an updated install that stored Android TTS is offered Cori on a re-run on a mid-range phone`() {
        every { voiceOutputPreferences.selectedEngine } returns flowOf(VoiceOutputEngine.AndroidTts)
        device(caps = FULL_CAPS, hasTts = true, tier = HardwareTier.MID_RANGE)

        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(VoiceOutputEngine.SherpaExperimental, vm.uiState.value.outputEngine)
        assertEquals(listOf(SherpaPiperVoice.CoriHigh), vm.uiState.value.plannedVoices)
    }

    @Test
    fun `a low-power phone keeps Android TTS as its default output`() {
        every { voiceOutputPreferences.selectedEngine } returns flowOf(VoiceOutputEngine.AndroidTts)
        device(caps = FULL_CAPS, hasTts = true, tier = HardwareTier.LOW_POWER)

        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(VoiceOutputEngine.AndroidTts, vm.uiState.value.outputEngine)
        assertEquals(emptyList<SherpaPiperVoice>(), vm.uiState.value.plannedVoices)
    }

    @Test
    fun `a stored choice Automatic-only device no longer offers stays off the list`() {
        every { voiceInputPreferences.selectedEngine } returns flowOf(VoiceInputEngine.AndroidNative)
        device(caps = VoiceCapabilities(platformRecognizerPresent = false, onDeviceRecognitionAvailable = false), hasTts = true, tier = HardwareTier.FLAGSHIP)

        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(VoiceInputEngine.Auto, vm.uiState.value.inputEngine)
    }

    @Test
    fun `choosing Android TTS removes Cori from the planned voices`() {
        val vm = viewModel()
        vm.atDownloadStep()
        assertEquals(listOf(SherpaPiperVoice.CoriHigh), vm.uiState.value.plannedVoices)

        vm.onOutputSelected(VoiceOutputEngine.AndroidTts)

        assertEquals(emptyList<SherpaPiperVoice>(), vm.uiState.value.plannedVoices)
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
        coVerify(exactly = 0) { downloadManager.setDownloadNetworkChoice(any()) }
    }

    @Test
    fun `nothing downloads before the download step is finished`() {
        val vm = viewModel()
        vm.atDownloadStep()
        vm.onChoiceSelected(DownloadNetworkChoice.MOBILE_DATA_ALLOWED)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(SetupStep.DOWNLOAD, vm.uiState.value.step)
        coVerify(exactly = 0) { downloadManager.setDownloadNetworkChoice(any()) }
        verify(exactly = 0) { voicePackDownloadManager.startDownload(any(), any(), any(), any()) }
    }

    @Test
    fun `Finish saves speech choices before the network choice, then starts Cori`() {
        val vm = viewModel()
        vm.atDownloadStep()
        vm.onChoiceSelected(DownloadNetworkChoice.MOBILE_DATA_ALLOWED)

        vm.onContinue {}
        dispatcher.scheduler.advanceUntilIdle()

        coVerifyOrder {
            voiceInputPreferences.setSelectedEngine(VoiceInputEngine.Auto)
            voiceOutputPreferences.setSelectedEngine(VoiceOutputEngine.SherpaExperimental)
            downloadManager.setDownloadNetworkChoice(DownloadNetworkChoice.MOBILE_DATA_ALLOWED)
            voicePackDownloadManager.startDownload(SherpaPiperVoice.CoriHigh)
        }
        coVerify(exactly = 1) { setupWizardPreferences.markCompleted() }
    }

    @Test
    fun `Finish with Android TTS saves it and starts no voice download`() {
        val vm = viewModel()
        vm.atDownloadStep()
        vm.onOutputSelected(VoiceOutputEngine.AndroidTts)

        vm.onContinue {}
        dispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { voiceOutputPreferences.setSelectedEngine(VoiceOutputEngine.AndroidTts) }
        verify(exactly = 0) { voicePackDownloadManager.startDownload(any(), any(), any(), any()) }
    }

    @Test
    fun `Skip saves the speech choices made so far`() {
        val vm = viewModel()
        vm.atSpeechStep()
        vm.onInputSelected(VoiceInputEngine.AndroidNative)
        vm.onOutputSelected(VoiceOutputEngine.AndroidTts)

        vm.onSkip {}
        dispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { voiceInputPreferences.setSelectedEngine(VoiceInputEngine.AndroidNative) }
        coVerify(exactly = 1) { voiceOutputPreferences.setSelectedEngine(VoiceOutputEngine.AndroidTts) }
    }

    private companion object {
        val FULL_CAPS = VoiceCapabilities(platformRecognizerPresent = true, onDeviceRecognitionAvailable = true)
    }
}
