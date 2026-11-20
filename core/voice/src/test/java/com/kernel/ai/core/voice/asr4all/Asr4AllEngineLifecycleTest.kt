package com.kernel.ai.core.voice.asr4all

import com.kernel.ai.core.voice.asr4all.runtime.EtExecutionException
import com.kernel.ai.core.voice.asr4all.runtime.EtProgram
import com.kernel.ai.core.voice.asr4all.runtime.EtProgramClosedException
import com.kernel.ai.core.voice.asr4all.runtime.EtProgramLoader
import com.kernel.ai.core.voice.asr4all.runtime.EtTensor
import java.nio.file.Files
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Lifecycle and failure typing. The program here is a stand-in that returns tensors shaped
 * like a given export; text correctness is covered by the real-data tests and the host harness.
 */
class Asr4AllEngineLifecycleTest {
    // M's config files plus an empty stand-in for the .pte, which the fake loader never reads.
    private val modelDir = Files.createTempDirectory("asr4all-engine").toFile().also { dir ->
        Asr4AllTestData.modelDir("m").copyRecursively(dir)
        dir.resolve("${Asr4AllModelConfig.EXPORT_DIR}/asr_encoder.pte").writeBytes(ByteArray(0))
    }

    @AfterEach
    fun cleanup() {
        modelDir.deleteRecursively()
    }

    @Test
    fun `calls after close fail with a typed error and never reach the program`() {
        val program = FakeProgram()
        val engine = open(program)
        engine.acceptAudio(FloatArray(30_000))
        engine.close()
        engine.close() // idempotent
        val callsAtClose = program.executeCalls

        assertThrows<Asr4AllClosedException> { engine.acceptAudio(FloatArray(10)) }
        assertThrows<Asr4AllClosedException> { engine.acceptPcm16(ShortArray(10)) }
        assertThrows<Asr4AllClosedException> { engine.finish() }
        assertThrows<Asr4AllClosedException> { engine.reset() }
        assertThrows<Asr4AllClosedException> { engine.partialText }
        assertEquals(1, program.closeCalls)
        assertEquals(callsAtClose, program.executeCalls)
    }

    @Test
    fun `program built for another size fails at open with a shape error`() {
        // An S program (hidden width 384) opened with M's config files.
        val program = FakeProgram(hiddenDim = 384)

        val e = assertThrows<Asr4AllShapeException> { open(program) }

        assertEquals("stream_c16r4", e.method)
        assertEquals(3, e.outputIndex)
        assertTrue(e.actual.contains("384"), e.actual)
        assertEquals(1, program.closeCalls)
    }

    @Test
    fun `program rejecting config-derived inputs is a config error`() {
        val program = FakeProgram(rejectInputs = true)

        assertThrows<Asr4AllConfigException> { open(program) }
        assertEquals(1, program.closeCalls)
    }

    @Test
    fun `missing tier method is a config error`() {
        val program = FakeProgram(methods = setOf("stream_c16r4", "pcec_c16"))

        val e = assertThrows<Asr4AllConfigException> { open(program) }
        assertTrue(e.message!!.contains("pcec_c16_flush"), e.message)
    }

    @Test
    fun `shape change mid-stream raises a typed error and the engine recovers after it`() {
        val program = FakeProgram()
        val engine = open(program)
        program.hiddenDim = 640

        assertThrows<Asr4AllShapeException> { engine.acceptAudio(FloatArray(30_000)) }
        assertEquals("", engine.partialText)

        program.hiddenDim = 512
        engine.acceptAudio(FloatArray(30_000))
        engine.finish()
        engine.close()
    }

    @Test
    fun `repeated open, reset and close cycles release every program`() {
        val programs = (1..20).map { FakeProgram() }
        for (program in programs) {
            open(program).use { engine ->
                repeat(3) {
                    engine.acceptAudio(FloatArray(25_000))
                    engine.reset()
                }
                engine.acceptAudio(FloatArray(12_345))
                engine.finish()
            }
        }
        assertTrue(programs.all { it.closeCalls == 1 && !it.usedAfterClose })
    }

    private fun open(program: FakeProgram): Asr4AllEngine =
        Asr4AllEngine.open(modelDir, Asr4AllSize.M, EtProgramLoader { _, _ -> program })

    /** Returns zero tensors shaped like the M export unless told otherwise. */
    private class FakeProgram(
        var hiddenDim: Int = 512,
        private val rejectInputs: Boolean = false,
        private val methods: Set<String> = setOf("stream_c16r4", "pcec_c16", "pcec_c16_flush"),
    ) : EtProgram {
        var executeCalls = 0
        var closeCalls = 0
        var usedAfterClose = false

        override val methodNames: Set<String> get() = methods

        override fun execute(method: String, inputs: List<EtTensor>): List<EtTensor> {
            if (closeCalls > 0) {
                usedAfterClose = true
                throw EtProgramClosedException("closed")
            }
            executeCalls++
            if (rejectInputs) throw EtExecutionException("input 0 shape mismatch")
            return when (method) {
                "stream_c16r4" -> listOf(
                    EtTensor.zeros(1, 16, 257),
                    inputs[1],
                    inputs[2],
                    EtTensor.zeros(1, 16, hiddenDim.toLong()),
                )
                "pcec_c16" -> listOf(
                    EtTensor.of(LongArray(32) { 6149 }, 1, 32),
                    inputs[4],
                    EtTensor.of(longArrayOf(inputs[5].longs()[0] + 32), 1),
                )
                "pcec_c16_flush" -> listOf(EtTensor.of(LongArray(65) { 6149 }, 1, 65))
                else -> error(method)
            }
        }

        override fun close() {
            closeCalls++
        }
    }
}
