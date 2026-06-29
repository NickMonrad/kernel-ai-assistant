package com.kernel.ai.core.ui.permissions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.kernel.ai.core.permissions.R

/**
 * Composable that renders a default-assistant role setup prompt.
 *
 * This is distinct from [VoicePermissionPrompt] which handles microphone permission.
 * The default-assistant role is an Android [android.content.pm.RoleManager] concept
 * that determines which app responds to the system-wide wake word.
 *
 * @param onGrant Called when the user taps "Set as default".
 * @param onCancel Called when the user taps "Not now" or dismisses the dialog.
 * @param dialogTestTag Test tag for the dialog surface.
 */
@Composable
fun DefaultAssistantPrompt(
    onGrant: () -> Unit,
    onCancel: () -> Unit,
    dialogTestTag: String = "default_assistant_prompt",
) {
    val title = stringResource(R.string.default_assistant_setup_title)
    val body = stringResource(R.string.default_assistant_setup_description)
    val grantLabel = stringResource(R.string.default_assistant_setup_grant)
    val cancelLabel = stringResource(R.string.default_assistant_setup_cancel)

    val actions = remember(onGrant, onCancel, grantLabel, cancelLabel) {
        listOf(
            PermissionDialogAction(
                label = grantLabel,
                testTag = "default_assistant_setup_grant",
                onClick = onGrant,
                isPrimary = true,
            ),
            PermissionDialogAction(
                label = cancelLabel,
                testTag = "default_assistant_setup_cancel",
                onClick = onCancel,
                isPrimary = false,
            ),
        )
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
 * Composable that renders a success state after default-assistant role is granted.
 *
 * @param onOk Called when the user taps "OK".
 * @param dialogTestTag Test tag for the dialog surface.
 */
@Composable
fun DefaultAssistantPromptSuccess(
    onOk: () -> Unit,
    dialogTestTag: String = "default_assistant_success",
) {
    val title = stringResource(R.string.default_assistant_setup_success_title)
    val body = stringResource(R.string.default_assistant_setup_success_description)
    val okLabel = stringResource(R.string.default_assistant_setup_success_ok)

    val actions = remember(onOk, okLabel) {
        listOf(
            PermissionDialogAction(
                label = okLabel,
                testTag = "default_assistant_setup_success_ok",
                onClick = onOk,
                isPrimary = true,
            ),
        )
    }

    PermissionOverlayDialog(
        title = title,
        body = body,
        actions = actions,
        dialogTestTag = dialogTestTag,
        onDismissRequest = onOk,
    )
}
