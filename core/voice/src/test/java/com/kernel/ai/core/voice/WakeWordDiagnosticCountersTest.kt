package com.kernel.ai.core.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WakeWordDiagnosticCountersTest {
    @Test
    fun `snapshot reports stage counters and silence gate ratio`() {
        val counters = WakeWordDiagnosticCounters()
        repeat(20) { counters.recordAudioFrame() }
        repeat(20) { counters.recordStage1Execution() }
        repeat(5) { counters.recordStage2Execution() }
        repeat(3) { counters.recordStage3Execution() }
        repeat(15) { counters.recordSilenceGateSkip() }

        val snapshot = counters.snapshot(elapsedMillis = 3_600_000L)

        assertEquals(20, snapshot.audioFrames)
        assertEquals(20, snapshot.stage1Executions)
        assertEquals(5, snapshot.stage2Executions)
        assertEquals(3, snapshot.stage3Executions)
        assertEquals(20, snapshot.stage23EligibleFrames)
        assertEquals(0.75, snapshot.silenceGateSkipRatio)
        assertEquals(5.0, snapshot.stage2ExecutionsPerHour())
        assertEquals(3.0, snapshot.stage3ExecutionsPerHour())
    }

    @Test
    fun `snapshot reports verifier outcomes and activation paths`() {
        val counters = WakeWordDiagnosticCounters()
        counters.recordVerifierResult(passed = true)
        counters.recordVerifierResult(passed = false)
        counters.recordHighConfidenceActivation()
        counters.recordVerifiedActivation()

        val snapshot = counters.snapshot(elapsedMillis = 1_800_000L)

        assertEquals(2, snapshot.verifierInvocations)
        assertEquals(1, snapshot.verifierPasses)
        assertEquals(1, snapshot.verifierRejects)
        assertEquals(1, snapshot.highConfidenceActivations)
        assertEquals(1, snapshot.verifiedActivations)
        assertEquals(4.0, snapshot.verifierInvocationsPerHour())
    }

    @Test
    fun `zero elapsed snapshot avoids invalid rates`() {
        val counters = WakeWordDiagnosticCounters()

        val snapshot = counters.snapshot(elapsedMillis = 0L)

        assertEquals(0.0, snapshot.silenceGateSkipRatio)
        assertEquals(0.0, snapshot.stage2ExecutionsPerHour())
        assertEquals(0.0, snapshot.stage3ExecutionsPerHour())
        assertEquals(0.0, snapshot.verifierInvocationsPerHour())
    }
}
