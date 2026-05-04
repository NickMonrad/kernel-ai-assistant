package com.kernel.ai.feature.chat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.memory.dao.QuickActionDao
import com.kernel.ai.core.memory.entity.QuickActionEntity
import com.kernel.ai.core.skills.QuickIntentRouter
import com.kernel.ai.core.skills.SkillCall
import com.kernel.ai.core.skills.SkillRegistry
import com.kernel.ai.core.skills.SkillResult
import com.kernel.ai.core.skills.ToolPresentationJson
import com.kernel.ai.core.skills.slot.PendingSlotRequest
import com.kernel.ai.core.skills.slot.normalizeSlotReply
import com.kernel.ai.core.voice.VoiceCaptureMode
import com.kernel.ai.core.voice.VoiceInputController
import com.kernel.ai.core.voice.VoiceInputEvent
import com.kernel.ai.core.voice.VoiceInputStartResult
import com.kernel.ai.core.voice.VoiceOutputController
import com.kernel.ai.core.voice.VoiceOutputEvent
import com.kernel.ai.core.voice.VoiceOutputPreferences
import com.kernel.ai.core.voice.VoiceOutputResult
import com.kernel.ai.core.voice.VoiceSpeakRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "KernelAI"
private const val SLOT_REPLY_REARM_DELAY_MS = 350L
private const val VOICE_REPLY_TTS_DELAY_MS = 150L
private const val VOICE_COMMAND_DUPLICATE_WINDOW_MS = 2_000L
private const val PHONE_PERMISSION_REQUIRED_ERROR = "Phone permission is required for auto-dial."

/** Input modality for an action. Carried through slot-fill state for voice-readiness (#350/#588). */
enum class InputMode { Text, Voice }

/**
 * Manages the Actions tab: fast intent routing via [QuickIntentRouter] (Tier 2),
 * skill execution, action-history persistence, and local slot-fill state.
 *
 * Routing is synchronous (<30 ms) — no model loading required at app start.
 * Skill execution runs on the viewModelScope coroutine.
 *
 * Slot-fill state is managed locally here (not via SlotFillerManager, which is
 * ChatViewModel's concern). When QIR returns NeedsSlot, [_pendingSlot] is primed
 * and [ActionsScreen] shows a ModalBottomSheet. On reply, the merged params either
 * continue slot-fill for the next missing value or execute the intent. See #589/#601.
 */
