package com.kernel.ai.assistant

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArmUnlessDestroyedTest {

    @Test
    fun `a live service starts the detector and does not stop it`() {
        val calls = mutableListOf<String>()

        val armed = armUnlessDestroyed(isDestroyed = { false }, start = { calls += "start" }, stop = { calls += "stop" })

        assertTrue(armed)
        assertEquals(listOf("start"), calls)
    }

    @Test
    fun `a destroyed service never starts the detector`() {
        val calls = mutableListOf<String>()

        val armed = armUnlessDestroyed(isDestroyed = { true }, start = { calls += "start" }, stop = { calls += "stop" })

        assertFalse(armed)
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `a service destroyed while the start runs leaves the detector stopped`() {
        // onDestroy sets the flag and stops the detector on main; this re-arm's start finishes after that stop.
        var destroyed = false
        val calls = mutableListOf<String>()

        val armed = armUnlessDestroyed(
            isDestroyed = { destroyed },
            start = {
                calls += "start"
                destroyed = true
            },
            stop = { calls += "stop" },
        )

        assertFalse(armed)
        assertEquals(listOf("start", "stop"), calls, "the start that finished after destroy must be undone")
    }
}
