package com.kernel.ai.feature.chat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.memory.dao.QuickActionDao
import com.kernel.ai.core.memory.entity.QuickActionEntity
import com.kernel.ai.core.skills.QuickIntentRouter
import com.kernel.ai.core.skills.Skill
import com.kernel.ai.core.skills.SkillCall
import com.kernel.ai.core.skills.SkillRegistry
import com.kernel.ai.core.skills.SkillResult
import com.kernel.ai.core.skills.slot.PendingSlotRequest
import com.kernel.ai.core.skills.slot.SlotFillerManager
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

/**
 * Manages the Actions tab: fast intent routing via [QuickIntentRouter] (Tier 2),
 * skill execution, and action-history persistence.
 *
 * Routing is synchronous (<30 ms) — no model loading required at app start.
 * Skill execution runs on the viewModelScope coroutine.
 */
@HiltViewModel
class ActionsViewModel @Inject constructor(
    private val quickIntentRouter: QuickIntentRouter,
    private val skillRegistry: SkillRegistry,
    private val quickActionDao: QuickActionDao,
    private val slotFillerManager: SlotFillerManager,
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

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 1)
    val events = _events.asSharedFlow()

    /** Last error message to show in the UI. Cleared on next action. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Executes a quick-action command via the [QuickIntentRouter] Tier 2 fast intent layer.
     *
     * Routing is synchronous (<30 ms) — no model loading required. If the router matches
     * a known intent, the corresponding skill executes and the result is persisted to history.
     * Unrecognised queries are recorded as not-handled so the user gets immediate feedback.
     */
    fun executeAction(query: String) {
        if (query.isBlank()) return
        _error.value = null

        viewModelScope.launch {
            try {
                _uiState.value = UiState.Executing

                val routeResult = quickIntentRouter.route(query)
                val intent = when (routeResult) {
                    is QuickIntentRouter.RouteResult.RegexMatch -> routeResult.intent
                    is QuickIntentRouter.RouteResult.ClassifierMatch -> routeResult.intent
                    is QuickIntentRouter.RouteResult.FallThrough -> {
                        // No quick action match — hand off to the LLM via the chat screen.
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
                        _uiState.value = UiState.Idle
                        return@launch
                    }
                    is QuickIntentRouter.RouteResult.NeedsSlot -> {
                        // Multi-turn slot-filling — prime the SlotFillerManager then navigate to
                        // Chat so the slot prompt is shown instead of re-routing the original query.
                        Log.d(TAG, "ActionsViewModel: NeedsSlot for \"$query\" → priming slot fill, navigating to chat")
                        slotFillerManager.startSlotFill(
                            PendingSlotRequest(
                                intentName = routeResult.intent.intentName,
                                existingParams = routeResult.intent.params,
                                missingSlot = routeResult.missingSlot,
                            ),
                        )
                        _events.emit(UiEvent.NavigateToChat(""))
                        _uiState.value = UiState.Idle
                        return@launch
                    }
                }

                val entity = run {
                    // Router intent names (e.g. "toggle_flashlight_on") are sub-intent values
                    // passed as the intent_name param to run_intent — they are not skill names.
                    // Resolve: direct skill name match first, then fall back to run_intent.
                    val directSkill = skillRegistry.get(intent.intentName)
                    val (skill, callParams) = when {
                        directSkill != null -> directSkill to intent.params
                        else -> {
                            val runIntent = skillRegistry.get("run_intent")
                            runIntent to (mapOf("intent_name" to intent.intentName) + intent.params)
                        }
                    }
                    if (skill != null) {
                        val skillResult = skill.execute(SkillCall(skill.name, callParams))
                        buildEntityFromSkillResult(query, intent.intentName, skillResult)
                    } else {
                        Log.w(TAG, "ActionsViewModel: intent '${intent.intentName}' has no registered skill")
                        QuickActionEntity(
                            userQuery = query,
                            skillName = intent.intentName,
                            resultText = "Action recognised but not yet implemented.",
                            isSuccess = false,
                        )
                    }
                }
                quickActionDao.insert(entity)
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

    private suspend fun buildEntityFromSkillResult(
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
