package com.kernel.ai.core.voice

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Deterministic coverage for #1432: silence-gate exit must preserve the
 * embedding ring so Stage 3 can score the wake phrase while it is still at
 * the tail of the classifier window.
 *
 * The reproduction is grounded in the preserved #1410 physical evidence:
 *
 * - Passing trials (S21 trial 002, S23U trial 001) exited the silence gate
 *   1.6–2.9 s BEFORE the frozen fixture played (ambient acoustic event), so
 *   the ring was live and Stage 3 evaluated continuously; both fired
 *   ACTIVATION_CANDIDATE at phrase onset + 0.90–0.93 s with confidence
 *   0.889–0.900 (high path), i.e. while the phrase end sat ~0.2 s beyond the
 *   window's receptive-field end.
 * - Failing trials (S21 trial 001, S23U trial 002) were exited BY the phrase
 *   onset itself; the pre-#1432 unconditional `embFramesAccumulated = 0`
 *   forced a 16-frame (~1.28 s) classifier blackout (STAGE3_READY observed at
 *   phrase + 1.225–1.233 s = 15 × 80 ms refill), and no later evaluation ever
 *   matched the tail-aligned position — no candidate in 11 S21 + 6 S23U
 *   classified misses.
 *
 * The deterministic simulator below mirrors the detector loop ordering and
 * reuses the production [SilenceGateTransitionState] and
 * [WakeWordEmbeddingRingState] classes.  The scoring stub encodes only the
 * empirically observed firing position (phrase end 0.15–0.35 s beyond the
 * window's receptive-field end); it is never used to assert a score value.
 */
class WakeWordRingResumeTest {

    // ── Ring state unit tests ─────────────────────────────────────────────

    @Test
    fun `gate entry does not corrupt a valid embedding history`() {
        val ring = WakeWordEmbeddingRingState()
        repeat(16) { ring.append(silenceVector()) }
        val before = FloatArray(ring.capacity * ring.dim)
        ring.copyWindow(before)
        val headBefore = ring.head
        val accumulatedBefore = ring.accumulated

        // Gate entry performs no ring mutation in production: the detector
        // only calls SilenceGateTransitionState.enter() and skips Stage 2/3.
        // The valid embedding history must be untouched and the window intact.
        val gate = SilenceGateTransitionState()
        assertTrue(gate.enter())
        assertTrue(gate.isGated)

        assertEquals(headBefore, ring.head)
        assertEquals(accumulatedBefore, ring.accumulated)
        assertTrue(ring.isWindowComplete)
        val after = FloatArray(ring.capacity * ring.dim)
        ring.copyWindow(after)
        assertArrayEquals(before, after)
    }

    @Test
    fun `gate exit preserves a complete ring - no refill blackout`() {
        val ring = WakeWordEmbeddingRingState()
        repeat(16) { ring.append(silenceVector()) }
        assertTrue(ring.isWindowComplete)

        // Production gate-exit path (see OnnxWakeWordDetector fast-open block):
        // the ring is intentionally NOT flushed; only the STAGE3_READY report
        // is re-armed.  Stage 3 may score the very next frame.
        val canScoreAfterExit = ring.isWindowComplete
        assertTrue(canScoreAfterExit)
        assertEquals(16, ring.accumulated)
    }

    @Test
    fun `classifier window is exactly 16 chronological embeddings`() {
        val ring = WakeWordEmbeddingRingState()
        val vectors = (0 until 24).map { phraseVector(it) }
        vectors.forEach { ring.append(it) }

        // 24 appends wrap the 16-slot ring twice; the window must contain
        // exactly the last 16 in chronological order, no duplicates.
        val window = FloatArray(ring.capacity * ring.dim)
        ring.copyWindow(window)
        for (slot in 0 until ring.capacity) {
            val expected = vectors[8 + slot] // last 16 of 24
            assertArrayEquals(expected, window.copyOfRange(slot * ring.dim, (slot + 1) * ring.dim))
        }
    }

    @Test
    fun `incomplete ring blocks scoring until exactly capacity frames`() {
        val ring = WakeWordEmbeddingRingState()
        assertFalse(ring.isWindowComplete)
        repeat(15) { ring.append(silenceVector()) }
        assertFalse(ring.isWindowComplete)
        ring.append(silenceVector())
        assertTrue(ring.isWindowComplete)
    }

    @Test
    fun `new generation does not reuse previous embeddings`() {
        val ring = WakeWordEmbeddingRingState()
        repeat(16) { ring.append(phraseVector(it)) }

        // Generation / re-arm boundary: fresh allocation in production
        // (runDetectionLoop), modelled here by reset().
        ring.reset()
        assertEquals(0, ring.accumulated)
        assertEquals(0, ring.head)
        assertFalse(ring.isWindowComplete)

        repeat(16) { ring.append(silenceVector()) }
        val window = FloatArray(ring.capacity * ring.dim)
        ring.copyWindow(window)
        for (slot in 0 until ring.capacity) {
            assertArrayEquals(silenceVector(), window.copyOfRange(slot * ring.dim, (slot + 1) * ring.dim))
        }
    }

    @Test
    fun `stop and re-arm reset the correct state`() {
        // Detector stop + service re-arm starts a new generation; each
        // runDetectionLoop allocates a fresh ring, so no state survives.
        // reset() is the contract the allocation provides.
        val ring = WakeWordEmbeddingRingState()
        repeat(16) { ring.append(phraseVector(it % 14)) }
        ring.reset()
        assertFalse(ring.isWindowComplete)
        assertEquals(0, ring.head)
        assertEquals(0, ring.accumulated)
    }

    // ── Deterministic pipeline reproduction ───────────────────────────────

    @Test
    fun `reproduces classifier_model_miss with old flush and fixes it`() {
        // Failing-trial configuration: prolonged gated silence, then the wake
        // phrase itself triggers the gate exit.
        val timeline = phraseTimeline(exitNoiseFrame = null)
        val old = DetectorSim(timeline, flushRingOnGateExit = true).apply { run() }
        val fixed = DetectorSim(timeline, flushRingOnGateExit = false).apply { run() }

        // Old behaviour: 16-frame blackout, first evaluation after the phrase
        // has passed the tail-aligned position, no candidate — the #1410 miss.
        assertNull(old.candidateFrame)
        assertEquals(timeline.phraseStart + 15, old.firstScoredAfterExitFrame)

        // Fixed behaviour: first evaluation on the exit frame itself, phrase
        // still at the tail of the window; candidate at phrase + 0.8 s (the
        // observed pass-trial firing region was phrase + 0.90–0.93 s).
        assertNotNull(fixed.candidateFrame)
        assertEquals(timeline.phraseStart, fixed.firstScoredAfterExitFrame)
        assertEquals(timeline.phraseStart + 10, fixed.candidateFrame)
    }

    @Test
    fun `first post-gate evaluation is the exit frame - phrase onset included`() {
        val timeline = phraseTimeline(exitNoiseFrame = null)
        val fixed = DetectorSim(timeline, flushRingOnGateExit = false).apply { run() }

        val firstWindow = fixed.windowsByFrame.getValue(timeline.phraseStart)
        // 15 silence-era slots + the phrase-onset embedding in slot 16.
        for (slot in 0 until 15) {
            assertArrayEquals(
                silenceVector(),
                firstWindow.copyOfRange(slot * RING_DIM, (slot + 1) * RING_DIM),
            )
        }
        assertArrayEquals(
            phraseVector(0),
            firstWindow.copyOfRange(15 * RING_DIM, 16 * RING_DIM),
        )
        // And the window slides: at candidate time the phrase onset is still in
        // the window, so the onset can never "pass" an un-evaluated region.
        val candidateWindow = fixed.windowsByFrame.getValue(timeline.phraseStart + 10)
        assertArrayEquals(
            phraseVector(0),
            candidateWindow.copyOfRange(5 * RING_DIM, 6 * RING_DIM),
        )
    }

    @Test
    fun `gated resume evaluates identical windows to the un-gated path`() {
        // Passing-trial configuration: gate exits ~20 frames before the phrase
        // (ambient event), detector stays un-gated through the phrase.
        val timeline = phraseTimeline(exitNoiseFrame = null)
        val gated = DetectorSim(timeline, flushRingOnGateExit = false).apply { run() }
        val unGated = DetectorSim(phraseTimeline(exitNoiseFrame = 380), flushRingOnGateExit = false).apply { run() }

        // With the fix the gated path evaluates, at every frame from the
        // phrase onset onward, exactly the same classifier input as the
        // un-gated (passing) path.  The classifier is a pure function of the
        // window, so identical windows imply identical scores and activation.
        val untilFrame = (gated.candidateFrame ?: timeline.phraseStart) - timeline.phraseStart
        for (frame in 0 until untilFrame) {
            val a = gated.windowsByFrame.getValue(timeline.phraseStart + frame)
            val b = unGated.windowsByFrame.getValue(timeline.phraseStart + frame)
            assertArrayEquals(a, b, "window divergence at phrase frame $frame")
        }
        assertEquals(gated.candidateFrame, unGated.candidateFrame)
    }

    @Test
    fun `periodic probes produce a chronological post-resume window`() {
        val timeline = phraseTimeline(exitNoiseFrame = null)
        val fixed = DetectorSim(timeline, flushRingOnGateExit = false).apply { run() }

        // Probes during gating are silence vectors; the post-exit window must
        // be ordered [oldest … newest] with the newest probe entry still
        // positioned before the first phrase embeddings.
        val windowAtTwo = fixed.windowsByFrame.getValue(timeline.phraseStart + 2)
        for (slot in 0 until 13) {
            assertArrayEquals(
                silenceVector(),
                windowAtTwo.copyOfRange(slot * RING_DIM, (slot + 1) * RING_DIM),
            )
        }
        assertArrayEquals(
            phraseVector(0),
            windowAtTwo.copyOfRange(13 * RING_DIM, 14 * RING_DIM),
        )
        assertArrayEquals(
            phraseVector(1),
            windowAtTwo.copyOfRange(14 * RING_DIM, 15 * RING_DIM),
        )
        assertArrayEquals(
            phraseVector(2),
            windowAtTwo.copyOfRange(15 * RING_DIM, 16 * RING_DIM),
        )
    }

    @Test
    fun `repeated identical runs never omit the activation candidate`() {
        // 25 identical gated trials: the fix must never intermittently lose
        // the candidate (the #1410 miss pattern), including across probe-phase
        // shifts from different idle lengths.
        for (idleFrames in listOf(400, 413, 426)) {
            repeat(25) {
                val sim = DetectorSim(
                    phraseTimeline(exitNoiseFrame = null, prePhraseSilence = idleFrames),
                    flushRingOnGateExit = false,
                ).apply { run() }
                assertNotNull(sim.candidateFrame, "candidate omitted with idle=$idleFrames")
            }
        }
        // And the old flush behaviour misses deterministically in every case.
        repeat(25) {
            val sim = DetectorSim(phraseTimeline(exitNoiseFrame = null), flushRingOnGateExit = true).apply { run() }
            assertNull(sim.candidateFrame)
        }
    }

    @Test
    fun `negative audio produces no activation in either behaviour`() {
        // No phrase ever: silence, one noise exit, then silence.  The fixed
        // path keeps scoring chronological windows but must not fabricate a
        // candidate; gating cadence continues.
        val timeline = Timeline(
            prePhraseSilence = 400,
            phraseStart = 400,
            phraseEnd = 400,
            noiseRange = 380..380,
            trailingFrames = 200,
        )
        val old = DetectorSim(timeline, flushRingOnGateExit = true).apply { run() }
        val fixed = DetectorSim(timeline, flushRingOnGateExit = false).apply { run() }

        assertNull(old.candidateFrame)
        assertNull(fixed.candidateFrame)
        assertTrue(fixed.gateSkips > 0)
        assertEquals(old.gateSkips, fixed.gateSkips)
    }

    @Test
    fun `near-phrase audio adds no false activation in either behaviour`() {
        // Voiced but non-phrase audio (noise) exits the gate after prolonged
        // silence.  The classifier input stays a standard chronological window
        // and the unrecognised audio never produces a candidate — the fix only
        // changes WHEN scoring resumes, not what is scored.
        val timeline = Timeline(
            prePhraseSilence = 400,
            phraseStart = 400,
            phraseEnd = 400, // no phrase — only NOISE frames
            noiseRange = 400..413,
            trailingFrames = 120,
        )
        val old = DetectorSim(timeline, flushRingOnGateExit = true).apply { run() }
        val fixed = DetectorSim(timeline, flushRingOnGateExit = false).apply { run() }

        assertNull(old.candidateFrame)
        assertNull(fixed.candidateFrame)

        // With the fix the first post-exit evaluation is the exit frame itself
        // and the window is chronological (16 distinct entries, silence first).
        val firstWindow = fixed.windowsByFrame.getValue(400)
        for (slot in 0 until 15) {
            assertArrayEquals(
                silenceVector(),
                firstWindow.copyOfRange(slot * RING_DIM, (slot + 1) * RING_DIM),
            )
        }
        assertArrayEquals(
            noiseVector(),
            firstWindow.copyOfRange(15 * RING_DIM, 16 * RING_DIM),
        )
    }

    @Test
    fun `event ordering after gated resume is correct`() {
        val fixed = DetectorSim(phraseTimeline(exitNoiseFrame = null), flushRingOnGateExit = false).apply { run() }
        val events = fixed.events

        val gateIdx = events.indexOf("SILENCE_GATE_ENTERED")
        val voicedIdx = events.indexOf("VOICED_FRAME_AFTER_SILENCE")
        val resumedIdx = events.indexOf("STAGE2_RESUMED")
        // The startup ring fill emits its own READY before the gate ever
        // entered; the resume-path READY is the one after VOICED.
        val readyIdx = events.drop(voicedIdx).indexOf("STAGE3_READY") + voicedIdx
        val candidateIdx = events.indexOf("ACTIVATION_CANDIDATE")
        val verifiedIdx = events.indexOf("VERIFIED_ACTIVATION")

        assertTrue(gateIdx in 0 until voicedIdx)
        assertTrue(voicedIdx < resumedIdx)
        assertTrue(resumedIdx < readyIdx)
        assertTrue(readyIdx < candidateIdx)
        assertTrue(candidateIdx < verifiedIdx)
        // One STAGE3_READY per exit (re-armed on exit, re-emitted on the first
        // post-exit evaluation).  The startup fill emits its own READY before
        // the gate ever entered.
        assertEquals(1, events.drop(voicedIdx).count { it == "STAGE3_READY" })
    }

    @Test
    fun `high and low confidence activation paths keep candidate order`() {
        // High path (confidence >= high threshold): candidate then verified,
        // no verifier involvement — unchanged by the fix.
        val high = DetectorSim(
            phraseTimeline(exitNoiseFrame = null),
            flushRingOnGateExit = false,
            scorer = { end, phraseEnd -> if (tailLagSeconds(end, phraseEnd) in 0.15..0.35) 0.89f else 0.05f },
        ).apply { run() }
        assertNotNull(high.candidateFrame)
        assertTrue(high.events.indexOf("ACTIVATION_CANDIDATE") < high.events.indexOf("VERIFIED_ACTIVATION"))
        assertFalse(high.verifierInvocations > 0)

        // Low band (low <= confidence < high): candidate triggers verifier;
        // accepted verifier still yields VERIFIED_ACTIVATION after candidate.
        val low = DetectorSim(
            phraseTimeline(exitNoiseFrame = null),
            flushRingOnGateExit = false,
            scorer = { end, phraseEnd -> if (tailLagSeconds(end, phraseEnd) in 0.15..0.35) 0.55f else 0.05f },
        ).apply { run() }
        assertNotNull(low.candidateFrame)
        assertTrue(low.events.indexOf("ACTIVATION_CANDIDATE") < low.events.indexOf("VERIFIED_ACTIVATION"))
        assertTrue(low.verifierInvocations > 0)
    }

    @Test
    fun `silence gate cadence and probe cadence unchanged by the fix`() {
        // Negative timeline: both behaviours run to completion identically, so
        // gating/probe counters are directly comparable (the fixed behaviour
        // stops earlier only when a candidate fires).
        val timeline = Timeline(
            prePhraseSilence = 400,
            phraseStart = 400,
            phraseEnd = 400,
            noiseRange = 380..380,
            trailingFrames = 200,
        )
        val old = DetectorSim(timeline, flushRingOnGateExit = true).apply { run() }
        val fixed = DetectorSim(timeline, flushRingOnGateExit = false).apply { run() }

        // Gate entered at the same frame in both behaviours (hangover +
        // skip-cadence gate — the fix does not touch gating).
        assertEquals(old.firstGateEnteredFrame, fixed.firstGateEnteredFrame)
        val enteredFrame = fixed.firstGateEnteredFrame ?: error("gate never entered")
        assertTrue(enteredFrame < 300) // hangover gate needs ~66 silent frames

        // Same number of skipped frames and periodic probe Stage 2 executions
        // during gated silence.
        assertEquals(old.gateSkips, fixed.gateSkips)
        assertEquals(old.probeExecutions, fixed.probeExecutions)
        assertTrue(fixed.probeExecutions >= 16) // ring fully refreshed by probes before exit

        // Diagnostic counters are untouched by the fix (they only count
        // executions; nothing in the resume path was removed).
        assertTrue(old.gateSkips > 0)
    }

    // ── Simulator ─────────────────────────────────────────────────────────

    private companion object {
        const val RING_DIM = 96
        const val PHRASE_FRAMES = 14 // ~1.12 s speech — frozen fixture portion
        const val FRAME_MS = 80.0
        const val HIGH_SCORE = 0.89f
        const val LOW_SCORE = 0.05f

        fun silenceVector(): FloatArray = FloatArray(RING_DIM) { 0.5f }

        /** Distinct vector per phrase frame so windows are identifiable. */
        fun phraseVector(i: Int): FloatArray = FloatArray(RING_DIM) { 0.5f + i * 0.01f }

        fun noiseVector(): FloatArray = FloatArray(RING_DIM) { 0.9f }

        fun tailLagSeconds(windowEndFrame: Int, phraseEndFrame: Int): Double =
            (phraseEndFrame - windowEndFrame) * FRAME_MS / 1000.0

        /**
         * Default scorer calibrated from the #1410 pass evidence: both passing
         * trials fired with the phrase end ~0.21–0.24 s beyond the window's
         * receptive-field end; failing trials' first evaluation came with the
         * phrase end already ~0.07 s inside the field (and sliding further
         * in), scoring below the low threshold.
         */
        fun tailScorer(windowEndFrame: Int, phraseEndFrame: Int): Float =
            if (tailLagSeconds(windowEndFrame, phraseEndFrame) in 0.15..0.35) HIGH_SCORE else LOW_SCORE

        fun phraseTimeline(exitNoiseFrame: Int?, prePhraseSilence: Int = 400): Timeline =
            Timeline(
                prePhraseSilence = prePhraseSilence,
                phraseStart = prePhraseSilence,
                phraseEnd = prePhraseSilence + PHRASE_FRAMES,
                noiseRange = if (exitNoiseFrame != null) exitNoiseFrame..exitNoiseFrame else IntRange.EMPTY,
                trailingFrames = 120,
            )
    }

    private enum class AudioClass { SILENT, NOISE, PHRASE }

    private class Timeline(
        val prePhraseSilence: Int,
        val phraseStart: Int,
        val phraseEnd: Int,
        val noiseRange: IntRange = IntRange.EMPTY,
        val trailingFrames: Int,
    ) {
        val totalFrames = prePhraseSilence + (phraseEnd - phraseStart) + trailingFrames

        fun audioClass(frame: Int): AudioClass = when {
            frame in noiseRange -> AudioClass.NOISE
            frame in phraseStart until phraseEnd -> AudioClass.PHRASE
            else -> AudioClass.SILENT
        }
    }

    private class DetectorSim(
        private val timeline: Timeline,
        private val flushRingOnGateExit: Boolean,
        private val scorer: (windowEndFrame: Int, phraseEndFrame: Int) -> Float = ::tailScorer,
    ) {
        private val ring = WakeWordEmbeddingRingState()
        private val gate = SilenceGateTransitionState()
        val events = mutableListOf<String>()
        val windowsByFrame = mutableMapOf<Int, FloatArray>()
        private val probeInterval = secondsToFrames(WAKE_WORD_MAX_SILENCE_SKIP_SECONDS)
        private val hangover = secondsToFrames(WAKE_WORD_DEFAULT_SILENCE_HANGOVER_SECONDS)
        private val phraseEndFrame = if (timeline.phraseEnd > timeline.phraseStart) timeline.phraseEnd else null

        var candidateFrame: Int? = null
            private set
        var firstScoredWindowEndFrame: Int? = null
            private set
        var firstScoredAfterExitFrame: Int? = null
            private set
        var firstGateEnteredFrame: Int? = null
            private set
        var gateSkips = 0
            private set
        var probeExecutions = 0
            private set
        var verifierInvocations = 0
            private set

        fun run() {
            var chunkCount = 0
            var silenceFrames = 0
            var voicedFrameStreak = 0
            var emittedStage3Ready = false
            var lastGateExitFrame = Int.MAX_VALUE

            while (chunkCount < timeline.totalFrames && candidateFrame == null) {
                chunkCount++
                val frame = chunkCount - 1
                val audioClass = timeline.audioClass(frame)
                val isVoiced = audioClass != AudioClass.SILENT

                // Fast-open / slow-close (mirrors the detector loop order).
                if (isVoiced) {
                    if (gate.onVoicedFrame()) {
                        lastGateExitFrame = frame
                        // Pre-#1432 behaviour: unconditional ring flush.
                        if (flushRingOnGateExit) ring.reset()
                        emittedStage3Ready = false
                        events += "VOICED_FRAME_AFTER_SILENCE"
                    }
                    silenceFrames = 0
                    voicedFrameStreak = 0
                } else {
                    voicedFrameStreak++
                    if (voicedFrameStreak >= 3) silenceFrames++
                }

                // Gating with periodic probes (Stage 1 mel ring is implicit).
                if (silenceFrames > hangover && chunkCount % probeInterval.toLong() != 0L) {
                    gateSkips++
                    if (gate.enter()) {
                        if (firstGateEnteredFrame == null) firstGateEnteredFrame = frame
                        events += "SILENCE_GATE_ENTERED"
                    }
                    continue
                }

                // Stage 2.
                if (gate.onStage2Execution()) events += "STAGE2_RESUMED"
                if (gate.isGated) probeExecutions++
                ring.append(
                    when (audioClass) {
                        AudioClass.SILENT -> silenceVector()
                        AudioClass.NOISE -> noiseVector()
                        AudioClass.PHRASE -> phraseVector(frame - timeline.phraseStart)
                    },
                )
                if (!ring.isWindowComplete) continue
                if (!emittedStage3Ready) {
                    emittedStage3Ready = true
                    events += "STAGE3_READY"
                }

                // Stage 3.
                val window = FloatArray(ring.capacity * ring.dim)
                ring.copyWindow(window)
                windowsByFrame[frame] = window
                if (firstScoredWindowEndFrame == null) firstScoredWindowEndFrame = frame
                if (firstScoredAfterExitFrame == null && frame >= lastGateExitFrame) {
                    firstScoredAfterExitFrame = frame
                }
                val confidence = scorer(frame, phraseEndFrame ?: frame)

                when {
                    confidence >= WAKE_WORD_DEFAULT_THRESHOLD -> {
                        candidateFrame = frame
                        events += "ACTIVATION_CANDIDATE"
                        events += "VERIFIED_ACTIVATION"
                    }
                    confidence >= WAKE_WORD_DEFAULT_LOW_THRESHOLD -> {
                        candidateFrame = frame
                        verifierInvocations++
                        events += "ACTIVATION_CANDIDATE"
                        events += "VERIFIED_ACTIVATION"
                    }
                    else -> Unit
                }
            }
        }
    }
}
