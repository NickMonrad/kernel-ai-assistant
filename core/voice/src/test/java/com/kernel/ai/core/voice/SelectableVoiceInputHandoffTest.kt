package com.kernel.ai.core.voice

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.concurrent.thread
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
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
    private val native: NativeAndroidVoiceInputController = mockk()
    private val sherpa: SherpaOnnxVoiceInputController = mockk()
    private val recognitionSupport: AndroidNativeRecognitionSupport = mockk()
    private val nativeEvents = MutableSharedFlow<VoiceInputEvent>(extraBufferCapacity = 64)
    private val sherpaEvents = MutableSharedFlow<VoiceInputEvent>(extraBufferCapacity = 64)
    private val mode = VoiceCaptureMode.AlertCommand

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { voiceInputPreferences.selectedEngine } returns MutableStateFlow(VoiceInputEngine.AndroidNative)
        every { voiceInputPreferences.allowOnlineSpeechFallback } returns MutableStateFlow(false)
        every { native.events } returns nativeEvents
        every { sherpa.events } returns sherpaEvents
        every { native.stopListening() } just runs
        every { sherpa.stopListening() } just runs
        coEvery { sherpa.isSpecAvailable(any()) } returns true
        coEvery { native.startListening(any(), any()) } returns VoiceInputStartResult.Started(1L)
        coEvery { sherpa.startListening(any()) } returns VoiceInputStartResult.Started(2L)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun controller() = SelectableVoiceInputController(
        voiceInputPreferences = voiceInputPreferences,
        nativeAndroidVoiceInputController = native,
        sherpaOnnxVoiceInputController = sherpa,
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
        coEvery { sherpa.startListening(any()) } coAnswers {
            sherpaEvents.emit(VoiceInputEvent.ListeningStarted(mode, id))
            VoiceInputStartResult.Started(id)
        }
    }

    /** Zipformer start that never finishes loading; records whether it was cancelled. */
    private fun zipformerLoadHangs(): CompletableDeferred<Boolean> {
        val cancelled = CompletableDeferred<Boolean>()
        coEvery { sherpa.startListening(any()) } coAnswers {
            try {
                awaitCancellation()
            } catch (e: CancellationException) {
                cancelled.complete(true)
                throw e
            }
        }
        return cancelled
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
        coVerify(exactly = 0) { sherpa.startListening(any()) }
    }

    @Test
    fun `failed hand-off surfaces the native error and stop, and leaves native active`() = runTest(dispatcher) {
        coEvery { sherpa.startListening(any()) } returns VoiceInputStartResult.Unavailable("no model")
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
        coVerify(exactly = 0) { sherpa.startListening(any()) }
    }

    @Test
    fun `stop during the teardown settle never starts zipformer`() = runTest(dispatcher) {
        val c = controller()
        val seen = eventsOf(c)
        val notices = noticesOf(c)

        c.startListening(mode)
        nativeEvents.emit(stalled(1L))
        advanceTimeBy(NATIVE_TEARDOWN_SETTLE_MS / 2)
        c.stopListening()
        advanceUntilIdle()

        coVerify(exactly = 0) { sherpa.startListening(any()) }
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

        thread(name = "not-main") { c.stopListening() }.join()
        advanceUntilIdle()

        assertTrue(loadCancelled.isCompleted)
        assertTrue(seen.none { it is VoiceInputEvent.Error })
        assertTrue(notices.isEmpty())
        // The pipeline is still routing after the cancelled hand-off.
        nativeEvents.emit(VoiceInputEvent.ListeningStopped(mode, 1L))
        advanceUntilIdle()
        assertEquals(listOf(VoiceInputEvent.ListeningStopped(mode, 1L)), seen)
    }

    @Test
    fun `stop after zipformer started but before the hand-off commits stops zipformer`() = runTest(dispatcher) {
        val c = controller()
        val notices = noticesOf(c)
        coEvery { sherpa.startListening(any()) } coAnswers {
            c.stopListening() // lands after Zipformer opened the microphone
            VoiceInputStartResult.Started(2L)
        }

        c.startListening(mode)
        nativeEvents.emit(stalled(1L))
        advanceUntilIdle()

        // activate(native) at start, stopListening itself, and the hand-off's own stop.
        verify(atLeast = 3) { sherpa.stopListening() }
        assertTrue(notices.isEmpty())
    }

    @Test
    fun `newer start supersedes a hand-off in flight without deadlock`() = runTest(dispatcher) {
        val loadCancelled = zipformerLoadHangs()
        val c = controller()
        val notices = noticesOf(c)

        c.startListening(mode)
        nativeEvents.emit(stalled(1L))
        advanceUntilIdle()

        coEvery { native.startListening(any(), any()) } returns VoiceInputStartResult.Started(3L)
        val second = async { c.startListening(mode) }
        advanceUntilIdle()

        assertEquals(VoiceInputStartResult.Started(3L), second.await())
        assertTrue(loadCancelled.isCompleted)
        assertTrue(notices.isEmpty())
    }

    @Test
    fun `stale native error from an earlier session is never handed off`() = runTest(dispatcher) {
        val c = controller()
        val seen = eventsOf(c)

        c.startListening(mode)
        coEvery { native.startListening(any(), any()) } returns VoiceInputStartResult.Started(3L)
        c.startListening(mode)
        nativeEvents.emit(stalled(1L))
        advanceUntilIdle()

        assertEquals(listOf(stalled(1L)), seen)
        coVerify(exactly = 0) { sherpa.startListening(any()) }
        verify(exactly = 0) { native.stopListening() }
    }

    @Test
    fun `native start failure falls back at start, routes zipformer readiness and replays the note`() =
        runTest(dispatcher) {
            coEvery { native.startListening(any(), any()) } returns VoiceInputStartResult.Unavailable("no service")
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
    fun `stop during the start-time zipformer load reports a capture that ended at once`() = runTest(dispatcher) {
        coEvery { native.startListening(any(), any()) } returns VoiceInputStartResult.Unavailable("no service")
        val loadCancelled = zipformerLoadHangs()
        val c = controller()
        val seen = eventsOf(c)

        val start = async { c.startListening(mode) }
        advanceUntilIdle()
        c.stopListening()
        advanceUntilIdle()

        val result = start.await() as VoiceInputStartResult.Started
        assertTrue(loadCancelled.isCompleted)
        assertNotEquals(2L, result.captureSessionId)
        assertEquals(listOf(VoiceInputEvent.ListeningStopped(mode, result.captureSessionId)), seen)
        assertNull(c.latestNoticeFor(result.captureSessionId))
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
