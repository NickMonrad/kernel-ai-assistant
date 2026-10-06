package com.kernel.ai.core.inference.download

import com.kernel.ai.core.inference.BuildConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KernelModelTest {

    @Test
    fun `isDeprecated defaults to false for all current models`() {
        KernelModel.entries.forEach { model ->
            assertFalse(model.isDeprecated, "Unexpected deprecated model ${model.name}")
        }
    }

    @Test
    fun `Arctic assets use a stable ungated rolling release source`() {
        val model = KernelModel.ARCTIC_EMBED_M_V1_5
        val vocab = KernelModel.ARCTIC_EMBED_V1_5_VOCAB
        val releasePrefix =
            "https://github.com/NickMonrad/kernel-ai-assistant/releases/download/model-arctic-embed-m-v1.5-current/"

        assertTrue(model.isRequired)
        assertFalse(model.isGated)
        assertEquals("arctic-embed-m-v1.5-int8.tflite", model.fileName)
        assertEquals(113_850_784L, model.approxSizeBytes)
        assertEquals("${releasePrefix}${model.fileName}", model.downloadUrl)
        assertTrue(vocab.isRequired)
        assertFalse(vocab.isGated)
        assertEquals("arctic-embed-m-v1.5-vocab.txt", vocab.fileName)
        assertEquals(231_508L, vocab.approxSizeBytes)
        assertEquals("${releasePrefix}${vocab.fileName}", vocab.downloadUrl)
    }

    @Test
    fun `E4B GPU test model uses a distinct debug-only pinned artifact`() {
        val gpuTest = KernelModel.GEMMA_4_E4B_GPU_TEST
        val genericE4b = KernelModel.GEMMA_4_E4B
        val uploadCommit = "2eee7ac325f20eb8c9ac1d0e972f7c84663062da"

        assertEquals("Gemma 4 E-4B GPU (Test)", gpuTest.displayName)
        assertEquals("gemma-4-E4B-it-gpu.litertlm", gpuTest.fileName)
        assertNotEquals(genericE4b.fileName, gpuTest.fileName)
        assertEquals(2_969_059_328L, gpuTest.approxSizeBytes)
        assertEquals(
            "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/$uploadCommit/${gpuTest.fileName}",
            gpuTest.downloadUrl,
        )
        assertFalse(gpuTest.isRequired)
        assertNull(gpuTest.preferredForTier)
        assertFalse(gpuTest.isGated)
        assertEquals(BuildConfig.DEBUG, gpuTest.showInModelManagement)
        assertEquals("gemma-4-E4B-it.litertlm", genericE4b.fileName)
    }
}
