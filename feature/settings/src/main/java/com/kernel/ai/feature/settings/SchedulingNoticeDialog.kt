package com.kernel.ai.feature.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.kernel.ai.core.permissions.R as PermissionsR
import com.kernel.ai.core.permissions.exactAlarmSettingsIntents
import com.kernel.ai.core.permissions.fullScreenIntentSettingsIntents
import com.kernel.ai.core.permissions.launchFirstAvailable

/**
 * Post-save notice for alarm and timer scheduling (#2185). Each offer opens the Settings page that fixes its
 * warning, so the user can act from the dialog instead of searching for the page.
 */
@Composable
internal fun SchedulingNoticeDialog(notice: SchedulingNotice, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val exactAlarmNoPage = stringResource(PermissionsR.string.exact_alarm_no_settings_page)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Info, contentDescription = null) },
        text = { Text(notice.message) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK") }
        },
        dismissButton = if (notice.offers.isEmpty()) {
            null
        } else {
            {
                Row {
                    notice.offers.forEach { offer ->
                        TextButton(onClick = {
                            val opened = when (offer) {
                                AlarmSettingsOffer.EXACT_ALARMS ->
                                    launchFirstAvailable(exactAlarmSettingsIntents(context.packageName)) { context.startActivity(it) }
                                AlarmSettingsOffer.FULL_SCREEN_ALERTS ->
                                    launchFirstAvailable(fullScreenIntentSettingsIntents(context.packageName)) { context.startActivity(it) }
                            }
                            if (!opened) {
                                val message = when (offer) {
                                    AlarmSettingsOffer.EXACT_ALARMS -> exactAlarmNoPage
                                    AlarmSettingsOffer.FULL_SCREEN_ALERTS -> "Couldn't open full-screen alert settings on this device."
                                }
                                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                            }
                            onDismiss()
                        }) { Text(offer.buttonLabel) }
                    }
                }
            }
        },
    )
}

private val AlarmSettingsOffer.buttonLabel: String
    get() = when (this) {
        AlarmSettingsOffer.EXACT_ALARMS -> "Alarms & reminders"
        AlarmSettingsOffer.FULL_SCREEN_ALERTS -> "Full-screen alerts"
    }
