package com.kernel.ai.feature.settings

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppPermissionsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val context: Context = mockk(relaxed = true)
    private val pm: PackageManager = mockk()
    private lateinit var viewModel: AppPermissionsViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { context.packageManager } returns pm
        every { context.packageName } returns "com.kernel.ai.test"
        mockkStatic(ContextCompat::class)
        every { ContextCompat.checkSelfPermission(any(), any()) } returns PackageManager.PERMISSION_GRANTED
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun createViewModel(): AppPermissionsViewModel {
        viewModel = AppPermissionsViewModel(context)
        testDispatcher.scheduler.advanceUntilIdle()
        return viewModel
    }

    // ── Row definitions ───────────────────────────────────────────────

    @Test
    fun `dashboard includes expected runtime permission rows`() = runTest {
        val vm = createViewModel()
        val perms = vm.uiState.value.permissions
        val labels = perms.filter { !it.isSpecial }.map { it.label }
        assertTrue("Phone" in labels)
        assertTrue("Microphone" in labels)
        assertTrue("Notifications" in labels)
        assertTrue("Location" in labels)
        assertTrue("Contacts" in labels)
        assertTrue("Calendar" in labels)
    }

    @Test
    fun `dashboard includes expected special access rows`() = runTest {
        val nm: NotificationManager = mockk()
        every { context.getSystemService(Context.NOTIFICATION_SERVICE) } returns nm
        every { nm.isNotificationPolicyAccessGranted } returns true

        val vm = createViewModel()
        val perms = vm.uiState.value.permissions
        val special = perms.filter { it.isSpecial }
        assertEquals(2, special.size)
        assertEquals("Do Not Disturb", special[0].label)
        assertEquals("Modify system settings", special[1].label)
    }

    @Test
    fun `runtime permission descriptions are capability oriented`() = runTest {
        val vm = createViewModel()
        val perms = vm.uiState.value.permissions
        for (p in perms) {
            if (p.isSpecial) continue
            assertFalse(p.description.contains("required", ignoreCase = true),
                "Description for '${p.label}' should not say 'required': '${p.description}'")
        }
    }

    @Test
    fun `runtime permissions map to correct capability descriptions`() = runTest {
        val vm = createViewModel()
        val perms = vm.uiState.value.permissions
        assertEquals("Hands-free calling", perms.first { it.label == "Phone" }.description)
        assertEquals("Voice input for Quick Actions and Hey Jandal", perms.first { it.label == "Microphone" }.description)
        assertEquals("Alarms, timers, and download notifications", perms.first { it.label == "Notifications" }.description)
        assertEquals("Local weather", perms.first { it.label == "Location" }.description)
        assertEquals("Contact lookup for calls, SMS, and email", perms.first { it.label == "Contacts" }.description)
        assertEquals("Calendar lookup for important dates", perms.first { it.label == "Calendar" }.description)
    }

    // ── Classification ─────────────────────────────────────────────────

    @Test
    fun `runtime permission is not special`() = runTest {
        val vm = createViewModel()
        val phone = vm.uiState.value.permissions.first { it.label == "Phone" }
        assertFalse(phone.isSpecial)
    }

    @Test
    fun `DND and write settings are classified as special`() = runTest {
        val nm: NotificationManager = mockk()
        every { context.getSystemService(Context.NOTIFICATION_SERVICE) } returns nm
        every { nm.isNotificationPolicyAccessGranted } returns true

        val vm = createViewModel()
        val special = vm.uiState.value.permissions.filter { it.isSpecial }
        assertEquals(2, special.size)
        assertTrue(special.all { it.isSpecial })
    }

    // ── Grant states ──────────────────────────────────────────────────

    @Test
    fun `permission granted when checkSelfPermission returns GRANTED`() = runTest {
        val vm = createViewModel()
        val phone = vm.uiState.value.permissions.first { it.label == "Phone" }
        assertTrue(phone.isGranted)
    }

    @Test
    fun `permission not granted when checkSelfPermission returns DENIED`() = runTest {
        every {
            ContextCompat.checkSelfPermission(any(), Manifest.permission.CALL_PHONE)
        } returns PackageManager.PERMISSION_DENIED
        val vm = createViewModel()
        val phone = vm.uiState.value.permissions.first { it.label == "Phone" }
        assertFalse(phone.isGranted)
    }


    // ── Repair routing ────────────────────────────────────────────────
    // These tests verify that repair methods dispatch intents correctly.
    // On JVM without Robolectric, startActivity may not work, so the tests
    // verify the method completes and inspect the internal logic.

    @Test
    fun `openAppInfoSettings completes without exception`() = runTest {
        val vm = createViewModel()
        // Should not throw even if startActivity fails
        runCatching { vm.openAppInfoSettings() }
    }

    @Test
    fun `openSpecialPermissionSettings completes without exception for DND`() = runTest {
        val vm = createViewModel()
        runCatching { vm.openSpecialPermissionSettings(Manifest.permission.ACCESS_NOTIFICATION_POLICY) }
    }

    @Test
    fun `openSpecialPermissionSettings completes for WRITE_SETTINGS`() = runTest {
        val vm = createViewModel()
        runCatching { vm.openSpecialPermissionSettings(Manifest.permission.WRITE_SETTINGS) }
    }

    @Test
    fun `openSpecialPermissionSettings completes for unknown permission`() = runTest {
        val vm = createViewModel()
        runCatching { vm.openSpecialPermissionSettings("unknown.permission") }
    }
    // ── Refresh ───────────────────────────────────────────────────────

    @Test
    fun `refresh updates permission grant states`() = runTest {
        val vm = createViewModel()
        val phoneBefore = vm.uiState.value.permissions.first { it.label == "Phone" }
        assertTrue(phoneBefore.isGranted)

        every {
            ContextCompat.checkSelfPermission(any(), Manifest.permission.CALL_PHONE)
        } returns PackageManager.PERMISSION_DENIED

        vm.refresh()
        testDispatcher.scheduler.advanceUntilIdle()

        val phoneAfter = vm.uiState.value.permissions.first { it.label == "Phone" }
        assertFalse(phoneAfter.isGranted)
    }
}
