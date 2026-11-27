package com.kernel.ai.core.inference

import android.content.Context
import android.os.Build
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
 * Activation precision for GPU engines on Qualcomm SoCs (#1765). FLOAT32 stops the Adreno
 * digit/word corruption seen with the FP16 default (upstream LiteRT-LM #4001, #4749) at roughly
 * +1.4 GB PSS and +1.5-3 s time to first token. Revert: change this constant to
 * `ActivationDataType.FLOAT16`, or empty [FLOAT32_SOC_MANUFACTURERS].
 */
private val GPU_ACTIVATION_DATA_TYPE: ActivationDataType = ActivationDataType.FLOAT32

/** Lowercased [Build.SOC_MANUFACTURER] values that get [GPU_ACTIVATION_DATA_TYPE] (S23U, Honor Magic8 Pro). */
private val FLOAT32_SOC_MANUFACTURERS = setOf("qualcomm", "qti")

/**
 * Activation precision for an engine on this backend. GPU on a Qualcomm SoC gets FLOAT32; other GPUs
 * (e.g. Exynos/Mali, or an unknown/empty manufacturer) and CPU/NPU return null, the LiteRT default.
 */
fun BackendType.activationDataType(socManufacturer: String? = Build.SOC_MANUFACTURER): ActivationDataType? =
    when (this) {
        BackendType.GPU ->
            if (socManufacturer?.trim()?.lowercase() in FLOAT32_SOC_MANUFACTURERS) GPU_ACTIVATION_DATA_TYPE else null
        BackendType.CPU, BackendType.NPU, BackendType.AUTO -> null
    }
