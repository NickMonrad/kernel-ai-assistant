package com.kernel.ai.core.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.FloatBuffer

/**
 * Real-model Stage 3 classifier validation for #1432 (review finding 2).
 *
 * Executes the committed `hey_jandal.onnx` classifier (JVM ONNX Runtime, CPU)
 * on deterministic embedding windows generated through the real Stage 1/2
 * pipeline (openWakeWord `AudioFeatures` — the exact feature path used by
 * `training/train.py`; see `wake-embeddings/GENERATION.md` for provenance,
 * model hashes and stream semantics).
 *
 * Windows are constructed with the production [WakeWordEmbeddingRingState]
 * where the resume path is modelled (preserved sparse-probe history + wake
 * onset). Thresholds are the configured production defaults
 * ([WAKE_WORD_DEFAULT_THRESHOLD] = 0.65 high, [WAKE_WORD_DEFAULT_LOW_THRESHOLD]
 * = 0.50 low).
 *
 * Purpose: prove the preserved-ring resume path is false-positive safe with
 * the actual classifier and that it still produces the intended positive.
 * The synthetic timeline simulator in [WakeWordRingResumeTest] covers state
 * and ordering only; this class covers classifier behaviour.
 */
class WakeWordClassifierModelTest {

    private val highThreshold = WAKE_WORD_DEFAULT_THRESHOLD
    private val lowThreshold = WAKE_WORD_DEFAULT_LOW_THRESHOLD

    // ── Model + fixture loading ─────────────────────────────────────────

