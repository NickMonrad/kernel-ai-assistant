package com.kernel.ai.core.ui

import android.Manifest
import android.content.Context
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

/**
 * Gates a user-started download on notification permission (#1855).
 *
 * Progress notifications need POST_NOTIFICATIONS on API 33+, and nothing else requests it until
 * part 3 of #1855. The returned gate shows a short reason first, then asks; [onProceed] runs once
 * the user answers either way, so the download is never blocked by the permission.
 */
@Composable
fun rememberDownloadNotificationGate(): (onProceed: () -> Unit) -> Unit {
    val context = LocalContext.current
    var rationale by remember { mutableStateOf<(() -> Unit)?>(null) }
    var awaitingResult by remember { mutableStateOf<(() -> Unit)?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
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
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
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
            if (hasNotificationPermission(context)) onProceed() else rationale = onProceed
        }
    }
}

private fun hasNotificationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
