package com.kernel.ai

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.awaitCancellation

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
            preInitAvailableSystemMemoryMiB = 2_300L,
            engineReady = true,
            processPssMiB = 2_036.0,
            processRssMiB = 2_100.0,
        )
        val fields = observation.reportFields("warm_reload")

        assertEquals("gpu_retained", observation.outcome)
        assertEquals(true, observation.gpuRetained)
        assertEquals(2_048L, observation.gpuMinimumAvailableMemoryMiB)
        assertEquals(252L, observation.gpuMemoryHeadroomMiB)
        assertEquals(2_300L, fields["warm_reload_pre_init_available_system_memory_mib"])
        assertEquals(true, fields["warm_reload_engine_ready"])
    }

    @Test
    fun reloadCaptureSamplesBeforeInitializeAndReadsReadyStateAfterReturn() = runBlocking {
        val events = mutableListOf<String>()
        val capture = capturePr1451Reload(
            sampleAvailableSystemMemoryMiB = {
                events += "sample"
                1_925L
            },
            initialize = { events += "initialize" },
            capturePostInitState = {
                events += "ready_state"
                Pr1451ReloadPostInitState(engineReady = true, activeBackend = "CPU")
            },
        )
        val postInitState = requireNotNull(capture.postInitState)
        val observation = Pr1451ReloadEvidence.assess(
            backend = postInitState.activeBackend,
            preInitAvailableSystemMemoryMiB = capture.preInitAvailableSystemMemoryMiB,
            engineReady = postInitState.engineReady,
            processPssMiB = null,
            processRssMiB = null,
        )

        assertEquals(listOf("sample", "initialize", "ready_state"), events)
        assertEquals(1_925L, observation.preInitAvailableSystemMemoryMiB)
        assertEquals(-123L, observation.gpuMemoryHeadroomMiB)
        assertEquals(true, observation.reportFields("warm_reload")["warm_reload_engine_ready"])
        assertEquals("cpu_fallback", observation.outcome)
    }

    @Test
    fun cpuReloadReportsNegativeHeadroomAndDoesNotFailCompletedArm() {
        val cpuReload = Pr1451ReloadEvidence.assess(
            backend = "CPU",
            preInitAvailableSystemMemoryMiB = 1_925L,
            engineReady = true,
            processPssMiB = 2_036.0,
            processRssMiB = 2_100.0,
        )
        val gpuReload = Pr1451ReloadEvidence.assess(
            backend = "GPU",
            preInitAvailableSystemMemoryMiB = 2_300L,
            engineReady = true,
            processPssMiB = 2_000.0,
            processRssMiB = 2_050.0,
        )
        val summary = Pr1451ReloadEvidence.summarize("passed", cpuReload, gpuReload)
        val fields = cpuReload.reportFields("warm_reload")

        assertEquals("cpu_fallback", cpuReload.outcome)
        assertEquals(false, cpuReload.gpuRetained)
        assertEquals(-123L, cpuReload.gpuMemoryHeadroomMiB)
        assertEquals("CPU", fields["warm_reload_backend"])
        assertEquals(1_925L, fields["warm_reload_pre_init_available_system_memory_mib"])
        assertEquals(-123L, fields["warm_reload_gpu_memory_headroom_mib"])
        assertEquals(true, fields["warm_reload_engine_ready"])
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
            preInitAvailableSystemMemoryMiB = null,
            engineReady = true,
            processPssMiB = 2_036.0,
            processRssMiB = 2_100.0,
        )
        val gpuReload = Pr1451ReloadEvidence.assess(
            backend = "GPU",
            preInitAvailableSystemMemoryMiB = 2_300L,
            engineReady = true,
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
    fun reloadWithoutReadyEngineFailsForGpuAndCpuBackends() {
        val observations = listOf("GPU", "CPU").map { backend ->
            Pr1451ReloadEvidence.assess(
                backend = backend,
                preInitAvailableSystemMemoryMiB = 2_300L,
                engineReady = false,
                processPssMiB = null,
                processRssMiB = null,
            )
        }
        val summary = Pr1451ReloadEvidence.summarize("passed", observations[0], observations[1])

        observations.forEach { observation ->
            assertEquals("reload_not_ready", observation.outcome)
            assertEquals(false, observation.gpuRetained)
        }
        assertEquals("failed", summary.armStatus)
        assertEquals("failed_reload_not_ready", summary.reloadGpuStabilityStatus)
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
    fun initializationTimeoutRetainsMemorySampleAndFailsArm() = runBlocking {
        val events = mutableListOf<String>()
        val capture = capturePr1451Reload(
            sampleAvailableSystemMemoryMiB = {
                events += "sample"
                1_925L
            },
            initialize = {
                events += "initialize"
                initializePr1451ReloadWithTimeout(
                    timeoutMs = 10L,
                    phase = "warm_reload",
                ) {
                    awaitCancellation()
                }
            },
            capturePostInitState = {
                events += "ready_state"
                Pr1451ReloadPostInitState(engineReady = true, activeBackend = "GPU")
            },
        )
        val failedReload = Pr1451ReloadEvidence.assess(
            backend = capture.postInitState?.activeBackend,
            preInitAvailableSystemMemoryMiB = capture.preInitAvailableSystemMemoryMiB,
            engineReady = capture.postInitState?.engineReady,
            processPssMiB = null,
            processRssMiB = null,
            failureMessage = capture.failureMessage,
        )
        val successfulReload = Pr1451ReloadEvidence.assess(
            backend = "GPU",
            preInitAvailableSystemMemoryMiB = 2_300L,
            engineReady = true,
            processPssMiB = 2_000.0,
            processRssMiB = 2_050.0,
        )
        val summary = Pr1451ReloadEvidence.summarize("passed", failedReload, successfulReload)

        assertEquals(listOf("sample", "initialize"), events)
        assertEquals(1_925L, capture.preInitAvailableSystemMemoryMiB)
        assertNull(capture.postInitState)
        assertTrue(capture.failureMessage.orEmpty().contains("Timed out initializing warm_reload"))
        assertEquals(-123L, failedReload.gpuMemoryHeadroomMiB)
        assertEquals(null, failedReload.engineReady)
        assertEquals("reload_error", failedReload.outcome)
        assertEquals("failed", summary.armStatus)
        assertEquals("failed_reload_error", summary.reloadGpuStabilityStatus)
    }

    @Test
    fun initializationErrorsRetainMemorySampleAndFailArm() = runBlocking {
        val events = mutableListOf<String>()
        val capture = capturePr1451Reload(
            sampleAvailableSystemMemoryMiB = {
                events += "sample"
                2_300L
            },
            initialize = {
                events += "initialize"
                initializePr1451ReloadWithTimeout(
                    timeoutMs = 1_000L,
                    phase = "warm_reload",
                ) {
                    throw IllegalStateException("native initialization failed")
                }
            },
            capturePostInitState = {
                events += "ready_state"
                Pr1451ReloadPostInitState(engineReady = true, activeBackend = "GPU")
            },
        )
        val failedReload = Pr1451ReloadEvidence.assess(
            backend = capture.postInitState?.activeBackend,
            preInitAvailableSystemMemoryMiB = capture.preInitAvailableSystemMemoryMiB,
            engineReady = capture.postInitState?.engineReady,
            processPssMiB = null,
            processRssMiB = null,
            failureMessage = capture.failureMessage,
        )
        val successfulReload = Pr1451ReloadEvidence.assess(
            backend = "GPU",
            preInitAvailableSystemMemoryMiB = 2_300L,
            engineReady = true,
            processPssMiB = 2_000.0,
            processRssMiB = 2_050.0,
        )
        val summary = Pr1451ReloadEvidence.summarize("passed", failedReload, successfulReload)

        assertEquals(listOf("sample", "initialize"), events)
        assertEquals(2_300L, failedReload.preInitAvailableSystemMemoryMiB)
        assertNull(failedReload.engineReady)
        assertEquals("reload_error", failedReload.outcome)
        assertTrue(failedReload.failureMessage.orEmpty().contains("native initialization failed"))
        assertEquals("failed", summary.armStatus)
        assertEquals("failed_reload_error", summary.reloadGpuStabilityStatus)
    }
}
