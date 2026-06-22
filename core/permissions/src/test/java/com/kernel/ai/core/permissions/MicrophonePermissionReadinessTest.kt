package com.kernel.ai.core.permissions

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import org.junit.jupiter.api.Test
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlin.test.assertEquals

class MicrophonePermissionReadinessTest {

    private val baseFlags = 0

    @Test
    fun `not granted returns NotGranted`() {
        val ctx = mockContext(granted = false, flags = baseFlags)
        assertEquals(MicrophoneReadiness.NotGranted, MicrophonePermissionReadiness.evaluate(ctx))
    }

    @Test
    fun `durable grant returns DurableWhileInUse`() {
        val ctx = mockContext(granted = true, flags = baseFlags)
        assertEquals(MicrophoneReadiness.DurableWhileInUse, MicrophonePermissionReadiness.evaluate(ctx))
    }

    @Test
    fun `one-time flag set returns GrantedForCurrentUseOnly`() {
        val ctx = mockContext(granted = true, flags = 0x00000080)
        assertEquals(MicrophoneReadiness.GrantedForCurrentUseOnly, MicrophonePermissionReadiness.evaluate(ctx))
    }

    @Test
    fun `one-time and user-set flags returns GrantedForCurrentUseOnly`() {
        // Both FLAG_PERMISSION_ONE_TIME and FLAG_PERMISSION_USER_SET
        val ctx = mockContext(granted = true, flags = 0x00000080 or 0x00000004)
        assertEquals(MicrophoneReadiness.GrantedForCurrentUseOnly, MicrophonePermissionReadiness.evaluate(ctx))
    }

    @Test
    fun `exception during flag check returns Unknown`() {
        val ctx = mockk<Context> {
            every { packageManager } throws RuntimeException("mock failure")
        }
        mockkStatic(ContextCompat::class)
        every {
            ContextCompat.checkSelfPermission(any(), Manifest.permission.RECORD_AUDIO)
        } returns PackageManager.PERMISSION_GRANTED

        assertEquals(MicrophoneReadiness.Unknown, MicrophonePermissionReadiness.evaluate(ctx))
    }

    private fun mockContext(granted: Boolean, flags: Int): Context {
        val pm = mockk<PackageManager> {
            every {
                getPermissionFlags(Manifest.permission.RECORD_AUDIO, any())
            } returns flags
        }
        val ctx = mockk<Context> {
            every { packageManager } returns pm
            every { packageName } returns "com.kernel.ai.test"
        }
        mockkStatic(ContextCompat::class)
        every {
            ContextCompat.checkSelfPermission(any(), Manifest.permission.RECORD_AUDIO)
        } returns if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        return ctx
    }
}
