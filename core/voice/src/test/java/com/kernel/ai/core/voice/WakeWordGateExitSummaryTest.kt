package com.kernel.ai.core.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Lifecycle contract tests for the #1432 debug-gated per-gate-exit diagnostic
 * ([WakeWordGateExitDiagnostics] + [buildGateExitSummary]).
 *
 * The diagnostic is the bounded instrument used to capture sub-threshold
 * classifier confidence during the physical smoke, so its lifecycle must match
 * the real detector exactly:
 *
 * ```
 * gate entered
 * → zero or more periodic gated Stage-2 probes
 * → gate exited on voiced audio
 * → zero or more Stage-3 evaluations / verifier attempts
 * → gate entered again or detector generation ended
 * → one summary emitted
 * ```
 *
 * These tests exercise the actual lifecycle state holder used by
 * [OnnxWakeWordDetector], not string construction in isolation.
 */
class WakeWordGateExitSummaryTest {

    private fun newDiag(generationId: Long = 1L) = WakeWordGateExitDiagnostics(generationId)

    // ── Lifecycle: emission rules ──────────────────────────────────────────

    @Test
    fun `initial gate entry produces no summary`() {
        val diag = newDiag()

        assertNull(diag.onGateEntered(100))
        assertTrue(!diag.episodeOpen)
    }

    @Test
    fun `second gate entry without an intervening exit produces no fabricated summary`() {
        val diag = newDiag()
        diag.onGateEntered(100)

        assertNull(diag.onGateEntered(400))
    }

    @Test
    fun `termination emits one final summary for an open episode`() {
        val diag = newDiag()
        diag.onGateEntered(100)
        diag.onGateExited(200)
        diag.onStage3Evaluation(0.42f, 202)

        val s = diag.finish()!!

        assertTrue(s.contains("stage3Evals=1"))
        assertTrue(s.contains("maxConfidence=0.42"))
        assertTrue(s.contains("maxConfidenceOffsetFrames=2"))
        assertTrue(s.contains("lowVerifyEntered=false"))
    }

    @Test
    fun `termination emits nothing when no episode is open`() {
        assertNull(newDiag().finish())

        val diag = newDiag()
        diag.onGateEntered(100)
        assertNull(diag.finish())

        val diag2 = newDiag()
        diag2.onGateEntered(100)
        diag2.onGateExited(200)
        diag2.onGateEntered(300) // episode closed by re-entry
        assertNull(diag2.finish())
    }

    @Test
    fun `gate re-entry after a closed episode emits no second summary`() {
        val diag = newDiag()
        diag.onGateEntered(100)
        diag.onGateExited(200)
        diag.onStage3Evaluation(0.5f, 201)
        val first = diag.onGateEntered(300)!!

        // Second gate entry with no intervening exit: no fabricated summary.
        assertNull(diag.onGateEntered(500))
    }

    // ── Lifecycle: one episode's own values ────────────────────────────────

    @Test
    fun `one episode reports only its own probes evals max offset and verifier result`() {
        val diag = newDiag(generationId = 7L)
        diag.onGateEntered(100)
        diag.onGatedProbeExecution()
        diag.onGatedProbeExecution()
        diag.onGateExited(200) // exit frame 200 = offset 0
        diag.onStage3Evaluation(0.1f, 200)
        diag.onStage3Evaluation(0.6f, 201)
        diag.onLowVerify(accepted = false)

        val s = diag.onGateEntered(500)!!
        assertTrue(s.contains("gen=7"))
        assertTrue(s.contains("stage3Evals=2"))
        assertTrue(s.contains("maxConfidence=0.6"))
        assertTrue(s.contains("maxConfidenceOffsetFrames=1"))
        assertTrue(s.contains("lowVerifyEntered=true"))
        assertTrue(s.contains("lowVerifyAccepted=false"))
        assertTrue(s.contains("gatedProbeExecutions=2"))
    }

