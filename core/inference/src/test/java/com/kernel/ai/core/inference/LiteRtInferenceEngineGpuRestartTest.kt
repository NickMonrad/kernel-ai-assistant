package com.kernel.ai.core.inference

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [checkGpuRestartNeeded] — the pure-function GPU restart
 * decision logic extracted from [LiteRtInferenceEngine.resetConversation].
 *
 * These tests verify the threshold arithmetic and backend branching without
 * requiring LiteRT hardware or engine construction.
 */
class LiteRtInferenceEngineGpuRestartTest {

    // -------------------------------------------------------------------------
    // GPU backend — below threshold
    // -------------------------------------------------------------------------

    @Test
    fun `GPU at count 0 below threshold returns shouldRestart=false and count=1`() {
        val decision = checkGpuRestartNeeded(
            gpuResetCount = 0,
            backend = BackendType.GPU,
            threshold = 30,
        )
        assertFalse(decision.shouldRestart, "GPU count 0 should not trigger restart")
        assertEquals(1, decision.updatedCount, "GPU count 0 should increment to 1")
    }

    @Test
    fun `GPU at count 28 below threshold returns shouldRestart=false and count=29`() {
        val decision = checkGpuRestartNeeded(
            gpuResetCount = 28,
            backend = BackendType.GPU,
            threshold = 30,
        )
        assertFalse(decision.shouldRestart, "GPU count 28 should not trigger restart")
        assertEquals(29, decision.updatedCount, "GPU count 28 should increment to 29")
    }

    @Test
    fun `GPU at count 29 hits threshold 30 returns shouldRestart=true and count=0`() {
        // 29 + 1 = 30 >= threshold 30 → restart and reset
        val decision = checkGpuRestartNeeded(
            gpuResetCount = 29,
            backend = BackendType.GPU,
            threshold = 30,
        )
        assertTrue(decision.shouldRestart, "GPU count 29 + 1 = 30 at threshold 30 should trigger restart")
        assertEquals(0, decision.updatedCount, "GPU count 29 at threshold should reset to 0")
    }
    // GPU backend — at / above threshold
    // -------------------------------------------------------------------------

    @Test
    fun `GPU at count 30 hits threshold triggers restart and resets count to 0`() {
        val decision = checkGpuRestartNeeded(
            gpuResetCount = 30,
            backend = BackendType.GPU,
            threshold = 30,
        )
        assertTrue(decision.shouldRestart, "GPU count 30 should trigger restart")
        assertEquals(0, decision.updatedCount, "GPU count 30 should reset to 0")
    }

    @Test
    fun `GPU at count 29 with default threshold 30 returns shouldRestart=true and count=0`() {
        // Uses default threshold (GPU_ENGINE_RESTART_INTERVAL = 30)
        val decision = checkGpuRestartNeeded(
            gpuResetCount = 29,
            backend = BackendType.GPU,
            // no explicit threshold — uses default
        )
        assertTrue(decision.shouldRestart, "GPU count 29 at default threshold should trigger restart")
        assertEquals(0, decision.updatedCount, "GPU count 29 at default threshold should reset to 0")
    }

    @Test
    fun `GPU at count 31 above threshold triggers restart and resets count to 0`() {
        // Defensive: if count somehow exceeds threshold, still restart
        val decision = checkGpuRestartNeeded(
            gpuResetCount = 31,
            backend = BackendType.GPU,
            threshold = 30,
        )
        assertTrue(decision.shouldRestart, "GPU count 31 should trigger restart (>= threshold)")
        assertEquals(0, decision.updatedCount, "GPU count 31 should reset to 0")
    }

    // -------------------------------------------------------------------------
    // GPU backend — custom thresholds
    // -------------------------------------------------------------------------

    @Test
    fun `GPU at count 0 with threshold 1 triggers restart immediately`() {
        val decision = checkGpuRestartNeeded(
            gpuResetCount = 0,
            backend = BackendType.GPU,
            threshold = 1,
        )
        assertTrue(decision.shouldRestart, "GPU count 0 at threshold 1 should restart (0+1 >= 1)")
        assertEquals(0, decision.updatedCount, "GPU count 0 at threshold 1 should reset to 0")
    }

    // -------------------------------------------------------------------------
    // Non-GPU backends — always reset count, never restart
    // -------------------------------------------------------------------------

    @Test
    fun `CPU backend resets count to 0 and never triggers restart`() {
        val decision = checkGpuRestartNeeded(
            gpuResetCount = 29,
            backend = BackendType.CPU,
        )
        assertFalse(decision.shouldRestart, "CPU should never trigger restart")
        assertEquals(0, decision.updatedCount, "CPU should reset count to 0")
    }

    @Test
    fun `NPU backend resets count to 0 and never triggers restart`() {
        val decision = checkGpuRestartNeeded(
            gpuResetCount = 15,
            backend = BackendType.NPU,
        )
        assertFalse(decision.shouldRestart, "NPU should never trigger restart")
        assertEquals(0, decision.updatedCount, "NPU should reset count to 0")
    }

    @Test
    fun `AUTO backend resets count to 0 and never triggers restart`() {
        val decision = checkGpuRestartNeeded(
            gpuResetCount = 7,
            backend = BackendType.AUTO,
        )
        assertFalse(decision.shouldRestart, "AUTO should never trigger restart")
        assertEquals(0, decision.updatedCount, "AUTO should reset count to 0")
    }

    @Test
    fun `CPU backend resets count to 0 even when count is zero`() {
        val decision = checkGpuRestartNeeded(
            gpuResetCount = 0,
            backend = BackendType.CPU,
        )
        assertFalse(decision.shouldRestart, "CPU at count 0 should not restart")
        assertEquals(0, decision.updatedCount, "CPU at count 0 should stay at 0")
    }

    // -------------------------------------------------------------------------
    // Default threshold
    // -------------------------------------------------------------------------

    @Test
    fun `GPU below default threshold 30 uses GPU_ENGINE_RESTART_INTERVAL`() {
        val decision = checkGpuRestartNeeded(
            gpuResetCount = 0,
            backend = BackendType.GPU,
            // No explicit threshold — defaults to GPU_ENGINE_RESTART_INTERVAL = 30
        )
        assertFalse(decision.shouldRestart, "GPU count 0 at default threshold should not restart")
        assertEquals(1, decision.updatedCount, "GPU count 0 at default threshold should increment")
    }
}
