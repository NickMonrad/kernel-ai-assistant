package com.kernel.ai.core.voice

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SelectableVoiceInputControllerTest {
    private val dispatcher = StandardTestDispatcher()
    private val voiceInputPreferences: VoiceInputPreferences = mockk()
    private val voskOfflineVoiceInputController: VoskOfflineVoiceInputController = mockk()
    private val nativeAndroidVoiceInputController: NativeAndroidVoiceInputController = mockk()
    private val sherpaOnnxVoiceInputController: SherpaOnnxVoiceInputController = mockk()

    @Test
    fun `events only flow from the selected controller`() = runTest(dispatcher) {
        val selectedEngine = MutableStateFlow(VoiceInputEngine.AndroidNative)
        val voskEvents = MutableSharedFlow<VoiceInputEvent>()
        val nativeEvents = MutableSharedFlow<VoiceInputEvent>()
        val received = mutableListOf<VoiceInputEvent>()
        every { voiceInputPreferences.selectedEngine } returns selectedEngine
        every { voskOfflineVoiceInputController.events } returns voskEvents
        every { nativeAndroidVoiceInputController.events } returns nativeEvents
        every { sherpaOnnxVoiceInputController.events } returns MutableSharedFlow()
        every { voskOfflineVoiceInputController.stopListening() } just runs
        every { nativeAndroidVoiceInputController.stopListening() } just runs
        every { sherpaOnnxVoiceInputController.stopListening() } just runs
        coEvery { nativeAndroidVoiceInputController.startListening(VoiceCaptureMode.Command) } returns
            VoiceInputStartResult.Started(1L)

        val controller = SelectableVoiceInputController(
            voiceInputPreferences = voiceInputPreferences,
            voskOfflineVoiceInputController = voskOfflineVoiceInputController,
            nativeAndroidVoiceInputController = nativeAndroidVoiceInputController,
            sherpaOnnxVoiceInputController = sherpaOnnxVoiceInputController,
        )
        val collectJob = launch { controller.events.collect { received += it } }

        controller.startListening(VoiceCaptureMode.Command)
        advanceUntilIdle()
        voskEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "stale vosk"))
        nativeEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "fresh native"))
        advanceUntilIdle()

        assertEquals(
            listOf(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "fresh native")),
            received,
        )
        collectJob.cancel()
    }

    @Test
    fun `events receive synchronous startup error from newly selected controller`() = runTest(dispatcher) {
        val selectedEngine = MutableStateFlow(VoiceInputEngine.AndroidNative)
        val voskEvents = MutableSharedFlow<VoiceInputEvent>()
        val nativeEvents = MutableSharedFlow<VoiceInputEvent>()
        val received = mutableListOf<VoiceInputEvent>()
        every { voiceInputPreferences.selectedEngine } returns selectedEngine
        every { voskOfflineVoiceInputController.events } returns voskEvents
        every { nativeAndroidVoiceInputController.events } returns nativeEvents
        every { sherpaOnnxVoiceInputController.events } returns MutableSharedFlow()
        every { voskOfflineVoiceInputController.stopListening() } just runs
        every { nativeAndroidVoiceInputController.stopListening() } just runs
        every { sherpaOnnxVoiceInputController.stopListening() } just runs
        coEvery { nativeAndroidVoiceInputController.startListening(VoiceCaptureMode.Command) } coAnswers {
            nativeEvents.emit(
                VoiceInputEvent.Error(
                    mode = VoiceCaptureMode.Command,
                    message = "startup failed",
                ),
            )
            VoiceInputStartResult.Started(1L)
        }

        val controller = SelectableVoiceInputController(
            voiceInputPreferences = voiceInputPreferences,
            voskOfflineVoiceInputController = voskOfflineVoiceInputController,
            nativeAndroidVoiceInputController = nativeAndroidVoiceInputController,
            sherpaOnnxVoiceInputController = sherpaOnnxVoiceInputController,
        )
        val collectJob = launch { controller.events.collect { received += it } }
        advanceUntilIdle()

        controller.startListening(VoiceCaptureMode.Command)
        advanceUntilIdle()

        assertEquals(
            listOf(
                VoiceInputEvent.Error(
                    mode = VoiceCaptureMode.Command,
                    message = "startup failed",
                ),
            ),
            received,
        )
        collectJob.cancel()
    }

    @Test
    fun `stopListening stops both controllers to avoid orphaned sessions`() = runTest(dispatcher) {
        every { voiceInputPreferences.selectedEngine } returns MutableStateFlow(VoiceInputEngine.Vosk)
        every { voskOfflineVoiceInputController.events } returns MutableSharedFlow()
        every { nativeAndroidVoiceInputController.events } returns MutableSharedFlow()
        every { sherpaOnnxVoiceInputController.events } returns MutableSharedFlow()
        every { voskOfflineVoiceInputController.stopListening() } just runs
        every { nativeAndroidVoiceInputController.stopListening() } just runs
        every { sherpaOnnxVoiceInputController.stopListening() } just runs

        val controller = SelectableVoiceInputController(
            voiceInputPreferences = voiceInputPreferences,
            voskOfflineVoiceInputController = voskOfflineVoiceInputController,
            nativeAndroidVoiceInputController = nativeAndroidVoiceInputController,
            sherpaOnnxVoiceInputController = sherpaOnnxVoiceInputController,
        )

        controller.stopListening()

        verify(exactly = 1) { voskOfflineVoiceInputController.stopListening() }
        verify(exactly = 1) { nativeAndroidVoiceInputController.stopListening() }
    }

    // ── wake-window verification delegation (#1432) ────────────────────────

    @Test
    fun `transcribeBlocking forwards the wake-window PCM exactly once to the Sherpa verifier`() = runTest(dispatcher) {
        every { voskOfflineVoiceInputController.events } returns MutableSharedFlow()
        every { nativeAndroidVoiceInputController.events } returns MutableSharedFlow()
        every { sherpaOnnxVoiceInputController.events } returns MutableSharedFlow()
        val pcm = shortArrayOf(1, 2, 3, 4)
        coEvery { sherpaOnnxVoiceInputController.transcribeBlocking(pcm) } returns "hey jandal"

        val controller = SelectableVoiceInputController(
            voiceInputPreferences = voiceInputPreferences,
            voskOfflineVoiceInputController = voskOfflineVoiceInputController,
            nativeAndroidVoiceInputController = nativeAndroidVoiceInputController,
            sherpaOnnxVoiceInputController = sherpaOnnxVoiceInputController,
        )

        assertEquals("hey jandal", controller.transcribeBlocking(pcm))

        coVerify(exactly = 1) { sherpaOnnxVoiceInputController.transcribeBlocking(pcm) }
    }

    @Test
    fun `transcribeBlocking propagates the returned wake transcript`() = runTest(dispatcher) {
        every { voskOfflineVoiceInputController.events } returns MutableSharedFlow()
        every { nativeAndroidVoiceInputController.events } returns MutableSharedFlow()
        every { sherpaOnnxVoiceInputController.events } returns MutableSharedFlow()
        val pcm = shortArrayOf(9, 8, 7)
        coEvery { sherpaOnnxVoiceInputController.transcribeBlocking(pcm) } returns "a jandel"

        val controller = SelectableVoiceInputController(
            voiceInputPreferences = voiceInputPreferences,
            voskOfflineVoiceInputController = voskOfflineVoiceInputController,
            nativeAndroidVoiceInputController = nativeAndroidVoiceInputController,
            sherpaOnnxVoiceInputController = sherpaOnnxVoiceInputController,
        )

        assertEquals("a jandel", controller.transcribeBlocking(pcm))
    }

    @Test
    fun `transcribeBlocking propagates null truthfully`() = runTest(dispatcher) {
        every { voskOfflineVoiceInputController.events } returns MutableSharedFlow()
        every { nativeAndroidVoiceInputController.events } returns MutableSharedFlow()
        every { sherpaOnnxVoiceInputController.events } returns MutableSharedFlow()
        val pcm = shortArrayOf(5, 5, 5)
        coEvery { sherpaOnnxVoiceInputController.transcribeBlocking(pcm) } returns null

        val controller = SelectableVoiceInputController(
            voiceInputPreferences = voiceInputPreferences,
            voskOfflineVoiceInputController = voskOfflineVoiceInputController,
            nativeAndroidVoiceInputController = nativeAndroidVoiceInputController,
            sherpaOnnxVoiceInputController = sherpaOnnxVoiceInputController,
        )

        assertNull(controller.transcribeBlocking(pcm))
    }

    @Test
    fun `transcribeBlocking never starts or stops interactive controllers`() = runTest(dispatcher) {
        every { voiceInputPreferences.selectedEngine } returns MutableStateFlow(VoiceInputEngine.Vosk)
        every { voskOfflineVoiceInputController.events } returns MutableSharedFlow()
        every { nativeAndroidVoiceInputController.events } returns MutableSharedFlow()
        every { sherpaOnnxVoiceInputController.events } returns MutableSharedFlow()
        every { voskOfflineVoiceInputController.stopListening() } just runs
        every { nativeAndroidVoiceInputController.stopListening() } just runs
        every { sherpaOnnxVoiceInputController.stopListening() } just runs
        coEvery { sherpaOnnxVoiceInputController.transcribeBlocking(any()) } returns null

        val controller = SelectableVoiceInputController(
            voiceInputPreferences = voiceInputPreferences,
            voskOfflineVoiceInputController = voskOfflineVoiceInputController,
            nativeAndroidVoiceInputController = nativeAndroidVoiceInputController,
            sherpaOnnxVoiceInputController = sherpaOnnxVoiceInputController,
        )

        controller.transcribeBlocking(shortArrayOf(1, 2))

        coVerify(exactly = 0) { voskOfflineVoiceInputController.startListening(any()) }
        coVerify(exactly = 0) { nativeAndroidVoiceInputController.startListening(any()) }
        coVerify(exactly = 0) { sherpaOnnxVoiceInputController.startListening(any()) }
        verify(exactly = 0) { voskOfflineVoiceInputController.stopListening() }
        verify(exactly = 0) { nativeAndroidVoiceInputController.stopListening() }
        verify(exactly = 0) { sherpaOnnxVoiceInputController.stopListening() }
    }

    @Test
    fun `transcribeBlocking leaves the interactive engine selection untouched`() = runTest(dispatcher) {
        val selectedEngine = MutableStateFlow(VoiceInputEngine.Vosk)
        every { voiceInputPreferences.selectedEngine } returns selectedEngine
        every { voskOfflineVoiceInputController.events } returns MutableSharedFlow()
        every { nativeAndroidVoiceInputController.events } returns MutableSharedFlow()
        every { sherpaOnnxVoiceInputController.events } returns MutableSharedFlow()
        every { voskOfflineVoiceInputController.stopListening() } just runs
        every { nativeAndroidVoiceInputController.stopListening() } just runs
        every { sherpaOnnxVoiceInputController.stopListening() } just runs
        coEvery { voskOfflineVoiceInputController.startListening(VoiceCaptureMode.Command) } returns
            VoiceInputStartResult.Started(1L)
        coEvery { sherpaOnnxVoiceInputController.transcribeBlocking(any()) } returns "hey jandal"

        val controller = SelectableVoiceInputController(
            voiceInputPreferences = voiceInputPreferences,
            voskOfflineVoiceInputController = voskOfflineVoiceInputController,
            nativeAndroidVoiceInputController = nativeAndroidVoiceInputController,
            sherpaOnnxVoiceInputController = sherpaOnnxVoiceInputController,
        )

        // Verification runs while Vosk is selected; the interactive selection must survive.
        assertEquals("hey jandal", controller.transcribeBlocking(shortArrayOf(1, 2)))
        assertEquals(VoiceInputEngine.Vosk, selectedEngine.value)
        assertEquals(VoiceInputStartResult.Started(1L), controller.startListening(VoiceCaptureMode.Command))
        coVerify(exactly = 1) { voskOfflineVoiceInputController.startListening(VoiceCaptureMode.Command) }
        coVerify(exactly = 0) { nativeAndroidVoiceInputController.startListening(any()) }
        coVerify(exactly = 0) { sherpaOnnxVoiceInputController.startListening(any()) }
    }

    @Test
    fun `every interactive engine selection keeps its normal capture behaviour`() = runTest(dispatcher) {
        every { voskOfflineVoiceInputController.events } returns MutableSharedFlow()
        every { nativeAndroidVoiceInputController.events } returns MutableSharedFlow()
        every { sherpaOnnxVoiceInputController.events } returns MutableSharedFlow()
        every { voskOfflineVoiceInputController.stopListening() } just runs
        every { nativeAndroidVoiceInputController.stopListening() } just runs
        every { sherpaOnnxVoiceInputController.stopListening() } just runs

        val engines = listOf(
            VoiceInputEngine.Vosk to voskOfflineVoiceInputController,
            VoiceInputEngine.AndroidNative to nativeAndroidVoiceInputController,
            VoiceInputEngine.SherpaZipformer to sherpaOnnxVoiceInputController,
        )
        for ((engine, expected) in engines) {
            every { voiceInputPreferences.selectedEngine } returns MutableStateFlow(engine)
            coEvery { expected.startListening(VoiceCaptureMode.Command) } returns
                VoiceInputStartResult.Started(1L)

            val controller = SelectableVoiceInputController(
                voiceInputPreferences = voiceInputPreferences,
                voskOfflineVoiceInputController = voskOfflineVoiceInputController,
                nativeAndroidVoiceInputController = nativeAndroidVoiceInputController,
                sherpaOnnxVoiceInputController = sherpaOnnxVoiceInputController,
            )

            assertEquals(
                VoiceInputStartResult.Started(1L),
                controller.startListening(VoiceCaptureMode.Command),
                "capture must route to $engine",
            )
            coVerify(exactly = 1) { expected.startListening(VoiceCaptureMode.Command) }
        }
    }
}
