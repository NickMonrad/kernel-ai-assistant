package com.kernel.ai.core.voice.kokoro

import com.kernel.ai.core.voice.VoiceOutputResult
import com.kernel.ai.core.voice.VoicePcmSink
import com.kernel.ai.core.voice.VoicePcmTrack
import com.kernel.ai.core.voice.VoiceReplyHooks
import com.kernel.ai.core.voice.VoiceReplyStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Drives [KokoroReplySession] through a real [VoiceReplyStream] with fake PCM seams (#2117 mid-reply hand-off). */
class KokoroReplySessionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Counts waveforms written to the track: one per sentence that reached the speaker. */
    private class CountingSink : VoicePcmSink {
        @Volatile var writes = 0

        override fun open(sampleRate: Int, pitch: Float): VoicePcmTrack = object : VoicePcmTrack {
            override fun write(samples: FloatArray, gain: Float, isCurrent: () -> Boolean) {
                writes++
            }

            override fun drain(isCurrent: () -> Boolean) {}

            override fun discard() {}

            override fun release() {}
        }

        override fun silenceActive() {}
    }

    private object NoHooks : VoiceReplyHooks {
        override fun onPlaybackStarting(generation: Long, text: String) {}

        override fun onPlaybackEnded(generation: Long) {}
    }

    private fun session(
        sink: CountingSink,
        isCurrent: () -> Boolean,
        synthesize: suspend (String) -> FloatArray?,
        speakRemainder: (suspend (String) -> VoiceOutputResult)?,
    ): KokoroReplySession {
        val handoff = KokoroHandoff()
        val stream: VoiceReplyStream<KokoroUnit> = VoiceReplyStream(
            engine = "Kokoro",
            spokenText = "test",
            generation = 1L,
            isCurrent = isCurrent,
            unavailable = null,
            chunker = { text, _ -> kokoroSentenceUnits(text, handoff) { listOf(it) } },
            synthesize = { unit -> handoff.synthesizeOrHandOff(unit, synthesize) },
            sink = sink,
            sampleRate = 24_000,
            hooks = NoHooks,
            gain = 1f,
            pitch = 1f,
            scope = scope,
        )
        return KokoroReplySession(stream, handoff, KokoroReplyTiming(1L), "test", isCurrent, speakRemainder, scope)
    }

    private fun failOn(failing: String): suspend (String) -> FloatArray? = { sentence ->
        if (sentence == failing) throw IllegalStateException("device fault") else FloatArray(100)
    }

    @Test
    fun `a chunk that fails mid-reply hands the rest of the reply to the remainder speaker once`() {
        runBlocking {
            val sink = CountingSink()
            val remainder = mutableListOf<String>()
            val session = session(
                sink = sink,
                isCurrent = { true },
                synthesize = failOn("Two."),
                speakRemainder = { text ->
                    remainder += text
                    VoiceOutputResult.Spoken
                },
            )

            val result = session.append("One. Two. Three.", isFinal = true)
            session.awaitPlayback()

            assertEquals(VoiceOutputResult.Spoken, result)
            assertEquals(listOf("Two. Three."), remainder)
            assertEquals(1, sink.writes)
        }
    }

    @Test
    fun `text appended after a mid-reply failure joins the remainder in order`() {
        runBlocking {
            val remainder = mutableListOf<String>()
            val session = session(
                sink = CountingSink(),
                isCurrent = { true },
                synthesize = failOn("Two."),
                speakRemainder = { text ->
                    remainder += text
                    VoiceOutputResult.Spoken
                },
            )

            session.append("One. Two.", isFinal = false)
            session.append("Four.", isFinal = false)
            session.append("Five.", isFinal = true)

            assertEquals(listOf("Two. Four. Five."), remainder)
        }
    }

    @Test
    fun `a reply whose first chunk fails has nothing to hand over and reports Unavailable`() {
        runBlocking {
            val sink = CountingSink()
            val remainder = mutableListOf<String>()
            val session = session(
                sink = sink,
                isCurrent = { true },
                synthesize = failOn("One."),
                speakRemainder = { text ->
                    remainder += text
                    VoiceOutputResult.Spoken
                },
            )

            val result = session.append("One. Two.", isFinal = true)
            session.awaitPlayback()

            assertTrue(result is VoiceOutputResult.Unavailable)
            assertTrue((result as VoiceOutputResult.Unavailable).message.contains("device fault"))
            assertEquals(emptyList<String>(), remainder)
            assertEquals(0, sink.writes)
        }
    }

    @Test
    fun `stop during a failed reply abandons the remainder`() {
        runBlocking {
            var current = true
            val remainder = mutableListOf<String>()
            val session = session(
                sink = CountingSink(),
                isCurrent = { current },
                synthesize = { sentence ->
                    if (sentence == "Two.") {
                        current = false
                        throw IllegalStateException("stopped")
                    }
                    FloatArray(100)
                },
                speakRemainder = { text ->
                    remainder += text
                    VoiceOutputResult.Spoken
                },
            )

            val result = session.append("One. Two. Three.", isFinal = true)

            assertEquals(VoiceOutputResult.Spoken, result)
            assertEquals(emptyList<String>(), remainder)
        }
    }

    @Test
    fun `a reply without failures plays every sentence and never calls the remainder speaker`() {
        runBlocking {
            val sink = CountingSink()
            val remainder = mutableListOf<String>()
            val session = session(
                sink = sink,
                isCurrent = { true },
                synthesize = { FloatArray(100) },
                speakRemainder = { text ->
                    remainder += text
                    VoiceOutputResult.Spoken
                },
            )

            val result = session.append("One. Two. Three.", isFinal = true)
            session.awaitPlayback()

            assertEquals(VoiceOutputResult.Spoken, result)
            assertEquals(3, sink.writes)
            assertEquals(emptyList<String>(), remainder)
        }
    }
}
