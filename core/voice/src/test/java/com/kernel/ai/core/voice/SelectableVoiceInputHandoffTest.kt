package com.kernel.ai.core.voice

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlin.concurrent.thread
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Android native to Zipformer hand-off through [SelectableVoiceInputController] (#1853): the
 * hand-off itself, the speech-progress and session gates, failure surfacing, and the races
 * between a hand-off and stop or a newer start. Runs on a test Main dispatcher with virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SelectableVoiceInputHandoffTest {
    // Unconfined: startListening and collectors run eagerly, so a test's emits can't race a suspended start.
    private val dispatcher = UnconfinedTestDispatcher()
    private val voiceInputPreferences: VoiceInputPreferences = mockk()
    private val selectedEngine = MutableStateFlow(VoiceInputEngine.AndroidNative)
    private val native: NativeAndroidVoiceInputController = mockk()
    private val sherpa: SherpaOnnxVoiceInputController = mockk()
    private val recognitionSupport: AndroidNativeRecognitionSupport = mockk()
    private val nativeEvents = MutableSharedFlow<VoiceInputEvent>(extraBufferCapacity = 64)
    private val sherpaEvents = MutableSharedFlow<VoiceInputEvent>(extraBufferCapacity = 64)
    private val mode = VoiceCaptureMode.AlertCommand

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { voiceInputPreferences.selectedEngine } returns selectedEngine
        every { voiceInputPreferences.allowOnlineSpeechFallback } returns MutableStateFlow(false)
        every { native.events } returns nativeEvents
        every { sherpa.events } returns sherpaEvents
        every { native.stopListeningForSession(any()) } just runs
        every { native.cancelListeningForSession(any()) } just runs
        every { sherpa.stopListeningForSession(any()) } just runs
        every { sherpa.cancelListeningForSession(any()) } just runs
        every { native.stopListening() } just runs
        every { sherpa.stopListening() } just runs
        coEvery { sherpa.isSpecAvailable(any()) } returns true
        coEvery { native.startListening(any(), any(), any(), any()) } coAnswers {
            thirdArg<(Long) -> Unit>().invoke(1L)
            VoiceInputStartResult.Started(1L)
        }
        coEvery { sherpa.startListening(any(), any(), any()) } coAnswers {
            thirdArg<(Long) -> Unit>().invoke(2L)
            VoiceInputStartResult.Started(2L)
        }
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun controller() = SelectableVoiceInputController(
        voiceInputPreferences = voiceInputPreferences,
        nativeAndroidVoiceInputController = native,
        sherpaOnnxVoiceInputController = sherpa,
        asr4allVoiceInputController = mockk<Asr4allVoiceInputController>().also {
            every { it.events } returns MutableSharedFlow()
            every { it.stopListening() } just runs
            every { it.supersedeCapture() } just runs
            every { it.admissionEpochNow() } returns 0L
            every { it.supersedeCaptureIfAdmissionEpoch(any()) } answers { firstArg<Long>() + 1L }
            coEvery { it.releaseEngineIf(any(), any()) } coAnswers {
                secondArg<suspend () -> Boolean>().invoke()
            }
        },
        androidNativeRecognitionSupport = recognitionSupport,
    )

    private fun TestScope.eventsOf(c: SelectableVoiceInputController): MutableList<VoiceInputEvent> {
        val seen = mutableListOf<VoiceInputEvent>()
        backgroundScope.launch { c.events.collect { seen += it } }
        return seen
    }

    private fun TestScope.noticesOf(c: SelectableVoiceInputController): MutableList<VoiceNotice> {
        val notices = mutableListOf<VoiceNotice>()
        backgroundScope.launch { c.notices.collect { notices += it } }
        return notices
    }

    /** Zipformer start that emits readiness while starting, as the real controller does. */
    private fun zipformerStartsAs(id: Long) {
        coEvery { sherpa.startListening(any(), any(), any()) } coAnswers {
            thirdArg<(Long) -> Unit>().invoke(id)
            sherpaEvents.emit(VoiceInputEvent.ListeningStarted(mode, id))
            VoiceInputStartResult.Started(id)
        }
    }

    /** Zipformer start that never finishes loading; records whether it was cancelled. */
    private fun zipformerLoadHangs(): CompletableDeferred<Boolean> {
        val cancelled = CompletableDeferred<Boolean>()
        coEvery { sherpa.startListening(any(), any(), any()) } coAnswers {
            thirdArg<(Long) -> Unit>().invoke(2L)
            try {
                awaitCancellation()
            } catch (e: CancellationException) {
                cancelled.complete(true)
                throw e
            }
        }
        return cancelled
    }
    /**
     * Simulates a Zipformer start whose caller unwinds before NonCancellable mic teardown emits
     * the terminal. A second start can then prove the old terminal cannot clear its owner.
     */
    private fun zipformerLoadHangsUntilTerminal(): Pair<CompletableDeferred<Boolean>, CompletableDeferred<Unit>> {
        val cancelled = CompletableDeferred<Boolean>()
        val allowTerminal = CompletableDeferred<Unit>()
        var sessionId = 1L
        coEvery { sherpa.startListening(any(), any(), any()) } coAnswers {
            val id = ++sessionId
            thirdArg<(Long) -> Unit>().invoke(id)
            if (id == 2L) {
                try {
                    awaitCancellation()
                } catch (e: CancellationException) {
                    cancelled.complete(true)
                    withContext(NonCancellable) {
                        allowTerminal.await()
                        sherpaEvents.emit(VoiceInputEvent.ListeningStopped(mode, id, cancelled = true))
                    }
                    throw e
                }
            } else {
                sherpaEvents.emit(VoiceInputEvent.ListeningStarted(mode, id))
                VoiceInputStartResult.Started(id)
            }
        }
        return cancelled to allowTerminal
    }

    private fun stalled(id: Long) =
        VoiceInputEvent.Error(mode, "Android speech stalled", captureSessionId = id, category = NATIVE_STALLED)

    private fun notReady(id: Long) =
        VoiceInputEvent.Error(mode, "Didn't start", captureSessionId = id, category = NATIVE_NOT_READY)

    @Test
    fun `stall after readiness hands off, keeps the cue single and re-stamps zipformer events`() = runTest(dispatcher) {
        zipformerStartsAs(2L)
        val c = controller()
        val seen = eventsOf(c)
        val notices = noticesOf(c)

        assertEquals(VoiceInputStartResult.Started(1L), c.startListening(mode))
        nativeEvents.emit(VoiceInputEvent.ListeningStarted(mode, 1L))
        nativeEvents.emit(stalled(1L))
        nativeEvents.emit(VoiceInputEvent.ListeningStopped(mode, 1L))
        advanceUntilIdle()
        sherpaEvents.emit(VoiceInputEvent.Transcript(mode, "hello", captureSessionId = 2L))
        advanceUntilIdle()

        // Native readiness once; Zipformer readiness suppressed; native error and stop dropped.
        assertEquals(
            listOf(
                VoiceInputEvent.ListeningStarted(mode, 1L),
                VoiceInputEvent.Transcript(mode, "hello", captureSessionId = 1L),
            ),
            seen,
        )
        verify(exactly = 1) { native.stopListening() }
        assertEquals(listOf(VoiceNotice(1L, ZIPFORMER_STALL_NOTICE)), notices)
    }

    @Test
    fun `native that never became ready hands off and zipformer readiness plays the cue`() = runTest(dispatcher) {
        zipformerStartsAs(2L)
        val c = controller()
        val seen = eventsOf(c)
        val notices = noticesOf(c)

        c.startListening(mode)
        nativeEvents.emit(notReady(1L))
        advanceUntilIdle()

        assertEquals(listOf(VoiceInputEvent.ListeningStarted(mode, 1L)), seen)
        assertEquals(listOf(VoiceNotice(1L, ZIPFORMER_START_NOTICE)), notices)
    }

    @Test
    fun `speech progress blocks hand-off so the native error is surfaced`() = runTest(dispatcher) {
        val c = controller()
        val seen = eventsOf(c)

        c.startListening(mode)
        nativeEvents.emit(VoiceInputEvent.SpeechDetected(mode, 1L))
        nativeEvents.emit(stalled(1L))
        advanceUntilIdle()

        assertTrue(seen.contains(stalled(1L)))
        coVerify(exactly = 0) { sherpa.startListening(any(), any(), any()) }
    }

    @Test
    fun `failed hand-off surfaces the native error and stop, and leaves native active`() = runTest(dispatcher) {
        coEvery { sherpa.startListening(any(), any(), any()) } returns VoiceInputStartResult.Unavailable("no model")
        val c = controller()
        val seen = eventsOf(c)
        val notices = noticesOf(c)

        c.startListening(mode)
        nativeEvents.emit(stalled(1L))
        nativeEvents.emit(VoiceInputEvent.ListeningStopped(mode, 1L))
        advanceUntilIdle()

        assertEquals(listOf(stalled(1L), VoiceInputEvent.ListeningStopped(mode, 1L)), seen)
        assertTrue(notices.isEmpty())
    }

    @Test
    fun `exception inside the hand-off still delivers the native error`() = runTest(dispatcher) {
        coEvery { sherpa.isSpecAvailable(any()) } throws IllegalStateException("storage gone")
        val c = controller()
        val seen = eventsOf(c)

        c.startListening(mode)
        nativeEvents.emit(stalled(1L))
        nativeEvents.emit(VoiceInputEvent.ListeningStopped(mode, 1L))
        advanceUntilIdle()

        assertEquals(listOf(stalled(1L), VoiceInputEvent.ListeningStopped(mode, 1L)), seen)
    }

    @Test
    fun `exception while tearing native down still delivers the native error`() = runTest(dispatcher) {
        val c = controller()
        val seen = eventsOf(c)
        c.startListening(mode)
        every { native.stopListening() } throws IllegalStateException("binder died")

        nativeEvents.emit(stalled(1L))
        advanceUntilIdle()

        assertEquals(listOf(stalled(1L)), seen)
        coVerify(exactly = 0) { sherpa.startListening(any(), any(), any()) }
    }

    @Test
    fun `stop during the teardown settle never starts zipformer`() = runTest(dispatcher) {
        val c = controller()
        val seen = eventsOf(c)
        val notices = noticesOf(c)

        c.startListening(mode)
        nativeEvents.emit(stalled(1L))
        advanceTimeBy(NATIVE_TEARDOWN_SETTLE_MS / 2)
        c.stopListening(1L)
        advanceUntilIdle()

        coVerify(exactly = 0) { sherpa.startListening(any(), any(), any()) }
        assertTrue(seen.none { it is VoiceInputEvent.Error })
        assertTrue(notices.isEmpty())
    }

    @Test
    fun `stop from another thread during the zipformer load cancels the load`() = runTest(dispatcher) {
        val loadCancelled = zipformerLoadHangs()
        val c = controller()
        val seen = eventsOf(c)
        val notices = noticesOf(c)

        c.startListening(mode)
        nativeEvents.emit(stalled(1L))
        advanceUntilIdle() // the hand-off is now waiting on the Zipformer load

        thread(name = "not-main") { c.cancelListening(1L) }.join()
        advanceUntilIdle()

        assertTrue(loadCancelled.isCompleted)
        assertTrue(seen.none { it is VoiceInputEvent.Error })
        assertTrue(notices.isEmpty())
        // A cancelled in-flight Sherpa admission retires through its own matched terminal.
        sherpaEvents.emit(VoiceInputEvent.ListeningStopped(mode, 2L, cancelled = true))
        advanceUntilIdle()
        assertEquals(
            listOf(VoiceInputEvent.ListeningStopped(mode, 1L, cancelled = true)),
            seen,
        )
    }

    @Test
    fun `handoff admission stays owned through scoped stop until Sherpa releases mic and focus`() =
        runTest(dispatcher) {
            val c = controller()
            val seen = eventsOf(c)
            val release = CompletableDeferred<Unit>()
            val order = mutableListOf<String>()
            val scopedStops = mutableListOf<Long>()
            val nativeStops = mutableListOf<Long>()
            var releaseScheduled = false

            fun requestSherpaRelease() {
                if (releaseScheduled) return
                releaseScheduled = true
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    release.await()
                    order += "mic-released"
                    order += "focus-released"
                    sherpaEvents.emit(
                        VoiceInputEvent.ListeningStopped(mode, 2L, cancelled = true),
                    )
                }
            }

            every { native.stopListeningForSession(any()) } answers {
                nativeStops += firstArg<Long>()
            }
            every { sherpa.stopListeningForSession(any()) } answers {
                scopedStops += firstArg<Long>()
                requestSherpaRelease()
            }
            coEvery { sherpa.startListening(any(), any(), any()) } coAnswers {
                thirdArg<(Long) -> Unit>().invoke(2L)
                sherpaEvents.emit(VoiceInputEvent.ListeningStarted(mode, 2L))
                c.stopListening(1L)
                nativeEvents.emit(VoiceInputEvent.ListeningStopped(mode, 1L))
                VoiceInputStartResult.Started(2L)
            }

            c.startListening(mode)
            nativeEvents.emit(stalled(1L))
            advanceTimeBy(NATIVE_TEARDOWN_SETTLE_MS)
            advanceUntilIdle()

            assertEquals(listOf(2L), scopedStops)
            assertTrue(nativeStops.isEmpty())
            assertTrue(c.captureActivity.value)
            assertTrue(seen.none { it is VoiceInputEvent.ListeningStopped })

            release.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("mic-released", "focus-released"), order)
            assertEquals(
                listOf(VoiceInputEvent.ListeningStopped(mode, 1L, cancelled = true)),
                seen.filterIsInstance<VoiceInputEvent.ListeningStopped>(),
            )
            assertFalse(c.captureActivity.value)
        }

    @Test
    fun `stop after zipformer started but before the hand-off commits stops zipformer`() = runTest(dispatcher) {
        val c = controller()
        val notices = noticesOf(c)
        val seen = eventsOf(c)
        every { sherpa.cancelListeningForSession(2L) } answers {
            sherpaEvents.tryEmit(VoiceInputEvent.ListeningStopped(mode, 2L, cancelled = true))
        }
        coEvery { sherpa.startListening(any(), any(), any()) } coAnswers {
            thirdArg<(Long) -> Unit>().invoke(2L)
            c.stopListening(1L) // lands after Zipformer opened the microphone
            VoiceInputStartResult.Started(2L)
        }

        c.startListening(mode)
        nativeEvents.emit(stalled(1L))
        advanceUntilIdle()

        coVerify { sherpa.cancelListeningForSession(2L) }
        verify(exactly = 1) { sherpa.stopListeningForSession(2L) }
        verify(exactly = 0) { sherpa.stopListening() }
        assertEquals(
            listOf(VoiceInputEvent.ListeningStopped(mode, 1L, cancelled = true)),
            seen.filterIsInstance<VoiceInputEvent.ListeningStopped>(),
        )
        assertTrue(notices.isEmpty())
    }

    @Test
    fun `newer start supersedes a hand-off in flight without deadlock`() = runTest(dispatcher) {
        val loadCancelled = zipformerLoadHangs()
        val c = controller()
        every { sherpa.stopListeningForSession(2L) } answers {
            sherpaEvents.tryEmit(VoiceInputEvent.ListeningStopped(mode, 2L, cancelled = true))
        }
        val notices = noticesOf(c)
        val seen = eventsOf(c)

        c.startListening(mode)
        nativeEvents.emit(stalled(1L))
        advanceUntilIdle()

        coEvery { native.startListening(any(), any(), any(), any()) } coAnswers {
            thirdArg<(Long) -> Unit>().invoke(3L)
            VoiceInputStartResult.Started(3L)
        }
        val second = async { c.startListening(mode) }
        advanceUntilIdle()

        assertEquals(VoiceInputStartResult.Started(3L), second.await())
        assertTrue(loadCancelled.isCompleted)
        assertTrue(notices.isEmpty())
        assertTrue(seen.none { it is VoiceInputEvent.Error })
    }

    @Test
    fun `stale native error from an earlier session is never handed off`() = runTest(dispatcher) {
        val c = controller()
        every { native.stopListeningForSession(1L) } answers {
            nativeEvents.tryEmit(VoiceInputEvent.ListeningStopped(mode, 1L))
        }
        val seen = eventsOf(c)

        c.startListening(mode)
        coEvery { native.startListening(any(), any(), any(), any()) } coAnswers {
            thirdArg<(Long) -> Unit>().invoke(3L)
            VoiceInputStartResult.Started(3L)
        }
        c.startListening(mode)
        nativeEvents.emit(stalled(1L))
        advanceUntilIdle()

        assertEquals(
            listOf(VoiceInputEvent.ListeningStopped(mode, 1L, cancelled = true)),
            seen.filterIsInstance<VoiceInputEvent.ListeningStopped>(),
        )
        assertTrue(seen.none { it is VoiceInputEvent.Error })
        coVerify(exactly = 0) { sherpa.startListening(any(), any(), any()) }
        verify(exactly = 0) { native.stopListening() }
    }

    @Test
    fun `native start failure falls back at start, routes zipformer readiness and replays the note`() =
        runTest(dispatcher) {
            coEvery { native.startListening(any(), any(), any(), any()) } returns VoiceInputStartResult.Unavailable("no service")
            zipformerStartsAs(2L)
            val c = controller()
            val seen = eventsOf(c)

            assertEquals(VoiceInputStartResult.Started(2L), c.startListening(mode))
            advanceUntilIdle()

            assertEquals(listOf(VoiceInputEvent.ListeningStarted(mode, 2L)), seen)
            // Subscribed only after the start returned: the replayed note is still delivered.
            assertEquals(ZIPFORMER_START_NOTICE, c.noticesFor(2L).first())
            assertEquals(ZIPFORMER_START_NOTICE, c.latestNoticeFor(2L))
        }

    @Test
    fun `native cancelled stop from a failed start is held until zipformer settles`() =
        runTest(dispatcher) {
            coEvery { native.startListening(any(), any(), any(), any()) } coAnswers {
                thirdArg<(Long) -> Unit>().invoke(101L)
                nativeEvents.emit(VoiceInputEvent.ListeningStopped(mode, 101L, cancelled = true))
                VoiceInputStartResult.Unavailable("native failed")
            }
            zipformerStartsAs(202L)
            val c = controller()
            val seen = eventsOf(c)

            assertEquals(VoiceInputStartResult.Started(202L), c.startListening(mode))
            advanceUntilIdle()

            // Consumers treat any cancelled stop as ending the capture: the superseded native stop must not reach them.
            assertEquals(listOf(VoiceInputEvent.ListeningStarted(mode, 202L)), seen)
        }

    @Test
    fun `late native cancelled stop after zipformer takes over is never emitted`() =
        runTest(dispatcher) {
            coEvery { native.startListening(any(), any(), any(), any()) } coAnswers {
                thirdArg<(Long) -> Unit>().invoke(101L)
                VoiceInputStartResult.Unavailable("native failed")
            }
            zipformerStartsAs(202L)
            val c = controller()
            val seen = eventsOf(c)

            assertEquals(VoiceInputStartResult.Started(202L), c.startListening(mode))
            nativeEvents.emit(VoiceInputEvent.ListeningStopped(mode, 101L, cancelled = true))
            advanceUntilIdle()

            assertEquals(listOf(VoiceInputEvent.ListeningStarted(mode, 202L)), seen)
        }

    @Test
    fun `a previous capture whose backend never settles is retired so the next start proceeds`() =
        runTest(dispatcher) {
            val c = controller()
            assertEquals(VoiceInputStartResult.Started(1L), c.startListening(mode))
            coEvery { native.startListening(any(), any(), any(), any()) } coAnswers {
                thirdArg<(Long) -> Unit>().invoke(2L)
                VoiceInputStartResult.Started(2L)
            }

            // Session 1's backend never emits its terminal, so its teardown never completes.
            val second = async { c.startListening(mode) }
            advanceTimeBy(BACKEND_TEARDOWN_TIMEOUT_MS - 1)
            runCurrent()
            assertFalse(second.isCompleted)
            advanceTimeBy(1)
            runCurrent()

            assertTrue(second.isCompleted, "the next start must not wait on a wedged backend forever")
            assertEquals(VoiceInputStartResult.Started(2L), second.await())
        }

    @Test
    fun `a wedged zipformer after a native hand-off is retired by owner so the next start proceeds`() =
        runTest(dispatcher) {
            zipformerStartsAs(2L)
            val c = controller()
            assertEquals(VoiceInputStartResult.Started(1L), c.startListening(mode))
            nativeEvents.emit(VoiceInputEvent.ListeningStarted(mode, 1L))
            nativeEvents.emit(stalled(1L))
            nativeEvents.emit(VoiceInputEvent.ListeningStopped(mode, 1L))
            advanceUntilIdle()
            // The hand-off re-keys the admission under owner 1 with backend id 2. Zipformer never settles.
            coEvery { native.startListening(any(), any(), any(), any()) } coAnswers {
                thirdArg<(Long) -> Unit>().invoke(3L)
                VoiceInputStartResult.Started(3L)
            }

            val second = async { c.startListening(mode) }
            advanceTimeBy(BACKEND_TEARDOWN_TIMEOUT_MS - 1)
            runCurrent()
            assertFalse(second.isCompleted)
            advanceTimeBy(1)
            runCurrent()

            // Pre-fix, retirement looked up backend id 2 and found nothing, so the loop waited again.
            assertTrue(second.isCompleted, "a wedged hand-off backend must be retired after one bounded wait")
            assertTrue(second.await() is VoiceInputStartResult.Started)
        }

    @Test
    fun `a retired wedged capture gets one synthetic cancelled stop and its late terminal is dropped`() =
        runTest(dispatcher) {
            val c = controller()
            val seen = eventsOf(c)
            assertEquals(VoiceInputStartResult.Started(1L), c.startListening(mode))
            nativeEvents.emit(VoiceInputEvent.ListeningStarted(mode, 1L))
            coEvery { native.startListening(any(), any(), any(), any()) } coAnswers {
                thirdArg<(Long) -> Unit>().invoke(2L)
                VoiceInputStartResult.Started(2L)
            }

            // Session 1 never emits its terminal. Retirement must tell consumers it ended.
            val second = async { c.startListening(mode) }
            advanceTimeBy(BACKEND_TEARDOWN_TIMEOUT_MS)
            runCurrent()
            assertTrue(second.isCompleted)
            assertEquals(
                listOf(VoiceInputEvent.ListeningStopped(mode, 1L, cancelled = true)),
                seen.filterIsInstance<VoiceInputEvent.ListeningStopped>(),
            )

            // The backend's late, uncancelled terminal must not duplicate the stop.
            nativeEvents.emit(VoiceInputEvent.ListeningStopped(mode, 1L))
            advanceUntilIdle()
            assertEquals(
                listOf(VoiceInputEvent.ListeningStopped(mode, 1L, cancelled = true)),
                seen.filterIsInstance<VoiceInputEvent.ListeningStopped>(),
            )
        }

    @Test
    fun `a throw from the native start releases its held terminal and the fallback slot`() = runTest(dispatcher) {
        val c = controller()
        val seen = eventsOf(c)
        coEvery { native.startListening(any(), any(), any(), any()) } coAnswers {
            thirdArg<(Long) -> Unit>().invoke(1L)
            // The native stop is held while the fallback would decide; the throw must not strand it.
            nativeEvents.emit(VoiceInputEvent.ListeningStopped(mode, 1L, cancelled = true))
            throw IllegalStateException("binder died")
        }

        val failure = runCatching { c.startListening(mode) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException, "the native throw must reach the caller")
        advanceUntilIdle()
        assertEquals(
            listOf(VoiceInputEvent.ListeningStopped(mode, 1L, cancelled = true)),
            seen.filterIsInstance<VoiceInputEvent.ListeningStopped>(),
        )
    }

    @Test
    fun `native reservation failure keeps capture activity fenced while zipformer fallback is pending`() =
        runTest(dispatcher) {
            coEvery { native.startListening(any(), any(), any(), any()) } coAnswers {
                thirdArg<(Long) -> Unit>().invoke(101L)
                nativeEvents.emit(VoiceInputEvent.ListeningStopped(mode, 101L, cancelled = true))
                VoiceInputStartResult.Unavailable("native unavailable")
            }
            val fallbackAdmission = CompletableDeferred<Unit>()
            coEvery { sherpa.startListening(any(), any(), any()) } coAnswers {
                fallbackAdmission.await()
                thirdArg<(Long) -> Unit>().invoke(202L)
                VoiceInputStartResult.Started(202L)
            }
            val c = controller()
            val activity = mutableListOf<Boolean>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                c.captureActivity.collect { activity += it }
            }
            advanceUntilIdle()
            val start = async { c.startListening(mode) }
            advanceUntilIdle()

            assertEquals(listOf(false, true), activity)
            assertTrue(c.captureActivity.value)
            fallbackAdmission.complete(Unit)
            assertEquals(VoiceInputStartResult.Started(202L), start.await())
            advanceUntilIdle()
            assertEquals(listOf(false, true), activity)
            sherpaEvents.emit(VoiceInputEvent.ListeningStopped(mode, 202L))
            advanceUntilIdle()

            assertEquals(listOf(false, true, false), activity)
        }

    @Test
    fun `unavailable fallback routes both post-cleanup terminals and retires admissions`() = runTest(dispatcher) {
        coEvery { native.startListening(any(), any(), any(), any()) } coAnswers {
            thirdArg<(Long) -> Unit>().invoke(101L)
            nativeEvents.emit(VoiceInputEvent.ListeningStopped(mode, 101L, cancelled = true))
            VoiceInputStartResult.Unavailable("native failed")
        }
        coEvery { sherpa.startListening(any(), any(), any()) } coAnswers {
            thirdArg<(Long) -> Unit>().invoke(202L)
            sherpaEvents.emit(VoiceInputEvent.ListeningStopped(mode, 202L, cancelled = true))
            VoiceInputStartResult.Unavailable("mic unavailable")
        }
        val c = controller()
        val seen = eventsOf(c)

        assertEquals(VoiceInputStartResult.Unavailable("native failed"), c.startListening(mode))
        advanceUntilIdle()

        assertEquals(
            listOf(
                VoiceInputEvent.ListeningStopped(mode, 101L, cancelled = true),
                VoiceInputEvent.ListeningStopped(mode, 202L, cancelled = true),
            ),
            seen.filterIsInstance<VoiceInputEvent.ListeningStopped>(),
        )
        assertFalse(c.captureActivity.value)
        assertFalse(c.hasCaptureOrStart())
    }

    @Test
    fun `same sherpa replacement retires the remapped fallback owner and keeps the new owner`() =
        runTest(dispatcher) {
            zipformerStartsAs(2L)
            val c = controller()
            val seen = eventsOf(c)

            assertEquals(VoiceInputStartResult.Started(1L), c.startListening(mode))
            nativeEvents.emit(notReady(1L))
            advanceUntilIdle()
            selectedEngine.value = VoiceInputEngine.SherpaZipformer
            every { sherpa.stopListeningForSession(2L) } answers {
                sherpaEvents.tryEmit(VoiceInputEvent.ListeningStopped(mode, 2L))
            }
            coEvery { sherpa.startListening(any(), any(), any()) } coAnswers {
                thirdArg<(Long) -> Unit>().invoke(3L)
                sherpaEvents.emit(VoiceInputEvent.ListeningStarted(mode, 3L))
                VoiceInputStartResult.Started(3L)
            }

            assertEquals(VoiceInputStartResult.Started(3L), c.startListening(mode))
            advanceUntilIdle()

            assertTrue(seen.contains(VoiceInputEvent.ListeningStopped(mode, 1L, cancelled = true)))
            assertTrue(seen.contains(VoiceInputEvent.ListeningStarted(mode, 3L)))
            assertTrue(c.captureActivity.value)
            assertTrue(c.hasCaptureOrStart())
        }

    @Test
    fun `caller cancellation leaves activity until gated teardown terminal then clears it`() = runTest(dispatcher) {
        coEvery { native.startListening(any(), any(), any(), any()) } returns VoiceInputStartResult.Unavailable("no service")
        val (loadCancelled, allowTerminal) = zipformerLoadHangsUntilTerminal()
        val c = controller()
        val seen = eventsOf(c)

        val start = async { c.startListening(mode) }
        advanceUntilIdle()
        start.cancel() // no selector stop; the child performs its terminal cleanup later
        advanceUntilIdle()

        assertTrue(loadCancelled.isCompleted)
        assertTrue(start.isCancelled)
        assertTrue(c.captureActivity.value)
        assertTrue(c.hasCaptureOrStart())
        assertTrue(seen.none { it is VoiceInputEvent.ListeningStopped })

        allowTerminal.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            listOf(VoiceInputEvent.ListeningStopped(mode, 2L, cancelled = true)),
            seen.filterIsInstance<VoiceInputEvent.ListeningStopped>(),
        )
        assertFalse(c.captureActivity.value)
        assertFalse(c.hasCaptureOrStart())
    }

    @Test
    fun `late cancelled terminal from unwound start does not clear replacement owner`() = runTest(dispatcher) {
        coEvery { native.startListening(any(), any(), any(), any()) } returns VoiceInputStartResult.Unavailable("no service")
        val (loadCancelled, allowTerminal) = zipformerLoadHangsUntilTerminal()
        val c = controller()
        val seen = eventsOf(c)

        val abandoned = async { c.startListening(mode) }
        advanceUntilIdle()
        abandoned.cancel()
        advanceUntilIdle()
        assertTrue(loadCancelled.isCompleted)
        assertTrue(abandoned.isCancelled)

        val replacement = async { c.startListening(mode) }
        // The late terminal arrives inside the teardown bound, so the replacement must still be waiting for it.
        advanceTimeBy(BACKEND_TEARDOWN_TIMEOUT_MS - 1)
        runCurrent()
        assertFalse(replacement.isCompleted)
        assertTrue(c.captureActivity.value)

        allowTerminal.complete(Unit)
        advanceUntilIdle()

        assertEquals(VoiceInputStartResult.Started(3L), replacement.await())
        assertEquals(
            listOf(VoiceInputEvent.ListeningStopped(mode, 2L, cancelled = true)),
            seen.filterIsInstance<VoiceInputEvent.ListeningStopped>(),
        )
        assertTrue(seen.contains(VoiceInputEvent.ListeningStarted(mode, 3L)))
        assertTrue(c.captureActivity.value)
        assertTrue(c.hasCaptureOrStart())
    }

    @Test
    fun `terminal before off-main start continuation clears empty generation fence`() = runTest(dispatcher) {
        selectedEngine.value = VoiceInputEngine.SherpaZipformer
        val c = controller()
        val seen = eventsOf(c)
        coEvery { sherpa.startListening(any(), any(), any()) } coAnswers {
            thirdArg<(Long) -> Unit>().invoke(44L)
            sherpaEvents.emit(VoiceInputEvent.ListeningStopped(mode, 44L, cancelled = true))
            VoiceInputStartResult.Started(44L)
        }

        val callerDispatcher = StandardTestDispatcher(testScheduler)
        val start = async(callerDispatcher) { c.startListening(mode) }
        runCurrent()

        assertEquals(VoiceInputStartResult.Started(44L), start.await())
        assertEquals(
            listOf(VoiceInputEvent.ListeningStopped(mode, 44L, cancelled = true)),
            seen.filterIsInstance<VoiceInputEvent.ListeningStopped>(),
        )
        assertFalse(c.captureActivity.value)
        assertFalse(c.hasCaptureOrStart())
    }

    @Test
    fun `stop during start-time zipformer load returns admitted cancellation without ghost start`() = runTest(dispatcher) {
        coEvery { native.startListening(any(), any(), any(), any()) } returns VoiceInputStartResult.Unavailable("no service")
        val loadCancelled = zipformerLoadHangs()
        every { sherpa.cancelListeningForSession(2L) } answers {
            sherpaEvents.tryEmit(VoiceInputEvent.ListeningStopped(mode, 2L, cancelled = true))
        }
        val c = controller()
        val seen = eventsOf(c)

        val start = async { c.startListening(mode) }
        advanceUntilIdle()
        c.stopListening()
        advanceUntilIdle()

        assertEquals(VoiceInputStartResult.Cancelled(2L), start.await())
        assertTrue(loadCancelled.isCompleted)
        assertEquals(listOf(VoiceInputEvent.ListeningStopped(mode, 2L, cancelled = true)), seen)
        io.mockk.verify(exactly = 1) { sherpa.cancelListeningForSession(2L) }
    }

    @Test
    fun `voice event flow never drops or reorders events behind a slow collector`() = runTest(dispatcher) {
        val flow = voiceEventFlow()
        val gate = CompletableDeferred<Unit>()
        val received = mutableListOf<String>()
        backgroundScope.launch {
            flow.collect {
                gate.await()
                received += (it as VoiceInputEvent.PartialTranscript).text
            }
        }

        val accepted = (0 until 1_000).map { flow.tryEmit(VoiceInputEvent.PartialTranscript(mode, "$it", 1L)) }
        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue(accepted.all { it })
        assertEquals((0 until 1_000).map { it.toString() }, received)
    }
}
