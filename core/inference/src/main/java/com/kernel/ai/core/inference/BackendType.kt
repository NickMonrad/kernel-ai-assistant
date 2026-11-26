package com.kernel.ai.core.inference

import android.content.Context
import com.google.ai.edge.litertlm.ActivationDataType
import com.google.ai.edge.litertlm.Backend

enum class BackendType {
    /** Force CPU execution with XNNPACK delegate. */
    CPU,

    /** GPU via OpenCL delegate. Requires libOpenCL.so on the device. */
    GPU,

    /** Qualcomm Hexagon NPU via QNN delegate. Requires nativeLibraryDir. */
    NPU,

    /** Resolved by [HardwareProfileDetector]: GPU for FLAGSHIP/MID_RANGE, CPU for LOW_POWER. */
    AUTO;
}

fun BackendType.toBackend(context: Context): Backend = when (this) {
    BackendType.CPU -> Backend.CPU(numOfThreads = 4)
    BackendType.GPU -> Backend.GPU()
    BackendType.NPU -> Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
    BackendType.AUTO -> Backend.GPU() // AUTO is resolved before calling toBackend
}

/**
 * Activation precision for GPU engines (#1765). FLOAT32 stops the Adreno digit/word corruption
 * seen with the FP16 default (upstream LiteRT-LM #4001, #4749) at roughly +1.4 GB PSS and
 * +1.5-3 s time to first token. Revert: change this constant to `ActivationDataType.FLOAT16`.
 */
private val GPU_ACTIVATION_DATA_TYPE: ActivationDataType = ActivationDataType.FLOAT32

/** Activation precision for an engine on this backend; null keeps the LiteRT default (CPU, NPU). */
fun BackendType.activationDataType(): ActivationDataType? = when (this) {
    BackendType.GPU -> GPU_ACTIVATION_DATA_TYPE
    BackendType.CPU, BackendType.NPU, BackendType.AUTO -> null
}
