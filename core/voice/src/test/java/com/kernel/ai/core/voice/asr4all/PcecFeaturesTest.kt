package com.kernel.ai.core.voice.asr4all

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PcecFeaturesTest {
    private val pcec = Asr4AllTestData.configM().pcec

    @Test
    fun `pause buckets are log2 frame gaps capped at the last bucket`() {
        val gaps = listOf(0, 1, 2, 3, 4, 7, 8, 15, 16, 31, 32, 63, 64, 127, 128, 1000)
        val expected = listOf(0, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8)

        assertEquals(expected, gaps.map { PcecFeatures.pauseBucket(pcec, it) })
    }
}
