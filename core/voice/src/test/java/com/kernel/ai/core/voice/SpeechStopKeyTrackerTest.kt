package com.kernel.ai.core.voice

import android.view.KeyEvent
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpeechStopKeyTrackerTest {

    @Test
    fun `volume down while speaking is consumed and its key-up is swallowed`() {
        var stops = 0
        val tracker = SpeechStopKeyTracker { stops++; true }

        assertTrue(tracker.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, repeatCount = 0))
        assertTrue(tracker.onKeyUp(KeyEvent.KEYCODE_VOLUME_DOWN))

        assertTrue(stops == 1)
    }

    @Test
    fun `auto-repeat downs of a consumed press are swallowed without stopping again`() {
        var stops = 0
        val tracker = SpeechStopKeyTracker { stops++; true }

        tracker.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, repeatCount = 0)
        assertTrue(tracker.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, repeatCount = 1))
        assertTrue(tracker.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, repeatCount = 2))

        assertTrue(stops == 1)
    }

    @Test
    fun `volume key while not speaking falls through, so normal volume change happens`() {
        val tracker = SpeechStopKeyTracker { false }

        assertFalse(tracker.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, repeatCount = 0))
        assertFalse(tracker.onKeyUp(KeyEvent.KEYCODE_VOLUME_DOWN))
    }

    @Test
    fun `a held key that starts repeating before speech stops is not consumed`() {
        var stops = 0
        val tracker = SpeechStopKeyTracker { stops++; true }

        assertFalse(tracker.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, repeatCount = 1))

        assertTrue(stops == 0)
    }

    @Test
    fun `non-volume keys are never consumed`() {
        var stops = 0
        val tracker = SpeechStopKeyTracker { stops++; true }

        assertFalse(tracker.onKeyDown(KeyEvent.KEYCODE_BACK, repeatCount = 0))
        assertFalse(tracker.onKeyUp(KeyEvent.KEYCODE_BACK))

        assertTrue(stops == 0)
    }

    @Test
    fun `key-up of a different volume key does not release the consumed press`() {
        val tracker = SpeechStopKeyTracker { true }

        tracker.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, repeatCount = 0)

        assertFalse(tracker.onKeyUp(KeyEvent.KEYCODE_VOLUME_UP))
        assertTrue(tracker.onKeyUp(KeyEvent.KEYCODE_VOLUME_DOWN))
    }
}
