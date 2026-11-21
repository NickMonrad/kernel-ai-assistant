package com.kernel.ai.speech

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.media.session.MediaSession
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpeechPlaybackForegroundTest {
    private val token = mockk<MediaSession.Token>()

    @Test
    fun `start denied by the OS returns false instead of throwing`() {
        val context = mockk<Context>()
        every { context.startForegroundService(any()) } throws
            ForegroundServiceStartNotAllowedException("background start restricted")

        val started = SpeechPlaybackForeground().start(context, token)

        assertFalse(started)
        verify(exactly = 1) { context.startForegroundService(any()) }
    }

    @Test
    fun `stop before promotion is deferred until the service reaches startForeground`() {
        val context = mockk<Context>(relaxed = true)
        val foreground = SpeechPlaybackForeground()

        assertTrue(foreground.start(context, token))
        foreground.stop(context)
        verify(exactly = 0) { context.stopService(any()) }

        var stops = 0
        assertTrue(foreground.promoted { stops++ })
        assertEquals(1, stops)
    }

    @Test
    fun `stop after promotion stops the service immediately`() {
        val context = mockk<Context>(relaxed = true)
        val foreground = SpeechPlaybackForeground()
        assertTrue(foreground.start(context, token))
        assertFalse(foreground.promoted {})

        foreground.stop(context)

        verify(exactly = 1) { context.stopService(any()) }
    }

    @Test
    fun `a denied start leaves no promotion pending so stop still reaches the service`() {
        val context = mockk<Context>()
        every { context.startForegroundService(any()) } throws
            ForegroundServiceStartNotAllowedException("background start restricted")
        every { context.stopService(any()) } returns true
        val foreground = SpeechPlaybackForeground()

        assertFalse(foreground.start(context, token))
        foreground.stop(context)

        verify(exactly = 1) { context.stopService(any()) }
    }

    @Test
    fun `a start during pending promotion promotes with the newest token, not the released one`() {
        val context = mockk<Context>(relaxed = true)
        val released = mockk<MediaSession.Token>()
        val current = mockk<MediaSession.Token>()
        val foreground = SpeechPlaybackForeground()

        assertTrue(foreground.start(context, released))
        foreground.stop(context)
        assertTrue(foreground.start(context, current))

        assertSame(current, foreground.promotionToken())
        assertFalse(foreground.promoted {})
        verify(exactly = 1) { context.startForegroundService(any()) }
    }
}
