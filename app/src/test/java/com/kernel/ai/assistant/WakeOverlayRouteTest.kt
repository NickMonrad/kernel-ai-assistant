package com.kernel.ai.assistant

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

internal class WakeOverlayRouteTest {

    /** #2081: the direct start needs an interactive, unlocked device; full-screen then tap are the fallbacks. */
    @ParameterizedTest(name = "interactive={0} keyguardLocked={1} fullScreenAllowed={2} -> {3}")
    @CsvSource(
        "true,  false, false, DIRECT_ACTIVITY",
        "true,  false, true,  DIRECT_ACTIVITY",
        "true,  true,  false, TAP_NOTIFICATION",
        "true,  true,  true,  FULL_SCREEN_NOTIFICATION",
        "false, false, false, TAP_NOTIFICATION",
        "false, false, true,  FULL_SCREEN_NOTIFICATION",
        "false, true,  false, TAP_NOTIFICATION",
        "false, true,  true,  FULL_SCREEN_NOTIFICATION",
    )
    fun `route follows interactive, keyguard and full-screen grant`(
        interactive: Boolean,
        keyguardLocked: Boolean,
        fullScreenAllowed: Boolean,
        expected: WakeOverlayRoute,
    ) {
        assertEquals(
            expected,
            chooseWakeOverlayRoute(
                interactive = interactive,
                keyguardLocked = keyguardLocked,
                fullScreenAllowed = fullScreenAllowed,
            ),
        )
    }
}
