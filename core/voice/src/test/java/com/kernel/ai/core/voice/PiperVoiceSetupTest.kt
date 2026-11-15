package com.kernel.ai.core.voice

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class PiperVoiceSetupTest {
    private val voiceOutputPreferences = mockk<VoiceOutputPreferences>()
    private val voicePackDownloadManager = mockk<SherpaVoicePackDownloadManager>(relaxed = true)
    private val states = MutableStateFlow<Map<SherpaPiperVoice, VoicePackDownloadState>>(emptyMap())
    private val setup = PiperVoiceSetup(voiceOutputPreferences, voicePackDownloadManager)

    init {
        every { voiceOutputPreferences.selectedSherpaVoice } returns flowOf(SherpaPiperVoice.CoriHigh)
        every { voicePackDownloadManager.downloadStates } returns states
    }

    @Test
    fun `choosing Android TTS starts no download`() = runBlocking {
        setup.onEngineChosen(VoiceOutputEngine.AndroidTts)

        verify(exactly = 0) { voicePackDownloadManager.startDownload(any()) }
    }

    @Test
    fun `choosing Piper starts the selected missing voice once`() = runBlocking {
        setup.onEngineChosen(VoiceOutputEngine.SherpaExperimental)

        verify(exactly = 1) { voicePackDownloadManager.startDownload(SherpaPiperVoice.CoriHigh) }
    }

    @Test
    fun `choosing Piper while the voice is downloading does not restart it`() = runBlocking {
        states.value = mapOf(
            SherpaPiperVoice.CoriHigh to VoicePackDownloadState.Downloading(progress = 0.4f),
        )

        setup.onEngineChosen(VoiceOutputEngine.SherpaExperimental)

        verify(exactly = 0) { voicePackDownloadManager.startDownload(any()) }
    }

    @Test
    fun `choosing Piper after a failed attempt starts the voice again`() = runBlocking {
        states.value = mapOf(
            SherpaPiperVoice.CoriHigh to VoicePackDownloadState.Error("network lost"),
        )

        setup.onEngineChosen(VoiceOutputEngine.SherpaExperimental)

        verify(exactly = 1) { voicePackDownloadManager.startDownload(SherpaPiperVoice.CoriHigh) }
    }

    @Test
    fun `choosing Piper with the voice already downloaded does not download it again`() = runBlocking {
        states.value = mapOf(
            SherpaPiperVoice.CoriHigh to VoicePackDownloadState.Downloaded(localDir = "/voices/cori"),
        )

        setup.onEngineChosen(VoiceOutputEngine.SherpaExperimental)

        verify(exactly = 0) { voicePackDownloadManager.startDownload(any()) }
    }
}
