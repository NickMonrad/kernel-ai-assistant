package com.kernel.ai.core.executorch

import org.pytorch.executorch.DType
import org.pytorch.executorch.EValue
import org.pytorch.executorch.ExecutorchRuntimeException
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor

/** Native seam so the lifecycle and assertion logic is testable without the runtime. */
internal interface NativeModule {
    fun methodNames(): Set<String>

    fun execute(method: String, inputs: List<ExecuTorchTensor>, outputSpecs: List<TensorSpec>): List<ExecuTorchTensor>

    fun destroy()
}

internal fun interface NativeModuleLoader {
    fun load(path: String, loadMode: LoadMode, threads: Int): NativeModule
}

/** Production [NativeModuleLoader] backed by `org.pytorch.executorch.Module`. */
internal object PyTorchModuleLoader : NativeModuleLoader {
    override fun load(path: String, loadMode: LoadMode, threads: Int): NativeModule {
        val module = try {
            runtimeCall("load '$path'") { Module.load(path, loadMode.nativeCode, threads) }
        } catch (e: LinkageError) {
            // libexecutorch.so ships for arm64-v8a and x86_64 only.
            throw ExecuTorchException("ExecuTorch native runtime is unavailable on this device: $e", cause = e)
        }
        return PyTorchModule(module)
    }
}

private class PyTorchModule(private val module: Module) : NativeModule {
    override fun methodNames(): Set<String> = runtimeCall("list methods") { module.getMethods() }.toSet()

    override fun execute(
        method: String,
        inputs: List<ExecuTorchTensor>,
        outputSpecs: List<TensorSpec>,
    ): List<ExecuTorchTensor> {
        val values = Array(inputs.size) { EValue.from(inputs[it].toNative()) }
        val outputs = runtimeCall("run method '$method'") { module.execute(method, *values) }
        return outputs.mapIndexed { index, value ->
            if (!value.isTensor) {
                throw ExecuTorchException(
                    "ExecuTorch method '$method' output $index is not a tensor (EValue type " +
                        "${value.mTypeCode}); this module only returns tensors",
                )
            }
            value.toTensor().toKotlin(method, index, outputSpecs.getOrNull(index))
        }
    }

    override fun destroy() = module.destroy()
}

private inline fun <T> runtimeCall(action: String, block: () -> T): T = try {
    block()
} catch (e: ExecutorchRuntimeException) {
    throw ExecuTorchException("ExecuTorch failed to $action: ${e.message}", e.errorCode, e)
}

private fun ExecuTorchTensor.toNative(): Tensor {
    val shape = shapeArray()
    return when (this) {
        is ExecuTorchTensor.Float32 -> Tensor.fromBlob(data, shape)
        is ExecuTorchTensor.Int32 -> Tensor.fromBlob(data, shape)
        is ExecuTorchTensor.Int64 -> Tensor.fromBlob(data, shape)
        is ExecuTorchTensor.Int8 -> Tensor.fromBlob(data, shape)
        is ExecuTorchTensor.UInt8 -> Tensor.fromBlobUnsigned(data, shape)
        is ExecuTorchTensor.Bool -> BoolInputTensor(shape, data)
    }
}

private fun Tensor.toKotlin(method: String, index: Int, spec: TensorSpec?): ExecuTorchTensor {
    val shape = shape()
    return when (val dtype = dtype()) {
        DType.FLOAT -> ExecuTorchTensor.Float32(dataAsFloatArray, shape)
        DType.INT32 -> ExecuTorchTensor.Int32(dataAsIntArray, shape)
        DType.INT64 -> ExecuTorchTensor.Int64(dataAsLongArray, shape)
        DType.INT8 -> ExecuTorchTensor.Int8(dataAsByteArray, shape)
        DType.UINT8 -> ExecuTorchTensor.UInt8(dataAsUnsignedByteArray, shape)
        else -> throw ExecuTorchException(
            "ExecuTorch method '$method' output $index ('${spec?.name ?: "undeclared"}') has unsupported " +
                "dtype $dtype; expected ${spec ?: "no declared output"}",
        )
    }
}
