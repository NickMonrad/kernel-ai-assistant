package com.kernel.ai.feature.chat

import androidx.compose.runtime.Composable
import com.kernel.ai.core.skills.SettingsAction
import com.kernel.ai.core.skills.SettingsDeepLink
import com.kernel.ai.core.ui.permissions.PermissionDialogAction
import com.kernel.ai.core.ui.permissions.PermissionOverlayDialog

/** Chooser state for a failed lookup's in-app recovery actions (#1859). Shared by Chat and Quick Actions. */
data class SettingsActionState(
    val message: String,
    val actions: List<SettingsAction>,
)

/**
 * Chooser for a failed lookup's recovery actions. [onSelect] receives the chosen deep link; the
 * caller decides whether to dismiss. Nothing is called or sent from here.
 */
@Composable
internal fun SettingsActionChooserDialog(
    state: SettingsActionState,
    onSelect: (SettingsDeepLink) -> Unit,
    onDismiss: () -> Unit,
) {
    PermissionOverlayDialog(
        title = state.message,
        body = "Choose what to do next.",
        actions = state.actions.mapIndexed { index, action ->
            PermissionDialogAction(
                label = action.label,
                testTag = "settings_action_$index",
                onClick = { onSelect(action.deepLink) },
                isPrimary = index == 0,
            )
        } + PermissionDialogAction(
            label = "Not now",
            testTag = "settings_action_not_now",
            onClick = onDismiss,
        ),
        dialogTestTag = "settings_action_dialog",
        onDismissRequest = onDismiss,
    )
}
