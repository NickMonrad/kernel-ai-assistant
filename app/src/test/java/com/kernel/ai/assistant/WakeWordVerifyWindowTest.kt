package com.kernel.ai.assistant

import com.kernel.ai.core.voice.VoiceInputController
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Deterministic tests for the #1432 low-confidence wake verification wiring:
 * [WakeWordService.verifyWakeWindow] must consult the delegated controller's
 * wake transcript and accept only a supported Hey Jandal form.
 *
 * Physical evidence (trials 019 / 026 of the #1410 `regression_post_fix`
 * matrix): low-mode `ACTIVATION_CANDIDATE`s were emitted (~0.5165 / ~0.6002)
 * but the facade's interface-default `transcribeBlocking` returned `null`, so
 * every low-band candidate was guaranteed to reject.  These tests pin the
 * corrected wiring: the PCM is forwarded to the delegate, and only the
 * delegate's transcript (never the interactive engine selection) decides.
 */
class WakeWordVerifyWindowTest {

    private val voiceInputController: VoiceInputController = mockk()

    @Test
    fun `activates when the delegated transcript contains a supported Hey Jandal form`() = runTest {
        val pcm = shortArrayOf(10, 20, 30, 40)
        coEvery { voiceInputController.transcribeBlocking(pcm) } returns "hey jandal, set a timer"

        assertTrue(verifyWakeWindow(voiceInputController, pcm))
    }

    @Test
    fun `activates for ASR variant spellings of the wake phrase`() = runTest {
        val pcm = shortArrayOf(1)
        coEvery { voiceInputController.transcribeBlocking(pcm) } returns "a jandel"

        assertTrue(verifyWakeWindow(voiceInputController, pcm))
    }

    @Test
    fun `rejects a non-wake transcript`() = runTest {
        val pcm = shortArrayOf(2, 3)
        coEvery { voiceInputController.transcribeBlocking(pcm) } returns "play some music"

        assertFalse(verifyWakeWindow(voiceInputController, pcm))
    }

    @Test
    fun `rejects null truthfully`() = runTest {
        val pcm = shortArrayOf(4, 5, 6)
        coEvery { voiceInputController.transcribeBlocking(pcm) } returns null

        assertFalse(verifyWakeWindow(voiceInputController, pcm))
    }

    @Test
    fun `forwards the wake-window PCM exactly once to the delegate`() = runTest {
        val pcm = shortArrayOf(7, 8, 9)
        coEvery { voiceInputController.transcribeBlocking(pcm) } returns "hey jandal"

        verifyWakeWindow(voiceInputController, pcm)

        coVerify(exactly = 1) { voiceInputController.transcribeBlocking(pcm) }
    }

    @Test
    fun `forwards even an empty window instead of short-circuiting`() = runTest {
        val pcm = shortArrayOf()
        coEvery { voiceInputController.transcribeBlocking(pcm) } returns "hey jandal"

        assertTrue(verifyWakeWindow(voiceInputController, pcm))
        coVerify(exactly = 1) { voiceInputController.transcribeBlocking(pcm) }
    }

    // ── #1439: Whisper verifier transcripts ────────────────────────────────────

    @Test
    fun `accepts the Whisper verifier transcript of the genuine wake phrase`() = runTest {
        // Exact catalogue Whisper tiny.en files transcribe the fixed natural
        // fixture (detector-equivalent window) deterministically as "hi, jandal".
        val pcm = shortArrayOf(11, 12, 13)
        coEvery { voiceInputController.transcribeBlocking(pcm) } returns "hi, jandal"

        assertTrue(verifyWakeWindow(voiceInputController, pcm))
    }

    @Test
    fun `accepts the jando truncation variant`() = runTest {
        val pcm = shortArrayOf(14, 15)
        coEvery { voiceInputController.transcribeBlocking(pcm) } returns "hi, jando"

        assertTrue(verifyWakeWindow(voiceInputController, pcm))
    }

    @Test
    fun `rejects Whisper fragments and non-wake transcripts`() = runTest {
        val pcm = shortArrayOf(16, 17, 18)
        listOf(
            "hi, gentle",
            "hi, sandal",
            "hy gen",
            "hy general",
            "[ silence ]",
            "[blank_audio]",
            "what time is it",
            "again",
            "play some music",
        ).forEach { transcript ->
            coEvery { voiceInputController.transcribeBlocking(pcm) } returns transcript
            assertFalse(verifyWakeWindow(voiceInputController, pcm), "must reject $transcript")
        }
    }
}
