package com.kernel.ai.core.voice

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BufferedVoiceOutputStreamingSessionTest {

    private class RecordingController : VoiceOutputController {
        val spoken = mutableListOf<String>()

        override suspend fun speak(request: VoiceSpeakRequest): VoiceOutputResult {
            spoken += request.text
            return VoiceOutputResult.Spoken
        }

        override fun stop() {}
    }

    @Test
    fun `chunks without a separator are joined with one space before speaking`() = runTest {
        val controller = RecordingController()
        val session = controller.openStreamingSession(VoiceSpeakRequest(text = ""))

        session.append("It is 11 degrees.")
        session.append("Today's forecast is clear.", isFinal = true)

        assertEquals(listOf("It is 11 degrees. Today's forecast is clear."), controller.spoken)
    }

    @Test
    fun `no extra space is added when a chunk already starts with whitespace`() = runTest {
        val controller = RecordingController()
        val session = controller.openStreamingSession(VoiceSpeakRequest(text = ""))

        session.append("Hello")
        session.append(" world.", isFinal = true)

        assertEquals(listOf("Hello world."), controller.spoken)
    }
}
