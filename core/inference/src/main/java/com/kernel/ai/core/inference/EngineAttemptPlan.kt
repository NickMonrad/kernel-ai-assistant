package com.kernel.ai.core.inference

import com.google.ai.edge.litertlm.ActivationDataType
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Absolute MemAvailable floor before any GPU or NPU engine init. Catches 4-6 GB devices. */
internal const val MIN_AVAIL_MEM_FOR_GPU_BYTES = 2L * 1024 * 1024 * 1024

/**
 * Extra MemAvailable required on top of [MIN_AVAIL_MEM_FOR_GPU_BYTES] when GPU runs FLOAT32
 * activations (#2137, follow-up of #2130). The S23U measured +1.4 GB PSS and +2.05 GB GL mtrack
 * with FLOAT32, while MemAvailable sat at 1.1-1.4 GB during chat, so FLOAT32 init gets its own
 * headroom (~1.6 GB).
 */
internal const val FLOAT32_EXTRA_AVAIL_MEM_BYTES = 1_600L * 1024 * 1024

/**
 * One engine-init attempt. [activation] null means the LiteRT default precision. [reason] is logged
 * so the path that ran is visible in logcat.
 */
internal data class EngineAttempt(
    val backend: BackendType,
    val activation: ActivationDataType?,
    val reason: String,
)

/**
 * Ordered engine-init attempts for [requested]. [gpuPreferred] is the GPU activation this device
 * asks for (see [activationDataType]); null means the default precision is the only GPU option.
 * CPU is always the final attempt unless the request is CPU itself.
 */
internal fun planEngineAttempts(
    requested: BackendType,
    availMem: Long,
    gpuPreferred: ActivationDataType?,
): List<EngineAttempt> {
    if (requested == BackendType.CPU) return listOf(EngineAttempt(BackendType.CPU, null, "requested"))
    val cpu = EngineAttempt(BackendType.CPU, null, "cpu fallback")
    val gpu = planGpuAttempts(availMem, gpuPreferred)
    // Below the absolute floor GPU is skipped, and NPU is skipped with it (unchanged behaviour).
    if (gpu.isEmpty()) return listOf(cpu)
    val npu = if (requested == BackendType.NPU) {
        listOf(EngineAttempt(BackendType.NPU, null, "requested"))
    } else {
        emptyList()
    }
    return npu + gpu + cpu
}

internal fun planGpuAttempts(availMem: Long, gpuPreferred: ActivationDataType?): List<EngineAttempt> {
    if (availMem < MIN_AVAIL_MEM_FOR_GPU_BYTES) return emptyList()
    if (gpuPreferred == null) return listOf(EngineAttempt(BackendType.GPU, null, "default precision"))
    if (availMem < MIN_AVAIL_MEM_FOR_GPU_BYTES + FLOAT32_EXTRA_AVAIL_MEM_BYTES) {
        return listOf(EngineAttempt(BackendType.GPU, null, "float32 memory short"))
    }
    return listOf(
        EngineAttempt(BackendType.GPU, gpuPreferred, "float32"),
        EngineAttempt(BackendType.GPU, null, "default precision after float32 failure"),
    )
}

/**
 * Runs [init] for each attempt in order and returns the first success with its attempt. A failed
 * attempt (including a per-attempt timeout) moves on to the next one. Cancellation of the caller
 * propagates. When every attempt fails, the last failure is rethrown.
 */
internal suspend fun <T : Any> runEngineAttempts(
    attempts: List<EngineAttempt>,
    init: suspend (EngineAttempt) -> T,
): Pair<EngineAttempt, T> {
    var lastFailure: Exception? = null
    for (attempt in attempts) {
        try {
            return attempt to init(attempt)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            lastFailure = e
        }
    }
    throw lastFailure ?: IllegalArgumentException("no engine attempts planned")
}
