package com.kernel.ai.feature.settings

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import java.io.FileInputStream
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * Shared UI Automator helpers for contextual permission-flow smoke tests (#1157).
 *
 * The harness intentionally drives app-owned surfaces via ADB/test intent extras
 * instead of LLM inference. System Settings automation is limited to stable
 * launch/return checks; special-access toggles remain best-effort because OEM
 * Settings screens differ across Samsung One UI and AOSP.
 */
internal class PermissionFlowHarness(
    val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()),
    val context: Context = ApplicationProvider.getApplicationContext(),
) {
    fun wakeAndHome() {
        device.wakeUp()
        executeShell("input keyevent KEYCODE_WAKEUP")
        executeShell("wm dismiss-keyguard")
        executeShell("input swipe 540 2000 540 500 200")
        unlockPin()?.let { pin ->
            executeShell("input text ${pin.replace(" ", "%s")}")
            executeShell("input keyevent ENTER")
        }
        // No HOME key press here — PIN unlock on Samsung One UI already returns
        // to the home screen. A trailing HOME event would race with the test's
        // am start -S force-stop + launch, causing the app to immediately background.
        device.waitForIdle()
    }

    fun ensureStartupPermissionsGranted() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            grantRuntimePermission(Manifest.permission.POST_NOTIFICATIONS)
        }
        grantRuntimePermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        grantRuntimePermission(Manifest.permission.ACCESS_FINE_LOCATION)
        grantRuntimePermission(Manifest.permission.READ_CONTACTS)
    }

    fun launchClean() {
        executeShell("am start -W -S -n $PACKAGE/$MAIN_ACTIVITY")
        assertAppForeground("App did not launch")
    }

    fun launchQuickAction(query: String) {
        executeShell(
            "am start -W -S -n $PACKAGE/$MAIN_ACTIVITY " +
                "--es quick_action_input_encoded ${Uri.encode(query)} " +
                "--ez quick_action_is_voice false",
        )
        assertAppForeground("App did not launch")
    }

    fun grantRuntimePermission(permission: String) {
        executeShell("pm grant $PACKAGE $permission")
    }

    fun revokeRuntimePermission(permission: String) {
        executeShell("pm revoke $PACKAGE $permission")
    }

    fun clearRuntimePermissionFlags(permission: String) {
        executeShell("pm clear-permission-flags $PACKAGE $permission user-set user-fixed")
    }

    fun prepareRuntimePermissionPrompt(permission: String) {
        clearRuntimePermissionFlags(permission)
        revokeRuntimePermission(permission)
    }

    fun markRuntimePermissionPermanentlyDenied(permission: String) {
        revokeRuntimePermission(permission)
        executeShell("pm set-permission-flags $PACKAGE $permission user-set user-fixed")
    }

    fun bestEffortDisableDndPolicyAccess() {
        executeShell("cmd notification disallow_dnd $PACKAGE")
    }

    fun isDndPolicyAccessGranted(): Boolean {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return notificationManager.isNotificationPolicyAccessGranted
    }

    fun waitForText(text: String, timeoutMs: Long = DIALOG_TIMEOUT_MS): UiObject2 {
        val obj = device.wait(Until.findObject(By.text(text)), timeoutMs)
        assertNotNull("Expected text not visible: $text", obj)
        return obj
    }

    fun waitForTextContains(text: String, timeoutMs: Long = DIALOG_TIMEOUT_MS): UiObject2 {
        val obj = device.wait(Until.findObject(By.textContains(text)), timeoutMs)
        assertNotNull("Expected text containing '$text' not visible", obj)
        return obj
    }

    fun assertTextVisible(text: String, timeoutMs: Long = DIALOG_TIMEOUT_MS) {
        waitForText(text, timeoutMs)
    }

    fun assertTextContainsVisible(text: String, timeoutMs: Long = DIALOG_TIMEOUT_MS) {
        waitForTextContains(text, timeoutMs)
    }

    fun assertTextNotVisible(text: String) {
        assertFalse("Unexpected visible text: $text", device.hasObject(By.text(text)))
    }

    fun clickText(text: String, timeoutMs: Long = DIALOG_TIMEOUT_MS) {
        val obj = waitForText(text, timeoutMs)
        if (obj.isClickable) {
            obj.click()
        } else {
            // Text may be inside a TextButton; click the parent to avoid
            // tapping outside dialog bounds and triggering onDismissRequest.
            val parent = obj.parent
            if (parent != null && parent.isClickable) {
                parent.click()
            } else {
                obj.click()
            }
        }
    }
    fun clickClickableContainingText(text: String, timeoutMs: Long = DIALOG_TIMEOUT_MS) {
        // Find a clickable ancestor (TextButton) that contains the text node.
        val selector = By.clickable(true).hasChild(By.text(text))
        val obj = device.wait(Until.findObject(selector), timeoutMs)
        assertNotNull("Expected clickable element containing text '$text' not found", obj)
        obj.click()
    }

    fun assertAppForeground(message: String = "App should be in foreground") {
        assertTrue(message, waitForPackageForeground(PACKAGE, LAUNCH_TIMEOUT_MS))
    }

    fun assertSettingsOpened(message: String = "Expected Android Settings to open") {
        val opened = device.wait(
            Until.hasObject(By.pkg(SETTINGS_PACKAGE)),
            SETTINGS_TIMEOUT_MS,
        ) || device.currentPackageName.orEmpty().contains("settings", ignoreCase = true)
        assertTrue(message, opened)
    }

    fun returnToAppFromSettings() {
        device.pressBack()
        assertTrue("Expected to return to Jandal from Settings", waitForPackageForeground(PACKAGE, LAUNCH_TIMEOUT_MS))
    }

    fun dismissSystemPermissionIfShown() {
        for (label in SYSTEM_DENY_LABELS + SYSTEM_ALLOW_LABELS) {
            val obj = device.wait(Until.findObject(By.text(label)), SHORT_TIMEOUT_MS)
            if (obj != null) {
                obj.click()
                return
            }
        }
    }

    private fun waitForPackageForeground(packageName: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (device.currentPackageName == packageName) return true
            val activities = shellOutput("dumpsys activity activities")
            if (activities.contains("ResumedActivity") && activities.contains(packageName)) {
                return true
            }
            Thread.sleep(500)
        }
        return false
    }

    private fun shellOutput(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
        return try {
            FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
        } finally {
            descriptor.close()
        }
    }

    private fun unlockPin(): String? =
        InstrumentationRegistry.getArguments().getString("unlock_pin")
            ?.takeIf { it.isNotBlank() }


    private fun executeShell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
            .close()
    }

    companion object {
        const val PACKAGE = "com.kernel.ai.debug"
        private const val MAIN_ACTIVITY = "com.kernel.ai.MainActivity"
        private const val SETTINGS_PACKAGE = "com.android.settings"
        private const val LAUNCH_TIMEOUT_MS = 20_000L
        private const val DIALOG_TIMEOUT_MS = 6_000L
        private const val SETTINGS_TIMEOUT_MS = 8_000L
        private const val SHORT_TIMEOUT_MS = 750L

        private val SYSTEM_ALLOW_LABELS = listOf("Allow", "While using the app", "Only this time")
        private val SYSTEM_DENY_LABELS = listOf("Don't allow", "Deny", "No thanks")
    }
}
