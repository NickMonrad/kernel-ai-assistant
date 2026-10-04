package com.kernel.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Pr1451ReloadEvidenceTest {
    @Test
    fun gpuReloadIsReportedAsRetained() {
        val observation = Pr1451ReloadEvidence.assess(
            backend = "GPU",
            availableSystemMemoryMiB = 2_300L,
            processPssMiB = 2_036.0,
            processRssMiB = 2_100.0,
        )

        assertEquals("gpu_retained", observation.outcome)
        assertEquals(true, observation.gpuRetained)
        assertEquals(2_048L, observation.gpuMinimumAvailableMemoryMiB)
        assertEquals(252L, observation.gpuMemoryHeadroomMiB)
    }

    @Test
    fun cpuReloadReportsNegativeHeadroomAndDoesNotFailCompletedArm() {
        val cpuReload = Pr1451ReloadEvidence.assess(
            backend = "CPU",
            availableSystemMemoryMiB = 1_925L,
            processPssMiB = 2_036.0,
            processRssMiB = 2_100.0,
        )
        val gpuReload = Pr1451ReloadEvidence.assess(
            backend = "GPU",
            availableSystemMemoryMiB = 2_300L,
            processPssMiB = 2_000.0,
            processRssMiB = 2_050.0,
        )
        val summary = Pr1451ReloadEvidence.summarize("passed", cpuReload, gpuReload)
        val fields = cpuReload.reportFields("warm_reload")

        assertEquals("cpu_fallback", cpuReload.outcome)
        assertEquals(false, cpuReload.gpuRetained)
        assertEquals(-123L, cpuReload.gpuMemoryHeadroomMiB)
        assertEquals("CPU", fields["warm_reload_backend"])
        assertEquals(1_925L, fields["warm_reload_available_system_memory_mib"])
        assertEquals(-123L, fields["warm_reload_gpu_memory_headroom_mib"])
        assertEquals(2_036.0, fields["warm_reload_process_pss_mib"])
        assertEquals(2_100.0, fields["warm_reload_process_rss_mib"])
        assertEquals(false, fields["warm_reload_gpu_retained"])
        assertEquals("cpu_fallback", fields["warm_reload_outcome"])
        assertEquals("passed", summary.armStatus)
        assertEquals("failed_cpu_fallback", summary.reloadGpuStabilityStatus)
    }

    @Test
    fun unrecognizedReloadBackendRemainsUnknownRatherThanCpuFailure() {
        val unknownReload = Pr1451ReloadEvidence.assess(
            backend = "NPU",
            availableSystemMemoryMiB = null,
            processPssMiB = 2_036.0,
            processRssMiB = 2_100.0,
        )
        val gpuReload = Pr1451ReloadEvidence.assess(
            backend = "GPU",
            availableSystemMemoryMiB = 2_300L,
            processPssMiB = 2_000.0,
            processRssMiB = 2_050.0,
        )
        val summary = Pr1451ReloadEvidence.summarize("passed", unknownReload, gpuReload)

        assertEquals("unknown_backend", unknownReload.outcome)
        assertNull(unknownReload.gpuRetained)
        assertNull(unknownReload.gpuMemoryHeadroomMiB)
        assertEquals("passed", summary.armStatus)
        assertEquals("unknown_backend", summary.reloadGpuStabilityStatus)
    }

    @Test
    fun initialGpuGateRejectsCpuAndUnknownBackends() {
        assertThrows(IllegalStateException::class.java) {
            Pr1451ReloadEvidence.requireGpuBackend("CPU")
        }
        assertThrows(IllegalStateException::class.java) {
            Pr1451ReloadEvidence.requireGpuBackend(null)
        }
        Pr1451ReloadEvidence.requireGpuBackend("GPU")
    }

    @Test
    fun diagnosticReloadsAreBlockedUntilInitialGpuMeasurementsComplete() {
        val gate = Pr1451ReloadOrderGate()

        assertThrows(IllegalStateException::class.java) { gate.requireDiagnosticReloadAllowed() }
        gate.markInitialGpuMeasurementsComplete()
        gate.requireDiagnosticReloadAllowed()
        assertThrows(IllegalStateException::class.java) { gate.markInitialGpuMeasurementsComplete() }
    }

    @Test
    fun reloadErrorsAreDiagnosticAndKeepArmStatusSeparate() {
        val failedReload = Pr1451ReloadEvidence.assess(
            backend = null,
            availableSystemMemoryMiB = null,
            processPssMiB = null,
            processRssMiB = null,
            failureMessage = "initialization timed out",
        )
        val gpuReload = Pr1451ReloadEvidence.assess(
            backend = "GPU",
            availableSystemMemoryMiB = 2_300L,
            processPssMiB = 2_000.0,
            processRssMiB = 2_050.0,
        )
        val summary = Pr1451ReloadEvidence.summarize("passed", failedReload, gpuReload)

        assertEquals("reload_error", failedReload.outcome)
        assertNull(failedReload.gpuRetained)
        assertEquals("passed", summary.armStatus)
        assertEquals("failed_reload_error", summary.reloadGpuStabilityStatus)
        assertTrue(failedReload.reportFields("background_reload").containsKey("background_reload_failure"))
    }
}
