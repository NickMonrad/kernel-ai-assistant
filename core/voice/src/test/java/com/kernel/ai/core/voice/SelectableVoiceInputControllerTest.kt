package com.kernel.ai.core.voice

import com.kernel.ai.core.inference.diagnostics.JandalEvents
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class SelectableVoiceInputControllerTest {
    private val dispatcher = StandardTestDispatcher()
    private val voiceInputPreferences: VoiceInputPreferences = mockk()
    private val nativeAndroidVoiceInputController: NativeAndroidVoiceInputController = mockk()
    private val sherpaOnnxVoiceInputController: SherpaOnnxVoiceInputController = mockk()
    private val asr4allVoiceInputController: Asr4allVoiceInputController = mockk()
    private val recognitionSupport: AndroidNativeRecognitionSupport = mockk()
    private val nativeEvents = MutableSharedFlow<VoiceInputEvent>()
    private val sherpaEvents = MutableSharedFlow<VoiceInputEvent>()
    private val asr4allEvents = MutableSharedFlow<VoiceInputEvent>()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { voiceInputPreferences.allowOnlineSpeechFallback } returns MutableStateFlow(false)
        every { nativeAndroidVoiceInputController.events } returns nativeEvents
        every { sherpaOnnxVoiceInputController.events } returns sherpaEvents
        every { asr4allVoiceInputController.events } returns asr4allEvents
        every { asr4allVoiceInputController.stopListening() } just runs
        every { asr4allVoiceInputController.supersedeCapture() } just runs
        every { asr4allVoiceInputController.admissionEpochNow() } returns 0L
        coEvery { asr4allVoiceInputController.releaseEngine() } just runs
        every { nativeAndroidVoiceInputController.stopListeningForSession(any()) } just runs
        every { nativeAndroidVoiceInputController.cancelListeningForSession(any()) } just runs
        every { sherpaOnnxVoiceInputController.stopListeningForSession(any()) } just runs
        every { sherpaOnnxVoiceInputController.cancelListeningForSession(any()) } just runs
        every { nativeAndroidVoiceInputController.stopListening() } just runs
        every { sherpaOnnxVoiceInputController.stopListening() } just runs
        coEvery { nativeAndroidVoiceInputController.startListening(any(), any(), any(), any()) } coAnswers {
            thirdArg<(Long) -> Unit>().invoke(1L)
            VoiceInputStartResult.Started(1L)
        }
        coEvery { sherpaOnnxVoiceInputController.startListening(any(), any(), any()) } coAnswers {
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
        nativeAndroidVoiceInputController = nativeAndroidVoiceInputController,
        sherpaOnnxVoiceInputController = sherpaOnnxVoiceInputController,
        asr4allVoiceInputController = asr4allVoiceInputController,
        androidNativeRecognitionSupport = recognitionSupport,
    )

    private fun selectEngine(engine: VoiceInputEngine) {
        every { voiceInputPreferences.selectedEngine } returns MutableStateFlow(engine)
    }

    private fun nativeAvailability(
        onDevice: Boolean,
        localeStatus: AndroidNativeRecognitionLocaleStatus = AndroidNativeRecognitionLocaleStatus.Ready,
    ) = AndroidNativeRecognitionAvailability(
        isRecognitionAvailable = true,
        isOnDeviceRecognitionAvailable = onDevice,
        languageTag = "en-NZ",
        languageDisplayName = "English (New Zealand)",
        localeStatus = localeStatus,
    )

    // ── Auto routing (pure) ─────────────────────────────────────────────────

    @Test
    fun `auto prefers zipformer when the online fallback is off`() {
        assertEquals(
            AutoSttRoute.Zipformer,
            resolveAutoSttRoute(nativeOnDeviceReady = true, zipformerReady = true, allowOnlineFallback = false),
        )
    }

    @Test
    fun `auto uses android native when the online fallback is allowed`() {
        assertEquals(
            AutoSttRoute.Native,
            resolveAutoSttRoute(nativeOnDeviceReady = true, zipformerReady = true, allowOnlineFallback = true),
        )
    }

    @Test
    fun `auto uses on-device native while zipformer downloads`() {
        assertEquals(
            AutoSttRoute.Native,
            resolveAutoSttRoute(nativeOnDeviceReady = true, zipformerReady = false, allowOnlineFallback = false),
        )
    }

    @Test
    fun `auto uses zipformer when native is unavailable even with online fallback allowed`() {
        assertEquals(
            AutoSttRoute.Zipformer,
            resolveAutoSttRoute(nativeOnDeviceReady = false, zipformerReady = true, allowOnlineFallback = true),
        )
    }

    @Test
    fun `auto reports unavailable when neither recognizer is ready`() {
        assertEquals(
            AutoSttRoute.Unavailable,
            resolveAutoSttRoute(nativeOnDeviceReady = false, zipformerReady = false, allowOnlineFallback = true),
        )
    }

    // ── Controller wiring ───────────────────────────────────────────────────

    @Test
    fun `auto starts zipformer by default without checking native support`() = runTest(dispatcher) {
        selectEngine(VoiceInputEngine.Auto)
        coEvery { sherpaOnnxVoiceInputController.isSpecAvailable(SherpaSttModelSpec.ZIPFORMER) } returns true

        assertEquals(VoiceInputStartResult.Started(2L), controller().startListening(VoiceCaptureMode.Command))

        coVerify(exactly = 1) { sherpaOnnxVoiceInputController.startListening(VoiceCaptureMode.Command, any(), any()) }
        coVerify(exactly = 0) { nativeAndroidVoiceInputController.startListening(any(), any(), any(), any()) }
        verify(exactly = 0) { recognitionSupport.getCaptureAvailability() }
    }

    @Test
    fun `auto starts on-device native without platform fallback while zipformer downloads`() = runTest(dispatcher) {
        selectEngine(VoiceInputEngine.Auto)
        every { recognitionSupport.getCaptureAvailability() } returns nativeAvailability(onDevice = true)
        coEvery { sherpaOnnxVoiceInputController.isSpecAvailable(SherpaSttModelSpec.ZIPFORMER) } returns false

        assertEquals(VoiceInputStartResult.Started(1L), controller().startListening(VoiceCaptureMode.Command))

        coVerify(exactly = 1) { nativeAndroidVoiceInputController.startListening(VoiceCaptureMode.Command, false, any(), any()) }
    }

    @Test
    fun `auto passes the online fallback setting to native`() = runTest(dispatcher) {
        selectEngine(VoiceInputEngine.Auto)
        every { voiceInputPreferences.allowOnlineSpeechFallback } returns MutableStateFlow(true)
        every { recognitionSupport.getCaptureAvailability() } returns nativeAvailability(onDevice = true)
        coEvery { sherpaOnnxVoiceInputController.isSpecAvailable(SherpaSttModelSpec.ZIPFORMER) } returns true

        controller().startListening(VoiceCaptureMode.Command)

        coVerify(exactly = 1) { nativeAndroidVoiceInputController.startListening(VoiceCaptureMode.Command, true, any(), any()) }
    }

    @Test
    fun `auto with online fallback uses zipformer when on-device native is unavailable`() = runTest(dispatcher) {
        selectEngine(VoiceInputEngine.Auto)
        every { voiceInputPreferences.allowOnlineSpeechFallback } returns MutableStateFlow(true)
        every { recognitionSupport.getCaptureAvailability() } returns nativeAvailability(onDevice = false)
        coEvery { sherpaOnnxVoiceInputController.isSpecAvailable(SherpaSttModelSpec.ZIPFORMER) } returns true

        assertEquals(VoiceInputStartResult.Started(2L), controller().startListening(VoiceCaptureMode.Command))

        coVerify(exactly = 1) { sherpaOnnxVoiceInputController.startListening(VoiceCaptureMode.Command, any(), any()) }
        coVerify(exactly = 0) { nativeAndroidVoiceInputController.startListening(any(), any(), any(), any()) }
    }

    @Test
    fun `auto runs the live locale check when no cached answer exists`() = runTest(dispatcher) {
        selectEngine(VoiceInputEngine.Auto)
        every { recognitionSupport.getCaptureAvailability() } returns
            nativeAvailability(onDevice = true, localeStatus = AndroidNativeRecognitionLocaleStatus.Unknown)
        coEvery { recognitionSupport.getAvailability() } returns nativeAvailability(onDevice = true)
        coEvery { sherpaOnnxVoiceInputController.isSpecAvailable(SherpaSttModelSpec.ZIPFORMER) } returns false

        controller().startListening(VoiceCaptureMode.Command)

        coVerify(exactly = 1) { recognitionSupport.getAvailability() }
        coVerify(exactly = 1) { nativeAndroidVoiceInputController.startListening(VoiceCaptureMode.Command, false, any(), any()) }
    }


    @Test
    fun `cancelled native admission remains owned until its matching terminal`() = runTest(dispatcher) {
        selectEngine(VoiceInputEngine.AndroidNative)
        coEvery { sherpaOnnxVoiceInputController.isSpecAvailable(any()) } returns false
        var starts = 0L
        coEvery { nativeAndroidVoiceInputController.startListening(any(), any(), any(), any()) } coAnswers {
            val id = ++starts
            thirdArg<(Long) -> Unit>().invoke(id)
            if (id == 1L) VoiceInputStartResult.Cancelled(id)
            else VoiceInputStartResult.Started(id)
        }
        val c = controller()
        val seen = mutableListOf<VoiceInputEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.events.collect { seen += it } }
        advanceUntilIdle()

        assertEquals(VoiceInputStartResult.Cancelled(1L), c.startListening(VoiceCaptureMode.Command))
        assertTrue(c.captureActivity.value)
        c.stopListening(1L)
        nativeEvents.emit(VoiceInputEvent.ListeningStopped(VoiceCaptureMode.Command, 1L, cancelled = true))
        advanceUntilIdle()
        assertFalse(c.captureActivity.value)
        assertEquals(VoiceInputStartResult.Started(2L), c.startListening(VoiceCaptureMode.Command))

        c.stopListening(1L)
        nativeEvents.emit(VoiceInputEvent.ListeningStarted(VoiceCaptureMode.Command, 2L))
        advanceUntilIdle()
        assertTrue(c.hasCaptureOrStart())
        assertEquals(listOf(2L), seen.filterIsInstance<VoiceInputEvent.ListeningStarted>().map { it.captureSessionId })
        verify(exactly = 1) { nativeAndroidVoiceInputController.cancelListeningForSession(1L) }
    }

    @Test
    fun `direct Sherpa waits for the previous native session teardown`() = runTest(dispatcher) {
        val nativeEvents = MutableSharedFlow<VoiceInputEvent>(extraBufferCapacity = 1)
        every { nativeAndroidVoiceInputController.events } returns nativeEvents
        val selected = MutableStateFlow(VoiceInputEngine.AndroidNative)
        every { voiceInputPreferences.selectedEngine } returns selected
        val nativeStops = mutableListOf<Long>()
        every { nativeAndroidVoiceInputController.stopListeningForSession(any()) } answers {
            nativeStops += firstArg<Long>()
        }
        coEvery { nativeAndroidVoiceInputController.startListening(any(), any(), any(), any()) } coAnswers {
            thirdArg<(Long) -> Unit>().invoke(1L)
            VoiceInputStartResult.Started(1L)
        }
        coEvery { sherpaOnnxVoiceInputController.startListening(any(), any(), any()) } coAnswers {
            thirdArg<(Long) -> Unit>().invoke(2L)
            sherpaEvents.emit(VoiceInputEvent.ListeningStarted(VoiceCaptureMode.Command, 2L))
            sherpaEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "sherpa final", 2L))
            VoiceInputStartResult.Started(2L)
        }
        val c = controller()
        val seen = mutableListOf<VoiceInputEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.events.collect { seen += it } }
        advanceUntilIdle()

        assertEquals(VoiceInputStartResult.Started(1L), c.startListening(VoiceCaptureMode.Command))
        selected.value = VoiceInputEngine.SherpaZipformer
        val replacement = async { c.startListening(VoiceCaptureMode.Command) }
        runCurrent()

        assertFalse(replacement.isCompleted)
        assertEquals(listOf(1L), nativeStops)
        coVerify(exactly = 0) { sherpaOnnxVoiceInputController.startListening(any(), any(), any()) }

        nativeEvents.emit(VoiceInputEvent.ListeningStopped(VoiceCaptureMode.Command, 1L))
        runCurrent()
        advanceTimeBy(350)
        runCurrent()
        coVerify(exactly = 0) { sherpaOnnxVoiceInputController.startListening(any(), any(), any()) }
        advanceUntilIdle()
        runCurrent()
        assertEquals(VoiceInputStartResult.Started(2L), replacement.await())
        advanceUntilIdle()

        assertTrue(seen.contains(VoiceInputEvent.ListeningStarted(VoiceCaptureMode.Command, 2L)))
        assertTrue(seen.contains(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "sherpa final", 2L)))
        verify(exactly = 0) { nativeAndroidVoiceInputController.stopListening() }
    }

    @Test
    fun `newer start supersedes direct local handover during native settle`() = runTest(dispatcher) {
        val nativeEvents = MutableSharedFlow<VoiceInputEvent>(extraBufferCapacity = 1)
        every { nativeAndroidVoiceInputController.events } returns nativeEvents
        val selected = MutableStateFlow(VoiceInputEngine.AndroidNative)
        every { voiceInputPreferences.selectedEngine } returns selected
        every { nativeAndroidVoiceInputController.stopListeningForSession(any()) } just runs
        coEvery { nativeAndroidVoiceInputController.startListening(any(), any(), any(), any()) } coAnswers {
            thirdArg<(Long) -> Unit>().invoke(1L)
            VoiceInputStartResult.Started(1L)
        }
        var sherpaStarts = 0
        coEvery { sherpaOnnxVoiceInputController.startListening(any(), any(), any()) } coAnswers {
            sherpaStarts++
            thirdArg<(Long) -> Unit>().invoke(2L)
            VoiceInputStartResult.Started(2L)
        }
        val c = controller()
        advanceUntilIdle()
        assertEquals(VoiceInputStartResult.Started(1L), c.startListening(VoiceCaptureMode.Command))

        selected.value = VoiceInputEngine.SherpaZipformer
        val stale = async { c.startListening(VoiceCaptureMode.Command) }
        runCurrent()
        nativeEvents.emit(VoiceInputEvent.ListeningStopped(VoiceCaptureMode.Command, 1L))
        runCurrent()
        assertFalse(stale.isCompleted)

        val latest = async { c.startListening(VoiceCaptureMode.Command) }
        runCurrent()
        advanceTimeBy(NATIVE_TEARDOWN_SETTLE_MS)
        runCurrent()

        assertEquals(VoiceInputStartResult.Cancelled(), stale.await())
        assertEquals(VoiceInputStartResult.Started(2L), latest.await())
        assertEquals(1, sherpaStarts)
    }

    @Test
    fun `cancelled local-start waiter leaves native settle deadline for replacement`() = runTest(dispatcher) {
        val nativeEvents = MutableSharedFlow<VoiceInputEvent>(extraBufferCapacity = 1)
        every { nativeAndroidVoiceInputController.events } returns nativeEvents
        val selected = MutableStateFlow(VoiceInputEngine.AndroidNative)
        every { voiceInputPreferences.selectedEngine } returns selected
        every { nativeAndroidVoiceInputController.stopListeningForSession(any()) } just runs
        coEvery { nativeAndroidVoiceInputController.startListening(any(), any(), any(), any()) } coAnswers {
            thirdArg<(Long) -> Unit>().invoke(1L)
            VoiceInputStartResult.Started(1L)
        }
        var sherpaStarts = 0
        coEvery { sherpaOnnxVoiceInputController.startListening(any(), any(), any()) } coAnswers {
            sherpaStarts++
            thirdArg<(Long) -> Unit>().invoke(2L)
            VoiceInputStartResult.Started(2L)
        }
        val c = controller()
        advanceUntilIdle()
        assertEquals(VoiceInputStartResult.Started(1L), c.startListening(VoiceCaptureMode.Command))

        selected.value = VoiceInputEngine.SherpaZipformer
        val first = async { c.startListening(VoiceCaptureMode.Command) }
        runCurrent()
        nativeEvents.emit(VoiceInputEvent.ListeningStopped(VoiceCaptureMode.Command, 1L))
        runCurrent()
        advanceTimeBy(200)
        runCurrent()
        first.cancel()
        runCurrent()
        assertTrue(first.isCancelled)

        val replacement = async { c.startListening(VoiceCaptureMode.Command) }
        runCurrent()
        assertFalse(replacement.isCompleted)
        assertEquals(0, sherpaStarts)
        advanceTimeBy(150)
        runCurrent()
        assertFalse(replacement.isCompleted)
        assertEquals(0, sherpaStarts)

        advanceUntilIdle()
        assertEquals(VoiceInputStartResult.Started(2L), replacement.await())
        assertEquals(1, sherpaStarts)
    }

    @Test
    fun `drained native terminal still fences the next direct local start`() = runTest(dispatcher) {
        val nativeEvents = MutableSharedFlow<VoiceInputEvent>(extraBufferCapacity = 1)
        every { nativeAndroidVoiceInputController.events } returns nativeEvents
        val selected = MutableStateFlow(VoiceInputEngine.AndroidNative)
        every { voiceInputPreferences.selectedEngine } returns selected
        coEvery { nativeAndroidVoiceInputController.startListening(any(), any(), any(), any()) } coAnswers {
            thirdArg<(Long) -> Unit>().invoke(1L)
            VoiceInputStartResult.Started(1L)
        }
        var sherpaStarts = 0
        coEvery { sherpaOnnxVoiceInputController.startListening(any(), any(), any()) } coAnswers {
            sherpaStarts++
            thirdArg<(Long) -> Unit>().invoke(2L)
            VoiceInputStartResult.Started(2L)
        }
        val c = controller()
        advanceUntilIdle()
        assertEquals(VoiceInputStartResult.Started(1L), c.startListening(VoiceCaptureMode.Command))

        nativeEvents.emit(VoiceInputEvent.ListeningStopped(VoiceCaptureMode.Command, 1L))
        runCurrent()
        assertFalse(c.hasCaptureOrStart())
        selected.value = VoiceInputEngine.SherpaZipformer

        val replacement = async { c.startListening(VoiceCaptureMode.Command) }
        runCurrent()
        assertFalse(replacement.isCompleted)
        advanceTimeBy(350)
        runCurrent()
        assertFalse(replacement.isCompleted)
        assertEquals(0, sherpaStarts)

        advanceUntilIdle()
        assertEquals(VoiceInputStartResult.Started(2L), replacement.await())
        assertEquals(1, sherpaStarts)
    }

    @Test
    fun `new selector attempt invalidates native start waiting on readiness`() = runTest(dispatcher) {
        selectEngine(VoiceInputEngine.AndroidNative)
        val firstPredicate = CompletableDeferred<() -> Boolean>()
        val releaseReadiness = CompletableDeferred<Unit>()
        val predicate = slot<() -> Boolean>()
        var starts = 0
        coEvery { nativeAndroidVoiceInputController.startListening(any(), any(), any(), capture(predicate)) } coAnswers {
            val attempt = ++starts
            val isWanted = predicate.captured
            if (attempt == 1) {
                firstPredicate.complete(isWanted)
                releaseReadiness.await()
                if (!isWanted()) return@coAnswers VoiceInputStartResult.Cancelled()
            }
            val sessionId = attempt.toLong()
            thirdArg<(Long) -> Unit>().invoke(sessionId)
            VoiceInputStartResult.Started(sessionId)
        }
        val c = controller()

        val first = async { c.startListening(VoiceCaptureMode.Command) }
        runCurrent()
        val firstIsWanted = firstPredicate.await()

        val replacement = async { c.startListening(VoiceCaptureMode.Command) }
        runCurrent()
        assertFalse(firstIsWanted())
        releaseReadiness.complete(Unit)
        runCurrent()

        assertEquals(VoiceInputStartResult.Cancelled(), first.await())
        assertEquals(VoiceInputStartResult.Started(2L), replacement.await())
        assertEquals(2, starts)
    }

    @Test
    fun `direct Sherpa route after asr4all stops the old backend and routes readiness`() = runTest(dispatcher) {
        val selected = MutableStateFlow(VoiceInputEngine.Asr4all)
        every { voiceInputPreferences.selectedEngine } returns selected
        coEvery { asr4allVoiceInputController.startListening(any(), any(), any()) } coAnswers {
            thirdArg<(Long) -> Unit>().invoke(91L)
            VoiceInputStartResult.Started(91L)
        }
        coEvery { sherpaOnnxVoiceInputController.startListening(any(), any(), any()) } coAnswers {
            thirdArg<(Long) -> Unit>().invoke(92L)
            sherpaEvents.emit(VoiceInputEvent.ListeningStarted(VoiceCaptureMode.Command, 92L))
            VoiceInputStartResult.Started(92L)
        }
        val c = controller()
        val seen = mutableListOf<VoiceInputEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.events.collect { seen += it } }
        advanceUntilIdle()

        assertEquals(VoiceInputStartResult.Started(91L), c.startListening(VoiceCaptureMode.Command))
        selected.value = VoiceInputEngine.SherpaZipformer
        assertEquals(VoiceInputStartResult.Started(92L), c.startListening(VoiceCaptureMode.Command))
        advanceUntilIdle()

        assertTrue(seen.contains(VoiceInputEvent.ListeningStarted(VoiceCaptureMode.Command, 92L)))
        coVerify(atLeast = 1) { asr4allVoiceInputController.releaseEngine() }
        verify(exactly = 0) { asr4allVoiceInputController.stopListening() }
    }

    @Test
    fun `direct native route waits for the previous Sherpa teardown`() = runTest(dispatcher) {
        selectEngine(VoiceInputEngine.SherpaZipformer)
        val sherpaStops = mutableListOf<Long>()
        every { sherpaOnnxVoiceInputController.stopListeningForSession(any()) } answers {
            sherpaStops += firstArg<Long>()
        }
        coEvery { sherpaOnnxVoiceInputController.startListening(any(), any(), any()) } coAnswers {
            thirdArg<(Long) -> Unit>().invoke(2L)
            VoiceInputStartResult.Started(2L)
        }
        coEvery { nativeAndroidVoiceInputController.startListening(any(), any(), any(), any()) } coAnswers {
            thirdArg<(Long) -> Unit>().invoke(3L)
            VoiceInputStartResult.Started(3L)
        }
        val c = controller()
        advanceUntilIdle()
        assertEquals(VoiceInputStartResult.Started(2L), c.startListening(VoiceCaptureMode.Command))

        selectEngine(VoiceInputEngine.AndroidNative)
        val replacement = async { c.startListening(VoiceCaptureMode.Command) }
        runCurrent()

        assertFalse(replacement.isCompleted)
        assertEquals(listOf(2L), sherpaStops)
        coVerify(exactly = 0) { nativeAndroidVoiceInputController.startListening(any(), any(), any(), any()) }

        sherpaEvents.emit(VoiceInputEvent.ListeningStopped(VoiceCaptureMode.Command, 2L))
        runCurrent()
        assertEquals(VoiceInputStartResult.Started(3L), replacement.await())
    }

    @Test
    fun `direct asr4all route waits for the previous Sherpa teardown`() = runTest(dispatcher) {
        selectEngine(VoiceInputEngine.SherpaZipformer)
        val sherpaStops = mutableListOf<Long>()
        every { sherpaOnnxVoiceInputController.stopListeningForSession(any()) } answers {
            sherpaStops += firstArg<Long>()
        }
        coEvery { sherpaOnnxVoiceInputController.startListening(any(), any(), any()) } coAnswers {
            thirdArg<(Long) -> Unit>().invoke(2L)
            VoiceInputStartResult.Started(2L)
        }
        coEvery { asr4allVoiceInputController.startListening(any(), any(), any()) } coAnswers {
            thirdArg<(Long) -> Unit>().invoke(3L)
            VoiceInputStartResult.Started(3L)
        }
        val c = controller()
        advanceUntilIdle()
        assertEquals(VoiceInputStartResult.Started(2L), c.startListening(VoiceCaptureMode.Command))

        selectEngine(VoiceInputEngine.Asr4all)
        val replacement = async { c.startListening(VoiceCaptureMode.Command) }
        runCurrent()

        assertFalse(replacement.isCompleted)
        assertEquals(listOf(2L), sherpaStops)
        coVerify(exactly = 0) { asr4allVoiceInputController.startListening(any(), any(), any()) }

        sherpaEvents.emit(VoiceInputEvent.ListeningStopped(VoiceCaptureMode.Command, 2L))
        runCurrent()
        assertEquals(VoiceInputStartResult.Started(3L), replacement.await())
    }

    @Test
    fun `engine selection change during teardown prevents the stale replacement start`() = runTest(dispatcher) {
        val selected = MutableStateFlow(VoiceInputEngine.SherpaZipformer)
        every { voiceInputPreferences.selectedEngine } returns selected
        every { sherpaOnnxVoiceInputController.stopListeningForSession(any()) } just runs
        coEvery { sherpaOnnxVoiceInputController.startListening(any(), any(), any()) } coAnswers {
            thirdArg<(Long) -> Unit>().invoke(2L)
            VoiceInputStartResult.Started(2L)
        }
        val c = controller()
        advanceUntilIdle()
        assertEquals(VoiceInputStartResult.Started(2L), c.startListening(VoiceCaptureMode.Command))

        selected.value = VoiceInputEngine.AndroidNative
        val replacement = async { c.startListening(VoiceCaptureMode.Command) }
        runCurrent()
        assertFalse(replacement.isCompleted)

        selected.value = VoiceInputEngine.Asr4all
        sherpaEvents.emit(VoiceInputEvent.ListeningStopped(VoiceCaptureMode.Command, 2L))
        runCurrent()

        assertEquals(VoiceInputStartResult.Cancelled(), replacement.await())
        coVerify(exactly = 0) { nativeAndroidVoiceInputController.startListening(any(), any(), any(), any()) }
        coVerify(exactly = 0) { asr4allVoiceInputController.startListening(any(), any(), any()) }
        assertFalse(c.captureActivity.value)
    }

    @Test

    fun `failed sherpa admission retires its id without clearing a later capture`() = runTest(dispatcher) {
        selectEngine(VoiceInputEngine.SherpaZipformer)
        var starts = 2L
        coEvery { sherpaOnnxVoiceInputController.startListening(any(), any(), any()) } coAnswers {
            val id = ++starts
            thirdArg<(Long) -> Unit>().invoke(id)
            if (id == 3L) VoiceInputStartResult.Unavailable("startup failed")
            else VoiceInputStartResult.Started(id)
        }
        val c = controller()
        val seen = mutableListOf<VoiceInputEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.events.collect { seen += it } }
        advanceUntilIdle()

        assertEquals(
            VoiceInputStartResult.Unavailable("startup failed"),
            c.startListening(VoiceCaptureMode.Command),
        )
        assertFalse(c.captureActivity.value)
        assertEquals(VoiceInputStartResult.Started(4L), c.startListening(VoiceCaptureMode.Command))

        c.stopListening(3L)
        sherpaEvents.emit(VoiceInputEvent.ListeningStarted(VoiceCaptureMode.Command, 4L))
        advanceUntilIdle()
        assertTrue(c.hasCaptureOrStart())
        assertEquals(listOf(4L), seen.filterIsInstance<VoiceInputEvent.ListeningStarted>().map { it.captureSessionId })
        verify(exactly = 0) { sherpaOnnxVoiceInputController.stopListeningForSession(3L) }
    }
    @Test
    fun `auto reports unavailable while the offline model downloads`() = runTest(dispatcher) {
        selectEngine(VoiceInputEngine.Auto)
        every { recognitionSupport.getCaptureAvailability() } returns nativeAvailability(onDevice = false)
        coEvery { sherpaOnnxVoiceInputController.isSpecAvailable(SherpaSttModelSpec.ZIPFORMER) } returns false

        assertEquals(
            VoiceInputStartResult.Unavailable(AUTO_STT_UNAVAILABLE_MESSAGE),
            controller().startListening(VoiceCaptureMode.Command),
        )
    }

    @Test
    fun `explicit android native engine passes the online fallback setting`() = runTest(dispatcher) {
        selectEngine(VoiceInputEngine.AndroidNative)

        controller().startListening(VoiceCaptureMode.Command)

        coVerify(exactly = 1) { nativeAndroidVoiceInputController.startListening(VoiceCaptureMode.Command, false, any(), any()) }
        verify(exactly = 0) { sherpaOnnxVoiceInputController.stopListening() }
    }

    @Test
    fun `user stop reaches the asr4all capture`() {
        controller().stopListening()

        verify(exactly = 1) { asr4allVoiceInputController.stopListening() }
    }

    @Test
    fun `switching from asr4all waits for release before the native engine starts`() = runTest(dispatcher) {
        selectEngine(VoiceInputEngine.AndroidNative)

        controller().startListening(VoiceCaptureMode.Command)

        coVerifyOrder {
            asr4allVoiceInputController.releaseEngine()
            nativeAndroidVoiceInputController.startListening(VoiceCaptureMode.Command, any(), any(), any())
        }
    }

    @Test
    fun `every sherpa engine selection starts the sherpa controller`() = runTest(dispatcher) {
        VoiceInputEngine.entries.filter { it.isSherpaFamily }.forEach { engine ->
            selectEngine(engine)
            controller().startListening(VoiceCaptureMode.Command)
        }
        val sherpaCount = VoiceInputEngine.entries.count { it.isSherpaFamily }
        coVerify(exactly = sherpaCount) { sherpaOnnxVoiceInputController.startListening(VoiceCaptureMode.Command, any(), any()) }
        coVerify(exactly = 0) { nativeAndroidVoiceInputController.startListening(any(), any(), any(), any()) }
    }

    @Test
    fun `events only flow from the selected controller`() = runTest(dispatcher) {
        selectEngine(VoiceInputEngine.AndroidNative)
        val received = mutableListOf<VoiceInputEvent>()
        val controller = controller()
        val collectJob = launch { controller.events.collect { received += it } }

        controller.startListening(VoiceCaptureMode.Command)
        advanceUntilIdle()
        sherpaEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "stale sherpa", 2L))
        nativeEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "fresh native", 1L))
        advanceUntilIdle()

        assertEquals(listOf(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "fresh native", 1L)), received)
        collectJob.cancel()
    }

    @Test
    fun `events receive synchronous startup error from newly selected controller`() = runTest(dispatcher) {
        selectEngine(VoiceInputEngine.AndroidNative)
        coEvery { nativeAndroidVoiceInputController.startListening(VoiceCaptureMode.Command, any(), any(), any()) } coAnswers {
            thirdArg<(Long) -> Unit>().invoke(1L)
            nativeEvents.emit(VoiceInputEvent.Error(mode = VoiceCaptureMode.Command, message = "startup failed", captureSessionId = 1L))
            VoiceInputStartResult.Started(1L)
        }
        val received = mutableListOf<VoiceInputEvent>()
        val controller = controller()
        val collectJob = launch { controller.events.collect { received += it } }
        advanceUntilIdle()

        controller.startListening(VoiceCaptureMode.Command)
        advanceUntilIdle()

        assertEquals(
            listOf(VoiceInputEvent.Error(mode = VoiceCaptureMode.Command, message = "startup failed", captureSessionId = 1L)),
            received,
        )
        collectJob.cancel()
    }

    @Test
    fun `stopListening stops every controller to avoid orphaned sessions`() = runTest(dispatcher) {
        selectEngine(VoiceInputEngine.Auto)

        controller().stopListening()

        verify(exactly = 1) { nativeAndroidVoiceInputController.stopListening() }
        verify(exactly = 1) { sherpaOnnxVoiceInputController.stopListening() }
    }

    // ── Wake verification ───────────────────────────────────────────────────

    @Test
    fun `transcribeBlocking forwards the wake-window PCM exactly once to the Sherpa verifier`() = runTest(dispatcher) {
        selectEngine(VoiceInputEngine.AndroidNative)
        val pcm = shortArrayOf(1, 2, 3)
        coEvery { sherpaOnnxVoiceInputController.transcribeBlocking(pcm) } returns "hey jandal"

        assertEquals("hey jandal", controller().transcribeBlocking(pcm))
        coVerify(exactly = 1) { sherpaOnnxVoiceInputController.transcribeBlocking(pcm) }
    }

    @Test
    fun `transcribeBlocking propagates null truthfully`() = runTest(dispatcher) {
        selectEngine(VoiceInputEngine.AndroidNative)
        val pcm = shortArrayOf(4, 5)
        coEvery { sherpaOnnxVoiceInputController.transcribeBlocking(pcm) } returns null

        assertNull(controller().transcribeBlocking(pcm))
    }

    @Test
    fun `transcribeBlocking never starts or stops interactive controllers`() = runTest(dispatcher) {
        selectEngine(VoiceInputEngine.Auto)
        val pcm = shortArrayOf(6)
        coEvery { sherpaOnnxVoiceInputController.transcribeBlocking(pcm) } returns "hey jandal"

        controller().transcribeBlocking(pcm)

        coVerify(exactly = 0) { nativeAndroidVoiceInputController.startListening(any(), any(), any(), any()) }
        coVerify(exactly = 0) { sherpaOnnxVoiceInputController.startListening(any(), any(), any()) }
        verify(exactly = 0) { nativeAndroidVoiceInputController.stopListening() }
        verify(exactly = 0) { sherpaOnnxVoiceInputController.stopListening() }
    }

    @Test
    fun `auto zipformer start exports the stt_route record`() = runTest(dispatcher) {
        JandalEvents.install(Files.createTempFile("jandal-auto-route", ".log").toFile())
        selectEngine(VoiceInputEngine.Auto)
        coEvery { sherpaOnnxVoiceInputController.isSpecAvailable(SherpaSttModelSpec.ZIPFORMER) } returns true

        controller().startListening(VoiceCaptureMode.Command)
        JandalEvents.awaitWritesForTest()

        val lines = JandalEvents.exportLines()
        assertTrue(
            lines.any { " voice stt_route " in it && "route=ZIPFORMER" in it && "online_fallback=false" in it },
            lines.toString(),
        )
    }

    @Test
    fun `stop during the selectable suspension keeps the microphone closed and sends no ListeningStarted`() =
        runTest(dispatcher) {
            val controller = controller()
            every { voiceInputPreferences.selectedEngine } returns flow {
                controller.stopListening() // the stop lands while Selectable is still reading settings
                emit(VoiceInputEngine.Auto)
            }
            coEvery { sherpaOnnxVoiceInputController.isSpecAvailable(SherpaSttModelSpec.ZIPFORMER) } returns true
            var micOpened = false
            coEvery { sherpaOnnxVoiceInputController.startListening(any(), any(), any()) } coAnswers {
                if (secondArg<() -> Boolean>().invoke()) {
                    micOpened = true
                    thirdArg<(Long) -> Unit>().invoke(2L)
                    sherpaEvents.emit(
                        VoiceInputEvent.ListeningStarted(VoiceCaptureMode.Command, 2L),
                    )
                    VoiceInputStartResult.Started(2L)
                } else {
                    VoiceInputStartResult.Cancelled()
                }
            }
            val received = mutableListOf<VoiceInputEvent>()
            val collectJob = launch { controller.events.collect { received += it } }

            controller.startListening(VoiceCaptureMode.Command)
            advanceUntilIdle()
            collectJob.cancel()

            assertFalse(micOpened)
            assertTrue(received.none { it is VoiceInputEvent.ListeningStarted })
        }

    @Test
    fun `sherpa start without a stop opens the microphone and sends ListeningStarted`() =
        runTest(dispatcher) {
            val controller = controller()
            selectEngine(VoiceInputEngine.Auto)
            coEvery { sherpaOnnxVoiceInputController.isSpecAvailable(SherpaSttModelSpec.ZIPFORMER) } returns true
            var micOpened = false
            coEvery { sherpaOnnxVoiceInputController.startListening(any(), any(), any()) } coAnswers {
                if (secondArg<() -> Boolean>().invoke()) {
                    micOpened = true
                    thirdArg<(Long) -> Unit>().invoke(2L)
                }
                VoiceInputStartResult.Started(2L)
            }

            controller.startListening(VoiceCaptureMode.Command)

            assertTrue(micOpened)
        }

    // ── Android native prewarm (#1973) ──────────────────────────────────────

    private suspend fun prewarmAndroidNative(online: Boolean, checkDue: Boolean) {
        selectEngine(VoiceInputEngine.AndroidNative)
        every { voiceInputPreferences.allowOnlineSpeechFallback } returns MutableStateFlow(online)
        every { recognitionSupport.getCaptureAvailability() } returns
            nativeAvailability(onDevice = true, localeStatus = AndroidNativeRecognitionLocaleStatus.Unknown)
        every { recognitionSupport.localeCheckDue("en-NZ") } returns checkDue
        coEvery { recognitionSupport.getAvailability() } returns nativeAvailability(onDevice = true)
        controller().prewarm()
    }

    @Test
    fun `android native prewarm runs the language check once it is due with online on`() = runTest(dispatcher) {
        prewarmAndroidNative(online = true, checkDue = true)
        coVerify(exactly = 1) { recognitionSupport.getAvailability() }
    }

    @Test
    fun `android native prewarm reuses an answered language check`() = runTest(dispatcher) {
        prewarmAndroidNative(online = true, checkDue = false)
        coVerify(exactly = 0) { recognitionSupport.getAvailability() }
    }

    @Test
    fun `android native prewarm runs no language check with online off`() = runTest(dispatcher) {
        prewarmAndroidNative(online = false, checkDue = true)
        coVerify(exactly = 0) { recognitionSupport.getAvailability() }
    }

    @Test
    fun `moving the selection away from asr4all releases its engine without another capture`() = runBlocking {
        val selected = MutableStateFlow(VoiceInputEngine.Asr4all)
        every { voiceInputPreferences.selectedEngine } returns selected
        val released = CompletableDeferred<Unit>()
        coEvery { asr4allVoiceInputController.releaseEngine() } coAnswers { released.complete(Unit) }

        controller()
        selected.value = VoiceInputEngine.AndroidNative

        withTimeout(5_000) { released.await() }
    }

    @Test
    fun `asr4all admission binds the consumer before a terminal can race the start result`() = runTest(dispatcher) {
        selectEngine(VoiceInputEngine.Asr4all)
        val order = mutableListOf<String>()
        val cancellation = VoiceInputEvent.ListeningStopped(
            VoiceCaptureMode.Command,
            captureSessionId = 11L,
            cancelled = true,
        )
        coEvery { asr4allVoiceInputController.startListening(any(), any(), any()) } coAnswers {
            thirdArg<(Long) -> Unit>().invoke(11L)
            assertEquals(listOf("bound:11"), order)
            asr4allEvents.emit(cancellation)
            VoiceInputStartResult.Started(11L)
        }
        val c = controller()
        val seen = mutableListOf<VoiceInputEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.events.collect { seen += it } }
        advanceUntilIdle()

        val result = c.startListening(VoiceCaptureMode.Command) { sessionId ->
            order += "bound:$sessionId"
        }
        advanceUntilIdle()

        assertEquals(VoiceInputStartResult.Started(11L), result)
        assertEquals(listOf("bound:11"), order)
        assertEquals(listOf(cancellation), seen.filterIsInstance<VoiceInputEvent.ListeningStopped>())
    }

    @Test
    fun `asr4all result settles text but matching stopped settles capture ownership`() = runTest(dispatcher) {
        selectEngine(VoiceInputEngine.Asr4all)
        coEvery { asr4allVoiceInputController.startListening(any(), any(), any()) } coAnswers {
            thirdArg<(Long) -> Unit>().invoke(12L)
            VoiceInputStartResult.Started(12L)
        }
        val c = controller()
        advanceUntilIdle()

        assertEquals(VoiceInputStartResult.Started(12L), c.startListening(VoiceCaptureMode.Command))
        asr4allEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "final", 12L))
        advanceUntilIdle()
        assertTrue(c.hasCaptureOrStart())
        assertTrue(c.captureActivity.value)

        val teardown = async { c.awaitCaptureTeardown(12L) }
        runCurrent()
        assertFalse(teardown.isCompleted)

        asr4allEvents.emit(VoiceInputEvent.ListeningStopped(VoiceCaptureMode.Command, 12L))
        advanceUntilIdle()
        teardown.await()
        assertFalse(c.hasCaptureOrStart())
        assertFalse(c.captureActivity.value)
    }

    @Test
    fun `never admitted replacement clears the capture activity fence`() = runTest(dispatcher) {
        selectEngine(VoiceInputEngine.Asr4all)
        var starts = 0
        coEvery { asr4allVoiceInputController.startListening(any(), any(), any()) } coAnswers {
            starts++
            if (starts == 1) {
                thirdArg<(Long) -> Unit>().invoke(21L)
                VoiceInputStartResult.Started(21L)
            } else {
                VoiceInputStartResult.Cancelled()
            }
        }
        val c = controller()
        val activity = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            c.captureActivity.collect { activity += it }
        }
        advanceUntilIdle()

        assertEquals(VoiceInputStartResult.Started(21L), c.startListening(VoiceCaptureMode.Command))
        assertEquals(VoiceInputStartResult.Cancelled(), c.startListening(VoiceCaptureMode.Command))

        assertEquals(listOf(false, true), activity)
        assertTrue(c.hasCaptureOrStart())
        asr4allEvents.emit(VoiceInputEvent.ListeningStopped(VoiceCaptureMode.Command, 21L))
        advanceUntilIdle()
        assertEquals(listOf(false, true, false), activity)
        assertFalse(c.hasCaptureOrStart())
    }

    @Test
    fun `events from a superseded asr4all session never forward and the current session does`() = runTest(dispatcher) {
        selectEngine(VoiceInputEngine.Asr4all)
        var starts = 0L
        coEvery { asr4allVoiceInputController.startListening(any(), any(), any()) } answers {
            starts++
            val id = 4L + starts
            thirdArg<(Long) -> Unit>().invoke(id)
            VoiceInputStartResult.Started(id)
        }
        val c = controller()
        val seen = mutableListOf<VoiceInputEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.events.collect { seen += it } }
        advanceUntilIdle()

        c.startListening(VoiceCaptureMode.Command)
        asr4allEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "first", 5L))
        advanceUntilIdle()

        c.startListening(VoiceCaptureMode.Command)
        asr4allEvents.emit(VoiceInputEvent.ListeningStopped(VoiceCaptureMode.Command, 5L, cancelled = true))
        asr4allEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "stale", 5L))
        asr4allEvents.emit(VoiceInputEvent.PartialTranscript(VoiceCaptureMode.Command, "second", 6L))
        advanceUntilIdle()

        c.stopListening()
        asr4allEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "final", 6L))
        advanceUntilIdle()

        advanceUntilIdle()
        assertEquals(listOf("first", "final"), seen.filterIsInstance<VoiceInputEvent.Transcript>().map { it.text })
        assertEquals(listOf("second"), seen.filterIsInstance<VoiceInputEvent.PartialTranscript>().map { it.text })
        assertEquals(
            listOf(false),
            seen.filterIsInstance<VoiceInputEvent.ListeningStopped>()
                .filter { it.captureSessionId == 5L }
                .map { it.cancelled },
        )
    }

    @Test
    fun `an engine release drops queued asr4all results but forwards the cancellation terminal`() = runTest(dispatcher) {
        selectEngine(VoiceInputEngine.Asr4all)
        var epoch = 0L
        every { asr4allVoiceInputController.admissionEpochNow() } answers { epoch }
        coEvery { asr4allVoiceInputController.startListening(any(), any(), any()) } answers {
            thirdArg<(Long) -> Unit>().invoke(7L)
            VoiceInputStartResult.Started(7L)
        }
        val c = controller()
        val seen = mutableListOf<VoiceInputEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.events.collect { seen += it } }
        advanceUntilIdle()

        c.startListening(VoiceCaptureMode.Command)
        // Deletion or a selection switch releases the engine: admission is invalidated.
        epoch = 1L
        asr4allEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "queued", 7L))
        asr4allEvents.emit(VoiceInputEvent.ListeningStopped(VoiceCaptureMode.Command, 7L, cancelled = true))
        advanceUntilIdle()

        assertTrue(seen.none { it is VoiceInputEvent.Transcript })
        assertEquals(
            listOf(VoiceInputEvent.ListeningStopped(VoiceCaptureMode.Command, 7L, cancelled = true)),
            seen.filterIsInstance<VoiceInputEvent.ListeningStopped>(),
        )
    }

    @Test
    fun `a switch between the selection read and asr4all admission rejects the start`() = runTest(dispatcher) {
        selectEngine(VoiceInputEngine.Asr4all)
        var epoch = 0L
        every { asr4allVoiceInputController.admissionEpochNow() } answers { epoch }
        var admitted: Boolean? = null
        coEvery { asr4allVoiceInputController.startListening(any(), any(), any()) } answers {
            // A switch lands after the selection read and before the mic admission check.
            epoch = 1L
            admitted = secondArg<() -> Boolean>().invoke()
            VoiceInputStartResult.Started(9L)
        }
        val c = controller()

        c.startListening(VoiceCaptureMode.Command)

        assertEquals(false, admitted)
    }

    @Test
    fun `a delayed cancel from a superseded asr4all session reaches its original owner without changing the replacement`() = runTest(dispatcher) {
        selectEngine(VoiceInputEngine.Asr4all)
        var starts = 0L
        coEvery { asr4allVoiceInputController.startListening(any(), any(), any()) } answers {
            starts++
            val id = 6L + starts
            thirdArg<(Long) -> Unit>().invoke(id)
            VoiceInputStartResult.Started(id)
        }
        val c = controller()
        val seen = mutableListOf<VoiceInputEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.events.collect { seen += it } }
        advanceUntilIdle()

        // Session A (7) is listening, then session B (8) replaces it and is listening too.
        c.startListening(VoiceCaptureMode.Command)
        asr4allEvents.emit(VoiceInputEvent.PartialTranscript(VoiceCaptureMode.Command, "a", 7L))
        advanceUntilIdle()
        c.startListening(VoiceCaptureMode.Command)
        asr4allEvents.emit(VoiceInputEvent.PartialTranscript(VoiceCaptureMode.Command, "b", 8L))
        advanceUntilIdle()

        // A's delayed cancellation is delivered to its owner after B is active; ID matching keeps B bound.
        asr4allEvents.emit(VoiceInputEvent.ListeningStopped(VoiceCaptureMode.Command, 7L, cancelled = true))
        advanceUntilIdle()
        assertEquals(
            listOf(VoiceInputEvent.ListeningStopped(VoiceCaptureMode.Command, 7L, cancelled = true)),
            seen.filterIsInstance<VoiceInputEvent.ListeningStopped>(),
        )
        assertEquals(listOf("a", "b"), seen.filterIsInstance<VoiceInputEvent.PartialTranscript>().map { it.text })

        // B's own cancellation clears it, without any transcript being submitted.
        asr4allEvents.emit(VoiceInputEvent.ListeningStopped(VoiceCaptureMode.Command, 8L, cancelled = true))
        advanceUntilIdle()
        assertEquals(
            listOf(
                VoiceInputEvent.ListeningStopped(VoiceCaptureMode.Command, 7L, cancelled = true),
                VoiceInputEvent.ListeningStopped(VoiceCaptureMode.Command, 8L, cancelled = true),
            ),
            seen.filterIsInstance<VoiceInputEvent.ListeningStopped>(),
        )
        assertTrue(seen.none { it is VoiceInputEvent.Transcript })
    }
    @Test
    fun `prewarm waiting for selector admission skips after engine selection changes`() = runTest(dispatcher) {
        val selected = MutableStateFlow(VoiceInputEngine.Asr4all)
        every { voiceInputPreferences.selectedEngine } returns selected
        val startEntered = CompletableDeferred<Unit>()
        val releaseStart = CompletableDeferred<Unit>()
        val releaseObserved = CompletableDeferred<Unit>()
        var prewarmAdmitted: Boolean? = null
        coEvery { asr4allVoiceInputController.releaseEngine() } coAnswers {
            releaseObserved.complete(Unit)
        }
        coEvery { asr4allVoiceInputController.startListening(any(), any(), any()) } coAnswers {
            startEntered.complete(Unit)
            releaseStart.await()
            VoiceInputStartResult.Cancelled()
        }
        coEvery { asr4allVoiceInputController.prewarmIfCurrent(any()) } coAnswers {
            prewarmAdmitted = firstArg<suspend () -> Boolean>().invoke()
            prewarmAdmitted == true
        }
        val c = controller()
        val start = async { c.startListening(VoiceCaptureMode.Command) }
        runCurrent()
        startEntered.await()

        val prewarm = async { c.prewarm() }
        runCurrent()
        selected.value = VoiceInputEngine.AndroidNative
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { releaseObserved.await() }
        }
        releaseStart.complete(Unit)

        assertTrue(start.await() is VoiceInputStartResult.Cancelled)
        prewarm.await()
        assertEquals(false, prewarmAdmitted)
    }
}
