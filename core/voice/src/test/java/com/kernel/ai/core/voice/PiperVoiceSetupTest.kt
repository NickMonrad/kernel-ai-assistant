package com.kernel.ai.core.voice

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PiperVoiceSetupTest {
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
