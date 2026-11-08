package com.kernel.ai.assistant

import android.content.Context
import com.kernel.ai.core.inference.download.KernelModel
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/**
 * #1439: the low-band wake verifier prefers the Whisper tiny.en catalogue
 * model (the only model that recognises the fixed natural wake phrase).
 * [WakeWordService] provisions the missing files through the existing
 * catalogue flow when the wake word starts.
 */
class WakeWordVerifierProvisionTest {

    private val tempModelsDir: File = Files.createTempDirectory("wake-verifier-models").toFile()

    private val context: Context = mockk {
        every { getExternalFilesDir("models") } returns tempModelsDir
    }

    @AfterEach
    fun cleanup() {
        tempModelsDir.deleteRecursively()
    }

    @Test
    fun `reports all Whisper verifier files missing on an empty models dir`() {
        val missing = missingWakeVerifierModelFiles(context)

        assertEquals(
            listOf(
                "sherpa-whisper-tiny.en-encoder.int8.onnx",
                "sherpa-whisper-tiny.en-decoder.int8.onnx",
                "sherpa-whisper-tiny.en-tokens.txt",
            ),
            missing,
        )
    }

    @Test
    fun `reports only the missing Whisper verifier files`() {
        File(tempModelsDir, "sherpa-whisper-tiny.en-encoder.int8.onnx").writeText("stub")

        assertEquals(
            listOf(
                "sherpa-whisper-tiny.en-decoder.int8.onnx",
                "sherpa-whisper-tiny.en-tokens.txt",
            ),
            missingWakeVerifierModelFiles(context),
        )
    }

    @Test
    fun `reports nothing when every Whisper verifier file is present`() {
        listOf(
            "sherpa-whisper-tiny.en-encoder.int8.onnx",
            "sherpa-whisper-tiny.en-decoder.int8.onnx",
            "sherpa-whisper-tiny.en-tokens.txt",
        ).forEach { name -> File(tempModelsDir, name).writeText("stub") }

        assertTrue(missingWakeVerifierModelFiles(context).isEmpty())
    }

    @Test
    fun `maps every Whisper verifier file to its catalogue entry`() {
        val models = wakeVerifierKernelModels()

        assertEquals(3, models.size)
        assertEquals(
            listOf(
                "sherpa-whisper-tiny.en-encoder.int8.onnx",
                "sherpa-whisper-tiny.en-decoder.int8.onnx",
                "sherpa-whisper-tiny.en-tokens.txt",
            ),
            models.map { it.fileName },
        )
        assertTrue(models.all { it is KernelModel })
    }
}
