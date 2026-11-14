package com.kernel.ai.core.model.availability

import com.kernel.ai.core.inference.download.ConversationModelRules
import com.kernel.ai.core.inference.download.DownloadState
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.hardware.HardwareTier

/**
 * A group of models that do one job. Each role says how many of its members must be on disk
 * for the role to work.
 */
enum class ModelRole(
    val members: List<KernelModel>,
    val requirement: RoleRequirement,
) {
    /** One conversation model is enough, subject to the tier rules in [ConversationModelRules]. */
    CHAT_BRAIN(
        members = listOf(KernelModel.GEMMA_4_E4B, KernelModel.GEMMA_4_E2B),
        requirement = RoleRequirement.ANY_ONE,
    ),

    /** Every required file is needed before memory search works. */
    MEMORY_SEARCH(
        members = listOf(KernelModel.ARCTIC_EMBED_M_V1_5, KernelModel.ARCTIC_EMBED_V1_5_VOCAB),
        requirement = RoleRequirement.ALL,
    ),

    /** Built-in intent classifier. Always available, never required to download. */
    QUICK_COMMANDS(
        members = listOf(KernelModel.MINI_LM),
        requirement = RoleRequirement.NONE,
    ),
}

enum class RoleRequirement { ANY_ONE, ALL, NONE }

/** Where a row sits on the screen. [MORE_MODELS] is only used by the chat brain role. */
enum class ModelGroup { IN_USE, OPTIONAL, MORE_MODELS, NOT_NEEDED }

enum class RoleStatus { READY, DOWNLOADING, MISSING }

/** One model inside a role section, with the decisions the UI needs already made. */
data class ModelRoleRow(
    val model: KernelModel,
    val state: DownloadState,
    val group: ModelGroup,
    /** Used for the role's "Using:" header and for the Delete-in-use guard. */
    val inUse: Boolean,
    /** Required for the role to work right now. Drives the Required tag. */
    val required: Boolean,
    /** Tier-recommended for this phone. Drives sort order and the recommended note. */
    val recommended: Boolean,
    /** Whether tapping the row chooses it. False when the tier cannot run this model. */
    val selectable: Boolean,
)

data class ModelRoleSection(
    val role: ModelRole,
    val status: RoleStatus,
    /** Model currently answering chat or serving memory search. Null when nothing is ready. */
    val using: KernelModel?,
    /** The chat brain preference, or null for automatic. Only meaningful for CHAT_BRAIN. */
    val preferred: KernelModel?,
    val rows: List<ModelRoleRow>,
)

/** One line of the "Still needed" card. */
data class StillNeededItem(
    val role: ModelRole,
    val status: RoleStatus,
    /** Progress 0..1 while [status] is DOWNLOADING, null if unknown. */
    val progress: Float?,
    /** Models the card's Download action starts, in order. Empty when nothing can be downloaded. */
    val downloadModels: List<KernelModel>,
)

/** Pure role rules. No Android or Compose dependencies, so this is unit-tested directly. */
object ModelRoles {

    /** Models that appear on the Models screen and in role sections. */
    fun visibleMembers(role: ModelRole): List<KernelModel> =
        role.members.filter { it.showInModelManagement && !it.isDeprecated }

    /** Tier-preferred chat model; the one used when the user has not chosen. */
    fun autoChatModel(tier: HardwareTier): KernelModel = ConversationModelRules.autoConversationModel(tier)

    /** True when chat can run on [tier] with the models in [states]. */
    fun chatReady(tier: HardwareTier, states: Map<KernelModel, DownloadState>): Boolean =
        ConversationModelRules.conversationReady(tier) { isReady(it, states) }

    /**
     * The chat model that answers right now, or null when chat cannot run. Mirrors the engine's
     * choice via [ConversationModelRules.effectiveConversationModel].
     */
    fun chatBrainUsing(
        preferred: KernelModel?,
        tier: HardwareTier,
        states: Map<KernelModel, DownloadState>,
    ): KernelModel? {
        if (!chatReady(tier, states)) return null
        return ConversationModelRules.effectiveConversationModel(preferred, tier) { isReady(it, states) }
    }

    fun isReady(model: KernelModel, states: Map<KernelModel, DownloadState>): Boolean =
        model.isBundled || states[model] is DownloadState.Downloaded

    fun roleStatus(role: ModelRole, states: Map<KernelModel, DownloadState>, tier: HardwareTier): RoleStatus {
        val members = visibleMembers(role)
        return when (role.requirement) {
            RoleRequirement.NONE -> RoleStatus.READY
            RoleRequirement.ANY_ONE -> when {
                chatReady(tier, states) -> RoleStatus.READY
                members.any { states[it] is DownloadState.Downloading } -> RoleStatus.DOWNLOADING
                else -> RoleStatus.MISSING
            }
            RoleRequirement.ALL -> when {
                members.all { isReady(it, states) } -> RoleStatus.READY
                members.any { states[it] is DownloadState.Downloading } -> RoleStatus.DOWNLOADING
                else -> RoleStatus.MISSING
            }
        }
    }

