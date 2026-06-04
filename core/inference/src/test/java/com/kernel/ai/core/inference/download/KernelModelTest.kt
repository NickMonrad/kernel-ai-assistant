package com.kernel.ai.core.inference.download

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KernelModelTest {

    @Test
    fun `isDeprecated defaults to false for all models`() {
        KernelModel.entries.forEach { model ->
            // Only SM8550 is explicitly deprecated; all others default to false
            if (model == KernelModel.EMBEDDING_GEMMA_300M_SM8550) {
                assertTrue(model.isDeprecated, "Expected ${model.name} to be deprecated")
            } else {
                assertFalse(model.isDeprecated, "Expected ${model.name} isDeprecated to be false")
            }
        }
    }

    @Test
    fun `deprecated model is excluded from preferredForTier matches`() {
        // SM8550 is deprecated — it should not match any tier preference logic
        assertTrue(KernelModel.EMBEDDING_GEMMA_300M_SM8550.isDeprecated)
    }
}
