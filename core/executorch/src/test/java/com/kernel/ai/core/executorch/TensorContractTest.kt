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
    fun `spec range matches sizes inside its bounds only`() {
        val spec = TensorSpec("logits", TensorDType.FLOAT32, TensorDim.exact(1), TensorDim.between(1, 8), TensorDim.exact(3))
        assertTrue(spec.matches(ExecuTorchTensor.Float32(FloatArray(15), longArrayOf(1, 5, 3))))
        assertTrue(!spec.matches(ExecuTorchTensor.Float32(FloatArray(20), longArrayOf(1, 5, 4))))
        assertTrue(!spec.matches(ExecuTorchTensor.Float32(FloatArray(27), longArrayOf(1, 9, 3))))
    }

    @Test
    fun `spec rejects dtype and rank mismatches`() {
        val spec = TensorSpec("ids", TensorDType.INT64, TensorDim.exact(2))
        assertTrue(!spec.matches(ExecuTorchTensor.Int32(IntArray(2), longArrayOf(2))))
        assertTrue(!spec.matches(ExecuTorchTensor.Int64(LongArray(2), longArrayOf(1, 2))))
    }

    @Test
    fun `dim rejects negative sizes and inverted ranges`() {
        assertThrows<IllegalArgumentException> { TensorDim.exact(-2) }
        assertThrows<IllegalArgumentException> { TensorDim.between(4, 2) }
    }

    @Test
    fun `bool factories fill every element with one value and report BOOL`() {
        val ones = ExecuTorchTensor.Bool.allTrue(longArrayOf(1, 5))
        val zeros = ExecuTorchTensor.Bool.allFalse(longArrayOf(1, 5))
        assertEquals(TensorDType.BOOL, ones.dtype)
        assertEquals(listOf(1L, 5L), ones.shape)
        assertTrue(ones.data.all { it } && ones.data.size == 5)
        assertTrue(zeros.data.none { it } && zeros.data.size == 5)
    }

    @Test
    fun `bool tensor with a zero dim holds no elements`() {
        val empty = ExecuTorchTensor.Bool.allTrue(longArrayOf(1, 0))
        assertEquals(0, empty.data.size)
    }

    @Test
    fun `bool spec matches only the bool dtype`() {
        val spec = TensorSpec("textMask", TensorDType.BOOL, TensorDim.exact(1), TensorDim.between(2, 128))
        assertTrue(spec.matches(ExecuTorchTensor.Bool.allTrue(longArrayOf(1, 16))))
        assertTrue(!spec.matches(ExecuTorchTensor.Int64(LongArray(16), longArrayOf(1, 16))))
    }

    @Test
    fun `shape assertion names method, output and both shapes`() {
        val error = assertThrows<OutputShapeMismatchException> {
            assertOutputShapes(
                "encode",
                listOf(ExecuTorchTensor.Float32(FloatArray(8), longArrayOf(2, 4))),
                listOf(TensorSpec("features", TensorDType.FLOAT32, TensorDim.exact(2), TensorDim.exact(3))),
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
                listOf(TensorSpec("y", TensorDType.FLOAT32, TensorDim.exact(1))),
            )
        }
    }
}
