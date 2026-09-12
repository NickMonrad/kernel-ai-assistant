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
    private val inflect: InflectMicroVoiceOutputController = mockk(relaxed = true)
    private val androidTts: AndroidTextToSpeechController = mockk(relaxed = true)
    private val selectedEngine = MutableStateFlow(VoiceOutputEngine.AndroidTts)

    private val sherpaEvents = MutableSharedFlow<VoiceOutputEvent>(extraBufferCapacity = 8)
    private val inflectEvents = MutableSharedFlow<VoiceOutputEvent>(extraBufferCapacity = 8)
    private val androidEvents = MutableSharedFlow<VoiceOutputEvent>(extraBufferCapacity = 8)
    @BeforeEach
    fun setup() {
        every { voiceOutputPreferences.selectedEngine } returns selectedEngine
        every { sherpa.events } returns sherpaEvents
        every { inflect.events } returns inflectEvents
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
    fun `speak routes to Sherpa when Kokoro engine is selected`() = runTest(dispatcher) {
        selectedEngine.value = VoiceOutputEngine.KokoroExperimental
        val request = VoiceSpeakRequest("Kokoro regression")
        coEvery { sherpa.warmUp() } returns VoiceOutputResult.Spoken
        coEvery { sherpa.speak(request) } returns VoiceOutputResult.Spoken

        val controller = buildController()
        val result = controller.speak(request)

        assertEquals(VoiceOutputResult.Spoken, result)
        coVerify(exactly = 1) { sherpa.warmUp() }
        coVerify(exactly = 1) { sherpa.speak(request) }
        coVerify(exactly = 0) { inflect.speak(any()) }
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
    fun `openStreamingSession routes to Sherpa when Sherpa is selected and available`() =
        runTest(dispatcher) {
            selectedEngine.value = VoiceOutputEngine.SherpaExperimental
            val request = VoiceSpeakRequest("Kia ora")
            val session = mockk<VoiceOutputStreamingSession>()
            coEvery { sherpa.warmUp() } returns VoiceOutputResult.Spoken
            coEvery { sherpa.openStreamingSession(request) } returns session

            val controller = buildController()
            val result = controller.openStreamingSession(request)

            assertEquals(session, result)
            coVerify(exactly = 1) { sherpa.openStreamingSession(request) }
            coVerify(exactly = 0) { androidTts.openStreamingSession(any()) }
        }

    @Test
    fun `openStreamingSession falls back to Android TTS when Sherpa is unavailable`() =
        runTest(dispatcher) {
            selectedEngine.value = VoiceOutputEngine.SherpaExperimental
            val request = VoiceSpeakRequest("Fallback stream")
            val session = mockk<VoiceOutputStreamingSession>()
            coEvery { sherpa.warmUp() } returns VoiceOutputResult.Unavailable("no AAR")
            coEvery { androidTts.warmUp() } returns VoiceOutputResult.Spoken
            coEvery { androidTts.openStreamingSession(request) } returns session

            val controller = buildController()
            val result = controller.openStreamingSession(request)

            assertEquals(session, result)
            coVerify(exactly = 1) { sherpa.warmUp() }
            coVerify(exactly = 1) { androidTts.warmUp() }
            coVerify(exactly = 1) { androidTts.openStreamingSession(request) }
        }

    @Test
    fun `speak routes to Inflect when Inflect engine is selected and available`() = runTest(dispatcher) {
        selectedEngine.value = VoiceOutputEngine.InflectMicroExperimental
        val request = VoiceSpeakRequest("Inflect debug")
        coEvery { inflect.warmUp() } returns VoiceOutputResult.Spoken
        coEvery { inflect.speak(request) } returns VoiceOutputResult.Spoken

        val controller = buildController()
        val result = controller.speak(request)

        assertEquals(VoiceOutputResult.Spoken, result)
        coVerify(exactly = 1) { inflect.warmUp() }
        coVerify(exactly = 1) { inflect.speak(request) }
        coVerify(exactly = 0) { androidTts.speak(any()) }
    }
    @Test
    fun `streaming Inflect success does not use Android TTS`() = runTest(dispatcher) {
        selectedEngine.value = VoiceOutputEngine.InflectMicroExperimental
        val request = VoiceSpeakRequest("Inflect stream")
        val inflectSession = mockk<VoiceOutputStreamingSession>()
        coEvery { inflect.warmUp() } returns VoiceOutputResult.Spoken
        coEvery { inflect.openStreamingSession(request) } returns inflectSession
        coEvery { inflectSession.append("Inflect stream", true) } returns VoiceOutputResult.Spoken

        val session = buildController().openStreamingSession(request)
        val result = session.append("Inflect stream", isFinal = true)

        assertEquals(VoiceOutputResult.Spoken, result)
        coVerify(exactly = 1) { inflectSession.append("Inflect stream", true) }
        coVerify(exactly = 0) { androidTts.warmUp() }
        coVerify(exactly = 0) { androidTts.speak(any()) }
    }

    @Test
    fun `streaming Inflect final failure speaks the full buffered response with Android TTS`() =
        runTest(dispatcher) {
            selectedEngine.value = VoiceOutputEngine.InflectMicroExperimental
            val request = VoiceSpeakRequest(text = "")
            val inflectSession = mockk<VoiceOutputStreamingSession>()
            val fullText = "The sky appears blue due to Rayleigh scattering."
            coEvery { inflect.warmUp() } returns VoiceOutputResult.Spoken
            coEvery { inflect.openStreamingSession(request) } returns inflectSession
            coEvery { inflectSession.append("The sky appears blue due to ", false) } returns
                VoiceOutputResult.Spoken
            coEvery { inflectSession.append("Rayleigh scattering.", true) } returns
                VoiceOutputResult.Unavailable("phonemizer failed")
            coEvery { androidTts.warmUp() } returns VoiceOutputResult.Spoken
            coEvery { androidTts.speak(request.copy(text = fullText)) } returns VoiceOutputResult.Spoken

            val session = buildController().openStreamingSession(request)
            session.append("The sky appears blue due to ", isFinal = false)
            val result = session.append("Rayleigh scattering.", isFinal = true)

            assertEquals(VoiceOutputResult.Spoken, result)
            coVerify(exactly = 1) { androidTts.warmUp() }
            coVerify(exactly = 1) { androidTts.speak(request.copy(text = fullText)) }
            coVerify(exactly = 1) { inflectSession.append("The sky appears blue due to ", false) }
            coVerify(exactly = 1) { inflectSession.append("Rayleigh scattering.", true) }
            assertEquals(VoiceOutputResult.Spoken, session.append("ignored", isFinal = true))
            coVerify(exactly = 1) { androidTts.warmUp() }
        }

    @Test
    fun `streaming Inflect warmUp failure still uses Android TTS session`() = runTest(dispatcher) {
        selectedEngine.value = VoiceOutputEngine.InflectMicroExperimental
        val request = VoiceSpeakRequest("Fallback stream")
        val androidSession = mockk<VoiceOutputStreamingSession>()
        coEvery { inflect.warmUp() } returns VoiceOutputResult.Unavailable("models missing")
        coEvery { androidTts.warmUp() } returns VoiceOutputResult.Spoken
        coEvery { androidTts.openStreamingSession(request) } returns androidSession

        val result = buildController().openStreamingSession(request)

        assertEquals(androidSession, result)
        coVerify(exactly = 1) { androidTts.warmUp() }
        coVerify(exactly = 1) { androidTts.openStreamingSession(request) }
        coVerify(exactly = 0) { inflect.openStreamingSession(any()) }
    }

    @Test
    fun `streaming Inflect failure returns Android Unavailable when fallback warmUp fails`() =
        runTest(dispatcher) {
            selectedEngine.value = VoiceOutputEngine.InflectMicroExperimental
            val request = VoiceSpeakRequest(text = "")
            val inflectSession = mockk<VoiceOutputStreamingSession>()
            val androidUnavailable = VoiceOutputResult.Unavailable("Android TTS unavailable")
            coEvery { inflect.warmUp() } returns VoiceOutputResult.Spoken
            coEvery { inflect.openStreamingSession(request) } returns inflectSession
            coEvery { inflectSession.append("Response text", true) } returns
                VoiceOutputResult.Unavailable("phonemizer failed")
            coEvery { androidTts.warmUp() } returns androidUnavailable

            val session = buildController().openStreamingSession(request)
            val result = session.append("Response text", isFinal = true)

            assertEquals(androidUnavailable, result)
            coVerify(exactly = 1) { androidTts.warmUp() }
            coVerify(exactly = 0) { androidTts.speak(any()) }
        }

    @Test
    fun `streaming Inflect failure does not change selected engine for the next turn`() =
        runTest(dispatcher) {
            selectedEngine.value = VoiceOutputEngine.InflectMicroExperimental
            val request = VoiceSpeakRequest(text = "")
            val firstInflectSession = mockk<VoiceOutputStreamingSession>()
            val secondInflectSession = mockk<VoiceOutputStreamingSession>()
            coEvery { inflect.warmUp() } returns VoiceOutputResult.Spoken
            coEvery { inflect.openStreamingSession(request) } returnsMany
                listOf(firstInflectSession, secondInflectSession)
            coEvery { firstInflectSession.append("First response", true) } returns
                VoiceOutputResult.Unavailable("phonemizer failed")
            coEvery { secondInflectSession.append("Second response", true) } returns
                VoiceOutputResult.Spoken
            coEvery { androidTts.warmUp() } returns VoiceOutputResult.Spoken
            coEvery { androidTts.speak(request.copy(text = "First response")) } returns
                VoiceOutputResult.Spoken

            val controller = buildController()
            val firstSession = controller.openStreamingSession(request)
            assertEquals(
                VoiceOutputResult.Spoken,
                firstSession.append("First response", isFinal = true),
            )
            val secondSession = controller.openStreamingSession(request)
            val secondResult = secondSession.append("Second response", isFinal = true)

            assertEquals(VoiceOutputResult.Spoken, secondResult)
            assertEquals(VoiceOutputEngine.InflectMicroExperimental, selectedEngine.value)
            coVerify(exactly = 2) { inflect.warmUp() }
            coVerify(exactly = 2) { inflect.openStreamingSession(request) }
            coVerify(exactly = 1) { androidTts.speak(any()) }
        }

    @Test
    fun `stop stops all controllers defensively`() = runTest(dispatcher) {
        every { sherpa.stop() } just runs
        every { inflect.stop() } just runs
        every { androidTts.stop() } just runs

        val controller = buildController()
        controller.stop()

        verify(exactly = 1) { sherpa.stop() }
        verify(exactly = 1) { inflect.stop() }
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
        inflect = inflect,
        androidTts = androidTts,
    )
}
