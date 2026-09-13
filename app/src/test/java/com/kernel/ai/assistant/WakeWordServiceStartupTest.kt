package com.kernel.ai.assistant

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WakeWordServiceStartupTest {

    @Test
    fun `microphone FGS rejection becomes a non-start result`() {
        val rejection = SecurityException("microphone FGS start not allowed")
        var rejected: SecurityException? = null

        val started = tryPromoteToMicrophoneForeground(
            promote = {
                throw rejection
            },
            onRejected = { error -> rejected = error },
        )

        assertFalse(started)
        assertSame(rejection, rejected)
    }

    @Test
    fun `successful microphone FGS promotion remains a start result`() {
        var promotionCount = 0
        var rejectionCount = 0

        val started = tryPromoteToMicrophoneForeground(
            promote = { promotionCount++ },
            onRejected = { rejectionCount++ },
        )

        assertTrue(started)
        assertEquals(1, promotionCount)
        assertEquals(0, rejectionCount)
    }
}
