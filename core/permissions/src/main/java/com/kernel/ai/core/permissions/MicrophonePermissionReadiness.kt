package com.kernel.ai.core.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
 * Evaluates microphone permission readiness using Android's public permission
 * flag API to distinguish one-time / "Ask every time" grants from durable grants.
 *
 * The heuristic uses [PackageManager.getPermissionFlags] to check whether the
 * one-time permission flag ([FLAG_PERMISSION_ONE_TIME]) is set. If the flag is
 * set (or cannot be determined), the permission is classified as
 * [MicrophoneReadiness.GrantedForCurrentUseOnly] — it exists right now but may
 * not persist.
 *
 * Note: [FLAG_PERMISSION_ONE_TIME] is a hidden AOSP constant (0x00000080). Its
 * value is stable across Android versions but not part of the public SDK. This
 * approach is widely used in practice. On older API levels or OEM builds that
 * do not set this flag, the behaviour degrades to [DurableWhileInUse] for
 * granted permissions (safe but permissive for Hey Jandal).
 *
 * **Known limitation:** Some OEM builds may not set the one-time flag even for
 * one-time grants, or may set it inconsistently. On such devices, one-time
 * grants may be misclassified as [DurableWhileInUse]. This is a best-effort
 * heuristic using only public APIs.
 */
object MicrophonePermissionReadiness {

    /** One-time permission flag value (hidden constant, stable since API 33). */
    private const val FLAG_PERMISSION_ONE_TIME = 0x00000080

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

        val flags = try {
            context.packageManager.getPermissionFlags(
                Manifest.permission.RECORD_AUDIO,
                context.packageName,
            )
        } catch (e: Exception) {
            // getPermissionFlags may throw on unusual Android builds.
            return MicrophoneReadiness.Unknown
        }

        return if ((flags and FLAG_PERMISSION_ONE_TIME) != 0) {
            MicrophoneReadiness.GrantedForCurrentUseOnly
        } else {
            MicrophoneReadiness.DurableWhileInUse
        }
    }
}
