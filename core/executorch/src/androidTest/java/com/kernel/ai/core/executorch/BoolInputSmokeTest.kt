package com.kernel.ai.core.executorch

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the BOOL-input program from tools/generate_bool_smoke_pte.py on the real runtime.
 *
 * `masked(x, mask) = where(mask, x, 0)`. A mixed mask only gives `[1, 0, 3, 0]` if the runtime reads
 * each BOOL element from the input buffer, not a uniform value.
 */
@RunWith(AndroidJUnit4::class)
class BoolInputSmokeTest {
    private lateinit var pte: File
    private lateinit var model: ExecuTorchModel

    private val masked = MethodSignature(
        "masked",
        inputs = listOf(
            TensorSpec("x", TensorDType.FLOAT32, TensorDim.exact(1), TensorDim.exact(4)),
            TensorSpec("mask", TensorDType.BOOL, TensorDim.exact(1), TensorDim.exact(4)),
        ),
        outputs = listOf(TensorSpec("y", TensorDType.FLOAT32, TensorDim.exact(1), TensorDim.exact(4))),
    )

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        pte = File(instrumentation.targetContext.cacheDir, "bool_smoke.pte")
        instrumentation.context.assets.open("bool_smoke.pte").use { input ->
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
    fun mixedBoolMaskSelectsPerElement() {
        val x = ExecuTorchTensor.Float32(floatArrayOf(1f, 2f, 3f, 4f), longArrayOf(1, 4))
        val mask = ExecuTorchTensor.Bool.of(longArrayOf(1, 4), booleanArrayOf(true, false, true, false))
        val y = model.run(masked, listOf(x, mask)).single() as ExecuTorchTensor.Float32
        assertArrayEquals(floatArrayOf(1f, 0f, 3f, 0f), y.data, 0f)
    }

    @Test
    fun allTrueMaskReturnsInput() {
        val x = ExecuTorchTensor.Float32(floatArrayOf(1f, 2f, 3f, 4f), longArrayOf(1, 4))
        val y = model.run(masked, listOf(x, ExecuTorchTensor.Bool.allTrue(longArrayOf(1, 4))))
            .single() as ExecuTorchTensor.Float32
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f), y.data, 0f)
    }

    @Test
    fun nonBoolMaskIsRejectedBeforeTheRuntime() {
        val x = ExecuTorchTensor.Float32(floatArrayOf(1f, 2f, 3f, 4f), longArrayOf(1, 4))
        val wrongDtype = ExecuTorchTensor.UInt8(byteArrayOf(1, 0, 1, 0), longArrayOf(1, 4))
        assertThrows(InputShapeMismatchException::class.java) {
            model.run(masked, listOf(x, wrongDtype))
        }
    }
}
