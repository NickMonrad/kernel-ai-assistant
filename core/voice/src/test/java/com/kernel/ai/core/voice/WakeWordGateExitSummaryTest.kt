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
    fun `classifier context rms tracks only embedding frames and includes the voiced exit frame`() {
        // Realistic gated sequence: startup embeddings, gated frames where
        // Stage 2 is skipped (loud, deliberately distinctive), gated probe
        // frames that do produce embeddings, a voiced gate-exit frame, and
        // fewer than 16 post-exit embeddings — the Stage 3 maximum is
        // recorded once the combined pre-exit probes + post-exit embeddings
        // form the complete 16-embedding classifier window.
        val diag = newDiag()

        // Startup frames (embeddings produced): RMS 1..4.
        for (i in 1..4) diag.onEmbeddingFrameRms(i.toFloat())
        diag.onGateEntered(4)

        // Gated frames, Stage 2 SKIPPED — loud (1000..1006) but must never
        // enter the classifier context.
        for (i in 0..6) diag.onEpisodeFrameRms((1000 + i).toFloat())
        // Gated probe (embedding produced): RMS 200.
        diag.onEmbeddingFrameRms(200f)
        // Gated frames, Stage 2 SKIPPED — loud again (1007..1008).
        diag.onEpisodeFrameRms(1007f)
        diag.onEpisodeFrameRms(1008f)
        // Gated probe (embedding produced): RMS 300.
        diag.onEmbeddingFrameRms(300f)

        // Voiced gate-exit frame: opens the episode AND produces an embedding.
        diag.onGateExited(16)
        diag.onEpisodeFrameRms(400f)
        diag.onEmbeddingFrameRms(400f)

        // Post-exit open-gate frames (embeddings produced), quieter than the
        // exit frame: RMS 50..58.
        for (i in 0..8) {
            diag.onEpisodeFrameRms((50 + i).toFloat())
            diag.onEmbeddingFrameRms((50 + i).toFloat())
        }

        // Classifier window now complete: 16 embedding-associated values
        // [1,2,3,4,200,300,400,50..58].  Skipped frames (1000..1008) absent.
        diag.onStage3Evaluation(0.6f, 26)
        val s = diag.onGateEntered(100)!!

        assertTrue(s.contains("maxConfidence=0.6"))
        // Peak = 400 (the voiced exit frame); mean = 1396/16 = 87.25 — the
        // exact 16 embedding-associated values, no skipped-gated audio.
        assertTrue(s.contains("maxWindowPeakRms=400.0"), s)
        assertTrue(s.contains("maxWindowMeanRms=87.25"), s)
        // Episode peak = 400: the voiced exit frame is included (post-exit
        // frames are quieter), gated pre-exit frames (1000+) are excluded.
        assertTrue(s.contains("episodePeakRms=400.0"), s)
    }

    @Test
    fun `incomplete classifier context reports no window energy`() {
        // Fail closed: a Stage 3 maximum with fewer than 16 embedding-
        // associated frames must report none, not average a partial ring.
        val diag = newDiag()
        diag.onGateEntered(0)
        diag.onGateExited(10)
        repeat(5) { diag.onEpisodeFrameRms((it + 1).toFloat()) }
        repeat(5) { diag.onEmbeddingFrameRms((it + 1).toFloat()) }
        diag.onStage3Evaluation(0.5f, 15)

        val s = diag.onGateEntered(100)!!
        assertTrue(s.contains("maxConfidence=0.5"))
        assertTrue(s.contains("maxWindowPeakRms=none"), s)
        assertTrue(s.contains("maxWindowMeanRms=none"), s)
        // Episode peak is independent and still reported.
        assertTrue(s.contains("episodePeakRms=5.0"), s)
    }

    @Test
    fun `episode peak includes the voiced exit frame and excludes gated frames`() {
        val diag = newDiag()
        diag.onGateEntered(0)
        // Loud gated-interval frames — episode not open, must be excluded.
        repeat(5) { diag.onEpisodeFrameRms(1000f) }
        diag.onGateExited(10)
        // The voiced exit frame itself — must be included.
        diag.onEpisodeFrameRms(500f)
        diag.onEmbeddingFrameRms(500f)
        // Quiet open-episode frames — included but below the exit peak.
        diag.onEpisodeFrameRms(10f)
        diag.onEpisodeFrameRms(20f)
        diag.onEmbeddingFrameRms(10f)
        diag.onEmbeddingFrameRms(20f)
        diag.onStage3Evaluation(0.5f, 13)

        val s = diag.onGateEntered(100)!!
        assertTrue(s.contains("episodePeakRms=500.0"), s)
        // Classifier context ring (3 values) is incomplete → fail closed.
        assertTrue(s.contains("maxWindowPeakRms=none"), s)
        assertTrue(s.contains("maxWindowMeanRms=none"), s)
    }

    @Test
    fun `energy fields reset between episodes while the context ring mirrors the production ring`() {
        val diag = newDiag()
        // Episode 1: 16 embedding frames RMS 1..16 (window complete).
        diag.onGateEntered(0)
        diag.onGateExited(10)
        for (i in 1..16) {
            diag.onEpisodeFrameRms(i.toFloat())
            diag.onEmbeddingFrameRms(i.toFloat())
        }
        diag.onStage3Evaluation(0.7f, 26)
        val first = diag.onGateEntered(100)!!
        assertTrue(first.contains("episodePeakRms=16.0"))
        assertTrue(first.contains("maxWindowPeakRms=16.0"))
        assertTrue(first.contains("maxWindowMeanRms=8.5"))

        // Episode 2: episode state must reset (peak = episode-2 frames only);
        // the classifier-context ring persists like WakeWordEmbeddingRingState
        // (the window genuinely contains pre-exit embeddings).
        diag.onGateExited(200)
        diag.onEpisodeFrameRms(9f)
        diag.onEpisodeFrameRms(8f)
        diag.onEpisodeFrameRms(7f)
        diag.onEmbeddingFrameRms(9f)
        diag.onEmbeddingFrameRms(8f)
        diag.onEmbeddingFrameRms(7f)
        diag.onStage3Evaluation(0.4f, 203)
        val second = diag.onGateEntered(300)!!
        assertTrue(second.contains("episodePeakRms=9.0"), second)
        // Ring = [4..16, 7, 8, 9]: peak 16, mean (130 + 24) / 16 = 9.625.
        assertTrue(second.contains("maxWindowPeakRms=16.0"), second)
        assertTrue(second.contains("maxWindowMeanRms=9.625"), second)
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
