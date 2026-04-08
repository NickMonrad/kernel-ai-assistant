package com.kernel.ai.core.inference

import android.content.Context
import com.google.ai.edge.litertlm.Backend

enum class BackendType {
    /** Force CPU execution with XNNPACK delegate. */
    CPU,

    /** GPU via OpenCL delegate. Requires libOpenCL.so on the device. */
    GPU,

    /** Qualcomm Hexagon NPU via QNN delegate. Requires nativeLibraryDir. */
    NPU,

    /** Try NPU → GPU → CPU in order. */
    AUTO;
}

fun BackendType.toBackend(context: Context): Backend = when (this) {
    BackendType.CPU -> Backend.CPU(numOfThreads = 4)
    BackendType.GPU -> Backend.GPU()
    BackendType.NPU -> Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
    BackendType.AUTO -> Backend.GPU() // AUTO is resolved before calling toBackend
}
