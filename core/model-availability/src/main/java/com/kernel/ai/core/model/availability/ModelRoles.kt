package com.kernel.ai.core.model.availability

import com.kernel.ai.core.inference.download.DownloadState
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.hardware.HardwareTier

/**
 * A job the user needs a model for. The Models screen, the setup wizard (#1855) and the
 * welcome (#1849) all group models by role, so the grouping lives here, not in any screen.
 */
enum class ModelRole(
    val members: List<KernelModel>,
    val requirement: RoleRequirement,
) {
    /** One conversation model is enough. The tier decides which one is preferred. */
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
    /** Used for the role's "Using:" header and for "Delete would break" checks. */
    val inUse: Boolean,
    /** Required for the role to work right now. Drives the Required tag. */
    val required: Boolean,
    /** Tier-recommended for this phone. Drives sort order and the recommended note. */
    val recommended: Boolean,
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
    /** Model the card's Download action starts. Null when nothing is missing. */
    val downloadModel: KernelModel?,
)

/** Pure role rules. No Android or Compose dependencies, so this is unit-tested directly. */
object ModelRoles {

    /** Models that appear on the Models screen and in role sections. */
    fun visibleMembers(role: ModelRole): List<KernelModel> =
        role.members.filter { it.showInModelManagement && !it.isDeprecated }

    /** Tier-preferred chat model; the one used when the user has not chosen. */
    fun autoChatModel(tier: HardwareTier): KernelModel =
        if (tier == HardwareTier.FLAGSHIP) KernelModel.GEMMA_4_E4B else KernelModel.GEMMA_4_E2B

    /**
     * The chat model that answers right now: the user's choice if it is installed, otherwise
     * the tier default if installed, otherwise the other chat model if installed, otherwise null.
     */
    fun chatBrainUsing(
        preferred: KernelModel?,
        tier: HardwareTier,
        states: Map<KernelModel, DownloadState>,
    ): KernelModel? {
        val wanted = preferred ?: autoChatModel(tier)
        val other = if (wanted == KernelModel.GEMMA_4_E4B) KernelModel.GEMMA_4_E2B else KernelModel.GEMMA_4_E4B
        return when {
            isReady(wanted, states) -> wanted
            isReady(autoChatModel(tier), states) -> autoChatModel(tier)
            isReady(other, states) -> other
            else -> null
        }
    }

    fun isReady(model: KernelModel, states: Map<KernelModel, DownloadState>): Boolean =
        model.isBundled || states[model] is DownloadState.Downloaded

    fun roleStatus(role: ModelRole, states: Map<KernelModel, DownloadState>, tier: HardwareTier): RoleStatus {
        val members = visibleMembers(role)
        return when (role.requirement) {
            RoleRequirement.NONE -> RoleStatus.READY
            RoleRequirement.ANY_ONE -> when {
                chatBrainUsing(null, tier, states) != null -> RoleStatus.READY
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
     * Whether deleting [model] would leave its role unable to work. The screen asks for
     * confirmation first when this is true.
     */
    fun deletionBreaksRole(
        model: KernelModel,
        states: Map<KernelModel, DownloadState>,
    ): Boolean {
        val role = roleOf(model) ?: return false
        if (!isReady(model, states)) return false
        return when (role.requirement) {
            RoleRequirement.NONE -> false
            RoleRequirement.ALL -> model.isRequired
            RoleRequirement.ANY_ONE -> role.members
                .filter { it != model }
                .none { isReady(it, states) }
        }
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
            // With nothing installed, the tier default is the download the role needs.
            RoleRequirement.ANY_ONE -> if (using == null) {
                model == autoChatModel(tier)
            } else {
                model == using && deletionBreaksRole(model, states)
            }
        }
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
            val downloadModel = when (role.requirement) {
                RoleRequirement.ANY_ONE -> autoChatModel(tier).takeIf { it in members }
                    ?: members.first()
                RoleRequirement.ALL -> members.firstOrNull { !isReady(it, states) && it.isRequired }
                RoleRequirement.NONE -> null
            }
            StillNeededItem(
                role = role,
                status = status,
                progress = (downloading?.let { states[it] } as? DownloadState.Downloading)?.progress,
                downloadModel = downloadModel,
            )
        }
}
