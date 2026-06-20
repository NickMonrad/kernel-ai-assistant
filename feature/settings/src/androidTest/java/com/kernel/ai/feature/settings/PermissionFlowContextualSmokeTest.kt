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

        harness.clickText("Allow hands-free calling")
        harness.dismissSystemPermissionIfShown()

        harness.assertTextVisible("Open App Permissions")
        harness.assertTextVisible("Open dialer this time")
        harness.clickText("Open App Permissions")

        harness.assertTextVisible("App Permissions")
        harness.assertTextContainsVisible("Phone")
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

        harness.assertTextVisible("Allow Jandal to control Do Not Disturb?")
        harness.assertTextVisible(
            "Android requires special access before Jandal can turn Do Not Disturb on or off.",
        )
        harness.assertTextVisible("Open DND access settings")
        harness.assertTextVisible("Not now")
        harness.assertTextNotVisible("Do Not Disturb is on")

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
