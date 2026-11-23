package com.kernel.ai.assistant

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

internal class WakeOverlayRouteTest {

    /**
     * #2081: the direct start needs an interactive, unlocked device with a visible Jandal window; with no
     * visible window it is a background launch that Android blocks. Every other case uses the assistant
     * session when Jandal is the assistant, and a tap-to-open notification otherwise.
     */
    @ParameterizedTest(name = "interactive={0} keyguardLocked={1} jandalVisible={2} assistantSession={3} -> {4}")
    @CsvSource(
        "true,  false, true,  false, DIRECT_ACTIVITY",
        "true,  false, true,  true,  DIRECT_ACTIVITY",
        "true,  false, false, false, TAP_NOTIFICATION",
        "true,  false, false, true,  ASSISTANT_SESSION",
        "true,  true,  true,  false, TAP_NOTIFICATION",
        "true,  true,  true,  true,  ASSISTANT_SESSION",
        "true,  true,  false, false, TAP_NOTIFICATION",
        "true,  true,  false, true,  ASSISTANT_SESSION",
        "false, false, true,  false, TAP_NOTIFICATION",
        "false, false, true,  true,  ASSISTANT_SESSION",
        "false, false, false, false, TAP_NOTIFICATION",
        "false, false, false, true,  ASSISTANT_SESSION",
        "false, true,  true,  false, TAP_NOTIFICATION",
        "false, true,  true,  true,  ASSISTANT_SESSION",
        "false, true,  false, false, TAP_NOTIFICATION",
        "false, true,  false, true,  ASSISTANT_SESSION",
    )
    fun `route follows interactive, keyguard, visible window and assistant session`(
        interactive: Boolean,
        keyguardLocked: Boolean,
        jandalVisible: Boolean,
        assistantSessionAvailable: Boolean,
        expected: WakeOverlayRoute,
    ) {
        assertEquals(
            expected,
            chooseWakeOverlayRoute(
                interactive = interactive,
                keyguardLocked = keyguardLocked,
                jandalVisible = jandalVisible,
                assistantSessionAvailable = assistantSessionAvailable,
            ),
        )
    }
}
