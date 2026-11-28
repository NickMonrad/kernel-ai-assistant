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
    fun `resume from the notification promotes with the listening text and clears the pause`() {
        val pause = WakeUserPause().apply { pause() }
        var postedText: String? = null
        val result = promoteWithUserAction(
            pause,
            WakeWordService.ACTION_RESUME,
            setUp = true,
            cameraSuspended = false,
            otherAppRecording = false,
            promote = { postedText = it },
            onRejected = {},
        )
        assertEquals(UserActionPromotion(promoted = true, pauseChanged = true), result)
        assertEquals(NOTIFICATION_TEXT_LISTENING, postedText)
        assertFalse(pause.paused)
    }

    @Test
    fun `pause from the notification promotes with the paused text and sets the pause`() {
        val pause = WakeUserPause()
        var postedText: String? = null
        val result = promoteWithUserAction(
            pause,
            WakeWordService.ACTION_PAUSE,
            setUp = true,
            cameraSuspended = false,
            otherAppRecording = false,
            promote = { postedText = it },
            onRejected = {},
        )
        assertEquals(UserActionPromotion(promoted = true, pauseChanged = true), result)
        assertEquals(NOTIFICATION_TEXT_USER_PAUSED, postedText)
        assertTrue(pause.paused)
    }

    @Test
    fun `the pause change is applied before the notification is promoted`() {
        val pause = WakeUserPause().apply { pause() }
        var pausedWhenPromoted: Boolean? = null
        promoteWithUserAction(
            pause,
            WakeWordService.ACTION_RESUME,
            setUp = true,
            cameraSuspended = false,
            otherAppRecording = false,
            promote = { pausedWhenPromoted = pause.paused },
            onRejected = {},
        )
        assertEquals(false, pausedWhenPromoted)
    }

    @Test
    fun `a notification action on a service that is not set up leaves the pause alone`() {
        val pause = WakeUserPause().apply { pause() }
        var postedText: String? = null
        val result = promoteWithUserAction(
            pause,
            WakeWordService.ACTION_RESUME,
            setUp = false,
            cameraSuspended = false,
            otherAppRecording = false,
            promote = { postedText = it },
            onRejected = {},
        )
        assertEquals(UserActionPromotion(promoted = true, pauseChanged = false), result)
        assertTrue(pause.paused)
        assertEquals(NOTIFICATION_TEXT_USER_PAUSED, postedText)
    }

    @Test
    fun `a rejected promotion reverts a Resume so the pause still matches the detector`() {
        val pause = WakeUserPause().apply { pause() }
        var rejection: SecurityException? = null
        val result = promoteWithUserAction(
            pause,
            WakeWordService.ACTION_RESUME,
            setUp = true,
            cameraSuspended = false,
            otherAppRecording = false,
            promote = { throw SecurityException("microphone FGS not allowed") },
            onRejected = { rejection = it },
        )
        assertEquals(UserActionPromotion(promoted = false, pauseChanged = false), result)
        assertTrue(pause.paused)
        assertEquals("microphone FGS not allowed", rejection?.message)
    }

    @Test
    fun `a rejected promotion reverts a Pause so the mic is not released without a pause`() {
        val pause = WakeUserPause()
        val result = promoteWithUserAction(
            pause,
            WakeWordService.ACTION_PAUSE,
            setUp = true,
            cameraSuspended = false,
            otherAppRecording = false,
            promote = { throw SecurityException("microphone FGS not allowed") },
            onRejected = {},
        )
        assertEquals(UserActionPromotion(promoted = false, pauseChanged = false), result)
        assertFalse(pause.paused)
    }
}
