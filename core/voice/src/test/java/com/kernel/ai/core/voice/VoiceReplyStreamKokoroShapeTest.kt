package com.kernel.ai.core.voice

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The shared stream with a Kokoro-shaped engine: units are plain text chunks (as `KokoroEngine.splitText`
 * returns), synthesis is a fake waveform per chunk at 24 kHz. Complements [VoiceReplyStreamTest], which drives the
 * Inflect unit type, so both engines are covered by the same stream contract.
 */
class VoiceReplyStreamKokoroShapeTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val chunks = listOf("first chunk.", "second chunk.", "third chunk.")

    private class Track(private val onWrite: (Int) -> Unit) : VoicePcmTrack {
        val writes = mutableListOf<Float>()
        var drained = false
        var discarded = false

        override fun write(samples: FloatArray, gain: Float, isCurrent: () -> Boolean) {
            writes += samples.max()
            onWrite(writes.size)
        }

        override fun drain(isCurrent: () -> Boolean) {
            drained = true
        }

        override fun discard() {
            discarded = true
        }

        override fun release() = Unit
    }

    private class Sink : VoicePcmSink {
        val tracks = mutableListOf<Track>()
        val firstWriteEntered = CountDownLatch(1)
        var blockFirstWrite: CountDownLatch? = null

        override fun open(sampleRate: Int, pitch: Float): VoicePcmTrack {
            check(sampleRate == 24_000) { "Kokoro shape expects 24 kHz, got $sampleRate" }
            return Track { count ->
                if (count == 1) {
                    firstWriteEntered.countDown()
                    blockFirstWrite?.await(5, TimeUnit.SECONDS)
                }
            }.also { tracks += it }
        }

        override fun silenceActive() = Unit
    }

    private object NoHooks : VoiceReplyHooks {
        override fun onPlaybackStarting(generation: Long, text: String) = Unit
        override fun onPlaybackEnded(generation: Long) = Unit
    }

    private fun stream(
        text: String,
        sink: Sink,
        generation: AtomicLong,
        myGeneration: Long,
        synthesize: suspend (String) -> FloatArray?,
    ) = VoiceReplyStream<String>(
        engine = "Kokoro",
        spokenText = text,
        generation = myGeneration,
        isCurrent = { generation.get() == myGeneration },
        unavailable = null,
        chunker = { raw, _ ->
            // Stands in for splitText: one unit per sentence, in order, as the lane's partition yields them.
            raw.split(Regex("(?<=\\.)\\s+")).asSequence().filter(String::isNotBlank)
        },
        synthesize = synthesize,
        sink = sink,
        sampleRate = 24_000,
        hooks = NoHooks,
        gain = 1f,
        pitch = 1f,
        scope = scope,
    )

    @Test
    fun `kokoro-shaped chunks play in order on one track and the track drains on the final append`() = runBlocking {
        val sink = Sink()
        val generation = AtomicLong(1)
        val synthesized = mutableListOf<String>()
        val s = stream(chunks.joinToString(" "), sink, generation, 1) { chunk ->
            synthesized += chunk
            FloatArray(480) { synthesized.size.toFloat() }
        }

        assertEquals(VoiceOutputResult.Spoken, s.append(chunks.joinToString(" "), isFinal = true))
        s.awaitPlayback()

        assertEquals(chunks, synthesized)
        val track = sink.tracks.single()
        assertEquals(listOf(1f, 2f, 3f), track.writes)
        assertTrue(track.drained)
        assertFalse(track.discarded)
    }

    @Test
    fun `abort during a kokoro chunk drops its output and synthesizes no later chunk`() = runBlocking {
        val sink = Sink()
        val generation = AtomicLong(1)
        val synthCalls = AtomicInteger()
        val firstSynthEntered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val s = stream(chunks.joinToString(" "), sink, generation, 1) {
            if (synthCalls.incrementAndGet() == 1) {
                firstSynthEntered.complete(Unit)
                gate.await()
            }
            FloatArray(480) { 1f }
        }

        val append = async(Dispatchers.Default) { s.append(chunks.joinToString(" "), isFinal = true) }
        withTimeout(5_000) { firstSynthEntered.await() }
        // Mirror the controller's stop: bump the generation, then abort. The native chunk in progress finishes; its output is dropped.
        generation.incrementAndGet()
        s.abort()
        gate.complete(Unit)

        assertEquals(VoiceOutputResult.Spoken, withTimeout(5_000) { append.await() })
        s.awaitPlayback()
        assertEquals(1, synthCalls.get())
        assertTrue(sink.tracks.isEmpty())
    }

    @Test
    fun `a failed kokoro chunk is skipped and the rest of the reply plays`() = runBlocking {
        val sink = Sink()
        val generation = AtomicLong(1)
        val s = stream(chunks.joinToString(" "), sink, generation, 1) { chunk ->
            if (chunk.startsWith("second")) throw IllegalStateException("native run failed")
            FloatArray(480) { 1f }
        }

        assertEquals(VoiceOutputResult.Spoken, s.append(chunks.joinToString(" "), isFinal = true))
        s.awaitPlayback()

        assertEquals(listOf(1f, 1f), sink.tracks.single().writes)
        assertTrue(sink.tracks.single().drained)
    }

    @Test
    fun `stop while a chunk plays and the next is synthesizing discards the track and the late chunk`() = runBlocking {
        val sink = Sink().apply { blockFirstWrite = CountDownLatch(1) }
        val generation = AtomicLong(1)
        val synthCalls = AtomicInteger()
        val nextEntered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val s = stream(chunks.joinToString(" "), sink, generation, 1) {
            if (synthCalls.incrementAndGet() == 2) {
                nextEntered.complete(Unit)
                gate.await()
            }
            FloatArray(480) { 1f }
        }

        val append = async(Dispatchers.Default) { s.append(chunks.joinToString(" "), isFinal = true) }
        // Chunk 1 is playing (its write blocks); chunk 2 is inside its native run.
        assertTrue(sink.firstWriteEntered.await(5, TimeUnit.SECONDS))
        withTimeout(5_000) { nextEntered.await() }
        generation.incrementAndGet()
        s.abort()
        sink.blockFirstWrite!!.countDown()
        gate.complete(Unit)

        assertEquals(VoiceOutputResult.Spoken, withTimeout(5_000) { append.await() })
        s.awaitPlayback()
        assertEquals(2, synthCalls.get())
        val track = sink.tracks.single()
        assertEquals(listOf(1f), track.writes)
        assertTrue(track.discarded)
        assertFalse(track.drained)
    }
}
