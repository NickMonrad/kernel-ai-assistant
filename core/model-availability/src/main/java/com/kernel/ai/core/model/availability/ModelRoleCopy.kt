package com.kernel.ai.core.model.availability

import com.kernel.ai.core.inference.download.KernelModel

/**
 * Every user-facing string for role-based model lists. The Models screen, the setup wizard
 * (#1855) and the welcome (#1849) read from here so the wording stays identical.
 *
 * Technical names (`KernelModel.displayName`) are shown as secondary text, never as the
 * headline.
 */
object ModelRoleCopy {

    // ── Role titles and notes ────────────────────────────────────────────────
    fun roleTitle(role: ModelRole): String = when (role) {
        ModelRole.CHAT_BRAIN -> "Chat brain"
        ModelRole.MEMORY_SEARCH -> "Memory search"
        ModelRole.QUICK_COMMANDS -> "Quick commands"
    }

    /** One line under the role title. */
    fun roleSummary(role: ModelRole): String = when (role) {
        ModelRole.CHAT_BRAIN -> "Jandal needs one of these to chat."
        ModelRole.MEMORY_SEARCH -> "Required. Jandal can't remember things without it."
        ModelRole.QUICK_COMMANDS -> "Built in. Nothing to download."
    }

    /** "Using: <model>" header line. Null when nothing is installed for the role. */
    fun usingLine(using: KernelModel?): String =
        if (using == null) "Using: nothing yet" else "Using: ${using.displayName}"

    /** Note under the "Using" line explaining why that model was picked. */
    fun usingNote(
        role: ModelRole,
        using: KernelModel?,
        preferred: KernelModel?,
        recommendedForTier: KernelModel?,
    ): String? = when {
        using == null -> if (role == ModelRole.CHAT_BRAIN) "Not downloaded yet" else null
        role != ModelRole.CHAT_BRAIN -> null
        preferred != null && preferred == using -> "Chosen by you"
        preferred != null -> "Your choice isn't available on this phone right now"
        using == recommendedForTier -> RECOMMENDED_NOTE
        else -> "Chosen automatically"
    }

    // ── Per-model purpose (headline) and technical name (secondary) ──────────
    fun purpose(model: KernelModel): String = when (model) {
        KernelModel.GEMMA_4_E4B -> "Answers your questions and chats, with more detail."
        KernelModel.GEMMA_4_E2B -> "Fast chat model that runs on any supported phone."
        KernelModel.ARCTIC_EMBED_M_V1_5 -> "Helps Jandal remember and find things you've told it."
        KernelModel.ARCTIC_EMBED_V1_5_VOCAB -> "Vocabulary that memory search needs alongside Arctic Embed."
        KernelModel.MINI_LM -> "Recognises common requests like alarms and timers, offline."
        else -> model.displayName
    }

    /** Secondary text: technical name and size. */
    fun technicalLine(model: KernelModel): String =
        "${model.displayName} · ${sizeLabel(model.approxSizeBytes)}"

    fun sizeLabel(bytes: Long): String =
        if (bytes >= 1_000_000_000L) "%.1f GB".format(bytes / 1_000_000_000f)
        else "%.0f MB".format(bytes / 1_000_000f)

    // ── Row tags and notes ───────────────────────────────────────────────────
    const val REQUIRED_TAG = "Required"
    const val RECOMMENDED_NOTE = "Recommended for this phone"
    const val AUTO_CHOICE_TITLE = "Choose automatically"
    const val AUTO_CHOICE_SUPPORTING = "Best chat model for this phone"

    // ── Section group labels ────────────────────────────────────────────────
    fun groupTitle(group: ModelGroup): String? = when (group) {
        ModelGroup.IN_USE -> "In use"
        ModelGroup.OPTIONAL -> "Optional"
        ModelGroup.NOT_NEEDED -> "Not needed on this phone"
        ModelGroup.MORE_MODELS -> null // rendered as the collapsible "More models" row
    }

    const val MORE_MODELS_TITLE = "More models"
    const val HIDE_MORE_MODELS = "Hide more models"
    fun moreModelsCount(count: Int): String = if (count == 1) "1 more model" else "$count more models"

    // ── Row actions ─────────────────────────────────────────────────────────
    const val UPDATE_ACTION = "Update"
    const val DOWNLOAD_ACTION = "Download"
    const val CANCEL_ACTION = "Cancel"
    const val DELETE_ACTION = "Delete"

    // ── Delete confirmation ─────────────────────────────────────────────────
    const val DELETE_WARNING_TITLE = "Delete a required model?"
    fun deleteWarningBody(model: KernelModel): String =
        "Jandal needs ${model.displayName} to work. Until it is downloaded again, chat or memory search may stop."

    // ── Still needed card ───────────────────────────────────────────────────
    const val STILL_NEEDED_TITLE = "Still needed"
    const val ALL_SET_TEXT = "All set: Jandal has everything it needs"

    fun stillNeededLabel(item: StillNeededItem): String {
        val name = roleTitle(item.role)
        return when (item.status) {
            RoleStatus.READY -> "$name: ready"
            RoleStatus.MISSING -> "$name: not downloaded"
            RoleStatus.DOWNLOADING -> item.progress
                ?.takeIf { it > 0f }
                ?.let { "$name: downloading ${(it * 100).toInt()}%" }
                ?: "$name: downloading"
        }
    }

    // ── Voice sections (read-only; managed in Voice settings) ──────────────
    const val STT_SECTION_TITLE = "Speech recognition (Zipformer): turns your voice into text, offline"
    const val TTS_SECTION_TITLE = "Voice output: reads replies aloud"
    const val MANAGE_VOICE_ACTION = "Manage in Voice settings"
    const val VOICE_READY = "Ready"
    const val VOICE_DOWNLOADING = "Downloading"
    const val VOICE_NOT_DOWNLOADED = "Not downloaded"
    const val VOICE_BUILT_IN = "Using Android's built-in voice"
    const val VOICE_OTHER_ENGINE = "Another voice is selected in Voice settings"
    const val NOT_ON_THIS_PHONE = "Not available on this phone"
}
