package com.kernel.ai.core.inference

import com.google.ai.edge.litertlm.ActivationDataType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * #1765: GPU engines must request FLOAT32 activations; CPU and NPU keep the LiteRT default
 * (null is passed through to EngineConfig, which the engine treats as "not set").
 */
class BackendActivationDataTypeTest {

    @Test
    fun `GPU engines request FLOAT32 activations`() {
        assertEquals(ActivationDataType.FLOAT32, BackendType.GPU.activationDataType())
    }

    @Test
    fun `CPU engines keep the LiteRT default activation type`() {
        assertNull(BackendType.CPU.activationDataType())
    }

    @Test
    fun `NPU engines keep the LiteRT default activation type`() {
        assertNull(BackendType.NPU.activationDataType())
    }
}
