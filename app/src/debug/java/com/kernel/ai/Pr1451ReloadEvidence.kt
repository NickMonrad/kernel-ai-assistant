package com.kernel.ai

internal data class Pr1451ReloadObservation(
    val backend: String?,
    val availableSystemMemoryMiB: Long?,
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
        "${prefix}_available_system_memory_mib" to availableSystemMemoryMiB,
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

/** #1451 diagnostic reload evidence; it never changes production backend policy or arm status. */
internal object Pr1451ReloadEvidence {
    const val GPU_MINIMUM_AVAILABLE_MEMORY_MIB = 2048L

    fun requireGpuBackend(backend: String?) {
        check(backend == "GPU") { "GPU run fell back or selected another backend" }
    }

    fun assess(
        backend: String?,
        availableSystemMemoryMiB: Long?,
        processPssMiB: Double?,
        processRssMiB: Double?,
        failureMessage: String? = null,
    ): Pr1451ReloadObservation {
        val normalizedBackend = backend?.uppercase()
        val outcome = when {
            !failureMessage.isNullOrBlank() -> "reload_error"
            normalizedBackend == "GPU" -> "gpu_retained"
            normalizedBackend == "CPU" -> "cpu_fallback"
            else -> "unknown_backend"
        }
        val gpuRetained = when (outcome) {
            "gpu_retained" -> true
            "cpu_fallback" -> false
            else -> null
        }
        return Pr1451ReloadObservation(
            backend = backend,
            availableSystemMemoryMiB = availableSystemMemoryMiB,
            processPssMiB = processPssMiB,
            processRssMiB = processRssMiB,
            gpuMinimumAvailableMemoryMiB = GPU_MINIMUM_AVAILABLE_MEMORY_MIB,
            gpuMemoryHeadroomMiB = availableSystemMemoryMiB?.minus(GPU_MINIMUM_AVAILABLE_MEMORY_MIB),
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
        val reloadStatus = when {
            observations.any { it.outcome == "cpu_fallback" } -> "failed_cpu_fallback"
            observations.any { it.outcome == "reload_error" } -> "failed_reload_error"
            observations.all { it.gpuRetained == true } -> "passed"
            else -> "unknown_backend"
        }
        return Pr1451ReloadSummary(
            armStatus = armStatus,
            reloadGpuStabilityStatus = reloadStatus,
        )
    }
}
