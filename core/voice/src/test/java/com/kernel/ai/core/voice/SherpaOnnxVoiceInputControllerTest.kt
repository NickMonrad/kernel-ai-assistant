package com.kernel.ai.core.voice

import android.content.Context
import android.media.AudioManager
import kotlinx.coroutines.flow.MutableStateFlow
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun createAllStubModelFiles() {
        listOf(
            "sherpa-stt-encoder.int8.onnx",
            "sherpa-stt-decoder.int8.onnx",
            "sherpa-stt-joiner.int8.onnx",
            "sherpa-stt-tokens.txt",
        ).forEach { name -> File(tempModelsDir, name).writeText("stub") }
    }
}
