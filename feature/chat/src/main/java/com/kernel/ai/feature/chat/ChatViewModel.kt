package com.kernel.ai.feature.chat

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.inference.ContextWindowManager
import com.kernel.ai.core.inference.DEFAULT_SYSTEM_PROMPT
import com.kernel.ai.core.inference.EmbeddingEngine
import com.kernel.ai.core.inference.FunctionGemmaRouter
import com.kernel.ai.core.inference.GenerationResult
import com.kernel.ai.core.inference.InferenceEngine
import com.kernel.ai.core.inference.JandalPersona
import com.kernel.ai.core.inference.KernelAIToolSet
import com.kernel.ai.core.inference.LlmDispatcher
import com.kernel.ai.core.inference.ModelConfig
import com.kernel.ai.core.inference.download.DownloadState
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.download.ModelDownloadManager
import com.kernel.ai.core.inference.hardware.HardwareTier
import com.kernel.ai.core.memory.rag.RagRepository
import com.kernel.ai.core.memory.repository.ConversationRepository
import com.kernel.ai.core.memory.repository.MemoryRepository
import com.kernel.ai.core.memory.repository.ModelSettingsRepository
import com.kernel.ai.core.memory.repository.UserProfileRepository
import com.kernel.ai.core.memory.usecase.EpisodicDistillationUseCase
import com.kernel.ai.core.skills.SkillExecutor
import com.kernel.ai.core.skills.SkillRegistry
import com.kernel.ai.core.skills.SkillResult
import com.kernel.ai.feature.chat.model.ChatMessage
import com.kernel.ai.feature.chat.model.ChatUiState
import com.kernel.ai.feature.chat.model.ChatUiState.ModelDownloadProgress
import com.kernel.ai.feature.chat.model.ToolCallInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
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
    private val episodicDistillationUseCase: EpisodicDistillationUseCase,
    private val modelSettingsRepository: ModelSettingsRepository,
    private val skillRegistry: SkillRegistry,
    private val skillExecutor: SkillExecutor,
    private val functionGemmaRouter: FunctionGemmaRouter,
    private val kernelAIToolSet: KernelAIToolSet,
    private val embeddingEngine: EmbeddingEngine,
    private val jandalPersona: JandalPersona,
) : ViewModel() {

    /** Passed via nav arg; null means "start a new conversation". */
    private val navConversationId: String? = savedStateHandle["conversationId"]

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val _inputText = MutableStateFlow("")
    private val _error = MutableStateFlow<String?>(null)
    private val _conversationTitle = MutableStateFlow<String?>(null)
    private var conversationId: String? = null
    private val contextWindowManager = ContextWindowManager()
    private val truthsSeedingMutex = Mutex()

    /** Tracks the timestamp of the last episodic distillation for the current conversation. */
    private var lastDistilledAt: Long? = null

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

    /** True while Gemma-4 is being lazily initialised in response to a [sendMessage] call. */
    private val _isLoadingModel = MutableStateFlow(false)

    /** Ensures at most one concurrent Gemma-4 initialisation attempt. */
    private val gemma4InitMutex = Mutex()

    private data class EngineState(
        val isReady: Boolean,
        val isGenerating: Boolean,
        val isLoadingModel: Boolean,
    )
    private data class InputState(
        val messages: List<ChatMessage>,
        val inputText: String,
        val error: String?,
        val conversationTitle: String?,
    )

    private val engineState = combine(
        inferenceEngine.isReady,
        inferenceEngine.isGenerating,
        _isLoadingModel,
    ) { isReady, isGenerating, isLoadingModel ->
        EngineState(isReady, isGenerating, isLoadingModel)
    }

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
        val allDownloaded = downloadManager.areRequiredModelsDownloaded()
        val tier = downloadManager.deviceTier
        val displayModels: List<KernelModel> = if (tier == HardwareTier.FLAGSHIP) {
            KernelModel.entries.filter { it.isRequired && it != KernelModel.GEMMA_4_E2B } +
                KernelModel.GEMMA_4_E4B
        } else {
            KernelModel.entries.filter { it.isRequired }
        }

        when {
            !allDownloaded -> {
                val anyDownloading = displayModels.any { downloadStates[it] is DownloadState.Downloading }
                val progress = displayModels.map { model ->
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
                isLoadingModel = engine.isLoadingModel,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ChatUiState.Loading,
    )

    init {
        viewModelScope.launch { initializeConversation() }
        viewModelScope.launch { seedKiwiTruthsIfNeeded() }
        viewModelScope.launch {
            // E4B first — GPU compilation needs every byte of headroom.
            // FG's 289MB on CPU is enough to tip OOM during GPU init.
            // Once E4B is stable, FG loads in ~0.4s with no memory pressure.
            initEngineWhenReady()
            initFunctionGemmaRouter()
        }
    }

    private suspend fun seedKiwiTruthsIfNeeded() {
        truthsSeedingMutex.withLock {
            if (jandalPersona.isTruthsSeeded) return
            jandalPersona.truths.forEach { truth ->
                memoryRepository.addCoreMemory(truth, source = "jandal_persona")
            }
            jandalPersona.markTruthsSeeded()
            Log.i("ChatViewModel", "Seeded ${jandalPersona.truths.size} Kiwi truths into core memory")
        }
    }

    private suspend fun buildSystemPrompt(historyTurns: List<Pair<String, String>> = emptyList()): String {
        val profile = userProfileRepository.get()
        val dateTime = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, HH:mm", Locale.ENGLISH))
        return buildString {
            append(DEFAULT_SYSTEM_PROMPT)
            append("\n\n${jandalPersona.getGreeting()} ${jandalPersona.buildSessionVocab()}")
            append("\n\n[Current date and time]\n$dateTime")
            // Runtime info fetched dynamically via get_system_info skill at query time
            if (profile.isNotBlank()) {
                // Truncate profile to context-window-aware budget (10% of context window, max 3000 chars).
                // The original stored profile is never modified — only the injected copy is shortened.
                val contextWindowSize = activeModel?.let { model ->
                    modelSettingsRepository.getSettings(model.modelId).contextWindowSize
                } ?: 4096
                val maxProfileChars = modelSettingsRepository.getMaxUserProfileChars(contextWindowSize)
                val injectedProfile = profile.take(maxProfileChars)
                append("\n\nThe following is background context about the user — use it to personalise responses:\n\n[User Profile]\n$injectedProfile")
            }
            if (historyTurns.isNotEmpty()) {
                append("\n\n[Previous conversation context]\n")
                for ((user, assistant) in historyTurns) {
                    append("User: $user\nAssistant: $assistant\n")
                }
                append("[End of previous conversation context]")
            }
            val nativeDeclarations = skillRegistry.buildNativeDeclarations()
            if (nativeDeclarations.isNotBlank()) {
                append("\n\n[Tool Use]\nWhen you need to perform a device action, output ONLY a tool call token with NO text before or after.\nUse the exact function name from the list below.\n\nNo-argument format: <|tool_call>call:FUNCTION_NAME{}<tool_call|>\nWith-argument format: <|tool_call>call:FUNCTION_NAME{param:<|\"|>value<|\"|>}<tool_call|>\n\n$nativeDeclarations")
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

        // Track lastDistilledAt for episodic distillation on close.
        lastDistilledAt = conversation?.lastDistilledAt

        val persisted = conversationRepository.getMessagesOnce(id)
        if (persisted.isNotEmpty()) {
            _messages.value = persisted.map { entity ->
                ChatMessage(
                    id = entity.id,
                    role = if (entity.role == "user") ChatMessage.Role.USER else ChatMessage.Role.ASSISTANT,
                    content = entity.content,
                    thinkingText = entity.thinkingText,
                    toolCall = entity.toolCallJson?.let { json ->
                        try {
                            val obj = org.json.JSONObject(json)
                            ToolCallInfo(
                                skillName = obj.getString("skillName"),
                                requestJson = obj.getString("requestJson"),
                                resultText = obj.getString("resultText"),
                                isSuccess = obj.getBoolean("isSuccess"),
                            )
                        } catch (e: Exception) {
                            Log.w("KernelAI", "Failed to deserialize toolCallJson: ${e.message}")
                            null
                        }
                    },
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

        // Engine init is handled eagerly by initEngineWhenReady() — shows loading screen.
    }

    /**
     * Eagerly initialises the Gemma-4 [InferenceEngine] at startup.
     *
     * Waits for all required models to be on disk, then loads the preferred
     * conversation model with a visible loading screen ([ChatUiState.Loading]).
     *
     * Protected by [gemma4InitMutex] — the same lock used by [initGemma4] — so a concurrent
     * [sendMessage] call during the ~20s GPU init cannot race here, double-close
     * EmbeddingGemma, or orphan the GPU engine allocation.
     */
    private suspend fun initEngineWhenReady() {
        downloadManager.downloadStates
            .filter { states ->
                KernelModel.entries.filter { it.isRequired }
                    .all { states[it] is DownloadState.Downloaded }
            }
            .first()

        gemma4InitMutex.withLock {
            if (inferenceEngine.isReady.value) return

            val preferred = downloadManager.preferredConversationModel()
            val modelPath = downloadManager.getModelPath(preferred) ?: return
            activeModel = preferred
            try {
                embeddingEngine.close()
                System.gc()
                Log.i("KernelAI", "Released EmbeddingGemma for Gemma-4 GPU init")

                val settings = modelSettingsRepository.getSettings(preferred.modelId)
                inferenceEngine.initialize(ModelConfig(
                    modelPath = modelPath,
                    systemPrompt = buildSystemPrompt(),
                    maxTokens = settings.contextWindowSize,
                    temperature = settings.temperature,
                    topP = settings.topP,
                    toolSet = kernelAIToolSet,
                ))
                estimatedTokensUsed = 0
                inferenceEngine.updateSystemPrompt(buildSystemPrompt())
            } catch (e: Exception) {
                _error.value = "Failed to load model: ${e.message}"
            }
        }
    }

    /**
     * Eagerly initialises [FunctionGemmaRouter] from `init {}` so skill commands are ready
     * before the user types anything.
     *
     * Waits for [KernelModel.FUNCTION_GEMMA_270M] to be on disk (handles the case where the
     * model is currently being downloaded when the chat opens). If the model is absent and not
     * downloading, logs a warning and returns without blocking.
     * Non-fatal if initialisation throws.
     */
    private suspend fun initFunctionGemmaRouter() {
        // Wait until FunctionGemma transitions out of Downloading state, or bail immediately
        // if it's NotDownloaded / Error (never been fetched).
        downloadManager.downloadStates
            .filter { states ->
                val state = states[KernelModel.FUNCTION_GEMMA_270M]
                state is DownloadState.Downloaded ||
                    state is DownloadState.NotDownloaded ||
                    state is DownloadState.Error
            }
            .first()

        val routerPath = downloadManager.getModelPath(KernelModel.FUNCTION_GEMMA_270M)
        if (routerPath == null) {
            Log.i("KernelAI", "FunctionGemmaRouter: model not downloaded — intent routing disabled")
            return
        }
        try {
            functionGemmaRouter.initialize(routerPath)
            Log.i("KernelAI", "FunctionGemmaRouter initialized successfully")
        } catch (e: Exception) {
            Log.w("KernelAI", "FunctionGemmaRouter: init failed (non-fatal): ${e.message}", e)
        }
    }

    /**
     * Lazily initialises the Gemma-4 [InferenceEngine] on the first [sendMessage] that
     * routes to the streaming path.
     *
     * Uses [gemma4InitMutex] to guarantee idempotency: if called concurrently the second
     * caller waits for the first to finish, then finds [InferenceEngine.isReady] already
     * true and returns immediately.
     *
     * Only waits for the preferred conversation model to be on disk via
     * [ModelDownloadManager.getModelPath] (file-existence check) — does NOT block on the
     * [ModelDownloadManager.downloadStates] flow so that manually ADB-pushed models are
     * recognised immediately.
     */
    private suspend fun initGemma4() {
        gemma4InitMutex.withLock {
            if (inferenceEngine.isReady.value) return

            val preferred = downloadManager.preferredConversationModel()
            val modelPath = downloadManager.getModelPath(preferred) ?: return
            activeModel = preferred
            try {
                embeddingEngine.close()
                System.gc()
                Log.i("KernelAI", "Released EmbeddingGemma for Gemma-4 GPU init")

                val settings = modelSettingsRepository.getSettings(preferred.modelId)
                inferenceEngine.initialize(ModelConfig(
                    modelPath = modelPath,
                    systemPrompt = buildSystemPrompt(),
                    maxTokens = settings.contextWindowSize,
                    temperature = settings.temperature,
                    topP = settings.topP,
                    toolSet = kernelAIToolSet,
                ))
                estimatedTokensUsed = 0
                // Rebuild system prompt now that activeBackend is resolved (backend field
                // was null during initialize()).
                inferenceEngine.updateSystemPrompt(buildSystemPrompt())
            } catch (e: Exception) {
                _error.value = "Failed to load model: ${e.message}"
            }
        }
    }

    /**
     * Immediately appends a completed assistant message to the UI and persists it to Room.
     * Used by the FunctionGemma skill-dispatch path where there is no streaming.
     */
    private suspend fun appendAssistantMessage(convId: String, content: String) {
        val msgId = UUID.randomUUID().toString()
        val msg = ChatMessage(
            id = msgId,
            role = ChatMessage.Role.ASSISTANT,
            content = content,
        )
        _messages.update { it + msg }
        val savedId = conversationRepository.addMessage(convId, "assistant", content)
        ragRepository.indexMessage(savedId, convId, content)
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
        if (text.isBlank() || inferenceEngine.isGenerating.value || _isLoadingModel.value) return

        _inputText.value = ""
        val convId = conversationId ?: return

        // Synchronous flag set — collapses the TOCTOU window before coroutine dispatch
        if (!inferenceEngine.isReady.value) {
            _isLoadingModel.value = true
        }

        viewModelScope.launch {
            // Hoisted so the streaming section (outside the try/finally) can read them.
            var assistantMsgId = ""
            var accumulatedContent = StringBuilder()
            var accumulatedThinking = StringBuilder()
            var prompt = ""

            try {
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

            // FunctionGemma routing disabled — concurrent loading causes OOM during
            // E4B GPU init. All queries go directly to Gemma-4 for now.
            // TODO: re-enable with lazy load-on-demand after Gemma-4 is ready.

            // Lazy-init Gemma-4 if not yet loaded.
            if (!inferenceEngine.isReady.value) {
                initGemma4()
                if (!inferenceEngine.isReady.value) {
                    // Model still not ready (e.g. file absent) — tell the user and bail.
                    appendAssistantMessage(convId, "Still loading the AI model, please try again in a moment.")
                    return@launch
                }
            }
            // _isLoadingModel is always cleared by the outer finally block below.

            // 2. Gemma-4 streaming inference path.
            assistantMsgId = UUID.randomUUID().toString()
            val streamingPlaceholder = ChatMessage(
                id = assistantMsgId,
                role = ChatMessage.Role.ASSISTANT,
                content = "",
                isStreaming = true,
            )
            _messages.update { it + streamingPlaceholder }

            accumulatedContent = StringBuilder()
            accumulatedThinking = StringBuilder()

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

            prompt = if (ragContext.isNotBlank()) "$ragContext\n\n$text" else text

            } finally {
                // Reset the loading spinner on every exit path from the pre-inference block —
                // skill early-returns, model-not-ready bail-outs, and the normal fall-through.
                _isLoadingModel.value = false
            }

            try {
                kernelAIToolSet.resetTurnState()
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
                            val fullContent = accumulatedContent.toString()
                            val thinking = accumulatedThinking.toString().takeIf { it.isNotBlank() }

                            // Detect if Gemma-4 output is a function call JSON
                            val toolCallResult = tryExecuteToolCall(fullContent)
                            if (toolCallResult != null) {
                                val (toolCall, resultContent) = toolCallResult

                                // Update streaming message with result text (not raw JSON)
                                _messages.update { msgs ->
                                    msgs.map { msg ->
                                        if (msg.id == assistantMsgId) msg.copy(
                                            content = resultContent,
                                            isStreaming = false,
                                            toolCall = toolCall,
                                        ) else msg
                                    }
                                }

                                // Persist with toolCallJson
                                val toolCallJsonStr = org.json.JSONObject().apply {
                                    put("skillName", toolCall.skillName)
                                    put("requestJson", toolCall.requestJson)
                                    put("resultText", toolCall.resultText)
                                    put("isSuccess", toolCall.isSuccess)
                                }.toString()
                                val savedId = conversationRepository.addMessage(
                                    convId, "assistant", resultContent,
                                    thinkingText = thinking,
                                    toolCallJson = toolCallJsonStr,
                                )
                                ragRepository.indexMessage(savedId, convId, resultContent)
                                estimatedTokensUsed += contextWindowManager.estimateTokens(text) +
                                    contextWindowManager.estimateTokens(resultContent)
                                needsHistoryReplay = true
                            } else {
                                // Normal text response
                                _messages.update { msgs ->
                                    msgs.map { if (it.id == assistantMsgId) it.copy(isStreaming = false) else it }
                                }
                                val savedAssistantMsgId = conversationRepository.addMessage(convId, "assistant", fullContent, thinking)
                                ragRepository.indexMessage(savedAssistantMsgId, convId, fullContent)
                                estimatedTokensUsed += contextWindowManager.estimateTokens(text) +
                                    contextWindowManager.estimateTokens(fullContent)
                            }

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

        // Mark the KV cache dirty BEFORE sending the title prompt. generateOnce() writes the
        // prompt + response into the live LiteRT session. Setting the flag early ensures any
        // concurrent sendMessage() that slips past the isGenerating guard will still trigger
        // a history replay and flush the title artefacts before the next generation (#172).
        needsHistoryReplay = true
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
            // needsHistoryReplay is already true (set before the call) — KV cache may be
            // partially dirty from the prompt write, so the flag correctly stays set.
            // Silent failure — title stays null, user can rename manually.
        }
    }

    /**
     * Attempts to parse [raw] as a skill function call JSON and execute it.
     * Returns a pair of (ToolCallInfo, humanReadableResult) if successful, null otherwise.
     * Returns null for ParseError/UnknownSkill so Gemma-4 output is treated as plain text.
     * Limits to 1 tool call per Gemma-4 response to prevent loops.
     */
    private suspend fun tryExecuteToolCall(raw: String): Pair<ToolCallInfo, String>? {
        // E4B often wraps the JSON tool call in prose. Extract the first valid
        // {"name": ..., "arguments": ...} block from anywhere in the response.
        val extracted = extractNativeToolCall(raw) ?: extractToolCallJson(raw) ?: return null

        val result = skillExecutor.execute(extracted)
        return when (result) {
            is SkillResult.Success -> {
                val skillName = try {
                    org.json.JSONObject(extracted).optString("name", "unknown")
                } catch (e: Exception) { "unknown" }
                val toolCall = ToolCallInfo(
                    skillName = skillName,
                    requestJson = extracted,
                    resultText = result.content,
                    isSuccess = true,
                )
                Pair(toolCall, result.content)
            }
            is SkillResult.Failure -> {
                val toolCall = ToolCallInfo(
                    skillName = result.skillName,
                    requestJson = extracted,
                    resultText = result.error,
                    isSuccess = false,
                )
                Pair(toolCall, "I tried to do that but something went wrong: ${result.error}")
            }
            is SkillResult.ParseError, is SkillResult.UnknownSkill -> null
        }
    }


    /**
     * Scans [text] for a Gemma 4 native tool call token:
     *   <|tool_call>call:name{key:<|"|>value<|"|>,key2:numval}<tool_call|>
     * If found, normalises it to {"name": "name", "arguments": {...}} JSON
     * so it can be dispatched via the existing [SkillExecutor] path.
     */
    private fun extractNativeToolCall(text: String): String? {
        val startTag = "<|tool_call>"
        val endTag = "<tool_call|>"
        val startIdx = text.indexOf(startTag)
        if (startIdx < 0) return null
        val innerStart = startIdx + startTag.length
        val endIdx = text.indexOf(endTag, innerStart)
        if (endIdx < 0) return null

        // inner = "call:name{args}" or "call:name" (no braces for no-arg tools)
        val inner = text.substring(innerStart, endIdx).trim()
        if (!inner.startsWith("call:")) return null

        val afterCall = inner.substring("call:".length)
        val braceIdx = afterCall.indexOf('{')

        val rawName: String
        val argsJson = org.json.JSONObject()
        if (braceIdx < 0) {
            rawName = afterCall.trim()
        } else {
            rawName = afterCall.substring(0, braceIdx).trim()
            val lastBrace = afterCall.lastIndexOf('}')
            if (lastBrace > braceIdx) {
                val argsBlock = afterCall.substring(braceIdx + 1, lastBrace).trim()
                if (argsBlock.isNotBlank()) parseNativeArgs(argsBlock, argsJson)
            }
        }

        if (rawName.isBlank()) return null
        val snakeName = camelToSnake(rawName)

        return org.json.JSONObject()
            .put("name", snakeName)
            .put("arguments", argsJson)
            .toString()
    }

    /** Converts camelCase to snake_case for mapping native tool names to SkillRegistry names. */
    private fun camelToSnake(name: String): String =
        name.replace(Regex("([A-Z])")) { "_${it.value.lowercase()}" }.trimStart('_')

    /**
     * Parses the Gemma 4 native arg block, e.g.:
     *   location:<|"|>London<|"|>,unit:<|"|>celsius<|"|>
     *   duration:5
     */
    private fun parseNativeArgs(raw: String, out: org.json.JSONObject) {
        val strToken = """<|"|>"""
        var i = 0
        while (i < raw.length) {
            val colonIdx = raw.indexOf(':', i)
            if (colonIdx < 0) break
            val key = raw.substring(i, colonIdx).trim()
            val rest = raw.substring(colonIdx + 1)
            if (rest.startsWith(strToken)) {
                val valueStart = strToken.length
                val valueEnd = rest.indexOf(strToken, valueStart)
                if (valueEnd < 0) break
                out.put(key, rest.substring(valueStart, valueEnd))
                i = colonIdx + 1 + valueEnd + strToken.length
                if (i < raw.length && raw[i] == ',') i++
            } else {
                val commaIdx = rest.indexOf(',')
                val rawVal = if (commaIdx < 0) rest.trim() else rest.substring(0, commaIdx).trim()
                out.put(key, rawVal.toLongOrNull() ?: rawVal.toDoubleOrNull() ?: rawVal)
                i = colonIdx + 1 + (if (commaIdx < 0) rest.length else commaIdx + 1)
            }
        }
    }

    /**
     * Scans [text] for the first balanced {...} block that contains a "name" key and
     * can be parsed as a valid skill call JSON. Handles responses where E4B emits the
     * tool call JSON embedded in conversational prose rather than as a standalone object.
     */
    private fun extractToolCallJson(text: String): String? {
        var i = 0
        while (i < text.length) {
            val start = text.indexOf('{', i)
            if (start == -1) break
            var depth = 0
            var j = start
            var inString = false
            var escape = false
            while (j < text.length) {
                val c = text[j]
                when {
                    escape -> escape = false
                    c == '\\' && inString -> escape = true
                    c == '"' -> inString = !inString
                    !inString && c == '{' -> depth++
                    !inString && c == '}' -> {
                        depth--
                        if (depth == 0) {
                            val candidate = text.substring(start, j + 1)
                            if (candidate.contains("\"name\"")) return candidate
                            break
                        }
                    }
                }
                j++
            }
            i = start + 1
        }
        return null
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
        // Fire-and-forget episodic distillation on conversation close.
        if (convId != null) {
            val lastDistilled = lastDistilledAt
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    episodicDistillationUseCase.distil(convId, lastDistilled)
                }.onFailure { Log.w("KernelAI", "Episodic distillation failed: ${it.message}") }
            }
        }
        viewModelScope.launch { inferenceEngine.shutdown() }
        CoroutineScope(LlmDispatcher).launch {
            functionGemmaRouter.release()
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> "%.0f MB".format(bytes / 1_048_576.0)
    else -> "$bytes B"
}
