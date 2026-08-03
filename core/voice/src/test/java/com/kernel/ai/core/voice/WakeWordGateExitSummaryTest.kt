package com.kernel.ai.core.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Contract tests for the #1432 debug-gated per-gate-exit diagnostic summary
 * ([buildGateExitSummary]).  The summary is the bounded instrument used to
 * capture sub-threshold classifier confidence during the physical smoke, so
 * its format and value rounding must be stable and parseable.
 */
class WakeWordGateExitSummaryTest {

    @Test
    fun `summary carries all bounded aggregate fields`() {
        val s = buildGateExitSummary(
            generationId = 7L,
            episodeOpen = true,
            stage3Evaluations = 42,
            maxConfidence = 0.51654834f,
            maxConfidenceChunk = 503,
            lowVerifyEntered = true,
            lowVerifyAccepted = false,
            gatedStage2Executions = 61L,
        )

        assertTrue(s.contains("gen=7"))
        assertTrue(s.contains("episodeOpen=true"))
        assertTrue(s.contains("stage3Evals=42"))
        assertTrue(s.contains("maxConfidence=0.51654834"))
        assertTrue(s.contains("maxConfidenceChunk=503"))
        assertTrue(s.contains("lowVerifyEntered=true"))
        assertTrue(s.contains("lowVerifyAccepted=false"))
        assertTrue(s.contains("gatedStage2Executions=61"))
    }

    @Test
    fun `summary reports none when no score was observed in the episode`() {
        val s = buildGateExitSummary(
            generationId = 8L,
            episodeOpen = false,
            stage3Evaluations = 0,
            maxConfidence = -1f,
            maxConfidenceChunk = 0,
            lowVerifyEntered = false,
            lowVerifyAccepted = false,
            gatedStage2Executions = 0L,
        )

        assertTrue(s.contains("stage3Evals=0"))
        assertTrue(s.contains("maxConfidence=none"))
        assertTrue(s.contains("lowVerifyEntered=false"))
    }

    @Test
    fun `summary uses locale-independent float formatting`() {
        val s = buildGateExitSummary(
            generationId = 1L,
            episodeOpen = true,
            stage3Evaluations = 1,
            maxConfidence = 0.6f,
            maxConfidenceChunk = 100,
            lowVerifyEntered = true,
            lowVerifyAccepted = true,
            gatedStage2Executions = 3L,
        )

        // Float.toString() is locale-independent; a comma decimal separator
        // would break logcat parsing.
        assertEquals("0.6", s.substringAfter("maxConfidence=").substringBefore(" maxConfidenceChunk="))
    }
}
