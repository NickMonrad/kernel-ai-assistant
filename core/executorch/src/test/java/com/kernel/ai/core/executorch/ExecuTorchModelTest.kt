package com.kernel.ai.core.executorch

import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class ExecuTorchModelTest {
    @TempDir
    lateinit var dir: File

    private lateinit var pte: File
    private val opened = mutableListOf<ExecuTorchModel>()
    private val loads = mutableListOf<FakeNativeModule>()

    @BeforeEach
    fun setUp() {
        pte = File(dir, "model.pte").apply {
            writeBytes(PteHeaderTest.pte(programSize = 64, segmentBase = 0, segmentSize = 0, total = 64))
        }
    }

    // The thread-pool guard is process-wide; leave no model open for the next test.
    @AfterEach
    fun tearDown() = opened.forEach { it.close() }

    private fun load(
        threads: Int = 2,
        native: () -> FakeNativeModule = { FakeNativeModule() },
    ): ExecuTorchModel =
        ExecuTorchModel.load(pte, threads, LoadMode.MMAP) { _, _, _ -> native().also { loads += it } }
            .also { opened += it }

    private val output = ExecuTorchTensor.Float32(floatArrayOf(1f, 2f, 3f), longArrayOf(1, 3))
    private val input = ExecuTorchTensor.Float32(FloatArray(4), longArrayOf(1, 4))
    private val forward = MethodSignature(
        "forward",
        inputs = listOf(TensorSpec("x", TensorDType.FLOAT32, 1, 4)),
        outputs = listOf(TensorSpec("y", TensorDType.FLOAT32, 1, 3)),
    )

    @Test
    fun `load rejects a thread count below one before reaching the runtime`() {
        assertThrows<IllegalArgumentException> { load(threads = 0) }
        assertTrue(loads.isEmpty())
    }

    @Test
    fun `load rejects a missing file before reaching the runtime`() {
        pte.delete()
        assertThrows<IllegalArgumentException> { load() }
        assertTrue(loads.isEmpty())
    }

    @Test
    fun `load rejects a file that is not a program before reaching the runtime`() {
        pte.writeText("<html>404</html>")
        assertThrows<ExecuTorchException> { load() }
        assertTrue(loads.isEmpty())
    }

    @Test
    fun `run returns outputs that match the signature`() {
        val model = load { FakeNativeModule(outputs = listOf(output)) }
        val result = model.run(forward, listOf(input))
        assertSame(output, result.single())
        assertEquals(listOf("forward"), loads.single().executed)
    }

    @Test
    fun `run fails fast when the output shape differs from the signature`() {
        val model = load { FakeNativeModule(outputs = listOf(output)) }
        val wrongOutputs = MethodSignature("forward", forward.inputs, listOf(TensorSpec("y", TensorDType.FLOAT32, 1, 4)))
        assertThrows<OutputShapeMismatchException> { model.run(wrongOutputs, listOf(input)) }
    }

    @Test
    fun `run rejects mismatched inputs without calling the runtime`() {
        val model = load { FakeNativeModule(outputs = listOf(output)) }
        val error = assertThrows<InputShapeMismatchException> {
            model.run(forward, listOf(ExecuTorchTensor.Float32(FloatArray(6), longArrayOf(1, 6))))
        }
        assertTrue(error.message!!.contains("input 0 ('x')"), error.message)
        assertThrows<InputShapeMismatchException> { model.run(forward, emptyList()) }
        assertTrue(loads.single().executed.isEmpty())
    }

    @Test
    fun `run rejects an unknown method without calling the runtime`() {
        val model = load()
        val decode = MethodSignature("decode", forward.inputs, forward.outputs)
        val error = assertThrows<IllegalArgumentException> { model.run(decode, listOf(input)) }
        assertTrue(error.message!!.contains("forward"))
        assertTrue(loads.single().executed.isEmpty())
    }

    @Test
    fun `run after close throws without reaching the freed native module`() {
        val model = load { FakeNativeModule(outputs = listOf(output)) }
        model.close()
        model.close()
        assertThrows<IllegalStateException> { model.run(forward, listOf(input)) }
        assertTrue(model.isClosed)
        assertTrue(loads.single().executed.isEmpty())
        assertEquals(1, loads.single().destroyCount)
    }

    @Test
    fun `close waits for an in-flight run before destroying the native module`() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val running = CountDownLatch(1)
        val release = CountDownLatch(1)
        val model = load {
            FakeNativeModule(outputs = listOf(output), events = events) {
                running.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        val runner = thread { model.run(forward, listOf(input)) }
        assertTrue(running.await(5, TimeUnit.SECONDS))
        val closer = thread { model.close() }
        closer.join(200)
        assertTrue(closer.isAlive, "close returned while run was executing")
        release.countDown()
        runner.join(5_000)
        closer.join(5_000)
        assertEquals(listOf("execute:forward", "destroy"), events.toList())
    }

    @Test
    fun `a different thread count is rejected while another model is open`() {
        load(threads = 4)
        val error = assertThrows<IllegalStateException> { load(threads = 2) }
        assertTrue(error.message!!.contains("4 threads"), error.message)
        assertEquals(1, loads.size)
        load(threads = 4)
        assertEquals(2, loads.size)
    }

    @Test
    fun `a different thread count is allowed once every model is closed`() {
        load(threads = 4).close()
        load(threads = 2)
        assertEquals(2, loads.size)
    }

    @Test
    fun `a failed load does not hold the thread pool`() {
        assertThrows<ExecuTorchException> {
            ExecuTorchModel.load(pte, 4, LoadMode.MMAP) { _, _, _ -> throw ExecuTorchException("bad program") }
        }
        val broken = FakeNativeModule(methodsError = ExecuTorchException("invalid program"))
        assertThrows<ExecuTorchException> { load(threads = 4) { broken } }
        assertEquals(1, broken.destroyCount)
        load(threads = 2)
    }

    private class FakeNativeModule(
        private val methods: Set<String> = setOf("forward"),
        private val outputs: List<ExecuTorchTensor> = emptyList(),
        private val events: MutableList<String> = mutableListOf(),
        private val methodsError: Throwable? = null,
        private val onExecute: () -> Unit = {},
    ) : NativeModule {
        val executed = mutableListOf<String>()
        var destroyCount = 0

        override fun methodNames(): Set<String> = methodsError?.let { throw it } ?: methods

        override fun execute(method: String, inputs: List<ExecuTorchTensor>): List<ExecuTorchTensor> {
            onExecute()
            executed += method
            events += "execute:$method"
            return outputs
        }

        override fun destroy() {
            destroyCount++
            events += "destroy"
        }
    }
}
