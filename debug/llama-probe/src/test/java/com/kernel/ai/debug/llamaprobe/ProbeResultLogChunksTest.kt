package com.kernel.ai.debug.llamaprobe

import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProbeResultLogChunksTest {
    @Test
    fun `large JSON is split below byte limit without breaking UTF-8`() {
        val json = "{\"summary\":\"${"meal🍲plan".repeat(1_000)}\"}"
        val maxBytes = 257

        val chunks = splitProbeJsonForLogcat(json, maxBytes)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.toByteArray(StandardCharsets.UTF_8).size <= maxBytes })
        assertEquals(json, chunks.joinToString(separator = ""))
    }

    @Test
    fun `default chunks stay below Android log record budget`() {
        val json = "{\"result\":\"${"x".repeat(6_000)}\"}"

        val chunks = splitProbeJsonForLogcat(json)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.toByteArray(StandardCharsets.UTF_8).size <= 2_800 })
        assertEquals(json, chunks.joinToString(separator = ""))
    }

    @Test
    fun `empty result produces no log records`() {
        assertTrue(splitProbeJsonForLogcat("").isEmpty())
    }

    @Test
    fun `large byte limit does not overflow chunk end calculation`() {
        val json = "{\"ok\":true}"

        assertEquals(listOf(json), splitProbeJsonForLogcat(json, Int.MAX_VALUE))
    }

    @Test
    fun `byte limit must fit one UTF-8 code point`() {
        assertThrows(IllegalArgumentException::class.java) {
            splitProbeJsonForLogcat("{}", maxBytes = 3)
        }
    }
}
