package com.kernel.ai.assistant

import com.kernel.ai.core.inference.diagnostics.JandalEvents

/**
 * The user's "Pause Hey Jandal" choice from the foreground notification (#2109).
 *
 * Pausing stops wake capture and releases the microphone until the user resumes. The state is in memory
 * only: it survives screen-off, but a reboot or a service restart starts listening again.
 */
internal class WakeUserPause {
    /** Volatile: written on the main thread by the notification action, read under the wake mic lock. */
    @Volatile var paused: Boolean = false
        private set

    /** Returns true when this call changed the state. */
    fun pause(): Boolean {
        if (paused) return false
        paused = true
        return true
    }

    /** Returns true when this call changed the state. */
    fun resume(): Boolean {
        if (!paused) return false
        paused = false
        return true
    }
}

internal const val NOTIFICATION_TEXT_LISTENING = "Listening for wake word…"
internal const val NOTIFICATION_TEXT_CAMERA_PAUSED = "Paused while Camera is in use"
internal const val NOTIFICATION_TEXT_OTHER_APP_RECORDING = "Paused while another app records"
internal const val NOTIFICATION_TEXT_USER_PAUSED = "Paused. Tap Resume to listen for Hey Jandal."

/**
 * The wake notification text for the current state. The most specific reason wins: the user's own pause,
 * then the Honor camera, then another app's recording (#2109). Each reason has its own text, so the text
 * alone identifies the state when the notification is refreshed.
 */
internal fun wakeNotificationText(userPaused: Boolean, cameraSuspended: Boolean, otherAppRecording: Boolean): String =
    when {
        userPaused -> NOTIFICATION_TEXT_USER_PAUSED
        cameraSuspended -> NOTIFICATION_TEXT_CAMERA_PAUSED
        otherAppRecording -> NOTIFICATION_TEXT_OTHER_APP_RECORDING
        else -> NOTIFICATION_TEXT_LISTENING
    }

/** What [promoteWithUserAction] did: whether the foreground promotion held, and whether the pause change was kept. */
internal data class UserActionPromotion(val promoted: Boolean, val pauseChanged: Boolean)

/**
 * #2145: promotes the service to the foreground with the notification for the state after a Pause or Resume from
 * the notification. The pause state changes before [promote] runs, so the posted notification shows the state the
 * user chose. [promote] receives that text and must record it only after posting it.
 *
 * A rejected promotion reverts the change: the detector has not been touched yet, so the pause state must keep
 * matching it. The caller runs the side effects (journal, detector release or re-arm) only for a kept change.
 */
internal fun promoteWithUserAction(
    pause: WakeUserPause,
    action: String?,
    setUp: Boolean,
    cameraSuspended: Boolean,
    otherAppRecording: Boolean,
    promote: (text: String) -> Unit,
    onRejected: (SecurityException) -> Unit,
): UserActionPromotion {
    val changed = when {
        !setUp -> false
        action == WakeWordService.ACTION_PAUSE -> pause.pause()
        action == WakeWordService.ACTION_RESUME -> pause.resume()
        else -> false
    }
    val text = wakeNotificationText(pause.paused, cameraSuspended, otherAppRecording)
    val promoted = tryPromoteToMicrophoneForeground(
        promote = { promote(text) },
        onRejected = { error ->
            if (changed) {
                if (pause.paused) pause.resume() else pause.pause()
            }
            onRejected(error)
        },
    )
    return UserActionPromotion(promoted = promoted, pauseChanged = changed && promoted)
}

/**
 * Journals a user pause or resume to the support export (#2109). `paused` is a Boolean, so the export reads
 * `wake_user_pause paused=true` or `paused=false`. Strings are always redacted.
 */
internal fun recordWakeUserPauseEvent(paused: Boolean) =
    JandalEvents.record("service", "wake_user_pause", "paused" to paused)
