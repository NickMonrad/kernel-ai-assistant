package com.kernel.ai.feature.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Represents a single runtime permission whose grant status the user can inspect and act on.
 *
 * @param label        Human-readable name shown in the UI.
 * @param description  What the permission is used for in this app.
 * @param permission   The [Manifest.permission] constant value.
 * @param isGranted    Current grant state as of the last refresh.
 * @param isSpecial    If true, this is a special-access permission (like DND/write settings)
 *                     managed through a system settings intent rather than a runtime request.
 */
data class AppPermissionItem(
    val label: String,
    val description: String,
    val permission: String,
    val isGranted: Boolean = false,
    val isSpecial: Boolean = false,
)

data class AppPermissionsUiState(
    val permissions: List<AppPermissionItem> = emptyList(),
)

/**
 * Builds an Intent that opens the system App-info page for a given package.
 * Extracted for testability — can be verified directly with Robolectric.
 */
internal fun buildAppInfoSettingsIntent(packageName: String): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/**
 * Builds an Intent for a special-access permission settings panel, or null
 * if the permission is not recognised (caller should fall back to app info).
 * Extracted for testability — can be verified directly with Robolectric.
 */
internal fun buildSpecialPermissionSettingsIntent(permission: String, packageName: String): Intent? = when (permission) {
    Manifest.permission.ACCESS_NOTIFICATION_POLICY ->
        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
    Manifest.permission.WRITE_SETTINGS ->
        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        }
    else -> null
}

@HiltViewModel
class AppPermissionsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppPermissionsUiState())
    val uiState: StateFlow<AppPermissionsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = AppPermissionsUiState(
                permissions = buildPermissionList(),
            )
        }
    }

    /** Opens the system App-info page for this app so the user can toggle runtime permissions. */
    fun openAppInfoSettings() {
        context.startActivity(buildAppInfoSettingsIntent(context.packageName))
    }

    /** Opens a specific system settings panel for a special-access permission. */
    fun openSpecialPermissionSettings(permission: String) {
        val intent = buildSpecialPermissionSettingsIntent(permission, context.packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } else {
            openAppInfoSettings()
        }
    }

    private fun buildPermissionList(): List<AppPermissionItem> {

        // Runtime permissions that the app actually uses and can be revoked.
        // Each entry maps to the skill/feature that depends on it.
        val knownPermissions = listOf(
            AppPermissionItem(
                label = "Phone",
                description = "Hands-free calling",
                permission = Manifest.permission.CALL_PHONE,
            ),
            AppPermissionItem(
                label = "Microphone",
                description = "Voice input for Quick Actions and Hey Jandal",
                permission = Manifest.permission.RECORD_AUDIO,
            ),
            AppPermissionItem(
                label = "Notifications",
                description = "Alarms, timers, and download notifications",
                permission = Manifest.permission.POST_NOTIFICATIONS,
            ),
            AppPermissionItem(
                label = "Location",
                description = "Local weather",
                permission = Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
            AppPermissionItem(
                label = "Contacts",
                description = "Contact lookup for calls, SMS, and email",
                permission = Manifest.permission.READ_CONTACTS,
            ),
            AppPermissionItem(
                label = "Calendar",
                description = "Calendar lookup for important dates",
                permission = Manifest.permission.READ_CALENDAR,
            ),
        )

        // Special-access permissions — granted via system settings, not runtime request.
        val specialPermissions = listOf(
            AppPermissionItem(
                label = "Do Not Disturb",
                description = "Do Not Disturb control",
                permission = Manifest.permission.ACCESS_NOTIFICATION_POLICY,
                isGranted = checkNotificationPolicyAccess(),
                isSpecial = true,
            ),
            // WRITE_SETTINGS — brightness control; usually auto-granted on Samsung devices,
            // but included for completeness on devices that deny it.
            AppPermissionItem(
                label = "Modify system settings",
                description = "Brightness and system settings",
                permission = Manifest.permission.WRITE_SETTINGS,
                isGranted = Settings.System.canWrite(context),
                isSpecial = true,
            ),
        )

        return knownPermissions.map { perm ->
            perm.copy(
                isGranted = ContextCompat.checkSelfPermission(context, perm.permission)
                    == PackageManager.PERMISSION_GRANTED,
            )
        } + specialPermissions
    }

    private fun checkNotificationPolicyAccess(): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as? android.app.NotificationManager ?: return false
        return nm.isNotificationPolicyAccessGranted
    }
}