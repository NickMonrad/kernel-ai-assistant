package com.kernel.ai.core.voice

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WakeWordUtilsTest {

    // ── containsWakePhrase ─────────────────────────────────────────────────────

    @Test
    fun `containsWakePhrase matches Hey Jandal`() {
        assertTrue("Hey Jandal".containsWakePhrase())
        assertTrue("hey jandal".containsWakePhrase())
        assertTrue("HEY JANDAL".containsWakePhrase())
    }

    @Test
    fun `containsWakePhrase matches ASR variants`() {
        assertTrue("a jandel".containsWakePhrase())
        assertTrue("hey handel".containsWakePhrase())
        assertTrue("Hey Handal".containsWakePhrase())
        assertTrue("a hando".containsWakePhrase())
    }

    @Test
    fun `containsWakePhrase rejects non-matches`() {
        assertFalse("hello world".containsWakePhrase())
        assertFalse("hey there".containsWakePhrase())
        assertFalse("jandal".containsWakePhrase())
        assertFalse("".containsWakePhrase())
    }

    @Test
    fun `containsWakePhrase matches without whitespace`() {
        // \s* allows zero whitespace between particles
        assertTrue("heyjandal".containsWakePhrase())
        assertTrue("heyjandel".containsWakePhrase())
    }

    @Test
    fun `containsWakePhrase rejects embedded substrings`() {
        // \b word boundaries prevent matching inside larger words
        assertFalse("they jandal".containsWakePhrase())
        assertFalse("hey jandalish".containsWakePhrase())
        assertTrue("oh heyjandal who".containsWakePhrase())
        assertTrue("say hey jandal now".containsWakePhrase())
    }
}
