package com.kernel.ai.core.voice

import android.content.Context
import android.media.AudioManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class SherpaOnnxVoiceInputControllerTest {

    // Real temp directory used as the models dir — avoids mocking File I/O while
    // remaining completely deterministic and side-effect-free.
    private val tempModelsDir: File = Files.createTempDirectory("stt-test-models").toFile()

    private val audioManager: AudioManager = mockk(relaxed = true)
    private val voiceInputPreferences: VoiceInputPreferences = mockk()
    private val context: Context = mockk {
        every { getExternalFilesDir("models") } returns tempModelsDir
        every { getSystemService(Context.AUDIO_SERVICE) } returns audioManager
    }
    private val selectedEngine = MutableStateFlow(VoiceInputEngine.SherpaZipformer)
    private val controller = SherpaOnnxVoiceInputController(context, voiceInputPreferences)

    init {
        every { voiceInputPreferences.selectedEngine } returns selectedEngine
    }

    @AfterEach
    fun cleanup() {
        tempModelsDir.deleteRecursively()
    }

    // ── Availability ──────────────────────────────────────────────────────────

    @Test
    fun `isAvailable returns false when all model files absent`() = runTest {
        // tempModelsDir is empty — nothing created.
        assertFalse(controller.isAvailable())
    }

    @Test
    fun `isAvailable returns false when one model file absent`() = runTest {
        // Create 3 of the 4 required files — tokens.txt is missing.
        listOf(
            "sherpa-stt-encoder.int8.onnx",
            "sherpa-stt-decoder.int8.onnx",
            "sherpa-stt-joiner.int8.onnx",
        ).forEach { File(tempModelsDir, it).writeText("stub") }

        assertFalse(controller.isAvailable())
    }

    @Test
    fun `startListening returns Unavailable when model files absent`() = runTest {
        // No files in tempModelsDir → isAvailable() == false → Unavailable.
        val result = controller.startListening(VoiceCaptureMode.Command)

        assertInstanceOf(VoiceInputStartResult.Unavailable::class.java, result)
    }

    @Test
    fun `startListening returns Unavailable when Sherpa class not found`() = runTest {
        // All four model files present → isAvailable() == true.
        // initRecognizer() then calls Class.forName("com.k2fsa.sherpa.onnx.FeatureConfig"),
        // which throws ClassNotFoundException because the AAR is not on the unit-test
        // classpath → ensureRecognizer() returns null → Unavailable.
        createAllStubModelFiles()

        val result = controller.startListening(VoiceCaptureMode.Command)

        assertInstanceOf(VoiceInputStartResult.Unavailable::class.java, result)
    }

    // ── #1439: wake-verifier model selection ───────────────────────────────────

    @Test
    fun `resolveWakeVerifierSpec prefers Whisper when its files are present`() = runTest {
        createWhisperStubModelFiles()
        // Whisper verifier wins regardless of the interactive engine selection.
        listOf(
            VoiceInputEngine.Vosk,
            VoiceInputEngine.SherpaZipformer,
            VoiceInputEngine.SherpaWhisper,
            VoiceInputEngine.SherpaParaformer,
        ).forEach { engine ->
            selectedEngine.value = engine
            assertEquals(SherpaSttModelSpec.WHISPER, controller.resolveWakeVerifierSpec())
        }
    }

    @Test
    fun `resolveWakeVerifierSpec falls back to the selected online engine without Whisper`() = runTest {
        createAllStubModelFiles() // Zipformer only
        selectedEngine.value = VoiceInputEngine.SherpaZipformer
        assertEquals(SherpaSttModelSpec.ZIPFORMER, controller.resolveWakeVerifierSpec())

        selectedEngine.value = VoiceInputEngine.SherpaParaformer
        assertEquals(SherpaSttModelSpec.PARAFORMER, controller.resolveWakeVerifierSpec())
    }

    @Test
    fun `resolveWakeVerifierSpec falls back to Zipformer default without Whisper or online engine`() = runTest {
        createAllStubModelFiles() // Zipformer only
        listOf(
            VoiceInputEngine.Vosk,
            VoiceInputEngine.AndroidNative,
            VoiceInputEngine.SherpaWhisper,
            VoiceInputEngine.SherpaSenseVoice,
        ).forEach { engine ->
            selectedEngine.value = engine
            assertEquals(SherpaSttModelSpec.ZIPFORMER, controller.resolveWakeVerifierSpec())
        }
    }

    @Test
    fun `transcribeBlocking rejects empty PCM`() = runTest {
        createAllStubModelFiles()
        assertEquals(null, controller.transcribeBlocking(shortArrayOf()))
    }

    // ── #1440: wake-verifier cache mutual exclusion and lifecycle ──────────────

    @Test
    fun `fallback online to Whisper transition releases the fallback and leaves only Whisper cached`() = runTest {
        createAllStubModelFiles() // Zipformer only → online fallback verifier
        val events = mutableListOf<String>()
        val holder = FakeHolder()
        controller.wakeVerifierTestBuilder = testDouble(events, holder)

        assertEquals("hy general", controller.transcribeBlocking(shortArrayOf(1, 2)))
        assertTrue(controller.wakeOnlineVerifierCached)
        assertFalse(controller.wakeWhisperVerifierCached)
        assertEquals(0, holder.online!!.releaseCount)

        createWhisperStubModelFiles() // Whisper becomes available → verifier transition
        assertEquals("hi, jandal", controller.transcribeBlocking(shortArrayOf(3, 4)))

        // The superseded online fallback must be released and evicted from the cache.
        assertEquals(1, holder.online!!.releaseCount)
        assertTrue(events.contains("online_release"))
        assertFalse(controller.wakeOnlineVerifierCached)
        assertTrue(controller.wakeWhisperVerifierCached)
        assertTrue(holder.whisper != null)
    }

    @Test
    fun `Whisper to fallback online transition releases Whisper and leaves only the fallback cached`() = runTest {
        createAllStubModelFiles()
        createWhisperStubModelFiles()
        val events = mutableListOf<String>()
        val holder = FakeHolder()
        controller.wakeVerifierTestBuilder = testDouble(events, holder)

        assertEquals("hi, jandal", controller.transcribeBlocking(shortArrayOf(1, 2)))
        assertTrue(controller.wakeWhisperVerifierCached)
        assertFalse(controller.wakeOnlineVerifierCached)
        assertEquals(0, holder.whisper!!.releaseCount)

        deleteWhisperStubModelFiles() // Whisper unavailable → fallback verifier transition
        assertEquals("hy general", controller.transcribeBlocking(shortArrayOf(3, 4)))

        // The superseded Whisper verifier must be released and evicted from the cache.
        assertEquals(1, holder.whisper!!.releaseCount)
        assertTrue(events.contains("whisper_release"))
        assertFalse(controller.wakeWhisperVerifierCached)
        assertTrue(controller.wakeOnlineVerifierCached)
        assertTrue(holder.online != null)
    }

    @Test
    fun `consecutive calls with the same verifier reuse the cached recognizer`() = runTest {
        createAllStubModelFiles()
        createWhisperStubModelFiles()
        val events = mutableListOf<String>()
        val holder = FakeHolder()
        controller.wakeVerifierTestBuilder = testDouble(events, holder)

        assertEquals("hi, jandal", controller.transcribeBlocking(shortArrayOf(1)))
        val firstWhisper = holder.whisper
        assertEquals("hi, jandal", controller.transcribeBlocking(shortArrayOf(2)))
        assertSame(firstWhisper, holder.whisper)
        assertEquals(0, firstWhisper!!.releaseCount)
        assertEquals(2, firstWhisper.streams.size) // one stream per call; recognizer reused
        assertTrue(controller.wakeWhisperVerifierCached)

        // The online fallback reuses its cached recognizer the same way.
        deleteWhisperStubModelFiles()
        assertEquals("hy general", controller.transcribeBlocking(shortArrayOf(3)))
        val firstOnline = holder.online
        assertEquals("hy general", controller.transcribeBlocking(shortArrayOf(4)))
        assertSame(firstOnline, holder.online)
        assertEquals(0, firstOnline!!.releaseCount)
        assertEquals(2, firstOnline.streams.size)
        assertTrue(controller.wakeOnlineVerifierCached)
        assertFalse(controller.wakeWhisperVerifierCached)
    }

    @Test
    fun `verifier switching cannot release the recognizer while its decode is active`() = runTest {
        createAllStubModelFiles()
        createWhisperStubModelFiles() // Whisper selected first
        val events = mutableListOf<String>()
        val holder = FakeHolder()
        val gate = BlockingGate()
        controller.wakeVerifierTestBuilder = testDouble(events, holder, whisperDecodeGate = gate)

        val first = async(Dispatchers.IO) { controller.transcribeBlocking(shortArrayOf(1, 2, 3)) }
        assertTrue(gate.entered.await(5, TimeUnit.SECONDS), "whisper decode must have started")

        // Make the fallback the next selection while the Whisper decode is still active.
        deleteWhisperStubModelFiles()
        val second = async(Dispatchers.IO) { controller.transcribeBlocking(shortArrayOf(4, 5)) }

        // The in-flight Whisper recognizer must not be released while decoding.
        assertFalse("whisper_release" in events)
        gate.release.countDown()

        assertEquals("hi, jandal", first.await())
        assertEquals("hy general", second.await())

        // Eviction may only happen after the first verification finished its decode
        // and stream release — the single ownership boundary serializes the switch.
        val releaseIdx = events.indexOf("whisper_release")
        val streamReleaseIdx = events.indexOf("whisper_stream_release")
        assertTrue(
            releaseIdx > streamReleaseIdx && releaseIdx >= 0,
            "whisper must be released only after its stream release: $events",
        )
        assertFalse(controller.wakeWhisperVerifierCached)
        assertTrue(controller.wakeOnlineVerifierCached)
    }

    @Test
    fun `wake-verifier lifecycle leaves the interactive recognizer and engine selection untouched`() = runTest {
        createAllStubModelFiles()
        createWhisperStubModelFiles()
        val events = mutableListOf<String>()
        val holder = FakeHolder()
        controller.wakeVerifierTestBuilder = testDouble(events, holder)
        val engineBefore = selectedEngine.value

        controller.transcribeBlocking(shortArrayOf(1, 2)) // whisper
        controller.transcribeBlocking(shortArrayOf(3, 4)) // whisper reuse
        deleteWhisperStubModelFiles()
        controller.transcribeBlocking(shortArrayOf(5, 6)) // online transition
        controller.transcribeBlocking(shortArrayOf(7, 8)) // online reuse

        assertEquals(engineBefore, selectedEngine.value)
        assertFalse(controller.interactiveRecognizerActive)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun createAllStubModelFiles() {
        listOf(
            "sherpa-stt-encoder.int8.onnx",
            "sherpa-stt-decoder.int8.onnx",
            "sherpa-stt-joiner.int8.onnx",
            "sherpa-stt-tokens.txt",
        ).forEach { name -> File(tempModelsDir, name).writeText("stub") }
    }

    private fun createWhisperStubModelFiles() {
        listOf(
            "sherpa-whisper-tiny.en-encoder.int8.onnx",
            "sherpa-whisper-tiny.en-decoder.int8.onnx",
            "sherpa-whisper-tiny.en-tokens.txt",
        ).forEach { name -> File(tempModelsDir, name).writeText("stub") }
    }

    private fun deleteWhisperStubModelFiles() {
        listOf(
            "sherpa-whisper-tiny.en-encoder.int8.onnx",
            "sherpa-whisper-tiny.en-decoder.int8.onnx",
            "sherpa-whisper-tiny.en-tokens.txt",
        ).forEach { name -> File(tempModelsDir, name).delete() }
    }

    /**
     * Installs the #1440 wake-verifier construction test double: fake recognizers
     * drive the real cache-ownership lifecycle in [transcribeBlocking] without the
     * Sherpa AAR on the classpath.
     */
    private fun testDouble(
        events: MutableList<String>,
        holder: FakeHolder,
        whisperDecodeGate: BlockingGate? = null,
    ): SherpaOnnxVoiceInputController.WakeVerifierTestBuilder =
        object : SherpaOnnxVoiceInputController.WakeVerifierTestBuilder {
            override fun buildOnline(spec: SherpaSttModelSpec): Pair<Any, SherpaOnnxVoiceInputController.WakeRecognizerMethods>? {
                val rec = FakeOnlineRecognizer(events)
                holder.online = rec
                events.add("online_built")
                return rec to onlineMethodsFor(rec)
            }

            override fun buildWhisper(spec: SherpaSttModelSpec): Pair<Any, SherpaOnnxVoiceInputController.WakeWhisperMethods>? {
                val rec = FakeWhisperRecognizer(events, whisperDecodeGate)
                holder.whisper = rec
                events.add("whisper_built")
                return rec to whisperMethodsFor(rec)
            }
        }

    private fun onlineMethodsFor(rec: FakeOnlineRecognizer): SherpaOnnxVoiceInputController.WakeRecognizerMethods {
        val streamCls = FakeOnlineStream::class.java
        return SherpaOnnxVoiceInputController.WakeRecognizerMethods(
            createStream = FakeOnlineRecognizer::class.java.getDeclaredMethod("createStream", String::class.java),
            acceptWaveform = streamCls.getDeclaredMethod("acceptWaveform", FloatArray::class.java, Int::class.javaPrimitiveType),
            inputFinished = streamCls.getDeclaredMethod("inputFinished"),
            isReady = FakeOnlineRecognizer::class.java.getDeclaredMethod("isReady", streamCls),
            decode = FakeOnlineRecognizer::class.java.getDeclaredMethod("decode", streamCls),
            streamRelease = streamCls.getDeclaredMethod("release"),
            getResult = FakeOnlineRecognizer::class.java.getDeclaredMethod("getResult", streamCls),
        )
    }

    private fun whisperMethodsFor(rec: FakeWhisperRecognizer): SherpaOnnxVoiceInputController.WakeWhisperMethods {
        val streamCls = FakeWhisperStream::class.java
        return SherpaOnnxVoiceInputController.WakeWhisperMethods(
            createStream = FakeWhisperRecognizer::class.java.getDeclaredMethod("createStream"),
            acceptWaveform = streamCls.getDeclaredMethod("acceptWaveform", FloatArray::class.java, Int::class.javaPrimitiveType),
            decode = FakeWhisperRecognizer::class.java.getDeclaredMethod("decode", streamCls),
            getResult = FakeWhisperRecognizer::class.java.getDeclaredMethod("getResult", streamCls),
            streamRelease = streamCls.getDeclaredMethod("release"),
        )
    }

    private class FakeHolder {
        var online: FakeOnlineRecognizer? = null
        var whisper: FakeWhisperRecognizer? = null
    }

    private class BlockingGate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
    }

    private class FakeOnlineRecognizer(private val events: MutableList<String>) {
        var releaseCount = 0
            private set
        val streams = mutableListOf<FakeOnlineStream>()

        fun createStream(hotwords: String): FakeOnlineStream {
            events.add("online_createStream")
            return FakeOnlineStream(events).also { streams += it }
        }

        fun isReady(stream: FakeOnlineStream): Boolean = !stream.decoded

        fun decode(stream: FakeOnlineStream) {
            stream.decoded = true
            events.add("online_decode")
        }

        fun getResult(stream: FakeOnlineStream): FakeResult = FakeResult("hy general")

        fun release() {
            releaseCount++
            events.add("online_release")
        }
    }

    private class FakeOnlineStream(private val events: MutableList<String>) {
        var decoded = false

        fun acceptWaveform(samples: FloatArray, sampleRate: Int) {
            events.add("online_accept")
        }

        fun inputFinished() {
            events.add("online_inputFinished")
        }

        fun release() {
            events.add("online_stream_release")
        }
    }

    private class FakeWhisperRecognizer(
        private val events: MutableList<String>,
        private val decodeGate: BlockingGate? = null,
    ) {
        var releaseCount = 0
            private set
        val streams = mutableListOf<FakeWhisperStream>()

        fun createStream(): FakeWhisperStream {
            events.add("whisper_createStream")
            return FakeWhisperStream(events).also { streams += it }
        }

        fun decode(stream: FakeWhisperStream) {
            decodeGate?.let {
                it.entered.countDown()
                it.release.await()
            }
            events.add("whisper_decode")
        }

        fun getResult(stream: FakeWhisperStream): FakeResult = FakeResult("hi, jandal")

        fun release() {
            releaseCount++
            events.add("whisper_release")
        }
    }

    private class FakeWhisperStream(private val events: MutableList<String>) {
        fun acceptWaveform(samples: FloatArray, sampleRate: Int) {
            events.add("whisper_accept")
        }

        fun release() {
            events.add("whisper_stream_release")
        }
    }

    private class FakeResult(private val text: String) {
        fun getText(): String = text
    }
}
