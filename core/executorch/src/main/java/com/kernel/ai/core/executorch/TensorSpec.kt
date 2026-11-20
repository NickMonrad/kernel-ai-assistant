package com.kernel.ai.core.executorch

/**
 * The dtype and shape expected for one method input or output.
 *
 * ExecuTorch's Java API exposes no input/output shape metadata, so callers declare it. Use
 * [ANY] for a dimension that legitimately varies (for example a frame count that follows the
 * input length); a dynamic dimension must still stay within the bounds the program was
 * exported with.
 *
 * @param name label used in error messages, e.g. "logits".
 */
class TensorSpec(val name: String, val dtype: TensorDType, vararg dims: Long) {
    /** Expected dimensions; [ANY] matches any size. */
    val dims: List<Long> = dims.toList()

    init {
        require(name.isNotBlank()) { "TensorSpec name must not be blank" }
        require(this.dims.all { it >= 0 || it == ANY }) {
            "TensorSpec '$name' dims must be >= 0 or TensorSpec.ANY, got ${this.dims}"
        }
    }

    fun matches(tensor: ExecuTorchTensor): Boolean =
        tensor.dtype == dtype &&
            tensor.shape.size == dims.size &&
            dims.indices.all { dims[it] == ANY || dims[it] == tensor.shape[it] }

    override fun toString(): String =
        "$name: $dtype${dims.joinToString(prefix = "[", postfix = "]") { if (it == ANY) "*" else it.toString() }}"

    companion object {
        /** Wildcard dimension. */
        const val ANY: Long = -1L
    }
}

/**
 * The inputs and outputs a caller expects from one method of a program.
 *
 * Declare one per method, next to the code that builds its inputs. [ExecuTorchModel.run]
 * checks inputs before calling the runtime and outputs before returning them.
 */
class MethodSignature(
    val method: String,
    val inputs: List<TensorSpec>,
    val outputs: List<TensorSpec>,
) {
    init {
        require(method.isNotBlank()) { "MethodSignature method must not be blank" }
    }

    override fun toString(): String = "$method($inputs) -> $outputs"
}

/** Inputs passed to a method differ in number, dtype or shape from its signature. */
class InputShapeMismatchException(message: String) : IllegalArgumentException(message)

/** A method returned a different number, dtype or shape of outputs than its signature declares. */
class OutputShapeMismatchException(message: String) : IllegalStateException(message)

/**
 * Asserts [inputs] against [expected], index by index, before they reach the runtime.
 *
 * @throws InputShapeMismatchException naming the method, the input and both shapes.
 */
fun requireInputShapes(method: String, inputs: List<ExecuTorchTensor>, expected: List<TensorSpec>) {
    mismatch("input", method, inputs, expected)?.let { throw InputShapeMismatchException(it) }
}

/**
 * Asserts [outputs] against [expected], index by index.
 *
 * @throws OutputShapeMismatchException naming the method, the output and both shapes.
 */
fun assertOutputShapes(method: String, outputs: List<ExecuTorchTensor>, expected: List<TensorSpec>) {
    mismatch("output", method, outputs, expected)?.let { throw OutputShapeMismatchException(it) }
}

private fun mismatch(
    kind: String,
    method: String,
    tensors: List<ExecuTorchTensor>,
    expected: List<TensorSpec>,
): String? {
    if (tensors.size != expected.size) {
        return "ExecuTorch method '$method' has ${tensors.size} $kind(s) $tensors; " +
            "expected ${expected.size}: $expected"
    }
    val index = tensors.indices.firstOrNull { !expected[it].matches(tensors[it]) } ?: return null
    return "ExecuTorch method '$method' $kind $index ('${expected[index].name}') is " +
        "${tensors[index]}; expected ${expected[index]}"
}
