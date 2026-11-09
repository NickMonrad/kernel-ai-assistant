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

    @Test
    fun `periodic GPU restart is disabled by default for every backend`() {
        for (backend in BackendType.entries) {
            val decision = checkGpuRestartNeeded(
                gpuResetCount = 4,
                backend = backend,
            )
            assertFalse(decision.shouldRestart, "$backend must not restart by default")
            assertEquals(0, decision.updatedCount, "$backend reset count stays cleared by default")
        }
    }

    @Test
    fun `disabled GPU restart stays off beyond the historical threshold`() {
        var count = 0
        repeat(100) {
            val decision = checkGpuRestartNeeded(count, BackendType.GPU)
            assertFalse(decision.shouldRestart)
            count = decision.updatedCount
        }
        assertEquals(0, count)
    }

    @Test
    fun `debug preference enables restart only in debuggable builds`() {
        assertFalse(shouldEnableGpuEngineRestart(debuggableBuild = true, preferenceEnabled = false))
        assertTrue(shouldEnableGpuEngineRestart(debuggableBuild = true, preferenceEnabled = true))
        assertFalse(shouldEnableGpuEngineRestart(debuggableBuild = false, preferenceEnabled = true))
    }

    @Test
    fun `debug opt-in retains the historical fifth GPU reset cadence`() {
        var count = 0
        repeat(4) {
            val decision = checkGpuRestartNeeded(
                gpuResetCount = count,
                backend = BackendType.GPU,
                restartEnabled = true,
            )
            assertFalse(decision.shouldRestart)
            count = decision.updatedCount
        }
        assertEquals(4, count)

        val fifth = checkGpuRestartNeeded(
            gpuResetCount = count,
            backend = BackendType.GPU,
            restartEnabled = true,
        )
        assertTrue(fifth.shouldRestart)
        assertEquals(0, fifth.updatedCount)
    }

    @Test
    fun `debug opt-in still never restarts non-GPU backends`() {
        for (backend in listOf(BackendType.CPU, BackendType.NPU, BackendType.AUTO)) {
            val decision = checkGpuRestartNeeded(
                gpuResetCount = 4,
                backend = backend,
                restartEnabled = true,
            )
            assertFalse(decision.shouldRestart, "$backend must never use the GPU restart")
            assertEquals(0, decision.updatedCount)
        }
    }
}
