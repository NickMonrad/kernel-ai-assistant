package com.kernel.ai.core.voice.asr4all

import com.kernel.ai.core.executorch.ExecuTorchException
import com.kernel.ai.core.executorch.ExecuTorchTensor
import com.kernel.ai.core.executorch.MethodSignature
import com.kernel.ai.core.executorch.TensorSpec
import java.nio.file.Files
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Lifecycle and failure typing. The backend here is a stand-in that returns tensors shaped
 * like a given export; text correctness is covered by the real-data tests and the host harness.
 */
class Asr4AllEngineLifecycleTest {
    // M's config files plus an empty stand-in for the .pte, which these tests never read.
    private val modelDir = Files.createTempDirectory("asr4all-engine").toFile().also { dir ->
        Asr4AllTestData.modelDir("m").copyRecursively(dir)
        dir.resolve("${Asr4AllModelConfig.EXPORT_DIR}/asr_encoder.pte").writeBytes(ByteArray(0))
    }
    private val config = Asr4AllTestData.configM()

    @AfterEach
    fun cleanup() {
        modelDir.deleteRecursively()
    }

    @Test
    fun `calls after close fail with a typed error and never reach the program`() {
        val backend = FakeBackend()
        val engine = open(backend)
        engine.acceptAudio(FloatArray(30_000))
        engine.close()
        engine.close() // idempotent
        val callsAtClose = backend.runCalls

        assertThrows<Asr4AllClosedException> { engine.acceptAudio(FloatArray(10)) }
        assertThrows<Asr4AllClosedException> { engine.acceptPcm16(ShortArray(10)) }
        assertThrows<Asr4AllClosedException> { engine.finish() }
        assertThrows<Asr4AllClosedException> { engine.reset() }
        assertThrows<Asr4AllClosedException> { engine.partialText }
        assertEquals(1, backend.closeCalls)
        assertEquals(callsAtClose, backend.runCalls)
    }

    @Test
    fun `program file from another size is a config error before the runtime loads it`() {
        // The .pte declares S's hidden width (384) next to M's config files.
        var loads = 0
        val e = assertThrows<Asr4AllConfigException> {
            Asr4AllEngine.open(modelDir, Asr4AllSize.M, null, 4, { _, _ -> loads++; FakeBackend() }) { declared(hiddenDim = 384) }
        }

        assertTrue(e.message!!.contains("stream_c16r4") && e.message!!.contains("384"), e.message)
        assertEquals(0, loads)
    }

    @Test
    fun `outputs of another layout fail at open with a shape error`() {
        val backend = FakeBackend(hiddenDim = 384)

        val e = assertThrows<Asr4AllShapeException> { open(backend) }

        assertEquals("stream_c16r4", e.method)
        assertTrue(e.message!!.contains("384"), e.message)
        assertEquals(1, backend.closeCalls)
    }

    @Test
    fun `runtime failure at open is a runtime error and releases the program`() {
        val backend = FakeBackend(failRuns = true)

        assertThrows<Asr4AllRuntimeException> { open(backend) }
        assertEquals(1, backend.closeCalls)
    }

    @Test
    fun `missing tier method is a config error`() {
        val backend = FakeBackend(methods = setOf("stream_c16r4", "pcec_c16"))

        val e = assertThrows<Asr4AllConfigException> { open(backend) }
        assertTrue(e.message!!.contains("pcec_c16_flush"), e.message)
    }

    @Test
    fun `shape change mid-stream raises a typed error and the engine recovers after it`() {
        val backend = FakeBackend()
        val engine = open(backend)
        backend.hiddenDim = 640

        assertThrows<Asr4AllShapeException> { engine.acceptAudio(FloatArray(30_000)) }
        assertEquals("", engine.partialText)

        backend.hiddenDim = 512
        engine.acceptAudio(FloatArray(30_000))
        engine.finish()
        engine.close()
    }

    @Test
    fun `repeated open, reset and close cycles release every program`() {
        val backends = (1..20).map { FakeBackend() }
        for (backend in backends) {
            open(backend).use { engine ->
                repeat(3) {
                    engine.acceptAudio(FloatArray(25_000))
                    engine.reset()
                }
                engine.acceptAudio(FloatArray(12_345))
                engine.finish()
            }
        }
        assertTrue(backends.all { it.closeCalls == 1 && !it.usedAfterClose })
    }

    private fun open(backend: FakeBackend): Asr4AllEngine =
        Asr4AllEngine.open(modelDir, Asr4AllSize.M, null, 4, { _, _ -> backend }) { declared() }

    /** What a `.pte` matching M's config at `stream_c16r4` declares, with [hiddenDim] substituted. */
    private fun declared(hiddenDim: Long = 512): Map<String, PteMethodInfo> {
        fun info(specs: List<TensorSpec>) = specs.map { s ->
            PteTensorInfo(s.dtype, s.dims.map { if (it == 512L) hiddenDim else it }, dynamic = false)
        }
        return Asr4AllSignatures(config, config.tier(null)).all.associate { it.method to PteMethodInfo(info(it.inputs), info(it.outputs)) }
    }

    /** Returns zero tensors shaped like the M export unless told otherwise. */
    private class FakeBackend(
        var hiddenDim: Int = 512,
        private val failRuns: Boolean = false,
        private val methods: Set<String> = setOf("stream_c16r4", "pcec_c16", "pcec_c16_flush"),
    ) : Asr4AllBackend {
        var runCalls = 0
        var closeCalls = 0
        var usedAfterClose = false

        override val methodNames: Set<String> get() = methods

        override fun run(signature: MethodSignature, inputs: List<ExecuTorchTensor>): List<ExecuTorchTensor> {
            if (closeCalls > 0) {
                usedAfterClose = true
                throw IllegalStateException("closed")
            }
            runCalls++
            if (failRuns) throw ExecuTorchException("failed", errorCode = 18)
            return when (signature.method) {
                "stream_c16r4" -> listOf(
                    zeros(1, 16, 257),
                    inputs[1],
                    inputs[2],
                    zeros(1, 16, hiddenDim.toLong()),
                )
                "pcec_c16" -> listOf(
                    ExecuTorchTensor.Int64(LongArray(32) { 6149 }, longArrayOf(1, 32)),
                    inputs[4],
                    ExecuTorchTensor.Int64(longArrayOf((inputs[5] as ExecuTorchTensor.Int64).data[0] + 32), longArrayOf(1)),
                )
                "pcec_c16_flush" -> listOf(ExecuTorchTensor.Int64(LongArray(65) { 6149 }, longArrayOf(1, 65)))
                else -> error(signature.method)
            }
        }

        override fun close() {
            closeCalls++
        }

        private fun zeros(vararg shape: Long) =
            ExecuTorchTensor.Float32(FloatArray(shape.fold(1L) { a, d -> a * d }.toInt()), shape)
    }
}
