package com.kernel.ai.core.inference

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InferenceGenerationServiceTest {
    @Test
    fun `start tolerates temporary foreground-service background-start restriction`() {
        val context = mockk<Context>()
        every { context.startForegroundService(any()) } throws
            ForegroundServiceStartNotAllowedException("background start restricted")

        mockkStatic(Log::class)
        every { Log.w(any(), any(), any<Throwable>()) } returns 0

        try {
            InferenceGenerationService.start(context)
        } finally {
            unmockkStatic(Log::class)
        }

        verify(exactly = 1) { context.startForegroundService(any()) }
    }

    @Test
    fun `start surfaces unrelated security failures`() {
        val context = mockk<Context>()
        every { context.startForegroundService(any()) } throws SecurityException("invalid service type")

        assertThrows(SecurityException::class.java) {
            InferenceGenerationService.start(context)
        }
    }

    @Test
    fun `stop before promotion is deferred until service is foreground`() {
        val lifecycle = ForegroundServiceLifecycle()
        var starts = 0
        var stops = 0

        lifecycle.start { starts++ }
        lifecycle.stop { stops++ }

        assertEquals(1, starts)
        assertEquals(0, stops)
        assertTrue(lifecycle.promoted { stops++ })
        assertEquals(1, stops)
    }

    @Test
    fun `new generation keeps a pending service start alive`() {
        val lifecycle = ForegroundServiceLifecycle()
        var starts = 0
        var stops = 0

        lifecycle.start { starts++ }
        lifecycle.stop { stops++ }
        lifecycle.start { starts++ }

        assertEquals(1, starts)
        assertFalse(lifecycle.promoted { stops++ })
        assertEquals(0, stops)

        lifecycle.stop { stops++ }
        assertEquals(1, stops)
    }

    @Test
    fun `failed start does not leave a promotion pending`() {
        val lifecycle = ForegroundServiceLifecycle()
        assertThrows(SecurityException::class.java) {
            lifecycle.start { throw SecurityException("invalid service type") }
        }

        var stops = 0
        lifecycle.stop { stops++ }
        assertEquals(1, stops)
    }

    @Test
    fun `stop after promotion immediately stops service`() {
        val lifecycle = ForegroundServiceLifecycle()
        lifecycle.start {}
        assertFalse(lifecycle.promoted {})

        var stops = 0
        lifecycle.stop { stops++ }
        assertEquals(1, stops)
    }

}
