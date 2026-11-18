package com.kernel.ai.core.voice.asr4all

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.nio.FloatBuffer
import java.nio.LongBuffer

/** Static shape of a named graph input or output, as declared by the graph. */
internal fun OrtSession.inputShape(name: String): LongArray =
    (inputInfo.getValue(name).info as TensorInfo).shape

internal fun OrtSession.outputShape(name: String): LongArray =
    (outputInfo.getValue(name).info as TensorInfo).shape

internal fun OrtEnvironment.floats(data: FloatArray, shape: LongArray): OnnxTensor =
    OnnxTensor.createTensor(this, FloatBuffer.wrap(data), shape)

internal fun OrtEnvironment.longs(data: LongArray, shape: LongArray): OnnxTensor =
    OnnxTensor.createTensor(this, LongBuffer.wrap(data), shape)

/**
 * Runs [this] with [inputs] (closed afterwards) and returns each requested output copied out as a
 * [FloatArray] or [LongArray]. Outputs are copied before the result is released, so callers never
 * hold ORT-owned memory.
 */
internal fun OrtSession.runCopy(inputs: Map<String, OnnxTensor>, outputs: List<String>): Map<String, Any> {
    try {
        return run(inputs, outputs.toSet()).use { result ->
            outputs.associateWith { name ->
                val tensor = result.get(name).orElseThrow() as OnnxTensor
                when (tensor.info.type) {
                    OnnxJavaType.FLOAT -> tensor.floatBuffer.let { b -> FloatArray(b.remaining()).also { b.get(it) } }
                    OnnxJavaType.INT64 -> tensor.longBuffer.let { b -> LongArray(b.remaining()).also { b.get(it) } }
                    else -> error("unsupported asr4all output type ${tensor.info.type} for $name")
                }
            }
        }
    } finally {
        inputs.values.forEach { it.close() }
    }
}

@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any>.floats(name: String): FloatArray = getValue(name) as FloatArray

@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any>.longs(name: String): LongArray = getValue(name) as LongArray