    @Test
    fun `maximum confidence at the exit frame reports offset 0`() {
        val diag = newDiag()
        diag.onGateEntered(0)
        diag.onGateExited(77)
        diag.onStage3Evaluation(0.88f, 77) // same chunk as the exit

        val s = diag.onGateEntered(100)!!
        assertTrue(s.contains("maxConfidence=0.88"))
        assertTrue(s.contains("maxConfidenceOffsetFrames=0"))
    }

    @Test
    fun `evaluations before any episode are not counted`() {
        val diag = newDiag()
        diag.onStage3Evaluation(0.9f, 50) // startup, no episode open
        diag.onGateEntered(100)
        diag.onGateExited(200)
        diag.onStage3Evaluation(0.4f, 201)

        val s = diag.onGateEntered(300)!!
        assertTrue(s.contains("stage3Evals=1"))
        assertTrue(s.contains("maxConfidence=0.4"))
    }

    // ── Lifecycle: episode isolation ───────────────────────────────────────

    @Test
    fun `two consecutive episodes produce independent summaries with no state leakage`() {
        val diag = newDiag()

        // Episode 1: 0 probes, 1 evaluation at 0.9 (offset 1), verifier accepted.
        diag.onGateEntered(0)
        diag.onGateExited(10)
        diag.onStage3Evaluation(0.9f, 11)
        diag.onLowVerify(accepted = true)
        val first = diag.onGateEntered(100)!!
        assertTrue(first.contains("stage3Evals=1"))
        assertTrue(first.contains("maxConfidence=0.9"))
        assertTrue(first.contains("maxConfidenceOffsetFrames=1"))
        assertTrue(first.contains("lowVerifyEntered=true"))
        assertTrue(first.contains("lowVerifyAccepted=true"))
        assertTrue(first.contains("gatedProbeExecutions=0"))

        // Episode 2: 3 probes, 2 evaluations peaking at 0.5 (offset 1),
        // verifier rejected — nothing may bleed from episode 1.
        diag.onGatedProbeExecution()
        diag.onGatedProbeExecution()
        diag.onGatedProbeExecution()
        diag.onGateExited(200)
        diag.onStage3Evaluation(0.3f, 200)
        diag.onStage3Evaluation(0.5f, 201)
        diag.onLowVerify(accepted = false)
        val second = diag.onGateEntered(300)!!
        assertTrue(second.contains("stage3Evals=2"))
        assertTrue(second.contains("maxConfidence=0.5"))
        assertTrue(second.contains("maxConfidenceOffsetFrames=1"))
        assertTrue(second.contains("lowVerifyEntered=true"))
        assertTrue(second.contains("lowVerifyAccepted=false"))
        assertTrue(second.contains("gatedProbeExecutions=3"))

        // Episode 1's summary is an immutable snapshot — unchanged after episode 2.
        assertTrue(first.contains("stage3Evals=1"))
        assertTrue(first.contains("lowVerifyAccepted=true"))
    }

    @Test
    fun `probe counts do not bleed into the next gated interval`() {
        val diag = newDiag()
        diag.onGateEntered(0)
        diag.onGatedProbeExecution()
        diag.onGatedProbeExecution()
        diag.onGateExited(10)
        diag.onStage3Evaluation(0.5f, 11)
        val first = diag.onGateEntered(100)!!
        assertTrue(first.contains("gatedProbeExecutions=2"))

        // New gated interval without probes: next episode must report 0.
        diag.onGateExited(200)
        diag.onStage3Evaluation(0.6f, 201)
        val second = diag.onGateEntered(300)!!
        assertTrue(second.contains("gatedProbeExecutions=0"))
    }

    // ── Captured-audio energy (the #1432 level discriminator) ────────────────

    @Test
    fun `max window energy is captured from the frames around the maximum`() {
        val diag = newDiag()
        diag.onGateEntered(0)
        // 21 frames before/at the exit: ring = frames 6..21, episode not open.
        for (i in 1..21) diag.onFrameRms(i.toFloat())
        diag.onGateExited(21)
        // Post-exit frames: episode open; ring now 11..30 by the second eval.
        for (i in 22..30) diag.onFrameRms(i.toFloat())
        diag.onStage3Evaluation(0.2f, 25)
        diag.onStage3Evaluation(0.9f, 26)

        val s = diag.onGateEntered(100)!!
        // Window at eval 26 = last 16 of the 30 fed frames = 15..30 → peak 30, mean 22.5.
        assertTrue(s.contains("maxConfidence=0.9"))
        assertTrue(s.contains("maxWindowPeakRms=30.0"), s)
        assertTrue(s.contains("maxWindowMeanRms=22.5"), s)
        // Episode peak counts post-exit frames only (22..30).
        assertTrue(s.contains("episodePeakRms=30.0"), s)
    }

