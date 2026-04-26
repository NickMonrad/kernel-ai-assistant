package com.kernel.ai.feature.chat

import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.inference.ContextWindowManager
import com.kernel.ai.core.inference.BORING_AI_SYSTEM_PROMPT
import com.kernel.ai.core.inference.BORING_MINIMAL_SYSTEM_PROMPT
import com.kernel.ai.core.inference.EmbeddingEngine
import com.kernel.ai.core.inference.GenerationResult
import com.kernel.ai.core.inference.HALF_JANDAL_SYSTEM_PROMPT
import com.kernel.ai.core.inference.IdentityTier
import com.kernel.ai.core.inference.InferenceEngine
import com.kernel.ai.core.inference.JandalPersona
import com.kernel.ai.core.inference.LlmDispatcher
import com.kernel.ai.core.inference.MINIMAL_SYSTEM_PROMPT
import com.kernel.ai.core.inference.ModelConfig
import com.kernel.ai.core.inference.PersonaMode
import com.kernel.ai.core.inference.download.DownloadState
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.download.ModelDownloadManager
import com.kernel.ai.core.inference.hardware.HardwareTier
import com.kernel.ai.core.memory.rag.RagRepository
import com.kernel.ai.core.memory.repository.ConversationRepository
import com.kernel.ai.core.memory.repository.MemoryRepository
import com.kernel.ai.core.memory.repository.ModelSettingsRepository
import com.kernel.ai.core.memory.repository.UserProfileRepository
import com.kernel.ai.core.memory.repository.MealPlanSessionRepository
import com.kernel.ai.core.memory.usecase.EpisodicDistillationUseCase
import com.kernel.ai.core.skills.KernelAIToolSet
import com.kernel.ai.core.skills.QuickIntentRouter
import com.kernel.ai.core.skills.SkillCall
import com.kernel.ai.core.skills.SkillExecutor
import com.kernel.ai.core.skills.SkillRegistry
import com.kernel.ai.core.skills.SkillResult
import com.kernel.ai.core.skills.ToolPresentation
import com.kernel.ai.core.skills.slot.PendingSlotRequest
import com.kernel.ai.core.skills.slot.SlotFillResult
import com.kernel.ai.core.skills.slot.SlotFillerManager
import com.google.ai.edge.litertlm.ToolProvider
import com.kernel.ai.feature.chat.model.ChatMessage
import com.kernel.ai.feature.chat.model.ChatUiState
import com.kernel.ai.feature.chat.model.ChatUiState.ModelDownloadProgress
import com.kernel.ai.feature.chat.model.ToolCallInfo
import com.kernel.ai.feature.chat.model.toJsonString
import com.kernel.ai.feature.chat.model.toolCallInfoFromJson
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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
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
    private val quickIntentRouter: QuickIntentRouter,
    private val slotFillerManager: SlotFillerManager,
    private val kernelAIToolSet: KernelAIToolSet,
    private val toolProvider: ToolProvider,
    private val embeddingEngine: EmbeddingEngine,
    private val jandalPersona: JandalPersona,
    private val nzTruthSeedingService: NzTruthSeedingService,
    private val verboseLoggingPreferenceUseCase: com.kernel.ai.core.memory.usecase.VerboseLoggingPreferenceUseCase,
    private val mealPlanSessionRepository: MealPlanSessionRepository,
) : ViewModel() {

    val isSeeding: StateFlow<Boolean> = nzTruthSeedingService.isSeeding

    /** Passed via nav arg; null means "start a new conversation". */
    private val navConversationId: String? = savedStateHandle["conversationId"]

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val _inputText = MutableStateFlow("")
    private val _error = MutableStateFlow<String?>(null)
    private val _conversationTitle = MutableStateFlow<String?>(null)
    private var conversationId: String? = null
    private val contextWindowManager = ContextWindowManager()
    private var activeContextWindowSize: Int = 4096

    /** Tracks the timestamp of the last episodic distillation for the current conversation. */
    private var lastDistilledAt: Long? = null

    /**
     * Intent stored when the BERT-tiny classifier returns [QuickIntentRouter.RouteResult.ClassifierMatch]
     * with [needsConfirmation=true]. The LLM is allowed to ask the user for confirmation; if the
     * user's next reply is a simple affirmation the intent is dispatched directly without another
     * LLM round-trip. Cleared on any non-affirmation input or on new conversation.
     *
     * See issue #621.
     */
    private var pendingConfirmationIntent: QuickIntentRouter.MatchedIntent? = null

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

    /** Complete (user, assistant) turn pairs accumulated since the last KV cache reset.
     *  Triggers a proactive reset when it reaches the dynamic turn limit derived from
     *  [activeContextWindowSize] to cap KV cache memory growth (#543). */
    private var turnsSinceReset = 0

    /** The model currently loaded into the inference engine; used for the [Runtime] context block. */
    private var activeModel: KernelModel? = null

    /** Set synchronously when title generation is launched to prevent duplicate coroutines. */
    private var titleGenerationStarted = false

    /**
     * True when [_conversationTitle] holds a first-message placeholder rather than a
     * proper AI-generated title. Allows [generateTitle] to overwrite it after the 2nd exchange.
     */
    private var titleIsPlaceholder = false

    /** C2 (#487): per-turn flag — true once the hallucination retry has been attempted. */
    private var hallucinationRetryAttempted = false

    /** True while Gemma-4 is being lazily initialised in response to a [sendMessage] call. */
    private val _isLoadingModel = MutableStateFlow(false)

    /** True once [initializeConversation] has completed and [conversationId] is set. */
    private val _conversationInitialized = MutableStateFlow(false)

    /**
     * Exposed for [ChatScreen]'s initialQuery auto-submit: fires as soon as the conversation
     * record exists, without waiting for the LLM to load. Slot-fill queries (NeedsSlot) never
     * need the model; [sendMessage] handles model loading internally for LLM-routed queries.
     */
    val isConversationReady: StateFlow<Boolean> = _conversationInitialized.asStateFlow()
    private val _showThinkingProcess = MutableStateFlow(true)

    /** Ensures at most one concurrent Gemma-4 initialisation attempt. */
    private val gemma4InitMutex = Mutex()

    private data class EngineState(
        val isReady: Boolean,
        val isGenerating: Boolean,
        val isLoadingModel: Boolean,
        val conversationInitialized: Boolean,
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
        _conversationInitialized,
    ) { isReady, isGenerating, isLoadingModel, conversationInitialized ->
        EngineState(isReady, isGenerating, isLoadingModel, conversationInitialized)
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
        _showThinkingProcess,
    ) { engine, downloadStates, input, showThinking ->
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
            !engine.isReady || !engine.conversationInitialized -> ChatUiState.Loading
            else -> ChatUiState.Ready(
                conversationId = conversationId ?: "",
                conversationTitle = input.conversationTitle,
                messages = input.messages,
                isGenerating = engine.isGenerating,
                inputText = input.inputText,
                error = input.error,
                isLoadingModel = engine.isLoadingModel,
                showThinkingProcess = showThinking,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ChatUiState.Loading,
    )

    init {
        // Load verbose logging preference from DataStore (safe in core:memory module)
        viewModelScope.launch {
            verboseLoggingPreferenceUseCase.loadAndApplyVerboseLoggingPreference()
        }
        viewModelScope.launch { initializeConversation() }
        nzTruthSeedingService.seedIfNeeded()
        viewModelScope.launch {
            // E4B first — GPU compilation needs every byte of headroom.
            // FG's 289MB on CPU is enough to tip OOM during GPU init.
            // Once E4B is stable, FG loads in ~0.4s with no memory pressure.
            initEngineWhenReady()
        }
        viewModelScope.launch {
            // Re-initialize automatically when Android evicts the engine under memory pressure
            // (#609). We wait for the NEXT ON_START lifecycle event (not current state) because:
            // - TRIM_MEMORY_UI_HIDDEN fires while ProcessLifecycleOwner still reports STARTED
            //   (700ms debounce before ON_STOP dispatches), so withStarted{} returns immediately
            // - We need the user to have the app actually open, not just screen-on briefly
            // - waitForScreenInteractive() in initialize() is a safety net if screen goes off
            //   mid-init, but the real gate should be app-in-foreground
            inferenceEngine.evictionEvents.collect {
                Log.i("ChatViewModel", "Engine evicted — waiting for app to open before re-init (#609)")
                withContext(Dispatchers.Main) {
                    suspendCancellableCoroutine { cont ->
                        val lifecycle = ProcessLifecycleOwner.get().lifecycle
                        // LifecycleEventObserver replays catch-up events when added (e.g. ON_START
                        // if current state is STARTED). We must ignore that replay and only resume
                        // after we've confirmed the app went to background (ON_STOP) and came back
                        // (ON_START). Pre-seed seenStop=true if debounce has already fired.
                        var seenStop = lifecycle.currentState < Lifecycle.State.STARTED
                        val observer = object : LifecycleEventObserver {
                            override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                                when (event) {
                                    Lifecycle.Event.ON_STOP -> seenStop = true
                                    Lifecycle.Event.ON_START -> if (seenStop) {
                                        lifecycle.removeObserver(this)
                                        if (cont.isActive) cont.resume(Unit)
                                    }
                                    else -> {}
                                }
                            }
                        }
                        lifecycle.addObserver(observer)
                        cont.invokeOnCancellation { lifecycle.removeObserver(observer) }
                    }
                }
                Log.i("ChatViewModel", "App opened by user — re-initializing engine after eviction")
                initEngineWhenReady()
            }
        }
        viewModelScope.launch {
            jandalPersona.personaMode.collect {
                if (inferenceEngine.isReady.value) {
                    inferenceEngine.updateSystemPrompt(buildSystemPrompt())
                }
            }
        }
    }

    private suspend fun buildSystemPrompt(
        historyTurns: List<Pair<String, String>> = emptyList(),
        isFirstReply: Boolean = _messages.value.none { it.role == ChatMessage.Role.ASSISTANT },
        identityTier: IdentityTier = IdentityTier.FULL,
    ): String {
        val profile = userProfileRepository.get()
        val personaMode = jandalPersona.currentPersonaMode
        val now = LocalDateTime.now()
        val dateTime = now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, HH:mm", Locale.ENGLISH))
        // ISO date injected alongside the human-readable date so the model can copy it directly
        // when generating calendar event dates without having to reformat the year.
        val isoDate = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH))
        return buildString {
            when (identityTier) {
                IdentityTier.FULL -> {
                    append(
                        when (personaMode) {
                            PersonaMode.FULL -> com.kernel.ai.core.inference.DEFAULT_SYSTEM_PROMPT
                            PersonaMode.HALF -> HALF_JANDAL_SYSTEM_PROMPT
                            PersonaMode.BORING -> BORING_AI_SYSTEM_PROMPT
                        }
                    )
                    // Session vocab is stable per conversation — safe to bake into system prompt.
                    // Greeting instruction is injected per-turn (in the user prompt context)
                    // so it can change from "Kia ora" on turn 1 to "no greeting" on turn 2+
                    // without needing an expensive system prompt reset that clears the KV cache.
                    jandalPersona.buildSessionVocab(personaMode)
                        .takeIf { it.isNotBlank() }
                        ?.let { append("\n\n$it") }
                }
                IdentityTier.MINIMAL -> {
                    append(
                        if (personaMode == PersonaMode.BORING) {
                            BORING_MINIMAL_SYSTEM_PROMPT
                        } else {
                            MINIMAL_SYSTEM_PROMPT
                        }
                    )
                }
            }
            append("\n\n[Current date and time]\n$dateTime (ISO: $isoDate)")
            // Skip profile and history for minimal (Actions) tier to save tokens
            if (identityTier == IdentityTier.FULL) {
                // Prefer structured YAML injection (compact, ~200 tokens) over raw text (~750 tokens).
                val structured = userProfileRepository.getStructured()
                val contextWindowSize = activeModel?.let { model ->
                    modelSettingsRepository.getSettings(model.modelId).contextWindowSize
                } ?: 4096
                val maxProfileChars = modelSettingsRepository.getMaxUserProfileChars(contextWindowSize)
                if (structured != null && !structured.isEmpty()) {
                    append("\n\n[User Profile]\n${structured.toYaml().take(maxProfileChars)}")
                } else if (profile.isNotBlank()) {
                    // Fallback to raw text if no structured data yet
                    val injectedProfile = profile.take(maxProfileChars)
                    append("\n\n[User Profile]\n$injectedProfile")
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
    }

    private fun buildToolUsePrompt(): String {
        val skillNames = skillRegistry.buildNativeDeclarations()
        if (skillNames.isBlank()) return ""
        return buildString {
            append("[Tool Use]\n")
            append("You are an AI assistant that helps users by answering questions and completes tasks using skills.\n")
            append("For EVERY new task, request, or question that needs a tool, you MUST execute these steps in exact order.\n")
            append("You MUST NOT skip any steps.\n\n")
            append("1. First, find the most relevant skill from this list:\n")
            append(skillNames)
            append("\n\n")
            append("After this step you MUST go to the next step. ")
            append("You MUST NOT use run_intent under any circumstances at this step.\n\n")
            append("2. If a relevant skill exists, call load_skill with the skill name to get its full instructions.\n\n")
            append("You MUST NOT use run_intent under any circumstances at this step.\n\n")
            append("3. Follow the skill's instructions exactly to complete the task. ")
            append("Only use run_intent after steps 1 and 2 are complete and only when the loaded skill tells you to. ")
            append("Output ONLY the final result to the user when successful.\n\n")
            append("CRITICAL: Execute all steps silently. Do NOT output intermediate reasoning, status updates, or tool call text.")
        }
    }

    private suspend fun initializeConversation() {
        try {
        val id = navConversationId ?: conversationRepository.createConversation()
        conversationId = id
        // Persist resolved ID so process-death recreation restores the right conversation
        // instead of creating a fresh one on every cold start.
        savedStateHandle["conversationId"] = id

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
                    toolCall = entity.toolCallJson?.let(::toolCallInfoFromJson),
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
        } finally {
            // Always unblock the Loading guard — even on DB error the engine-ready
            // path should still transition to Ready rather than freezing the screen.
            _conversationInitialized.value = true
        }
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
            .filter { states -> downloadManager.areRequiredModelsDownloaded() }
            .first()

        gemma4InitMutex.withLock {
            if (inferenceEngine.isReady.value) return

            val preferred = downloadManager.preferredConversationModel()
            val modelPath = downloadManager.getModelPath(preferred) ?: return
            activeModel = preferred
            try {
                // EmbeddingGemma uses CPU only (no GPU conflict with Gemma-4).
                // embeddingEngine.close() removed — it silently broke search_memory (#445)

                val settings = modelSettingsRepository.getSettings(preferred.modelId)
                activeContextWindowSize = settings.contextWindowSize
                _showThinkingProcess.value = settings.showThinkingProcess
                inferenceEngine.initialize(ModelConfig(
                    modelPath = modelPath,
                    systemPrompt = buildSystemPrompt(),
                    maxTokens = settings.contextWindowSize,
                    temperature = settings.temperature,
                    topP = settings.topP,
                    topK = settings.topK,
                    thinkingEnabled = settings.showThinkingProcess,
                    toolProvider = toolProvider,
                ))
                // Sync to actual clamped KV-cache size (safeTokenCount / hardware-tier cap).
                // settings.contextWindowSize may be power-of-2 (e.g. 4096→4000) and the
                // resolved value is what LiteRT actually allocated — use it for reset thresholds.
                inferenceEngine.resolvedMaxTokens.value.takeIf { it > 0 }?.let {
                    activeContextWindowSize = it
                }
                estimatedTokensUsed = 0
                turnsSinceReset = 0
                inferenceEngine.updateSystemPrompt(buildSystemPrompt())
            } catch (e: Exception) {
                _error.value = "Failed to load model: ${e.message}"
            }
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
                // EmbeddingGemma uses CPU only (no GPU conflict with Gemma-4).
                // embeddingEngine.close() removed — it silently broke search_memory (#445)

                val settings = modelSettingsRepository.getSettings(preferred.modelId)
                activeContextWindowSize = settings.contextWindowSize
                _showThinkingProcess.value = settings.showThinkingProcess
                inferenceEngine.initialize(ModelConfig(
                    modelPath = modelPath,
                    systemPrompt = buildSystemPrompt(),
                    maxTokens = settings.contextWindowSize,
                    temperature = settings.temperature,
                    topP = settings.topP,
                    topK = settings.topK,
                    thinkingEnabled = settings.showThinkingProcess,
                    toolProvider = toolProvider,
                ))
                // Sync to actual clamped KV-cache size (safeTokenCount / hardware-tier cap).
                inferenceEngine.resolvedMaxTokens.value.takeIf { it > 0 }?.let {
                    activeContextWindowSize = it
                }
                estimatedTokensUsed = 0
                turnsSinceReset = 0
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
     * Used by the QuickIntentRouter skill-dispatch path where there is no streaming.
     *
     * [shouldIndex] — set false for device action responses, slot-fill prompts, and error
     * messages that should not surface in future RAG retrievals.
     */
    private suspend fun appendAssistantMessage(convId: String, content: String, shouldIndex: Boolean = true) {
        val msgId = UUID.randomUUID().toString()
        val msg = ChatMessage(
            id = msgId,
            role = ChatMessage.Role.ASSISTANT,
            content = content,
        )
        _messages.update { it + msg }
        val savedId = conversationRepository.addMessage(convId, "assistant", content)
        if (shouldIndex) ragRepository.indexMessage(savedId, convId, content)
    }

    /** Like [appendAssistantMessage] but also attaches a [ToolCallInfo] chip so the UI shows
     *  which skill produced the reply. Used by the DirectReply path (QuickIntentRouter skills
     *  that bypass the LLM). */
    private suspend fun appendAssistantMessageWithToolCall(
        convId: String,
        content: String,
        skillName: String,
        requestJson: String,
        isSuccess: Boolean,
        presentation: ToolPresentation? = null,
    ) {
        val msgId = UUID.randomUUID().toString()
        val toolCall = ToolCallInfo(
            skillName = skillName,
            requestJson = requestJson,
            resultText = content,
            isSuccess = isSuccess,
            presentation = presentation,
        )
        val msg = ChatMessage(
            id = msgId,
            role = ChatMessage.Role.ASSISTANT,
            content = content,
            toolCall = toolCall,
        )
        _messages.update { it + msg }
        val savedId = conversationRepository.addMessage(
            convId, "assistant", content,
            toolCallJson = toolCall.toJsonString(),
        )
        if (shouldIndexToolCallResult(skillName)) ragRepository.indexMessage(savedId, convId, content)
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
            // Set by the Tier 2 intercept when a skill executes successfully; injected into
            // the E4B prompt so it can generate a natural conversational wrapper.
            var systemContext: String? = null
            var groundingContext: String? = null
            var isToolQueryForTurn = false
            // Set true when QIR routes to a device action, OR when the LLM calls a non-indexable
            // tool (run_intent, get_weather, etc.) — suppresses RAG indexing for both the user
            // message and the LLM response to prevent stale device state in future RAG (#614).
            var isDeviceActionExchange = false
            // Hoisted so the Complete handler can index the user message after knowing whether
            // any device-action tools were called during LLM inference.
            var savedUserMsgId = ""

            try {
            val userMsgId = UUID.randomUUID().toString()
            val userMessage = ChatMessage(
                id = userMsgId,
                role = ChatMessage.Role.USER,
                content = text,
            )
            _messages.update { it + userMessage }
            savedUserMsgId = conversationRepository.addMessage(convId, "user", text)
            // User message indexing is deferred to the LLM Complete handler — skipped if any
            // non-indexable tool (run_intent, weather, etc.) is called during inference.

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

            // Tier 2: QuickIntentRouter — fast device action intercept (<30 ms, no model load).
            // On a match: execute the skill immediately, then inject [System: ...] context so
            // E4B generates a natural conversational wrapper around the action result.
            // On failure or UnknownSkill: fall through to E4B unchanged.

            // Slot-fill shortcut: if the previous QIR match was paused awaiting a required
            // param, route the user's reply here before touching QIR or the LLM.
            if (slotFillerManager.hasPending) {
                when (val fillResult = slotFillerManager.onUserReply(text)) {
                    is SlotFillResult.Completed -> {
                        val skill = skillRegistry.get("run_intent")
                        if (skill != null) {
                            val callParams = mapOf("intent_name" to fillResult.intentName) + fillResult.params
                            val skillResult = skill.execute(SkillCall(skill.name, callParams))
                            when (skillResult) {
                                is SkillResult.DirectReply -> {
                                    appendAssistantMessageWithToolCall(
                                        convId = convId,
                                        content = skillResult.content,
                                        skillName = fillResult.intentName,
                                        requestJson = callParams.toString(),
                                        isSuccess = true,
                                        presentation = skillResult.presentation,
                                    )
                                }
                                is SkillResult.Success -> appendAssistantMessage(convId, skillResult.content, shouldIndex = false)
                                is SkillResult.Failure -> appendAssistantMessage(convId, skillResult.error, shouldIndex = false)
                                else -> appendAssistantMessage(convId, "Something went wrong.", shouldIndex = false)
                            }
                        }
                        return@launch
                    }
                    is SlotFillResult.Cancelled -> {
                        appendAssistantMessage(convId, "Okay, cancelled.", shouldIndex = false)
                        return@launch
                    }
                }
            }

            // Confirmation shortcut (#621): if the user is affirming a classifier match that
            // needed confirmation, dispatch the pending intent directly — skip LLM entirely.
            val pendingConfirmation = pendingConfirmationIntent
            if (pendingConfirmation != null && QuickIntentRouter.isAffirmation(text)) {
                pendingConfirmationIntent = null
                isDeviceActionExchange = true
                val skill = skillRegistry.get("run_intent")
                if (skill != null) {
                    val callParams = mapOf("intent_name" to pendingConfirmation.intentName) + pendingConfirmation.params
                    Log.d("KernelAI", "ConfirmationFastPath: dispatching ${pendingConfirmation.intentName}")
                    val skillResult = skill.execute(SkillCall(skill.name, callParams))
                    when (skillResult) {
                        is SkillResult.DirectReply -> {
                            appendAssistantMessageWithToolCall(
                                convId = convId,
                                content = skillResult.content,
                                skillName = pendingConfirmation.intentName,
                                requestJson = callParams.toString(),
                                isSuccess = true,
                                presentation = skillResult.presentation,
                            )
                            return@launch
                        }
                        is SkillResult.Success -> {
                            systemContext = "[System: ${pendingConfirmation.intentName} — ${skillResult.content}]"
                            if (!inferenceEngine.isReady.value) {
                                appendAssistantMessage(convId, skillResult.content, shouldIndex = false)
                                return@launch
                            }
                        }
                        is SkillResult.Failure -> {
                            systemContext = "[System: ${pendingConfirmation.intentName} failed — ${skillResult.error}]"
                        }
                        else -> { /* fall through to E4B unchanged */ }
                    }
                }
                // Fall through to E4B for a natural conversational wrapper
            } else if (pendingConfirmation != null) {
                // Non-affirmation — user moved on; clear the pending confirmation
                pendingConfirmationIntent = null
            }

            val weatherFollowUpLocation = WeatherConversationReferenceResolver.resolveLocation(
                query = text,
                messages = _messages.value.dropLast(1),
            )
            val routeResult = quickIntentRouter.route(text)
            val matchedIntent = weatherFollowUpLocation?.let {
                QuickIntentRouter.MatchedIntent(
                    intentName = "get_weather",
                    params = mapOf("location" to it),
                    source = "conversation",
                )
            } ?: when (routeResult) {
                is QuickIntentRouter.RouteResult.RegexMatch -> routeResult.intent
                is QuickIntentRouter.RouteResult.ClassifierMatch -> {
                    if (routeResult.needsConfirmation) {
                        // Store for fast-path dispatch if user affirms; let LLM ask the question.
                        pendingConfirmationIntent = routeResult.intent
                        Log.d("KernelAI", "ConfirmationFastPath: pending ${routeResult.intent.intentName} (conf=${routeResult.confidence})")
                        systemContext = "[System: The user may want to run the intent '${routeResult.intent.intentName}'. " +
                            "Offer to do it for them and wait for their confirmation.]"
                        null
                    } else {
                        pendingConfirmationIntent = null
                        routeResult.intent
                    }
                }
                is QuickIntentRouter.RouteResult.FallThrough -> null
                is QuickIntentRouter.RouteResult.NeedsSlot -> {
                    // Intent matched but a required param is missing — ask the user for it.
                    slotFillerManager.startSlotFill(
                        PendingSlotRequest(
                            intentName = routeResult.intent.intentName,
                            existingParams = routeResult.intent.params,
                            missingSlot = routeResult.missingSlot,
                        ),
                    )
                    appendAssistantMessage(
                        convId,
                        slotFillerManager.pendingRequest?.promptMessage ?: "What would you like to say?",
                        shouldIndex = false,
                    )
                    return@launch
                }
            }
            if (matchedIntent != null) {
                // Calendar intent matched by classifier but params not extractable via regex —
                // skip immediate execution and fall through to E4B with a structured hint.
                if (matchedIntent.intentName == "create_calendar_event" &&
                    matchedIntent.params["title"].isNullOrBlank()
                ) {
                    val rawQuery = matchedIntent.params["raw_query"] ?: text
                    val titleHint = matchedIntent.params["extracted_title"]
                    val titleClause = if (titleHint != null) "The event title is likely \"$titleHint\". " else ""
                    systemContext = "[System: User wants to create a calendar event. " +
                        "Their request: \"$rawQuery\". " +
                        "${titleClause}Extract the event title, date, and time, then call " +
                        "runIntent(intentName=\"create_calendar_event\", ...). " +
                        "Pass the date exactly as the user said it. Pass time as HH:MM 24h.]"
                    // fall through to E4B — do NOT execute now
                } else {
                // Router intent names (e.g. "toggle_flashlight_on") are sub-intent values
                // handled by the run_intent skill — they aren't top-level skill names.
                // Resolve: direct skill match first, then fall back to run_intent.
                isDeviceActionExchange = true
                val directSkill = skillRegistry.get(matchedIntent.intentName)
                val (skill, callParams) = when {
                    directSkill != null -> directSkill to matchedIntent.params
                    else -> {
                        val runIntent = skillRegistry.get("run_intent")
                        runIntent to (mapOf("intent_name" to matchedIntent.intentName) + matchedIntent.params)
                    }
                }
                if (skill != null) {
                    Log.d("KernelAI", "NativeIntentHandler.handle: intent=${matchedIntent.intentName} params=$callParams")
                    val skillResult = skill.execute(SkillCall(skill.name, callParams))
                    when (skillResult) {
                        is com.kernel.ai.core.skills.SkillResult.DirectReply -> {
                            // Skill produced a complete, self-contained reply — show it verbatim
                            // and bypass the LLM entirely to avoid number/unit corruption.
                            Log.d("KernelAI", "DirectReply: ${skillResult.content.take(200)}")
                            appendAssistantMessageWithToolCall(
                                convId = convId,
                                content = skillResult.content,
                                skillName = matchedIntent.intentName,
                                requestJson = callParams.toString(),
                                isSuccess = true,
                                presentation = skillResult.presentation,
                            )
                            return@launch
                        }
                        is com.kernel.ai.core.skills.SkillResult.Success -> {
                            systemContext = "[System: ${matchedIntent.intentName} — ${skillResult.content}]"
                            // E4B not loaded yet: show action result directly and skip the wrapper.
                            if (!inferenceEngine.isReady.value) {
                                appendAssistantMessage(convId, skillResult.content, shouldIndex = false)
                                return@launch
                            }
                        }
                        is com.kernel.ai.core.skills.SkillResult.Failure -> {
                            // Action failed — inject error context so E4B can explain naturally.
                            systemContext = "[System: ${matchedIntent.intentName} failed — ${skillResult.error}]"
                        }
                        else -> { /* UnknownSkill/ParseError — fall through to E4B unchanged */ }
                    }
                }
                } // end else (non-calendar or calendar with params)
            }

            // Lazy-init Gemma-4 if not yet loaded.
            if (!inferenceEngine.isReady.value) {
                initGemma4()
                if (!inferenceEngine.isReady.value) {
                    // Model still not ready (e.g. file absent) — tell the user and bail.
                    appendAssistantMessage(convId, "Still loading the AI model, please try again in a moment.", shouldIndex = false)
                    return@launch
                }
            }
            // _isLoadingModel is always cleared by the outer finally block below.

            // 2. Gemma-4 streaming inference path.
            // Capture isFirstReply before adding the streaming placeholder — once the placeholder
            // is in _messages the ASSISTANT check would always return false.
            val isFirstReply = _messages.value.none { it.role == ChatMessage.Role.ASSISTANT }
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
                maxTokens = ContextWindowManager.episodicBudget(activeContextWindowSize),
            )
            val ragTokenCost = contextWindowManager.estimateTokens(ragContext)

            // Proactive context reset: if we're at ~75% of the token budget, reset
            // the conversation and replay history to avoid LiteRT locking up.
            val tokenBudget = activeContextWindowSize
            val proactiveReset = estimatedTokensUsed > (tokenBudget * 0.75).toInt()
            // Turn-count reset: cap KV cache growth to a dynamic limit derived from the active
            // context window size, preventing OOM after rapid short-message inferences (#543).
            val maxTurns = ContextWindowManager.maxTurnsForContext(activeContextWindowSize)
            val turnCountReset = turnsSinceReset >= maxTurns
            Log.d("KernelOOM", "tokens_in_use=$estimatedTokensUsed budget=$tokenBudget " +
                "threshold=${(tokenBudget * 0.75).toInt()} proactive_reset=$proactiveReset")

            // Context stripping for tool-routable queries (#438, #481).
            // When the router had a best-guess intent (FallThrough with non-null bestGuess)
            // or the query matches known tool-trigger keywords, strip the Jandal personality
            // and RAG context to free ~1000 tokens for tool-call reasoning. These queries
            // don't benefit from episodic memory or cultural tone — they need clear headspace.
            val priorMessages = _messages.value.dropLast(2) // exclude just-added user + placeholder
            val previousAssistant = priorMessages.lastOrNull { it.role == ChatMessage.Role.ASSISTANT }?.content
            val previousUser = priorMessages.lastOrNull { it.role == ChatMessage.Role.USER }?.content
            val isToolFollowUp = looksLikeToolFollowUp(text, previousUser, previousAssistant)
            val isToolQuery = (routeResult is QuickIntentRouter.RouteResult.FallThrough &&
                routeResult.bestGuess != null) ||
                looksLikeToolQuery(text) ||
                isToolFollowUp
            isToolQueryForTurn = isToolQuery
            val effectiveIdentityTier = if (isToolQuery) IdentityTier.MINIMAL else IdentityTier.FULL
            val effectiveRagContext = if (isToolQuery) "" else ragContext
            val effectiveRagTokenCost = if (isToolQuery) 0 else ragTokenCost

            // Meal-planner session context (#689): when an active session exists, inject
            // structured session state so the model can continue deterministically across
            // follow-up turns. Also suppress episodic/RAG injection for these turns to
            // prevent stale memory leakage (#687).
            val cid = conversationId
            val isActiveMealPlannerTurn = cid != null &&
                mealPlanSessionRepository.getSession(cid)?.let { it.status != "completed" } == true
            val mealPlanContext = if (isActiveMealPlannerTurn && cid != null) {
                val session = mealPlanSessionRepository.getSession(cid)
                buildMealPlanContext(session)
            } else ""
            // Suppress RAG for active meal-planner turns — session state is the source of truth.
            val effectiveRagContextForPrompt = if (isActiveMealPlannerTurn) "" else effectiveRagContext
            // Force history replay when meal-planner session is active — clears stale episodic
            // context from the KV cache so old meal-plan memories cannot leak into the continuation.
            if (isActiveMealPlannerTurn) {
                needsHistoryReplay = true
            }

            // Anaphora handling (#491): tool queries with "save that", "look it up", etc. need
            // the previous turn to resolve what "that/it/this" refers to. Inject the last
            // user+assistant pair as a lightweight context block — still no RAG or personality.
            val anaphoraContext: String = if (isToolQuery && (looksLikeAnaphora(text) || isToolFollowUp)) {
                val lastPair = priorMessages.takeLast(2)
                if (lastPair.isEmpty()) "" else buildString {
                    append("[Context: previous exchange]\n")
                    for (msg in lastPair) {
                        val speaker = if (msg.role == ChatMessage.Role.USER) "User" else "Jandal"
                        append("$speaker: ${msg.content}\n")
                    }
                }.trimEnd()
            } else ""

            if (needsHistoryReplay || proactiveReset || turnCountReset) {
                needsHistoryReplay = false
                val allMessages = _messages.value.dropLast(2) // exclude just-added user + placeholder
                val rawTurns = contextWindowManager.extractTurns(
                    allMessages.map { it.content to (it.role == ChatMessage.Role.USER) }
                )
                // Apply turn-count cap before token-budget selection so both limits are enforced.
                val turns = rawTurns.takeLast(maxTurns)
                if (turns.size < rawTurns.size) {
                    Log.d("KernelAI", "Context truncated (turn limit $maxTurns for ${activeContextWindowSize}t window): kept last ${turns.size} of ${rawTurns.size} turns")
                }
                val selected = contextWindowManager.selectHistory(turns, ContextWindowManager.historyBudget(activeContextWindowSize))
                // Inject history into the system prompt so Gemma treats it as background context.
                val systemPromptWithHistory = buildSystemPrompt(selected, isFirstReply = isFirstReply, identityTier = effectiveIdentityTier)
                inferenceEngine.updateSystemPrompt(systemPromptWithHistory)
                // Re-baseline from selected history, then add the RAG cost for this turn.
                estimatedTokensUsed = selected.sumOf {
                    contextWindowManager.estimateTokens(it.first) + contextWindowManager.estimateTokens(it.second)
                } + effectiveRagTokenCost
                turnsSinceReset = 0
            } else {
                estimatedTokensUsed += effectiveRagTokenCost
            }

            prompt = buildString {
                if (effectiveRagContextForPrompt.isNotBlank()) append("$effectiveRagContextForPrompt\n\n")
                if (mealPlanContext.isNotBlank()) append("$mealPlanContext\n\n")
                if (anaphoraContext.isNotBlank()) append("$anaphoraContext\n\n")
                if (systemContext != null) append("$systemContext\n\n")
                if (effectiveRagContextForPrompt.isNotBlank() || systemContext != null) {
                    append("[System: If the answer depends on provided context, memory, or tool output, copy exact dates, numbers, names, titles, and quoted phrases exactly as written. You may still explain or analyse them when the user asks, but do not mutate literal facts. If the exact detail is not present, say you are not sure.]\n\n")
                }
                if (isToolQuery) {
                    buildToolUsePrompt()
                        .takeIf { it.isNotBlank() }
                        ?.let { append("$it\n\n") }
                }
                // Greeting instruction injected per-turn so turn 1 says "Kia ora" and
                // subsequent turns explicitly suppress greetings — without invalidating the KV cache.
                // Suppressed entirely for tool queries to keep the prompt focused.
                if (!isToolQuery) {
                    append("[System: ${jandalPersona.buildGreetingInstruction(isFirstReply, jandalPersona.currentPersonaMode)}]\n\n")
                }
                append(text)
            }
            groundingContext = buildString {
                if (effectiveRagContextForPrompt.isNotBlank()) append(effectiveRagContextForPrompt)
                if (systemContext != null) {
                    if (isNotBlank()) append('\n')
                    append(systemContext)
                }
            }.ifBlank { null }

            } finally {
                // Reset the loading spinner on every exit path from the pre-inference block —
                // skill early-returns, model-not-ready bail-outs, and the normal fall-through.
                _isLoadingModel.value = false
            }

            try {
                kernelAIToolSet.resetTurnState()
                hallucinationRetryAttempted = false
                var rawToolCallRetryAttempted = false
                var currentPrompt = prompt
                var needsHallucinationRetry: Boolean

            do {
                needsHallucinationRetry = false

            inferenceEngine.generate(currentPrompt).collect { result ->
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
                            val fullContent = correctGroundedFacts(accumulatedContent.toString(), groundingContext)
                            val thinking = accumulatedThinking.toString().takeIf { it.isNotBlank() }

                            // With native SDK tool calling, tool execution happens
                            // transparently during generate() — the SDK calls our @Tool
                            // methods and feeds results back to the model. Check if any
                            // tool was invoked this turn for UI metadata.
                            val nativeToolCall = if (kernelAIToolSet.wasToolCalled()) {
                                val name = kernelAIToolSet.lastToolName() ?: "unknown"
                                val result = kernelAIToolSet.lastToolResult() ?: ""
                                ToolCallInfo(
                                    skillName = name,
                                    requestJson = "",
                                    resultText = result,
                                    isSuccess = !result.startsWith("error"),
                                    presentation = kernelAIToolSet.lastToolPresentation(),
                                )
                            } else null

                            // Fallback: if the SDK didn't handle tool calls (e.g. model
                            // emitted raw JSON instead of using native format), try the
                            // legacy text-based extraction path.
                            val toolCallResult = if (nativeToolCall == null) {
                                tryExecuteToolCall(fullContent)
                            } else null

                            if (nativeToolCall != null || toolCallResult != null) {
                                val toolCall = nativeToolCall ?: toolCallResult!!.first
                                val resultContent = when {
                                    nativeToolCall != null && toolCall.presentation != null && toolCall.isSuccess ->
                                        toolCall.resultText
                                    nativeToolCall != null -> fullContent
                                    else -> toolCallResult!!.second
                                }

                                // Update streaming message with result text
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
                                val savedId = conversationRepository.addMessage(
                                    convId, "assistant", resultContent,
                                    thinkingText = thinking,
                                    toolCallJson = toolCall.toJsonString(),
                                )
                                // Only index knowledge results (e.g. Wikipedia) — not device
                                // actions, weather, or system info which are ephemeral (#614).
                                if (shouldIndexToolCallResult(toolCall.skillName)) {
                                    ragRepository.indexMessage(savedId, convId, resultContent)
                                } else {
                                    // LLM called a device/ephemeral tool — suppress indexing of
                                    // the user message and final response too.
                                    isDeviceActionExchange = true
                                }
                                estimatedTokensUsed += contextWindowManager.estimateTokens(text) +
                                    contextWindowManager.estimateTokens(resultContent) +
                                    contextWindowManager.estimateTokens(thinking ?: "")
                                turnsSinceReset++
                                // Do NOT set needsHistoryReplay here — KV cache remains valid after
                                // native tool calls and forcing a replay drops prior turns from the
                                // tight history budget, causing context amnesia (#446).
                            } else {
                                val isHallucination = looksLikeToolConfirmation(fullContent)
                                val isRawToolCall = isToolQueryForTurn && looksLikeRawToolCall(fullContent)

                                // C2 (#487): Single automatic retry before falling to C1 failure.
                                // If the model hallucinated a tool confirmation and we haven't
                                // retried yet, prepend a correction and re-run inference — unless
                                // the context window is already >75% full.
                                if ((isHallucination && !hallucinationRetryAttempted) ||
                                    (isRawToolCall && !rawToolCallRetryAttempted)
                                ) {
                                    if (isHallucination) {
                                        hallucinationRetryAttempted = true
                                    } else {
                                        rawToolCallRetryAttempted = true
                                    }
                                    val budgetOk = estimatedTokensUsed <= (activeContextWindowSize * 0.75).toInt()
                                    if (budgetOk) {
                                        Log.w(
                                            "KernelAI",
                                            if (isHallucination) "hallucination_retry_attempted" else "raw_tool_call_retry_attempted",
                                        )
                                        needsHallucinationRetry = true
                                        currentPrompt = if (isHallucination) {
                                            HALLUCINATION_RETRY_CORRECTION + "\n\n" + prompt
                                        } else {
                                            RAW_TOOL_CALL_RETRY_CORRECTION + "\n\n" + prompt
                                        }
                                        // Reset streaming state for the retry pass
                                        accumulatedContent = StringBuilder()
                                        accumulatedThinking = StringBuilder()
                                        activeStreamingContent = accumulatedContent
                                        activeStreamingThinking = accumulatedThinking
                                        _messages.update { msgs ->
                                            msgs.map { if (it.id == assistantMsgId) it.copy(content = "", isStreaming = true) else it }
                                        }
                                        return@collect
                                    }
                                    // Token budget >75% — skip retry, fall through to C1 failure
                                }

                                // Normal text or C1 hallucination failure
                                val displayContent = if (isHallucination || isRawToolCall) {
                                    if (currentPrompt !== prompt) {
                                        Log.w(
                                            "KernelAI",
                                            if (isHallucination) "hallucination_retry_failed" else "raw_tool_call_retry_failed",
                                        )
                                    }
                                    Log.w(
                                        "KernelAI",
                                        if (isHallucination) {
                                            "Hallucination guard triggered — model confirmed action without tool call"
                                        } else {
                                            "Raw tool-call guard triggered — model leaked tool syntax instead of executing it"
                                        },
                                    )
                                    "I wasn't able to complete that action — please try again, or try phrasing it differently."
                                } else {
                                    when {
                                        currentPrompt !== prompt && rawToolCallRetryAttempted ->
                                            Log.d("KernelAI", "raw_tool_call_retry_succeeded")
                                        currentPrompt !== prompt && hallucinationRetryAttempted ->
                                            Log.d("KernelAI", "hallucination_retry_succeeded")
                                    }
                                    fullContent
                                }
                                _messages.update { msgs ->
                                    msgs.map { if (it.id == assistantMsgId) it.copy(content = displayContent, isStreaming = false) else it }
                                }
                                val savedAssistantMsgId = conversationRepository.addMessage(convId, "assistant", displayContent, thinking)
                                // Don't index hallucination error messages — they're noise (#614).
                                // Don't index LLM wrappers around device actions — stale device state
                                // ("The light's on!") poisons future RAG retrievals (#614).
                                // Also index the user message here (deferred from sendMessage entry)
                                // so we can skip it too if a device tool was called during inference.
                                if (!isHallucination && !isRawToolCall && !isDeviceActionExchange) {
                                    if (savedUserMsgId.isNotBlank()) {
                                        ragRepository.indexMessage(savedUserMsgId, convId, text)
                                    }
                                    ragRepository.indexMessage(savedAssistantMsgId, convId, displayContent)
                                }
                                estimatedTokensUsed += contextWindowManager.estimateTokens(text) +
                                    contextWindowManager.estimateTokens(displayContent) +
                                    contextWindowManager.estimateTokens(thinking ?: "")
                                turnsSinceReset++
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
            } while (needsHallucinationRetry)

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
        turnsSinceReset = 0
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
            turnsSinceReset = 0
            needsHistoryReplay = false
            pendingConfirmationIntent = null
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
        val extracted = ToolCallExtractor.extractNativeToolCall(raw)
            ?: ToolCallExtractor.extractToolCallJson(raw) ?: return null

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
                    presentation = result.presentation,
                )
                Pair(toolCall, result.content)
            }
        is SkillResult.DirectReply -> {
                val skillName = try {
                    org.json.JSONObject(extracted).optString("name", "unknown")
                } catch (e: Exception) { "unknown" }
                val toolCall = ToolCallInfo(
                    skillName = skillName,
                    requestJson = extracted,
                    resultText = result.content,
                    isSuccess = true,
                    presentation = result.presentation,
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
    }

    /**
     * Returns true if [query] looks like a tool-routable request that doesn't benefit from
     * the full Jandal personality or episodic RAG context (#438, #481).
     *
     * Used to switch to [IdentityTier.MINIMAL] and skip RAG injection for device-action
     * and tool-calling queries, freeing ~1000 tokens for tool-call reasoning.
     */
    private fun looksLikeToolQuery(query: String): Boolean = com.kernel.ai.feature.chat.looksLikeToolQuery(query)

    private fun looksLikeAnaphora(text: String): Boolean = com.kernel.ai.feature.chat.looksLikeAnaphora(text)
    private fun looksLikeToolFollowUp(
        text: String,
        previousUser: String?,
        previousAssistant: String?,
    ): Boolean = com.kernel.ai.feature.chat.looksLikeToolFollowUp(text, previousUser, previousAssistant)

    private fun looksLikeToolConfirmation(response: String): Boolean =
        com.kernel.ai.feature.chat.looksLikeToolConfirmation(response)

    private fun looksLikeRawToolCall(response: String): Boolean =
        com.kernel.ai.feature.chat.looksLikeRawToolCall(response)
}

/** C2 correction prepended to the prompt when a hallucination retry is attempted (#487). */
private const val HALLUCINATION_RETRY_CORRECTION =
    "[System: Your previous response was blocked. It appeared to describe performing an action " +
        "without actually calling the required tool function. You MUST call the appropriate tool — " +
        "do not narrate results. If the tool is unavailable, say so honestly.]"

private const val RAW_TOOL_CALL_RETRY_CORRECTION =
    "[System: Your previous response was blocked. It emitted raw tool-call syntax into chat " +
        "instead of executing the tool. Do NOT print <|tool_call> tokens, JSON tool calls, or " +
        "function text. Silently call the appropriate native tool function and then answer with " +
        "the final user-facing result only.]"

/**
 * Returns true if a tool call result should be indexed in episodic RAG memory.
 *
 * Device actions, weather, and system info are ephemeral — indexing them causes the
 * model to surface stale device state as facts in future turns (#614). Knowledge
 * results (e.g. Wikipedia via run_js) are worth recalling.
 */
private fun shouldIndexToolCallResult(skillName: String): Boolean = when (skillName) {
    "run_intent",       // device actions — transient state, not facts
    "get_weather",      // ephemeral — must always call live
    "get_system_info",  // ephemeral — time/date always stale
    "load_skill",       // meta-tool — no content value
    "save_memory",      // memory system handles indexing separately
    "search_memory",    // read-only, no new content
    -> false
    else -> true        // run_js (wikipedia etc.) — knowledge worth recalling
}

/**
 * Repairs a small set of known literal-copy failures when the model was given grounding context.
 *
 * The repair is intentionally narrow:
 * - percentage truncation from [System:] tool context, e.g. 92% -> 9%
 * - malformed standalone year tokens, e.g. 200007 -> 2007 or 209 -> 2009
 *
 * Broader paraphrasing is left untouched so analytical answers still read naturally.
 */
internal fun correctGroundedFacts(response: String, groundingContext: String?): String {
    if (groundingContext.isNullOrBlank()) return response

    val expectedNumbers = Regex("""\d+""").findAll(groundingContext)
        .map { it.value }
        .filter { it.length >= 2 }
        .distinct()
        .toList()

    // Snapshot original percentage tokens so later loop iterations can't re-correct
    // a token that was already fixed by a prior iteration (chain-correction guard).
    val originalPctTokens = Regex("""(\d+)%""").findAll(response).map { it.groupValues[1] }.toSet()
    var corrected = response
    expectedNumbers.forEach { expected ->
        if (corrected.contains("$expected%")) return@forEach
        corrected = corrected.replace(Regex("""(\d+)%""")) { pctMatch ->
            val found = pctMatch.groupValues[1]
            if (found in originalPctTokens && expected.startsWith(found) && found.length < expected.length) "$expected%"
            else pctMatch.value
        }
    }

    val expectedYears = Regex("""(?<!\d)(?:1[6-9]\d{2}|20\d{2}|21\d{2})(?!\d)""")
        .findAll(groundingContext)
        .map { it.value }
        .distinct()
        .toList()
    if (expectedYears.isEmpty()) return corrected

    return Regex("""(?<![\d%])\d{3,6}(?![\d%])""").replace(corrected) { match ->
        val found = match.value
        if (found.length == 4 && found in expectedYears) return@replace found

        val candidates = expectedYears.filter { expected ->
            expected != found &&
                found.firstOrNull() == expected.firstOrNull() &&
                levenshteinDistance(found, expected) <= if (found.length <= 4) 1 else 2
        }
        if (candidates.size == 1) candidates.first() else found
    }
}

private fun levenshteinDistance(left: String, right: String): Int {
    if (left == right) return 0
    if (left.isEmpty()) return right.length
    if (right.isEmpty()) return left.length

    val prev = IntArray(right.length + 1) { it }
    val curr = IntArray(right.length + 1)

    for (i in 1..left.length) {
        curr[0] = i
        for (j in 1..right.length) {
            val cost = if (left[i - 1] == right[j - 1]) 0 else 1
            curr[j] = minOf(
                curr[j - 1] + 1,
                prev[j] + 1,
                prev[j - 1] + cost,
            )
        }
        for (j in prev.indices) prev[j] = curr[j]
    }

    return prev[right.length]
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> "%.0f MB".format(bytes / 1_048_576.0)
    else -> "$bytes B"
}

/**
 * Builds a compact meal-planner session context block for injection into prompts.
 *
 * When an active meal-plan session exists, this provides the model with structured
 * state (preferences, plan, current day) so it can continue deterministically
 * across follow-up turns without re-asking already-known information.
 */
private fun buildMealPlanContext(session: com.kernel.ai.core.memory.entity.MealPlanSessionEntity?): String {
    if (session == null) return ""
    return buildString {
        append("[Meal Planner Session]\n")
        append("Status: ${session.status}\n")
        session.peopleCount?.let { append("People: $it\n") }
        session.days?.let { append("Days: $it\n") }
        if (session.dietaryRestrictionsJson != "[]") {
            append("Dietary: ${session.dietaryRestrictionsJson}\n")
        }
        if (session.proteinPreferencesJson != "[]") {
            append("Proteins: ${session.proteinPreferencesJson}\n")
        }
        session.highLevelPlanJson?.let { plan ->
            append("Plan: $plan\n")
        }
        session.currentDayIndex?.let { idx ->
            val dayLabel = idx + 1
            append("Current day: $dayLabel\n")
        }
        append("[End Meal Planner Session]")
    }
}
