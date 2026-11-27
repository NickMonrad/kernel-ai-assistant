package com.kernel.ai.assistant

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WakeUserPauseTest {

    @Test
    fun `pause and resume each change the state once`() {
        val pause = WakeUserPause()
        assertFalse(pause.paused)
        assertTrue(pause.pause())
        assertTrue(pause.paused)
        assertFalse(pause.pause())
        assertTrue(pause.resume())
        assertFalse(pause.paused)
        assertFalse(pause.resume())
    }

    @Test
    fun `the user's pause is the most specific notification text`() {
        assertEquals(
            NOTIFICATION_TEXT_USER_PAUSED,
            wakeNotificationText(userPaused = true, cameraSuspended = true, otherAppRecording = true),
        )
    }

    @Test
    fun `camera beats another app, and another app beats listening`() {
        assertEquals(
            NOTIFICATION_TEXT_CAMERA_PAUSED,
            wakeNotificationText(userPaused = false, cameraSuspended = true, otherAppRecording = true),
        )
        assertEquals(
            NOTIFICATION_TEXT_OTHER_APP_RECORDING,
            wakeNotificationText(userPaused = false, cameraSuspended = false, otherAppRecording = true),
        )
        assertEquals(
            NOTIFICATION_TEXT_LISTENING,
            wakeNotificationText(userPaused = false, cameraSuspended = false, otherAppRecording = false),
        )
    }

    @Test
    fun `resume from the notification shows the listening text in the foreground notification`() {
        val pause = WakeUserPause().apply { pause() }
        val text = foregroundWakeText(
            pause,
            WakeWordService.ACTION_RESUME,
            setUp = true,
            cameraSuspended = false,
            otherAppRecording = false,
        )
        assertEquals(NOTIFICATION_TEXT_LISTENING, text)
        assertFalse(pause.paused)
    }

    @Test
    fun `pause from the notification shows the paused text in the foreground notification`() {
        val pause = WakeUserPause()
        val text = foregroundWakeText(
            pause,
            WakeWordService.ACTION_PAUSE,
            setUp = true,
            cameraSuspended = false,
            otherAppRecording = false,
        )
        assertEquals(NOTIFICATION_TEXT_USER_PAUSED, text)
        assertTrue(pause.paused)
    }

    @Test
    fun `a notification action on a service that is not set up leaves the pause alone`() {
        val pause = WakeUserPause().apply { pause() }
        foregroundWakeText(
            pause,
            WakeWordService.ACTION_RESUME,
            setUp = false,
            cameraSuspended = false,
            otherAppRecording = false,
        )
        assertTrue(pause.paused)
    }
}
