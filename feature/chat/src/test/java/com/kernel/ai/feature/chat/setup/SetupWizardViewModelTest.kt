package com.kernel.ai.feature.chat.setup

import androidx.lifecycle.SavedStateHandle
import com.kernel.ai.core.inference.download.DownloadNetworkChoice
import com.kernel.ai.core.inference.download.ModelDownloadManager
import com.kernel.ai.core.inference.prefs.DownloadNetworkPreferences
import com.kernel.ai.core.inference.setup.SetupWizardPreferences
import com.kernel.ai.core.inference.setup.SetupWizardProgress
import com.kernel.ai.core.voice.SherpaPiperVoice
import com.kernel.ai.core.voice.SherpaVoicePackDownloadManager
import com.kernel.ai.core.voice.VoiceOutputEngine
import com.kernel.ai.core.voice.VoiceOutputPreferences
import com.kernel.ai.core.voice.VoicePackDownloadState
import io.mockk.coEvery
import io.mockk.coVerify
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

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { setupWizardPreferences.progress } returns flowOf(SetupWizardProgress(decided = true, completed = false))
        every { voicePackDownloadManager.downloadStates } returns MutableStateFlow(emptyMap())
        every { voiceOutputPreferences.selectedEngine } returns flowOf(VoiceOutputEngine.SherpaExperimental)
        coEvery { downloadManager.plannedFirstRunModels() } returns emptyList()
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(handle: SavedStateHandle = SavedStateHandle()): SetupWizardViewModel =
        SetupWizardViewModel(
            downloadManager,
            downloadNetworkPreferences,
            setupWizardPreferences,
            voicePackDownloadManager,
            voiceOutputPreferences,
            handle,
        )

    @Test
    fun `skipping with no saved choice saves Wi-Fi only so models still download`() {
        every { downloadNetworkPreferences.choice } returns flowOf(null)
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
        every { downloadNetworkPreferences.choice } returns flowOf(null)
        val handle = SavedStateHandle()
        val before = viewModel(handle)
        dispatcher.scheduler.advanceUntilIdle()
        before.onChoiceSelected(DownloadNetworkChoice.MOBILE_DATA_ALLOWED)

        val restored = viewModel(handle)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(DownloadNetworkChoice.MOBILE_DATA_ALLOWED, restored.uiState.value.choice)
        assertFalse(restored.uiState.value.choiceRecorded)
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
}
