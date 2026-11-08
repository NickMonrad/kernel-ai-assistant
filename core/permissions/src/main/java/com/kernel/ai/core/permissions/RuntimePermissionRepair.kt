package com.kernel.ai.core.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Centralises Android settings intent construction for runtime permission repair.
 *
 * Primary route: [Settings.ACTION_APPLICATION_DETAILS_SETTINGS] with the app
 * package URI. This is the shortest reliable public route for third-party apps
 * to reach a specific app's permission screen.
 *
 * Best-effort permission-group extras are included as hints for Settings
 * implementations that honour them. AOSP and OEM Settings frequently ignore
 * these and show the generic app-info screen, which is acceptable as the
 * fallback.
 *
 * DND and write-settings special access are NOT handled here — they use their
 * own dedicated settings intents ([Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS]
 * and [Settings.ACTION_MANAGE_WRITE_SETTINGS]) preserved in ActionsScreen.
 */
object RuntimePermissionRepair {

    private const val EXTRA_PERMISSION_NAME = "android.provider.extra.PERMISSION_NAME"
    private const val EXTRA_PERMISSION_GROUP_NAME = "android.provider.extra.PERMISSION_GROUP_NAME"
    private const val SETTINGS_FRAGMENT_ARGS_KEY = ":settings:fragment_args_key"

    /** Build a repair Intent using the application context to derive the app package. */
    fun intentFor(context: Context, permission: String): Intent =
        intentFor(packageName = context.packageName, permission = permission)

    /** Build a repair Intent with an explicit package name. */
    fun intentFor(packageName: String, permission: String): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EXTRA_PERMISSION_NAME, permission)
            permissionGroupFor(permission)?.let { group ->
                putExtra(EXTRA_PERMISSION_GROUP_NAME, group)
                putExtra(SETTINGS_FRAGMENT_ARGS_KEY, group)
            }
        }
    }

    /** Maps a runtime permission to its Android permission-group string for best-effort extras. */
    private fun permissionGroupFor(permission: String): String? = when (permission) {
        Manifest.permission.CALL_PHONE -> "android.permission-group.PHONE"
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION -> "android.permission-group.LOCATION"
        Manifest.permission.READ_CONTACTS -> "android.permission-group.CONTACTS"
        Manifest.permission.READ_CALENDAR -> "android.permission-group.CALENDAR"
        Manifest.permission.RECORD_AUDIO -> "android.permission-group.MICROPHONE"
        Manifest.permission.POST_NOTIFICATIONS -> "android.permission-group.NOTIFICATIONS"
        else -> null
    }
}
