package com.kernel.ai.core.inference

import com.kernel.ai.core.inference.download.KernelModel

/** Static user-facing feature support for a model. Distinct from current user settings/state. */
data class ModelCapabilities(
    val supportsThinking: Boolean,
    val supportsImageInput: Boolean,
    val supportsAudioInput: Boolean,
    val supportsSpeculativeDecoding: Boolean,
) {
    val supportsAttachments: Boolean
        get() = supportsImageInput || supportsAudioInput
}

val KernelModel.capabilities: ModelCapabilities
    get() = when (this) {
        KernelModel.GEMMA_4_E2B,
        KernelModel.GEMMA_4_E4B,
        -> ModelCapabilities(
            supportsThinking = true,
            supportsImageInput = false,
            supportsAudioInput = false,
            supportsSpeculativeDecoding = true,
        )

        else -> ModelCapabilities(
            supportsThinking = false,
            supportsImageInput = false,
            supportsAudioInput = false,
            supportsSpeculativeDecoding = false,
        )
    }
