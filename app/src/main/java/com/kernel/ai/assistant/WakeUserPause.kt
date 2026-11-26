package com.kernel.ai.assistant

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
