package com.kernel.ai.core.voice

import io.mockk.coEvery
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
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SelectableVoiceInputControllerTest {
    private val dispatcher = StandardTestDispatcher()
    private val voiceInputPreferences: VoiceInputPreferences = mockk()
    private val voskOfflineVoiceInputController: VoskOfflineVoiceInputController = mockk()
    private val nativeAndroidVoiceInputController: NativeAndroidVoiceInputController = mockk()
    private val sherpaOnnxVoiceInputController: SherpaOnnxVoiceInputController = mockk()
    private val whisperVoiceInputController: WhisperVoiceInputController = mockk()
    private val parakeetVoiceInputController: ParakeetVoiceInputController = mockk()

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
        every { whisperVoiceInputController.events } returns MutableSharedFlow()
        every { parakeetVoiceInputController.events } returns MutableSharedFlow()
        every { voskOfflineVoiceInputController.stopListening() } just runs
        every { nativeAndroidVoiceInputController.stopListening() } just runs
        every { sherpaOnnxVoiceInputController.stopListening() } just runs
        every { whisperVoiceInputController.stopListening() } just runs
        every { parakeetVoiceInputController.stopListening() } just runs
        coEvery { nativeAndroidVoiceInputController.startListening(VoiceCaptureMode.Command) } returns VoiceInputStartResult.Started

        val controller = SelectableVoiceInputController(
            voiceInputPreferences = voiceInputPreferences,
            voskOfflineVoiceInputController = voskOfflineVoiceInputController,
            nativeAndroidVoiceInputController = nativeAndroidVoiceInputController,
            sherpaOnnxVoiceInputController = sherpaOnnxVoiceInputController,
            whisperVoiceInputController = whisperVoiceInputController,
            parakeetVoiceInputController = parakeetVoiceInputController,
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
        every { whisperVoiceInputController.events } returns MutableSharedFlow()
        every { parakeetVoiceInputController.events } returns MutableSharedFlow()
        every { voskOfflineVoiceInputController.stopListening() } just runs
        every { nativeAndroidVoiceInputController.stopListening() } just runs
        every { sherpaOnnxVoiceInputController.stopListening() } just runs
        every { whisperVoiceInputController.stopListening() } just runs
        every { parakeetVoiceInputController.stopListening() } just runs
        coEvery { nativeAndroidVoiceInputController.startListening(VoiceCaptureMode.Command) } coAnswers {
            nativeEvents.emit(
                VoiceInputEvent.Error(
                    mode = VoiceCaptureMode.Command,
                    message = "startup failed",
                ),
            )
            VoiceInputStartResult.Started
        }

        val controller = SelectableVoiceInputController(
            voiceInputPreferences = voiceInputPreferences,
            voskOfflineVoiceInputController = voskOfflineVoiceInputController,
            nativeAndroidVoiceInputController = nativeAndroidVoiceInputController,
            sherpaOnnxVoiceInputController = sherpaOnnxVoiceInputController,
            whisperVoiceInputController = whisperVoiceInputController,
            parakeetVoiceInputController = parakeetVoiceInputController,
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
    fun `stopListening stops all controllers to avoid orphaned sessions`() = runTest(dispatcher) {
        every { voiceInputPreferences.selectedEngine } returns MutableStateFlow(VoiceInputEngine.Vosk)
        every { voskOfflineVoiceInputController.events } returns MutableSharedFlow()
        every { nativeAndroidVoiceInputController.events } returns MutableSharedFlow()
        every { sherpaOnnxVoiceInputController.events } returns MutableSharedFlow()
        every { whisperVoiceInputController.events } returns MutableSharedFlow()
        every { parakeetVoiceInputController.events } returns MutableSharedFlow()
        every { voskOfflineVoiceInputController.stopListening() } just runs
        every { nativeAndroidVoiceInputController.stopListening() } just runs
        every { sherpaOnnxVoiceInputController.stopListening() } just runs
        every { whisperVoiceInputController.stopListening() } just runs
        every { parakeetVoiceInputController.stopListening() } just runs

        val controller = SelectableVoiceInputController(
            voiceInputPreferences = voiceInputPreferences,
            voskOfflineVoiceInputController = voskOfflineVoiceInputController,
            nativeAndroidVoiceInputController = nativeAndroidVoiceInputController,
            sherpaOnnxVoiceInputController = sherpaOnnxVoiceInputController,
            whisperVoiceInputController = whisperVoiceInputController,
            parakeetVoiceInputController = parakeetVoiceInputController,
        )

        controller.stopListening()

        verify(exactly = 1) { voskOfflineVoiceInputController.stopListening() }
        verify(exactly = 1) { nativeAndroidVoiceInputController.stopListening() }
        verify(exactly = 1) { sherpaOnnxVoiceInputController.stopListening() }
        verify(exactly = 1) { whisperVoiceInputController.stopListening() }
        verify(exactly = 1) { parakeetVoiceInputController.stopListening() }
    }
}
