package com.kernel.ai.core.permissions

import io.mockk.*
import org.junit.jupiter.api.Test
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import org.junit.jupiter.api.Assertions.assertEquals

class MicrophonePermissionReadinessTest {

    @Test
    fun `not granted returns NotGranted`() {
        val ctx = mockk<Context>()
        mockkStatic(ContextCompat::class)
        every {
            ContextCompat.checkSelfPermission(any(), Manifest.permission.RECORD_AUDIO)
        } returns PackageManager.PERMISSION_DENIED
        assertEquals(MicrophoneReadiness.NotGranted, MicrophonePermissionReadiness.evaluate(ctx))
    }

    @Test
    fun `granted returns Granted`() {
        val ctx = mockk<Context>()
        mockkStatic(ContextCompat::class)
        every {
            ContextCompat.checkSelfPermission(any(), Manifest.permission.RECORD_AUDIO)
        } returns PackageManager.PERMISSION_GRANTED
        assertEquals(MicrophoneReadiness.Granted, MicrophonePermissionReadiness.evaluate(ctx))
    }

    @Test
    fun `exception returns Unknown`() {
        val ctx = mockk<Context>()
        mockkStatic(ContextCompat::class)
        every {
            ContextCompat.checkSelfPermission(any(), Manifest.permission.RECORD_AUDIO)
        } throws RuntimeException("mock failure")
        assertEquals(MicrophoneReadiness.Unknown, MicrophonePermissionReadiness.evaluate(ctx))
    }
}
