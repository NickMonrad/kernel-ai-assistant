package com.kernel.ai.core.permissions

import android.content.Context

/**
 * Where a "grant access" action for one permission must go.
 *
 * [Granted] — already granted; nothing to do.
 * [RequestInApp] — launch the in-app runtime request (`ActivityResultContracts.RequestPermission`).
 * [AppInfo] — permanently denied; the system will not show the prompt, so open the App-info page.
 * [SpecialAccess] — special access (DND, write settings, exact alarms, full-screen intent,
 *   battery optimisation) is only grantable on its own system page.
 */
enum class PermissionRoute {
    Granted,
    RequestInApp,
    AppInfo,
    SpecialAccess,
}

/**
 * Pure routing decision shared by the App Permissions screen and the setup wizard.
 *
 * Permanent denial is detected as: the permission was requested by this app before
 * ([wasRequestedBefore], because `shouldShowRequestPermissionRationale` is also false
 * before the first request) AND [shouldShowRationale] is false.
 */
fun decidePermissionRoute(
    isGranted: Boolean,
    isSpecialAccess: Boolean,
    wasRequestedBefore: Boolean,
    shouldShowRationale: Boolean,
): PermissionRoute = when {
    isGranted -> PermissionRoute.Granted
    isSpecialAccess -> PermissionRoute.SpecialAccess
    wasRequestedBefore && !shouldShowRationale -> PermissionRoute.AppInfo
    else -> PermissionRoute.RequestInApp
}

/**
 * Persists whether this app has launched the in-app runtime request for a permission.
 * Needed because a never-requested permission and a permanently denied one both report
 * `shouldShowRequestPermissionRationale == false`.
 */
class PermissionRequestStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun wasRequested(permission: String): Boolean = prefs.getBoolean(permission, false)

    fun markRequested(permission: String) {
        prefs.edit().putBoolean(permission, true).apply()
    }

    /** Forget the record, e.g. once the permission is granted (one-time grants auto-revoke). */
    fun clear(permission: String) {
        prefs.edit().remove(permission).apply()
    }

    private companion object {
        const val PREFS_NAME = "runtime_permission_requests"
    }
}
