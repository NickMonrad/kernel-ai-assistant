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
    fun `Arctic production assets are required public checksum-pinned artifacts`() {
        val model = KernelModel.ARCTIC_EMBED_M_V1_5
        val vocab = KernelModel.ARCTIC_EMBED_V1_5_VOCAB

        assertTrue(model.isRequired)
        assertFalse(model.isGated)
        assertEquals("17c2211fbd759e769b3030837d8259a86a2f7603a0f64222fde14474e4c3054d", model.expectedSha256)
        assertTrue(vocab.isRequired)
        assertFalse(vocab.isGated)
        assertEquals("07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3", vocab.expectedSha256)
    }
}
