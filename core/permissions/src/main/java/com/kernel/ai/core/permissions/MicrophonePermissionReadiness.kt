package com.kernel.ai.core.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Classification of microphone permission readiness.
 *
 * [NotGranted] — RECORD_AUDIO is not currently granted.
 *
 * [Granted] — RECORD_AUDIO is currently granted. This applies to both durable
 * "While using the app" grants and temporary one-time / "Ask every time" grants.
 * Android does not expose a reliable public API for third-party apps to
 * distinguish these states.
 *
 * [Unknown] — Could not determine the current permission state.
 */
enum class MicrophoneReadiness {
    NotGranted,
    Granted,
    Unknown,
}

/**
 * Evaluates microphone permission readiness using [ContextCompat.checkSelfPermission].
 *
 * **Known limitation:** Android does not expose a reliable public API for
 * third-party apps to distinguish durable microphone grants from one-time /
 * ask-every-time grants. The `PackageManager.getPermissionFlags` API requires
 * the system-level `GET_PERMISSIONS` permission not available to normal apps.
 * The `shouldShowRequestPermissionRationale` method is a denial-flow signal,
 * not a grant-durability signal, and must not be used for this purpose.
 *
 * This implementation reports the current permission grant state only.
 * Lifecycle-based re-check (ON_RESUME observer) is used to detect when a
 * temporary grant expires and the permission is no longer granted.
 */
object MicrophonePermissionReadiness {

    /**
     * Evaluate microphone permission readiness for the given [context].
     *
     * @param context Application or activity context.
     * @return [MicrophoneReadiness.Granted] if RECORD_AUDIO is currently granted,
     *   [MicrophoneReadiness.NotGranted] if denied, or [MicrophoneReadiness.Unknown]
     *   if the state could not be determined.
     */
    fun evaluate(context: Context): MicrophoneReadiness {
        return try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) {
                MicrophoneReadiness.Granted
            } else {
                MicrophoneReadiness.NotGranted
            }
        } catch (e: Exception) {
            MicrophoneReadiness.Unknown
        }
    }
}
