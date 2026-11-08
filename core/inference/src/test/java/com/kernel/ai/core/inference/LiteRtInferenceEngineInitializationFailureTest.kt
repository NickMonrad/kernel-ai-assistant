package com.kernel.ai.core.inference

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.google.ai.edge.litertlm.Engine
import com.kernel.ai.core.inference.hardware.HardwareProfile
import com.kernel.ai.core.inference.hardware.HardwareProfileDetector
import com.kernel.ai.core.inference.hardware.HardwareTier
import com.kernel.ai.core.inference.hardware.QuantizationVerifier
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkConstructor
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class LiteRtInferenceEngineInitializationFailureTest {
    private val context = mockk<Context>(relaxed = true)
    private val powerManager = mockk<PowerManager>()
    private val hardwareProfileDetector = mockk<HardwareProfileDetector>()

    @BeforeEach
    fun setUp() {
        every { powerManager.isInteractive } returns true
        every { context.getSystemService(PowerManager::class.java) } returns powerManager
        every { context.getSystemService(Context.ACTIVITY_SERVICE) } returns null
        every { context.cacheDir } returns File("/tmp")
        every { hardwareProfileDetector.profile } returns HardwareProfile(
            tier = HardwareTier.FLAGSHIP,
            totalRamBytes = 12L * 1024 * 1024 * 1024,
            socManufacturer = "Qualcomm",
            socModel = "SM8550",
            recommendedBackend = BackendType.GPU,
            recommendedMaxTokens = 8192,
        )

        mockkObject(InferenceLoadingService.Companion)
        every { InferenceLoadingService.start(any()) } just runs
        every { InferenceLoadingService.stop(any()) } just runs
        mockkObject(QuantizationVerifier)
        every { QuantizationVerifier.verify(any(), any(), any()) } returns true
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        mockkConstructor(Engine::class)
        every { anyConstructed<Engine>().initialize() } throws CancellationException("native init cancelled")
        every { anyConstructed<Engine>().createConversation(any()) } returns mockk(relaxed = true)
        every { anyConstructed<Engine>().close() } just runs
    }

    @AfterEach
    fun tearDown() {
        unmockkConstructor(Engine::class)
        unmockkStatic(Log::class)
        unmockkObject(QuantizationVerifier, InferenceLoadingService.Companion)
    }

    @Test
    fun `native init cancellation closes failed candidates before backend fallback`() = runTest {
        val engine = LiteRtInferenceEngine(context, hardwareProfileDetector)
        var failure: Throwable? = null

        try {
            engine.initialize(
                ModelConfig(
                    modelPath = "/models/gemma-4-E4B-it.litertlm",
                    backendType = BackendType.GPU,
                    speculativeDecodingEnabled = false,
                ),
            )
        } catch (error: Throwable) {
            failure = error
        }

        assertTrue(failure is InferenceException)
        assertTrue(
            failure?.message?.contains("native init cancelled") == true,
            "Expected the backend cancellation detail in the final error, got: $failure",
        )
        verify(exactly = 2) { anyConstructed<Engine>().close() }
    }

    @Test
    fun `parent cancellation propagates and closes active engine candidate`() = runTest {
        val enteredInitialize = CountDownLatch(1)
        val finishInitialize = CountDownLatch(1)
        every { anyConstructed<Engine>().initialize() } answers {
            enteredInitialize.countDown()
            finishInitialize.await()
        }
        val engine = LiteRtInferenceEngine(context, hardwareProfileDetector)
        val initJob = launch {
            engine.initialize(
                ModelConfig(
                    modelPath = "/models/gemma-4-E4B-it.litertlm",
                    backendType = BackendType.GPU,
                    speculativeDecodingEnabled = false,
                ),
            )
        }

        try {
            assertTrue(withContext(Dispatchers.IO) { enteredInitialize.await(5, TimeUnit.SECONDS) })
            initJob.cancel()
        } finally {
            finishInitialize.countDown()
        }
        initJob.join()

        assertTrue(initJob.isCancelled)
        verify(exactly = 1) { anyConstructed<Engine>().close() }
        verify(exactly = 1) { anyConstructed<Engine>().initialize() }
    }

    @Test
    fun `duplicate initialization waits for the active attempt and reuses its ready engine`() = runTest {
        val enteredInitialize = CountDownLatch(1)
        val finishInitialize = CountDownLatch(1)
        val initializeCalls = AtomicInteger()
        every { anyConstructed<Engine>().initialize() } answers {
            initializeCalls.incrementAndGet()
            enteredInitialize.countDown()
            finishInitialize.await()
        }
        val engine = LiteRtInferenceEngine(context, hardwareProfileDetector)
        val config = testConfig()
        val first = async { engine.initialize(config) }
        runCurrent()

        try {
            assertTrue(withContext(Dispatchers.IO) { enteredInitialize.await(5, TimeUnit.SECONDS) })
            val duplicate = async { engine.initialize(config) }
            runCurrent()
            assertFalse(duplicate.isCompleted, "A duplicate caller must wait for the active initialization")

            finishInitialize.countDown()
            first.await()
            duplicate.await()
            assertTrue(engine.isReady.value)
            assertEquals(1, initializeCalls.get())
        } finally {
            finishInitialize.countDown()
        }
    }

    @Test
    fun `cancelling a GPU restart caller still completes initialization and allows later init`() = runTest {
        val enteredRestartInitialize = CountDownLatch(1)
        val finishRestartInitialize = CountDownLatch(1)
        val initializeCalls = AtomicInteger()
        every { anyConstructed<Engine>().initialize() } answers {
            if (initializeCalls.incrementAndGet() == 2) {
                enteredRestartInitialize.countDown()
                finishRestartInitialize.await()
            }
        }
        val engine = LiteRtInferenceEngine(context, hardwareProfileDetector)
        val config = testConfig()
        engine.initialize(config)
        engine.gpuResetCount = 4
        val reset = launch { engine.resetConversation() }
        runCurrent()

        try {
            assertTrue(withContext(Dispatchers.IO) { enteredRestartInitialize.await(5, TimeUnit.SECONDS) })
            reset.cancel()
        } finally {
            finishRestartInitialize.countDown()
        }
        reset.join()

        assertTrue(reset.isCancelled)
        assertTrue(engine.isReady.value, "The non-cancellable restart must reach ready before returning")
        engine.initialize(config)
        assertEquals(2, initializeCalls.get(), "A later init must not be blocked or reload a ready engine")
    }

    @Test
    fun `restart failure is returned as retryable error and a later initialization succeeds`() = runTest {
        val initializeCalls = AtomicInteger()
        every { anyConstructed<Engine>().initialize() } answers {
            if (initializeCalls.incrementAndGet() == 2) {
                throw IllegalStateException("GPU context lost")
            }
        }
        val engine = LiteRtInferenceEngine(context, hardwareProfileDetector)
        val config = testConfig(BackendType.CPU)
        engine.initialize(config)
        setPrivateField(engine, "_activeBackend", kotlinx.coroutines.flow.MutableStateFlow(BackendType.GPU))
        engine.gpuResetCount = 4

        val failure = runCatching { engine.resetConversation() }.exceptionOrNull()

        assertTrue(failure is InferenceException)
        assertTrue(failure?.message?.contains("retry model initialization") == true)
        assertFalse(engine.isReady.value)

        engine.initialize(config)
        assertTrue(engine.isReady.value)
        assertEquals(3, initializeCalls.get())
    }

    private fun testConfig(backend: BackendType = BackendType.GPU) = ModelConfig(
        modelPath = "/models/gemma-4-E4B-it.litertlm",
        backendType = backend,
        speculativeDecodingEnabled = false,
    )

    private fun setPrivateField(target: Any, fieldName: String, value: Any?) {
        target.javaClass.getDeclaredField(fieldName).apply {
            isAccessible = true
            set(target, value)
        }
    }

}
