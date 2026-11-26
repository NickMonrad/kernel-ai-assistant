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
}
