package com.kernel.ai.core.voice

import android.content.Context
import android.media.AudioManager
import android.media.AudioRecord
import com.kernel.ai.core.voice.asr4all.Asr4allEngine
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Deterministic controller tests for the asr4all capture lifecycle. The recorder returns frames
 * at [amplitude] until stopped; the engine is a relaxed mock whose calls are appended to [log].
 * Every wait is a latch or semaphore released by the code under test; there are no sleeps.
 */
class Asr4allVoiceInputControllerTest {

    private val log = CopyOnWriteArrayList<String>()
    private val engines = CopyOnWriteArrayList<Asr4allEngine>()
    private val micOpens = java.util.concurrent.atomic.AtomicInteger(0)
    private val micReleases = java.util.concurrent.atomic.AtomicInteger(0)
    private val focusRequests = java.util.concurrent.atomic.AtomicInteger(0)
    private val focusAbandons = java.util.concurrent.atomic.AtomicInteger(0)
    private val micReleasedSignal = Semaphore(0)
    private val focusAbandonedSignal = Semaphore(0)
    @Volatile private var firstFrame = CountDownLatch(1)
    @Volatile private var openFails = false
    @Volatile private var amplitude: Short = 8000
    @Volatile private var renderGate: CountDownLatch? = null
    private val renderStarted = CountDownLatch(1)
    /** Decoder advances on every Nth decodeStream call (1 = every chunk). */
    @Volatile private var advanceEvery = 1
    /** Committed blank frames reported once the decoder has advanced this far (Int.MAX = never). */
    @Volatile private var blankAfterFrames = Int.MAX_VALUE
    /** Set to block engine construction until the test releases it. */
    @Volatile private var engineGate: CountDownLatch? = null
    private val engineEntered = CountDownLatch(1)

    private fun controller(): Asr4allVoiceInputController {
        var opened = 0
        val context = mockk<Context>(relaxed = true)
        every { context.getSystemService(AudioManager::class.java) } returns mockk<AudioManager>(relaxed = true) {
            every { requestAudioFocus(any<android.media.AudioFocusRequest>()) } answers { focusRequests.incrementAndGet(); 1 }
            every { abandonAudioFocusRequest(any()) } answers { focusAbandons.incrementAndGet(); focusAbandonedSignal.release(); 1 }
            // Unit tests report SDK_INT below O, so the controller takes the legacy overloads.
            every { requestAudioFocus(any<AudioManager.OnAudioFocusChangeListener>(), any(), any()) } answers { focusRequests.incrementAndGet(); 1 }
            every { abandonAudioFocus(any()) } answers { focusAbandons.incrementAndGet(); focusAbandonedSignal.release(); 1 }
        }
        return Asr4allVoiceInputController(context).apply {
            availableForTest = { true }
            micGrantedForTest = true
            recorderForTest = { recorder() }
            engineForTest = {
                engineEntered.countDown()
                engineGate?.await(5, TimeUnit.SECONDS)
                if (openFails) error("bad model file")
                opened++
                val index = opened
                newEngine(index).also { engines += it; log += "open#$index" }
            }
            releaseFencedForTest = null
        }
    }

    private fun recorder(): AudioRecord {
        micOpens.incrementAndGet()
        return mockk(relaxed = true) {
            every { state } returns AudioRecord.STATE_INITIALIZED
            every { release() } answers { micReleases.incrementAndGet(); micReleasedSignal.release() }
            every { read(any<ShortArray>(), any(), any()) } answers {
                val buf = firstArg<ShortArray>()
                buf.fill(amplitude)
                firstFrame.countDown()
                buf.size
            }
        }
    }

    private fun newEngine(index: Int): Asr4allEngine = mockk<Asr4allEngine>(relaxed = true).also { e ->
        var calls = 0
        var frames = 0
        every { e.decodeStream(any()) } answers {
            calls++
            if (calls % advanceEvery == 0) frames++
            "partial"
        }
        every { e.decodedFrameCount } answers { frames }
        every { e.trailingBlankFrames } answers { if (frames >= blankAfterFrames) ENDPOINT_FRAMES else 0 }
        every { e.decodePcec(any()) } answers {
            renderGate?.let { renderStarted.countDown(); it.await(5, TimeUnit.SECONDS) }
            "Final text."
        }
        every { e.reset() } answers { log += "reset#$index" }
        every { e.finish() } answers { log += "finish#$index"; "" }
        every { e.close() } answers { log += "close#$index" }
    }

    /** Collects events into a snapshot-able list and signals each ListeningStopped. */
    private class Collected(controllerEvents: Flow<VoiceInputEvent>, scope: CoroutineScope) {
        val events = CopyOnWriteArrayList<VoiceInputEvent>()
        val stopped = Semaphore(0)
        // UNDISPATCHED: subscribe before any start, because the event flow has no replay.
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            controllerEvents.collect {
                events += it
                if (it is VoiceInputEvent.ListeningStopped) stopped.release()
            }
        }

