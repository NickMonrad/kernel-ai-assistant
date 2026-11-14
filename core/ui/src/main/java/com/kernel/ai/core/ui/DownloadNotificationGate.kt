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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
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
 * Returns a request function that takes the pending download's id (a model name, or null for the
 * wizard's Finish). [onProceed] receives that id once the download may start:
 * - Granted, or Android will not ask again: immediately, with no dialog.
 * - Can ask in-app: after a short reason and the system prompt. Dismissing the reason also proceeds.
 *
 * The id, the reason-dialog state and the request time are saved with `rememberSaveable`, so a
 * configuration change during the reason dialog or the system prompt keeps the download.
 * Downloads never wait on the permission result.
 */
@Composable
fun rememberDownloadNotificationGate(onProceed: (pendingId: String?) -> Unit): (pendingId: String?) -> Unit {
    val context = LocalContext.current
    val latestOnProceed by rememberUpdatedState(onProceed)
    var pendingId by rememberSaveable { mutableStateOf<String?>(null) }
    var showReason by rememberSaveable { mutableStateOf(false) }
    var requestedAt by rememberSaveable { mutableLongStateOf(0L) }

    fun proceed() {
        val id = pendingId
        pendingId = null
        latestOnProceed(id)
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val activity = context.findActivity()
        context.recordPermissionResult(
            permission = NOTIFICATION_PERMISSION,
            granted = granted,
            shouldShowRationale = activity?.shouldShowRequestPermissionRationale(NOTIFICATION_PERMISSION) == true,
            silentlyDenied = !granted && isSilentDenial(System.currentTimeMillis() - requestedAt),
        )
        proceed()
    }

    if (showReason) {
        AlertDialog(
            onDismissRequest = {
                showReason = false
                proceed()
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
                    showReason = false
                    requestedAt = System.currentTimeMillis()
                    launcher.launch(NOTIFICATION_PERMISSION)
                }) { Text("Allow") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showReason = false
                    proceed()
                }) { Text("Not now") }
            },
        )
    }

    return remember(context) {
        { id: String? ->
            val activity = context.findActivity()
            val route = decidePermissionRoute(
                isGranted = hasNotificationPermission(context),
                isSpecialAccess = false,
                wasDeniedBefore = PermissionRequestStore.from(context).wasDenied(NOTIFICATION_PERMISSION),
                shouldShowRationale = activity?.shouldShowRequestPermissionRationale(NOTIFICATION_PERMISSION) == true,
            )
            if (route == PermissionRoute.RequestInApp) {
                pendingId = id
                showReason = true
            } else {
                latestOnProceed(id)
            }
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
