package com.kernel.ai.core.ui.permissions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.kernel.ai.core.permissions.VoicePermissionPromptConfig
import com.kernel.ai.core.permissions.VoicePermissionPromptState

/**
 * Composable that renders a voice permission overlay dialog.
 *
 * This is the shared UI component used by ChatScreen, ActionsScreen,
 * VoiceCommandActivity, and VoiceScreen to prompt for microphone permission.
 *
 * Each caller is responsible for:
 * - Obtaining the [VoicePermissionPromptConfig] via [com.kernel.ai.core.permissions.VoicePermissionPromptFactory]
 * - Handling the permission result (launching the system permission dialog or settings)
 * - Notifying the ViewModel of the result
 *
 * @param config The prompt configuration (title, description, button labels).
 * @param onGrant Called when the user taps the primary "grant" action.
 * @param onRetry Called when the user taps the "retry" action (denied once).
 * @param onOpenSettings Called when the user taps "Open Settings" (permanently denied).
 * @param onCancel Called when the user cancels or dismisses the dialog.
 * @param dialogTestTag Test tag for the dialog surface.
 */
@Composable
fun VoicePermissionPrompt(
    config: VoicePermissionPromptConfig,
    onGrant: () -> Unit,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onCancel: () -> Unit,
    dialogTestTag: String = "voice_permission_dialog",
) {
    val title = androidx.compose.ui.res.stringResource(config.titleRes)
    val body = androidx.compose.ui.res.stringResource(config.descriptionRes)

    val actions = remember(config) {
        buildPromptActions(config, onGrant, onRetry, onOpenSettings, onCancel)
    }

    PermissionOverlayDialog(
        title = title,
        body = body,
        actions = actions,
        dialogTestTag = dialogTestTag,
        onDismissRequest = onCancel,
    )
}

/**
 * Build the action list for a voice permission prompt based on the config state.
 */
private fun buildPromptActions(
    config: VoicePermissionPromptConfig,
    onGrant: () -> Unit,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onCancel: () -> Unit,
): List<PermissionDialogAction> {
    return when (config.state) {
        VoicePermissionPromptState.Missing -> listOf(
            PermissionDialogAction(
                label = "Grant",
                testTag = "voice_permission_grant",
                onClick = onGrant,
                isPrimary = true,
            ),
            PermissionDialogAction(
                label = "Cancel",
                testTag = "voice_permission_cancel",
                onClick = onCancel,
                isPrimary = false,
            ),
        )
        VoicePermissionPromptState.Denied -> listOf(
            PermissionDialogAction(
                label = "Retry",
                testTag = "voice_permission_retry",
                onClick = onRetry,
                isPrimary = true,
            ),
            PermissionDialogAction(
                label = "Cancel",
                testTag = "voice_permission_cancel",
                onClick = onCancel,
                isPrimary = false,
            ),
        )
        VoicePermissionPromptState.PermanentlyDenied -> listOf(
            PermissionDialogAction(
                label = "Open Settings",
                testTag = "voice_permission_open_settings",
                onClick = onOpenSettings,
                isPrimary = true,
            ),
            PermissionDialogAction(
                label = "Cancel",
                testTag = "voice_permission_cancel",
                onClick = onCancel,
                isPrimary = false,
            ),
        )
        VoicePermissionPromptState.Granted -> listOf(
            PermissionDialogAction(
                label = "OK",
                testTag = "voice_permission_ok",
                onClick = onCancel,
                isPrimary = true,
            ),
        )
        else -> listOf(
            PermissionDialogAction(
                label = "OK",
                testTag = "voice_permission_ok",
                onClick = onCancel,
                isPrimary = true,
            ),
        )
    }
}
