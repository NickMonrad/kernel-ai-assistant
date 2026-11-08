package com.kernel.ai.core.permissions

import androidx.annotation.StringRes

/**
 * Represents the state of a voice permission prompt across different entry points.
 *
 * [Missing] — microphone permission is not granted; user should be prompted to grant.
 * [Denied] — user denied the permission once; retry with rationale.
 * [PermanentlyDenied] — user denied without "never ask again"; redirect to settings.
 * [Granted] — microphone permission is already granted; no prompt needed.
 */
sealed class VoicePermissionPromptState {
    object Missing : VoicePermissionPromptState()
    object Denied : VoicePermissionPromptState()
    object PermanentlyDenied : VoicePermissionPromptState()
    object Granted : VoicePermissionPromptState()
}

/**
 * Configuration for a voice permission prompt, including localized copy and
 * the target permission state.
 *
 * Each entry point (chat, actions, widget) can request its own config from
 * [VoicePermissionPromptFactory], ensuring consistent messaging while allowing
 * context-specific titles and descriptions.
 */
data class VoicePermissionPromptConfig(
    val state: VoicePermissionPromptState,
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    @StringRes val positiveButtonRes: Int,
    @StringRes val negativeButtonRes: Int? = null,
    @StringRes val postButtonRes: Int? = null,
)

/**
 * Entry point identifiers for voice permission prompts.
 *
 * Used by [VoicePermissionPromptFactory] to select the appropriate copy
 * for the context in which the voice feature was invoked.
 */
enum class VoicePermissionEntryPoint {
    /** Push-to-talk or voice loop from ChatScreen. */
    CHAT_VOICE,

    /** Voice command from ActionsScreen. */
    ACTIONS_VOICE,

    /** Voice command from the widget (VoiceCommandActivity). */
    WIDGET_VOICE,

    /** Voice settings screen (VoiceScreen). */
    VOICE_SETTINGS,
}
