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
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FallbackVoiceOutputControllerTest {

    private val dispatcher = StandardTestDispatcher()

    private val voiceOutputPreferences: VoiceOutputPreferences = mockk()
    private val sherpa: SherpaOnnxVoiceOutputController = mockk(relaxed = true)
    private val androidTts: AndroidTextToSpeechController = mockk(relaxed = true)
    private val selectedEngine = MutableStateFlow(VoiceOutputEngine.AndroidTts)

    private val sherpaEvents = MutableSharedFlow<VoiceOutputEvent>(extraBufferCapacity = 8)
    private val androidEvents = MutableSharedFlow<VoiceOutputEvent>(extraBufferCapacity = 8)

    @BeforeEach
    fun setup() {
        every { voiceOutputPreferences.selectedEngine } returns selectedEngine
        every { sherpa.events } returns sherpaEvents
        every { androidTts.events } returns androidEvents
    }

    @Test
    fun `warmUp selects Android TTS when Android engine is preferred`() = runTest(dispatcher) {
        selectedEngine.value = VoiceOutputEngine.AndroidTts
        coEvery { androidTts.warmUp() } returns VoiceOutputResult.Spoken

        val controller = buildController()
        val result = controller.warmUp()

        assertEquals(VoiceOutputResult.Spoken, result)
        coVerify(exactly = 0) { sherpa.warmUp() }
        coVerify(exactly = 1) { androidTts.warmUp() }
    }

    @Test
    fun `warmUp selects Sherpa when Sherpa engine is preferred and available`() =
        runTest(dispatcher) {
            selectedEngine.value = VoiceOutputEngine.SherpaExperimental
            coEvery { sherpa.warmUp() } returns VoiceOutputResult.Spoken

            val controller = buildController()
            val result = controller.warmUp()

            assertEquals(VoiceOutputResult.Spoken, result)
            coVerify(exactly = 1) { sherpa.warmUp() }
            coVerify(exactly = 0) { androidTts.warmUp() }
        }

    @Test
    fun `warmUp falls back to Android TTS when Sherpa is unavailable`() = runTest(dispatcher) {
        selectedEngine.value = VoiceOutputEngine.SherpaExperimental
        coEvery { sherpa.warmUp() } returns VoiceOutputResult.Unavailable("AAR missing")
        coEvery { androidTts.warmUp() } returns VoiceOutputResult.Spoken

        val controller = buildController()
        val result = controller.warmUp()

        assertEquals(VoiceOutputResult.Spoken, result)
        coVerify(exactly = 1) { sherpa.warmUp() }
        coVerify(exactly = 1) { androidTts.warmUp() }
    }

    @Test
    fun `warmUp returns Unavailable when both backends fail`() = runTest(dispatcher) {
        selectedEngine.value = VoiceOutputEngine.SherpaExperimental
        coEvery { sherpa.warmUp() } returns VoiceOutputResult.Unavailable("no AAR")
        coEvery { androidTts.warmUp() } returns VoiceOutputResult.Unavailable("no TTS engine")

        val controller = buildController()
        val result = controller.warmUp()

        assertInstanceOf(VoiceOutputResult.Unavailable::class.java, result)
        coVerify(exactly = 1) { sherpa.warmUp() }
        coVerify(exactly = 1) { androidTts.warmUp() }
    }

    @Test
    fun `speak routes to Android TTS when Android engine is selected`() = runTest(dispatcher) {
        selectedEngine.value = VoiceOutputEngine.AndroidTts
        val request = VoiceSpeakRequest("Hello fallback")
        coEvery { androidTts.speak(request) } returns VoiceOutputResult.Spoken

        val controller = buildController()
        val result = controller.speak(request)

        assertEquals(VoiceOutputResult.Spoken, result)
        coVerify(exactly = 0) { sherpa.warmUp() }
        coVerify(exactly = 0) { sherpa.speak(any()) }
        coVerify(exactly = 1) { androidTts.speak(request) }
    }

    @Test
    fun `speak routes to Sherpa when Sherpa engine is selected and available`() =
        runTest(dispatcher) {
            selectedEngine.value = VoiceOutputEngine.SherpaExperimental
            val request = VoiceSpeakRequest("Kia ora")
            coEvery { sherpa.warmUp() } returns VoiceOutputResult.Spoken
            coEvery { sherpa.speak(request) } returns VoiceOutputResult.Spoken

            val controller = buildController()
            val result = controller.speak(request)

            assertEquals(VoiceOutputResult.Spoken, result)
            coVerify(exactly = 1) { sherpa.warmUp() }
            coVerify(exactly = 1) { sherpa.speak(request) }
            coVerify(exactly = 0) { androidTts.speak(any()) }
        }

    @Test
    fun `speak falls back to Android TTS when Sherpa warmUp is unavailable`() = runTest(dispatcher) {
        selectedEngine.value = VoiceOutputEngine.SherpaExperimental
        val request = VoiceSpeakRequest("Lazy init")
        coEvery { sherpa.warmUp() } returns VoiceOutputResult.Unavailable("no AAR")
        coEvery { androidTts.warmUp() } returns VoiceOutputResult.Spoken
        coEvery { androidTts.speak(request) } returns VoiceOutputResult.Spoken

        val controller = buildController()
        val result = controller.speak(request)

        assertEquals(VoiceOutputResult.Spoken, result)
        coVerify(exactly = 1) { sherpa.warmUp() }
        coVerify(exactly = 1) { androidTts.warmUp() }
        coVerify(exactly = 1) { androidTts.speak(request) }
    }

    @Test
    fun `speak falls back to Android TTS when the selected Sherpa voice pack is not downloaded`() =
        runTest(dispatcher) {
            selectedEngine.value = VoiceOutputEngine.SherpaExperimental
            val request = VoiceSpeakRequest("Fallback voice pack")
            coEvery { sherpa.warmUp() } returns VoiceOutputResult.Unavailable("Voice pack not downloaded")
            coEvery { androidTts.warmUp() } returns VoiceOutputResult.Spoken
            coEvery { androidTts.speak(request) } returns VoiceOutputResult.Spoken

            val controller = buildController()
            val result = controller.speak(request)

            assertEquals(VoiceOutputResult.Spoken, result)
            coVerify(exactly = 1) { sherpa.warmUp() }
            coVerify(exactly = 1) { androidTts.warmUp() }
            coVerify(exactly = 1) { androidTts.speak(request) }
        }

    @Test
    fun `speak falls back to Android TTS when Sherpa speak fails`() = runTest(dispatcher) {
        selectedEngine.value = VoiceOutputEngine.SherpaExperimental
        val request = VoiceSpeakRequest("Fallback after Sherpa error")
        coEvery { sherpa.warmUp() } returns VoiceOutputResult.Spoken
        coEvery { sherpa.speak(request) } returns VoiceOutputResult.Unavailable("generation failed")
        coEvery { androidTts.warmUp() } returns VoiceOutputResult.Spoken
        coEvery { androidTts.speak(request) } returns VoiceOutputResult.Spoken

        val controller = buildController()
        val result = controller.speak(request)

        assertEquals(VoiceOutputResult.Spoken, result)
        coVerify(exactly = 1) { sherpa.speak(request) }
        coVerify(exactly = 1) { androidTts.speak(request) }
    }

    @Test
    fun `stop stops both controllers defensively`() = runTest(dispatcher) {
        every { sherpa.stop() } just runs
        every { androidTts.stop() } just runs

        val controller = buildController()
        controller.stop()

        verify(exactly = 1) { sherpa.stop() }
        verify(exactly = 1) { androidTts.stop() }
    }

    @Test
    fun `events come from Sherpa when Sherpa is selected`() = runTest(dispatcher) {
        selectedEngine.value = VoiceOutputEngine.SherpaExperimental
        coEvery { sherpa.warmUp() } returns VoiceOutputResult.Spoken

        val controller = buildController()
        controller.warmUp()
        advanceUntilIdle()

        val received = mutableListOf<VoiceOutputEvent>()
        val collectJob = launch { controller.events.collect { received += it } }
        advanceUntilIdle()

        sherpaEvents.emit(VoiceOutputEvent.SpeakingStarted("Sherpa speaking"))
        androidEvents.emit(VoiceOutputEvent.SpeakingStarted("Android speaking"))
        advanceUntilIdle()

        assertEquals(
            listOf(VoiceOutputEvent.SpeakingStarted("Sherpa speaking")),
            received,
        )
        collectJob.cancel()
    }

    @Test
    fun `events come from Android TTS when it is the fallback`() = runTest(dispatcher) {
        selectedEngine.value = VoiceOutputEngine.SherpaExperimental
        coEvery { sherpa.warmUp() } returns VoiceOutputResult.Unavailable("no AAR")
        coEvery { androidTts.warmUp() } returns VoiceOutputResult.Spoken

        val controller = buildController()
        controller.warmUp()
        advanceUntilIdle()

        val received = mutableListOf<VoiceOutputEvent>()
        val collectJob = launch { controller.events.collect { received += it } }
        advanceUntilIdle()

        sherpaEvents.emit(VoiceOutputEvent.SpeakingStarted("Sherpa (should be ignored)"))
        androidEvents.emit(VoiceOutputEvent.SpeakingStopped)
        advanceUntilIdle()

        assertEquals(listOf(VoiceOutputEvent.SpeakingStopped), received)
        collectJob.cancel()
    }

    private fun buildController() = FallbackVoiceOutputController(
        voiceOutputPreferences = voiceOutputPreferences,
        sherpa = sherpa,
        androidTts = androidTts,
    )
}
