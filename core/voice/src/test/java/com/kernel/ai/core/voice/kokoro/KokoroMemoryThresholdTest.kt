package com.kernel.ai.core.voice.kokoro

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KokoroMemoryThresholdTest {
    private val mib = 1024L * 1024

    private fun snapshot(memAvailable: Long?, availMem: Long, lowMemory: Boolean = false) =
        KokoroMemorySnapshot(lowMemory = lowMemory, memAvailableBytes = memAvailable, availMemBytes = availMem)

    @Test
    fun `background warm-up keeps its 2 GiB threshold`() {
        assertEquals(2048 * mib, KOKORO_WARM_UP_MIN_AVAILABLE_BYTES)
        assertFalse(kokoroHasHeadroom(snapshot(2048 * mib - 1, 2048 * mib), KOKORO_WARM_UP_MIN_AVAILABLE_BYTES))
        assertTrue(kokoroHasHeadroom(snapshot(2048 * mib, 2048 * mib), KOKORO_WARM_UP_MIN_AVAILABLE_BYTES))
    }

    @Test
    fun `user-initiated check and reply load need 1_25 GiB`() {
        assertEquals(1280 * mib, KOKORO_LOAD_MIN_AVAILABLE_BYTES)
        assertFalse(kokoroHasHeadroom(snapshot(1280 * mib - 1, 1280 * mib - 1), KOKORO_LOAD_MIN_AVAILABLE_BYTES))
        assertTrue(kokoroHasHeadroom(snapshot(1280 * mib, 1280 * mib), KOKORO_LOAD_MIN_AVAILABLE_BYTES))
    }

    @Test
    fun `a 1_5 GiB phone passes the load threshold but not the warm-up one`() {
        val s = snapshot(1536 * mib, 1536 * mib)
        assertTrue(kokoroHasHeadroom(s, KOKORO_LOAD_MIN_AVAILABLE_BYTES))
        assertFalse(kokoroHasHeadroom(s, KOKORO_WARM_UP_MIN_AVAILABLE_BYTES))
    }

    @Test
    fun `MemAvailable decides when readable`() {
        assertFalse(kokoroHasHeadroom(snapshot(memAvailable = 1024 * mib, availMem = 3072 * mib), KOKORO_LOAD_MIN_AVAILABLE_BYTES))
    }

    @Test
    fun `availMem decides when MemAvailable is unreadable`() {
        assertTrue(kokoroHasHeadroom(snapshot(memAvailable = null, availMem = 1536 * mib), KOKORO_LOAD_MIN_AVAILABLE_BYTES))
    }

    @Test
    fun `low memory blocks even with headroom`() {
        assertFalse(kokoroHasHeadroom(snapshot(3072 * mib, 3072 * mib, lowMemory = true), KOKORO_LOAD_MIN_AVAILABLE_BYTES))
    }

    @Test
    fun `details show both figures and the threshold`() {
        val text = snapshot(1024 * mib, 3072 * mib).describe(KOKORO_LOAD_MIN_AVAILABLE_BYTES)
        assertTrue(text.contains("MemAvailable=1024 MiB"), text)
        assertTrue(text.contains("availMem=3072 MiB"), text)
        assertTrue(text.contains("threshold=1280 MiB"), text)
    }
}
