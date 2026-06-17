package com.kernel.ai.core.voice

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SherpaVoicePackDownloadManagerTest {

    @Test
    fun `canDownloadVoice allows release-visible voice in release build`() {
        assertTrue(SherpaVoicePackDownloadManager.canDownloadVoice(
            isReleaseBuild = true,
            voice = SherpaPiperVoice.CoriHigh,
        ))
    }

    @Test
    fun `canDownloadVoice allows non-release-visible voice in debug build`() {
        assertTrue(SherpaVoicePackDownloadManager.canDownloadVoice(
            isReleaseBuild = false,
            voice = SherpaPiperVoice.SemaineMedium,
        ))
    }

    @Test
    fun `canDownloadVoice blocks non-release-visible voice in release build`() {
        assertFalse(SherpaVoicePackDownloadManager.canDownloadVoice(
            isReleaseBuild = true,
            voice = SherpaPiperVoice.SemaineMedium,
        ))
    }

    @Test
    fun `canDownloadVoice allows release-visible voice in debug build`() {
        assertTrue(SherpaVoicePackDownloadManager.canDownloadVoice(
            isReleaseBuild = false,
            voice = SherpaPiperVoice.CoriHigh,
        ))
    }
}
