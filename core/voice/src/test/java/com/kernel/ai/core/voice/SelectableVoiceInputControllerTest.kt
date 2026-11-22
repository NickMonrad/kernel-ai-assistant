package com.kernel.ai.core.voice

import com.kernel.ai.core.inference.diagnostics.JandalEvents
import com.kernel.ai.core.inference.terms.TERMS_NOT_ACCEPTED_VOICE_MESSAGE
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
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
    private val sherpaEvents = MutableSharedFlow<VoiceInputEvent>(extraBufferCapacity = 64)
    private val asr4allEvents = MutableSharedFlow<VoiceInputEvent>(extraBufferCapacity = 64)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { voiceInputPreferences.allowOnlineSpeechFallback } returns MutableStateFlow(false)
        every { voiceInputPreferences.selectedEngine } returns MutableStateFlow(VoiceInputEngine.Auto)
        every { voiceInputPreferences.allowsSherpaRuntime() } returns true
        every { nativeAndroidVoiceInputController.events } returns nativeEvents
        every { sherpaOnnxVoiceInputController.events } returns sherpaEvents
        every { asr4allVoiceInputController.events } returns asr4allEvents
        every { asr4allVoiceInputController.stopListeningForSession(any()) } just runs
        every { asr4allVoiceInputController.cancelListeningForSession(any()) } just runs
        coEvery { asr4allVoiceInputController.startListening(any(), any(), any()) } coAnswers {
            val id = 99L
            thirdArg<(Long) -> Unit>().invoke(id)
            VoiceInputStartResult.Started(id)
        }
        every { asr4allVoiceInputController.stopListening() } just runs
        every { asr4allVoiceInputController.supersedeCapture() } just runs
        coEvery { asr4allVoiceInputController.releaseEngine() } just runs
        coEvery { asr4allVoiceInputController.isAvailable() } returns false
        coEvery { asr4allVoiceInputController.isBlockedByTerms() } returns false
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

    /** The selection observer subscribes on Dispatchers.Default, so wait for that subscription before the first flip. */
    private suspend fun awaitSelectionObserver(selected: MutableStateFlow<VoiceInputEngine>) {
        selected.subscriptionCount.first { it > 0 }
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
    fun `auto prefers asr4all over native when the online fallback is off`() {
        assertEquals(
            AutoSttRoute.Asr4all,
            resolveAutoSttRoute(
                nativeOnDeviceReady = true,
                asr4allReady = true,
                allowOnlineFallback = false,
            ),
        )
    }

    @Test
    fun `auto uses asr4all when it is the only ready recognizer`() {
        assertEquals(
            AutoSttRoute.Asr4all,
            resolveAutoSttRoute(
                nativeOnDeviceReady = false,
                asr4allReady = true,
                allowOnlineFallback = true,
            ),
        )
    }

    @Test
    fun `auto uses android native when the online fallback is allowed`() {
        assertEquals(
            AutoSttRoute.Native,
            resolveAutoSttRoute(
                nativeOnDeviceReady = true,
                asr4allReady = true,
                allowOnlineFallback = true,
            ),
        )
    }

    @Test
    fun `auto uses native when it is the only ready recognizer`() {
        assertEquals(
            AutoSttRoute.Native,
            resolveAutoSttRoute(
                nativeOnDeviceReady = true,
                asr4allReady = false,
                allowOnlineFallback = false,
            ),
        )
    }

    @Test
    fun `auto uses asr4all when native is unavailable even with online fallback allowed`() {
        assertEquals(
            AutoSttRoute.Asr4all,
            resolveAutoSttRoute(
                nativeOnDeviceReady = false,
                asr4allReady = true,
                allowOnlineFallback = true,
            ),
        )
    }

    @Test
    fun `auto reports unavailable when no recognizer is ready`() {
        assertEquals(
            AutoSttRoute.Unavailable,
            resolveAutoSttRoute(
                nativeOnDeviceReady = false,
                asr4allReady = false,
                allowOnlineFallback = true,
            ),
        )
    }

    @Test
    fun `release auto route follows online native, asr4all, on-device native, then unavailable`() {
        fun route(native: Boolean, asr4all: Boolean, online: Boolean) = resolveAutoSttRoute(
            nativeOnDeviceReady = native,
            asr4allReady = asr4all,
            allowOnlineFallback = online,
        )

        assertEquals(AutoSttRoute.Native, route(native = true, asr4all = true, online = true))
        assertEquals(AutoSttRoute.Native, route(native = true, asr4all = false, online = true))
        assertEquals(AutoSttRoute.Asr4all, route(native = false, asr4all = true, online = true))
        assertEquals(AutoSttRoute.Asr4all, route(native = true, asr4all = true, online = false))
        assertEquals(AutoSttRoute.Native, route(native = true, asr4all = false, online = false))
        assertEquals(AutoSttRoute.Asr4all, route(native = false, asr4all = true, online = false))
        assertEquals(AutoSttRoute.Unavailable, route(native = false, asr4all = false, online = true))
        assertEquals(AutoSttRoute.Unavailable, route(native = false, asr4all = false, online = false))
    }

    // ── Controller wiring ───────────────────────────────────────────────────

    @Test
    fun `auto starts asr4all by default without checking native support`() = runTest(dispatcher) {
        selectEngine(VoiceInputEngine.Auto)
        coEvery { asr4allVoiceInputController.isAvailable() } returns true

        assertEquals(VoiceInputStartResult.Started(99L), controller().startListening(VoiceCaptureMode.Command))

        coVerify(exactly = 1) { asr4allVoiceInputController.startListening(VoiceCaptureMode.Command, any(), any()) }
        coVerify(exactly = 0) { nativeAndroidVoiceInputController.startListening(any(), any(), any(), any()) }
        verify(exactly = 0) { recognitionSupport.getCaptureAvailability() }
    }

    @Test
    fun `release Android native does not fall back when native cannot start`() = runTest(dispatcher) {
        selectEngine(VoiceInputEngine.AndroidNative)
        every { voiceInputPreferences.allowsSherpaRuntime() } returns false
        coEvery { asr4allVoiceInputController.isAvailable() } returns true
        coEvery { nativeAndroidVoiceInputController.startListening(any(), any(), any(), any()) } returns
            VoiceInputStartResult.Unavailable("not ready")

        assertEquals(
            VoiceInputStartResult.Unavailable("not ready"),
            controller().startListening(VoiceCaptureMode.Command),
        )

        coVerify(exactly = 0) { sherpaOnnxVoiceInputController.isSpecAvailable(any()) }
        coVerify(exactly = 0) { sherpaOnnxVoiceInputController.startListening(any(), any()) }
        coVerify(exactly = 0) { asr4allVoiceInputController.startListening(any(), any(), any()) }
    }

    @Test
    fun `auto starts on-device native without platform fallback while asr4all is unavailable`() = runTest(dispatcher) {
        selectEngine(VoiceInputEngine.Auto)
        every { recognitionSupport.getCaptureAvailability() } returns nativeAvailability(onDevice = true)

        assertEquals(VoiceInputStartResult.Started(1L), controller().startListening(VoiceCaptureMode.Command))

        coVerify(exactly = 1) { nativeAndroidVoiceInputController.startListening(VoiceCaptureMode.Command, false, any(), any()) }
    }

    @Test
    fun `auto passes the online fallback setting to native`() = runTest(dispatcher) {
        selectEngine(VoiceInputEngine.Auto)
        every { voiceInputPreferences.allowOnlineSpeechFallback } returns MutableStateFlow(true)
        every { recognitionSupport.getCaptureAvailability() } returns nativeAvailability(onDevice = true)

        controller().startListening(VoiceCaptureMode.Command)

        coVerify(exactly = 1) { nativeAndroidVoiceInputController.startListening(VoiceCaptureMode.Command, true, any(), any()) }
    }

    @Test
    fun `auto with online fallback uses asr4all when on-device native is unavailable`() = runTest(dispatcher) {
        selectEngine(VoiceInputEngine.Auto)
        every { voiceInputPreferences.allowOnlineSpeechFallback } returns MutableStateFlow(true)
        every { recognitionSupport.getCaptureAvailability() } returns nativeAvailability(onDevice = false)
        coEvery { asr4allVoiceInputController.isAvailable() } returns true

        assertEquals(VoiceInputStartResult.Started(99L), controller().startListening(VoiceCaptureMode.Command))

        coVerify(exactly = 1) { asr4allVoiceInputController.startListening(VoiceCaptureMode.Command, any(), any()) }
        coVerify(exactly = 0) { nativeAndroidVoiceInputController.startListening(any(), any(), any(), any()) }
    }

    @Test
    fun `auto reports the Terms refusal when native is unavailable and the Terms block asr4all`() = runTest(dispatcher) {
        selectEngine(VoiceInputEngine.Auto)
        every { recognitionSupport.getCaptureAvailability() } returns nativeAvailability(onDevice = false)
        coEvery { asr4allVoiceInputController.isBlockedByTerms() } returns true

        assertEquals(
            VoiceInputStartResult.Unavailable(TERMS_NOT_ACCEPTED_VOICE_MESSAGE, VoiceRecovery.ACCEPT_TERMS),
            controller().startListening(VoiceCaptureMode.Command),
        )
        coVerify(exactly = 0) { asr4allVoiceInputController.startListening(any(), any(), any()) }
    }

    @Test
    fun `a failed native start reports the Terms refusal when the Terms block asr4all`() = runTest(dispatcher) {
        selectEngine(VoiceInputEngine.Auto)
        every { voiceInputPreferences.allowOnlineSpeechFallback } returns MutableStateFlow(true)
        every { recognitionSupport.getCaptureAvailability() } returns nativeAvailability(onDevice = true)
        coEvery { nativeAndroidVoiceInputController.startListening(any(), any(), any(), any()) } returns
            VoiceInputStartResult.Unavailable("Android speech is busy.")
        coEvery { asr4allVoiceInputController.isBlockedByTerms() } returns true

        assertEquals(
            VoiceInputStartResult.Unavailable(TERMS_NOT_ACCEPTED_VOICE_MESSAGE, VoiceRecovery.ACCEPT_TERMS),
            controller().startListening(VoiceCaptureMode.Command),
        )
    }

    @Test
    fun `auto reports the Terms refusal in release builds too`() = runTest(dispatcher) {
        every { voiceInputPreferences.allowsSherpaRuntime() } returns false
        selectEngine(VoiceInputEngine.Auto)
        every { recognitionSupport.getCaptureAvailability() } returns nativeAvailability(onDevice = false)
        coEvery { asr4allVoiceInputController.isBlockedByTerms() } returns true

        assertEquals(
            VoiceInputStartResult.Unavailable(TERMS_NOT_ACCEPTED_VOICE_MESSAGE, VoiceRecovery.ACCEPT_TERMS),
            controller().startListening(VoiceCaptureMode.Command),
        )
        coVerify(exactly = 0) { asr4allVoiceInputController.startListening(any(), any(), any()) }
    }

    @Test
    fun `auto reports the Terms refusal on a fresh install with no weights on disk`() = runTest(dispatcher) {
        // With no weights, Asr4all isAvailable() is false, and isBlockedByTerms() is true because the Terms gate the download.
        selectEngine(VoiceInputEngine.Auto)
        every { recognitionSupport.getCaptureAvailability() } returns nativeAvailability(onDevice = false)
        coEvery { asr4allVoiceInputController.isAvailable() } returns false
        coEvery { asr4allVoiceInputController.isBlockedByTerms() } returns true

        assertEquals(
            VoiceInputStartResult.Unavailable(TERMS_NOT_ACCEPTED_VOICE_MESSAGE, VoiceRecovery.ACCEPT_TERMS),
            controller().startListening(VoiceCaptureMode.Command),
        )
    }

    @Test
    fun `auto runs the live locale check when no cached answer exists`() = runTest(dispatcher) {
        selectEngine(VoiceInputEngine.Auto)
        every { recognitionSupport.getCaptureAvailability() } returns
            nativeAvailability(onDevice = true, localeStatus = AndroidNativeRecognitionLocaleStatus.Unknown)
        coEvery { recognitionSupport.getAvailability() } returns nativeAvailability(onDevice = true)

        controller().startListening(VoiceCaptureMode.Command)

        coVerify(exactly = 1) { recognitionSupport.getAvailability() }
        coVerify(exactly = 1) { nativeAndroidVoiceInputController.startListening(VoiceCaptureMode.Command, false, any(), any()) }
    }

    @Test
    fun `auto keeps a loaded asr4all engine when its route is asr4all`() = runTest(dispatcher) {
        selectEngine(VoiceInputEngine.Auto)
        coEvery { asr4allVoiceInputController.isAvailable() } returns true

        controller().startListening(VoiceCaptureMode.Command)

        coVerify(exactly = 1) { asr4allVoiceInputController.startListening(VoiceCaptureMode.Command, any(), any()) }
        coVerify(exactly = 0) { asr4allVoiceInputController.releaseEngine() }
    }

    @Test
    fun `auto releases a loaded asr4all engine when its route is not asr4all`() = runTest(dispatcher) {
        selectEngine(VoiceInputEngine.Auto)
        every { recognitionSupport.getCaptureAvailability() } returns nativeAvailability(onDevice = true)

        controller().startListening(VoiceCaptureMode.Command)

        coVerify(exactly = 1) { asr4allVoiceInputController.releaseEngine() }
        coVerify(exactly = 0) { asr4allVoiceInputController.startListening(any(), any(), any()) }
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
    fun `superseded native results are fenced while its terminal still retires ownership`() = runTest(dispatcher) {
        selectEngine(VoiceInputEngine.AndroidNative)
        val nativeStops = mutableListOf<Long>()
        every { nativeAndroidVoiceInputController.stopListeningForSession(any()) } answers {
            nativeStops += firstArg<Long>()
        }
        val c = controller()
        val seen = mutableListOf<VoiceInputEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.events.collect { seen += it } }
        advanceUntilIdle()

        assertEquals(VoiceInputStartResult.Started(1L), c.startListening(VoiceCaptureMode.Command))
        val replacement = async { c.startListening(VoiceCaptureMode.Command) }
        runCurrent()
        assertEquals(listOf(1L), nativeStops)

        nativeEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "stale", 1L))
        nativeEvents.emit(VoiceInputEvent.Error(VoiceCaptureMode.Command, "stale", 1L))
        nativeEvents.emit(VoiceInputEvent.ListeningStopped(VoiceCaptureMode.Command, 1L))
        runCurrent()
        assertTrue(replacement.isCompleted)
        assertFalse(seen.any { it.captureSessionId == 1L && it !is VoiceInputEvent.ListeningStopped })
        assertEquals(
            VoiceInputEvent.ListeningStopped(VoiceCaptureMode.Command, 1L, cancelled = true),
            seen.single { it.captureSessionId == 1L && it is VoiceInputEvent.ListeningStopped },
        )

    }
    @Test
    fun `superseded sherpa stop is cancelled while a current sherpa stop stays healthy`() = runTest(dispatcher) {
        selectEngine(VoiceInputEngine.SherpaParaformer)
        var nextId = 40L
        coEvery { sherpaOnnxVoiceInputController.startListening(any(), any(), any()) } coAnswers {
            val id = nextId++
            thirdArg<(Long) -> Unit>().invoke(id)
            VoiceInputStartResult.Started(id)
        }
        val c = controller()
        val seen = mutableListOf<VoiceInputEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.events.collect { seen += it } }
        advanceUntilIdle()

        c.startListening(VoiceCaptureMode.Command)
        val replacement = async { c.startListening(VoiceCaptureMode.Command) }
        runCurrent()
        sherpaEvents.emit(VoiceInputEvent.ListeningStopped(VoiceCaptureMode.Command, 40L))
        runCurrent()
        assertTrue(replacement.isCompleted)
        assertEquals(
            VoiceInputEvent.ListeningStopped(VoiceCaptureMode.Command, 40L, cancelled = true),
            seen.single { it.captureSessionId == 40L && it is VoiceInputEvent.ListeningStopped },
        )
        sherpaEvents.emit(VoiceInputEvent.ListeningStopped(VoiceCaptureMode.Command, 41L))
        runCurrent()
        assertEquals(
            VoiceInputEvent.ListeningStopped(VoiceCaptureMode.Command, 41L),
            seen.single { it.captureSessionId == 41L && it is VoiceInputEvent.ListeningStopped },
        )
    }

    @Test
    fun `native fallback keeps its admission mapped until queued physical terminal routes`() = runTest(dispatcher) {
        selectEngine(VoiceInputEngine.AndroidNative)
        val fallbackAdmission = CompletableDeferred<Unit>()
        coEvery { nativeAndroidVoiceInputController.startListening(any(), any(), any(), any()) } coAnswers {
            thirdArg<(Long) -> Unit>().invoke(70L)
            nativeEvents.emit(VoiceInputEvent.ListeningStopped(VoiceCaptureMode.Command, 70L, cancelled = true))
            VoiceInputStartResult.Unavailable("native setup failed after admission")
        }
        coEvery { asr4allVoiceInputController.isAvailable() } returns true
        coEvery { asr4allVoiceInputController.startListening(any(), any(), any()) } coAnswers {
            thirdArg<(Long) -> Unit>().invoke(71L)
            fallbackAdmission.await()
            VoiceInputStartResult.Started(71L)
        }
        val c = controller()
        val seen = mutableListOf<VoiceInputEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.events.collect { seen += it } }
        advanceUntilIdle()

        val fallback = async { c.startListening(VoiceCaptureMode.Command) }
        runCurrent()
        assertTrue(c.captureActivity.value)
        // The native cancelled stop is held while the fallback decides, so consumers must not see it yet.
        assertTrue(seen.none { it.captureSessionId == 70L })

        fallbackAdmission.complete(Unit)
        advanceUntilIdle()
        assertEquals(VoiceInputStartResult.Started(71L), fallback.await())
        assertTrue(c.captureActivity.value)
        // Superseded by the asr4all start: the native stop is dropped and never reaches consumers.
        assertTrue(seen.none { it.captureSessionId == 70L })
    }
    @Test
    fun `camera rearm is allowed after admitted native and unavailable sherpa both retire`() = runTest(dispatcher) {
        selectEngine(VoiceInputEngine.AndroidNative)
        coEvery { nativeAndroidVoiceInputController.startListening(any(), any(), any(), any()) } coAnswers {
            thirdArg<(Long) -> Unit>().invoke(80L)
            nativeEvents.emit(VoiceInputEvent.ListeningStopped(VoiceCaptureMode.Command, 80L, cancelled = true))
            VoiceInputStartResult.Unavailable("native setup failed")
        }
        coEvery { asr4allVoiceInputController.isAvailable() } returns true
        coEvery { asr4allVoiceInputController.startListening(any(), any(), any()) } coAnswers {
            thirdArg<(Long) -> Unit>().invoke(81L)
            asr4allEvents.emit(
                VoiceInputEvent.ListeningStopped(VoiceCaptureMode.Command, 81L, cancelled = true),
            )
            VoiceInputStartResult.Unavailable("microphone unavailable")
        }
        val c = controller()
        val seen = mutableListOf<VoiceInputEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.events.collect { seen += it } }
        advanceUntilIdle()

        assertTrue(c.startListening(VoiceCaptureMode.Command) is VoiceInputStartResult.Unavailable)
        advanceUntilIdle()

        assertFalse(c.hasCaptureOrStart())
        // Both failed-start stops are delivered; their order is not part of the contract.
        val stops = seen.filterIsInstance<VoiceInputEvent.ListeningStopped>()
        assertEquals(setOf(80L, 81L), stops.map { it.captureSessionId }.toSet())
        assertEquals(2, stops.size)
        assertTrue(
            mayRearmAfterCamera(
                heyJandalEnabled = true,
                recordAudioGranted = true,
                captureSuspended = false,
                voiceSessionActive = c.hasCaptureOrStart(),
                captureAllowed = true,
            ),
        )
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
        selected.value = VoiceInputEngine.SherpaParaformer
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

        selected.value = VoiceInputEngine.SherpaParaformer
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
    fun `newer local start releases stale waiter but keeps the original native settle deadline`() = runTest(dispatcher) {
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

        selected.value = VoiceInputEngine.SherpaParaformer
        val stale = async { c.startListening(VoiceCaptureMode.Command) }
        runCurrent()
        nativeEvents.emit(VoiceInputEvent.ListeningStopped(VoiceCaptureMode.Command, 1L))
        runCurrent()
        advanceTimeBy(200)
        runCurrent()

        val replacement = async { c.startListening(VoiceCaptureMode.Command) }
        runCurrent()
        assertEquals(VoiceInputStartResult.Cancelled(), stale.await())
        assertFalse(replacement.isCompleted)
        assertEquals(0, sherpaStarts)

        advanceTimeBy(150)
        runCurrent()
        assertFalse(replacement.isCompleted)
        assertEquals(0, sherpaStarts)
        advanceTimeBy(50)
        runCurrent()
        assertEquals(VoiceInputStartResult.Started(2L), replacement.await())
        assertEquals(1, sherpaStarts)
    }

    @Test
    fun `newer native start proceeds immediately while stale local waiter is settling`() = runTest(dispatcher) {
        val nativeEvents = MutableSharedFlow<VoiceInputEvent>(extraBufferCapacity = 1)
        every { nativeAndroidVoiceInputController.events } returns nativeEvents
        val selected = MutableStateFlow(VoiceInputEngine.AndroidNative)
        every { voiceInputPreferences.selectedEngine } returns selected
        every { nativeAndroidVoiceInputController.stopListeningForSession(any()) } just runs
        var nativeStarts = 0
        coEvery { nativeAndroidVoiceInputController.startListening(any(), any(), any(), any()) } coAnswers {
            val sessionId = (++nativeStarts).toLong()
            thirdArg<(Long) -> Unit>().invoke(sessionId)
            VoiceInputStartResult.Started(sessionId)
        }
        var sherpaStarts = 0
        coEvery { sherpaOnnxVoiceInputController.startListening(any(), any(), any()) } coAnswers {
            sherpaStarts++
            thirdArg<(Long) -> Unit>().invoke(3L)
            VoiceInputStartResult.Started(3L)
        }
        val c = controller()
        advanceUntilIdle()
        assertEquals(VoiceInputStartResult.Started(1L), c.startListening(VoiceCaptureMode.Command))

        selected.value = VoiceInputEngine.SherpaParaformer
        val stale = async { c.startListening(VoiceCaptureMode.Command) }
        runCurrent()
        nativeEvents.emit(VoiceInputEvent.ListeningStopped(VoiceCaptureMode.Command, 1L))
        runCurrent()
        assertFalse(stale.isCompleted)

        selected.value = VoiceInputEngine.AndroidNative
        val replacement = async { c.startListening(VoiceCaptureMode.Command) }
        runCurrent()

        assertEquals(VoiceInputStartResult.Cancelled(), stale.await())
        assertEquals(VoiceInputStartResult.Started(2L), replacement.await())
        assertEquals(0, sherpaStarts)
        assertEquals(0L, currentTime)
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
        selected.value = VoiceInputEngine.SherpaParaformer

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
        awaitSelectionObserver(selected)
        selected.value = VoiceInputEngine.SherpaParaformer
        // The selection observer retires asr4all on its own dispatcher: wait for that release before the new start.
        coVerify(timeout = 30_000) { asr4allVoiceInputController.releaseEngine() }
        assertEquals(VoiceInputStartResult.Started(92L), c.startListening(VoiceCaptureMode.Command))
        advanceUntilIdle()

        assertTrue(seen.contains(VoiceInputEvent.ListeningStarted(VoiceCaptureMode.Command, 92L)))
        coVerify(atLeast = 1) { asr4allVoiceInputController.releaseEngine() }
        verify(exactly = 0) { asr4allVoiceInputController.stopListening() }
    }

    @Test
    fun `direct native route waits for the previous Sherpa teardown`() = runTest(dispatcher) {
        selectEngine(VoiceInputEngine.SherpaParaformer)
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
        selectEngine(VoiceInputEngine.SherpaParaformer)
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
        val selected = MutableStateFlow(VoiceInputEngine.SherpaParaformer)
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
        selectEngine(VoiceInputEngine.SherpaParaformer)
        var starts = 2L
        coEvery { sherpaOnnxVoiceInputController.startListening(any(), any(), any()) } coAnswers {
            val id = ++starts
            thirdArg<(Long) -> Unit>().invoke(id)
            if (id == 3L) {
                sherpaEvents.emit(VoiceInputEvent.ListeningStopped(VoiceCaptureMode.Command, id, cancelled = true))
                VoiceInputStartResult.Unavailable("startup failed")
            } else VoiceInputStartResult.Started(id)
        }
        val c = controller()
        val seen = mutableListOf<VoiceInputEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.events.collect { seen += it } }
        advanceUntilIdle()

        assertEquals(
            VoiceInputStartResult.Unavailable("startup failed"),
            c.startListening(VoiceCaptureMode.Command),
        )
        advanceUntilIdle()
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
    fun `user stop discards the asr4all capture`() {
        controller().stopListening()

        verify(exactly = 1) { asr4allVoiceInputController.supersedeCapture() }
        verify(exactly = 0) { asr4allVoiceInputController.stopListening() }
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
    fun `auto asr4all start exports the stt_route record`() = runTest(dispatcher) {
        JandalEvents.install(Files.createTempFile("jandal-auto-route", ".log").toFile())
        selectEngine(VoiceInputEngine.Auto)
        coEvery { asr4allVoiceInputController.isAvailable() } returns true

        controller().startListening(VoiceCaptureMode.Command)
        JandalEvents.awaitWritesForTest()

        val lines = JandalEvents.exportLines()
        assertTrue(
            lines.any { " voice stt_route " in it && "route=ASR4ALL" in it && "online_fallback=false" in it },
            lines.toString(),
        )
    }

    @Test
    fun `stop during the selectable suspension keeps the microphone closed and sends no ListeningStarted`() =
        runTest(dispatcher) {
            lateinit var controller: SelectableVoiceInputController
            var settingsReads = 0
            every { voiceInputPreferences.selectedEngine } returns flow {
                // The first read is the init observer's; a later one is the start's, where the stop lands.
                if (++settingsReads > 1) controller.stopListening()
                emit(VoiceInputEngine.Auto)
            }
            controller = controller()
            coEvery { asr4allVoiceInputController.isAvailable() } returns true
            var micOpened = false
            coEvery { asr4allVoiceInputController.startListening(any(), any(), any()) } coAnswers {
                if (secondArg<() -> Boolean>().invoke()) {
                    micOpened = true
                    thirdArg<(Long) -> Unit>().invoke(2L)
                    asr4allEvents.emit(
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
    fun `asr4all start without a stop opens the microphone and sends ListeningStarted`() =
        runTest(dispatcher) {
            selectEngine(VoiceInputEngine.Auto)
            val controller = controller()
            coEvery { asr4allVoiceInputController.isAvailable() } returns true
            var micOpened = false
            coEvery { asr4allVoiceInputController.startListening(any(), any(), any()) } coAnswers {
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
    fun `a reselect after deselection keeps the new asr4all capture's transcript`() = runTest(dispatcher) {
        val selected = MutableStateFlow(VoiceInputEngine.Asr4all)
        every { voiceInputPreferences.selectedEngine } returns selected
        val c = controller()
        val seen = mutableListOf<VoiceInputEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.events.collect { seen += it } }
        advanceUntilIdle()
        awaitSelectionObserver(selected)

        selected.value = VoiceInputEngine.AndroidNative
        // The selection observer retires asr4all on its own dispatcher: wait for its release before reselecting.
        coVerify(timeout = 30_000) { asr4allVoiceInputController.releaseEngine() }
        advanceUntilIdle()
        selected.value = VoiceInputEngine.Asr4all
        assertEquals(VoiceInputStartResult.Started(99L), c.startListening(VoiceCaptureMode.Command))
        asr4allEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "current", 99L))
        advanceUntilIdle()

        assertEquals(listOf("current"), seen.filterIsInstance<VoiceInputEvent.Transcript>().map { it.text })
    }

    @Test
    fun `moving the selection away from asr4all releases its engine without another capture`() = runTest(dispatcher) {
        val selected = MutableStateFlow(VoiceInputEngine.Asr4all)
        every { voiceInputPreferences.selectedEngine } returns selected
        controller()
        advanceUntilIdle()
        awaitSelectionObserver(selected)

        selected.value = VoiceInputEngine.AndroidNative
        advanceUntilIdle()

        coVerify(timeout = 30_000, exactly = 1) { asr4allVoiceInputController.releaseEngine() }
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
        every { asr4allVoiceInputController.cancelListeningForSession(21L) } answers {
            asr4allEvents.tryEmit(VoiceInputEvent.ListeningStopped(VoiceCaptureMode.Command, 21L, cancelled = true))
        }
        val c = controller()
        val activity = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            c.captureActivity.collect { activity += it }
        }
        advanceUntilIdle()

        assertEquals(VoiceInputStartResult.Started(21L), c.startListening(VoiceCaptureMode.Command))
        assertEquals(VoiceInputStartResult.Cancelled(), c.startListening(VoiceCaptureMode.Command))

        // The superseded admission is retired by its own cancelled terminal inside the second start.
        advanceUntilIdle()
        assertEquals(listOf(false, true, false), activity)
        assertFalse(c.hasCaptureOrStart())
    }

    @Test
    fun `superseded asr4all results are dropped while scoped stop finalizes current session`() = runTest(dispatcher) {
        selectEngine(VoiceInputEngine.Asr4all)
        var starts = 0L
        coEvery { asr4allVoiceInputController.startListening(any(), any(), any()) } answers {
            starts++
            val id = 4L + starts
            thirdArg<(Long) -> Unit>().invoke(id)
            VoiceInputStartResult.Started(id)
        }
        every { asr4allVoiceInputController.cancelListeningForSession(5L) } answers {
            asr4allEvents.tryEmit(VoiceInputEvent.ListeningStopped(VoiceCaptureMode.Command, 5L, cancelled = true))
        }
        val c = controller()
        val seen = mutableListOf<VoiceInputEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.events.collect { seen += it } }
        advanceUntilIdle()

        c.startListening(VoiceCaptureMode.Command)
        asr4allEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "first", 5L))
        advanceUntilIdle()

        c.startListening(VoiceCaptureMode.Command)
        asr4allEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "stale", 5L))
        asr4allEvents.emit(VoiceInputEvent.PartialTranscript(VoiceCaptureMode.Command, "second", 6L))
        advanceUntilIdle()

        c.stopListening(6L)
        asr4allEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "final", 6L))
        advanceUntilIdle()

        advanceUntilIdle()
        assertEquals(listOf("first", "final"), seen.filterIsInstance<VoiceInputEvent.Transcript>().map { it.text })
        assertEquals(listOf("second"), seen.filterIsInstance<VoiceInputEvent.PartialTranscript>().map { it.text })
        assertEquals(
            listOf(true),
            seen.filterIsInstance<VoiceInputEvent.ListeningStopped>()
                .filter { it.captureSessionId == 5L }
                .map { it.cancelled },
        )
        verify(exactly = 1) { asr4allVoiceInputController.stopListeningForSession(6L) }
    }

    @Test
    fun `global stop discards an asr4all final queued before its teardown terminal`() = runTest(dispatcher) {
        selectEngine(VoiceInputEngine.Asr4all)
        coEvery { asr4allVoiceInputController.startListening(any(), any(), any()) } coAnswers {
            thirdArg<(Long) -> Unit>().invoke(70L)
            VoiceInputStartResult.Started(70L)
        }
        val c = controller()
        val seen = mutableListOf<VoiceInputEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.events.collect { seen += it } }
        advanceUntilIdle()

        assertEquals(VoiceInputStartResult.Started(70L), c.startListening(VoiceCaptureMode.Command))
        c.stopListening()
        asr4allEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "stale final", 70L))
        asr4allEvents.emit(VoiceInputEvent.ListeningStopped(VoiceCaptureMode.Command, 70L))
        advanceUntilIdle()

        assertTrue(seen.none { it is VoiceInputEvent.Transcript })
        assertEquals(
            listOf(VoiceInputEvent.ListeningStopped(VoiceCaptureMode.Command, 70L, cancelled = true)),
            seen.filterIsInstance<VoiceInputEvent.ListeningStopped>(),
        )
    }


    @Test
    fun `deselecting asr4all drops its queued final but forwards the cancellation terminal`() = runTest(dispatcher) {
        val selected = MutableStateFlow(VoiceInputEngine.Asr4all)
        every { voiceInputPreferences.selectedEngine } returns selected
        coEvery { asr4allVoiceInputController.startListening(any(), any(), any()) } coAnswers {
            thirdArg<(Long) -> Unit>().invoke(7L)
            VoiceInputStartResult.Started(7L)
        }
        val c = controller()
        val seen = mutableListOf<VoiceInputEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.events.collect { seen += it } }
        advanceUntilIdle()
        awaitSelectionObserver(selected)

        c.startListening(VoiceCaptureMode.Command)
        // Leaving asr4all discards its capture: the queued final must not reach the UI.
        selected.value = VoiceInputEngine.AndroidNative
        // The observer discards on its own dispatcher: wait for its release before the queued events arrive.
        coVerify(timeout = 30_000) { asr4allVoiceInputController.releaseEngine() }
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
    fun `scoped stop finalizes an asr4all capture with its final transcript and an uncancelled terminal`() =
        runTest(dispatcher) {
            selectEngine(VoiceInputEngine.Asr4all)
            coEvery { asr4allVoiceInputController.startListening(any(), any(), any()) } coAnswers {
                thirdArg<(Long) -> Unit>().invoke(30L)
                VoiceInputStartResult.Started(30L)
            }
            every { asr4allVoiceInputController.stopListeningForSession(30L) } answers {
                asr4allEvents.tryEmit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "done", 30L))
                asr4allEvents.tryEmit(VoiceInputEvent.ListeningStopped(VoiceCaptureMode.Command, 30L))
            }
            val c = controller()
            val seen = mutableListOf<VoiceInputEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.events.collect { seen += it } }
            advanceUntilIdle()

            c.startListening(VoiceCaptureMode.Command)
            c.stopListening(30L)
            advanceUntilIdle()

            assertEquals(listOf("done"), seen.filterIsInstance<VoiceInputEvent.Transcript>().map { it.text })
            assertEquals(
                listOf(VoiceInputEvent.ListeningStopped(VoiceCaptureMode.Command, 30L)),
                seen.filterIsInstance<VoiceInputEvent.ListeningStopped>(),
            )
            verify(exactly = 1) { asr4allVoiceInputController.stopListeningForSession(30L) }
            assertFalse(c.hasCaptureOrStart())
        }

    @Test
    fun `scoped stop finalizes a Sherpa capture with its final transcript`() = runTest(dispatcher) {
        selectEngine(VoiceInputEngine.SherpaParaformer)
        coEvery { sherpaOnnxVoiceInputController.startListening(any(), any(), any()) } coAnswers {
            thirdArg<(Long) -> Unit>().invoke(40L)
            VoiceInputStartResult.Started(40L)
        }
        every { sherpaOnnxVoiceInputController.stopListeningForSession(40L) } answers {
            sherpaEvents.tryEmit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "done", 40L))
            sherpaEvents.tryEmit(VoiceInputEvent.ListeningStopped(VoiceCaptureMode.Command, 40L))
        }
        val c = controller()
        val seen = mutableListOf<VoiceInputEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.events.collect { seen += it } }
        advanceUntilIdle()

        c.startListening(VoiceCaptureMode.Command)
        c.stopListening(40L)
        advanceUntilIdle()

        assertEquals(listOf("done"), seen.filterIsInstance<VoiceInputEvent.Transcript>().map { it.text })
        assertEquals(
            listOf(VoiceInputEvent.ListeningStopped(VoiceCaptureMode.Command, 40L)),
            seen.filterIsInstance<VoiceInputEvent.ListeningStopped>(),
        )
    }

    @Test
    fun `a finalized asr4all capture with no terminal is retired with a cancelled stop on both streams`() =
        runTest(dispatcher) {
            selectEngine(VoiceInputEngine.Asr4all)
            coEvery { asr4allVoiceInputController.startListening(any(), any(), any()) } coAnswers {
                thirdArg<(Long) -> Unit>().invoke(31L)
                VoiceInputStartResult.Started(31L)
            }
            val c = controller()
            val seen = mutableListOf<VoiceInputEvent>()
            val lifecycle = mutableListOf<VoiceInputEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.events.collect { seen += it } }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                c.micLifecycleEvents.collect { lifecycle += it }
            }
            advanceUntilIdle()

            c.startListening(VoiceCaptureMode.Command)
            c.stopListening(31L) // the backend never reports a terminal
            advanceTimeBy(BACKEND_TEARDOWN_TIMEOUT_MS - 1)
            assertTrue(seen.none { it is VoiceInputEvent.ListeningStopped })
            advanceUntilIdle()

            val stopped = VoiceInputEvent.ListeningStopped(VoiceCaptureMode.Command, 31L, cancelled = true)
            assertEquals(listOf(stopped), seen.filterIsInstance<VoiceInputEvent.ListeningStopped>())
            assertEquals(listOf(stopped), lifecycle.filterIsInstance<VoiceInputEvent.ListeningStopped>())
            assertFalse(c.captureActivity.value)
        }

    @Test
    fun `prewarm waiting for selector admission skips after engine selection changes`() = runTest(dispatcher) {
        val selected = MutableStateFlow(VoiceInputEngine.Asr4all)
        every { voiceInputPreferences.selectedEngine } returns selected
        val startEntered = CompletableDeferred<Unit>()
        val releaseStart = CompletableDeferred<Unit>()
        var prewarmAdmitted: Boolean? = null
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
        runCurrent()
        releaseStart.complete(Unit)
        advanceUntilIdle()

        assertTrue(start.await() is VoiceInputStartResult.Cancelled)
        prewarm.await()
        assertEquals(false, prewarmAdmitted)
    }
}
