package com.kernel.ai.core.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class VoiceRecoveryTest {

    private val refusal = VoiceRefusal("Accept the Terms of Use to use this speech model.", VoiceRecovery.ACCEPT_TERMS)

    @Test
    fun `recovery is shown while the refusal message is the error on screen`() {
        assertEquals(VoiceRecovery.ACCEPT_TERMS, recoveryFor(refusal.message, refusal))
    }

    @Test
    fun `recovery is dropped once a different error replaces the refusal`() {
        assertNull(recoveryFor("Voice input failed.", refusal))
    }

    @Test
    fun `no recovery without a refusal or without an error`() {
        assertNull(recoveryFor(refusal.message, null))
        assertNull(recoveryFor(null, refusal))
    }
}
