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
import com.kernel.ai.core.skills.slot.PendingSlotRequest
import dagger.hilt.android.lifecycle.HiltViewModel
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
 * and [ActionsScreen] shows a ModalBottomSheet. On reply, the merged params are
 * executed directly. See #589.
 */
@HiltViewModel
class ActionsViewModel @Inject constructor(
    private val quickIntentRouter: QuickIntentRouter,
    private val skillRegistry: SkillRegistry,
    private val quickActionDao: QuickActionDao,
) : ViewModel() {

    // ── Action history ──────────────────────────────────────────────────────

    val actions: StateFlow<List<QuickActionEntity>> = quickActionDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Loading / execution state ───────────────────────────────────────────

    sealed interface UiState {
        object Idle : UiState
        object Executing : UiState
    }

    /** One-shot navigation/UI events consumed by the screen. */
    sealed interface UiEvent {
        /** Query couldn't be handled by quick actions — navigate to chat for LLM processing. */
        data class NavigateToChat(val query: String) : UiEvent
    }

    /**
     * Local slot-fill state. Holds the pending [PendingSlotRequest] plus the original
     * user query (for history logging) and the [InputMode] (for voice-readiness).
     */
    data class PendingSlotState(
        val request: PendingSlotRequest,
        val originalQuery: String,
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

    // ── Public API ──────────────────────────────────────────────────────────

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
        if (query.isBlank()) return
        _error.value = null

        viewModelScope.launch {
            try {
                _uiState.value = UiState.Executing

                val routeResult = quickIntentRouter.route(query)
                when (routeResult) {
                    is QuickIntentRouter.RouteResult.FallThrough -> {
                        Log.d(TAG, "ActionsViewModel: FallThrough for \"$query\" → navigating to chat")
                        quickActionDao.insert(
                            QuickActionEntity(
                                userQuery = query,
                                skillName = "llm_fallthrough",
                                resultText = "Sending to Jandal for processing…",
                                isSuccess = true,
                            )
                        )
                        _events.emit(UiEvent.NavigateToChat(query))
                    }
                    is QuickIntentRouter.RouteResult.NeedsSlot -> {
                        // Pause execution — show slot prompt in a ModalBottomSheet.
                        // Do NOT use SlotFillerManager; state lives locally here.
                        Log.d(TAG, "ActionsViewModel: NeedsSlot for \"$query\" → showing slot sheet")
                        _pendingSlot.value = PendingSlotState(
                            request = PendingSlotRequest(
                                intentName = routeResult.intent.intentName,
                                existingParams = routeResult.intent.params,
                                missingSlot = routeResult.missingSlot,
                            ),
                            originalQuery = query,
                            inputMode = inputMode,
                        )
                    }
                    is QuickIntentRouter.RouteResult.RegexMatch ->
                        quickActionDao.insert(executeIntent(query, routeResult.intent.intentName, routeResult.intent.params))
                    is QuickIntentRouter.RouteResult.ClassifierMatch ->
                        quickActionDao.insert(executeIntent(query, routeResult.intent.intentName, routeResult.intent.params))
                }
            } catch (e: Exception) {
                Log.e(TAG, "ActionsViewModel: executeAction failed — ${e.message}", e)
                _error.value = e.message ?: "Unknown error"
                quickActionDao.insert(
                    QuickActionEntity(
                        userQuery = query,
                        resultText = "Error: ${e.message ?: "Unknown"}",
                        isSuccess = false,
                    )
                )
            } finally {
                _uiState.value = UiState.Idle
            }
        }
    }

    /**
     * Called when the user submits a reply to a slot-fill prompt.
     *
     * Merges the reply into the existing params and executes the intent directly.
     * For multi-slot intents (future), re-route through QIR after merging.
     */
    fun onSlotReply(text: String) {
        val pending = _pendingSlot.value ?: return
        if (text.isBlank()) {
            cancelSlotFill()
            return
        }
        _pendingSlot.value = null
        val mergedParams = pending.request.existingParams +
                mapOf(pending.request.missingSlot.name to text.trim())

        viewModelScope.launch {
            _uiState.value = UiState.Executing
            try {
                val entity = executeIntent(pending.originalQuery, pending.request.intentName, mergedParams)
                quickActionDao.insert(entity)
            } catch (e: Exception) {
                Log.e(TAG, "ActionsViewModel: onSlotReply failed — ${e.message}", e)
                _error.value = e.message ?: "Unknown error"
                quickActionDao.insert(
                    QuickActionEntity(
                        userQuery = pending.originalQuery,
                        resultText = "Error: ${e.message ?: "Unknown"}",
                        isSuccess = false,
                    )
                )
            } finally {
                _uiState.value = UiState.Idle
            }
        }
    }

    /** Silently dismiss the slot-fill sheet with no log entry. */
    fun cancelSlotFill() {
        _pendingSlot.value = null
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
    ): QuickActionEntity {
        val directSkill = skillRegistry.get(intentName)
        val (skill, callParams) = when {
            directSkill != null -> directSkill to params
            else -> skillRegistry.get("run_intent") to (mapOf("intent_name" to intentName) + params)
        }
        return if (skill != null) {
            val skillResult = skill.execute(SkillCall(skill.name, callParams))
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
        )
        is SkillResult.Success -> QuickActionEntity(
            userQuery = query,
            skillName = skillName,
            resultText = result.content,
            isSuccess = true,
        )
        is SkillResult.Failure -> QuickActionEntity(
            userQuery = query,
            skillName = skillName,
            resultText = "Action failed: ${result.error}",
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
}
