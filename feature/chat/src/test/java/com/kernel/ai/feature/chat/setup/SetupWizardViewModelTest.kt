package com.kernel.ai.feature.chat.setup

import com.kernel.ai.core.inference.download.DownloadNetworkChoice
import com.kernel.ai.core.inference.download.ModelDownloadManager
import com.kernel.ai.core.inference.hardware.HardwareTier
import com.kernel.ai.core.inference.prefs.DownloadNetworkPreferences
import com.kernel.ai.core.inference.setup.SetupWizardPreferences
import com.kernel.ai.core.inference.setup.SetupWizardProgress
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import io.mockk.verify

class SetupWizardViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val downloadManager: ModelDownloadManager = mockk(relaxed = true)
    private val downloadNetworkPreferences: DownloadNetworkPreferences = mockk(relaxed = true)
    private val setupWizardPreferences: SetupWizardPreferences = mockk(relaxed = true)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { downloadManager.deviceTier } returns HardwareTier.FLAGSHIP
        every { setupWizardPreferences.progress } returns flowOf(SetupWizardProgress(decided = true, completed = false))
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = SetupWizardViewModel(downloadManager, downloadNetworkPreferences, setupWizardPreferences)

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
}
