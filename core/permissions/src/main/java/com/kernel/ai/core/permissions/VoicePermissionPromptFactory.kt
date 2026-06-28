package com.kernel.ai.core.permissions

import androidx.annotation.StringRes

/**
 * Factory that produces [VoicePermissionPromptConfig] for different entry points
 * and permission states.
 *
 * This factory lives in core/permissions and returns raw resource IDs.
 * Callers (ChatScreen, ActionsScreen, etc.) resolve strings via Compose's
 * [androidx.compose.ui.res.stringResource] before passing to PermissionOverlayDialog.
 *
 * Centralizes all prompt copy so that ChatScreen, ActionsScreen, and
 * VoiceCommandActivity share the same messaging. When new strings are added,
 * they are defined in one place.
 */
object VoicePermissionPromptFactory {

    /**
     * Build a [VoicePermissionPromptConfig] for the given [entryPoint] and [state].
     *
     * Returns resource IDs — the caller resolves them via Compose's stringResource.
     */
    fun create(
        entryPoint: VoicePermissionEntryPoint,
        state: VoicePermissionPromptState,
    ): VoicePermissionPromptConfig {
        return when (state) {
            VoicePermissionPromptState.Missing -> buildMissingPrompt(entryPoint)
            VoicePermissionPromptState.Denied -> buildDeniedPrompt(entryPoint)
            VoicePermissionPromptState.PermanentlyDenied -> buildPermanentlyDeniedPrompt(entryPoint)
            VoicePermissionPromptState.Granted -> buildGrantedConfig()
        }
    }

    private fun buildMissingPrompt(entryPoint: VoicePermissionEntryPoint): VoicePermissionPromptConfig {
        val (title, description) = when (entryPoint) {
            VoicePermissionEntryPoint.CHAT_VOICE -> Pair(
                R.string.voice_permission_chat_title,
                R.string.voice_permission_chat_description,
            )
            VoicePermissionEntryPoint.ACTIONS_VOICE -> Pair(
                R.string.voice_permission_actions_title,
                R.string.voice_permission_actions_description,
            )
            VoicePermissionEntryPoint.WIDGET_VOICE -> Pair(
                R.string.voice_permission_widget_title,
                R.string.voice_permission_widget_description,
            )
            VoicePermissionEntryPoint.VOICE_SETTINGS -> Pair(
                R.string.voice_permission_settings_title,
                R.string.voice_permission_settings_description,
            )
        }

        return VoicePermissionPromptConfig(
            state = VoicePermissionPromptState.Missing,
            titleRes = title,
            descriptionRes = description,
            positiveButtonRes = R.string.voice_permission_grant,
            negativeButtonRes = R.string.voice_permission_cancel,
        )
    }

    private fun buildDeniedPrompt(entryPoint: VoicePermissionEntryPoint): VoicePermissionPromptConfig {
        val (title, description) = when (entryPoint) {
            VoicePermissionEntryPoint.CHAT_VOICE -> Pair(
                R.string.voice_permission_denied_chat_title,
                R.string.voice_permission_denied_chat_description,
            )
            VoicePermissionEntryPoint.ACTIONS_VOICE -> Pair(
                R.string.voice_permission_denied_actions_title,
                R.string.voice_permission_denied_actions_description,
            )
            VoicePermissionEntryPoint.WIDGET_VOICE -> Pair(
                R.string.voice_permission_denied_widget_title,
                R.string.voice_permission_denied_widget_description,
            )
            VoicePermissionEntryPoint.VOICE_SETTINGS -> Pair(
                R.string.voice_permission_denied_settings_title,
                R.string.voice_permission_denied_settings_description,
            )
        }

        return VoicePermissionPromptConfig(
            state = VoicePermissionPromptState.Denied,
            titleRes = title,
            descriptionRes = description,
            positiveButtonRes = R.string.voice_permission_retry,
            negativeButtonRes = R.string.voice_permission_cancel,
        )
    }

    private fun buildPermanentlyDeniedPrompt(entryPoint: VoicePermissionEntryPoint): VoicePermissionPromptConfig {
        val (title, description) = when (entryPoint) {
            VoicePermissionEntryPoint.CHAT_VOICE -> Pair(
                R.string.voice_permission_permanently_denied_chat_title,
                R.string.voice_permission_permanently_denied_chat_description,
            )
            VoicePermissionEntryPoint.ACTIONS_VOICE -> Pair(
                R.string.voice_permission_permanently_denied_actions_title,
                R.string.voice_permission_permanently_denied_actions_description,
            )
            VoicePermissionEntryPoint.WIDGET_VOICE -> Pair(
                R.string.voice_permission_permanently_denied_widget_title,
                R.string.voice_permission_permanently_denied_widget_description,
            )
            VoicePermissionEntryPoint.VOICE_SETTINGS -> Pair(
                R.string.voice_permission_permanently_denied_settings_title,
                R.string.voice_permission_permanently_denied_settings_description,
            )
        }

        return VoicePermissionPromptConfig(
            state = VoicePermissionPromptState.PermanentlyDenied,
            titleRes = title,
            descriptionRes = description,
            positiveButtonRes = R.string.voice_permission_open_settings,
            negativeButtonRes = R.string.voice_permission_cancel,
        )
    }

    private fun buildGrantedConfig(): VoicePermissionPromptConfig {
        return VoicePermissionPromptConfig(
            state = VoicePermissionPromptState.Granted,
            titleRes = R.string.voice_permission_granted_title,
            descriptionRes = R.string.voice_permission_granted_description,
            positiveButtonRes = R.string.voice_permission_ok,
        )
    }
}
