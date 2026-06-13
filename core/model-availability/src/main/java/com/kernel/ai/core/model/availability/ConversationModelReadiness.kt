package com.kernel.ai.core.model.availability

import com.kernel.ai.core.inference.download.DownloadState
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.hardware.HardwareTier

/**
 * Describes whether Chat has a conversation model it can use, centralised so that
 * both the Chat gate and Model Management surface the same logic.
 *
 * All device tiers require **at least one** conversation model to be downloaded
 * before Chat can open.  On [HardwareTier.FLAGSHIP], either GEMMA_4_E4B or
 * GEMMA_4_E2B satisfies this requirement, with E-4B treated as the recommended /
 * tier-preferred default.  On non‑flagship tiers only E-2B satisfies the requirement.
 */
sealed interface ConversationModelReadiness {

    /** At least one valid conversation model is installed. */
    data object Ready : ConversationModelReadiness

    /**
     * Only the smaller fallback model is installed; the tier-preferred model is missing.
     *
     * @property recommendedModel The tier-preferred conversation model that is not installed
     *   (e.g. E-4B on flagship).
     */
    data class FallbackActive(
        val recommendedModel: KernelModel,
    ) : ConversationModelReadiness

    /**
     * No conversation model is installed — user action is required.
     *
     * @property recommendedModel The model that should be downloaded first
     *   (tier-preferred default).
     * @property fallbackModel A fallback model that also satisfies Chat readiness
     *   (always GEMMA_4_E2B when present, same as recommendedModel on non-flagship).
     */
    data class ActionRequired(
        val recommendedModel: KernelModel,
        val fallbackModel: KernelModel = KernelModel.GEMMA_4_E2B,
    ) : ConversationModelReadiness

    companion object {
        /**
         * Computes [ConversationModelReadiness] for the current device [tier] and
         * per-model download states.
         *
         * @param tier Current device hardware tier.
         * @param downloadStates Current download states for all models.
         */
        fun compute(
            tier: HardwareTier,
            downloadStates: Map<KernelModel, DownloadState>,
        ): ConversationModelReadiness {
            val e4bDownloaded = downloadStates[KernelModel.GEMMA_4_E4B] is DownloadState.Downloaded
            val e2bDownloaded = downloadStates[KernelModel.GEMMA_4_E2B] is DownloadState.Downloaded

            return when (tier) {
                HardwareTier.FLAGSHIP -> when {
                    e4bDownloaded -> Ready
                    e2bDownloaded -> FallbackActive(recommendedModel = KernelModel.GEMMA_4_E4B)
                    else -> ActionRequired(
                        recommendedModel = KernelModel.GEMMA_4_E4B,
                        fallbackModel = KernelModel.GEMMA_4_E2B,
                    )
                }
                else -> when {
                    e2bDownloaded -> Ready
                    else -> ActionRequired(
                        recommendedModel = KernelModel.GEMMA_4_E2B,
                        fallbackModel = KernelModel.GEMMA_4_E2B,
                    )
                }
            }
        }
    }
}
