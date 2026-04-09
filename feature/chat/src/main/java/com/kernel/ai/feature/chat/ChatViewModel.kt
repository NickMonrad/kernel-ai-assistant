package com.kernel.ai.feature.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.inference.DEFAULT_SYSTEM_PROMPT
import com.kernel.ai.core.inference.GenerationResult
import com.kernel.ai.core.inference.InferenceEngine
import com.kernel.ai.core.inference.ModelConfig
import com.kernel.ai.core.inference.download.DownloadState
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.download.ModelDownloadManager
import com.kernel.ai.core.memory.rag.RagRepository
import com.kernel.ai.core.memory.repository.ConversationRepository
import com.kernel.ai.core.memory.repository.UserProfileRepository
import com.kernel.ai.feature.chat.model.ChatMessage
import com.kernel.ai.feature.chat.model.ChatUiState
import com.kernel.ai.feature.chat.model.ChatUiState.ModelDownloadProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val inferenceEngine: InferenceEngine,
    private val downloadManager: ModelDownloadManager,
    private val conversationRepository: ConversationRepository,
    private val ragRepository: RagRepository,
    private val userProfileRepository: UserProfileRepository,
) : ViewModel() {

    /** Passed via nav arg; null means "start a new conversation". */
    private val navConversationId: String? = savedStateHandle["conversationId"]

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val _inputText = MutableStateFlow("")
    private val _error = MutableStateFlow<String?>(null)
    private var conversationId: String? = null

    private data class EngineState(val isReady: Boolean, val isGenerating: Boolean)
    private data class InputState(
        val messages: List<ChatMessage>,
        val inputText: String,
        val error: String?,
    )

    private val engineState = combine(
        inferenceEngine.isReady,
        inferenceEngine.isGenerating,
    ) { isReady, isGenerating -> EngineState(isReady, isGenerating) }

    private val inputState = combine(
        _messages,
        _inputText,
        _error,
    ) { messages, inputText, error -> InputState(messages, inputText, error) }

    val uiState: StateFlow<ChatUiState> = combine(
        engineState,
        downloadManager.downloadStates,
        inputState,
    ) { engine, downloadStates, input ->
        val allRequired = KernelModel.entries.filter { it.isRequired }
        val allDownloaded = allRequired.all { downloadStates[it] is DownloadState.Downloaded }

        when {
            !allDownloaded -> {
                val anyDownloading = allRequired.any { downloadStates[it] is DownloadState.Downloading }
                val progress = allRequired.map { model ->
                    ModelDownloadProgress(
                        model = model,
                        displayName = model.displayName,
                        sizeLabel = formatBytes(model.approxSizeBytes),
                        state = downloadStates[model] ?: DownloadState.NotDownloaded,
                    )
                }
                ChatUiState.ModelsNotReady(isDownloading = anyDownloading, modelProgress = progress)
            }
            !engine.isReady -> ChatUiState.Loading
            else -> ChatUiState.Ready(
                conversationId = conversationId ?: "",
                messages = input.messages,
                isGenerating = engine.isGenerating,
                inputText = input.inputText,
                error = input.error,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ChatUiState.Loading,
    )

    init {
        viewModelScope.launch {
            initializeConversation()
        }
    }

    private suspend fun initializeConversation() {
        val id = navConversationId ?: conversationRepository.createConversation()
        conversationId = id

        val persisted = conversationRepository.getMessagesOnce(id)
        if (persisted.isNotEmpty()) {
            _messages.value = persisted.map { entity ->
                ChatMessage(
                    id = entity.id,
                    role = if (entity.role == "user") ChatMessage.Role.USER else ChatMessage.Role.ASSISTANT,
                    content = entity.content,
                    thinkingText = entity.thinkingText,
                )
            }
        }

        // Initialize the inference engine if not already ready.
        val preferred = downloadManager.preferredConversationModel()
        val modelState = downloadManager.downloadStates.value[preferred]
        if (modelState is DownloadState.Downloaded) {
            val profile = userProfileRepository.get()
            val dateTime = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, HH:mm"))
            val systemPrompt = buildString {
                append(DEFAULT_SYSTEM_PROMPT)
                append("\n\n[Current date and time]\n$dateTime")
                if (profile.isNotBlank()) append("\n\n[User Profile]\n$profile")
            }
            try {
                if (!inferenceEngine.isReady.value) {
                    inferenceEngine.initialize(ModelConfig(modelPath = modelState.localPath, systemPrompt = systemPrompt))
                } else {
                    inferenceEngine.updateSystemPrompt(systemPrompt)
                }
            } catch (e: Exception) {
                _error.value = "Failed to load model: ${e.message}"
            }
        }
    }

    fun retryDownload(model: KernelModel) {
        downloadManager.startDownload(model, force = false)
    }

    fun onInputChanged(text: String) {
        _inputText.value = text
        if (_error.value != null) _error.value = null
    }

    fun sendMessage() {
        val text = _inputText.value.trim()
        if (text.isBlank() || inferenceEngine.isGenerating.value) return

        _inputText.value = ""
        val convId = conversationId ?: return

        viewModelScope.launch {
            val userMsgId = UUID.randomUUID().toString()
            val userMessage = ChatMessage(
                id = userMsgId,
                role = ChatMessage.Role.USER,
                content = text,
            )
            _messages.update { it + userMessage }
            conversationRepository.addMessage(convId, "user", text)
            ragRepository.indexMessage(userMsgId, convId, text)

            // Placeholder for the streaming assistant reply.
            val assistantMsgId = UUID.randomUUID().toString()
            val streamingPlaceholder = ChatMessage(
                id = assistantMsgId,
                role = ChatMessage.Role.ASSISTANT,
                content = "",
                isStreaming = true,
            )
            _messages.update { it + streamingPlaceholder }

            var accumulatedContent = StringBuilder()
            var accumulatedThinking = StringBuilder()

            val ragContext = ragRepository.getRelevantContext(text)
            val prompt = if (ragContext.isNotBlank()) "$ragContext\n\n$text" else text

            try {
                inferenceEngine.generate(prompt).collect { result ->
                    when (result) {
                        is GenerationResult.Token -> {
                            accumulatedContent.append(result.text)
                            _messages.update { msgs ->
                                msgs.map { msg ->
                                    if (msg.id == assistantMsgId) {
                                        msg.copy(content = accumulatedContent.toString())
                                    } else msg
                                }
                            }
                        }

                        is GenerationResult.Thinking -> {
                            accumulatedThinking.append(result.text)
                            _messages.update { msgs ->
                                msgs.map { msg ->
                                    if (msg.id == assistantMsgId) {
                                        msg.copy(thinkingText = accumulatedThinking.toString())
                                    } else msg
                                }
                            }
                        }

                        is GenerationResult.Complete -> {
                            _messages.update { msgs ->
                                msgs.map { msg ->
                                    if (msg.id == assistantMsgId) msg.copy(isStreaming = false) else msg
                                }
                            }
                            val thinking = accumulatedThinking.toString().takeIf { it.isNotBlank() }
                            conversationRepository.addMessage(convId, "assistant", accumulatedContent.toString(), thinking)
                            ragRepository.indexMessage(assistantMsgId, convId, accumulatedContent.toString())
                            autoTitleConversation(convId, text)
                        }

                        is GenerationResult.Error -> {
                            _messages.update { msgs ->
                                msgs.map { msg ->
                                    if (msg.id == assistantMsgId) {
                                        msg.copy(content = "Sorry, I ran into an error.", isStreaming = false)
                                    } else msg
                                }
                            }
                            _error.value = result.message
                        }
                    }
                }
            } catch (e: Exception) {
                _messages.update { msgs ->
                    msgs.map { msg ->
                        if (msg.id == assistantMsgId) {
                            msg.copy(content = "Sorry, generation was cancelled.", isStreaming = false)
                        } else msg
                    }
                }
            }
        }
    }

    fun cancelGeneration() {
        inferenceEngine.cancelGeneration()
        // Clear any message stuck in streaming state
        _messages.update { msgs ->
            msgs.map { msg ->
                if (msg.isStreaming) msg.copy(
                    isStreaming = false,
                    content = msg.content.ifBlank { "Generation cancelled." },
                ) else msg
            }
        }
        // Reset LiteRT conversation — cancelProcess() leaves it in a partial state
        // which causes the next message to receive corrupted context (e.g. raw system prompt)
        viewModelScope.launch {
            inferenceEngine.resetConversation()
        }
    }

    fun startNewConversation() {
        viewModelScope.launch {
            val id = conversationRepository.createConversation()
            conversationId = id
            _messages.value = emptyList()
            inferenceEngine.resetConversation()
        }
    }

    /** Auto-generate a short title from the user's first message. */
    private suspend fun autoTitleConversation(convId: String, firstUserMessage: String) {
        val maxLen = 40
        val title = firstUserMessage.trim().take(maxLen).let { t ->
            if (firstUserMessage.length > maxLen) "$t…" else t
        }
        conversationRepository.renameConversation(convId, title)
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch { inferenceEngine.shutdown() }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> "%.0f MB".format(bytes / 1_048_576.0)
    else -> "$bytes B"
}
