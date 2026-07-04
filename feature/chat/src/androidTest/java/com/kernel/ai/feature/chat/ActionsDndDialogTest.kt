package com.kernel.ai.feature.chat

import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.kernel.ai.core.ui.permissions.PermissionDialogAction
import com.kernel.ai.core.ui.permissions.PermissionOverlayDialog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
/**
 * Instrumented Compose UI tests for the DND special-access dialog.
 *
 * The dialog is rendered in production via [PermissionOverlayDialog] →
 * BasicAlertDialog. This test uses [PermissionOverlayDialog] exactly as
 * [ActionsScreen] does.
 *
 * OS-boundary notes:
 *   - The "Open DND access settings" CTA triggers [ActionsViewModel.onDndOpenSettings],
 *     which emits [ActionsViewModel.UiEvent.OpenDndSettings]. The actual
 *     [android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS] intent launch
 *     is handled in [ActionsScreen]'s LaunchedEffect event collector (lines 280–289)
 *     via [android.content.Context.startActivity]. This final OS call is not directly
 *     verified at the Compose UI layer — it is covered by the ViewModel unit test
 *     (`dnd open settings emits OpenDndSettings event`) and by manual smoke test.
 *   - Full grant/revoke automation (toggling the notification-policy-access switch)
 *     is OEM-specific and inherently unstable across Samsung One UI vs AOSP — no
 *     stable third-party helper exists for this.
 *   - The lifecycle resume path (ON_RESUME → onDndResumeCheck) is tested at the
 *     ViewModel unit-test layer (retry-with-grant and blocked-without-grant).
 *   - Backdrop tap: uses [Instrumentation.sendPointerSync] with real MotionEvent
 *     injection rather than Compose [performTouchInput], because Compose touch
 *     injection does not reliably reach the scrim Surface inside Compose Dialog
 *     windows (coordinate-system mismatch between activity and dialog views).
 */
class ActionsDndDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ── Initial state ──────────────────────────────────────────────────────────

    @Test
    fun showsInitialDialogWithExpectedTitleAndBody() {
        val dndState = mutableStateOf<ActionsViewModel.DndState?>(null)
        composeTestRule.setContent {
            DndDialogContent(dndState = dndState, onOpenSettings = {}, onDismiss = {})
        }

        dndState.value = ActionsViewModel.DndState(
            intentName = "toggle_dnd_on",
            enabled = true,
        )

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Allow Jandal to control Do Not Disturb?")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "Android requires special access before Jandal can turn Do Not Disturb on or off."
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText("Open DND access settings")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Not now")
            .assertIsDisplayed()
    }

    // ── Blocked state ──────────────────────────────────────────────────────────

    @Test
    fun showsBlockedDialogWithExpectedTitleAndBody() {
        val dndState = mutableStateOf<ActionsViewModel.DndState?>(null)
        composeTestRule.setContent {
            DndDialogContent(dndState = dndState, onOpenSettings = {}, onDismiss = {})
        }

        dndState.value = ActionsViewModel.DndState(
            intentName = "toggle_dnd_on",
            enabled = true,
            isAccessBlocked = true,
        )

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Jandal still needs Do Not Disturb access")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "Grant Do Not Disturb access in Android settings, " +
                "then return to Jandal to continue."
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText("Open DND access settings")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Not now")
            .assertIsDisplayed()
    }

    // ── Hidden when state is null ──────────────────────────────────────────────

    @Test
    fun doesNotShowDialogWhenDndStateIsNull() {
        val dndState = mutableStateOf<ActionsViewModel.DndState?>(null)
        composeTestRule.setContent {
            DndDialogContent(dndState = dndState, onOpenSettings = {}, onDismiss = {})
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Allow Jandal to control Do Not Disturb?")
            .assertIsNotDisplayed()
        composeTestRule.onNodeWithText("Jandal still needs Do Not Disturb access")
            .assertIsNotDisplayed()
    }

    @Test
    fun hidesDialogWhenStateReturnsToNull() {
        val dndState = mutableStateOf<ActionsViewModel.DndState?>(
            ActionsViewModel.DndState(intentName = "toggle_dnd_on", enabled = true)
        )
        composeTestRule.setContent {
            DndDialogContent(dndState = dndState, onOpenSettings = {}, onDismiss = {})
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Allow Jandal to control Do Not Disturb?")
            .assertIsDisplayed()

        dndState.value = null
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Allow Jandal to control Do Not Disturb?")
            .assertIsNotDisplayed()
    }

    // ── CTA: "Open DND access settings" ────────────────────────────────────────

    @Test
    fun openSettingsButtonTriggersCallback() {
        val dndState = mutableStateOf<ActionsViewModel.DndState?>(
            ActionsViewModel.DndState(intentName = "toggle_dnd_on", enabled = true)
        )
        var openSettingsCalled = false
        composeTestRule.setContent {
            DndDialogContent(
                dndState = dndState,
                onOpenSettings = { openSettingsCalled = true },
                onDismiss = {},
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Open DND access settings").performClick()
        assertTrue("Open DND access settings callback was not invoked", openSettingsCalled)
    }

    // ── CTA: "Not now" ─────────────────────────────────────────────────────────

    @Test
    fun notNowButtonTriggersDismiss() {
        val dndState = mutableStateOf<ActionsViewModel.DndState?>(
            ActionsViewModel.DndState(intentName = "toggle_dnd_on", enabled = true)
        )
        var dismissCalled = false
        composeTestRule.setContent {
            DndDialogContent(
                dndState = dndState,
                onOpenSettings = {},
                onDismiss = { dismissCalled = true; dndState.value = null },
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Not now").performClick()
        assertTrue("Not now dismiss callback was not invoked", dismissCalled)
        composeTestRule.onNodeWithText("Allow Jandal to control Do Not Disturb?")
            .assertIsNotDisplayed()
    }

    @Test
    fun dialogDismissesViaBackdropTap() {
        val dndState = mutableStateOf<ActionsViewModel.DndState?>(
            ActionsViewModel.DndState(intentName = "toggle_dnd_on", enabled = true)
        )
        var dismissCalled = false
        composeTestRule.setContent {
            DndDialogContent(
                dndState = dndState,
                onOpenSettings = {},
                onDismiss = { dismissCalled = true; dndState.value = null },
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Allow Jandal to control Do Not Disturb?")
            .assertIsDisplayed()
        assertFalse("Dismiss should not have been called before interaction", dismissCalled)
        // Tap the dialog scrim via real MotionEvent injection through the
        // Android input pipeline (not Compose performTouchInput, which has
        // coordinate-system issues with Compose Dialog windows).
        // BasicAlertDialog's scrim Surface fills the dialog window; tapping
        // the left-edge area (50px from left, 200px from top) lands on the
        // scrim (outside the centered card) and triggers onDismissRequest.
        val downTime = SystemClock.uptimeMillis()
        InstrumentationRegistry.getInstrumentation().sendPointerSync(
            MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, 50f, 200f, 0)
        )
        InstrumentationRegistry.getInstrumentation().sendPointerSync(
            MotionEvent.obtain(downTime, downTime + 50, MotionEvent.ACTION_UP, 50f, 200f, 0)
        )
        composeTestRule.waitForIdle()
        assertTrue("Backdrop dismiss should trigger dismiss callback", dismissCalled)
        composeTestRule.onNodeWithText("Allow Jandal to control Do Not Disturb?")
            .assertIsNotDisplayed()
    }

    // ── Test wrapper composable ────────────────────────────────────────────────
    /**
     * Wraps [PermissionOverlayDialog] with the same DND-specific title, body,
     * and actions that [ActionsScreen] uses, driven by test-controllable state.
     *
     * @param dndState The DND permission state. Dialog shown when non-null.
     * @param onOpenSettings Called when "Open DND access settings" is tapped.
     * @param onDismiss Called when dismiss is triggered (scrim, back, "Not now").
     */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun DndDialogContent(
        dndState: MutableState<ActionsViewModel.DndState?>,
        onOpenSettings: () -> Unit,
        onDismiss: () -> Unit,
    ) {
        dndState.value?.let { state ->
            PermissionOverlayDialog(
                title = if (state.isAccessBlocked) {
                    "Jandal still needs Do Not Disturb access"
                } else {
                    "Allow Jandal to control Do Not Disturb?"
                },
                body = if (state.isAccessBlocked) {
                    "Grant Do Not Disturb access in Android settings, " +
                        "then return to Jandal to continue."
                } else {
                    "Android requires special access before Jandal can " +
                        "turn Do Not Disturb on or off."
                },
                actions = listOf(
                    PermissionDialogAction(
                        label = "Open DND access settings",
                        testTag = "permission_dialog_dnd_open_settings",
                        onClick = onOpenSettings,
                    ),
                    PermissionDialogAction(
                        label = "Not now",
                        testTag = "permission_dialog_dnd_not_now",
                        onClick = onDismiss,
                    ),
                ),
                dialogTestTag = "permission_dialog_dnd",
                onDismissRequest = onDismiss,
            )
        }
    }
}
