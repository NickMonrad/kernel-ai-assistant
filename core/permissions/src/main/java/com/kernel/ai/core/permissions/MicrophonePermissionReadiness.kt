package com.kernel.ai.core.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Classification of microphone permission readiness for different use cases.
 *
 * [NotGranted] — RECORD_AUDIO is not granted at all.
 *
 * [GrantedForCurrentUseOnly] — Permission is granted but may be a one-time or
 * "Ask every time" grant that will not persist across process/background
 * boundaries. Suitable for immediate one-shot voice capture but NOT for
 * long-running features like wake-word listening.
 *
 * [DurableWhileInUse] — Permission is durably granted (user explicitly chose
 * "While using the app" or "Always"). Suitable for Hey Jandal / wake-word.
 *
 * [Unknown] — Could not determine durability. Conservative callers should
 * treat this the same as [GrantedForCurrentUseOnly].
 */
enum class MicrophoneReadiness {
    NotGranted,
    GrantedForCurrentUseOnly,
    DurableWhileInUse,
    Unknown,
}

/**
 * Evaluates microphone permission readiness using available public APIs.
 *
 * **Heuristic:** This implementation uses [shouldShowRequestPermissionRationale]
 * as a weak signal. After a permission grant, if the system returns `false`
 * for shouldShowRationale, the grant may be one-time/ask-every-time (the system
 * does not expect a rationale dialog for temporary grants).
 *
 * **Known limitation:** Android does not expose a public API for third-party
 * apps to reliably distinguish one-time grants from durable grants. The
 * `PackageManager.getPermissionFlags` API requires the system-level
 * `GET_PERMISSIONS` permission not available to normal apps. This heuristic
 * is a best-effort approximation. Some one-time grants may be misclassified as
 * durable, and vice versa. On devices where this matters, the lifecycle-based
 * re-check (ON_RESUME observer) provides a second line of defence: if a
 * one-time grant expires on backgrounding, the next resume catches it.
 */
object MicrophonePermissionReadiness {

    /**
     * Evaluate microphone permission readiness for the given [context].
     *
     * @param context Application or activity context.
     * @return A [MicrophoneReadiness] classification.
     */
    fun evaluate(context: Context): MicrophoneReadiness {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return MicrophoneReadiness.NotGranted
        }

        // Weak signal: if the system doesn't expect rationale after a grant,
        // the grant may be one-time. This is not definitive — some OEMs always
        // return false — but it's the best public API signal available.
        return try {
            val activity = context as? android.app.Activity
            if (activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(
                    activity, Manifest.permission.RECORD_AUDIO
                )
            ) {
                MicrophoneReadiness.GrantedForCurrentUseOnly
            } else {
                MicrophoneReadiness.DurableWhileInUse
            }
        } catch (_: Exception) {
            MicrophoneReadiness.Unknown
        }
    }
}
