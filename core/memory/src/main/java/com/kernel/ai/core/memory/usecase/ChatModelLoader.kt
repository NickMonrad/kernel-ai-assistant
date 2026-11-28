package com.kernel.ai.core.memory.usecase

import com.google.ai.edge.litertlm.ToolProvider
import com.kernel.ai.core.inference.Gemma4InitLock
import com.kernel.ai.core.inference.InferenceEngine
import com.kernel.ai.core.inference.ModelConfig
import com.kernel.ai.core.inference.download.ModelDownloadManager
import com.kernel.ai.core.memory.repository.ModelSettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock

sealed interface ChatModelLoadResult {
    data object Loaded : ChatModelLoadResult
    data object NoModelDownloaded : ChatModelLoadResult
    data class Failed(val cause: Exception) : ChatModelLoadResult
}

/**
 * Loads the conversation model for the Kokoro speed check (#2170), under the same lock as chat start-up and with the
 * config NoteSmartTitleUseCase uses. A ready engine is left alone; chat hydrates its own prompt when it next opens.
 */
@Singleton
class ChatModelLoader @Inject constructor(
    private val inferenceEngine: InferenceEngine,
    private val modelDownloadManager: ModelDownloadManager,
    private val modelSettingsRepository: ModelSettingsRepository,
    private val toolProvider: ToolProvider,
) {
    suspend fun ensureLoaded(): ChatModelLoadResult = Gemma4InitLock.mutex.withLock {
        if (inferenceEngine.isReady.value) return@withLock ChatModelLoadResult.Loaded
        val model = modelDownloadManager.preferredConversationModel()
        val modelPath = modelDownloadManager.getModelPath(model)
            ?: return@withLock ChatModelLoadResult.NoModelDownloaded
        val settings = modelSettingsRepository.getSettings(model.modelId)
        try {
            inferenceEngine.initialize(
                ModelConfig(
                    modelPath = modelPath,
                    maxTokens = settings.contextWindowSize,
                    temperature = settings.temperature,
                    topP = settings.topP,
                    topK = settings.topK,
                    thinkingEnabled = settings.showThinkingProcess,
                    speculativeDecodingEnabled = settings.speculativeDecodingEnabled,
                    toolProvider = toolProvider,
                ),
            )
            ChatModelLoadResult.Loaded
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ChatModelLoadResult.Failed(e)
        }
    }
}
