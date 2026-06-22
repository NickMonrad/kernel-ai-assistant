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

package com.kernel.ai.core.permissions

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import org.junit.jupiter.api.Test
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.test.assertEquals

class MicrophonePermissionReadinessTest {

    @Test
    fun `not granted returns NotGranted`() {
        val ctx = mockContext(granted = false, shouldShowRationale = false)
        assertEquals(MicrophoneReadiness.NotGranted, MicrophonePermissionReadiness.evaluate(ctx))
    }

    @Test
    fun `durable grant returns DurableWhileInUse`() {
        val ctx = mockContext(granted = true, shouldShowRationale = true)
        assertEquals(MicrophoneReadiness.DurableWhileInUse, MicrophonePermissionReadiness.evaluate(ctx))
    }

    @Test
    fun `granted with no rationale returns GrantedForCurrentUseOnly`() {
        val ctx = mockContext(granted = true, shouldShowRationale = false)
        assertEquals(MicrophoneReadiness.GrantedForCurrentUseOnly, MicrophonePermissionReadiness.evaluate(ctx))
    }

    @Test
    fun `non-Activity context with durable grant returns DurableWhileInUse`() {
        val ctx = mockk<Context>()
        mockkStatic(ContextCompat::class)
        every {
            ContextCompat.checkSelfPermission(any(), Manifest.permission.RECORD_AUDIO)
        } returns PackageManager.PERMISSION_GRANTED

        // Non-Activity context — can't check shouldShowRequestPermissionRationale,
        // so conservatively treat as DurableWhileInUse
        assertEquals(MicrophoneReadiness.DurableWhileInUse, MicrophonePermissionReadiness.evaluate(ctx))
    }

    private fun mockContext(granted: Boolean, shouldShowRationale: Boolean): Context {
        val ctx = mockk<Activity>()
        mockkStatic(ContextCompat::class)
        mockkStatic(ActivityCompat::class)
        every {
            ContextCompat.checkSelfPermission(any(), Manifest.permission.RECORD_AUDIO)
        } returns if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        every {
            ActivityCompat.shouldShowRequestPermissionRationale(any(), Manifest.permission.RECORD_AUDIO)
        } returns shouldShowRationale
        return ctx
    }
}
