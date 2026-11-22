package com.kernel.ai.core.voice

import android.content.Context
import android.media.AudioManager
import android.media.AudioRecord
import com.kernel.ai.core.inference.hardware.HardwareProfileDetector
import com.kernel.ai.core.inference.hardware.HardwareTier
import com.kernel.ai.core.voice.asr4all.Asr4AllEngine
import com.kernel.ai.core.voice.asr4all.Asr4AllSize
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * Deterministic controller tests for the asr4all capture lifecycle. The recorder returns frames
 * at [amplitude] until stopped; the engine is a relaxed mock whose calls are appended to [log].
 * Every wait is a latch or semaphore released by the code under test; there are no sleeps.
 */
class Asr4allVoiceInputControllerTest {
    /** Generous join bound for the lifecycle tests: a healthy join must not be given up under CI load. Only the give-up test passes a shorter one. */
    private val defaultCaptureJoinTimeoutMs = 30_000L

    private val log = CopyOnWriteArrayList<String>()
    private val engines = CopyOnWriteArrayList<Asr4AllEngine>()
    private val micOpens = java.util.concurrent.atomic.AtomicInteger(0)
    private val micReleases = java.util.concurrent.atomic.AtomicInteger(0)
    private val focusRequests = java.util.concurrent.atomic.AtomicInteger(0)
    private val focusAbandons = java.util.concurrent.atomic.AtomicInteger(0)
    private val micReleasedSignal = Semaphore(0)
    private val focusAbandonedSignal = Semaphore(0)
    @Volatile private var firstFrame = CountDownLatch(1)
    @Volatile private var openFails = false
    @Volatile private var amplitude: Short = 8000
    /** Amplitude by 1-based read count, for tests where the reader runs ahead of the engine. Overrides [amplitude]. */
    @Volatile private var amplitudeForRead: ((Int) -> Int)? = null
    private val readCount = java.util.concurrent.atomic.AtomicInteger(0)
    /** Test-only: reads after this count return -1, as AudioRecord does when the mic reader stops mid-capture. */
    @Volatile private var readFailsAfter: Int? = null
    /** Test-only: the reader stays at most this many chunks ahead of the engine. Pre-roll drops its oldest chunks before attach, so an unbounded reader could drop the lead-in. */
    @Volatile private var readAhead: Int? = null
    /** Set once the engine finishes: the consumer is done, so the reader must not wait on it any longer. */
    @Volatile private var finished = false
    @Volatile private var renderGate: CountDownLatch? = null
    private val renderStarted = CountDownLatch(1)
    /** Partial text per acceptAudio call (1-based index); the default reports "Final" on every chunk. */
    @Volatile private var partialForCall: ((Int) -> String)? = null
    /** Text finish() returns for the utterance; empty means nothing was decoded. */
    @Volatile private var finalText = "Final text."
    /** Set to block engine construction until the test releases it. */
    @Volatile private var engineGate: CountDownLatch? = null
    /** Test-only: each read blocks on this latch. Holding it keeps a capture live; an unheld reader outruns the engine and reaches its 1 s silence endpoint (10 chunks) within microseconds. */
    @Volatile private var readGate: CountDownLatch? = null
    private val engineEntered = CountDownLatch(1)
    /** Test-only: holds focus abandonment after it is entered, so a teardown can be parked mid-release. */
    @Volatile private var abandonGate: CountDownLatch? = null
    private val abandonEntered = CountDownLatch(1)
    /** Focus events in call order; "abandon-done" is recorded only after the abandon returns. */
    private val focusOrder = java.util.concurrent.ConcurrentLinkedQueue<String>()
    @Volatile private var tier = HardwareTier.FLAGSHIP
    private val storedSize = MutableStateFlow(Asr4AllSize.M)
    private val openLog = CopyOnWriteArrayList<Pair<Asr4AllSize, Int>>()
    /** acceptAudio calls across every engine this test opened: one per capture chunk. */
    private val acceptCalls = java.util.concurrent.atomic.AtomicInteger(0)

    private fun onFocusRequest(): Int {
        focusRequests.incrementAndGet()
        focusOrder += "request"
        return 1
    }

    private fun onFocusAbandon(): Int {
        abandonEntered.countDown()
        abandonGate?.await(5, TimeUnit.SECONDS)
        focusAbandons.incrementAndGet()
        focusOrder += "abandon-done"
        focusAbandonedSignal.release()
        return 1
    }

