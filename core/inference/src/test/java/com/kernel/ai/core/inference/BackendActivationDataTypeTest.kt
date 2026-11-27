package com.kernel.ai.core.inference

import com.google.ai.edge.litertlm.ActivationDataType
import com.kernel.ai.core.inference.hardware.HardwareTier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * #1765: only GPU on a Qualcomm FLAGSHIP SoC requests FLOAT32 activations. Everything else
 * (other GPUs, MID_RANGE Qualcomm, unknown manufacturer, CPU, NPU, AUTO) keeps the LiteRT default.
 */
class BackendActivationDataTypeTest {

    @Test
    fun `GPU on Qualcomm FLAGSHIP requests FLOAT32 activations`() {
        assertEquals(
            ActivationDataType.FLOAT32,
            BackendType.GPU.activationDataType("qualcomm", HardwareTier.FLAGSHIP),
        )
    }

    @Test
    fun `GPU on QTI FLAGSHIP requests FLOAT32 activations`() {
        assertEquals(
            ActivationDataType.FLOAT32,
            BackendType.GPU.activationDataType("QTI", HardwareTier.FLAGSHIP),
        )
    }

    @Test
    fun `GPU on Qualcomm MID_RANGE keeps the LiteRT default`() {
        assertNull(BackendType.GPU.activationDataType("QTI", HardwareTier.MID_RANGE))
    }

    @Test
    fun `GPU on Samsung Exynos FLAGSHIP keeps the LiteRT default`() {
        assertNull(BackendType.GPU.activationDataType("Samsung", HardwareTier.FLAGSHIP))
    }

    @Test
    fun `GPU with unknown SoC manufacturer keeps the LiteRT default`() {
        assertNull(BackendType.GPU.activationDataType("unknown", HardwareTier.FLAGSHIP))
        assertNull(BackendType.GPU.activationDataType("", HardwareTier.FLAGSHIP))
    }

    @Test
    fun `CPU keeps the LiteRT default even on Qualcomm FLAGSHIP`() {
        assertNull(BackendType.CPU.activationDataType("QTI", HardwareTier.FLAGSHIP))
    }

    @Test
    fun `NPU keeps the LiteRT default even on Qualcomm FLAGSHIP`() {
        assertNull(BackendType.NPU.activationDataType("QTI", HardwareTier.FLAGSHIP))
    }

    @Test
    fun `AUTO keeps the LiteRT default even on Qualcomm FLAGSHIP`() {
        assertNull(BackendType.AUTO.activationDataType("QTI", HardwareTier.FLAGSHIP))
    }
}
