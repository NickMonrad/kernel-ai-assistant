package com.kernel.ai.core.permissions

import android.content.Context
import android.content.SharedPreferences

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
 * Pure routing decision for the "grant access" action. This is the routing function #1855 should
 * use for the setup wizard too. [PermissionDenialClassifier] stays separate: it only produces
 * microphone denial/repair copy and does not decide which action a button performs.
 *
 * Permanent denial is detected as: the user denied this permission at least once in-app while
 * Android still offered a rationale ([wasDeniedBefore], recorded by [recordPermissionResult]),
 * AND the rationale is now false (the second denial). Before the first denial,
 * `shouldShowRequestPermissionRationale` is also false, so the flag alone decides it.
 */
fun decidePermissionRoute(
    isGranted: Boolean,
    isSpecialAccess: Boolean,
    wasDeniedBefore: Boolean,
    shouldShowRationale: Boolean,
): PermissionRoute = when {
    isGranted -> PermissionRoute.Granted
    isSpecialAccess -> PermissionRoute.SpecialAccess
    wasDeniedBefore && !shouldShowRationale -> PermissionRoute.AppInfo
    else -> PermissionRoute.RequestInApp
}

/**
 * Persists the "denied once in-app" flag per runtime permission, in SharedPreferences
 * `runtime_permission_requests`. Build it only through [from] (Hilt and [recordPermissionResult]
 * both use it).
 */
class PermissionRequestStore internal constructor(private val prefs: SharedPreferences) {

    fun wasDenied(permission: String): Boolean = prefs.getBoolean(permission, false)

    fun markDenied(permission: String) {
        prefs.edit().putBoolean(permission, true).apply()
    }

    /** Forget the record, e.g. once the permission is granted (one-time grants auto-revoke). */
    fun clear(permission: String) {
        prefs.edit().remove(permission).apply()
    }

    companion object {
        private const val PREFS_NAME = "runtime_permission_requests"

        fun from(context: Context): PermissionRequestStore =
            PermissionRequestStore(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }
}

/**
 * Android returns a denial without showing a dialog when the permission is permanently denied.
 * A dialog needs user interaction, so a denial that returns within [SILENT_DENIAL_MAX_MS] of the
 * launch had no dialog. This catches permanent denials made before the denied-once record existed.
 */
const val SILENT_DENIAL_MAX_MS = 400L

fun isSilentDenial(elapsedMs: Long): Boolean = elapsedMs in 0 until SILENT_DENIAL_MAX_MS

/**
 * Call from every runtime-permission result callback, at every request site.
 *
 * - Granted: clears the record, so a later revoke (including one-time grants) goes back to "Allow".
 * - Denied while [shouldShowRationale] is true: records the first in-app denial. A dialog that was
 *   only dismissed does not set rationale on Android 11+, so it is not recorded and the row keeps
 *   offering the in-app request.
 * - Denied with [silentlyDenied] (see [isSilentDenial]): records a permanent denial, so the row
 *   routes to App info from now on.
 *
 * [shouldShowRationale] must be read from the Activity in the callback, never during composition.
 */
fun Context.recordPermissionResult(
    permission: String,
    granted: Boolean,
    shouldShowRationale: Boolean,
    silentlyDenied: Boolean = false,
) {
    val store = PermissionRequestStore.from(this)
    when {
        granted -> store.clear(permission)
        shouldShowRationale || silentlyDenied -> store.markDenied(permission)
    }
}
