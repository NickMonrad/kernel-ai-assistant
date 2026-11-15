package com.kernel.ai.feature.chat

import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.model.availability.ConversationModelReadiness
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Debug-only Chats overrides, toggled from the Chats ⋮ menu. In memory and process-scoped; the toggles do nothing
 * unless [BuildConfig.DEBUG] is true. They let a device that already has chats show the first-run welcome and a
 * conversation-model download, for QA evidence.
 */
internal object ChatsDebugOverrides {
    private val _showWelcome = MutableStateFlow(false)
    private val _simulateDownload = MutableStateFlow(false)

    val showWelcome: StateFlow<Boolean> = _showWelcome.asStateFlow()
    val simulateDownload: StateFlow<Boolean> = _simulateDownload.asStateFlow()

    /** Readiness used while a download is simulated: the preferred model at 42%. */
    val SimulatedDownload: ConversationModelReadiness =
        ConversationModelReadiness.Preparing(KernelModel.GEMMA_4_E4B, 0.42f)

    fun toggleWelcome() {
        if (BuildConfig.DEBUG) _showWelcome.update { !it }
    }

    fun toggleDownload() {
        if (BuildConfig.DEBUG) _simulateDownload.update { !it }
    }
}