    /**
     * Whether deleting [model] would stop the app from working: chat or memory search would
     * fall below its requirement. Uses the same rule as the app's readiness gate, so it covers
     * E-2B on non-flagship tiers, memory files, and not E-2B when E-4B still serves flagship chat.
     */
    fun deletionBreaksApp(
        model: KernelModel,
        tier: HardwareTier,
        states: Map<KernelModel, DownloadState>,
    ): Boolean {
        if (model.isBundled || !isReady(model, states)) return false
        val before = ConversationModelRules.appRequiredReady(tier) { isReady(it, states) }
        val after = ConversationModelRules.appRequiredReady(tier) { it != model && isReady(it, states) }
        return before && !after
    }

    fun roleOf(model: KernelModel): ModelRole? =
        ModelRole.entries.firstOrNull { model in it.members }

    /** Sections for the Models screen, in display order. Roles with no visible members are skipped. */
    fun sections(
        preferred: KernelModel?,
        tier: HardwareTier,
        states: Map<KernelModel, DownloadState>,
    ): List<ModelRoleSection> = ModelRole.entries.mapNotNull { role ->
        val members = visibleMembers(role)
        if (members.isEmpty()) return@mapNotNull null
        val using = if (role == ModelRole.CHAT_BRAIN) {
            chatBrainUsing(preferred, tier, states)
        } else {
            members.firstOrNull { isReady(it, states) }
        }
        val rows = members.map { model ->
            rowFor(role, model, tier, states, using)
        }.sortedWith(
            compareBy<ModelRoleRow> { !isReady(it.model, states) }
                .thenBy { !it.recommended },
        )
        ModelRoleSection(
            role = role,
            status = roleStatus(role, states, tier),
            using = using,
            preferred = if (role == ModelRole.CHAT_BRAIN) preferred else null,
            rows = rows,
        )
    }

    private fun rowFor(
        role: ModelRole,
        model: KernelModel,
        tier: HardwareTier,
        states: Map<KernelModel, DownloadState>,
        using: KernelModel?,
    ): ModelRoleRow {
        val ready = isReady(model, states)
        val applicable = model.preferredForTier == null || model.preferredForTier == tier
        val recommended = model.preferredForTier == tier
        val inUse = when (role) {
            ModelRole.CHAT_BRAIN -> model == using
            ModelRole.MEMORY_SEARCH -> ready
            ModelRole.QUICK_COMMANDS -> ready
        }
        val required = when (role.requirement) {
            RoleRequirement.NONE -> false
            RoleRequirement.ALL -> model.isRequired
            // With nothing usable installed, the tier default is the download the role needs.
            RoleRequirement.ANY_ONE -> if (using == null) {
                model == autoChatModel(tier)
            } else {
                model == using && deletionBreaksApp(model, tier, states)
            }
        }
        val selectable = ready && (role != ModelRole.CHAT_BRAIN ||
            model in ConversationModelRules.selectableConversationModels(tier))
        val group = when {
            ready && inUse -> ModelGroup.IN_USE
            ready -> ModelGroup.OPTIONAL
            required -> ModelGroup.IN_USE
            role == ModelRole.CHAT_BRAIN && model == KernelModel.GEMMA_4_E2B &&
                using == KernelModel.GEMMA_4_E4B -> ModelGroup.MORE_MODELS
            !applicable -> ModelGroup.NOT_NEEDED
            else -> ModelGroup.OPTIONAL
        }
        return ModelRoleRow(
            model = model,
            state = states[model] ?: DownloadState.NotDownloaded,
            group = group,
            inUse = inUse,
            required = required,
            recommended = recommended,
            selectable = selectable,
        )
    }

    /** Lines for the "Still needed" card: every required role that is not READY. */
    fun stillNeeded(
        tier: HardwareTier,
        states: Map<KernelModel, DownloadState>,
    ): List<StillNeededItem> = ModelRole.entries
        .filter { it.requirement != RoleRequirement.NONE }
        .mapNotNull { role ->
            val status = roleStatus(role, states, tier)
            if (status == RoleStatus.READY) return@mapNotNull null
            val members = visibleMembers(role)
            val downloading = members.firstOrNull { states[it] is DownloadState.Downloading }
            val downloadModels = when (role.requirement) {
                RoleRequirement.ANY_ONE -> listOf(autoChatModel(tier)).filter { it in members }
                RoleRequirement.ALL -> members.filter { !isReady(it, states) && it.isRequired }
                RoleRequirement.NONE -> emptyList()
            }
            StillNeededItem(
                role = role,
                status = status,
                progress = (downloading?.let { states[it] } as? DownloadState.Downloading)?.progress,
                downloadModels = downloadModels,
            )
        }
}