    private fun controller(
        captureJoinTimeoutMs: Long = defaultCaptureJoinTimeoutMs,
        termsAccepted: Boolean = true,
    ): Asr4allVoiceInputController {
        var opened = 0
        val context = mockk<Context>(relaxed = true)
        every { context.getSystemService(AudioManager::class.java) } returns mockk<AudioManager>(relaxed = true) {
            every { requestAudioFocus(any<android.media.AudioFocusRequest>()) } answers { onFocusRequest() }
            every { abandonAudioFocusRequest(any()) } answers { onFocusAbandon() }
            // Unit tests report SDK_INT below O, so the controller takes the legacy overloads.
            every { requestAudioFocus(any<AudioManager.OnAudioFocusChangeListener>(), any(), any()) } answers { onFocusRequest() }
            every { abandonAudioFocus(any()) } answers { onFocusAbandon() }
        }
        val prefs = mockk<VoiceInputPreferences>(relaxed = true) {
            every { asr4allSize } returns storedSize
        }
        val detector = mockk<HardwareProfileDetector>(relaxed = true) {
            every { profile.tier } answers { tier }
        }
        return Asr4allVoiceInputController(context, prefs, detector, captureJoinTimeoutMs, { termsAccepted }).apply {
            availableForTest = { true }
            micGrantedForTest = true
            recorderForTest = { recorder() }
            engineOpenForTest = { size, threads ->
                engineEntered.countDown()
                engineGate?.await(5, TimeUnit.SECONDS)
                if (openFails) error("bad model file")
                opened++
                openLog += size to threads
                val index = opened
                newEngine(index).also { engines += it; log += "open#$index" }
            }
            releaseFencedForTest = null
        }
    }

    private fun recorder(): AudioRecord {
        micOpens.incrementAndGet()
        focusOrder += "mic-open"
        return mockk(relaxed = true) {
            every { state } returns AudioRecord.STATE_INITIALIZED
            every { release() } answers { micReleases.incrementAndGet(); micReleasedSignal.release() }
            every { read(any<ShortArray>(), any(), any()) } answers {
                val buf = firstArg<ShortArray>()
                val read = readCount.incrementAndGet()
                if (readFailsAfter?.let { read > it } == true) return@answers -1
                readAhead?.let { ahead -> while (read - acceptCalls.get() > ahead && !finished) Thread.sleep(1) }
                buf.fill(amplitudeForRead?.invoke(read)?.toShort() ?: amplitude)
                firstFrame.countDown()
                readGate?.await()
                buf.size
            }
        }
    }

