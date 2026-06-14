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
 *
 * ## State machine
 *
 * |Tier|Download states|Readiness|
 * |---|---|---|
 * |Flagship|E-4B downloaded|`Ready`|
 * |Flagship|E-4B not downloaded, E-2B downloaded|`FallbackActive`|
 * |Flagship|E-2B downloaded, E-4B downloading|`FallbackPreparing` (E-4B downloading)|
 * |Flagship|E-4B downloading|`Preparing` (E-4B)|
 * |Flagship|E-4B missing, E-2B downloading|`Preparing` (E-2B)|
 * |Flagship|Neither downloaded nor downloading|`ActionRequired`|
 * |Non-flagship|E-2B downloaded|`Ready`|
 * |Non-flagship|E-2B downloading|`Preparing` (E-2B)|
 * |Non-flagship|E-2B not downloaded, not downloading|`ActionRequired`|
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
     * The fallback model is installed, but the tier-preferred model is also
     * being downloaded — no redundant download CTA should be shown.
     *
     * @property recommendedModel The tier-preferred conversation model that is downloading
     *   (e.g. E-4B on flagship).
     * @property fallbackModel The model that is fully installed and usable
     *   (always GEMMA_4_E2B on flagship).
     * @property downloadingModel The model being downloaded (always the recommended model).
     * @property progress 0.0–1.0 completion fraction, or null if unknown.
     */
    data class FallbackPreparing(
        val recommendedModel: KernelModel,
        val fallbackModel: KernelModel = KernelModel.GEMMA_4_E2B,
        val downloadingModel: KernelModel,
        val progress: Float?,
    ) : ConversationModelReadiness

    /**
     * A conversation model is currently being downloaded — no user action is required.
     *
     * @property downloadingModel The model being downloaded (E-4B or E-2B).
     * @property progress 0.0–1.0 completion fraction, or null if unknown.
     */
    data class Preparing(
        val downloadingModel: KernelModel,
        val progress: Float?,
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
         * Priority order:
         * 1. E-4B installed → `Ready`
         * 2. E-2B installed + E-4B downloading → `FallbackPreparing`
         * 3. E-2B installed (fallback only) → `FallbackActive`
         * 4. A valid model is actively downloading → `Preparing`
         * 5. Nothing available → `ActionRequired`
         *
         * @param tier Current device hardware tier.
         * @param downloadStates Current download states for all models.
         */
        fun compute(
            tier: HardwareTier,
            downloadStates: Map<KernelModel, DownloadState>,
        ): ConversationModelReadiness {
            val e4bState = downloadStates[KernelModel.GEMMA_4_E4B]
            val e2bState = downloadStates[KernelModel.GEMMA_4_E2B]

            val e4bDownloaded = e4bState is DownloadState.Downloaded
            val e2bDownloaded = e2bState is DownloadState.Downloaded
            val e4bDownloading = e4bState is DownloadState.Downloading
            val e2bDownloading = e2bState is DownloadState.Downloading

            return when (tier) {
                HardwareTier.FLAGSHIP -> when {
                    e4bDownloaded -> Ready
                    e2bDownloaded && e4bDownloading -> FallbackPreparing(
                        recommendedModel = KernelModel.GEMMA_4_E4B,
                        fallbackModel = KernelModel.GEMMA_4_E2B,
                        downloadingModel = KernelModel.GEMMA_4_E4B,
                        progress = e4bState.progress,
                    )
                    e2bDownloaded -> FallbackActive(recommendedModel = KernelModel.GEMMA_4_E4B)
                    e4bDownloading -> Preparing(
                        downloadingModel = KernelModel.GEMMA_4_E4B,
                        progress = e4bState.progress,
                    )
                    e2bDownloading -> Preparing(
                        downloadingModel = KernelModel.GEMMA_4_E2B,
                        progress = e2bState.progress,
                    )
                    else -> ActionRequired(
                        recommendedModel = KernelModel.GEMMA_4_E4B,
                        fallbackModel = KernelModel.GEMMA_4_E2B,
                    )
                }
                else -> when {
                    e2bDownloaded -> Ready
                    e2bDownloading -> Preparing(
                        downloadingModel = KernelModel.GEMMA_4_E2B,
                        progress = e2bState.progress,
                    )
                    else -> ActionRequired(
                        recommendedModel = KernelModel.GEMMA_4_E2B,
                        fallbackModel = KernelModel.GEMMA_4_E2B,
                    )
                }
            }
        }
    }
}
