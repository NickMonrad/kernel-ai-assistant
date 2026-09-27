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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

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

}
