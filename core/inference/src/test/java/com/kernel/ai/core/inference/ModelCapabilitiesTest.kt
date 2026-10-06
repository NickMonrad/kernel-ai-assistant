package com.kernel.ai.core.inference

import com.kernel.ai.core.inference.download.KernelModel
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModelCapabilitiesTest {
    @Test
    fun `gemma conversation models expose shared capability metadata`() {
        val e2b = KernelModel.GEMMA_4_E2B.capabilities
        val e4b = KernelModel.GEMMA_4_E4B.capabilities
        val e4bGpuTest = KernelModel.GEMMA_4_E4B_GPU_TEST.capabilities

        assertTrue(e2b.supportsThinking)
        assertTrue(e2b.supportsSpeculativeDecoding)
        assertFalse(e2b.supportsAttachments)

        assertTrue(e4b.supportsThinking)
        assertTrue(e4b.supportsSpeculativeDecoding)
        assertFalse(e4b.supportsAttachments)
        assertTrue(e4bGpuTest.supportsThinking)
        assertTrue(e4bGpuTest.supportsSpeculativeDecoding)
        assertFalse(e4bGpuTest.supportsAttachments)
    }

    @Test
    fun `non conversation embedding model does not expose chat capabilities`() {
        val embedding = KernelModel.ARCTIC_EMBED_M_V1_5.capabilities

        assertFalse(embedding.supportsThinking)
        assertFalse(embedding.supportsImageInput)
        assertFalse(embedding.supportsAudioInput)
        assertFalse(embedding.supportsSpeculativeDecoding)
    }
}
