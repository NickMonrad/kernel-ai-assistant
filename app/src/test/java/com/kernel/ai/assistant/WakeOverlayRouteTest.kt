package com.kernel.ai.assistant

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WakeOverlayRouteTest {

    @Test
    fun `interactive unlocked device opens the overlay activity directly`() {
        assertEquals(
            WakeOverlayRoute.DIRECT_ACTIVITY,
            chooseWakeOverlayRoute(deviceInteractiveAndUnlocked = true, fullScreenAllowed = false),
        )
    }

    @Test
    fun `direct activity wins even when full-screen is allowed`() {
        assertEquals(
            WakeOverlayRoute.DIRECT_ACTIVITY,
            chooseWakeOverlayRoute(deviceInteractiveAndUnlocked = true, fullScreenAllowed = true),
        )
    }

    @Test
    fun `screen off with full-screen granted uses a full-screen notification (#2081)`() {
        assertEquals(
            WakeOverlayRoute.FULL_SCREEN_NOTIFICATION,
            chooseWakeOverlayRoute(deviceInteractiveAndUnlocked = false, fullScreenAllowed = true),
        )
    }

    @Test
    fun `screen off without full-screen falls back to a tap-to-open notification`() {
        assertEquals(
            WakeOverlayRoute.TAP_NOTIFICATION,
            chooseWakeOverlayRoute(deviceInteractiveAndUnlocked = false, fullScreenAllowed = false),
        )
    }
}