    @Test
    fun `episode peak rms excludes loud frames captured before the exit`() {
        val diag = newDiag()
        diag.onGateEntered(0)
        repeat(10) { diag.onFrameRms(100f) } // loud gated-interval frames
        diag.onGateExited(10)
        diag.onFrameRms(5f)
        diag.onStage3Evaluation(0.5f, 11)

        val s = diag.onGateEntered(100)!!
        assertTrue(s.contains("episodePeakRms=5.0"), s)
        // The max-confidence window's ring holds 11 frames (10×100 + 5) — the
        // classifier window's own audio (mean = 1005/11).
        assertTrue(s.contains("maxWindowPeakRms=100.0"), s)
        assertTrue(s.contains("maxWindowMeanRms=91.36364"), s)
    }

    @Test
    fun `energy fields reset between episodes`() {
        val diag = newDiag()
        diag.onGateEntered(0)
        diag.onGateExited(10)
        diag.onFrameRms(9f)
        diag.onStage3Evaluation(0.5f, 11)
        val first = diag.onGateEntered(100)!!
        assertTrue(first.contains("episodePeakRms=9.0"))

        // Second episode without evaluations: energy fields must be "none".
        diag.onGateExited(200)
        val second = diag.onGateEntered(300)!!
        assertTrue(second.contains("episodePeakRms=none"), second)
        assertTrue(second.contains("maxWindowPeakRms=none"), second)
        assertTrue(second.contains("maxWindowMeanRms=none"), second)
    }

    // ── Formatting contract ────────────────────────────────────────────────

    @Test
    fun `summary formatting is locale-independent and parseable`() {
        val s = buildGateExitSummary(
            generationId = 1L,
            stage3Evaluations = 42,
            maxConfidence = 0.6f,
            maxConfidenceOffsetFrames = 3,
            lowVerifyEntered = true,
            lowVerifyAccepted = true,
            gatedProbeExecutions = 5L,
            episodePeakRms = 7.25f,
            maxWindowPeakRms = 6.5f,
            maxWindowMeanRms = 4.125f,
        )

        assertTrue(s.startsWith("WakeWordDetector: gateExitSummary"))
        // Float.toString() is locale-independent; a comma decimal separator
        // would break logcat parsing.
        assertEquals("0.6", s.substringAfter("maxConfidence=").substringBefore(" maxConfidenceOffsetFrames="))
        assertEquals("7.25", s.substringAfter("episodePeakRms=").substringBefore(" maxWindowPeakRms="))
        assertEquals("6.5", s.substringAfter("maxWindowPeakRms=").substringBefore(" maxWindowMeanRms="))
        assertEquals("4.125", s.substringAfter("maxWindowMeanRms="))
    }

    @Test
    fun `summary reports none when no score was observed in the episode`() {
        val s = buildGateExitSummary(
            generationId = 8L,
            stage3Evaluations = 0,
            maxConfidence = -1f,
            maxConfidenceOffsetFrames = -1,
            lowVerifyEntered = false,
            lowVerifyAccepted = false,
            gatedProbeExecutions = 0L,
        )

        assertTrue(s.contains("stage3Evals=0"))
        assertTrue(s.contains("maxConfidence=none"))
        assertTrue(s.contains("maxConfidenceOffsetFrames=-1"))
        assertTrue(s.contains("lowVerifyEntered=false"))
        assertTrue(s.contains("gatedProbeExecutions=0"))
        assertTrue(s.contains("episodePeakRms=none"))
        assertTrue(s.contains("maxWindowPeakRms=none"))
        assertTrue(s.contains("maxWindowMeanRms=none"))
    }
}
