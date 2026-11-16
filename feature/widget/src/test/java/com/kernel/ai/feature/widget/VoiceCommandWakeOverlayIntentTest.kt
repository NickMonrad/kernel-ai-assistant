package com.kernel.ai.feature.widget

import com.kernel.ai.core.voice.WakeOverlayPhase
import com.kernel.ai.core.voice.WakeOverlayState
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * #1930: an exported activity only shows the wake overlay while the wake capture is live.
 * Robolectric is not on this module's test classpath, so the decision is tested directly.
 */
class VoiceCommandWakeOverlayIntentTest {

    @Test
    fun `wake overlay shows only while the capture is live`() {
        assertTrue(wakeOverlayShows(WakeOverlayState.Active(WakeOverlayPhase.Listening, "")))
        assertTrue(wakeOverlayShows(WakeOverlayState.Active(WakeOverlayPhase.Processing, "turn on")))
        assertFalse(wakeOverlayShows(WakeOverlayState.Idle))
        assertFalse(wakeOverlayShows(WakeOverlayState.Ended(routed = false)))
        assertFalse(wakeOverlayShows(WakeOverlayState.Ended(routed = true)))
    }
}
