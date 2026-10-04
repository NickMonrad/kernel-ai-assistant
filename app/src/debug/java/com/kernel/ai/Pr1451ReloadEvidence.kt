package com.kernel.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

internal suspend fun initializePr1451ReloadWithTimeout(
    timeoutMs: Long,
    phase: String,
    initialize: suspend () -> Unit,
) {
    val initializationCompleted = withTimeoutOrNull(timeoutMs) {
        initialize()
        true
    } ?: false
    check(initializationCompleted) { "Timed out initializing $phase" }
}

internal data class Pr1451ReloadPostInitState(
    val engineReady: Boolean,
    val activeBackend: String?,
)

internal data class Pr1451ReloadCapture<T>(
    val preInitAvailableSystemMemoryMiB: Long?,
    val postInitState: T?,
    val failureMessage: String?,
)

internal suspend fun <T> capturePr1451Reload(
    sampleAvailableSystemMemoryMiB: suspend () -> Long?,
    initialize: suspend () -> Unit,
    capturePostInitState: () -> T,
): Pr1451ReloadCapture<T> {
    val availableMemoryMiB = sampleAvailableSystemMemoryMiB()
    try {
        initialize()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        return Pr1451ReloadCapture(
            preInitAvailableSystemMemoryMiB = availableMemoryMiB,
            postInitState = null,
            failureMessage = "${failure::class.java.simpleName}: ${failure.message.orEmpty()}",
        )
    }
    return Pr1451ReloadCapture(
        preInitAvailableSystemMemoryMiB = availableMemoryMiB,
        postInitState = capturePostInitState(),
        failureMessage = null,
    )
}

internal data class Pr1451ReloadObservation(
    val backend: String?,
    val preInitAvailableSystemMemoryMiB: Long?,
    val engineReady: Boolean?,
    val processPssMiB: Double?,
    val processRssMiB: Double?,
    val gpuMinimumAvailableMemoryMiB: Long,
    val gpuMemoryHeadroomMiB: Long?,
    val gpuRetained: Boolean?,
    val outcome: String,
    val failureMessage: String?,
) {
    fun reportFields(prefix: String): Map<String, Any?> = mapOf(
        "${prefix}_backend" to backend,
        "${prefix}_pre_init_available_system_memory_mib" to preInitAvailableSystemMemoryMiB,
        "${prefix}_engine_ready" to engineReady,
        "${prefix}_gpu_minimum_available_memory_mib" to gpuMinimumAvailableMemoryMiB,
        "${prefix}_gpu_memory_headroom_mib" to gpuMemoryHeadroomMiB,
        "${prefix}_process_pss_mib" to processPssMiB,
        "${prefix}_process_rss_mib" to processRssMiB,
        "${prefix}_gpu_retained" to gpuRetained,
        "${prefix}_outcome" to outcome,
        "${prefix}_failure" to failureMessage,
    )
}

internal data class Pr1451ReloadSummary(
    val armStatus: String,
    val reloadGpuStabilityStatus: String,
)

internal class Pr1451ReloadOrderGate {
    private var initialGpuMeasurementsComplete = false

    fun markInitialGpuMeasurementsComplete() {
        check(!initialGpuMeasurementsComplete) { "Initial GPU measurements were already marked complete" }
        initialGpuMeasurementsComplete = true
    }

    fun requireDiagnosticReloadAllowed() {
        check(initialGpuMeasurementsComplete) {
            "Diagnostic reloads must follow the initial-GPU functional and thermal measurements"
        }
    }
}

/**
 * Reload observations do not change production backend policy. Ready CPU fallback is diagnostic;
 * initialization errors and not-ready engines fail the benchmark arm.
 */
internal object Pr1451ReloadEvidence {
    const val GPU_MINIMUM_AVAILABLE_MEMORY_MIB = 2048L

    fun requireGpuBackend(backend: String?) {
        check(backend == "GPU") { "GPU run fell back or selected another backend" }
    }

    fun assess(
        backend: String?,
        preInitAvailableSystemMemoryMiB: Long?,
        engineReady: Boolean?,
        processPssMiB: Double?,
        processRssMiB: Double?,
        failureMessage: String? = null,
    ): Pr1451ReloadObservation {
        val normalizedBackend = backend?.uppercase()
        val outcome = when {
            !failureMessage.isNullOrBlank() -> "reload_error"
            engineReady != true -> "reload_not_ready"
            normalizedBackend == "GPU" -> "gpu_retained"
            normalizedBackend == "CPU" -> "cpu_fallback"
            else -> "unknown_backend"
        }
        val gpuRetained = when (outcome) {
            "gpu_retained" -> true
            "cpu_fallback", "reload_not_ready" -> false
            else -> null
        }
        return Pr1451ReloadObservation(
            backend = backend,
            preInitAvailableSystemMemoryMiB = preInitAvailableSystemMemoryMiB,
            engineReady = engineReady,
            processPssMiB = processPssMiB,
            processRssMiB = processRssMiB,
            gpuMinimumAvailableMemoryMiB = GPU_MINIMUM_AVAILABLE_MEMORY_MIB,
            gpuMemoryHeadroomMiB = preInitAvailableSystemMemoryMiB?.minus(GPU_MINIMUM_AVAILABLE_MEMORY_MIB),
            gpuRetained = gpuRetained,
            outcome = outcome,
            failureMessage = failureMessage,
        )
    }

    fun summarize(
        armStatus: String,
        warmReload: Pr1451ReloadObservation,
        backgroundReload: Pr1451ReloadObservation,
    ): Pr1451ReloadSummary {
        val observations = listOf(warmReload, backgroundReload)
        val armFailed = observations.any { it.outcome == "reload_error" || it.outcome == "reload_not_ready" }
        val reloadStatus = when {
            observations.any { it.outcome == "reload_error" } -> "failed_reload_error"
            observations.any { it.outcome == "reload_not_ready" } -> "failed_reload_not_ready"
            observations.any { it.outcome == "cpu_fallback" } -> "failed_cpu_fallback"
            observations.all { it.gpuRetained == true } -> "passed"
            else -> "unknown_backend"
        }
        return Pr1451ReloadSummary(
            armStatus = if (armStatus == "passed" && armFailed) "failed" else armStatus,
            reloadGpuStabilityStatus = reloadStatus,
        )
    }
}
