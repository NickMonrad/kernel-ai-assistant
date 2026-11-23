package com.kernel.ai.feature.chat

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScreenOffStateTest {

    @Test
    fun `interactive unlocked device is not screen-off or locked`() {
        assertFalse(screenOffOrLockedState(interactive = true, keyguardLocked = false))
    }

    @Test
    fun `screen off counts as screen-off or locked`() {
        assertTrue(screenOffOrLockedState(interactive = false, keyguardLocked = false))
    }

    @Test
    fun `keyguard locked with screen on counts as screen-off or locked (#1989)`() {
        assertTrue(screenOffOrLockedState(interactive = true, keyguardLocked = true))
    }
}
