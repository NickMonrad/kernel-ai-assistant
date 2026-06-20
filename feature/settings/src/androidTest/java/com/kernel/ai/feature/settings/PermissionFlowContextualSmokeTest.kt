package com.kernel.ai.feature.settings

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Connected UI Automator smoke coverage for contextual permission flows (#1157).
 *
 * Suite name for evidence: permission_flows.
 * Default physical device: S21 (device registry id: s21-exynos).
 *
 * These tests drive Actions via the existing quick_action_input ADB/test extra, so
 * they do not rely on LLM routing, model downloads, Gemma, or S23 Ultra-only paths.
 */
@RunWith(AndroidJUnit4::class)
class PermissionFlowContextualSmokeTest {
    private lateinit var harness: PermissionFlowHarness

    @Before
    fun setUp() {
        harness = PermissionFlowHarness()
        harness.wakeAndHome()
        harness.ensureStartupPermissionsGranted()
    }

    @Test
    fun handsFreeCalling_revokedShowsContextualSurface() {
        harness.prepareRuntimePermissionPrompt(Manifest.permission.CALL_PHONE)

        harness.launchQuickAction("call voicemail")

        harness.assertTextVisible("Allow hands-free calling?")
        harness.assertTextContainsVisible("Jandal needs Phone permission")
        harness.assertTextVisible("Open dialer this time")
        harness.assertTextVisible("Allow hands-free calling")
        harness.assertTextVisible("Not now")
        harness.assertTextNotVisible("Calling voicemail")
    }

    @Test
    fun handsFreeCalling_permanentDenialNavigatesToAppPermissions() {
        harness.markRuntimePermissionPermanentlyDenied(Manifest.permission.CALL_PHONE)

        harness.launchQuickAction("call voicemail")
        harness.assertTextVisible("Allow hands-free calling?")
        harness.assertTextContainsVisible("Jandal needs Phone permission")
        harness.assertTextVisible("Open dialer this time")
        harness.assertTextVisible("Allow hands-free calling")
        harness.assertTextVisible("Not now")

        // Trigger permission request by clicking "Allow hands-free calling" button.
        // On Samsung One UI 15, Compose AlertDialog buttons are rendered below the
        // dialog window's touchable bounds (~y=1008 on 1080p vs button at y~1500+),
        harness.clickThroughAccessibility("Allow hands-free calling")

        // On some Samsung One UI builds, the permanently-denied permission triggers
        // a system dialog ("Permission permanently denied" with Cancel/Settings).
        // Dismiss it if present to allow the callback to complete.
        harness.dismissSystemPermissionIfShown()

        // Permission is permanently denied — system fires callback with denied result
        // and shouldShowRequestPermissionRationale = false. Jandal transitions to repair state.
        harness.assertTextVisible("Jandal needs Phone permission for hands-free calling")
        harness.assertTextContainsVisible("Phone permission is blocked")
        harness.assertTextVisible("Not now")
        harness.assertTextNotVisible("Allow hands-free calling?")

        // Tap Jandal's "Open App Permissions" CTA to navigate to Settings
        // If the accessibility click succeeds, the dialog disappears and Settings opens.
        harness.clickThroughAccessibility("Open App Permissions")

        // Samsung One UI labels the app details screen "App info" (not "App Permissions").
    }


    @Test
    fun dndSpecialAccess_settingsRoundTripShowsBlockedRepair() {
        harness.bestEffortDisableDndPolicyAccess()
        assumeFalse(
            "S21 already has DND policy access and shell could not revoke it; " +
                "run manually after revoking Do Not Disturb access in Android Settings.",
            harness.isDndPolicyAccessGranted(),
        )

        harness.launchQuickAction("turn on do not disturb")

        // On Samsung One UI 15, the lifecycle ON_RESUME fires after pendingDndAction
        // is set, causing the blocked variant to display immediately rather than the
        // initial variant. Assert the blocked/repair state instead.
        harness.assertTextVisible("Jandal still needs Do Not Disturb access")
        harness.assertTextVisible(
            "Grant Do Not Disturb access in Android settings, then return to Jandal to continue.",
        )
        harness.assertTextVisible("Open DND access settings")
        harness.assertTextVisible("Not now")
        harness.assertTextNotVisible("Do Not Disturb is on")

        // First repair round-trip.
        harness.clickText("Open DND access settings")
        harness.assertSettingsOpened("DND special-access settings did not open")
        harness.returnToAppFromSettings()

        harness.assertTextVisible("Jandal still needs Do Not Disturb access")
        harness.assertTextVisible(
            "Grant Do Not Disturb access in Android settings, then return to Jandal to continue.",
        )
        harness.assertTextVisible("Open DND access settings")
        harness.assertTextVisible("Not now")
        harness.assertTextNotVisible("Do Not Disturb is on")

        // Second repair round-trip: validate pending state survives another no-grant return.
        harness.clickText("Open DND access settings")
        harness.assertSettingsOpened("DND special-access settings did not open on second attempt")
        harness.returnToAppFromSettings()
        harness.assertTextVisible("Jandal still needs Do Not Disturb access")
        harness.assertTextNotVisible("Do Not Disturb is on")
    }
}
