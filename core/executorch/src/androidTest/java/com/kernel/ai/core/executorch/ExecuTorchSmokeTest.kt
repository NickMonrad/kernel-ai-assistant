package com.kernel.ai.core.executorch

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Loads the synthetic program from core/executorch/tools/generate_smoke_pte.py with the real
 * runtime: `forward` (XNNPACK-delegated Linear) and `triple` (portable int64 kernel).
 *
 * Every failure case here must be rejected before native code: the 1.5.1 runtime aborts the
 * process on a native error, which would kill the instrumentation run.
 */
@RunWith(AndroidJUnit4::class)
class ExecuTorchSmokeTest {
    private lateinit var pte: File
    private lateinit var model: ExecuTorchModel

    private val forward = MethodSignature(
        "forward",
        inputs = listOf(TensorSpec("x", TensorDType.FLOAT32, 1, 4)),
        outputs = listOf(TensorSpec("y", TensorDType.FLOAT32, 1, 3)),
    )
    private val triple = MethodSignature(
        "triple",
        inputs = listOf(TensorSpec("x", TensorDType.INT64, 2)),
        outputs = listOf(TensorSpec("y", TensorDType.INT64, TensorSpec.ANY)),
    )

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        pte = File(instrumentation.targetContext.cacheDir, "executorch_smoke.pte")
        instrumentation.context.assets.open("executorch_smoke.pte").use { input ->
            pte.outputStream().use { input.copyTo(it) }
        }
        model = ExecuTorchModel.load(pte, threads = 2)
    }

    @After
    fun tearDown() {
        model.close()
        pte.delete()
    }

    @Test
    fun runsBothNamedMethodsWithTypedTensors() {
        assertEquals(setOf("forward", "triple"), model.methodNames)

        val linear = model.run(
            forward,
            listOf(ExecuTorchTensor.Float32(floatArrayOf(1f, 2f, 3f, 4f), longArrayOf(1, 4))),
        ).single() as ExecuTorchTensor.Float32
        assertArrayEquals(floatArrayOf(10.5f, 19.5f, 31f), linear.data, 1e-5f)

        val tripled = model.run(
            triple,
            listOf(ExecuTorchTensor.Int64(longArrayOf(2, -5), longArrayOf(2))),
        ).single() as ExecuTorchTensor.Int64
        assertArrayEquals(longArrayOf(6, -15), tripled.data)
    }

    @Test
    fun wrongExpectedOutputShapeFailsFast() {
        val wrongOutputs = MethodSignature("forward", forward.inputs, listOf(TensorSpec("y", TensorDType.FLOAT32, 1, 4)))
        assertThrows(OutputShapeMismatchException::class.java) {
            model.run(wrongOutputs, listOf(ExecuTorchTensor.Float32(FloatArray(4), longArrayOf(1, 4))))
        }
    }

    @Test
    fun wrongInputShapeIsRejectedBeforeTheRuntime() {
        assertThrows(InputShapeMismatchException::class.java) {
            model.run(forward, listOf(ExecuTorchTensor.Float32(FloatArray(6), longArrayOf(1, 6))))
        }
        // The model is still usable afterwards.
        model.run(forward, listOf(ExecuTorchTensor.Float32(FloatArray(4), longArrayOf(1, 4))))
    }

    @Test
    fun runAfterCloseIsRejected() {
        model.close()
        assertThrows(IllegalStateException::class.java) {
            model.run(triple, listOf(ExecuTorchTensor.Int64(longArrayOf(1, 1), longArrayOf(2))))
        }
    }

    @Test
    fun invalidAndTruncatedProgramsAreRejectedBeforeTheRuntime() {
        val garbage = File(pte.parentFile, "not_a_program.pte").apply { writeBytes(ByteArray(64) { 7 }) }
        val truncated = File(pte.parentFile, "truncated.pte").apply { writeBytes(pte.readBytes().copyOf(2500)) }
        try {
            assertThrows(ExecuTorchException::class.java) { ExecuTorchModel.load(garbage, threads = 2) }
            assertThrows(ExecuTorchException::class.java) { ExecuTorchModel.load(truncated, threads = 2) }
        } finally {
            garbage.delete()
            truncated.delete()
        }
    }

    @Test
    fun aSecondModelSharesThePoolOnlyAtTheSameThreadCount() {
        assertThrows(IllegalStateException::class.java) { ExecuTorchModel.load(pte, threads = 3) }
        ExecuTorchModel.load(pte, threads = 2).use { second ->
            val out = second.run(triple, listOf(ExecuTorchTensor.Int64(longArrayOf(1, 2), longArrayOf(2))))
            assertArrayEquals(longArrayOf(3, 6), (out.single() as ExecuTorchTensor.Int64).data)
        }
        // The first model's XNNPACK delegate still works after the second model closed.
        model.run(forward, listOf(ExecuTorchTensor.Float32(FloatArray(4), longArrayOf(1, 4))))
    }
}
