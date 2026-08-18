package com.kernel.ai.core.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InflectMicroOnnxRunnerTest {
    @Test
    fun `phoneme IDs preserve upstream blank interleave contract`() {
        val ids = InflectMicroOnnxRunner.phonemesToTokenIds("həlˈoʊ")

        assertEquals(13, ids.size)
        assertEquals(0L, ids.first())
        assertEquals(0L, ids[2])
        assertEquals(0L, ids[4])
        assertEquals(0L, ids[6])
        assertEquals(0L, ids[8])
        assertEquals(0L, ids[10])
        assertEquals(0L, ids.last())
        assertTrue(ids.slice(1 until ids.lastIndex).filterIndexed { index, _ -> index % 2 == 0 }
            .all { it > 0L })
    }

    @Test
    fun `unsupported symbols fail closed instead of producing invalid IDs`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            InflectMicroOnnxRunner.phonemesToTokenIds("🙂")
        }

        assertTrue(error.message.orEmpty().contains("Unsupported Inflect symbol"))
    }

    @Test
    fun `empty phoneme output is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            InflectMicroOnnxRunner.phonemesToTokenIds("")
        }
    }
}
