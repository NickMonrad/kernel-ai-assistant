package com.kernel.ai.assistant

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

internal class WakeOverlayRouteTest {

    /**
     * #2081: the direct start needs an interactive, unlocked device. A screen-off or locked device uses the
     * assistant session when Jandal is the assistant, and a tap-to-open notification otherwise.
     */
    @ParameterizedTest(name = "interactive={0} keyguardLocked={1} assistantSession={2} -> {3}")
    @CsvSource(
        "true,  false, false, DIRECT_ACTIVITY",
        "true,  false, true,  DIRECT_ACTIVITY",
        "true,  true,  false, TAP_NOTIFICATION",
        "true,  true,  true,  ASSISTANT_SESSION",
        "false, false, false, TAP_NOTIFICATION",
        "false, false, true,  ASSISTANT_SESSION",
        "false, true,  false, TAP_NOTIFICATION",
        "false, true,  true,  ASSISTANT_SESSION",
    )
    fun `route follows interactive, keyguard and assistant session`(
        interactive: Boolean,
        keyguardLocked: Boolean,
        assistantSessionAvailable: Boolean,
        expected: WakeOverlayRoute,
    ) {
        assertEquals(
            expected,
            chooseWakeOverlayRoute(
                interactive = interactive,
                keyguardLocked = keyguardLocked,
                assistantSessionAvailable = assistantSessionAvailable,
            ),
        )
    }
}
