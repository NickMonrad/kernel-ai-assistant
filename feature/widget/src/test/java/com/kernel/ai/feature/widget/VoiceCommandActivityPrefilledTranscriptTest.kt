package com.kernel.ai.feature.widget

import com.kernel.ai.core.voice.WakeWordHandoff
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Unit tests for [validatePrefilledTranscriptToken] token validation.
 *
 * Verifies the security contract: the prefilled transcript extra is only trusted
 * when it matches the in-process [WakeWordHandoff.pendingTranscript] token set by
 * [WakeWordService] immediately before launching this activity.
 */
class VoiceCommandActivityPrefilledTranscriptTest {

    @BeforeEach
    fun setUp() {
        mockkObject(WakeWordHandoff)
        // Reset token between tests
        WakeWordHandoff.pendingTranscript = null
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `validatePrefilledTranscriptToken returns false when extra is null`() {
        WakeWordHandoff.pendingTranscript = "legitimate_token"

        val result = validatePrefilledTranscriptToken(null)

        assertFalse(result, "Expected false when extra is null")
    }

    @Test
    fun `validatePrefilledTranscriptToken returns false when token is null`() {
        WakeWordHandoff.pendingTranscript = null

        val result = validatePrefilledTranscriptToken("some_transcript")

        assertFalse(result, "Expected false when token is null")
    }

    @Test
    fun `validatePrefilledTranscriptToken returns false when token does not match extra`() {
        WakeWordHandoff.pendingTranscript = "legitimate_token"

        val result = validatePrefilledTranscriptToken("injected_transcript")

        assertFalse(result, "Expected false when token does not match extra")
    }

    @Test
    fun `validatePrefilledTranscriptToken returns true when token matches extra`() {
        val transcript = "wake_word_transcript"
        WakeWordHandoff.pendingTranscript = transcript

        val result = validatePrefilledTranscriptToken(transcript)

        assertTrue(result, "Expected true when token matches extra")
    }
}
