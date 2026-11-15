package com.kernel.ai.core.voice

import android.view.KeyEvent

/**
 * Turns a volume-key press into a speech stop (#1873) for an Activity's `onKeyDown`/`onKeyUp`.
 *
 * A press is consumed only when [requestStop] accepts it (Jandal is speaking). The consumed key
 * code is remembered so that its auto-repeat downs and its key-up are swallowed too; otherwise
 * the system would change the media volume after the speech was already stopped. Presses while
 * not speaking return false and fall through to normal volume handling.
 */
class SpeechStopKeyTracker(private val requestStop: () -> Boolean) {
    private var consumedKeyCode: Int? = null

    /** Returns true when the key-down is consumed. */
    fun onKeyDown(keyCode: Int, repeatCount: Int): Boolean {
        if (!isVolumeKey(keyCode)) return false
        if (consumedKeyCode == keyCode) return true
        if (repeatCount == 0 && requestStop()) {
            consumedKeyCode = keyCode
            return true
        }
        return false
    }

    /** Returns true when the key-up belongs to a press consumed by [onKeyDown]. */
    fun onKeyUp(keyCode: Int): Boolean {
        if (consumedKeyCode != keyCode) return false
        consumedKeyCode = null
        return true
    }

    private fun isVolumeKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
}
