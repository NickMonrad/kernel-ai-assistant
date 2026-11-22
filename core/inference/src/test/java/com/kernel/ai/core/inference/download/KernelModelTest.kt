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

    @Test
    fun `every model has its own file name`() {
        val names = KernelModel.entries.map { it.fileName }
        assertEquals(names.size, names.toSet().size, "duplicate fileName among ${names.groupBy { it }.filterValues { it.size > 1 }.keys}")
    }

    @Test
    fun `Gemma and asr4all downloads show the Terms link, other models do not`() {
        assertTrue(KernelModel.GEMMA_4_E4B.showsTermsOfUseLink)
        assertTrue(KernelModel.GEMMA_4_E2B.showsTermsOfUseLink)
        KernelModel.entries.filter { it.requiresTermsAcceptance }.forEach { model ->
            assertTrue(model.showsTermsOfUseLink, model.name)
        }
        assertFalse(KernelModel.ARCTIC_EMBED_M_V1_5.showsTermsOfUseLink)
    }
}
