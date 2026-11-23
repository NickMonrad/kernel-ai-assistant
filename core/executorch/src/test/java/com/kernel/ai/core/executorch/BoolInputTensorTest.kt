package com.kernel.ai.core.executorch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.pytorch.executorch.DType

class BoolInputTensorTest {
    @Test
    fun `bool input reports BOOL and one byte per element in row-major order`() {
        val tensor = BoolInputTensor(longArrayOf(1, 4), booleanArrayOf(true, false, true, false))

        assertEquals(DType.BOOL, tensor.dtype())
        assertEquals(4L, tensor.numel())
        val bytes = tensor.getRawDataBuffer().let { it as java.nio.ByteBuffer }
        assertEquals(listOf<Byte>(1, 0, 1, 0), (0 until bytes.limit()).map { bytes.get(it) })
    }

    @Test
    fun `bool input with a zero dim has an empty buffer`() {
        val tensor = BoolInputTensor(longArrayOf(1, 0), booleanArrayOf())

        assertEquals(0, tensor.getRawDataBuffer().limit())
    }
}
