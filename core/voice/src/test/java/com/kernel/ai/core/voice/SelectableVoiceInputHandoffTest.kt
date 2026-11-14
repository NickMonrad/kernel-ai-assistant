package com.kernel.ai.core.voice

import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Android native to Zipformer hand-off, end to end through [SelectableVoiceInputController].
 * Covers the #1853 review blockers: generation guard, speech-progress gate, failed hand-off
 * surfacing, session re-stamping, start-time fallback, and lossless event delivery.
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

    private fun stalled(id: Long) =
        VoiceInputEvent.Error(mode, "Android speech stalled", captureSessionId = id, category = NATIVE_STALLED)

    @Test
    fun `stalled native hands off mid session and re-stamps zipformer events`() = runTest(dispatcher) {
        val c = controller()
        val seen = mutableListOf<VoiceInputEvent>()
        val notices = mutableListOf<VoiceNotice>()
        backgroundScope.launch { c.events.collect { seen += it } }
        backgroundScope.launch { c.notices.collect { notices += it } }
        advanceUntilIdle()

        assertEquals(VoiceInputStartResult.Started(1L), c.startListening(mode))
        nativeEvents.emit(VoiceInputEvent.ListeningStarted(mode, 1L))
        nativeEvents.emit(stalled(1L))
        advanceUntilIdle()
        sherpaEvents.emit(VoiceInputEvent.Transcript(mode, "hello", captureSessionId = 2L))
        advanceUntilIdle()

        assertTrue(seen.none { it is VoiceInputEvent.Error })
        assertEquals(VoiceInputEvent.Transcript(mode, "hello", captureSessionId = 1L), seen.last())
        assertEquals(listOf(VoiceNotice(1L, ZIPFORMER_STALL_NOTICE)), notices)
    }

    @Test
    fun `speech progress blocks hand-off so the native error is surfaced`() = runTest(dispatcher) {
        val c = controller()
        val seen = mutableListOf<VoiceInputEvent>()
        backgroundScope.launch { c.events.collect { seen += it } }
        advanceUntilIdle()

        c.startListening(mode)
        nativeEvents.emit(VoiceInputEvent.SpeechDetected(mode, 1L))
        nativeEvents.emit(stalled(1L))
        advanceUntilIdle()

        assertTrue(seen.any { it is VoiceInputEvent.Error && it.captureSessionId == 1L })
        coVerifyNoZipformerStart()
    }

    @Test
    fun `failed hand-off surfaces the native error and leaves native active`() = runTest(dispatcher) {
        coEvery { sherpa.startListening(any()) } returns VoiceInputStartResult.Unavailable("no model")
        val c = controller()
        val seen = mutableListOf<VoiceInputEvent>()
        backgroundScope.launch { c.events.collect { seen += it } }
        advanceUntilIdle()

        c.startListening(mode)
        nativeEvents.emit(stalled(1L))
        advanceUntilIdle()

        assertEquals(listOf(stalled(1L)), seen)
    }

    @Test
    fun `stop during hand-off stops the late zipformer start and emits nothing`() = runTest(dispatcher) {
        val gate = CompletableDeferred<VoiceInputStartResult>()
        coEvery { sherpa.startListening(any()) } coAnswers { gate.await() }
        val c = controller()
        val seen = mutableListOf<VoiceInputEvent>()
        val notices = mutableListOf<VoiceNotice>()
        backgroundScope.launch { c.events.collect { seen += it } }
        backgroundScope.launch { c.notices.collect { notices += it } }
        advanceUntilIdle()

        c.startListening(mode)
        nativeEvents.emit(stalled(1L))
        advanceUntilIdle() // hand-off is now suspended in Zipformer start

        c.stopListening()
        gate.complete(VoiceInputStartResult.Started(2L))
        advanceUntilIdle()

        verify(atLeast = 2) { sherpa.stopListening() }
        assertTrue(seen.none { it is VoiceInputEvent.Error })
        assertTrue(notices.isEmpty())
    }

    @Test
    fun `native start failure falls back to zipformer at start and notes it`() = runTest(dispatcher) {
        coEvery { native.startListening(any(), any()) } returns VoiceInputStartResult.Unavailable("no service")
        val c = controller()
        val notices = mutableListOf<VoiceNotice>()
        backgroundScope.launch { c.notices.collect { notices += it } }
        advanceUntilIdle()

        assertEquals(VoiceInputStartResult.Started(2L), c.startListening(mode))
        advanceUntilIdle()

        assertEquals(listOf(VoiceNotice(2L, ZIPFORMER_START_NOTICE)), notices)
    }

    @Test
    fun `event queue delivers every event in order`() = runTest(dispatcher) {
        val queue = VoiceEventQueue(backgroundScope)
        val received = mutableListOf<String>()
        backgroundScope.launch {
            queue.events.collect { received += (it as VoiceInputEvent.PartialTranscript).text }
        }
        advanceUntilIdle()

        repeat(200) { queue.tryEmit(VoiceInputEvent.PartialTranscript(mode, "$it", 1L)) }
        advanceUntilIdle()

        assertEquals((0 until 200).map { it.toString() }, received)
    }

    private fun coVerifyNoZipformerStart() {
        io.mockk.coVerify(exactly = 0) { sherpa.startListening(any()) }
    }
}
