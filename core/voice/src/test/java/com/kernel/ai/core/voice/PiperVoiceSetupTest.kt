package com.kernel.ai.core.voice

import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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
    fun `choosing Android TTS starts no download and returns no voice`() = runBlocking {
        assertNull(setup.onEngineChosen(VoiceOutputEngine.AndroidTts))
        verify(exactly = 0) { voicePackDownloadManager.startDownload(any()) }
    }

    @Test
    fun `choosing Piper starts the selected missing voice once and returns it`() = runBlocking {
        val voice = setup.onEngineChosen(VoiceOutputEngine.SherpaExperimental)

        assertEquals(SherpaPiperVoice.CoriHigh, voice)
        coVerify(exactly = 1) { voicePackDownloadManager.startDownload(SherpaPiperVoice.CoriHigh) }
    }

    @Test
    fun `choosing Piper while the voice is downloading does not restart it`() = runBlocking {
        states.value = mapOf(
            SherpaPiperVoice.CoriHigh to VoicePackDownloadState.Downloading(progress = 0.4f),
        )

        val voice = setup.onEngineChosen(VoiceOutputEngine.SherpaExperimental)

        assertEquals(SherpaPiperVoice.CoriHigh, voice)
        verify(exactly = 0) { voicePackDownloadManager.startDownload(any()) }
    }

    @Test
    fun `missing voice starts when Piper is chosen`() {
        assertTrue(PiperVoiceSetup.shouldStartDownload(VoicePackDownloadState.NotDownloaded))
    }

    @Test
    fun `failed voice starts again so the row offers a retry that works`() {
        assertTrue(PiperVoiceSetup.shouldStartDownload(VoicePackDownloadState.Error("network lost")))
    }

    @Test
    fun `running download is not restarted by choosing Piper again`() {
        assertFalse(
            PiperVoiceSetup.shouldStartDownload(VoicePackDownloadState.Downloading(progress = 0.4f)),
        )
    }

    @Test
    fun `downloaded voice is not downloaded again`() {
        assertFalse(
            PiperVoiceSetup.shouldStartDownload(VoicePackDownloadState.Downloaded(localDir = "/voices/cori")),
        )
    }
}
