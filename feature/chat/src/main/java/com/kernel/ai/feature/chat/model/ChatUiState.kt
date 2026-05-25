package com.kernel.ai.feature.chat.model

import com.kernel.ai.core.inference.ModelCapabilities
import com.kernel.ai.core.inference.download.DownloadState
import com.kernel.ai.core.inference.download.KernelModel

sealed interface ChatUiState {
    data object Loading : ChatUiState

    data class Ready(
        val conversationId: String,
        val conversationTitle: String?,
        val messages: List<ChatMessage>,
        val isGenerating: Boolean,
        val isSpeakingResponse: Boolean,
        val inputText: String,
        val error: String?,
        val isLoadingModel: Boolean = false,
        /** Whether to show the model's thinking process tokens in the chat UI. */
        val showThinkingProcess: Boolean = true,
        val modelCapabilities: ModelCapabilities? = null,
        /** Current model temperature (synced from ModelSettingsEntity). */
        val temperature: Float = 0.7f,
        /** Current model top-P (synced from ModelSettingsEntity). */
        val topP: Float = 0.9f,
        /** Current model top-K (synced from ModelSettingsEntity). */
        val topK: Int = 64,
        // ---- Visual customisation (#906) ----
        /** 0=small, 1=medium, 2=large */
        val fontSize: Int = 1,
        /** Bubble theme preset key, "system" = use dynamic colour */
        val bubbleTheme: String = "system",
        /** ARGB colour for user bubble background; null = dynamic colour */
        val bubbleThemeUserColor: Long? = null,
        /** ARGB colour for assistant bubble background; null = dynamic colour */
        val bubbleThemeAssistantColor: Long? = null,
        /** ARGB colour for user bubble text; null = system default */
        val userFontColor: Long? = null,
        /** ARGB colour for assistant bubble text; null = system default */
        val assistantFontColor: Long? = null,
        /** "none", "color", "image" */
        val wallpaperType: String = "none",
        /** ARGB colour when wallpaperType="color"; null = none */
        val wallpaperColor: Long? = null,
        /** Content URI string when wallpaperType="image"; null = none */
        val wallpaperImageUri: String? = null,
    ) : ChatUiState

    /** Models need to be downloaded before chatting. */
    data class ModelsNotReady(
        val isDownloading: Boolean,
        /** Per-model download progress, ordered by priority (required first). */
        val modelProgress: List<ModelDownloadProgress> = emptyList(),
    ) : ChatUiState

    data class ModelDownloadProgress(
        val model: KernelModel,
        val displayName: String,
        val sizeLabel: String,
        val state: DownloadState,
    )
}