@HiltViewModel
class ActionsViewModel @Inject constructor(
    private val quickIntentRouter: QuickIntentRouter,
    private val skillRegistry: SkillRegistry,
    private val quickActionDao: QuickActionDao,
    private val voiceInputController: VoiceInputController,
    private val voiceOutputController: VoiceOutputController,
    private val voiceOutputPreferences: VoiceOutputPreferences,
) : ViewModel() {

    // ── Action history ──────────────────────────────────────────────────────

    val actions: StateFlow<List<QuickActionEntity>> = quickActionDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Loading / execution state ───────────────────────────────────────────

    sealed interface UiState {
        object Idle : UiState
        object Executing : UiState
    }

    sealed interface VoiceCaptureState {
        object Idle : VoiceCaptureState
        data class Preparing(val mode: VoiceCaptureMode) : VoiceCaptureState
        data class Listening(val mode: VoiceCaptureMode, val transcript: String = "") : VoiceCaptureState
        data class Processing(val mode: VoiceCaptureMode, val transcript: String) : VoiceCaptureState
    }

    sealed interface VoicePlaybackState {
        object Idle : VoicePlaybackState
        data class Speaking(val text: String) : VoicePlaybackState
    }

    /** One-shot navigation/UI events consumed by the screen. */
    sealed interface UiEvent {
        /** Query couldn't be handled by quick actions — navigate to chat for LLM processing. */
        data class NavigateToChat(val query: String) : UiEvent
        object RequestPhonePermission : UiEvent
    }

    private data class PendingPhonePermissionAction(
        val query: String,
        val intentName: String,
        val params: Map<String, String>,
        val inputMode: InputMode,
    )

    /**
     * Local slot-fill state. Holds the pending [PendingSlotRequest] plus the original
     * user query (for history logging) and the [InputMode] (for voice-readiness).
     */
    data class PendingSlotState(
        val request: PendingSlotRequest,
        val originalQuery: String,
        // TODO(#350/#588): use inputMode to adapt SlotFillBottomSheet for voice (mic button, no keyboard)
        val inputMode: InputMode,
    )

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 1)
    val events = _events.asSharedFlow()

    /** Non-null while waiting for the user to supply a missing slot value. */
    private val _pendingSlot = MutableStateFlow<PendingSlotState?>(null)
    val pendingSlot: StateFlow<PendingSlotState?> = _pendingSlot.asStateFlow()

    /** Last error message to show in the UI. Cleared on next action. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _voiceCaptureState = MutableStateFlow<VoiceCaptureState>(VoiceCaptureState.Idle)
    val voiceCaptureState: StateFlow<VoiceCaptureState> = _voiceCaptureState.asStateFlow()

    private val _voicePlaybackState = MutableStateFlow<VoicePlaybackState>(VoicePlaybackState.Idle)
    val voicePlaybackState: StateFlow<VoicePlaybackState> = _voicePlaybackState.asStateFlow()
    private var shouldAutoStartVoiceSlotReply = false
    private var pendingVoiceSlotReplyRestartJob: Job? = null
    private var pendingVoiceSpeechJob: Job? = null
    private var pendingPhonePermissionAction: PendingPhonePermissionAction? = null
    private var recentVoiceCommand: String? = null
    private var recentVoiceCommandAtMs: Long = 0L
    private var spokenResponsesEnabled = true

    init {
        viewModelScope.launch {
            voiceOutputPreferences.spokenResponsesEnabled.collect { enabled ->
                spokenResponsesEnabled = enabled
                if (enabled) {
                    voiceOutputController.warmUp()
                } else {
                    shouldAutoStartVoiceSlotReply = false
                    cancelPendingVoiceSlotReplyRestart()
                    cancelPendingVoiceSpeech()
                    voiceOutputController.stop()
                }
            }
        }
        viewModelScope.launch {
            voiceInputController.events.collect { event ->
                when (event) {
                    is VoiceInputEvent.ListeningStarted -> {
                        if (!ownsVoiceCapture(event.mode)) return@collect
                        val currentTranscript = (voiceCaptureState.value as? VoiceCaptureState.Listening)
                            ?.takeIf { it.mode == event.mode }
                            ?.transcript
                            .orEmpty()
                        _voiceCaptureState.value = VoiceCaptureState.Listening(event.mode, currentTranscript)
                    }
                    is VoiceInputEvent.PartialTranscript -> {
                        if (!ownsVoiceCapture(event.mode)) return@collect
                        _voiceCaptureState.value = VoiceCaptureState.Listening(event.mode, event.text)
                    }
                    is VoiceInputEvent.Transcript -> {
                        if (!ownsVoiceCapture(event.mode)) return@collect
                        val normalizedTranscript = normalizeVoiceCommand(event.text)
                        _voiceCaptureState.value = VoiceCaptureState.Processing(event.mode, normalizedTranscript)
                        when (event.mode) {
                            VoiceCaptureMode.Command -> executeAction(normalizedTranscript, InputMode.Voice)
                            VoiceCaptureMode.SlotReply -> onSlotReply(normalizedTranscript)
                            VoiceCaptureMode.AlertCommand -> Unit
                        }
                    }
                    is VoiceInputEvent.Error -> {
                        if (!ownsVoiceCapture(event.mode)) return@collect
                        _voiceCaptureState.value = VoiceCaptureState.Idle
                        _error.value = event.message
                    }
                    is VoiceInputEvent.ListeningStopped -> {
                        if (!ownsVoiceCapture(event.mode)) return@collect
                        val currentState = _voiceCaptureState.value
                        when (currentState) {
                            is VoiceCaptureState.Listening -> {
                                _voiceCaptureState.value = if (currentState.transcript.isBlank()) {
                                    VoiceCaptureState.Idle
                                } else {
                                    VoiceCaptureState.Processing(
                                        mode = currentState.mode,
                                        transcript = currentState.transcript,
                                    )
                                }
                            }
                            is VoiceCaptureState.Processing -> Unit
                            else -> _voiceCaptureState.value = VoiceCaptureState.Idle
                        }
                    }
                }
            }
        }
        viewModelScope.launch {
            voiceOutputController.events.collect { event ->
                when (event) {
                    is VoiceOutputEvent.SpeakingStarted -> {
                        cancelPendingVoiceSlotReplyRestart()
                        _voicePlaybackState.value = VoicePlaybackState.Speaking(event.text)
                    }
                    VoiceOutputEvent.SpeakingStopped -> {
                        _voicePlaybackState.value = VoicePlaybackState.Idle
                        if (
                            shouldAutoStartVoiceSlotReply &&
                            _pendingSlot.value?.inputMode == InputMode.Voice &&
                            _voiceCaptureState.value == VoiceCaptureState.Idle
                        ) {
                            shouldAutoStartVoiceSlotReply = false
                            cancelPendingVoiceSlotReplyRestart()
                            pendingVoiceSlotReplyRestartJob = viewModelScope.launch {
                                delay(SLOT_REPLY_REARM_DELAY_MS)
                                if (
                                    _pendingSlot.value?.inputMode == InputMode.Voice &&
                                    _voiceCaptureState.value == VoiceCaptureState.Idle
                                ) {
                                    startVoiceCapture(
                                        mode = VoiceCaptureMode.SlotReply,
                                        interruptPlayback = false,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Public API ──────────────────────────────────────────────────────────

    fun startVoiceCommand() {
        startVoiceCapture(VoiceCaptureMode.Command)
    }

    fun startVoiceSlotReply() {
        if (_pendingSlot.value == null) return
        shouldAutoStartVoiceSlotReply = false
        startVoiceCapture(VoiceCaptureMode.SlotReply)
    }

    fun stopVoiceCapture() {
        voiceInputController.stopListening()
        _voiceCaptureState.value = VoiceCaptureState.Idle
    }

    fun stopVoiceOutput() {
        shouldAutoStartVoiceSlotReply = false
        cancelPendingVoiceSlotReplyRestart()
        cancelPendingVoiceSpeech()
        voiceOutputController.stop()
        _voicePlaybackState.value = VoicePlaybackState.Idle
    }

    fun pauseTransientVoiceUi() {
        shouldAutoStartVoiceSlotReply = false
        cancelPendingVoiceSlotReplyRestart()
        cancelPendingVoiceSpeech()
        voiceInputController.stopListening()
        _voiceCaptureState.value = VoiceCaptureState.Idle
        voiceOutputController.stop()
        _voicePlaybackState.value = VoicePlaybackState.Idle
    }

    fun onMicrophonePermissionDenied() {
        _error.value = "Microphone permission is required for voice input."
    }

    fun onPhonePermissionGranted() {
        val pending = pendingPhonePermissionAction ?: return
        pendingPhonePermissionAction = null
        viewModelScope.launch {
            _uiState.value = UiState.Executing
            try {
                shouldAutoStartVoiceSlotReply = false
                voiceOutputController.stop()
                val entity = executeIntent(
                    query = pending.query,
                    intentName = pending.intentName,
                    params = pending.params,
                    inputMode = pending.inputMode,
                )
                quickActionDao.insert(entity)
                speakForVoice(pending.inputMode, entity.resultText)
            } catch (e: Exception) {
                Log.e(TAG, "ActionsViewModel: onPhonePermissionGranted failed — ${e.message}", e)
                _error.value = e.message ?: "Unknown error"
            } finally {
                _voiceCaptureState.value = VoiceCaptureState.Idle
                _uiState.value = UiState.Idle
            }
        }
    }

    fun onPhonePermissionDenied() {
        pendingPhonePermissionAction = null
        _error.value = "Phone permission is required for auto-dial."
    }

    /**
     * Executes a quick-action command via the [QuickIntentRouter] Tier 2 fast intent layer.
     *
     * Routing is synchronous (<30 ms) — no model loading required. If the router matches
     * a known intent, the corresponding skill executes and the result is persisted to history.
     * If a required slot is missing, [pendingSlot] is primed and execution pauses until
     * [onSlotReply] is called. Unrecognised queries fall through to Chat.
     *
     * @param inputMode Carried into [PendingSlotState] for voice-readiness; no-op until #350.
     */
    fun executeAction(query: String, inputMode: InputMode = InputMode.Text) {
        val normalizedQuery = if (inputMode == InputMode.Voice) normalizeVoiceCommand(query) else query.trim()
        if (normalizedQuery.isBlank()) return
        if (inputMode == InputMode.Voice && shouldSuppressDuplicateVoiceCommand(normalizedQuery)) {
            Log.w(TAG, "ActionsViewModel: suppressing duplicate rapid voice command \"$normalizedQuery\"")
            _voiceCaptureState.value = VoiceCaptureState.Idle
            return
        }
        cancelPendingVoiceSpeech()
        if (_pendingSlot.value != null) {
            // A slot-fill is already in progress — ignore until the user replies or cancels.
            // Voice (#350) may want to cancel-and-proceed here instead.
            Log.w(TAG, "ActionsViewModel: executeAction called while slot-fill pending — ignoring \"$normalizedQuery\"")
            return
        }
        _error.value = null

        viewModelScope.launch {
            try {
                shouldAutoStartVoiceSlotReply = false
                voiceOutputController.stop()
                _uiState.value = UiState.Executing

                val routeResult = quickIntentRouter.route(normalizedQuery)
                when (routeResult) {
                    is QuickIntentRouter.RouteResult.FallThrough -> {
                        Log.d(TAG, "ActionsViewModel: FallThrough for \"$normalizedQuery\" → navigating to chat")
                        val entity = QuickActionEntity(
                            userQuery = normalizedQuery,
                            skillName = "llm_fallthrough",
                            resultText = "Sending to Jandal for processing…",
                            isSuccess = true,
                        )
                        quickActionDao.insert(entity)
                        speakForVoice(inputMode, entity.resultText)
                        _events.emit(UiEvent.NavigateToChat(normalizedQuery))
                    }
                    is QuickIntentRouter.RouteResult.NeedsSlot -> {
                        // Pause execution — show slot prompt in a ModalBottomSheet.
                        // Do NOT use SlotFillerManager; state lives locally here.
                        Log.d(TAG, "ActionsViewModel: NeedsSlot for \"$normalizedQuery\" → showing slot sheet")
                        primePendingSlot(
                            intentName = routeResult.intent.intentName,
                            existingParams = routeResult.intent.params,
                            missingSlot = routeResult.missingSlot,
                            originalQuery = normalizedQuery,
                            inputMode = inputMode,
                        )
                    }
                    is QuickIntentRouter.RouteResult.RegexMatch -> {
                        val entity = executeIntent(
                            query = normalizedQuery,
                            intentName = routeResult.intent.intentName,
                            params = routeResult.intent.params,
                            inputMode = inputMode,
                        )
                        quickActionDao.insert(entity)
                        speakForVoice(inputMode, entity.resultText)
                    }
                    is QuickIntentRouter.RouteResult.ClassifierMatch -> {
                        val entity = executeIntent(
                            query = normalizedQuery,
                            intentName = routeResult.intent.intentName,
                            params = routeResult.intent.params,
                            inputMode = inputMode,
                        )
                        quickActionDao.insert(entity)
                        speakForVoice(inputMode, entity.resultText)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "ActionsViewModel: executeAction failed — ${e.message}", e)
                _error.value = e.message ?: "Unknown error"
                val entity = QuickActionEntity(
                    userQuery = normalizedQuery,
                    resultText = "Error: ${e.message ?: "Unknown"}",
                    isSuccess = false,
                )
                quickActionDao.insert(entity)
                speakForVoice(inputMode, entity.resultText)
            } finally {
                _voiceCaptureState.value = VoiceCaptureState.Idle
                _uiState.value = UiState.Idle
            }
        }
    }

    /**
     * Called when the user submits a reply to a slot-fill prompt.
     *
     * Merges the reply into the accumulated params, then either prompts for the next
     * missing required slot or executes the intent once the slot contract is complete.
     */
    fun onSlotReply(text: String) {
        cancelPendingVoiceSpeech()
        val pending = _pendingSlot.value ?: return
        shouldAutoStartVoiceSlotReply = false
        val normalizedText = if (pending.inputMode == InputMode.Voice) {
            normalizeVoiceSlotReply(text, pending.request.missingSlot.name)
        } else {
            normalizeSlotReply(text, pending.request.missingSlot.name)
        }
        if (normalizedText.isBlank()) {
            cancelSlotFill()
            return
        }

        val mergedParams = pending.request.existingParams +
            mapOf(pending.request.missingSlot.name to normalizedText)
        val nextMissingSlot = quickIntentRouter.nextMissingSlot(
            intentName = pending.request.intentName,
            params = mergedParams,
        )
        if (nextMissingSlot != null) {
            primePendingSlot(
                intentName = pending.request.intentName,
                existingParams = mergedParams,
                missingSlot = nextMissingSlot,
                originalQuery = pending.originalQuery,
                inputMode = pending.inputMode,
                delayVoicePrompt = pending.inputMode == InputMode.Voice,
            )
            return
        }

        _pendingSlot.value = null
        viewModelScope.launch {
            _uiState.value = UiState.Executing
            try {
                voiceOutputController.stop()
                val entity = executeIntent(
                    query = pending.originalQuery,
                    intentName = pending.request.intentName,
                    params = mergedParams,
                    inputMode = pending.inputMode,
                )
                quickActionDao.insert(entity)
                speakForVoice(
                    pending.inputMode,
                    entity.resultText,
                    delayMs = if (pending.inputMode == InputMode.Voice) VOICE_REPLY_TTS_DELAY_MS else 0L,
                )
            } catch (e: Exception) {
                Log.e(TAG, "ActionsViewModel: onSlotReply failed — ${e.message}", e)
                _error.value = e.message ?: "Unknown error"
                val entity = QuickActionEntity(
                    userQuery = pending.originalQuery,
                    resultText = "Error: ${e.message ?: "Unknown"}",
                    isSuccess = false,
                )
                quickActionDao.insert(entity)
                speakForVoice(
                    pending.inputMode,
                    entity.resultText,
                    delayMs = if (pending.inputMode == InputMode.Voice) VOICE_REPLY_TTS_DELAY_MS else 0L,
                )
            } finally {
                _voiceCaptureState.value = VoiceCaptureState.Idle
                _uiState.value = UiState.Idle
            }
        }
    }

    /** Silently dismiss the slot-fill sheet with no log entry. */
    fun cancelSlotFill() {
        shouldAutoStartVoiceSlotReply = false
        cancelPendingVoiceSlotReplyRestart()
        cancelPendingVoiceSpeech()
        _pendingSlot.value = null
        stopVoiceCapture()
        voiceOutputController.stop()
    }

    private fun primePendingSlot(
        intentName: String,
        existingParams: Map<String, String>,
        missingSlot: com.kernel.ai.core.skills.slot.SlotSpec,
        originalQuery: String,
        inputMode: InputMode,
        delayVoicePrompt: Boolean = false,
    ) {
        _pendingSlot.value = PendingSlotState(
            request = PendingSlotRequest(
                intentName = intentName,
                existingParams = existingParams,
                missingSlot = missingSlot,
            ),
            originalQuery = originalQuery,
            inputMode = inputMode,
        )
        shouldAutoStartVoiceSlotReply = inputMode == InputMode.Voice
        if (inputMode == InputMode.Voice) {
            _voiceCaptureState.value = VoiceCaptureState.Idle
        }
        speakForVoice(
            inputMode,
            _pendingSlot.value?.request?.promptMessage.orEmpty(),
            delayMs = if (delayVoicePrompt) VOICE_REPLY_TTS_DELAY_MS else 0L,
        )
    }


    fun deleteAction(id: String) {
        viewModelScope.launch { quickActionDao.deleteById(id) }
    }

    fun clearHistory() {
        viewModelScope.launch { quickActionDao.deleteAll() }
    }

    fun clearError() {
        _error.value = null
    }

    override fun onCleared() {
        super.onCleared()
        // QuickIntentRouter is a @Singleton — no cleanup needed here.
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    /**
     * Resolves [intentName] to a skill and executes it with [params].
     * Router intent names (e.g. "toggle_flashlight_on") are sub-intent values passed as the
     * intent_name param to run_intent — they are not skill names themselves.
     */
    private suspend fun executeIntent(
        query: String,
        intentName: String,
        params: Map<String, String>,
        inputMode: InputMode,
    ): QuickActionEntity {
        val directSkill = skillRegistry.get(intentName)
        val (skill, callParams) = when {
            directSkill != null -> directSkill to params
            else -> skillRegistry.get("run_intent") to (mapOf("intent_name" to intentName) + params)
        }
        return if (skill != null) {
            val skillResult = skill.execute(SkillCall(skill.name, callParams))
            if (shouldRequestPhonePermission(intentName, skillResult)) {
                pendingPhonePermissionAction = PendingPhonePermissionAction(
                    query = query,
                    intentName = intentName,
                    params = params,
                    inputMode = inputMode,
                )
                _events.emit(UiEvent.RequestPhonePermission)
            }
            buildEntityFromSkillResult(query, intentName, skillResult)
        } else {
            Log.w(TAG, "ActionsViewModel: intent '$intentName' has no registered skill")
            QuickActionEntity(
                userQuery = query,
                skillName = intentName,
                resultText = "Action recognised but not yet implemented.",
                isSuccess = false,
            )
        }
    }

    private fun buildEntityFromSkillResult(
        query: String,
        skillName: String,
        result: SkillResult,
    ): QuickActionEntity = when (result) {
        is SkillResult.DirectReply -> QuickActionEntity(
            userQuery = query,
            skillName = skillName,
            resultText = result.content,
            isSuccess = true,
            presentationJson = result.presentation?.let(ToolPresentationJson::toJsonString),
        )
        is SkillResult.Success -> QuickActionEntity(
            userQuery = query,
            skillName = skillName,
            resultText = result.content,
            isSuccess = true,
            presentationJson = result.presentation?.let(ToolPresentationJson::toJsonString),
        )
        is SkillResult.Failure -> QuickActionEntity(
            userQuery = query,
            skillName = skillName,
            resultText = if (isPhonePermissionRequired(skillName, result)) {
                result.error
            } else {
                "Action failed: ${result.error}"
            },
            isSuccess = false,
        )
        is SkillResult.UnknownSkill -> QuickActionEntity(
            userQuery = query,
            skillName = skillName,
            resultText = "Unknown skill: $skillName",
            isSuccess = false,
        )
        is SkillResult.ParseError -> QuickActionEntity(
            userQuery = query,
            skillName = skillName,
            resultText = "Parse error: ${result.reason}",
            isSuccess = false,
        )
    }

    private fun shouldRequestPhonePermission(intentName: String, result: SkillResult): Boolean {
        return isPhonePermissionRequired(intentName, result)
    }

    private fun isPhonePermissionRequired(intentName: String, result: SkillResult): Boolean {
        return intentName == "make_call" &&
            result is SkillResult.Failure &&
            result.error == PHONE_PERMISSION_REQUIRED_ERROR
    }


    private fun ownsVoiceCapture(mode: VoiceCaptureMode): Boolean =
        when (val state = _voiceCaptureState.value) {
            is VoiceCaptureState.Preparing -> state.mode == mode
            is VoiceCaptureState.Listening -> state.mode == mode
            is VoiceCaptureState.Processing -> state.mode == mode
            VoiceCaptureState.Idle -> false
        }


    private fun startVoiceCapture(
        mode: VoiceCaptureMode,
        interruptPlayback: Boolean = true,
    ) {
        cancelPendingVoiceSlotReplyRestart()
        cancelPendingVoiceSpeech()
        _error.value = null
        if (interruptPlayback) {
            voiceOutputController.stop()
        }
        _voiceCaptureState.value = VoiceCaptureState.Preparing(mode)
        viewModelScope.launch {
            when (val result = voiceInputController.startListening(mode)) {
                is VoiceInputStartResult.Started -> {
                    if (_voiceCaptureState.value is VoiceCaptureState.Preparing) {
                        _voiceCaptureState.value = VoiceCaptureState.Listening(mode)
                    }
                }
                is VoiceInputStartResult.Unavailable -> {
                    _voiceCaptureState.value = VoiceCaptureState.Idle
                    _error.value = result.message
                }
            }
        }
    }

    private fun speakForVoice(
        inputMode: InputMode,
        text: String,
        delayMs: Long = 0L,
    ) {
        if (inputMode != InputMode.Voice) return
        if (!spokenResponsesEnabled) return
        val summary = toSpokenSummary(text)
        if (summary.isBlank()) return
        cancelPendingVoiceSlotReplyRestart()
        cancelPendingVoiceSpeech()
        pendingVoiceSpeechJob = viewModelScope.launch {
            if (delayMs > 0) delay(delayMs)
            if (!spokenResponsesEnabled) return@launch
            when (val result = voiceOutputController.speak(VoiceSpeakRequest(text = summary))) {
                is VoiceOutputResult.Spoken -> Unit
                is VoiceOutputResult.Unavailable -> _error.value = result.message
            }
        }
    }

    private fun cancelPendingVoiceSlotReplyRestart() {
        pendingVoiceSlotReplyRestartJob?.cancel()
        pendingVoiceSlotReplyRestartJob = null
    }

    private fun cancelPendingVoiceSpeech() {
        pendingVoiceSpeechJob?.cancel()
        pendingVoiceSpeechJob = null
    }

    private fun shouldSuppressDuplicateVoiceCommand(query: String, nowMs: Long = System.nanoTime() / 1_000_000L): Boolean {
        val isDuplicate =
            recentVoiceCommand == query &&
                nowMs - recentVoiceCommandAtMs < VOICE_COMMAND_DUPLICATE_WINDOW_MS
        recentVoiceCommand = query
        recentVoiceCommandAtMs = nowMs
        return isDuplicate
    }

    private fun toSpokenSummary(text: String): String {
        val normalized = text.lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .joinToString(" ")
        val lowered = normalized.lowercase()
        return when {
            lowered.startsWith("error:") -> "That didn't work. Check the action history for details."
            lowered.startsWith("action failed:") -> "That action failed. Check the action history for details."
            lowered.startsWith("unknown skill:") -> "That action isn't available yet."
            lowered.startsWith("parse error:") -> "I couldn't understand that action."
            else -> normalized.take(180)
        }
    }

    private fun normalizeVoiceCommand(text: String): String {
        val original = text.trim()
        val normalized = normalizeAlarmTimeFragments(
            normalizeCommonVoiceMishears(
                normalizeSpokenNumbers(normalizeSpokenTimes(original))
            )
        )
        if (normalized != original) {
            Log.d(TAG, "ActionsViewModel: normalized voice command \"$original\" -> \"$normalized\"")
        }
        val normalizedListCommand = normalizeListVoiceMishears(normalized)
        if (normalizedListCommand != normalized) {
            Log.d(TAG, "ActionsViewModel: normalized list voice command \"$normalized\" -> \"$normalizedListCommand\"")
        }
        val listEllipsisMatch = VOICE_ADD_TO_LIST_WITHOUT_VERB.matchEntire(normalizedListCommand)
        if (listEllipsisMatch != null) {
            val item = listEllipsisMatch.groupValues[1].trim()
            val listName = listEllipsisMatch.groupValues[2].trim()
            val firstWord = item.substringBefore(' ').lowercase()
            if (item.isNotBlank() && listName.isNotBlank() && firstWord !in NON_LIST_ITEM_LEAD_WORDS) {
                return "add $item to $listName list"
            }
        }
        return normalizedListCommand
    }

    private fun normalizeAlarmTimeFragments(text: String): String {
        if (!ALARM_TIME_CONTEXT.containsMatchIn(text)) return text
        var normalized = text
        normalized = SPACED_DIGIT_TIME.replace(normalized) { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return@replace match.value
            val minute = match.groupValues[2].toIntOrNull() ?: return@replace match.value
            val meridiem = match.groupValues[3].lowercase()
            if (hour !in 1..12 || minute !in 0..59) return@replace match.value
            "$hour:${minute.toString().padStart(2, '0')} $meridiem"
        }
        normalized = COMPACT_DIGIT_TIME.replace(normalized) { match ->
            val digits = match.groupValues[1]
            val meridiem = match.groupValues[2].lowercase()
            val (hour, minute) = when (digits.length) {
                3 -> digits.take(1).toInt() to digits.drop(1).toInt()
                4 -> digits.take(2).toInt() to digits.drop(2).toInt()
                else -> return@replace match.value
            }
            if (hour !in 1..12 || minute !in 0..59) return@replace match.value
            "$hour:${minute.toString().padStart(2, '0')} $meridiem"
        }
        normalized = FLATTENED_THIRTY_TIME.replace(normalized) { match ->
            val flattened = match.groupValues[1].toIntOrNull() ?: return@replace match.value
            val hour = flattened - 30
            val meridiem = match.groupValues[2].lowercase()
            if (hour !in 1..12) return@replace match.value
            "$hour:30 $meridiem"
        }
        normalized = SPACED_THIRTY_WORD_TIME.replace(normalized) { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return@replace match.value
            val meridiem = match.groupValues[2].trim().lowercase()
            if (hour !in 0..23) return@replace match.value
            buildString {
                append(hour)
                append(":30")
                if (meridiem.isNotBlank()) {
                    append(' ')
                    append(meridiem)
                }
            }
        }
        normalized = TO_THIRTY_TIME.replace(normalized, "2:30")
        normalized = FLATTENED_BARE_THIRTY_TIME.replace(normalized) { match ->
            val flattened = match.groupValues[1].toIntOrNull() ?: return@replace match.value
            val hour = flattened - 30
            if (hour !in 1..23) return@replace match.value
            "$hour:30"
        }
        return normalized
    }

    private fun normalizeSpokenTimes(text: String): String {
        return SPOKEN_TIME_PHRASE.replace(text) { match ->
            val hourWord = match.groupValues[1].lowercase()
            val minutePhrase = match.groupValues[2].trim()
            val meridiem = match.groupValues[3].trim()
            val hour = parseSpokenHour(hourWord) ?: return@replace match.value
            val minute = parseSpokenMinutePhrase(minutePhrase) ?: return@replace match.value
            buildString {
                append(hour)
                append(':')
                append(minute.toString().padStart(2, '0'))
                if (meridiem.isNotBlank()) {
                    append(' ')
                    append(meridiem.lowercase())
                }
            }
        }
    }

    private fun parseSpokenMinutePhrase(phrase: String): Int? {
        val normalized = phrase.trim().lowercase()
        if (normalized.isBlank()) return null
        val minute = parseSpokenNumberPhrase(normalized)?.toIntOrNull() ?: return null
        return minute.takeIf { it in 0..59 }
    }

    private fun parseSpokenHour(raw: String): Int? {
        val normalized = raw.trim().lowercase()
        return normalized.toIntOrNull()
            ?.takeIf { it in 1..12 }
            ?: BASIC_SPOKEN_NUMBERS[normalized]?.takeIf { it in 1..12 }
    }

    private fun normalizeCommonVoiceMishears(text: String): String {
        var normalized = text
        COMMON_VOICE_PHRASE_REPLACEMENTS.forEach { (regex, replacement) ->
            normalized = regex.replace(normalized, replacement)
        }
        return normalized
    }

    private fun normalizeListVoiceMishears(text: String): String {
        VOICE_ADD_BRIDGE_ITEM_TO_LIST.matchEntire(text)?.let { match ->
            return "add bread to ${match.groupValues[1]}list"
        }
        VOICE_ADD_BRED_TO_LIST_MISHEAR.matchEntire(text)?.let { match ->
            return "add bread to ${match.groupValues[1]}list"
        }
        VOICE_ADD_BREAD_TO_LIST_MISHEAR.matchEntire(text)?.let { match ->
            return "add bread to ${match.groupValues[1]}list"
        }
        VOICE_ADD_TO_LIST_LEADING_AT.matchEntire(text)?.let { match ->
            return "add ${match.groupValues[1].trim()} to ${match.groupValues[2]}list"
        }
        val addToListLastMatch = VOICE_ADD_TO_LIST_ENDING_LAST.matchEntire(text) ?: return text
        return addToListLastMatch.groupValues[1] + "list"
    }

    private fun normalizeVoiceSlotReply(text: String, slotName: String): String {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return trimmed
        val normalized = if (looksLikeNumericSlot(slotName) || looksLikeStandaloneNumberPhrase(trimmed)) {
            normalizeSpokenNumbers(trimmed)
        } else {
            trimmed
        }
        return normalizeSlotReply(normalized, slotName)
    }

    private fun looksLikeNumericSlot(slotName: String): Boolean {
        val normalized = slotName.lowercase()
        return listOf(
            "time", "hour", "minute", "second", "day", "days", "week", "month", "year",
            "duration", "count", "number", "qty", "quantity", "amount", "percent",
            "percentage", "level", "volume", "brightness",
        ).any { normalized.contains(it) }
    }

    private fun looksLikeStandaloneNumberPhrase(text: String): Boolean {
        val words = text.lowercase()
            .replace(Regex("[^a-z\\s-]"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter(String::isNotBlank)
        return words.isNotEmpty() && words.all { SPOKEN_NUMBER_WORDS.contains(it) }
    }

    private fun normalizeSpokenNumbers(text: String): String {
        val pattern = Regex(
            "\\b(?:zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|thousand|oh|o)(?:[ -]+(?:zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|thousand|oh|o))*\\b",
            RegexOption.IGNORE_CASE,
        )
        return pattern.replace(text) { match ->
            parseSpokenNumberPhrase(match.value) ?: match.value
        }
    }

    private fun parseSpokenNumberPhrase(phrase: String): String? {
        val tokens = phrase.lowercase()
            .split(Regex("[ -]+"))
            .filter(String::isNotBlank)
        if (tokens.isEmpty()) return null

        if (tokens.size >= 2 && tokens.all { SINGLE_DIGIT_SPOKEN_NUMBERS.containsKey(it) }) {
            return tokens.joinToString(separator = "") { SINGLE_DIGIT_SPOKEN_NUMBERS.getValue(it).toString() }
        }

        var total = 0
        var current = 0
        var sawValue = false
        for (token in tokens) {
            when {
                BASIC_SPOKEN_NUMBERS.containsKey(token) -> {
                    current += BASIC_SPOKEN_NUMBERS.getValue(token)
                    sawValue = true
                }
                TENS_SPOKEN_NUMBERS.containsKey(token) -> {
                    current += TENS_SPOKEN_NUMBERS.getValue(token)
                    sawValue = true
                }
                token == "hundred" -> {
                    current = if (current == 0) 100 else current * 100
                    sawValue = true
                }
                token == "thousand" -> {
                    val group = if (current == 0) 1 else current
                    total += group * 1000
                    current = 0
                    sawValue = true
                }
                else -> return null
            }
        }
        return if (sawValue) (total + current).toString() else null
    }

    private companion object {
        val VOICE_ADD_TO_LIST_WITHOUT_VERB = Regex(
            """^(.+?)\s+to\s+(?:(?:my|the)\s+)?(.+?)\s+list$""",
            RegexOption.IGNORE_CASE,
        )
        val VOICE_ADD_BRIDGE_ITEM_TO_LIST = Regex(
            """^add\s+a\s+bridge\s+to\s+((?:(?:my|the)\s+)?)list$""",
            RegexOption.IGNORE_CASE,
        )
        val VOICE_ADD_BRED_TO_LIST_MISHEAR = Regex(
            """^(?:and|add)\s+bred\s+to\s+((?:(?:my|the)\s+)?)last$""",
            RegexOption.IGNORE_CASE,
        )
        val VOICE_ADD_BREAD_TO_LIST_MISHEAR = Regex(
            """^at\s+bridge\s+to\s+((?:(?:my|the)\s+)?)last$""",
            RegexOption.IGNORE_CASE,
        )
        val VOICE_ADD_TO_LIST_LEADING_AT = Regex(
            """^(?:at|and)\s+(.+?)\s+to\s+((?:(?:my|the)\s+)?)last$""",
            RegexOption.IGNORE_CASE,
        )
        val VOICE_ADD_TO_LIST_ENDING_LAST = Regex(
            """^((?:add\s+.+?\s+to|put\s+.+?\s+on|(?:chuck|stick|bung|pop|toss)\s+.+?\s+on)\s+(?:(?:my|the)\s+)?)last$""",
            RegexOption.IGNORE_CASE,
        )
        val SPOKEN_TIME_PHRASE = Regex(
            """\b(\d{1,2}|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)\s+((?:oh|o|zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty)(?:[ -]+(?:one|two|three|four|five|six|seven|eight|nine))?)\s*(am|pm|a\.m\.|p\.m\.)?\b""",
            RegexOption.IGNORE_CASE,
        )
        val ALARM_TIME_CONTEXT = Regex(
            """\b(?:alarm|wake\s+me|remind\s+me)\b""",
            RegexOption.IGNORE_CASE,
        )
        val SPACED_DIGIT_TIME = Regex(
            """\b(\d{1,2})\s+(\d{2})\s*(am|pm|a\.m\.|p\.m\.)\b""",
            RegexOption.IGNORE_CASE,
        )
        val COMPACT_DIGIT_TIME = Regex(
            """\b(\d{3,4})\s*(am|pm|a\.m\.|p\.m\.)\b""",
            RegexOption.IGNORE_CASE,
        )
        val FLATTENED_THIRTY_TIME = Regex(
            """\b(3[1-9]|4[0-2])\s*(am|pm|a\.m\.|p\.m\.)\b""",
            RegexOption.IGNORE_CASE,
        )
        val SPACED_THIRTY_WORD_TIME = Regex(
            """\b(\d{1,2})\s+(?:thirty|dirty)\s*(am|pm|a\.m\.|p\.m\.)?\b""",
            RegexOption.IGNORE_CASE,
        )
        val FLATTENED_BARE_THIRTY_TIME = Regex(
            """\b(3[1-9]|4\d|5[0-3])\b""",
            RegexOption.IGNORE_CASE,
        )
        val TO_THIRTY_TIME = Regex(
            """\b(?:to|too)\s+30\b""",
            RegexOption.IGNORE_CASE,
        )
        val COMMON_VOICE_PHRASE_REPLACEMENTS = listOf(
            Regex("""^add\s+and\s+""", RegexOption.IGNORE_CASE) to "add ",
            Regex("""^cole\s+""", RegexOption.IGNORE_CASE) to "call ",
            Regex("""^cold\s+""", RegexOption.IGNORE_CASE) to "call ",
            Regex("""\bsure\s+i\s+mean\b""", RegexOption.IGNORE_CASE) to "show me",
            Regex("""\bsure\s+me\b""", RegexOption.IGNORE_CASE) to "show me",
            Regex("""\bsarah\s+a\b""", RegexOption.IGNORE_CASE) to "set a",
            Regex("""\bsit\s+a\b""", RegexOption.IGNORE_CASE) to "set a",
            Regex("""\bsit\s+on\s+the\s+lam\b""", RegexOption.IGNORE_CASE) to "set an alarm",
            Regex("""\bcancel\s+the\s+time\s+of\b""", RegexOption.IGNORE_CASE) to "cancel the timer",
            Regex("""\bminute\s+time\b""", RegexOption.IGNORE_CASE) to "minute timer",
            Regex("""\bstart\s+time\s+for\b""", RegexOption.IGNORE_CASE) to "start timer for",
            Regex("""\bwhy\s+fine\b""", RegexOption.IGNORE_CASE) to "wifi",
            Regex("""\bwhy\s+fi\b""", RegexOption.IGNORE_CASE) to "wifi",
            Regex("""\bday\s+in\s+day\b""", RegexOption.IGNORE_CASE) to "dnd",
            Regex("""\bnext\s+drink\b""", RegexOption.IGNORE_CASE) to "next track",
            Regex("""\bget\s+system\s+far\b""", RegexOption.IGNORE_CASE) to "get system info",
            Regex(
                """\b((?:create|make|start|new)(?:\s+(?:a|an))?(?:\s+new)?)\s+lust\b""",
                RegexOption.IGNORE_CASE,
            ) to "$1 list",
            Regex("""\bhuge\s+your\s+music\b""", RegexOption.IGNORE_CASE) to "youtube music",
            Regex("""\byou\s+tube\s+music\b""", RegexOption.IGNORE_CASE) to "youtube music",
            Regex("""\bnujood\s+music\b""", RegexOption.IGNORE_CASE) to "youtube music",
            Regex("""\bnew\s+job\s+music\b""", RegexOption.IGNORE_CASE) to "youtube music",
            Regex("""\bplay\s+exam\b""", RegexOption.IGNORE_CASE) to "plexamp",
            Regex("""\bplex\s+amp\b""", RegexOption.IGNORE_CASE) to "plexamp",
            Regex("""\bcomplex\s+amp\b""", RegexOption.IGNORE_CASE) to "plexamp",
            Regex("""\bplagues\s+amp\b""", RegexOption.IGNORE_CASE) to "plexamp",
            Regex("""\bpen\s+adult\b""", RegexOption.IGNORE_CASE) to "panadol",
            Regex("""\bspaghetti\s+pastor\b""", RegexOption.IGNORE_CASE) to "spaghetti pasta",
            Regex("""\bpowerpoint\s+dick\b""", RegexOption.IGNORE_CASE) to "powerpoint deck",
            Regex("""\bwhat(?:'s| is)\s+the\s+day\s+today\b""", RegexOption.IGNORE_CASE) to "what's the date today",
        )
        val NON_LIST_ITEM_LEAD_WORDS = setOf(
            "add", "put", "show", "open", "go", "take", "remove", "delete", "clear",
            "display", "read", "create", "make", "start", "what", "what's", "whats",
        )
        val BASIC_SPOKEN_NUMBERS = mapOf(
            "zero" to 0,
            "one" to 1,
            "two" to 2,
            "three" to 3,
            "four" to 4,
            "five" to 5,
            "six" to 6,
            "seven" to 7,
            "eight" to 8,
            "nine" to 9,
            "ten" to 10,
            "eleven" to 11,
            "twelve" to 12,
            "thirteen" to 13,
            "fourteen" to 14,
            "fifteen" to 15,
            "sixteen" to 16,
            "seventeen" to 17,
            "eighteen" to 18,
            "nineteen" to 19,
        )
        val TENS_SPOKEN_NUMBERS = mapOf(
            "twenty" to 20,
            "thirty" to 30,
            "forty" to 40,
            "fifty" to 50,
            "sixty" to 60,
            "seventy" to 70,
            "eighty" to 80,
            "ninety" to 90,
        )
        val SINGLE_DIGIT_SPOKEN_NUMBERS = BASIC_SPOKEN_NUMBERS
            .filterValues { it in 0..9 }
            .toMutableMap()
            .apply {
                put("oh", 0)
                put("o", 0)
            }
        val SPOKEN_NUMBER_WORDS = BASIC_SPOKEN_NUMBERS.keys +
            TENS_SPOKEN_NUMBERS.keys +
            setOf("hundred", "thousand", "oh", "o")
    }
}
