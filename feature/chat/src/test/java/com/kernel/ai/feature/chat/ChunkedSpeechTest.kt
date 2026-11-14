package com.kernel.ai.feature.chat

import com.kernel.ai.core.voice.VoiceOutputController
import com.kernel.ai.core.voice.VoiceOutputResult
import com.kernel.ai.core.voice.VoiceOutputStreamingSession
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChunkedSpeechTest {
    private val appended = mutableListOf<Pair<String, Boolean>>()
    private val session = object : VoiceOutputStreamingSession {
        override suspend fun append(text: String, isFinal: Boolean): VoiceOutputResult {
            appended += text to isFinal
            return VoiceOutputResult.Spoken
        }
    }
    private val controller: VoiceOutputController = mockk {
        coEvery { openStreamingSession(any()) } returns session
    }

    @Test
    fun `a first sentence over 80 characters is not cut at a comma`() = runTest {
        val reply = "In Palmerston North, Manawatu-Wanganui, it's 24 degrees with partly cloudy skies " +
            "and a light breeze, feeling like 25 degrees. Today's high is 28 degrees, low is 21 degrees."

        assertEquals(VoiceOutputResult.Spoken, speakInSentenceChunks(controller, reply))

        assertTrue(appended.first().first.endsWith("feeling like 25 degrees."), "first chunk: ${appended.first().first}")
        assertTrue(appended.last().second, "last chunk must be final")
    }

    @Test
    fun `a multi-day forecast never starts a chunk with degrees`() = runTest {
        val reply = "In Palmerston North, it's 12 degrees, thunderstorm with hail, feeling like 9 degrees. " +
            "Today's high is 18 degrees, low is 10 degrees. " +
            "Sun 16 Nov: high 28 degrees, low 21 degrees. " +
            "Mon 17 Nov: high 26 degrees, low 19 degrees."

        speakInSentenceChunks(controller, reply)

        assertTrue(appended.none { it.first.trimStart().startsWith("degrees") }, "chunks: $appended")
    }

    @Test
    fun `abbreviations and clock times do not end a chunk`() = runTest {
        val reply = "Your project review meeting with the design team starts at 10:30 a.m. tomorrow, " +
            "so bring the notes. Dr. Smith will confirm the room."

        speakInSentenceChunks(controller, reply)

        assertTrue(appended.first().first.contains("tomorrow"), "first chunk split at a.m.: $appended")
        assertTrue(appended.none { it.first.trimEnd().endsWith("Dr.") }, "split after Dr.: $appended")
        assertTrue(appended.last().second, "last chunk must be final")
    }

    @Test
    fun `a long weather reply is spoken in several chunks with the last one final`() = runTest {
        val weather = "In Brisbane, it's 22 degrees, drizzle, feeling like 25 degrees. " +
            "Today's high is 28 degrees, low is 21 degrees. " +
            "Rain chance is 87 percent, so take an umbrella if you're heading out this afternoon."

        assertEquals(VoiceOutputResult.Spoken, speakInSentenceChunks(controller, weather))

        assertTrue(appended.size > 1, "expected more than one chunk, got $appended")
        assertTrue(appended.last().second, "last chunk must be final")
        assertTrue(appended.dropLast(1).none { it.second }, "only the last chunk is final")
        assertEquals(
            weather.replace(Regex("""\s+"""), " ").trim(),
            appended.joinToString(" ") { it.first }.replace(Regex("""\s+"""), " ").trim(),
        )
    }

    @Test
    fun `a short reply is one final chunk`() = runTest {
        speakInSentenceChunks(controller, "It's 6:22 AM.")
        assertEquals(listOf("It's 6:22 AM." to true), appended)
    }

    @Test
    fun `a chunk the engine rejects closes the session and reports unavailable`() = runTest {
        val calls = mutableListOf<Pair<String, Boolean>>()
        val failing = object : VoiceOutputStreamingSession {
            override suspend fun append(text: String, isFinal: Boolean): VoiceOutputResult {
                calls += text to isFinal
                return if (calls.size == 1) VoiceOutputResult.Unavailable("engine gone") else VoiceOutputResult.Spoken
            }
        }
        val failingController: VoiceOutputController = mockk {
            coEvery { openStreamingSession(any()) } returns failing
        }
        val text = "In Brisbane, it's 22 degrees, drizzle, feeling like 25 degrees. " +
            "Today's high is 28 degrees, low is 21 degrees. " +
            "Rain chance is 87 percent, so take an umbrella if you're heading out this afternoon."

        assertEquals(
            VoiceOutputResult.Unavailable("engine gone"),
            speakInSentenceChunks(failingController, text),
        )
        assertEquals(2, calls.size, "expected the failed chunk then a closing final append: $calls")
        assertEquals("" to true, calls.last())
    }

    @Test
    fun `cancellation mid-loop sends a final close to the session`() = runTest {
        val firstChunkAppended = CompletableDeferred<Unit>()
        val hangingSession = object : VoiceOutputStreamingSession {
            override suspend fun append(text: String, isFinal: Boolean): VoiceOutputResult {
                appended += text to isFinal
                if (appended.size == 1) {
                    firstChunkAppended.complete(Unit)
                    awaitCancellation() // Hold between chunks so cancellation lands mid-loop.
                }
                return VoiceOutputResult.Spoken
            }
        }
        coEvery { controller.openStreamingSession(any()) } returns hangingSession
        val longText = "First sentence of a long enough reply to need chunking. " +
            "Second sentence that would otherwise keep streaming. Third sentence for good measure."

        val job = launch { speakInSentenceChunks(controller, longText) }
        firstChunkAppended.await()
        job.cancel()
        job.join()

        // The default VoiceOutputStreamingSession.cancel() closes with an empty final append,
        // so Android TTS marks the final chunk queued and Sherpa closes its chunk channel.
        assertEquals("" to true, appended.last(), "cancellation must close the session, got $appended")
        assertTrue(appended.dropLast(1).none { it.second }, "no content chunk may be final: $appended")
    }

    @Test
    fun `cancellation invokes a session cancel override instead of appending`() = runTest {
        val firstChunkAppended = CompletableDeferred<Unit>()
        var cancelled = false
        val hangingSession = object : VoiceOutputStreamingSession {
            override suspend fun append(text: String, isFinal: Boolean): VoiceOutputResult {
                appended += text to isFinal
                firstChunkAppended.complete(Unit)
                awaitCancellation()
            }

            override suspend fun cancel() {
                cancelled = true
            }
        }
        coEvery { controller.openStreamingSession(any()) } returns hangingSession

        val job = launch {
            speakInSentenceChunks(controller, "A reply long enough to be split into chunks, with more than one sentence. Second sentence here.")
        }
        firstChunkAppended.await()
        job.cancel()
        job.join()

        assertTrue(cancelled, "session.cancel() must run on cancellation")
        assertTrue(appended.none { it.second }, "no final append expected when cancel() is overridden: $appended")
    }
}
