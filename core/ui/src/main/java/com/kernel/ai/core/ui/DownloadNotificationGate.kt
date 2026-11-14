package com.kernel.ai.core.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.kernel.ai.core.permissions.PermissionRequestStore
import com.kernel.ai.core.permissions.PermissionRoute
import com.kernel.ai.core.permissions.decidePermissionRoute
import com.kernel.ai.core.permissions.isSilentDenial
import com.kernel.ai.core.permissions.recordPermissionResult

private const val NOTIFICATION_PERMISSION = Manifest.permission.POST_NOTIFICATIONS

/**
 * Gates a user-started download on notification permission (#1855), using #1880's routing so the
 * denial history stays correct.
 *
 * - Granted: the download starts.
 * - Can ask in-app: a short reason is shown first, then the system prompt. The result is recorded with
 *   [recordPermissionResult].
 * - Permanently denied (Android will not ask again): the download starts without a dialog that could
 *   not lead anywhere.
 *
 * [onProceed] runs in every case except a "Not now" that is not asked again; downloads never wait on the
 * permission.
 *
 * Known gap: the reason dialog is held in `remember`, so a configuration change while it is showing
 * drops the waiting download. The user taps download again.
 */
@Composable
fun rememberDownloadNotificationGate(): (onProceed: () -> Unit) -> Unit {
    val context = LocalContext.current
    var rationale by remember { mutableStateOf<(() -> Unit)?>(null) }
    var awaitingResult by remember { mutableStateOf<(() -> Unit)?>(null) }
    var requestedAt by remember { mutableStateOf(0L) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val activity = context.findActivity()
        context.recordPermissionResult(
            permission = NOTIFICATION_PERMISSION,
            granted = granted,
            shouldShowRationale = activity?.shouldShowRequestPermissionRationale(NOTIFICATION_PERMISSION) == true,
            silentlyDenied = !granted && isSilentDenial(System.currentTimeMillis() - requestedAt),
        )
        val proceed = awaitingResult
        awaitingResult = null
        proceed?.invoke()
    }

    val pending = rationale
    if (pending != null) {
        AlertDialog(
            onDismissRequest = {
                rationale = null
                pending()
            },
            title = { Text("Show download progress?") },
            text = {
                Text(
                    "Jandal can show a notification while models download, so you can see progress " +
                        "from anywhere. Downloads continue either way.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    rationale = null
                    awaitingResult = pending
                    requestedAt = System.currentTimeMillis()
                    launcher.launch(NOTIFICATION_PERMISSION)
                }) { Text("Allow") }
            },
            dismissButton = {
                TextButton(onClick = {
                    rationale = null
                    pending()
                }) { Text("Not now") }
            },
        )
    }

    return remember(context) {
        { onProceed: () -> Unit ->
            val activity = context.findActivity()
            val route = decidePermissionRoute(
                isGranted = hasNotificationPermission(context),
                isSpecialAccess = false,
                wasDeniedBefore = PermissionRequestStore.from(context).wasDenied(NOTIFICATION_PERMISSION),
                shouldShowRationale = activity?.shouldShowRequestPermissionRationale(NOTIFICATION_PERMISSION) == true,
            )
            if (route == PermissionRoute.RequestInApp) rationale = onProceed else onProceed()
        }
    }
}

private fun hasNotificationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, NOTIFICATION_PERMISSION) == PackageManager.PERMISSION_GRANTED

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
