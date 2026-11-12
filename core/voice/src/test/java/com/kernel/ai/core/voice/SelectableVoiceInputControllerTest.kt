package com.kernel.ai.core.voice

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SelectableVoiceInputControllerTest {
    private val dispatcher = StandardTestDispatcher()
    private val voiceInputPreferences: VoiceInputPreferences = mockk()
    private val nativeAndroidVoiceInputController: NativeAndroidVoiceInputController = mockk()
    private val sherpaOnnxVoiceInputController: SherpaOnnxVoiceInputController = mockk()
    private val recognitionSupport: AndroidNativeRecognitionSupport = mockk()
    private val nativeEvents = MutableSharedFlow<VoiceInputEvent>()
    private val sherpaEvents = MutableSharedFlow<VoiceInputEvent>()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { voiceInputPreferences.allowOnlineSpeechFallback } returns MutableStateFlow(false)
        every { nativeAndroidVoiceInputController.events } returns nativeEvents
        every { sherpaOnnxVoiceInputController.events } returns sherpaEvents
        every { nativeAndroidVoiceInputController.stopListening() } just runs
        every { sherpaOnnxVoiceInputController.stopListening() } just runs
        coEvery { nativeAndroidVoiceInputController.startListening(any(), any()) } returns
            VoiceInputStartResult.Started(1L)
        coEvery { sherpaOnnxVoiceInputController.startListening(any()) } returns
            VoiceInputStartResult.Started(2L)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun controller() = SelectableVoiceInputController(
        voiceInputPreferences = voiceInputPreferences,
        nativeAndroidVoiceInputController = nativeAndroidVoiceInputController,
        sherpaOnnxVoiceInputController = sherpaOnnxVoiceInputController,
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
    fun `auto prefers on-device native for interactive capture`() {
        assertEquals(
            AutoSttRoute.Native,
            resolveAutoSttRoute(VoiceCaptureMode.Command, nativeOnDeviceReady = true, zipformerReady = true, allowOnlineFallback = false),
        )
    }

    @Test
    fun `auto falls back to zipformer when native on-device is unavailable`() {
        assertEquals(
            AutoSttRoute.Zipformer,
            resolveAutoSttRoute(VoiceCaptureMode.Command, nativeOnDeviceReady = false, zipformerReady = true, allowOnlineFallback = true),
        )
    }

    @Test
    fun `auto reports unavailable when neither recognizer is ready`() {
        assertEquals(
            AutoSttRoute.Unavailable,
            resolveAutoSttRoute(VoiceCaptureMode.Command, nativeOnDeviceReady = false, zipformerReady = false, allowOnlineFallback = true),
        )
    }

    @Test
    fun `auto alert commands prefer zipformer unless online fallback is allowed`() {
        assertEquals(
            AutoSttRoute.Zipformer,
            resolveAutoSttRoute(VoiceCaptureMode.AlertCommand, nativeOnDeviceReady = true, zipformerReady = true, allowOnlineFallback = false),
        )
        assertEquals(
            AutoSttRoute.Native,
            resolveAutoSttRoute(VoiceCaptureMode.AlertCommand, nativeOnDeviceReady = true, zipformerReady = true, allowOnlineFallback = true),
        )
    }

    @Test
    fun `auto alert commands use on-device native when zipformer is missing`() {
        assertEquals(
            AutoSttRoute.Native,
            resolveAutoSttRoute(VoiceCaptureMode.AlertCommand, nativeOnDeviceReady = true, zipformerReady = false, allowOnlineFallback = false),
        )
    }

    // ── Controller wiring ───────────────────────────────────────────────────

    @Test
    fun `auto starts native on-device without platform fallback by default`() = runTest(dispatcher) {
        selectEngine(VoiceInputEngine.Auto)
        every { recognitionSupport.getCaptureAvailability() } returns nativeAvailability(onDevice = true)
        coEvery { sherpaOnnxVoiceInputController.isSpecAvailable(SherpaSttModelSpec.ZIPFORMER) } returns true

        assertEquals(VoiceInputStartResult.Started(1L), controller().startListening(VoiceCaptureMode.Command))

        coVerify(exactly = 1) { nativeAndroidVoiceInputController.startListening(VoiceCaptureMode.Command, false) }
        coVerify(exactly = 0) { sherpaOnnxVoiceInputController.startListening(any()) }
    }

    @Test
    fun `auto passes the online fallback setting to native`() = runTest(dispatcher) {
        selectEngine(VoiceInputEngine.Auto)
        every { voiceInputPreferences.allowOnlineSpeechFallback } returns MutableStateFlow(true)
        every { recognitionSupport.getCaptureAvailability() } returns nativeAvailability(onDevice = true)
        coEvery { sherpaOnnxVoiceInputController.isSpecAvailable(SherpaSttModelSpec.ZIPFORMER) } returns true

        controller().startListening(VoiceCaptureMode.Command)

        coVerify(exactly = 1) { nativeAndroidVoiceInputController.startListening(VoiceCaptureMode.Command, true) }
    }

    @Test
    fun `auto uses zipformer when on-device native is unavailable`() = runTest(dispatcher) {
        selectEngine(VoiceInputEngine.Auto)
        every { recognitionSupport.getCaptureAvailability() } returns nativeAvailability(onDevice = false)
        coEvery { sherpaOnnxVoiceInputController.isSpecAvailable(SherpaSttModelSpec.ZIPFORMER) } returns true

        assertEquals(VoiceInputStartResult.Started(2L), controller().startListening(VoiceCaptureMode.Command))

        coVerify(exactly = 1) { sherpaOnnxVoiceInputController.startListening(VoiceCaptureMode.Command) }
        coVerify(exactly = 0) { nativeAndroidVoiceInputController.startListening(any(), any()) }
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
        coVerify(exactly = 1) { nativeAndroidVoiceInputController.startListening(VoiceCaptureMode.Command, false) }
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

        coVerify(exactly = 1) { nativeAndroidVoiceInputController.startListening(VoiceCaptureMode.Command, false) }
        verify(exactly = 1) { sherpaOnnxVoiceInputController.stopListening() }
    }

    @Test
    fun `every sherpa engine selection starts the sherpa controller`() = runTest(dispatcher) {
        VoiceInputEngine.entries.filter { it.isSherpaFamily }.forEach { engine ->
            selectEngine(engine)
            controller().startListening(VoiceCaptureMode.Command)
        }
        val sherpaCount = VoiceInputEngine.entries.count { it.isSherpaFamily }
        coVerify(exactly = sherpaCount) { sherpaOnnxVoiceInputController.startListening(VoiceCaptureMode.Command) }
        coVerify(exactly = 0) { nativeAndroidVoiceInputController.startListening(any(), any()) }
    }

    @Test
    fun `events only flow from the selected controller`() = runTest(dispatcher) {
        selectEngine(VoiceInputEngine.AndroidNative)
        val received = mutableListOf<VoiceInputEvent>()
        val controller = controller()
        val collectJob = launch { controller.events.collect { received += it } }

        controller.startListening(VoiceCaptureMode.Command)
        advanceUntilIdle()
        sherpaEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "stale sherpa"))
        nativeEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "fresh native"))
        advanceUntilIdle()

        assertEquals(listOf(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "fresh native")), received)
        collectJob.cancel()
    }

    @Test
    fun `events receive synchronous startup error from newly selected controller`() = runTest(dispatcher) {
        selectEngine(VoiceInputEngine.AndroidNative)
        coEvery { nativeAndroidVoiceInputController.startListening(VoiceCaptureMode.Command, any()) } coAnswers {
            nativeEvents.emit(VoiceInputEvent.Error(mode = VoiceCaptureMode.Command, message = "startup failed"))
            VoiceInputStartResult.Started(1L)
        }
        val received = mutableListOf<VoiceInputEvent>()
        val controller = controller()
        val collectJob = launch { controller.events.collect { received += it } }
        advanceUntilIdle()

        controller.startListening(VoiceCaptureMode.Command)
        advanceUntilIdle()

        assertEquals(
            listOf(VoiceInputEvent.Error(mode = VoiceCaptureMode.Command, message = "startup failed")),
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

        coVerify(exactly = 0) { nativeAndroidVoiceInputController.startListening(any(), any()) }
        coVerify(exactly = 0) { sherpaOnnxVoiceInputController.startListening(any()) }
        verify(exactly = 0) { nativeAndroidVoiceInputController.stopListening() }
        verify(exactly = 0) { sherpaOnnxVoiceInputController.stopListening() }
    }
}
