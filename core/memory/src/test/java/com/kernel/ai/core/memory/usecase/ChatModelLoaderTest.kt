package com.kernel.ai.core.memory.usecase

import com.google.ai.edge.litertlm.ToolProvider
import com.kernel.ai.core.inference.InferenceEngine
import com.kernel.ai.core.inference.ModelConfig
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.download.ModelDownloadManager
import com.kernel.ai.core.memory.entity.ModelSettingsEntity
import com.kernel.ai.core.memory.repository.ModelSettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ChatModelLoaderTest {
    private val inferenceEngine = mockk<InferenceEngine>()
    private val modelDownloadManager = mockk<ModelDownloadManager>()
    private val modelSettingsRepository = mockk<ModelSettingsRepository>()
    private val toolProvider = mockk<ToolProvider>()
    private val settings = ModelSettingsEntity(
        modelId = MODEL.modelId,
        contextWindowSize = 3072,
        temperature = 0.7f,
        topP = 0.8f,
        topK = 32,
        showThinkingProcess = false,
        speculativeDecodingEnabled = false,
    )
    private val loader = ChatModelLoader(inferenceEngine, modelDownloadManager, modelSettingsRepository, toolProvider)

    @Test
    fun `a ready engine is reused without initializing again`() = runBlocking {
        every { inferenceEngine.isReady } returns MutableStateFlow(true)

        assertEquals(ChatModelLoadResult.Loaded, loader.ensureLoaded())

        coVerify(exactly = 0) { inferenceEngine.initialize(any()) }
    }

    @Test
    fun `no downloaded chat model reports NoModelDownloaded and initializes nothing`() = runBlocking {
        every { inferenceEngine.isReady } returns MutableStateFlow(false)
        coEvery { modelDownloadManager.preferredConversationModel() } returns MODEL
        every { modelDownloadManager.getModelPath(MODEL) } returns null

        assertEquals(ChatModelLoadResult.NoModelDownloaded, loader.ensureLoaded())

        coVerify(exactly = 0) { inferenceEngine.initialize(any()) }
    }

    @Test
    fun `a downloaded chat model is initialized with its saved settings`() = runBlocking {
        every { inferenceEngine.isReady } returns MutableStateFlow(false)
        coEvery { modelDownloadManager.preferredConversationModel() } returns MODEL
        every { modelDownloadManager.getModelPath(MODEL) } returns MODEL_PATH
        coEvery { modelSettingsRepository.getSettings(MODEL.modelId) } returns settings
        coEvery { inferenceEngine.initialize(any()) } returns Unit

        assertEquals(ChatModelLoadResult.Loaded, loader.ensureLoaded())

        coVerify(exactly = 1) {
            inferenceEngine.initialize(match<ModelConfig> {
                it.modelPath == MODEL_PATH &&
                    it.maxTokens == settings.contextWindowSize &&
                    it.toolProvider === toolProvider
            })
        }
    }

    @Test
    fun `an initialization failure is reported, not thrown`() = runBlocking {
        val cause = IllegalStateException("out of memory")
        every { inferenceEngine.isReady } returns MutableStateFlow(false)
        coEvery { modelDownloadManager.preferredConversationModel() } returns MODEL
        every { modelDownloadManager.getModelPath(MODEL) } returns MODEL_PATH
        coEvery { modelSettingsRepository.getSettings(MODEL.modelId) } returns settings
        coEvery { inferenceEngine.initialize(any()) } throws cause

        val result = loader.ensureLoaded()

        assertSame(cause, (result as ChatModelLoadResult.Failed).cause)
    }

    @Test
    fun `cancellation during initialization is rethrown`() {
        every { inferenceEngine.isReady } returns MutableStateFlow(false)
        coEvery { modelDownloadManager.preferredConversationModel() } returns MODEL
        every { modelDownloadManager.getModelPath(MODEL) } returns MODEL_PATH
        coEvery { modelSettingsRepository.getSettings(MODEL.modelId) } returns settings
        coEvery { inferenceEngine.initialize(any()) } throws CancellationException("left settings")

        assertThrows(CancellationException::class.java) {
            runBlocking { loader.ensureLoaded() }
        }
    }

    private companion object {
        val MODEL = KernelModel.GEMMA_4_E2B
        const val MODEL_PATH = "/models/gemma-4-e2b.litertlm"
    }
}
