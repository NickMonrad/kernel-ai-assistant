package com.kernel.ai.core.voice.asr4all.runtime

import java.io.File

/*
 * Temporary seam for the shared ExecuTorch runtime module (#2003).
 *
 * #2003 owns the only ExecuTorch dependency. Until its PR lands, the asr4all engine (#2004)
 * is written against this small interface, which mirrors the API #2003 describes: load a
 * program from a file path, list its methods, run a named method with input tensors and
 * receive output tensors, set the thread count, close explicitly, and check the shape and
 * dtype of every output the consumer indexes. When #2003 merges, these types are replaced by
 * the module's own and this file is deleted; nothing here adds an ExecuTorch dependency.
 */

/** Element types the asr4all methods use. */
enum class EtDType { FLOAT32, INT64 }

/**
 * A tensor passed to or returned from a program method. Implementations may be backed by a
 * native tensor; [floats]/[longs] give read access to the contents and fail for the wrong
 * dtype. Tensors returned by a method may be passed back as inputs unchanged (the engine
 * does this for its streaming caches), so implementations should avoid copying in that case.
 */
interface EtTensor {
    val dtype: EtDType
    val shape: LongArray
    fun floats(): FloatArray
    fun longs(): LongArray

    companion object {
        fun of(data: FloatArray, vararg shape: Long): EtTensor = FloatEtTensor(data, shape)
        fun of(data: LongArray, vararg shape: Long): EtTensor = LongEtTensor(data, shape)
        fun zeros(vararg shape: Long): EtTensor = FloatEtTensor(FloatArray(numel(shape).toInt()), shape)

        fun numel(shape: LongArray): Long = shape.fold(1L) { acc, d -> acc * d }
    }
}

private class FloatEtTensor(private val data: FloatArray, override val shape: LongArray) : EtTensor {
    init {
        require(EtTensor.numel(shape) == data.size.toLong()) { "shape ${shape.contentToString()} != ${data.size} elements" }
    }
    override val dtype = EtDType.FLOAT32
    override fun floats(): FloatArray = data
    override fun longs(): LongArray = throw IllegalStateException("FLOAT32 tensor read as INT64")
}

private class LongEtTensor(private val data: LongArray, override val shape: LongArray) : EtTensor {
    init {
        require(EtTensor.numel(shape) == data.size.toLong()) { "shape ${shape.contentToString()} != ${data.size} elements" }
    }
    override val dtype = EtDType.INT64
    override fun floats(): FloatArray = throw IllegalStateException("INT64 tensor read as FLOAT32")
    override fun longs(): LongArray = data
}

/**
 * A loaded ExecuTorch program. Not thread-safe: callers serialise access. After [close],
 * every call throws [EtProgramClosedException].
 */
interface EtProgram : AutoCloseable {
    val methodNames: Set<String>

    /** Runs [method]; failures inside the runtime throw [EtExecutionException]. */
    fun execute(method: String, inputs: List<EtTensor>): List<EtTensor>
}

/** Opens programs with a given XNNPACK thread count. */
fun interface EtProgramLoader {
    fun load(file: File, threads: Int): EtProgram
}

/** Expected shape and dtype of one method output the consumer indexes. */
class EtOutputSpec(val shape: LongArray, val dtype: EtDType) {
    override fun toString(): String = "$dtype${shape.contentToString()}"
}

class EtProgramClosedException(message: String) : IllegalStateException(message)

class EtExecutionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Raised when a method output does not have the layout the consumer was about to index. */
class EtOutputShapeMismatchException(
    val method: String,
    val outputIndex: Int,
    val expected: String,
    val actual: String,
) : IllegalStateException("$method output[$outputIndex]: expected $expected, got $actual")

/**
 * Runtime output-shape check (#2003 scope item 4). Consumers call it with the expected layout
 * of every output they index before reading any buffer with a fixed stride. A `null` entry
 * means the consumer does not index that output (it is only passed back as an input).
 */
object EtOutputCheck {
    fun verify(method: String, outputs: List<EtTensor>, expected: List<EtOutputSpec?>) {
        if (outputs.size != expected.size) {
            throw EtOutputShapeMismatchException(method, -1, "${expected.size} outputs", "${outputs.size} outputs")
        }
        expected.forEachIndexed { index, spec ->
            if (spec == null) return@forEachIndexed
            val actual = outputs[index]
            if (actual.dtype != spec.dtype || !actual.shape.contentEquals(spec.shape)) {
                throw EtOutputShapeMismatchException(
                    method,
                    index,
                    spec.toString(),
                    "${actual.dtype}${actual.shape.contentToString()}",
                )
            }
        }
    }
}
