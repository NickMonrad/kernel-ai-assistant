package com.kernel.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class StartupPermissionPolicyTest {

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    // ── API 33+ ──────────────────────────────────────────────────────────

    @Test
    fun `api33plus includes POST_NOTIFICATIONS when not granted`() {
        mockkStatic(ContextCompat::class)
        every {
            ContextCompat.checkSelfPermission(any(), Manifest.permission.POST_NOTIFICATIONS)
        } returns PackageManager.PERMISSION_DENIED

        val ctx = mockk<Context>()
        val result = MainActivity().buildMissingStartupPermissions(ctx, sdkInt = Build.VERSION_CODES.TIRAMISU)
        assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), result)
    }

    @Test
    fun `api33plus does not include POST_NOTIFICATIONS when already granted`() {
        mockkStatic(ContextCompat::class)
        every {
            ContextCompat.checkSelfPermission(any(), Manifest.permission.POST_NOTIFICATIONS)
        } returns PackageManager.PERMISSION_GRANTED

        val ctx = mockk<Context>()
        val result = MainActivity().buildMissingStartupPermissions(ctx, sdkInt = Build.VERSION_CODES.TIRAMISU)
        assertEquals(emptyList<String>(), result)
    }

    @Test
    fun `api33plus does not include optional runtime permissions in startup bundle`() {
        mockkStatic(ContextCompat::class)
        every {
            ContextCompat.checkSelfPermission(any(), Manifest.permission.POST_NOTIFICATIONS)
        } returns PackageManager.PERMISSION_DENIED
        val ctx = mockk<Context>()
        val result = MainActivity().buildMissingStartupPermissions(ctx, sdkInt = Build.VERSION_CODES.TIRAMISU)
        assertFalse(Manifest.permission.ACCESS_COARSE_LOCATION in result)
        assertFalse(Manifest.permission.ACCESS_FINE_LOCATION in result)
        assertFalse(Manifest.permission.READ_CONTACTS in result)
        assertFalse(Manifest.permission.READ_CALENDAR in result)
        assertFalse(Manifest.permission.CALL_PHONE in result)
        assertFalse(Manifest.permission.RECORD_AUDIO in result)
    }

    // ── Pre-API 33 ──────────────────────────────────────────────────────

    @Test
    fun `preApi33 does not request POST_NOTIFICATIONS`() {
        val ctx = mockk<Context>()
        val result = MainActivity().buildMissingStartupPermissions(ctx, sdkInt = Build.VERSION_CODES.Q)
        assertFalse(Manifest.permission.POST_NOTIFICATIONS in result)
    }
}
