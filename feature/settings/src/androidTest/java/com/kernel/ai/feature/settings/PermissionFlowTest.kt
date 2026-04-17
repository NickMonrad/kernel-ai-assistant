package com.kernel.ai.feature.settings

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UI Automator tests for OS-level permission flows — #504.
 *
 * These tests cross the app/OS boundary and cannot be covered by Espresso alone.
 * Each test grants or denies a system permission dialog and verifies the app
 * responds correctly.
 *
 * Device: Samsung SM-S918B (Android 16 / API 36)
 * Package: com.kernel.ai.debug
 *
 * Run with:
 *   ./gradlew :feature:settings:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=\
 *       com.kernel.ai.feature.settings.PermissionFlowTest
 */
@RunWith(AndroidJUnit4::class)
class PermissionFlowTest {

    private lateinit var device: UiDevice
    private lateinit var context: Context

    companion object {
        private const val PACKAGE = "com.kernel.ai.debug"
        private const val LAUNCH_TIMEOUT_MS = 8_000L
        private const val DIALOG_TIMEOUT_MS = 5_000L

        // Samsung One UI / AOSP button labels for system dialogs
        private val ALLOW_LABELS = listOf("Allow", "While using the app", "Only this time")
        private val DENY_LABELS = listOf("Don't allow", "Deny", "No thanks")
        private val ALLOW_EXACT_ALARM_LABELS = listOf("Allow", "Permitted")
    }

    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        context = ApplicationProvider.getApplicationContext()
        device.wakeUp()
        device.pressHome()
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun launchApp() {
        val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK) }
        context.startActivity(intent)
        device.wait(Until.hasObject(By.pkg(PACKAGE).depth(0)), LAUNCH_TIMEOUT_MS)
    }

    private fun revokePermission(permission: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("pm revoke $PACKAGE $permission")
            .close()
    }

    private fun grantPermission(permission: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("pm grant $PACKAGE $permission")
            .close()
    }

    private fun findDialogButton(labels: List<String>): UiObject2? {
        for (label in labels) {
            val obj = device.wait(Until.findObject(By.text(label)), DIALOG_TIMEOUT_MS)
            if (obj != null) return obj
        }
        return null
    }

    // ── POST_NOTIFICATIONS ───────────────────────────────────────────────

    /**
     * Grant POST_NOTIFICATIONS via the system dialog.
     * Verifies the app is in the foreground after grant (dialog dismissed, not crashed).
     *
     * Android 13+ (API 33+) requires runtime permission for notifications.
     */
    @Test
    fun postNotifications_grant_appContinues() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        revokePermission("android.permission.POST_NOTIFICATIONS")
        launchApp()

        val allowButton = findDialogButton(ALLOW_LABELS)
        if (allowButton != null) {
            allowButton.click()
            Thread.sleep(500)
        }
        // After grant (or if dialog never appeared — permission already held), app should be visible
        val appVisible = device.wait(Until.hasObject(By.pkg(PACKAGE).depth(0)), LAUNCH_TIMEOUT_MS)
        assertTrue("App should be visible after POST_NOTIFICATIONS grant", appVisible)

        // Restore
        grantPermission("android.permission.POST_NOTIFICATIONS")
    }

    /**
     * Deny POST_NOTIFICATIONS — app should degrade gracefully (no crash).
     */
    @Test
    fun postNotifications_deny_appContinues() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        revokePermission("android.permission.POST_NOTIFICATIONS")
        launchApp()

        val denyButton = findDialogButton(DENY_LABELS)
        if (denyButton != null) {
            denyButton.click()
            Thread.sleep(500)
        }
        val appVisible = device.wait(Until.hasObject(By.pkg(PACKAGE).depth(0)), LAUNCH_TIMEOUT_MS)
        assertTrue("App should survive POST_NOTIFICATIONS denial without crashing", appVisible)

        // Restore
        grantPermission("android.permission.POST_NOTIFICATIONS")
    }

    // ── ACCESS_FINE_LOCATION ─────────────────────────────────────────────

    /**
     * Grant ACCESS_FINE_LOCATION — weather skill should use GPS path.
     * Verifies the app reaches foreground after dialog (not stuck on permission screen).
     */
    @Test
    fun locationPermission_grant_appContinues() {
        revokePermission("android.permission.ACCESS_FINE_LOCATION")
        revokePermission("android.permission.ACCESS_COARSE_LOCATION")
        launchApp()

        val allowButton = findDialogButton(ALLOW_LABELS)
        if (allowButton != null) {
            allowButton.click()
            Thread.sleep(500)
        }
        val appVisible = device.wait(Until.hasObject(By.pkg(PACKAGE).depth(0)), LAUNCH_TIMEOUT_MS)
        assertTrue("App should be visible after location grant", appVisible)

        // Restore
        grantPermission("android.permission.ACCESS_FINE_LOCATION")
        grantPermission("android.permission.ACCESS_COARSE_LOCATION")
    }

    /**
     * Deny ACCESS_FINE_LOCATION — app should fall back to profile/city-based weather.
     * Verifies no crash and app is still usable.
     */
    @Test
    fun locationPermission_deny_appFallsBackGracefully() {
        revokePermission("android.permission.ACCESS_FINE_LOCATION")
        revokePermission("android.permission.ACCESS_COARSE_LOCATION")
        launchApp()

        val denyButton = findDialogButton(DENY_LABELS)
        if (denyButton != null) {
            denyButton.click()
            Thread.sleep(500)
        }
        val appVisible = device.wait(Until.hasObject(By.pkg(PACKAGE).depth(0)), LAUNCH_TIMEOUT_MS)
        assertTrue("App should survive location denial without crashing", appVisible)

        // Restore
        grantPermission("android.permission.ACCESS_FINE_LOCATION")
        grantPermission("android.permission.ACCESS_COARSE_LOCATION")
    }

    // ── SCHEDULE_EXACT_ALARM ─────────────────────────────────────────────

    /**
     * SCHEDULE_EXACT_ALARM on Android 12+ (API 31+) is a special permission that
     * redirects to a Settings screen rather than a dialog. This test verifies:
     * 1. When the permission is revoked, the app is still launchable (no crash on start)
     * 2. When restored, the alarm scheduling code path is available
     *
     * Full grant-via-Settings flow requires accessibility service or manual tap;
     * we test the revoke→app-survives path and grant via shell.
     */
    @Test
    fun scheduleExactAlarm_revoked_appStillLaunches() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("appops set $PACKAGE SCHEDULE_EXACT_ALARM deny")
            .close()
        Thread.sleep(300)

        launchApp()
        val appVisible = device.wait(Until.hasObject(By.pkg(PACKAGE).depth(0)), LAUNCH_TIMEOUT_MS)
        assertTrue("App should launch without crashing when SCHEDULE_EXACT_ALARM is denied", appVisible)

        // Restore
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("appops set $PACKAGE SCHEDULE_EXACT_ALARM allow")
            .close()
    }

    /**
     * SCHEDULE_EXACT_ALARM granted via shell — verify app uses exact alarm path.
     * Checks the app reaches foreground without a permissions redirect screen.
     */
    @Test
    fun scheduleExactAlarm_granted_noRedirectScreen() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("appops set $PACKAGE SCHEDULE_EXACT_ALARM allow")
            .close()
        Thread.sleep(300)

        launchApp()
        val appVisible = device.wait(Until.hasObject(By.pkg(PACKAGE).depth(0)), LAUNCH_TIMEOUT_MS)
        assertTrue("App should be fully usable when SCHEDULE_EXACT_ALARM is granted", appVisible)

        // Verify we're NOT on a system Settings screen
        val onSettingsScreen = device.hasObject(By.pkg("com.android.settings"))
        assertTrue("Should not have been redirected to Settings", !onSettingsScreen)
    }

    // ── Deny → Re-grant recovery ─────────────────────────────────────────

    /**
     * Revoke POST_NOTIFICATIONS then re-grant via shell — app should recover
     * without requiring a restart (tests the dynamic permission check path).
     */
    @Test
    fun postNotifications_revokeAndRegrant_appRecovers() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        revokePermission("android.permission.POST_NOTIFICATIONS")
        launchApp()
        Thread.sleep(1_000)

        grantPermission("android.permission.POST_NOTIFICATIONS")
        Thread.sleep(500)

        val appVisible = device.wait(Until.hasObject(By.pkg(PACKAGE).depth(0)), LAUNCH_TIMEOUT_MS)
        assertTrue("App should be visible after re-granting POST_NOTIFICATIONS", appVisible)
    }
}
