package com.kernel.ai.core.inference.download

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
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
}
