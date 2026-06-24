package com.kernel.ai.feature.settings

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.unmockkAll
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
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
import org.junit.jupiter.api.Assertions.assertNotNull
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
        // Allow ContextCompat.checkSelfPermission to be stubbed per-test
        mockkStatic(ContextCompat::class)
        // Default: all permissions granted
        every {
            ContextCompat.checkSelfPermission(any(), any())
        } returns PackageManager.PERMISSION_GRANTED
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
        assertTrue("Phone" in labels, "Phone row present")
        assertTrue("Microphone" in labels, "Microphone row present")
        assertTrue("Notifications" in labels, "Notifications row present")
        assertTrue("Location" in labels, "Location row present")
        assertTrue("Contacts" in labels, "Contacts row present")
        assertTrue("Calendar" in labels, "Calendar row present")
    }

    @Test
    fun `dashboard includes expected special access rows`() = runTest {
        val nm: NotificationManager = mockk()
        every { context.getSystemService(Context.NOTIFICATION_SERVICE) } returns nm
        every { nm.isNotificationPolicyAccessGranted } returns true
        mockkStatic(Settings.System::class)
        every { Settings.System.canWrite(context) } returns true

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
            // Descriptions should describe the feature, not say "required"
            assertFalse(p.description.contains("required", ignoreCase = true),
                "Description for '${p.label}' should not say 'required': '${p.description}'")
        }
    }

    @Test
    fun `runtime permissions map to correct manifest constants`() = runTest {
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
        mockkStatic(Settings.System::class)
        every { Settings.System.canWrite(context) } returns true

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

    @Test
    fun `openAppInfoSettings emits app details intent`() = runTest {
        val vm = createViewModel()
        val intentSlot = slot<Intent>()
        every { context.startActivity(capture(intentSlot)) } just Runs

        vm.openAppInfoSettings()

        verify { context.startActivity(capture(intentSlot)) }
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intentSlot.captured.action)
    }

    @Test
    fun `openSpecialPermissionSettings for DND emits notification policy intent`() = runTest {
        val vm = createViewModel()
        val intentSlot = slot<Intent>()
        every { context.startActivity(capture(intentSlot)) } just Runs

        vm.openSpecialPermissionSettings(Manifest.permission.ACCESS_NOTIFICATION_POLICY)

        verify { context.startActivity(capture(intentSlot)) }
        assertEquals(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS, intentSlot.captured.action)
    }

    @Test
    fun `openSpecialPermissionSettings for WRITE_SETTINGS emits manage write settings intent`() = runTest {
        val vm = createViewModel()
        val intentSlot = slot<Intent>()
        every { context.startActivity(capture(intentSlot)) } just Runs

        vm.openSpecialPermissionSettings(Manifest.permission.WRITE_SETTINGS)

        verify { context.startActivity(capture(intentSlot)) }
        assertEquals(Settings.ACTION_MANAGE_WRITE_SETTINGS, intentSlot.captured.action)
    }

    @Test
    fun `openSpecialPermissionSettings for unknown permission falls back to app info`() = runTest {
        val vm = createViewModel()
        val intentSlot = slot<Intent>()
        every { context.startActivity(capture(intentSlot)) } just Runs

        vm.openSpecialPermissionSettings("unknown.permission")

        verify { context.startActivity(capture(intentSlot)) }
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intentSlot.captured.action)
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
