package com.kernel.ai.core.inference

import com.google.ai.edge.litertlm.ActivationDataType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * #1765: GPU engines on Qualcomm SoCs request FLOAT32 activations. Other GPUs, unknown SoC
 * manufacturers, CPU and NPU keep the LiteRT default (null is passed through to EngineConfig).
 */
class BackendActivationDataTypeTest {

    @Test
    fun `GPU on Qualcomm requests FLOAT32 activations`() {
        assertEquals(ActivationDataType.FLOAT32, BackendType.GPU.activationDataType("Qualcomm"))
    }

    @Test
    fun `GPU on QTI manufacturer string requests FLOAT32 activations`() {
        assertEquals(ActivationDataType.FLOAT32, BackendType.GPU.activationDataType("QTI"))
    }

    @Test
    fun `GPU on Samsung Exynos keeps the LiteRT default`() {
        assertNull(BackendType.GPU.activationDataType("Samsung"))
    }

    @Test
    fun `GPU with unknown SoC manufacturer keeps the LiteRT default`() {
        assertNull(BackendType.GPU.activationDataType(""))
        assertNull(BackendType.GPU.activationDataType(null))
    }

    @Test
    fun `CPU keeps the LiteRT default even on Qualcomm`() {
        assertNull(BackendType.CPU.activationDataType("Qualcomm"))
    }

    @Test
    fun `NPU keeps the LiteRT default even on Qualcomm`() {
        assertNull(BackendType.NPU.activationDataType("Qualcomm"))
    }
}
