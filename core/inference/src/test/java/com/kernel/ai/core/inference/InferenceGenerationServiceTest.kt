package com.kernel.ai.core.inference

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertThrows
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
}
