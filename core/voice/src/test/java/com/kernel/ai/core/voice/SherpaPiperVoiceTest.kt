package com.kernel.ai.core.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SherpaPiperVoiceTest {

    @Test
    fun `unknown stored voice falls back to Jenny`() {
        assertEquals(SherpaPiperVoice.JennyDioco, SherpaPiperVoice.fromStorage("not-a-real-voice"))
    }

    @Test
    fun `catalog includes expanded English voices with unique release identifiers`() {
        val expectedNewVoices = setOf(
            SherpaPiperVoice.AlanMedium,
            SherpaPiperVoice.CoriHigh,
            SherpaPiperVoice.AmyMedium,
            SherpaPiperVoice.JoeMedium,
            SherpaPiperVoice.LessacHigh,
            SherpaPiperVoice.RyanHigh,
        )

        assertTrue(SherpaPiperVoice.entries.containsAll(expectedNewVoices))
        assertEquals(
            SherpaPiperVoice.entries.size,
            SherpaPiperVoice.entries.map { it.assetDirectoryName }.toSet().size,
        )
        assertEquals(
            SherpaPiperVoice.entries.size,
            SherpaPiperVoice.entries.map { it.downloadKey }.toSet().size,
        )
        assertTrue(
            SherpaPiperVoice.entries.all { voice ->
                voice.approxDownloadBytes > 0 &&
                    voice.downloadUrl.endsWith("${voice.assetDirectoryName}.tar.bz2")
            },
        )
    }
}
