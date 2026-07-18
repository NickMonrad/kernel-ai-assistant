package com.kernel.ai.debug.acoustic

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AcousticStimulusTest {
    @AfterEach
    fun releasePlaybackGate() {
        PlaybackGate.release()
    }

    @Test
    fun `valid invocation accepts bounded contract`() {
        val result = InvocationParser.parse(
            AcousticStimulusContract.ACTION_PLAY,
            mapOf(
                "trial_id" to "smoke-001",
                "fixture_id" to "natural_wake",
                "volume_index" to 7,
                "player_gain" to 1.0f,
            ),
        )

        assertEquals(
            InvocationParseResult.Valid(StimulusInvocation("smoke-001", "natural_wake", 7, 1.0f)),
            result,
        )
    }

    @Test
    fun `invocation parser rejects malformed ids paths and unsafe bounds`() {
        assertError(mapOf("fixture_id" to "natural_wake", "volume_index" to 7), "missing_trial_id")
        assertError(mapOf("trial_id" to "bad id", "fixture_id" to "natural_wake", "volume_index" to 7), "malformed_trial_id")
        assertError(mapOf("trial_id" to "t1", "volume_index" to 7), "missing_fixture_id")
        assertError(mapOf("trial_id" to "t1", "fixture_id" to "../secret", "volume_index" to 7), "arbitrary_path_not_allowed")
        assertError(mapOf("trial_id" to "t1", "fixture_id" to "natural_wake", "volume_index" to -1), "unsafe_volume_index")
        assertError(mapOf("trial_id" to "t1", "fixture_id" to "natural_wake", "volume_index" to 7, "player_gain" to 1.1f), "unsafe_player_gain")
        assertError(mapOf("trial_id" to "t1", "fixture_id" to "natural_wake", "volume_index" to 7, "fixture_path" to "/sdcard/x.wav"), "arbitrary_path_not_allowed")
    }

    @Test
    fun `invocation parser rejects wrong action unknown extras and malformed types`() {
        val values = mapOf("trial_id" to "t1", "fixture_id" to "natural_wake", "volume_index" to 7)
        assertError(values, "invalid_action", action = "other.action")
        assertError(values + ("extra" to true), "unsupported_extra")
        assertError(values + ("volume_index" to "7"), "missing_or_malformed_volume_index")
        assertError(values + ("player_gain" to "1.0"), "malformed_player_gain")
    }

    @Test
    fun `fixture repository only resolves app private allowlisted files`() {
        val root = Files.createTempDirectory("acoustic-fixtures-").toFile()
        val file = File(root, "natural_wake.wav")
        writeWav(file, dataBytes = 96_000)
        val metadata = WavValidator.validate(file)
        val repository = FileFixtureRepository(root) { _ ->
            FixtureManifest(1, listOf(FixtureEntry("natural_wake", file.name, metadata.sha256, metadata.durationMs)))
        }

        val resolved = repository.resolveAndValidate("natural_wake")
        assertEquals(file.canonicalFile, resolved.file)
        assertEquals(metadata.sha256, resolved.metadata.sha256)
        assertThrowsCategory("unknown_fixture") { repository.resolveAndValidate("other") }
        assertThrowsCategory("fixture_missing") {
            FileFixtureRepository(root) { _ ->
                FixtureManifest(1, listOf(FixtureEntry("missing", "missing.wav", metadata.sha256, metadata.durationMs)))
            }.resolveAndValidate("missing")
        }
        assertThrowsCategory("arbitrary_path_not_allowed") {
            FileFixtureRepository(root) { _ ->
                FixtureManifest(1, listOf(FixtureEntry("escape", "../escape.wav", metadata.sha256, metadata.durationMs)))
            }.resolveAndValidate("escape")
        }
        assertThrowsCategory("fixture_hash_or_metadata_mismatch") {
            FileFixtureRepository(root) { _ ->
                FixtureManifest(
                    1,
                    listOf(FixtureEntry("natural_wake", file.name, "0".repeat(64), metadata.durationMs)),
                )
            }.resolveAndValidate("natural_wake")
        }
    }

    @Test
    fun `wav validator rejects malformed unsupported over duration and empty fixtures`() {
        val malformed = File.createTempFile("malformed-", ".wav")
        malformed.writeBytes("not-wave".toByteArray())
        assertThrowsCategory("malformed_wav") { WavValidator.validate(malformed) }

        val unsupported = File.createTempFile("unsupported-", ".wav")
        writeWav(unsupported, dataBytes = 96_000, channels = 2)
        assertThrowsCategory("unsupported_wav_format") { WavValidator.validate(unsupported) }

        val tooLong = File.createTempFile("too-long-", ".wav")
        writeWav(tooLong, dataBytes = 480_002)
        assertThrowsCategory("fixture_duration_not_supported") { WavValidator.validate(tooLong) }

        val empty = File.createTempFile("empty-", ".wav")
        assertThrowsCategory("fixture_empty") { WavValidator.validate(empty) }
    }

    @Test
    fun `valid playback emits prepared started completed cleanup and restores exact volume`() {
        val audio = FakeAudio()
        val player = FakePlayer()
        val logger = RecordingLogger()
        val writer = RecordingWriter()
        val engine = testEngine(audio, player, writer, logger)
        var result: StimulusResult? = null

        engine.handle(InvocationParseResult.Valid(invocation())) { result = it }
        player.triggerPrepared()
        player.triggerCompletion()

        assertEquals("completed", result?.completionStatus)
        assertNull(result?.errorCategory)
        assertEquals(
            listOf("prepared", "started", "completed", "cleanup_completed"),
            logger.events.map { it.name },
        )
        assertTrue(result?.events?.last()?.cleanupSuccess == true)
        assertTrue(result?.events?.last()?.exactRestorationVerified == true)
        assertEquals(4, result?.volumeBefore)
        assertEquals(7, result?.requestedVolume)
        assertEquals(7, result?.appliedVolume)
        assertEquals(4, result?.restoredVolume)
        assertTrue(result?.cleanupSuccess == true)
        assertTrue(result?.exactRestorationVerified == true)
        assertEquals(1, player.releaseCount)
        assertEquals(1, audio.abandonCount)
    }

    @Test
    fun `invalid route focus failure preparation error playback error and timeout all clean up`() {
        val cases = listOf(
            Triple(FakeAudio(route = OutputRoute.EXTERNAL_BLUETOOTH), FakePlayer(), "invalid_output_route"),
            Triple(FakeAudio(focusGranted = false), FakePlayer(), "audio_focus_denied"),
            Triple(FakeAudio(), FakePlayer(prepareThrows = true), "prepare_failed"),
        )
        cases.forEach { (audio, player, category) ->
            val writer = RecordingWriter()
            val logger = RecordingLogger()
            val engine = testEngine(audio, player, writer, logger)
            var result: StimulusResult? = null
            engine.handle(InvocationParseResult.Valid(invocation())) { result = it }
            assertEquals(category, result?.errorCategory)
            assertEquals("cleanup_completed", result?.events?.last()?.name)
            assertEquals(4, result?.restoredVolume)
            assertTrue(result?.exactRestorationVerified == true)
            assertEquals(if (category == "prepare_failed") 1 else 0, player.releaseCount)
            assertEquals(if (category == "prepare_failed") 1 else 0, audio.abandonCount)
        }

        val errorAudio = FakeAudio()
        val errorPlayer = FakePlayer()
        val errorWriter = RecordingWriter()
        val errorLogger = RecordingLogger()
        val errorEngine = testEngine(errorAudio, errorPlayer, errorWriter, errorLogger)
        var errorResult: StimulusResult? = null
        errorEngine.handle(InvocationParseResult.Valid(invocation())) { errorResult = it }
        errorPlayer.triggerPrepared()
        errorPlayer.triggerError()
        assertEquals("playback_error", errorResult?.errorCategory)
        assertEquals(
            listOf("prepared", "started", "error", "cleanup_completed"),
            errorLogger.events.map { it.name },
        )
        assertEquals(4, errorResult?.restoredVolume)

        val timeoutAudio = FakeAudio()
        val timeoutPlayer = FakePlayer()
        val timeoutScheduler = RecordingScheduler()
        val timeoutWriter = RecordingWriter()
        val timeoutLogger = RecordingLogger()
        val timeoutEngine = testEngine(timeoutAudio, timeoutPlayer, timeoutWriter, timeoutLogger, timeoutScheduler)
        var timeoutResult: StimulusResult? = null
        timeoutEngine.handle(InvocationParseResult.Valid(invocation())) { timeoutResult = it }
        timeoutScheduler.fire()
        assertEquals("playback_timeout", timeoutResult?.errorCategory)
        assertTrue(timeoutResult?.timeout == true)
        assertEquals(
            listOf("timeout", "cleanup_completed"),
            timeoutLogger.events.map { it.name },
        )
        assertEquals(4, timeoutResult?.restoredVolume)
    }

    @Test
    fun `result writer failure returns failed outcome and releases playback gate`() {
        val audio = FakeAudio()
        val player = FakePlayer()
        var result: StimulusResult? = null
        testEngine(audio, player, ThrowingWriter(), RecordingLogger())
            .handle(InvocationParseResult.Valid(invocation())) { result = it }
        player.triggerPrepared()
        player.triggerCompletion()

        assertEquals("invalid", result?.completionStatus)
        assertEquals("result_write_failed", result?.errorCategory)
        assertNull(result?.playbackErrorCategory)
        assertTrue(result?.evidencePersistenceFailed == true)
        assertTrue(result?.cleanupSuccess == true)
        assertEquals(
            listOf("prepared", "started", "completed", "cleanup_completed"),
            result?.events?.map { it.name },
        )
        assertEquals(
            AcousticStimulusContract.RESULT_FAILED,
            acousticStimulusResultCode(result!!),
        )
        assertTrue(
            result?.completionStatus != "completed" ||
                acousticStimulusResultCode(result!!) != AcousticStimulusContract.RESULT_OK,
        )
        assertTrue(PlaybackGate.tryAcquire())
        PlaybackGate.release()
    }

    @Test
    fun `playback start failure releases resources restores volume and releases gate`() {
        val audio = FakeAudio()
        val player = FakePlayer(startThrows = true)
        val logger = RecordingLogger()
        var result: StimulusResult? = null
        testEngine(audio, player, RecordingWriter(), logger)
            .handle(InvocationParseResult.Valid(invocation())) { result = it }
        player.triggerPrepared()

        assertEquals("playback_start_failed", result?.errorCategory)
        assertEquals(1, player.releaseCount)
        assertEquals(1, audio.abandonCount)
        assertEquals(4, result?.restoredVolume)
        assertTrue(result?.exactRestorationVerified == true)
        assertEquals(
            listOf("prepared", "error", "cleanup_completed"),
            logger.events.map { it.name },
        )
        assertTrue(PlaybackGate.tryAcquire())
        PlaybackGate.release()
    }

    @Test
    fun `overlapping request is rejected without changing volume`() {
        val firstAudio = FakeAudio()
        val firstPlayer = FakePlayer()
        val firstEngine = testEngine(firstAudio, firstPlayer, RecordingWriter(), RecordingLogger())
        firstEngine.handle(InvocationParseResult.Valid(invocation("first"))) {}

        val secondAudio = FakeAudio()
        var secondResult: StimulusResult? = null
        testEngine(secondAudio, FakePlayer(), RecordingWriter(), RecordingLogger())
            .handle(InvocationParseResult.Valid(invocation("second"))) { secondResult = it }

        assertEquals("overlap_rejected", secondResult?.errorCategory)
        assertTrue(secondResult?.overlapRejected == true)
        assertEquals(0, secondAudio.setVolumeCount)
    }

    @Test
    fun `volume above device maximum is rejected before playback`() {
        val audio = FakeAudio()
        var result: StimulusResult? = null
        testEngine(audio, FakePlayer(), RecordingWriter(), RecordingLogger())
            .handle(
                InvocationParseResult.Valid(StimulusInvocation("trial-high-volume", "natural_wake", 16, 1.0f)),
            ) { result = it }

        assertEquals("unsafe_volume_index", result?.errorCategory)
        assertEquals(1, audio.setVolumeCount)
        assertEquals(4, result?.restoredVolume)
    }

    @Test
    fun `cleanup failure invalidates an otherwise completed attempt`() {
        val audio = FakeAudio(restorationFails = true)
        val player = FakePlayer()
        val writer = RecordingWriter()
        var result: StimulusResult? = null
        testEngine(audio, player, writer, RecordingLogger())
            .handle(InvocationParseResult.Valid(invocation())) { result = it }
        player.triggerPrepared()
        player.triggerCompletion()

        assertEquals("invalid", result?.completionStatus)
        assertEquals("volume_restoration_failed", result?.errorCategory)
        assertFalse(result?.cleanupSuccess == true)
        assertFalse(result?.exactRestorationVerified == true)
    }

    @Test
    fun `cleanup failure preserves original playback failure`() {
        val audio = FakeAudio(restorationFails = true)
        val player = FakePlayer()
        val logger = RecordingLogger()
        var result: StimulusResult? = null
        testEngine(audio, player, RecordingWriter(), logger)
            .handle(InvocationParseResult.Valid(invocation())) { result = it }
        player.triggerPrepared()
        player.triggerError()

        assertEquals("invalid", result?.completionStatus)
        assertEquals("volume_restoration_failed", result?.errorCategory)
        assertEquals("playback_error", result?.playbackErrorCategory)
        assertFalse(result?.cleanupSuccess == true)
        assertFalse(result?.exactRestorationVerified == true)
        assertEquals("cleanup_completed", result?.events?.last()?.name)
        assertFalse(result?.events?.last()?.cleanupSuccess == true)
        assertEquals("volume_restoration_failed", result?.events?.last()?.errorCategory)
    }

    @Test
    fun `structured result contains required source evidence`() {
        val result = StimulusResult(
            trialId = "trial-1",
            fixtureId = "natural_wake",
            fixtureSha256 = "a".repeat(64),
            fixtureDurationMs = 1_000,
            requestWallClockMs = 10,
            requestMonotonicMs = 20,
            prepareMonotonicMs = 21,
            playbackStartMonotonicMs = 22,
            completionMonotonicMs = 23,
            cleanupMonotonicMs = 24,
            volumeBefore = 4,
            requestedVolume = 7,
            appliedVolume = 7,
            maximumVolume = 15,
            restoredVolume = 4,
            outputRouteBefore = OutputRoute.BUILT_IN_SPEAKER,
            outputRouteDuring = OutputRoute.BUILT_IN_SPEAKER,
            focusResult = "granted",
            completionStatus = "completed",
            errorCategory = null,
            timeout = false,
            overlapRejected = false,
            cleanupSuccess = true,
            exactRestorationVerified = true,
            events = listOf(StimulusEvent("started", 22, 12)),
        )
        assertEquals("trial-1", result.trialId)
        assertEquals("natural_wake", result.fixtureId)
        assertEquals(64, result.fixtureSha256?.length)
        assertEquals(4, result.volumeBefore)
        assertEquals("BUILT_IN_SPEAKER", result.outputRouteDuring?.name)
        assertTrue(result.exactRestorationVerified)
        assertEquals("started", result.events.single().name)
    }

    private fun assertError(values: Map<String, Any?>, category: String, action: String = AcousticStimulusContract.ACTION_PLAY) {
        val result = InvocationParser.parse(action, values) as InvocationParseResult.Invalid
        assertEquals(category, result.error.category)
    }

    private fun assertThrowsCategory(category: String, action: () -> Unit) {
        try {
            action()
            throw AssertionError("expected $category")
        } catch (error: FixtureValidationException) {
            assertEquals(category, error.category)
        }
    }

    private fun invocation(trialId: String = "trial-1") =
        StimulusInvocation(trialId, "natural_wake", 7, 1.0f)

    private fun testEngine(
        audio: FakeAudio,
        player: FakePlayer,
        writer: StimulusResultWriter,
        logger: RecordingLogger,
        scheduler: RecordingScheduler = RecordingScheduler(),
    ) = AcousticStimulusEngine(
        fixtures = FakeFixtureSource(),
        audio = audio,
        playerFactory = StimulusPlayerFactory { player },
        scheduler = scheduler,
        time = IncrementingTimeSource(),
        resultWriter = writer,
        eventLogger = logger,
    )

    private fun writeWav(
        file: File,
        dataBytes: Int,
        channels: Int = 1,
        sampleRate: Int = 48_000,
        bits: Int = 16,
    ) {
        FileOutputStream(file).use { output ->
            val riffSize = 36 + dataBytes
            fun text(value: String) = output.write(value.toByteArray(Charsets.US_ASCII))
            fun u16(value: Int) = output.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array())
            fun u32(value: Int) = output.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
            text("RIFF"); u32(riffSize); text("WAVE")
            text("fmt "); u32(16); u16(1); u16(channels); u32(sampleRate)
            u32(sampleRate * channels * bits / 8); u16(channels * bits / 8); u16(bits)
            text("data"); u32(dataBytes)
            output.write(ByteArray(dataBytes))
        }
    }

    private class IncrementingTimeSource : StimulusTimeSource {
        private val next = AtomicInteger(1_000)
        override fun wallClockMs(): Long = next.incrementAndGet().toLong()
        override fun monotonicMs(): Long = next.incrementAndGet().toLong()
    }

    private class FakeFixtureSource : FixtureSource {
        private val file = File.createTempFile("fixture-", ".wav").apply { writeBytes(byteArrayOf(1)) }
        private val metadata = WavMetadata("b".repeat(64), 1_000, 96_000, 48_000, 1, 16)
        private val resolved = ResolvedFixture(FixtureEntry("natural_wake", "natural_wake.wav", metadata.sha256, metadata.durationMs), file, metadata)
        override fun resolveAndValidate(fixtureId: String): ResolvedFixture {
            if (fixtureId != "natural_wake") throw FixtureValidationException("unknown_fixture")
            return resolved
        }
        override fun openFixture(fixture: ResolvedFixture): FileInputStream = FileInputStream(fixture.file)
    }

    private class FakeAudio(
        private val route: OutputRoute = OutputRoute.BUILT_IN_SPEAKER,
        val focusGranted: Boolean = true,
        private val restorationFails: Boolean = false,
    ) : StimulusAudioController {
        var volume = 4
        var setVolumeCount = 0
        var abandonCount = 0
        override fun snapshot() = AudioSnapshot(volume, 15, route)
        override fun currentRoute() = route
        override fun setMediaVolume(volume: Int) {
            setVolumeCount++
            this.volume = if (restorationFails && volume == 4) 5 else volume
        }
        override fun currentMediaVolume() = volume
        override fun requestFocus(): FocusRequestResult =
            if (focusGranted) FocusRequestResult.Granted(object : FocusHandle {})
            else FocusRequestResult.Denied()
        override fun abandonFocus(handle: FocusHandle) { abandonCount++ }
    }

    private class FakePlayer(
        private val prepareThrows: Boolean = false,
        private val startThrows: Boolean = false,
    ) : StimulusPlayer {
        private var prepared: (() -> Unit)? = null
        private var completion: (() -> Unit)? = null
        private var error: ((Int, Int) -> Unit)? = null
        var releaseCount = 0
        var started = false
        override fun setGain(gain: Float) = Unit
        override fun setDataSource(fileDescriptor: java.io.FileDescriptor) = Unit
        override fun setOnPreparedListener(listener: () -> Unit) { prepared = listener }
        override fun setOnCompletionListener(listener: () -> Unit) { completion = listener }
        override fun setOnErrorListener(listener: (what: Int, extra: Int) -> Unit) { error = listener }
        override fun prepareAsync() {
            if (prepareThrows) throw IllegalStateException("prepare")
        }
        override fun start() {
            if (startThrows) throw IllegalStateException("start")
            started = true
        }
        override fun release() { releaseCount++ }
        fun triggerPrepared() { prepared?.invoke() }
        fun triggerCompletion() { completion?.invoke() }
        fun triggerError() { error?.invoke(1, 2) }
    }

    private class RecordingScheduler : StimulusScheduler {
        var action: (() -> Unit)? = null
        override fun schedule(delayMs: Long, action: () -> Unit): StimulusCancellation {
            this.action = action
            return object : StimulusCancellation { override fun cancel() = Unit }
        }
        fun fire() { action?.invoke() }
    }

    private class RecordingWriter : StimulusResultWriter {
        val results = mutableListOf<StimulusResult>()
        override fun write(result: StimulusResult) { results += result }
    }

    private class ThrowingWriter : StimulusResultWriter {
        override fun write(result: StimulusResult) {
            throw IllegalStateException("writer")
        }
    }

    private class RecordingLogger : StimulusEventLogger {
        val events = mutableListOf<StimulusEvent>()
        override fun event(result: StimulusEvent, trialId: String?, fixtureId: String?) { events += result }
    }
}
