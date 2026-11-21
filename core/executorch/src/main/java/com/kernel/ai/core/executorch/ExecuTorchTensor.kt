package com.kernel.ai.core.executorch

/** Element types this module passes to and from ExecuTorch programs. */
enum class TensorDType { FLOAT32, INT32, INT64, INT8, UINT8 }

/**
 * A dense, row-major tensor exchanged with an ExecuTorch method.
 *
 * The element count of [shape] must equal the backing array's length; a mismatch fails
 * construction rather than reaching native code. The backing array is not copied here (the
 * runtime copies inputs into its own buffer), so do not mutate it while a run is in flight.
 */
sealed class ExecuTorchTensor(shape: LongArray, dataSize: Int) {
    abstract val dtype: TensorDType

    private val shapeArray: LongArray = shape.copyOf()

    /** Dimensions, outermost first. A scalar has an empty shape. */
    val shape: List<Long> = shapeArray.asList()

    init {
        require(shapeArray.all { it >= 0 }) { "Tensor dims must be >= 0, got ${shape.toList()}" }
        val elements = shapeArray.fold(1L) { acc, dim -> Math.multiplyExact(acc, dim) }
        require(elements == dataSize.toLong()) {
            "Tensor shape ${shape.toList()} holds $elements elements but the data has $dataSize"
        }
    }

    /** Shape as a fresh array for the native API. */
    internal fun shapeArray(): LongArray = shapeArray.copyOf()

    override fun toString(): String = "$dtype$shape"

    class Float32(val data: FloatArray, shape: LongArray) : ExecuTorchTensor(shape, data.size) {
        override val dtype get() = TensorDType.FLOAT32
    }

    class Int32(val data: IntArray, shape: LongArray) : ExecuTorchTensor(shape, data.size) {
        override val dtype get() = TensorDType.INT32
    }

    class Int64(val data: LongArray, shape: LongArray) : ExecuTorchTensor(shape, data.size) {
        override val dtype get() = TensorDType.INT64
    }

    /** Signed 8-bit elements. */
    class Int8(val data: ByteArray, shape: LongArray) : ExecuTorchTensor(shape, data.size) {
        override val dtype get() = TensorDType.INT8
    }

    /** Unsigned 8-bit elements stored in a [ByteArray]; read with `toInt() and 0xFF`. */
    class UInt8(val data: ByteArray, shape: LongArray) : ExecuTorchTensor(shape, data.size) {
        override val dtype get() = TensorDType.UINT8
    }
}
