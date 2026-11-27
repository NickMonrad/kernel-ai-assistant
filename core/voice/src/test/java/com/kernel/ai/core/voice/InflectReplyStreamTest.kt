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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Drives [InflectReplyStream] through fake PCM and hook seams. Each waveform carries its synthesis index in every
 * sample, so a track's writes show exactly which chunks reached the speaker and in what order.
 */
class InflectReplyStreamTest {
    private val longReply = (1..12).joinToString(" ") { "This is sentence number $it of the reply." }
    /** Three sentences of about 70 characters each, so each becomes its own speech chunk (limit 120). */
    private val threeChunks = listOf("Alpha", "Broken", "Gamma").joinToString(" ") {
        "$it ${"padding ".repeat(8).trim()}."
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private class FakeTrack(private val onWrite: (Int) -> Unit) : InflectPcmTrack {
        val writes = mutableListOf<Float>()
        var drained = false
        var discarded = false
        var released = false

        override fun write(samples: FloatArray, gain: Float, isCurrent: () -> Boolean) {
            // The max is the chunk's marker; the head fade and the appended tail silence never raise it.
            writes += samples.max()
            onWrite(writes.size)
        }

        override fun drain(isCurrent: () -> Boolean) {
            drained = true
        }

        override fun discard() {
            discarded = true
        }

        override fun release() {
            released = true
        }
    }

    private class FakeSink(var openError: Throwable? = null) : InflectPcmSink {
        val tracks = mutableListOf<FakeTrack>()
        val firstWriteEntered = CountDownLatch(1)
        /** When set, the first write blocks until the test releases it. */
        var blockFirstWrite: CountDownLatch? = null
        /** When set, the write with this 1-based index throws, as a device failure would. */
        var failWriteAt: Int? = null

        override fun open(pitch: Float): InflectPcmTrack {
            openError?.let { throw it }
            return FakeTrack { count ->
                if (count == 1) {
                    firstWriteEntered.countDown()
                    blockFirstWrite?.await(5, TimeUnit.SECONDS)
                }
                if (count == failWriteAt) throw IllegalStateException("device lost")
            }.also { tracks += it }
        }

        override fun silenceActive() = Unit
    }

    private class FakeHooks : InflectReplyHooks {
        val starts = mutableListOf<Long>()
        val ends = mutableListOf<Long>()

        override fun onPlaybackStarting(generation: Long, text: String) {
            starts += generation
        }

        override fun onPlaybackEnded(generation: Long) {
            ends += generation
        }
    }

    /** One reply wired to the shared [generation], which plays the controller's role of stop/replace counter. */
    private inner class Harness(
        val sink: FakeSink,
        val hooks: FakeHooks,
        val generation: AtomicLong,
        unavailable: VoiceOutputResult.Unavailable? = null,
    ) {
        val synthCalls = AtomicInteger()
        var synthesizer: suspend (String) -> Float = { synthCalls.get().toFloat() }
        var phonemizer: (String) -> String = { it }
        val myGeneration = generation.incrementAndGet()
        val stream = InflectReplyStream(
            spokenText = "reply",
            generation = myGeneration,
            isCurrent = { generation.get() == myGeneration },
            unavailable = unavailable,
            synthesize = { phonemes ->
                synthCalls.incrementAndGet()
                val index = synthesizer(phonemes)
                FloatArray(1000) { index }
            },
            phonemizeChunk = { phonemizer(it) },
            sink = sink,
            hooks = hooks,
            gain = 1f,
            pitch = 1f,
            scope = scope,
        )
    }

    private fun harness(
        sink: FakeSink = FakeSink(),
        hooks: FakeHooks = FakeHooks(),
        generation: AtomicLong = AtomicLong(0),
        unavailable: VoiceOutputResult.Unavailable? = null,
    ) = Harness(sink, hooks, generation, unavailable)

    @Test
    fun `chunks play in order on one track and the track drains on the final append`() = runBlocking {
        val sink = FakeSink()
        val hooks = FakeHooks()
        val h = harness(sink, hooks)

        assertEquals(VoiceOutputResult.Spoken, h.stream.append(longReply, isFinal = true))
        h.stream.awaitPlayback()

        assertEquals(1, sink.tracks.size)
        val track = sink.tracks.single()
        assertTrue(h.synthCalls.get() >= 2)
        assertEquals((1..h.synthCalls.get()).map { it.toFloat() }, track.writes)
        assertTrue(track.drained)
        assertFalse(track.discarded)
        assertTrue(track.released)
        assertEquals(listOf(h.myGeneration), hooks.starts)
        assertEquals(listOf(h.myGeneration), hooks.ends)
    }

    @Test
    fun `stop during synthesis plays nothing and synthesizes no later chunk`() = runBlocking {
        val sink = FakeSink()
        val hooks = FakeHooks()
        val generation = AtomicLong(0)
        val h = harness(sink, hooks, generation)
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        h.synthesizer = {
            entered.complete(Unit)
            gate.await()
            h.synthCalls.get().toFloat()
        }

        val append = async(Dispatchers.Default) { h.stream.append(longReply, isFinal = true) }
        withTimeout(5_000) { entered.await() }
        // Mirror the controller's stop(): bump the generation first, then abort the reply.
        generation.incrementAndGet()
        h.stream.abort()
        gate.complete(Unit)

        assertEquals(VoiceOutputResult.Spoken, withTimeout(5_000) { append.await() })
        h.stream.awaitPlayback()
        assertEquals(1, h.synthCalls.get())
        assertTrue(sink.tracks.isEmpty())
        assertTrue(hooks.starts.isEmpty())
    }

    @Test
    fun `a newer reply replacing an older one discards the older track and the newer one plays`() = runBlocking {
        val generation = AtomicLong(0)
        val olderSink = FakeSink().apply { blockFirstWrite = CountDownLatch(1) }
        val older = harness(olderSink, FakeHooks(), generation)
        val olderAppend = async(Dispatchers.Default) { older.stream.append(longReply, isFinal = true) }
        assertTrue(olderSink.firstWriteEntered.await(5, TimeUnit.SECONDS))

        // Mirror the controller's openReply(): the newer reply bumps the generation and aborts the older one.
        val newerSink = FakeSink()
        val newerHooks = FakeHooks()
        val newer = harness(newerSink, newerHooks, generation)
        older.stream.abort()
        olderSink.blockFirstWrite!!.countDown()
        withTimeout(5_000) { olderAppend.await() }
        older.stream.awaitPlayback()

        assertEquals(VoiceOutputResult.Spoken, newer.stream.append("Short newer reply.", isFinal = true))
        newer.stream.awaitPlayback()

        val olderTrack = olderSink.tracks.single()
        assertTrue(olderTrack.discarded)
        assertFalse(olderTrack.drained)
        assertEquals(1, olderTrack.writes.size)
        val newerTrack = newerSink.tracks.single()
        assertTrue(newerTrack.drained)
        assertEquals(listOf(newer.myGeneration), newerHooks.starts)
    }

    @Test
    fun `a blocked send unwinds on stop and nothing is synthesized afterwards`() = runBlocking {
        val generation = AtomicLong(0)
        val sink = FakeSink().apply { blockFirstWrite = CountDownLatch(1) }
        val h = harness(sink, FakeHooks(), generation)

        val producer = launch(Dispatchers.Default) { h.stream.append(longReply, isFinal = true) }
        // Wait until the track is open and chunk 1 is blocked in write. Aborting before open is a valid silent path
        // (see InflectReplyStream.playAudio), so this test must not race the consumer's open.
        assertTrue(sink.firstWriteEntered.await(5, TimeUnit.SECONDS))
        // Chunk 2 sits in the one-slot channel, chunk 3 is blocked in send.
        withTimeout(5_000) {
            while (h.synthCalls.get() < 3) delay(1)
        }
        val synthesizedBeforeStop = h.synthCalls.get()

        generation.incrementAndGet()
        h.stream.abort()
        sink.blockFirstWrite!!.countDown()

        withTimeout(5_000) { producer.join() }
        h.stream.awaitPlayback()
        assertEquals(synthesizedBeforeStop, h.synthCalls.get())
        val track = sink.tracks.single()
        assertTrue(track.discarded)
        assertEquals(1, track.writes.size)
    }

    @Test
    fun `a failed append does not stop later appends from playing`() = runBlocking {
        val sink = FakeSink()
        val h = harness(sink)
        h.synthesizer = {
            if (it.contains("broken", ignoreCase = true)) throw IllegalStateException("bad symbol")
            h.synthCalls.get().toFloat()
        }

        assertEquals(VoiceOutputResult.Spoken, h.stream.append("First sentence plays.", isFinal = false))
        assertEquals(VoiceOutputResult.Spoken, h.stream.append("Broken sentence here.", isFinal = false))
        assertEquals(VoiceOutputResult.Spoken, h.stream.append("Third sentence plays.", isFinal = true))
        h.stream.awaitPlayback()

        val track = sink.tracks.single()
        assertEquals(2, track.writes.size)
        assertTrue(track.drained)
    }

    @Test
    fun `a failed chunk inside one append is skipped and the rest of that append plays`() = runBlocking {
        val sink = FakeSink()
        val h = harness(sink)
        h.synthesizer = {
            if (it.contains("Broken")) throw IllegalStateException("bad symbol")
            h.synthCalls.get().toFloat()
        }

        val result = h.stream.append(threeChunks, isFinal = true)
        h.stream.awaitPlayback()

        assertEquals(VoiceOutputResult.Spoken, result)
        val track = sink.tracks.single()
        assertEquals(listOf(1f, 3f), track.writes)
        assertTrue(track.drained)
    }

    @Test
    fun `a phonemizer failure skips only its chunk and the rest of the append plays`() = runBlocking {
        val sink = FakeSink()
        val h = harness(sink)
        h.phonemizer = {
            if (it.contains("Broken")) throw IllegalStateException("phonemizer rejected")
            it
        }

        val result = h.stream.append(threeChunks, isFinal = true)
        h.stream.awaitPlayback()

        assertEquals(VoiceOutputResult.Spoken, result)
        assertEquals(listOf(1f, 2f), sink.tracks.single().writes)
    }

    @Test
    fun `a preparation failure stays silent until the final append reports it for the fallback`() = runBlocking {
        val sink = FakeSink()
        val h = harness(sink, unavailable = VoiceOutputResult.Unavailable("models missing"))

        assertEquals(VoiceOutputResult.Spoken, h.stream.append(longReply, isFinal = false))
        assertEquals(VoiceOutputResult.Unavailable("models missing"), h.stream.append("", isFinal = true))
        assertEquals(0, h.synthCalls.get())
        assertTrue(sink.tracks.isEmpty())
    }

    @Test
    fun `a write failure after audio started is reported as spoken so the fallback cannot repeat it`() =
        runBlocking {
            val sink = FakeSink().apply { failWriteAt = 2 }
            val h = harness(sink)

            val result = h.stream.append(longReply, isFinal = true)
            h.stream.awaitPlayback()

            assertEquals(VoiceOutputResult.Spoken, result)
            assertEquals(VoiceOutputResult.Spoken, h.stream.playbackResult())
            assertTrue(sink.tracks.single().discarded)
        }

    @Test
    fun `a failure before any audio is reported on the final append for the fallback`() = runBlocking {
        val sink = FakeSink()
        val hooks = FakeHooks()
        val h = harness(sink, hooks)
        h.synthesizer = { throw IllegalStateException("phonemizer empty") }

        assertEquals(VoiceOutputResult.Spoken, h.stream.append("Only sentence.", isFinal = false))
        val result = h.stream.append("", isFinal = true)

        assertTrue(result is VoiceOutputResult.Unavailable)
        assertTrue((result as VoiceOutputResult.Unavailable).message.startsWith("Inflect synthesis failed"))
        assertTrue(sink.tracks.isEmpty())
        assertTrue(hooks.starts.isEmpty())
    }

    @Test
    fun `caller cancel finishes queued audio, does not cut it, and synthesizes nothing more`() = runBlocking {
        val generation = AtomicLong(0)
        val sink = FakeSink().apply { blockFirstWrite = CountDownLatch(1) }
        val h = harness(sink, FakeHooks(), generation)

        val producer = launch(Dispatchers.Default) { h.stream.append(longReply, isFinal = true) }
        withTimeout(5_000) {
            while (h.synthCalls.get() < 3) delay(1)
        }

        producer.cancel()
        h.stream.cancel()
        sink.blockFirstWrite!!.countDown()
        withTimeout(5_000) { producer.join() }
        h.stream.awaitPlayback()

        val track = sink.tracks.single()
        assertTrue(track.drained)
        assertFalse(track.discarded)
        assertEquals(listOf(1f, 2f), track.writes)
        assertEquals(3, h.synthCalls.get())
    }

    @Test
    fun `a track that fails to open reports Unavailable for the fallback and does not throw`() = runBlocking {
        val sink = FakeSink(openError = IllegalStateException("AudioTrack refused"))
        val hooks = FakeHooks()
        val h = harness(sink, hooks)

        val result = h.stream.append(longReply, isFinal = true)
        h.stream.awaitPlayback()
        assertEquals(
            VoiceOutputResult.Unavailable("Inflect playback failed: AudioTrack refused"),
            h.stream.playbackResult(),
        )

        assertEquals(VoiceOutputResult.Spoken, result)
        assertTrue(sink.tracks.isEmpty())
        assertEquals(listOf(h.myGeneration), hooks.starts)
        assertEquals(listOf(h.myGeneration), hooks.ends)
    }
}