    private fun newEngine(index: Int): Asr4AllEngine = mockk<Asr4AllEngine>(relaxed = true).also { e ->
        var calls = 0
        every { e.acceptAudio(any(), any(), any()) } answers {
            calls++
            acceptCalls.incrementAndGet()
            partialForCall?.invoke(calls) ?: "Final"
        }
        every { e.reset() } answers { log += "reset#$index" }
        every { e.finish() } answers {
            finished = true
            renderGate?.let { renderStarted.countDown(); it.await(5, TimeUnit.SECONDS) }
            log += "finish#$index"
            finalText
        }
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
            assertTrue(stopped.tryAcquire(count, 30, TimeUnit.SECONDS), "capture did not stop")
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
    fun `without accepted terms the downloaded weights read as missing and nothing opens`() = runBlocking(Dispatchers.IO) {
        val c = controller(termsAccepted = false)
        assertFalse(c.isAvailable())
        assertTrue(c.startListening(VoiceCaptureMode.Command) is VoiceInputStartResult.Unavailable)
        assertTrue(openLog.isEmpty())
        assertEquals(0, micOpens.get())
    }

    @Test
    fun `release supersedes a capture with no transcript and closes before the next open`() = runBlocking(Dispatchers.IO) {
        val c = controller()
        val scope = CoroutineScope(Dispatchers.IO)
        val collected = Collected(c.events, scope)
        try {
            // Holds the reader after its first chunk, so the capture stays live until the release. An unheld reader
            // outruns the engine and reaches its 1 s silence endpoint (10 chunks) within microseconds, so it could end and emit
            // its transcript before the test releases it.
            val hold = CountDownLatch(1)
            readGate = hold
            c.releaseFencedForTest = { hold.countDown() }
            val first = startedId(c.startListening(VoiceCaptureMode.Command))
            assertTrue(firstFrame.await(5, TimeUnit.SECONDS))

            c.releaseEngine()
            collected.awaitStops(1)
            assertEquals(emptyList<String>(), collected.transcripts())
            assertEquals(0, log.count { it == "finish#1" })
            assertEquals(1, log.count { it == "close#1" })

            firstFrame = CountDownLatch(1)
            val nextHold = CountDownLatch(1)
            readGate = nextHold
            c.releaseFencedForTest = { nextHold.countDown() }
            val second = startedId(c.startListening(VoiceCaptureMode.Command))
            assertNotEquals(first, second)
            assertTrue(log.indexOf("close#1") < log.indexOf("open#2"))
            assertTrue(firstFrame.await(5, TimeUnit.SECONDS))
            c.releaseEngine()
            collected.awaitStops(1)
        } finally {
            readGate?.countDown() // releases a reader still held by a failing assertion
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
            // finish() blocks until the gate opens, so the release lands during the final render.
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
    fun `a stop during a cold model load releases mic and focus before the load ends and emits no transcript`() = runBlocking(Dispatchers.IO) {
        val c = controller()
        val scope = CoroutineScope(Dispatchers.IO)
        val collected = Collected(c.events, scope)
        val gate = CountDownLatch(1)
        engineGate = gate
        try {
            val start = async { c.startListening(VoiceCaptureMode.Command) }
            assertTrue(engineEntered.await(5, TimeUnit.SECONDS))
            assertEquals(1, micOpens.get(), "the mic opens before the model loads")

            c.stopListening()
            // Handshake: the reader must release the mic and focus while the load is still blocked.
            assertTrue(micReleasedSignal.tryAcquire(1, 5, TimeUnit.SECONDS), "stop must release the mic during the load")
            assertTrue(focusAbandonedSignal.tryAcquire(1, 5, TimeUnit.SECONDS), "stop must abandon focus during the load")
            assertEquals(0, log.count { it == "open#1" }, "the load has not finished")

            gate.countDown()
            val startResult = start.await()
            collected.awaitStops(1)
            assertTrue(startResult is VoiceInputStartResult.Cancelled)
            assertEquals(
                (startResult as VoiceInputStartResult.Cancelled).captureSessionId,
                collected.events.filterIsInstance<VoiceInputEvent.ListeningStopped>().single().captureSessionId,
            )
            assertEquals(emptyList<String>(), collected.transcripts())
            assertEquals(0, log.count { it == "finish#1" })
            assertTrue(
                collected.events.filterIsInstance<VoiceInputEvent.ListeningStopped>().single().cancelled,
                "a stop during the cold load has no final, so its terminal must be cancelled",
            )
            assertEquals(1, collected.started())

            // A new route takes focus only after the old one is abandoned.
            // Held: the second route's capture must stay live while focus and mic are asserted.
            val secondHold = CountDownLatch(1)
            readGate = secondHold
            c.releaseFencedForTest = { secondHold.countDown() }
            firstFrame = CountDownLatch(1)
            startedId(c.startListening(VoiceCaptureMode.Command))
            assertTrue(firstFrame.await(5, TimeUnit.SECONDS))
            assertEquals(2, focusRequests.get())
            assertEquals(1, focusAbandons.get())
            c.releaseEngine()
        } finally {
            engineGate = null
            readGate?.countDown()
            collected.job.cancel()
            scope.cancel()
        }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `deselection releases a cold mic immediately and stale release does not block reselection`() =
        runBlocking(Dispatchers.IO) {
            Dispatchers.setMain(UnconfinedTestDispatcher())
            val gate = CountDownLatch(1)
            engineGate = gate
            val selectedEngine = MutableStateFlow(VoiceInputEngine.Asr4all)
            val preferences = mockk<VoiceInputPreferences>()
            every { preferences.selectedEngine } returns selectedEngine
            every { preferences.allowOnlineSpeechFallback } returns MutableStateFlow(false)
            val native = mockk<NativeAndroidVoiceInputController>(relaxed = true)
            val sherpa = mockk<SherpaOnnxVoiceInputController>(relaxed = true)
            every { native.events } returns emptyFlow()
            every { sherpa.events } returns emptyFlow()
            val asr = controller()
            val selector = SelectableVoiceInputController(
                voiceInputPreferences = preferences,
                nativeAndroidVoiceInputController = native,
                sherpaOnnxVoiceInputController = sherpa,
                asr4allVoiceInputController = asr,
                androidNativeRecognitionSupport = mockk(relaxed = true),
            )
            try {
                val oldStart = async { selector.startListening(VoiceCaptureMode.Command) }
                assertTrue(engineEntered.await(5, TimeUnit.SECONDS), "cold engine load must be entered")
                assertEquals(1, micOpens.get())

                selectedEngine.value = VoiceInputEngine.AndroidNative
                assertTrue(
                    micReleasedSignal.tryAcquire(1, 5, TimeUnit.SECONDS),
                    "deselection must discard the old mic without waiting for the cold engine load",
                )
                assertEquals(0, log.count { it == "open#1" }, "the engine remains gated")

                selectedEngine.value = VoiceInputEngine.Asr4all
                val replacementStart = async { selector.startListening(VoiceCaptureMode.Command) }
                gate.countDown()

                assertTrue(oldStart.await() is VoiceInputStartResult.Cancelled)
                val replacement = replacementStart.await()
                assertTrue(replacement is VoiceInputStartResult.Started)
                assertEquals(2, micOpens.get(), "the reselected capture opens after the old reader exits")
                assertTrue(focusOrder.contains("abandon-done"), "the discarded route must abandon its focus")
                assertTrue(focusOrder.indexOf("abandon-done") < focusOrder.lastIndexOf("mic-open"))
                selector.cancelListening((replacement as VoiceInputStartResult.Started).captureSessionId)
                asr.releaseEngine()
            } finally {
                gate.countDown()
                engineGate = null
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `a new route opens focus and mic only after a blocked teardown abandons the old focus`() = runBlocking(Dispatchers.IO) {
        val c = controller()
        val release = CountDownLatch(1)
        abandonGate = release
        try {
            // Each route's reader is held after its first chunk, so a capture the test does not end stays live
            // instead of reaching its silence endpoint and abandoning focus on its own.
            val firstHold = CountDownLatch(1)
            readGate = firstHold
            firstFrame = CountDownLatch(1)
            startedId(c.startListening(VoiceCaptureMode.Command))
            assertTrue(firstFrame.await(5, TimeUnit.SECONDS))
            c.stopListening()
            // The held reader wakes on the closed buffer; its teardown then reaches the blocked abandon.
            firstHold.countDown()
            assertTrue(abandonEntered.await(5, TimeUnit.SECONDS), "teardown must reach focus abandonment")
            // Overlap: the new route runs eagerly up to its first suspension while the old teardown is held.
            val secondHold = CountDownLatch(1)
            readGate = secondHold
            c.releaseFencedForTest = { secondHold.countDown() }
            firstFrame = CountDownLatch(1)
            val start = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                val overlap = c.startListening(VoiceCaptureMode.Command)
                assertTrue(overlap is VoiceInputStartResult.Started, "overlapping start must open after the old abandon: $overlap")
            }
            assertEquals(1, focusRequests.get(), "no second focus request while the old abandon is held")
            assertEquals(1, micOpens.get(), "no second recorder open while the old abandon is held")
            release.countDown()
            start.join()
            assertTrue(firstFrame.await(5, TimeUnit.SECONDS))
            val events = focusOrder.toList()
            val abandonAt = events.indexOf("abandon-done")
            assertTrue(abandonAt >= 0, "old abandon must complete")
            val before = events.take(abandonAt)
            val after = events.drop(abandonAt + 1)
            assertEquals(1, before.count { it == "request" }, "only the first route requests focus before the abandon")
            assertEquals(1, before.count { it == "mic-open" }, "only the first route opens the recorder before the abandon")
            assertEquals(2, after.size, "exactly the new route's request and open follow the abandon: $events")
            assertTrue(after.containsAll(listOf("request", "mic-open")), "new route must request focus and open after abandon: $events")
            c.releaseEngine()
        } finally {
            release.countDown()
            readGate?.countDown()
            abandonGate = null
        }
    }

    @Test
    fun `a teardown still held past the join bound is given up and the new start is unavailable`() = runBlocking(Dispatchers.IO) {
        val c = controller(captureJoinTimeoutMs = 200)
        val release = CountDownLatch(1)
        abandonGate = release
        try {
            firstFrame = CountDownLatch(1)
            startedId(c.startListening(VoiceCaptureMode.Command))
            assertTrue(firstFrame.await(5, TimeUnit.SECONDS))
            val stop = launch { c.stopListening() }
            assertTrue(abandonEntered.await(5, TimeUnit.SECONDS), "teardown must reach focus abandonment")
            val result = c.startListening(VoiceCaptureMode.Command)
            assertTrue(result is VoiceInputStartResult.Unavailable, "a teardown held past the bound must be given up, got $result")
            assertEquals(1, focusRequests.get(), "the given-up route must not request focus")
            release.countDown()
            stop.join()
        } finally {
            release.countDown()
            abandonGate = null
        }
    }

    @Test
    fun `withEngineReleased gives up on a capture held past the bound and runs nothing`() = runBlocking(Dispatchers.IO) {
        val c = controller(captureJoinTimeoutMs = 200)
        val gate = CountDownLatch(1)
        readGate = gate
        try {
            firstFrame = CountDownLatch(1)
            startedId(c.startListening(VoiceCaptureMode.Command))
            assertTrue(firstFrame.await(5, TimeUnit.SECONDS))
            var ran = false
            val released = c.withEngineReleased { ran = true }
            assertTrue(released == null, "a reader still parked past the bound must not be released, got $released")
            assertTrue(!ran, "the block must not run while the capture is live")
        } finally {
            gate.countDown()
            readGate = null
        }
    }

    @Test
    fun `withEngineReleasedIfIdle refuses a live capture and leaves it recording`() = runBlocking(Dispatchers.IO) {
        val c = controller()
        // Holding the reader keeps the capture live; without it the fake source can end the capture on its own.
        val gate = CountDownLatch(1)
        readGate = gate
        try {
            firstFrame = CountDownLatch(1)
            startedId(c.startListening(VoiceCaptureMode.Command))
            assertTrue(firstFrame.await(5, TimeUnit.SECONDS))
            var ran = false
            // Bounded: a release that superseded the capture would return here at once; one that waited would hang.
            val released = withTimeout(5_000) { c.withEngineReleasedIfIdle { ran = true } }
            assertTrue(released == null, "a live capture must be refused, got $released")
            assertFalse(ran, "the block must not run while the capture is live")
            assertTrue(c.hasActiveCapture(), "the refused release must leave the capture running")
        } finally {
            gate.countDown()
            readGate = null
            c.stopListening()
        }
    }

    @Test
    fun `awaitCaptureIdle returns only after the live capture has ended`() = runBlocking(Dispatchers.IO) {
        val c = controller()
        val gate = CountDownLatch(1)
        readGate = gate
        try {
            firstFrame = CountDownLatch(1)
            startedId(c.startListening(VoiceCaptureMode.Command))
            assertTrue(firstFrame.await(5, TimeUnit.SECONDS))
            val idle = async { c.awaitCaptureIdle() }
            // The reader is held on [gate], so the capture is live and the waiter must still be parked.
            assertFalse(idle.isCompleted, "must not return while the capture is live")
            gate.countDown()
            c.stopListening()
            withTimeout(5_000) { idle.await() }
            assertFalse(c.hasActiveCapture())
        } finally {
            gate.countDown()
            readGate = null
            c.stopListening()
        }
    }

    @Test
    fun `prewarm returns without waiting for a live capture and opens no engine`() = runBlocking(Dispatchers.IO) {
        val c = controller()
        firstFrame = CountDownLatch(1)
        // Held: the capture must stay live while prewarm is asked to wait for it; an unheld reader can reach its silence endpoint first.
        val hold = CountDownLatch(1)
        readGate = hold
        try {
            startedId(c.startListening(VoiceCaptureMode.Command))
            assertTrue(firstFrame.await(5, TimeUnit.SECONDS))
            val opened = log.count { it.startsWith("open#") }
            // Bounded: a prewarm that waited for the live capture to end would never return here.
            val warmed = withTimeout(5_000) { c.prewarmIfCurrent { true } }
            assertFalse(warmed)
            assertEquals(opened, log.count { it.startsWith("open#") })
        } finally {
            c.stopListening()
            readGate?.countDown()
        }
    }

    @Test
    fun `a start waits for an exclusive engine release and opens nothing during it`() = runBlocking(Dispatchers.IO) {
        val c = controller()
        c.prewarm()
        val inside = CountDownLatch(1)
        val leave = CountDownLatch(1)
        val exclusive = launch {
            c.withEngineReleased {
                log += "exclusive-enter"
                inside.countDown()
                leave.await(5, TimeUnit.SECONDS)
                log += "exclusive-exit"
            }
        }
        assertTrue(inside.await(5, TimeUnit.SECONDS))
        val start = launch { c.startListening(VoiceCaptureMode.Command) }
        leave.countDown()
        exclusive.join()
        start.join()

        assertTrue(log.indexOf("exclusive-exit") < log.indexOf("open#2"), "log=$log")
        assertEquals(1, log.count { it == "exclusive-enter" })
        c.releaseEngine()
    }

    @Test
    fun `silence with no decoded text ends as no-speech without a transcript`() = runBlocking(Dispatchers.IO) {
        val c = controller()
        val scope = CoroutineScope(Dispatchers.IO)
        val collected = Collected(c.events, scope)
        try {
            partialForCall = { "" }
            finalText = ""
            firstFrame = CountDownLatch(1)
            c.startListening(VoiceCaptureMode.Command)
            collected.awaitStops(1)

            assertEquals(emptyList<String>(), collected.transcripts())
            val error = collected.events.filterIsInstance<VoiceInputEvent.Error>().single()
            assertEquals(NO_SPEECH_WINDOW_EXHAUSTED, error.category)
            assertEquals("No speech heard. Try again.", error.message)
        } finally {
            collected.job.cancel()
            scope.cancel()
        }
    }

    @Test
    fun `mic reader stopping mid-capture is retryable and not reported as no speech`() = runBlocking(Dispatchers.IO) {
        val c = controller()
        val scope = CoroutineScope(Dispatchers.IO)
        val collected = Collected(c.events, scope)
        try {
            partialForCall = { "" }
            finalText = ""
            readFailsAfter = 3
            firstFrame = CountDownLatch(1)
            c.startListening(VoiceCaptureMode.Command)
            collected.awaitStops(1)

            assertEquals(emptyList<String>(), collected.transcripts())
            val error = collected.events.filterIsInstance<VoiceInputEvent.Error>().single()
            assertNull(error.category)
            assertEquals("Microphone stopped. Try again.", error.message)
        } finally {
            collected.job.cancel()
            scope.cancel()
        }
    }

    @Test
    fun `a stop before the mic opens never opens the mic, plays the cue or emits ListeningStarted`() = runBlocking(Dispatchers.IO) {
        val c = controller()
        val scope = CoroutineScope(Dispatchers.IO)
        val collected = Collected(c.events, scope)
        var admittedId: Long? = null
        try {
            // The stop lands after the call started but before the mic-open decision.
            val result = c.startListening(
                VoiceCaptureMode.Command,
                isWanted = {
                    c.stopListening()
                    true
                },
                onSession = { admittedId = it },
            )

            assertTrue(result is VoiceInputStartResult.Cancelled)
            assertEquals(null, (result as VoiceInputStartResult.Cancelled).captureSessionId)
            assertEquals(null, admittedId, "no admission callback before the mic opens")
            assertEquals(0, micOpens.get(), "stop before mic must not open the mic")
            assertEquals(0, focusRequests.get(), "stop before mic must not request focus")
            assertEquals(0, collected.started(), "no ListeningStarted before the mic opens")
            assertTrue(collected.events.none { it is VoiceInputEvent.ListeningStopped }, "never-admitted captures emit no terminal")
        } finally {
            collected.job.cancel()
            scope.cancel()
        }
    }

    @Test
    fun `ListeningRequested is announced before the mic opens and each announced session ends in one ListeningStopped`() =
        runBlocking(Dispatchers.IO) {
            val c = controller()
            val order = CopyOnWriteArrayList<String>()
            val stopped = Semaphore(0)
            // Unconfined: the collector runs inside the emitting call, so `order` is in emission order.
            val scope = CoroutineScope(Dispatchers.Unconfined)
            val collect = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                c.events.collect {
                    when (it) {
                        is VoiceInputEvent.ListeningRequested -> order += "requested#${it.captureSessionId}"
                        is VoiceInputEvent.ListeningStarted -> order += "started#${it.captureSessionId}"
                        is VoiceInputEvent.ListeningStopped -> {
                            order += "stopped#${it.captureSessionId}"
                            stopped.release()
                        }
                        else -> Unit
                    }
                }
            }
            c.recorderForTest = {
                order += "mic-open"
                recorder()
            }
            try {
                firstFrame = CountDownLatch(1)
                val id = startedId(c.startListening(VoiceCaptureMode.Command))
                assertTrue(firstFrame.await(5, TimeUnit.SECONDS))
                c.stopListening()
                assertTrue(stopped.tryAcquire(5, TimeUnit.SECONDS), "capture did not stop")
                assertEquals(
                    listOf("requested#$id", "mic-open", "started#$id", "stopped#$id"),
                    order.toList(),
                )

                order.clear()
                c.recorderForTest = {
                    order += "mic-attempt"
                    null
                }
                val failed = c.startListening(VoiceCaptureMode.Command)
                assertTrue(failed is VoiceInputStartResult.Unavailable)
                val failedId = order.first().removePrefix("requested#")
                assertEquals(
                    listOf("requested#$failedId", "mic-attempt", "stopped#$failedId"),
                    order.toList(),
                )
            } finally {
                collect.cancel()
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
            // Teardown must finish while the native open is still blocked: the gate opens last.
            assertTrue(micReleasedSignal.tryAcquire(1, 5, TimeUnit.SECONDS), "cancelled start must release the mic before the engine loads")
            assertTrue(focusAbandonedSignal.tryAcquire(1, 5, TimeUnit.SECONDS), "cancelled start must abandon focus before the engine loads")
            collected.awaitStops(1)
            engineGate!!.countDown()
            caller.join()

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
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    fun `a failed load still reports the stop when the caller is cancelled while the reader drains`() = runBlocking(Dispatchers.IO) {
        val c = controller()
        val scope = CoroutineScope(Dispatchers.IO)
        val collected = Collected(c.events, scope)
        val gate = CountDownLatch(1)
        try {
            openFails = true
            readGate = gate
            val loadOpen = CountDownLatch(1)
            engineGate = loadOpen
            val joinReached = CountDownLatch(1)
            c.beforeFailedLoadJoinForTest = { joinReached.countDown() }
            val caller = launch { c.startListening(VoiceCaptureMode.Command) }
            assertTrue(engineEntered.await(5, TimeUnit.SECONDS))
            // The load is held until the reader is parked in read; a load that failed first would
            // close the buffer before the reader starts, so no frame would ever be read.
            assertTrue(firstFrame.await(5, TimeUnit.SECONDS))
            loadOpen.countDown()
            // The load fails; the seam fires just before the caller joins the reader, which stays parked in read.
            assertTrue(joinReached.await(5, TimeUnit.SECONDS))
            caller.cancel()
            gate.countDown()
            caller.join()

            collected.awaitStops(1)
            assertEquals(1, collected.stoppedCount(), "a cancelled caller must still report the stop")
        } finally {
            gate.countDown() // release the untimed reader wait so a failing assertion cannot hang the suite
            engineGate = null
            readGate = null
            collected.job.cancel()
            scope.cancel()
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    fun `a stop during the engine load joins the reader before the cancelled stop is reported`() = runBlocking(Dispatchers.IO) {
        val c = controller()
        val scope = CoroutineScope(Dispatchers.IO)
        val collected = Collected(c.events, scope)
        val gate = CountDownLatch(1)
        val loadOpen = CountDownLatch(1)
        val joinReached = CountDownLatch(1)
        try {
            engineGate = loadOpen
            readGate = gate
            c.beforeCancelledStopJoinForTest = { joinReached.countDown() }
            var result: VoiceInputStartResult? = null
            val caller = launch { result = c.startListening(VoiceCaptureMode.Command) }
            assertTrue(engineEntered.await(5, TimeUnit.SECONDS))
            // The reader must be parked in read before the stop, or it would exit without reading.
            assertTrue(firstFrame.await(5, TimeUnit.SECONDS))
            c.stopListening()
            loadOpen.countDown()
            assertTrue(joinReached.await(5, TimeUnit.SECONDS))
            assertEquals(0, micReleases.get(), "the reader is still parked in read")
            // Bounded absence check: the gate stays closed, so a stop reported now would arrive inside this window.
            assertFalse(collected.stopped.tryAcquire(1, TimeUnit.SECONDS), "no stop before the reader drains")
            gate.countDown()
            caller.join()

            assertTrue(result is VoiceInputStartResult.Cancelled)
            assertEquals(1, micReleases.get(), "the reader releases the mic before the stop is reported")
            collected.awaitStops(1)
            assertEquals(1, collected.stoppedCount())
        } finally {
            gate.countDown() // release the untimed reader wait so a failing assertion cannot hang the suite
            engineGate = null
            readGate = null
            collected.job.cancel()
            scope.cancel()
        }
    }

    @Test
    fun `silence after decoded text endpoints the utterance and emits the final`() = runBlocking(Dispatchers.IO) {
        val c = controller()
        val scope = CoroutineScope(Dispatchers.IO)
        val collected = Collected(c.events, scope)
        try {
            amplitudeForRead = { read -> if (read in 2..4) 8000 else 0 } // chunk 1 is the quiet reference
            partialForCall = { "Final" }
            readAhead = 2
            firstFrame = CountDownLatch(1)
            c.startListening(VoiceCaptureMode.Command)
            collected.awaitStops(1)
            readAhead = null

            assertEquals(listOf("Final text."), collected.transcripts())
            assertTrue(collected.events.any { it is VoiceInputEvent.SpeechDetected })
            assertEquals(1, log.count { it == "finish#1" })
        } finally {
            collected.job.cancel()
            scope.cancel()
        }
    }

    @Test
    fun `steady background noise endpoints once it becomes the noise floor instead of running to the cap`() =
        runBlocking(Dispatchers.IO) {
            val c = controller()
            val scope = CoroutineScope(Dispatchers.IO)
            val collected = Collected(c.events, scope)
            try {
                amplitude = 0
                partialForCall = { calls ->
                    if (calls == 1) amplitude = 1638 // steady hiss of about 5% full scale from chunk 2 on
                    "Final"
                }
                firstFrame = CountDownLatch(1)
                c.startListening(VoiceCaptureMode.Command)
                collected.awaitStops(1)

                // Chunk 1 is the only quiet reference; it leaves the 3 s window at chunk 31, after which the
                // hiss is the floor and 10 quiet chunks endpoint. The old fixed gate ran to the 600-chunk cap.
                assertTrue(acceptCalls.get() < 60, "steady noise ran ${acceptCalls.get()} chunks")
                assertEquals(listOf("Final text."), collected.transcripts())
            } finally {
                collected.job.cancel()
                scope.cancel()
            }
        }

    @Test
    fun `a soft speaker is not cut off mid-utterance by a fixed loudness floor`() = runBlocking(Dispatchers.IO) {
        val c = controller()
        val scope = CoroutineScope(Dispatchers.IO)
        val collected = Collected(c.events, scope)
        try {
            // Quiet room, then soft speech at about 1% full scale (under the old 0.02 gate), then silence.
            amplitudeForRead = { read -> if (read <= 30) 30 else if (read <= 60) 300 else 0 }
            partialForCall = { calls -> if (calls <= 30) "" else "word$calls" }
            readAhead = 2
            firstFrame = CountDownLatch(1)
            c.startListening(VoiceCaptureMode.Command)
            collected.awaitStops(1)
            readAhead = null

            assertTrue(collected.events.any { it is VoiceInputEvent.SpeechDetected })
            // 30 lead-in chunks, 30 speech chunks, then the 10-chunk quiet tail. The old gate endpointed during speech.
            assertTrue(acceptCalls.get() >= 60, "soft speech was cut after ${acceptCalls.get()} chunks")
        } finally {
            collected.job.cancel()
            scope.cancel()
        }
    }

    @Test
    fun `a microphone open that throws reports one cancelled stop and abandons focus`() = runBlocking(Dispatchers.IO) {
        val c = controller()
        val scope = CoroutineScope(Dispatchers.IO)
        val collected = Collected(c.events, scope)
        try {
            c.recorderForTest = { throw SecurityException("microphone permission revoked") }
            assertTrue(c.startListening(VoiceCaptureMode.Command) is VoiceInputStartResult.Unavailable)
            collected.awaitStops(1)

            assertEquals(
                listOf(true),
                collected.events.filterIsInstance<VoiceInputEvent.ListeningStopped>().map { it.cancelled },
            )
            assertEquals(focusRequests.get(), focusAbandons.get(), "every focus request must be abandoned")
        } finally {
            collected.job.cancel()
            scope.cancel()
        }
    }

    @Test
    fun `live partials emit each changed partial once and the final after silence`() = runBlocking(Dispatchers.IO) {
        val c = controller()
        val scope = CoroutineScope(Dispatchers.IO)
        val collected = Collected(c.events, scope)
        try {
            partialForCall = { calls ->
                if (calls == 5) amplitude = 0
                if (calls <= 2) "weather" else "Weather, 42."
            }
            finalText = "Weather, 42."
            firstFrame = CountDownLatch(1)
            c.startListening(VoiceCaptureMode.Command)
            collected.awaitStops(1)

            val partials = collected.events.filterIsInstance<VoiceInputEvent.PartialTranscript>()
            assertEquals(listOf("weather", "Weather, 42."), partials.map { it.text })
            assertEquals(listOf("Weather, 42."), collected.transcripts())
            assertEquals(1, log.count { it == "finish#1" })
        } finally {
            collected.job.cancel()
            scope.cancel()
        }
    }

    @Test
    fun `open takes size and thread count from the stored size and tier and reopens when they change`() =
        runBlocking(Dispatchers.IO) {
            val c = controller()
            tier = HardwareTier.LOW_POWER
            storedSize.value = Asr4AllSize.L
            c.prewarm()
            assertEquals(listOf(Asr4AllSize.M to 2), openLog.toList(), "L is not offered on LOW_POWER; M with 2 threads")

            tier = HardwareTier.FLAGSHIP
            c.prewarm()
            assertEquals(listOf(Asr4AllSize.M to 2, Asr4AllSize.L to 4), openLog.toList())
            assertTrue(log.indexOf("close#1") < log.indexOf("open#2"), "the old engine closes before the reopen")
            c.releaseEngine()
        }
}
