package com.kernel.ai.core.permissions

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log

private const val TAG = "SystemSettingsRoutes"

/**
 * Settings pages that let the user make Jandal the default digital assistant (#1858), in preference order.
 * AOSP (GrapheneOS) has no role dialog, so these pages are the only route there.
 */
fun defaultAssistantSettingsIntents(): List<Intent> = listOf(
    Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
    Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
)

/**
 * The "Alarms & reminders" page for this app (#1858), falling back to App info where the request page is
 * missing. Exact-alarm access is a special app permission, so there is no runtime dialog to show.
 */
fun exactAlarmSettingsIntents(packageName: String): List<Intent> = listOf(
    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
        data = Uri.fromParts("package", packageName, null)
    },
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
)

/** Whether exact alarms may be scheduled. Read live, so callers see the result after returning from Settings. */
fun canScheduleExactAlarms(context: Context): Boolean =
    context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

/**
 * Launches the first intent that opens, in order, and returns true. Returns false when none resolves
 * (for example a stripped-down OEM image), so the caller can show a message instead.
 */
fun launchFirstAvailable(intents: List<Intent>, launch: (Intent) -> Unit): Boolean {
    for (intent in intents) {
        try {
            launch(intent)
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Settings page unavailable: ${intent.action}", e)
        }
    }
    return false
}