    private fun loadClassifier(): OrtSession {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/models/wakeword/hey_jandal.onnx")) {
            "committed classifier asset missing from test resources"
        }.use { it.readBytes() }
        val env = OrtEnvironment.getEnvironment()
        return env.createSession(bytes, OrtSession.SessionOptions())
    }

    private val session: OrtSession by lazy { loadClassifier() }
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val inputName: String by lazy { session.inputNames.first() }
    private val outputName: String by lazy { session.outputNames.first() }

    private fun loadStream(name: String): List<FloatArray> {
        val text = checkNotNull(javaClass.getResourceAsStream("/wake-embeddings/$name")) {
            "fixture stream $name missing from test resources"
        }.use { it.readBytes().toString(Charsets.UTF_8) }
        return parseFloatArrays(text)
    }

    /** Minimal parser for the generator's JSON format: [[0.1,...],[...],...]. */
    private fun parseFloatArrays(text: String): List<FloatArray> {
        val compact = text.filter { !it.isWhitespace() }
        val values = ArrayList<Float>()
        val rows = ArrayList<FloatArray>()
        var i = 0
        while (i < compact.length) {
            when (compact[i]) {
                '[' -> {
                    values.clear()
                    i++
                }
                ']' -> {
                    if (values.isNotEmpty()) rows.add(values.toFloatArray())
                    i++
                }
                ',' -> i++
                else -> {
                    val start = i
                    while (i < compact.length && compact[i] !in ",]") i++
                    values.add(compact.substring(start, i).toFloat())
                }
            }
        }
        return rows
    }

    // ── Classifier scoring ──────────────────────────────────────────────

    private fun score(window: FloatArray): Float {
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(window), longArrayOf(1L, 16L, 96L))
        return tensor.use { input ->
            session.run(mapOf(inputName to input)).use { result ->
                // JVM onnxruntime wraps outputs in Optional (Android build does not).
                val t = result.get(outputName).get() as OnnxTensor
                ((t.value as Array<*>)[0] as FloatArray)[0]
            }
        }
    }

    private fun score(ring: WakeWordEmbeddingRingState): Float {
        val window = FloatArray(16 * 96)
        ring.copyWindow(window)
        return score(window)
    }

    // ── Fixture streams (see GENERATION.md) ─────────────────────────────

    private val fixture: List<FloatArray> by lazy { loadStream("fixture_stream.json") }
    private val silence: List<FloatArray> by lazy { loadStream("silence_stream.json") }
    private val noiseWhite: List<FloatArray> by lazy { loadStream("noise_white_stream.json") }
    private val noisePink: List<FloatArray> by lazy { loadStream("noise_pink_stream.json") }
    private val noiseSpeech: List<FloatArray> by lazy { loadStream("noise_speech_stream.json") }
    private val formant: List<FloatArray> by lazy { loadStream("speech_formant_stream.json") }

    private fun flatten(vectors: List<FloatArray>): FloatArray {
        val out = FloatArray(vectors.size * 96)
        vectors.forEachIndexed { i, v -> v.copyInto(out, i * 96) }
        return out
    }

    // ── Real classifier cases ───────────────────────────────────────────

    @Test
    fun `real classifier reaches the activation band on consecutive positive windows`() {
        // Fixture phrase windows [2:18] and [4:20] (see GENERATION.md).
        assertTrue(score(flatten(fixture.subList(2, 18))) >= highThreshold, "fs[2:18] below high")
        assertTrue(score(flatten(fixture.subList(4, 20))) >= highThreshold, "fs[4:20] below high")
    }

    @Test
    fun `full phrase onset band reaches the activation band`() {
        // #1444 robustness regression: the retrained classifier fires across
        // the whole onset band [2:18]-[5:21] (the #1432 S21 captures land at
        // any of these alignments; the committed model's band was narrower).
        for (i in 2..5) {
            val w = flatten(fixture.subList(i, i + 16))
            assertTrue(score(w) >= highThreshold, "fs[$i:${i + 16}] below high (${score(w)})")
        }
    }

    @Test
    fun `real classifier keeps silence below the low threshold`() {
        assertTrue(score(flatten(silence.subList(0, 16))) < lowThreshold)
    }

    @Test
    fun `real classifier keeps non-speech noise below the low threshold`() {
        assertTrue(score(flatten(noiseWhite.subList(0, 16))) < lowThreshold, "white noise")
        assertTrue(score(flatten(noisePink.subList(0, 16))) < lowThreshold, "pink noise")
        assertTrue(score(flatten(noiseSpeech.subList(0, 16))) < lowThreshold, "speech-shaped noise")
    }

    @Test
    fun `real classifier keeps near-phrase formant speech below the low threshold`() {
        assertTrue(score(flatten(formant.subList(0, 16))) < lowThreshold)
    }

    @Test
    fun `preserved sparse-probe history still produces the intended positive`() {
        // Resume path: the ring holds 8 probe-spaced silence embeddings (the
        // same vector — probes over sustained silence compute the same
        // embedding), then the wake phrase arrives and is appended
        // consecutively.  Every complete window is scored with the production
        // ring; the phrase-tail windows must reach the high band.
        val ring = WakeWordEmbeddingRingState()
        val scores = ArrayList<Float>()
        repeat(8) { ring.append(silence[0]) }
        for (frame in 0 until 24) {
            ring.append(fixture[frame])
            if (ring.isWindowComplete) scores.add(score(ring))
        }
        // Probe-only windows must stay below low.
        assertTrue(scores.first() < lowThreshold, "probe-only window crossed low")
        // The phrase windows must reach the activation band.
        val peak = scores.max()
        assertTrue(peak >= highThreshold, "preserved-history peak $peak below high")
    }

    @Test
    fun `preserved sparse-probe history followed by non-wake voiced audio stays below low`() {
        // Same resume path but the voiced audio is white noise: no window may
        // produce an activation candidate.
        val ring = WakeWordEmbeddingRingState()
        repeat(8) { ring.append(silence[0]) }
        for (frame in 0 until 24) {
            ring.append(noiseWhite[frame])
            if (ring.isWindowComplete) {
                assertTrue(score(ring) < lowThreshold, "hard-negative window crossed low")
            }
        }
    }

    @Test
    fun `immediate post-exit window with mostly probes is not a false positive`() {
        // First evaluation after gate exit: 15 probe-spaced silence
        // embeddings + the first voiced frame.
        val ring = WakeWordEmbeddingRingState()
        repeat(15) { ring.append(silence[0]) }
        ring.append(fixture[2])
        assertTrue(ring.isWindowComplete)
        assertTrue(score(ring) < lowThreshold)
    }

    @Test
    fun `old full-reset path misses at its first post-refill evaluation`() {
        // Pre-#1432 behaviour: the ring was flushed at the phrase onset, so
        // the first evaluation saw the first 16 post-onset embeddings.  The
        // real classifier scores this window below the low threshold — the
        // miss #1432 fixes (the phrase is never evaluated at the
        // tail-aligned position that produces the positive).
        val firstEval = flatten(fixture.subList(1, 17))
        assertTrue(score(firstEval) < lowThreshold, "old-reset first evaluation scored ${score(firstEval)}")
    }

    @Test
    fun `incomplete ring remains unscoreable`() {
        val ring = WakeWordEmbeddingRingState()
        repeat(15) { ring.append(silence[0]) }
        assertTrue(!ring.isWindowComplete)
        // The production loop guard skips scoring; only the state is asserted
        // here (scoring requires a complete window by construction).
        ring.append(silence[0])
        assertTrue(ring.isWindowComplete)
    }

    @Test
    fun `repeated real positive runs are stable`() {
        val window = flatten(fixture.subList(4, 20))
        val first = score(window)
        assertTrue(first >= highThreshold)
        repeat(10) { assertEquals(first, score(window)) }
    }

    @Test
    fun `every window of every negative stream stays at the classifier floor`() {
        // Full sweep over all 16-frame windows (the existing negative tests
        // only check window [0:16]).  Measured max across all negative streams
        // is ~0.001 (pink-noise window 33); the ceiling below pins the
        // no-phrase-content response of the real classifier: an exit episode
        // whose maximum confidence is ~0.001 (the #1432 physical
        // no-candidate trials, e.g. 0.001 on S23U at b9360af) never contained
        // any window with audible phrase content.
        val negatives = listOf(
            "silence" to silence,
            "white" to noiseWhite,
            "pink" to noisePink,
            "speech-noise" to noiseSpeech,
            "formant" to formant,
        )
        for ((name, stream) in negatives) {
            for (i in 0..(stream.size - 16)) {
                val s = score(flatten(stream.subList(i, i + 16)))
                assertTrue(s < 0.002f, "$name window [$i:${i + 16}] scored $s")
            }
        }
    }

    @Test
    fun `trailing silence windows of the fixture stay at the classifier floor`() {
        // Frames 6+ of the fixture stream are appended digital silence
        // (GENERATION.md).  The windows starting at 7+ contain no phrase
        // content and must stay at the floor; [6:22] is the phase-collapse
        // boundary window (see below) and is excluded here.
        for (i in 7..(fixture.size - 16)) {
            val s = score(flatten(fixture.subList(i, i + 16)))
            assertTrue(s < 0.002f, "fixture trailing window [$i:${i + 16}] scored $s")
        }
    }

    @Test
    fun `worst non-collapsed phrase alignment still clears the floor by two orders`() {
        // Window [1:17] places the phrase one frame off its optimal band and
        // is the lowest-scoring phrase-containing window that is still inside
        // the recognisable band (measured 0.2106).  It clears the no-phrase
        // ceiling (~0.001) by ~200x: a captured phrase at any audible level
        // necessarily produces at least one window above the floor.  The
        // physical no-candidate max of 0.001 therefore cannot come from any
        // window containing the phrase.
        assertTrue(score(flatten(fixture.subList(1, 17))) >= 0.2f)
    }

    @Test
    fun `phrase windows at collapsed alignments fall back to the floor`() {
        // The classifier only fires in a narrow alignment band: shifting the
        // window one frame beyond the band collapses the score to the floor
        // ([0:16] and [6:22] contain phrase-edge content at the wrong
        // position; measured 0.0016 / 0.0033).  In production the detector
        // slides the window 80 ms per frame, so a present phrase always
        // traverses its optimal band within a few frames (the #1438
        // production-feed parity: >= 0.78 at all 32 phase positions); these
        // collapsed windows are the training-feed analogue of a phrase that
        // is absent or far below the training level.
        assertTrue(score(flatten(fixture.subList(0, 16))) < 0.01f)
        assertTrue(score(flatten(fixture.subList(6, 22))) < 0.01f)
    }

    @Test
    fun `committed classifier asset is the pinned model`() {
        // Guards against drift of the test-resource copy vs the deployed
        // asset (app/src/main/assets/models/wakeword/hey_jandal.onnx).
        val bytes = checkNotNull(javaClass.getResourceAsStream("/models/wakeword/hey_jandal.onnx"))
            .use { it.readBytes() }
        val sha = java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(
            "3a920e291662d4b58e10432b5c7f686f00073c45972763d55552200b97f9c4a8",
            sha,
        )
    }
}
