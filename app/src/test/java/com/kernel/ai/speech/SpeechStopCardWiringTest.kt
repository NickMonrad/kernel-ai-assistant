package com.kernel.ai.speech

import android.content.Context
import android.media.session.MediaSession
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Pins the manager's card to the foreground gate: show/hide must reach [SpeechPlaybackService.start]
 * and [SpeechPlaybackService.stop], never a direct startForegroundService/stopService call.
 */
class SpeechStopCardWiringTest {
    private val context = mockk<Context>(relaxed = true)
    private val token = mockk<MediaSession.Token>()

    @BeforeEach
    fun setUp() {
        mockkObject(SpeechPlaybackService.Companion)
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(SpeechPlaybackService.Companion)
    }

    @Test
    fun `show and hide go through SpeechPlaybackService start and stop`() {
        every { SpeechPlaybackService.start(context, token) } returns true
        justRun { SpeechPlaybackService.stop(context) }

        val card = speechStopCard(context)
        card.show(token)
        card.hide()

        verify(exactly = 1) { SpeechPlaybackService.start(context, token) }
        verify(exactly = 1) { SpeechPlaybackService.stop(context) }
        verify(exactly = 0) { context.startForegroundService(any()) }
        verify(exactly = 0) { context.stopService(any()) }
    }
}
