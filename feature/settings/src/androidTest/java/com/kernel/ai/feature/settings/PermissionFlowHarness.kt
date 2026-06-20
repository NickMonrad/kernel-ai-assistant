package com.kernel.ai.feature.settings

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import java.io.FileInputStream
import android.view.accessibility.AccessibilityNodeInfo
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
        // Samsung One UI: wm dismiss-keyguard does not work (stays locked).
        // Use a screen off → on cycle to reset the lock screen, then swipe up.
        executeShell("input keyevent KEYCODE_SLEEP")
        device.waitForIdle(500)
        executeShell("input keyevent KEYCODE_WAKEUP")
        device.waitForIdle(1000)
        // Swipe from bottom to dismiss swipe-to-unlock keyguard.
        // Use an extended duration (500ms) for reliable gesture injection.
        executeShell("input swipe 540 2200 540 500 500")
        // Wait for lock screen to fully dismiss before proceeding.
        device.waitForIdle(1500)
        // Optional PIN unlock if the device uses a PIN/pattern (handled by test runner arg).
        unlockPin()?.let { pin ->
            executeShell("input text ${pin.replace(" ", "%s")}")
            executeShell("input keyevent ENTER")
            device.waitForIdle(1000)
        }
        // Finally press HOME to ensure launcher is foreground.
        device.pressHome()
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

    fun clickClickableAncestor(text: String, timeoutMs: Long = DIALOG_TIMEOUT_MS) {
        // Find the text node, then walk up parents to find the first clickable ancestor.
        // This works for deeply nested Compose TextButton structures where
        // By.clickable(true).hasChild(By.text(...)) fails due to depth.
        val textNode = waitForText(text, timeoutMs)
        var node: UiObject2 = textNode
        while (node != textNode.parent && !node.isClickable) {
            val parent = node.parent ?: break
            node = parent
        }
        if (node.isClickable) {
            node.click()
        } else {
            // Fallback: click the original text node
            textNode.click()
        }
    }

    /**
     * Click a text label, resolving coordinate-based clicks through Compose dialog
     * button regions.
     *
     * Two strategies, tried in order:
     * 1. Coordinate-based click at the text node's visible bounds center via
     *    device.click(). This bypasses UiAutomator's gesture injection issues on
     *    Samsung One UI 15 Compose AlertDialog where button elements are found
     *    but gesture clicks may not reach the Compose onClick handler.
     * 2. Accessibility-based click: walk up to a clickable ancestor and call
     *    performAction(ACTION_CLICK) via the accessibility API (no touch dispatch).
     *
     * Retries for [timeoutMs] with 200ms polling between strategies.
     *
     * @return true if click was performed, false if text not found or click failed.
     */
    fun clickThroughAccessibility(text: String, timeoutMs: Long = DIALOG_TIMEOUT_MS): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            // Strategy 1: Coordinate-based click at text's visible bounds center
            val textObj = device.wait(Until.findObject(By.text(text)), 500)
            if (textObj != null) {
                val bounds = textObj.visibleBounds
                if (device.click(bounds.centerX(), bounds.centerY())) {
                    return true
                }
            }

            // Strategy 2: Accessibility-based click (perform action on clickable ancestor)
            val clickedAccessibility = clickThroughAccessibilityImpl(text)
            if (clickedAccessibility) return true

            Thread.sleep(200)
        }
        return false
    }

    /**
     * Accessibility-based click implementation. Finds text node via accessibility API,
     * walks up to clickable ancestor, calls performAction(ACTION_CLICK).
     */
    private fun clickThroughAccessibilityImpl(text: String): Boolean {
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val root = uiAutomation.rootInActiveWindow ?: return false
        try {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                val clickable = findClickableAncestor(node)
                if (clickable != null) {
                    val result = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    clickable.recycle()
                    node.recycle()
                    return result
                }
                node.recycle()
            }
        } finally {
            root.recycle()
        }
        return false
    }

    /**
     * Walk up the AccessibilityNodeInfo parent chain to find the first
     * clickable ancestor. Returns null if the chain ends without finding one.
     *
     * The returned node is owned by the caller (must be recycled). The
     * intermediate parent nodes are recycled before return.
     */
    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val visited = mutableListOf<AccessibilityNodeInfo>()
        try {
            var current: AccessibilityNodeInfo? = node
            while (current != null) {
                if (current.isClickable) return current
                val parent = current.parent
                visited.add(current)
                current = parent
            }
            return null
        } finally {
            visited.forEach { n ->
                if (n !== node) n.recycle()
            }
        }
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


    internal fun executeShell(command: String) {
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
        private val SYSTEM_DENY_LABELS = listOf("Don't allow", "Deny", "No thanks", "Cancel")
    }
}
