package com.kernel.ai.core.inference

import com.google.ai.edge.litertlm.ActivationDataType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * #2137: GPU engine init order. FLOAT32 needs [FLOAT32_EXTRA_AVAIL_MEM_BYTES] on top of the 2 GB
 * floor; a failed FLOAT32 init retries GPU with the default precision before CPU.
 */
class EngineAttemptPlanTest {

    private val gib = 1024L * 1024 * 1024
    private val enoughForFloat32 = MIN_AVAIL_MEM_FOR_GPU_BYTES + FLOAT32_EXTRA_AVAIL_MEM_BYTES + gib
    private val enoughForDefaultOnly = MIN_AVAIL_MEM_FOR_GPU_BYTES + gib / 2
    private val belowGpuFloor = MIN_AVAIL_MEM_FOR_GPU_BYTES - 1

    @Test
    fun `enough memory plans FLOAT32 GPU first, then default GPU, then CPU`() {
        val attempts = planEngineAttempts(BackendType.GPU, enoughForFloat32, ActivationDataType.FLOAT32)

        assertEquals(
            listOf(
                BackendType.GPU to ActivationDataType.FLOAT32,
                BackendType.GPU to null,
                BackendType.CPU to null,
            ),
            attempts.map { it.backend to it.activation },
        )
        assertEquals("float32", attempts.first().reason)
    }

    @Test
    fun `memory short for FLOAT32 but enough for default plans default GPU, never FLOAT32`() {
        val attempts = planEngineAttempts(BackendType.AUTO, enoughForDefaultOnly, ActivationDataType.FLOAT32)

        assertEquals(
            listOf(BackendType.GPU to null, BackendType.CPU to null),
            attempts.map { it.backend to it.activation },
        )
        assertEquals("float32 memory short", attempts.first().reason)
    }

    @Test
    fun `memory below the absolute GPU floor plans CPU only`() {
        val attempts = planEngineAttempts(BackendType.NPU, belowGpuFloor, ActivationDataType.FLOAT32)

        assertEquals(listOf(BackendType.CPU), attempts.map { it.backend })
    }

    @Test
    fun `device without FLOAT32 GPU precision plans default GPU then CPU`() {
        val attempts = planEngineAttempts(BackendType.GPU, enoughForFloat32, gpuPreferred = null)

        assertEquals(
            listOf(BackendType.GPU to null, BackendType.CPU to null),
            attempts.map { it.backend to it.activation },
        )
    }

    @Test
    fun `NPU request tries NPU before the GPU plan`() {
        val attempts = planEngineAttempts(BackendType.NPU, enoughForFloat32, ActivationDataType.FLOAT32)

        assertEquals(
            listOf(BackendType.NPU, BackendType.GPU, BackendType.GPU, BackendType.CPU),
            attempts.map { it.backend },
        )
    }

    @Test
    fun `CPU request plans CPU only`() {
        val attempts = planEngineAttempts(BackendType.CPU, belowGpuFloor, gpuPreferred = null)

        assertEquals(listOf(BackendType.CPU), attempts.map { it.backend })
    }

    @Test
    fun `FLOAT32 init failure retries default GPU and never reaches CPU when GPU succeeds`() = runBlocking {
        val attempts = planEngineAttempts(BackendType.GPU, enoughForFloat32, ActivationDataType.FLOAT32)
        val calls = mutableListOf<Pair<BackendType, ActivationDataType?>>()

        val (attempt, value) = runEngineAttempts(attempts) { candidate ->
            calls += candidate.backend to candidate.activation
            if (candidate.activation == ActivationDataType.FLOAT32) error("FLOAT32 init failed")
            "engine-${candidate.backend}"
        }

        assertEquals(BackendType.GPU, attempt.backend)
        assertEquals(null, attempt.activation)
        assertEquals("engine-GPU", value)
        assertEquals(
            listOf(BackendType.GPU to ActivationDataType.FLOAT32, BackendType.GPU to null),
            calls,
        )
    }

    @Test
    fun `FLOAT32 and default GPU both failing falls back to CPU`() = runBlocking {
        val attempts = planEngineAttempts(BackendType.GPU, enoughForFloat32, ActivationDataType.FLOAT32)

        val (attempt, value) = runEngineAttempts(attempts) { candidate ->
            if (candidate.backend == BackendType.GPU) error("GPU init failed")
            "engine-CPU"
        }

        assertEquals(BackendType.CPU, attempt.backend)
        assertEquals("engine-CPU", value)
    }

    @Test
    fun `every attempt failing rethrows the last failure`() {
        val attempts = planEngineAttempts(BackendType.GPU, enoughForFloat32, ActivationDataType.FLOAT32)
        val cpuFailure = IllegalStateException("cpu failed")

        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                runEngineAttempts(attempts) { candidate ->
                    if (candidate.backend == BackendType.CPU) throw cpuFailure
                    error("gpu failed")
                }
            }
        }

        assertSame(cpuFailure, thrown)
    }
}
