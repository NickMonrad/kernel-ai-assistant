package com.kernel.ai.core.inference.download

import com.kernel.ai.core.inference.hardware.HardwareTier

/**
 * Tier-aware rules for which conversation model answers and what must be on disk before chat
 * can run. Pure functions over a presence predicate so the download manager, the Models screen
 * and the readiness UI share one definition.
 */
object ConversationModelRules {

    /** The conversation model this tier prefers when the user has not chosen one. */
    fun autoConversationModel(tier: HardwareTier): KernelModel =
        if (tier == HardwareTier.FLAGSHIP) KernelModel.GEMMA_4_E4B else KernelModel.GEMMA_4_E2B

    /** Models the user may pick as the chat brain on [tier]. E-4B needs flagship hardware. */
    fun selectableConversationModels(tier: HardwareTier): List<KernelModel> =
        if (tier == HardwareTier.FLAGSHIP) {
            listOf(KernelModel.GEMMA_4_E4B, KernelModel.GEMMA_4_E2B)
        } else {
            listOf(KernelModel.GEMMA_4_E2B)
        }

    /**
     * Chat can run. FLAGSHIP accepts either conversation model; every other tier needs E-2B
     * specifically, because the app loads E-2B as its always-on conversation model there.
     */
    fun conversationReady(tier: HardwareTier, isDownloaded: (KernelModel) -> Boolean): Boolean =
        when (tier) {
            HardwareTier.FLAGSHIP -> isDownloaded(KernelModel.GEMMA_4_E4B) ||
                isDownloaded(KernelModel.GEMMA_4_E2B)
            else -> isDownloaded(KernelModel.GEMMA_4_E2B)
        }

    /**
     * Everything the app needs before chat and memory work: a conversation model for [tier]
     * plus every other `isRequired` model.
     */
    fun appRequiredReady(tier: HardwareTier, isDownloaded: (KernelModel) -> Boolean): Boolean =
        conversationReady(tier, isDownloaded) &&
            KernelModel.entries
                .filter { it.isRequired && it != KernelModel.GEMMA_4_E2B }
                .all(isDownloaded)

    /**
     * The conversation model that answers: the user's pick when this tier can select it and it
     * is on disk, otherwise the tier default when on disk, otherwise E-2B. A saved pick the tier
     * cannot select (an old E-4B choice below flagship) is ignored. Callers check
     * [conversationReady] first.
     */
    fun effectiveConversationModel(
        userPreference: KernelModel?,
        tier: HardwareTier,
        isDownloaded: (KernelModel) -> Boolean,
    ): KernelModel {
        val selectable = selectableConversationModels(tier)
        if (userPreference != null && userPreference in selectable && isDownloaded(userPreference)) {
            return userPreference
        }
        val tierModel = autoConversationModel(tier)
        if (isDownloaded(tierModel)) return tierModel
        return KernelModel.GEMMA_4_E2B
    }
}
