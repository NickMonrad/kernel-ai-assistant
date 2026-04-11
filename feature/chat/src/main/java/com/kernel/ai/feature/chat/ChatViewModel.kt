package com.kernel.ai.feature.chat

import android.os.Build
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.inference.ContextWindowManager
import com.kernel.ai.core.inference.DEFAULT_SYSTEM_PROMPT
import com.kernel.ai.core.inference.GenerationResult
import com.kernel.ai.core.inference.InferenceEngine
import com.kernel.ai.core.inference.ModelConfig
import com.kernel.ai.core.inference.download.DownloadState
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.download.ModelDownloadManager
import com.kernel.ai.core.memory.rag.RagRepository
import com.kernel.ai.core.memory.repository.ConversationRepository
import com.kernel.ai.core.memory.repository.MemoryRepository
import com.kernel.ai.core.memory.repository.UserProfileRepository
import com.kernel.ai.feature.chat.model.ChatMessage
import com.kernel.ai.feature.chat.model.ChatUiState
import com.kernel.ai.feature.chat.model.ChatUiState.ModelDownloadProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
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
    private val memoryRepository: MemoryRepository,
) : ViewModel() {

    /** Passed via nav arg; null means "start a new conversation". */
    private val navConversationId: String? = savedStateHandle["conversationId"]

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val _inputText = MutableStateFlow("")
    private val _error = MutableStateFlow<String?>(null)
    private val _conversationTitle = MutableStateFlow<String?>(null)
    private var conversationId: String? = null
    private val contextWindowManager = ContextWindowManager()

    // Tracks the in-progress streaming response so it can be flushed to Room on cancel/clear.
    private var activeStreamingMsgId: String? = null
    private var activeStreamingContent = StringBuilder()
    private var activeStreamingThinking = StringBuilder()

    /**
     * When true, the next [sendMessage] will re-inject conversation history into the
     * system prompt before sending, restoring context after a reset (cancel or session restore).
     */
    private var needsHistoryReplay = false

    /** Estimated tokens consumed in the current LiteRT conversation (system prompt not counted). */
    private var estimatedTokensUsed = 0

    /** The model currently loaded into the inference engine; used for the [Runtime] context block. */
    private var activeModel: KernelModel? = null

    /** Set synchronously when title generation is launched to prevent duplicate coroutines. */
    private var titleGenerationStarted = false

    /**
     * True when [_conversationTitle] holds a first-message placeholder rather than a
     * proper AI-generated title. Allows [generateTitle] to overwrite it after the 2nd exchange.
     */
    private var titleIsPlaceholder = false

    private data class EngineState(val isReady: Boolean, val isGenerating: Boolean)
    private data class InputState(
        val messages: List<ChatMessage>,
        val inputText: String,
        val error: String?,
        val conversationTitle: String?,
    )

    private val engineState = combine(
        inferenceEngine.isReady,
        inferenceEngine.isGenerating,
    ) { isReady, isGenerating -> EngineState(isReady, isGenerating) }

    private val inputState = combine(
        _messages,
        _inputText,
        _error,
        _conversationTitle,
    ) { messages, inputText, error, title -> InputState(messages, inputText, error, title) }

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
                conversationTitle = input.conversationTitle,
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
        viewModelScope.launch { initializeConversation() }
        viewModelScope.launch { initEngineWhenReady() }
    }

    private suspend fun buildSystemPrompt(historyTurns: List<Pair<String, String>> = emptyList()): String {
        val profile = userProfileRepository.get()
        val dateTime = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, HH:mm"))
        val backend = inferenceEngine.activeBackend.value?.name ?: "CPU"
        val model = activeModel?.displayName ?: "Gemma 4"
        val device = "${Build.MANUFACTURER} ${Build.MODEL}"
        return buildString {
            append(DEFAULT_SYSTEM_PROMPT)
            append("\n\n[Current date and time]\n$dateTime")
            append("\n\n[Runtime]\nModel: $model | Backend: $backend | Device: $device")
            if (profile.isNotBlank()) {
                append("\n\nThe following is background context about the user — use it to personalise responses:\n\n[User Profile]\n$profile")
            }
            if (historyTurns.isNotEmpty()) {
                append("\n\n[Previous conversation context]\n")
                for ((user, assistant) in historyTurns) {
                    append("User: $user\nAssistant: $assistant\n")
                }
                append("[End of previous conversation context]")
            }
        }
    }

    private suspend fun initializeConversation() {
        val id = navConversationId ?: conversationRepository.createConversation()
        conversationId = id

        // Load persisted title immediately so UI shows it on back-navigation.
        val conversation = conversationRepository.getConversation(id)
        val existingTitle = conversation?.title
        _conversationTitle.value = existingTitle

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
            // History is in Room but not in LiteRT's KV cache — replay on next send.
            needsHistoryReplay = true
        }

        // Determine whether smart-title generation should still fire on this restored session.
        // A title that looks like a first-message placeholder (ends with '…', ≤43 chars) can
        // still be overwritten if the conversation is long enough for a smart title.
        if (existingTitle != null) {
            val looksLikePlaceholder = existingTitle.endsWith("…") && existingTitle.length <= 43
            if (looksLikePlaceholder) {
                // Placeholder from a previous session — allow smart title to fire whenever
                // messageCount >= 4 is reached. sendMessage() enforces that threshold.
                titleIsPlaceholder = true
                titleGenerationStarted = false
                Log.d("KernelAI", "Restored session $id has placeholder title — smart title can still fire")
            } else {
                titleGenerationStarted = true  // real title present — never overwrite
            }
        }
        // else: new conversation, both flags stay false (defaults)

        // Engine init is handled reactively by initEngineWhenReady().
    }

    /**
     * Waits until all required models are present on disk, then initialises the inference
     * engine with the preferred conversation model.
     *
     * Uses [ModelDownloadManager.getModelPath] (file-existence check) rather than the
     * [ModelDownloadManager.downloadStates] StateFlow so that models pushed manually via ADB
     * are recognised immediately, independent of WorkManager task state.
     *
     * Terminates after the first successful initialisation — the engine stays loaded for the
     * lifetime of the ViewModel.
     */
    private suspend fun initEngineWhenReady() {
        // Wait until the StateFlow reports all required models as Downloaded.
        // The initial value of downloadStates already reflects file existence, so this
        // fires immediately when models are already on disk.
        downloadManager.downloadStates
            .filter { states ->
                KernelModel.entries.filter { it.isRequired }
                    .all { states[it] is DownloadState.Downloaded }
            }
            .first()

        if (inferenceEngine.isReady.value) return

        val preferred = downloadManager.preferredConversationModel()
        // Use getModelPath (file-existence) — WorkManager state may disagree with reality
        // e.g. a worker is RUNNING for a model that was already pushed via ADB.
        val modelPath = downloadManager.getModelPath(preferred) ?: return
        activeModel = preferred
        try {
            // Initialize with a prompt that omits backend (not yet known).
            inferenceEngine.initialize(ModelConfig(modelPath = modelPath, systemPrompt = buildSystemPrompt()))
            estimatedTokensUsed = 0
            // Rebuild and push system prompt now that activeBackend is resolved — this
            // corrects the [Runtime] backend field which was null during initialize().
            inferenceEngine.updateSystemPrompt(buildSystemPrompt())
        } catch (e: Exception) {
            _error.value = "Failed to load model: ${e.message}"
        }
    }

    fun retryDownload(model: KernelModel) {
        downloadManager.startDownload(model, force = false)
    }

    fun onInputChanged(text: String) {
        _inputText.value = text
        if (_error.value != null) _error.value = null
    }

    fun renameConversation(newTitle: String) {
        val id = conversationId ?: return
        val trimmed = newTitle.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            titleIsPlaceholder = false  // signal before any suspension point
            conversationRepository.renameConversation(id, trimmed)
            _conversationTitle.value = trimmed
            titleIsPlaceholder = false  // user explicitly named this conversation
        }
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
            val savedUserMsgId = conversationRepository.addMessage(convId, "user", text)
            ragRepository.indexMessage(savedUserMsgId, convId, text)

            // After the very first user message, immediately set a placeholder title from the
            // first ~40 characters of the message so the conversation list never shows a blank
            // title. The smart-title generation (after the 2nd exchange) will overwrite this.
            if (_messages.value.size == 1 && _conversationTitle.value == null && !titleGenerationStarted) {
                val placeholder = text.trim().replace('\n', ' ').take(40) + "…"
                conversationRepository.renameConversation(convId, placeholder)
                _conversationTitle.value = placeholder
                titleIsPlaceholder = true
                Log.d("KernelAI", "Set placeholder title for $convId: \"$placeholder\"")
            }

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

            // Register active streaming state so cancel/onCleared can flush to Room.
            activeStreamingMsgId = assistantMsgId
            activeStreamingContent = accumulatedContent
            activeStreamingThinking = accumulatedThinking

            val ragContext = ragRepository.getRelevantContext(
                query = text,
                conversationId = convId,
                maxTokens = ContextWindowManager.EPISODIC_BUDGET,
            )
            val ragTokenCost = contextWindowManager.estimateTokens(ragContext)

            // Proactive context reset: if we're at ~75% of the token budget, reset
            // the conversation and replay history to avoid LiteRT locking up.
            val tokenBudget = ContextWindowManager.MAX_CONTEXT_TOKENS
            val proactiveReset = estimatedTokensUsed > (tokenBudget * 0.75).toInt()

            if (needsHistoryReplay || proactiveReset) {
                needsHistoryReplay = false
                val allMessages = _messages.value.dropLast(2) // exclude just-added user + placeholder
                val turns = contextWindowManager.extractTurns(
                    allMessages.map { it.content to (it.role == ChatMessage.Role.USER) }
                )
                val selected = contextWindowManager.selectHistory(turns)
                // Inject history into the system prompt so Gemma treats it as background context.
                val systemPromptWithHistory = buildSystemPrompt(selected)
                inferenceEngine.updateSystemPrompt(systemPromptWithHistory)
                // Re-baseline from selected history, then add the RAG cost for this turn.
                estimatedTokensUsed = selected.sumOf {
                    contextWindowManager.estimateTokens(it.first) + contextWindowManager.estimateTokens(it.second)
                } + ragTokenCost
            } else {
                estimatedTokensUsed += ragTokenCost
            }

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
                            val savedAssistantMsgId = conversationRepository.addMessage(convId, "assistant", accumulatedContent.toString(), thinking)
                            ragRepository.indexMessage(savedAssistantMsgId, convId, accumulatedContent.toString())
                            // Track cumulative token usage for proactive context window management.
                            estimatedTokensUsed += contextWindowManager.estimateTokens(text) +
                                contextWindowManager.estimateTokens(accumulatedContent.toString())
                            // Clear streaming tracking now that the message is fully persisted.
                            activeStreamingMsgId = null
                            activeStreamingContent = StringBuilder()
                            activeStreamingThinking = StringBuilder()

                            // Auto-title after the 2nd complete exchange (≥4 messages),
                            // but only if the conversation is still untitled (or holds a
                            // first-message placeholder). Uses >= to avoid missing the trigger
                            // if the count jumps past 4.
                            // Small delay lets the generate() flow fully release the
                            // single-threaded LlmDispatcher before generateOnce() claims it.
                            val messageCount = _messages.value.size
                            if (messageCount >= 4 && (_conversationTitle.value == null || titleIsPlaceholder) && !titleGenerationStarted) {
                                titleGenerationStarted = true
                                viewModelScope.launch {
                                    kotlinx.coroutines.delay(500L)
                                    generateTitle()
                                }
                            }
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
                            activeStreamingMsgId = null
                            activeStreamingContent = StringBuilder()
                            activeStreamingThinking = StringBuilder()
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
                activeStreamingMsgId = null
                activeStreamingContent = StringBuilder()
                activeStreamingThinking = StringBuilder()
            }
        }
    }

    fun cancelGeneration() {
        inferenceEngine.cancelGeneration()
        val partialContent = activeStreamingContent.toString()
        val partialThinking = activeStreamingThinking.toString().takeIf { it.isNotBlank() }
        val convId = conversationId

        // Update UI: mark streaming message as complete with whatever was streamed.
        _messages.update { msgs ->
            msgs.map { msg ->
                if (msg.isStreaming) msg.copy(isStreaming = false) else msg
            }
        }

        // Persist partial content to Room if we have anything streamed.
        if (partialContent.isNotBlank() && convId != null) {
            viewModelScope.launch {
                val savedId = conversationRepository.addMessage(convId, "assistant", partialContent, partialThinking)
                ragRepository.indexMessage(savedId, convId, partialContent)
            }
        }

        // Clear streaming tracking.
        activeStreamingMsgId = null
        activeStreamingContent = StringBuilder()
        activeStreamingThinking = StringBuilder()

        // Reset LiteRT conversation — cancelProcess() leaves it in a partial state.
        // Mark needsHistoryReplay so the next message re-injects prior context.
        needsHistoryReplay = true
        estimatedTokensUsed = 0
        viewModelScope.launch {
            inferenceEngine.resetConversation()
        }
    }

    fun getConversationAsText(): String {
        val messages = _messages.value
        return messages.joinToString("\n") { msg ->
            val prefix = if (msg.role == ChatMessage.Role.USER) "You" else "Jandal"
            "$prefix: ${msg.content}"
        }
    }

    fun startNewConversation() {
        viewModelScope.launch {
            val id = conversationRepository.createConversation()
            conversationId = id
            _messages.value = emptyList()
            _conversationTitle.value = null
            titleGenerationStarted = false
            titleIsPlaceholder = false
            estimatedTokensUsed = 0
            needsHistoryReplay = false
            inferenceEngine.resetConversation()
        }
    }

    /**
     * Generates a short AI title for the current conversation using the first two user messages
     * as context. Only called after the 2nd complete exchange when the conversation is untitled.
     *
     * Runs in its own coroutine — never blocks the chat UI. Silent failure: if generation
     * fails or the engine is busy, the title stays null and the user can rename manually.
     */
    private suspend fun generateTitle() {
        val id = conversationId ?: return

        // Double-check the title is still null or a placeholder (may have been set manually
        // in the meantime — a manual rename should never be overwritten).
        if (conversationRepository.getConversation(id)?.title != null && !titleIsPlaceholder) {
            Log.d("KernelAI", "Title already set for $id — skipping generation")
            return
        }

        val userMessages = _messages.value
            .filter { it.role == ChatMessage.Role.USER }
            .take(2)
            .joinToString(" / ") { it.content.take(100) }

        if (userMessages.isBlank()) {
            Log.w("KernelAI", "No user messages to generate title from")
            return
        }

        // Embed the directive in the prompt itself — we're using the existing conversation
        // so a separate systemPrompt has no effect here.
        val titlePrompt = "Reply with ONLY a short conversation title, 4-6 words, no quotes, " +
            "no markdown, no preamble, no alternatives. Just the title on one line.\n\n" +
            "Conversation: $userMessages"

        Log.d("KernelAI", "Generating title for $id with prompt: ${titlePrompt.take(80)}...")

        try {
            // generateOnce() reuses the existing conversation session (LiteRT only supports
            // one session at a time) and acquires generationMutex so it waits if engine is busy.
            val raw = inferenceEngine.generateOnce(titlePrompt, systemPrompt = null)
            Log.d("KernelAI", "Raw title output: \"$raw\"")
            val title = raw
                .trim()
                // Take only the first line — ignore "Or, ..." alternatives
                .lines().first().trim()
                // Strip leading preamble like "How about:", "Here's a title:", "Title:"
                .replace(Regex("^(?:How about|Here(?:'s| is) (?:a |your )?title|Title)[:\\s]*", RegexOption.IGNORE_CASE), "")
                // Strip markdown bold/italic markers
                .replace(Regex("[*_]+"), "")
                // Strip surrounding quotes
                .trim('"', '\'', '\u201C', '\u201D')
                // Strip trailing punctuation
                .trimEnd('.', '?', '!')
                .trim()
                .take(60)
            if (title.isNotBlank()) {
                // Re-check: user may have renamed the conversation while inference was running
                if (!titleIsPlaceholder) {
                    Log.d("KernelAI", "Title overwritten by user during generation — skipping smart title for $id")
                    return
                }
                conversationRepository.renameConversation(id, title)
                _conversationTitle.value = title
                titleIsPlaceholder = false
                Log.i("KernelAI", "Auto-titled conversation $id: $title")
            } else {
                Log.w("KernelAI", "Title generation produced blank result from raw: \"$raw\"")
            }
        } catch (e: Exception) {
            Log.w("KernelAI", "Auto-title generation failed: ${e.message}", e)
            // Silent failure — title stays null, user can rename manually.
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Flush any in-progress streamed content to Room before the ViewModel is destroyed.
        // runBlocking is acceptable here — called once on ViewModel teardown,
        // Room insert is fast (<10ms), main thread briefly blocked on nav back.
        // viewModelScope is already cancelled at this point so withContext(NonCancellable)
        // inside a viewModelScope.launch does not work reliably.
        val content = activeStreamingContent.toString()
        val thinking = activeStreamingThinking.toString().takeIf { it.isNotBlank() }
        val convId = conversationId
        if (content.isNotBlank() && convId != null) {
            runBlocking {
                conversationRepository.addMessage(convId, "assistant", content, thinking)
            }
        }
        viewModelScope.launch { inferenceEngine.shutdown() }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> "%.0f MB".format(bytes / 1_048_576.0)
    else -> "$bytes B"
}
