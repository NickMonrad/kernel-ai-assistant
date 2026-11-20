package com.kernel.ai.core.executorch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class TensorContractTest {
    @Test
    fun `tensor rejects data that does not fill its shape`() {
        val error = assertThrows<IllegalArgumentException> {
            ExecuTorchTensor.Float32(FloatArray(5), longArrayOf(1, 4))
        }
        assertTrue(error.message!!.contains("[1, 4]"))
    }

    @Test
    fun `tensor rejects negative dims`() {
        assertThrows<IllegalArgumentException> { ExecuTorchTensor.Int64(LongArray(0), longArrayOf(-1)) }
    }

    @Test
    fun `scalar tensor holds one element`() {
        assertDoesNotThrow { ExecuTorchTensor.Int32(intArrayOf(7), longArrayOf()) }
    }

    @Test
    fun `tensor shape is not affected by later changes to the caller's array`() {
        val shape = longArrayOf(2, 3)
        val tensor = ExecuTorchTensor.Int8(ByteArray(6), shape)
        shape[0] = 99
        assertEquals(listOf(2L, 3L), tensor.shape)
    }

    @Test
    fun `spec wildcard matches any size in that dimension only`() {
        val spec = TensorSpec("logits", TensorDType.FLOAT32, 1, TensorSpec.ANY, 3)
        assertTrue(spec.matches(ExecuTorchTensor.Float32(FloatArray(15), longArrayOf(1, 5, 3))))
        assertTrue(!spec.matches(ExecuTorchTensor.Float32(FloatArray(20), longArrayOf(1, 5, 4))))
    }

    @Test
    fun `spec rejects dtype and rank mismatches`() {
        val spec = TensorSpec("ids", TensorDType.INT64, 2)
        assertTrue(!spec.matches(ExecuTorchTensor.Int32(IntArray(2), longArrayOf(2))))
        assertTrue(!spec.matches(ExecuTorchTensor.Int64(LongArray(2), longArrayOf(1, 2))))
    }

    @Test
    fun `spec rejects dims other than sizes and ANY`() {
        assertThrows<IllegalArgumentException> { TensorSpec("x", TensorDType.FLOAT32, -2) }
    }

    @Test
    fun `shape assertion names method, output and both shapes`() {
        val error = assertThrows<OutputShapeMismatchException> {
            assertOutputShapes(
                "encode",
                listOf(ExecuTorchTensor.Float32(FloatArray(8), longArrayOf(2, 4))),
                listOf(TensorSpec("features", TensorDType.FLOAT32, 2, 3)),
            )
        }
        val message = error.message!!
        assertTrue(message.contains("'encode'"), message)
        assertTrue(message.contains("'features'"), message)
        assertTrue(message.contains("FLOAT32[2, 4]"), message)
        assertTrue(message.contains("FLOAT32[2, 3]"), message)
    }

    @Test
    fun `shape assertion rejects a different output count`() {
        assertThrows<OutputShapeMismatchException> {
            assertOutputShapes(
                "forward",
                listOf(
                    ExecuTorchTensor.Float32(FloatArray(1), longArrayOf(1)),
                    ExecuTorchTensor.Float32(FloatArray(1), longArrayOf(1)),
                ),
                listOf(TensorSpec("y", TensorDType.FLOAT32, 1)),
            )
        }
    }
}
