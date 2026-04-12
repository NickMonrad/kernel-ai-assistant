package com.kernel.ai.feature.chat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.inference.FunctionGemmaRouter
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.download.ModelDownloadManager
import com.kernel.ai.core.memory.dao.QuickActionDao
import com.kernel.ai.core.memory.entity.QuickActionEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "KernelAI"

/**
 * Manages the Actions tab: lazy FunctionGemma initialisation, command execution,
 * and action-history persistence.
 *
 * FunctionGemma is NOT loaded at startup — it initialises on the first user action
 * to avoid OOM when the main Gemma-4 model is already resident on GPU.
 */
@HiltViewModel
class ActionsViewModel @Inject constructor(
    private val functionGemmaRouter: FunctionGemmaRouter,
    private val downloadManager: ModelDownloadManager,
    private val quickActionDao: QuickActionDao,
) : ViewModel() {

    // ── Action history ──────────────────────────────────────────────────────

    val actions: StateFlow<List<QuickActionEntity>> = quickActionDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Loading / execution state ───────────────────────────────────────────

    sealed interface UiState {
        object Idle : UiState
        object LoadingModel : UiState
        object Executing : UiState
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** Last error message to show in the UI. Cleared on next action. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Executes a quick-action command.
     *
     * If FunctionGemma hasn't been loaded yet, it is initialised lazily first.
     * The result is persisted to the action history via Room.
     */
    fun executeAction(query: String) {
        if (query.isBlank()) return
        _error.value = null

        viewModelScope.launch {
            try {
                // 1. Lazy-load FunctionGemma if not yet ready
                if (!functionGemmaRouter.isReady.value) {
                    _uiState.value = UiState.LoadingModel
                    val modelPath = downloadManager.getModelPath(KernelModel.FUNCTION_GEMMA_270M)
                    if (modelPath == null) {
                        _error.value = "FunctionGemma model not downloaded. Please download it in Settings → Models."
                        _uiState.value = UiState.Idle
                        return@launch
                    }
                    Log.i(TAG, "ActionsViewModel: lazy-loading FunctionGemma from $modelPath")
                    functionGemmaRouter.initialize(modelPath)
                    if (!functionGemmaRouter.isReady.value) {
                        _error.value = "Failed to load the actions model. Try again."
                        _uiState.value = UiState.Idle
                        return@launch
                    }
                }

                // 2. Execute the command
                _uiState.value = UiState.Executing
                val result = functionGemmaRouter.handle(query)

                // 3. Persist result
                val entity = when (result) {
                    is FunctionGemmaRouter.HandleResult.ToolHandled -> QuickActionEntity(
                        userQuery = query,
                        skillName = extractSkillName(result.response),
                        resultText = result.response,
                        isSuccess = true,
                    )
                    is FunctionGemmaRouter.HandleResult.PlainResponse -> QuickActionEntity(
                        userQuery = query,
                        skillName = null,
                        resultText = result.response.ifEmpty { "No matching action found." },
                        isSuccess = result.response.isNotEmpty(),
                    )
                    is FunctionGemmaRouter.HandleResult.NotReady -> QuickActionEntity(
                        userQuery = query,
                        skillName = null,
                        resultText = "Actions model not ready.",
                        isSuccess = false,
                    )
                }
                quickActionDao.insert(entity)
            } catch (e: Exception) {
                Log.e(TAG, "ActionsViewModel: executeAction failed — ${e.message}", e)
                _error.value = e.message ?: "Unknown error"
                // Persist the failure
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
        // Do NOT release FunctionGemmaRouter here — it's a @Singleton shared with ChatViewModel.
        // The router's resources are managed at the application scope.
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    /**
     * Best-effort skill name extraction from the tool response.
     * FunctionGemma responses often start with the tool name or contain it.
     */
    private fun extractSkillName(response: String): String? {
        // The KernelAIToolSet logs the tool name; we can't access it from here.
        // Return null — the UI will show a generic action icon.
        return null
    }
}