        fun transcripts(): List<String> = events.filterIsInstance<VoiceInputEvent.Transcript>().map { it.text }
        fun started(): Int = events.count { it is VoiceInputEvent.ListeningStarted }
        fun stoppedCount(): Int = events.count { it is VoiceInputEvent.ListeningStopped }

        fun awaitStops(count: Int) {
            assertTrue(stopped.tryAcquire(count, 5, TimeUnit.SECONDS), "capture did not stop")
        }
    }

    private fun startedId(result: VoiceInputStartResult): Long =
        (result as VoiceInputStartResult.Started).captureSessionId

    @Test
    fun `explicit stop finalizes the utterance once and emits its transcript`() = runBlocking(Dispatchers.IO) {
        val c = controller()
        val scope = CoroutineScope(Dispatchers.IO)
        val collected = Collected(c.events, scope)
        try {
            firstFrame = CountDownLatch(1)
            c.startListening(VoiceCaptureMode.Command)
            assertTrue(firstFrame.await(5, TimeUnit.SECONDS))

            c.stopListening()
            collected.awaitStops(1)

            assertEquals(listOf("Final text."), collected.transcripts())
            assertEquals(1, log.count { it == "finish#1" })
            assertFalse(log.contains("close#1"))
        } finally {
            collected.job.cancel()
            scope.cancel()
        }
    }

    @Test
    fun `release supersedes a capture with no transcript and closes before the next open`() = runBlocking(Dispatchers.IO) {
        val c = controller()
        val scope = CoroutineScope(Dispatchers.IO)
        val collected = Collected(c.events, scope)
        try {
            val first = startedId(c.startListening(VoiceCaptureMode.Command))
            assertTrue(firstFrame.await(5, TimeUnit.SECONDS))

            c.releaseEngine()
            collected.awaitStops(1)
            assertEquals(emptyList<String>(), collected.transcripts())
            assertEquals(0, log.count { it == "finish#1" })
            assertEquals(1, log.count { it == "close#1" })

            firstFrame = CountDownLatch(1)
            val second = startedId(c.startListening(VoiceCaptureMode.Command))
            assertNotEquals(first, second)
            assertTrue(log.indexOf("close#1") < log.indexOf("open#2"))
            assertTrue(firstFrame.await(5, TimeUnit.SECONDS))
            c.releaseEngine()
            collected.awaitStops(1)
        } finally {
            collected.job.cancel()
            scope.cancel()
        }
    }

    @Test
    fun `a release that lands during the final render drops the transcript`() = runBlocking(Dispatchers.IO) {
        val c = controller()
        val scope = CoroutineScope(Dispatchers.IO)
        val collected = Collected(c.events, scope)
        try {
            // The first PCEC render in the capture loop blocks until the gate opens.
            val gate = CountDownLatch(1)
            renderGate = gate
            val fenced = CountDownLatch(1)
            c.releaseFencedForTest = { fenced.countDown() }
            firstFrame = CountDownLatch(1)
            c.startListening(VoiceCaptureMode.Command)
            assertTrue(firstFrame.await(5, TimeUnit.SECONDS))
            assertTrue(renderStarted.await(5, TimeUnit.SECONDS))

            // Release fences the capture while the render is blocked; the result must be dropped.
            val release = launch { c.releaseEngine() }
            assertTrue(fenced.await(5, TimeUnit.SECONDS))
            gate.countDown()
            release.join()
            collected.awaitStops(1)

            assertEquals(emptyList<String>(), collected.transcripts())
            assertEquals(1, log.count { it == "close#1" })
        } finally {
            collected.job.cancel()
            scope.cancel()
        }
    }

    @Test
    fun `restart after an explicit stop reuses the engine with a fresh session`() = runBlocking(Dispatchers.IO) {
        val c = controller()
        val scope = CoroutineScope(Dispatchers.IO)
        val collected = Collected(c.events, scope)
        try {
            firstFrame = CountDownLatch(1)
            val first = startedId(c.startListening(VoiceCaptureMode.Command))
            assertTrue(firstFrame.await(5, TimeUnit.SECONDS))
            c.stopListening()
            collected.awaitStops(1)

            firstFrame = CountDownLatch(1)
            val second = startedId(c.startListening(VoiceCaptureMode.Command))
            assertTrue(firstFrame.await(5, TimeUnit.SECONDS))
            c.stopListening()
            collected.awaitStops(1)

            assertNotEquals(first, second)
            assertEquals(1, engines.size)
            assertEquals(2, log.count { it == "finish#1" })
            assertEquals(2, collected.transcripts().size)
            assertFalse(log.contains("close#1"))
        } finally {
            collected.job.cancel()
            scope.cancel()
        }
    }

    @Test
    fun `release with an idle engine closes it once and is idempotent`() = runBlocking(Dispatchers.IO) {
        val c = controller()
        c.prewarm()

        c.releaseEngine()
        c.releaseEngine()

        assertEquals(listOf("open#1", "close#1"), log.toList())
    }

    @Test
    fun `a failing engine open returns unavailable from start and prewarm does not throw`() = runBlocking(Dispatchers.IO) {
        val c = controller()
        openFails = true

        c.prewarm()
        val result = c.startListening(VoiceCaptureMode.Command)

        assertTrue(result is VoiceInputStartResult.Unavailable)
        assertEquals(emptyList<String>(), log.toList())
        assertTrue(micReleasedSignal.tryAcquire(micOpens.get(), 5, TimeUnit.SECONDS), "failed open must release the mic")
        assertTrue(focusAbandonedSignal.tryAcquire(focusRequests.get(), 5, TimeUnit.SECONDS), "failed open must abandon focus")
        assertEquals(micOpens.get(), micReleases.get())
        assertEquals(focusRequests.get(), focusAbandons.get())
    }

    @Test
    fun `a stop before the mic opens never opens the mic, plays the cue or emits ListeningStarted`() = runBlocking(Dispatchers.IO) {
        val c = controller()
        val scope = CoroutineScope(Dispatchers.IO)
        val collected = Collected(c.events, scope)
        try {
            // The stop lands after the call started but before the mic-open decision.
            val result = c.startListening(VoiceCaptureMode.Command, isWanted = {
                c.stopListening()
                true
            })

            assertTrue(result is VoiceInputStartResult.Started)
            collected.awaitStops(1)
            assertEquals(0, micOpens.get(), "stop before mic must not open the mic")
            assertEquals(0, focusRequests.get(), "stop before mic must not request focus")
            assertEquals(0, collected.started(), "no ListeningStarted before the mic opens")
        } finally {
            collected.job.cancel()
            scope.cancel()
        }
    }

    @Test
    fun `a caller cancelled while the engine loads releases the mic, focus and emits ListeningStopped`() = runBlocking(Dispatchers.IO) {
        val c = controller()
        val scope = CoroutineScope(Dispatchers.IO)
        val collected = Collected(c.events, scope)
        try {
            engineGate = CountDownLatch(1)
            val caller = launch { c.startListening(VoiceCaptureMode.Command) }
            assertTrue(engineEntered.await(5, TimeUnit.SECONDS))
            assertEquals(1, micOpens.get(), "mic opens before the engine loads")

            caller.cancel()
            engineGate!!.countDown()
            caller.join()
            collected.awaitStops(1)

            assertTrue(micReleasedSignal.tryAcquire(1, 5, TimeUnit.SECONDS), "cancelled start must release the mic it opened")
            assertTrue(focusAbandonedSignal.tryAcquire(1, 5, TimeUnit.SECONDS), "cancelled start must abandon focus")
            assertEquals(1, micReleases.get())
            assertEquals(focusRequests.get(), focusAbandons.get())
            // Sherpa order: the cue opens with the mic, before the engine loads; cancel closes that pair.
            assertEquals(1, collected.started(), "ListeningStarted fires at mic open")
            assertEquals(1, collected.stoppedCount(), "cancel must pair it with ListeningStopped")
        } finally {
            collected.job.cancel()
            scope.cancel()
        }
    }

    @Test
    fun `a blank endpoint emits the decoded final even when RMS stays below threshold`() = runBlocking(Dispatchers.IO) {
        val c = controller()
        val scope = CoroutineScope(Dispatchers.IO)
        val collected = Collected(c.events, scope)
        try {
            amplitude = 0 // silence: RMS never reaches SPEECH_RMS
            blankAfterFrames = 3
            firstFrame = CountDownLatch(1)
            c.startListening(VoiceCaptureMode.Command)
            collected.awaitStops(1)

            assertEquals(listOf("Final text."), collected.transcripts())
            assertEquals(0, collected.events.count { it is VoiceInputEvent.SpeechDetected })
        } finally {
            collected.job.cancel()
            scope.cancel()
        }
    }

    @Test
    fun `PCEC renders once per decoder frame advance, not per chunk`() = runBlocking(Dispatchers.IO) {
        val c = controller()
        val scope = CoroutineScope(Dispatchers.IO)
        val collected = Collected(c.events, scope)
        try {
            // Decoder advances on every second chunk; endpoint after 10 committed frames.
            advanceEvery = 2
            blankAfterFrames = 10
            firstFrame = CountDownLatch(1)
            c.startListening(VoiceCaptureMode.Command)
            collected.awaitStops(1)

            // 10 renders during the loop (one per advance) plus 1 final render.
            verify(exactly = 11) { engines.single().decodePcec(true) }
            assertEquals(listOf("Final text."), collected.transcripts())
        } finally {
            collected.job.cancel()
            scope.cancel()
        }
    }

    private companion object {
        const val ENDPOINT_FRAMES = 25
    }
}
