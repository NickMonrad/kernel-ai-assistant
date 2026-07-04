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
 * Instrumented Compose UI tests for the write-settings special-access dialog.
 *
 * The dialog is rendered in production via [PermissionOverlayDialog] →
 * BasicAlertDialog. This test uses [PermissionOverlayDialog] exactly as
 * [ActionsScreen] does.
 *
 * OS-boundary notes:
 *   - The "Open settings access" CTA triggers [ActionsViewModel.onWriteSettingsOpenSettings],
 *     which emits [ActionsViewModel.UiEvent.OpenWriteSettings]. The actual
 *     [android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS] intent launch is
 *     handled in [ActionsScreen]'s LaunchedEffect event collector via
 *     [android.content.Context.startActivity]. This final OS call is not directly
 *     verified at the Compose UI layer — it is covered by the ViewModel unit test
 *     (`write settings open settings emits OpenWriteSettings event`) and by
 *     manual smoke test.
 *   - Full grant/revoke automation (toggling WRITE_SETTINGS) is OEM-specific
 *     and inherently unstable across Samsung One UI vs AOSP.
 *   - Backdrop tap: uses [Instrumentation.sendPointerSync] with real MotionEvent
 *     injection rather than Compose [performTouchInput], because Compose touch
 *     injection does not reliably reach the scrim Surface inside Compose Dialog
 *     windows (coordinate-system mismatch between activity and dialog views).
 */
class ActionsWriteSettingsDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ── Initial state ──────────────────────────────────────────────────────────

    @Test
    fun showsInitialDialogWithExpectedTitleAndBody() {
        val writeSettingsState = mutableStateOf<ActionsViewModel.WriteSettingsState?>(null)
        composeTestRule.setContent {
            WriteSettingsDialogContent(
                writeSettingsState = writeSettingsState,
                onOpenSettings = {},
                onDismiss = {},
            )
        }

        writeSettingsState.value = ActionsViewModel.WriteSettingsState(
            intentName = "set_brightness",
        )

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Allow Jandal to modify system settings?")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "Android requires special access before Jandal can change settings such as screen brightness."
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText("Open settings access")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Not now")
            .assertIsDisplayed()
    }

    // ── Blocked state ──────────────────────────────────────────────────────────

    @Test
    fun showsBlockedDialogWithExpectedTitleAndBody() {
        val writeSettingsState = mutableStateOf<ActionsViewModel.WriteSettingsState?>(null)
        composeTestRule.setContent {
            WriteSettingsDialogContent(
                writeSettingsState = writeSettingsState,
                onOpenSettings = {},
                onDismiss = {},
            )
        }

        writeSettingsState.value = ActionsViewModel.WriteSettingsState(
            intentName = "set_brightness",
            isAccessBlocked = true,
        )

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Jandal still needs settings access")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "Jandal still does not have access to modify system settings."
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText("Open settings access")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Not now")
            .assertIsDisplayed()
    }

    // ── Hidden when state is null ──────────────────────────────────────────────

    @Test
    fun doesNotShowDialogWhenWriteSettingsStateIsNull() {
        val writeSettingsState = mutableStateOf<ActionsViewModel.WriteSettingsState?>(null)
        composeTestRule.setContent {
            WriteSettingsDialogContent(
                writeSettingsState = writeSettingsState,
                onOpenSettings = {},
                onDismiss = {},
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Allow Jandal to modify system settings?")
            .assertIsNotDisplayed()
        composeTestRule.onNodeWithText("Jandal still needs settings access")
            .assertIsNotDisplayed()
    }

    @Test
    fun hidesDialogWhenStateReturnsToNull() {
        val writeSettingsState = mutableStateOf<ActionsViewModel.WriteSettingsState?>(
            ActionsViewModel.WriteSettingsState(intentName = "set_brightness")
        )
        composeTestRule.setContent {
            WriteSettingsDialogContent(
                writeSettingsState = writeSettingsState,
                onOpenSettings = {},
                onDismiss = {},
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Allow Jandal to modify system settings?")
            .assertIsDisplayed()

        writeSettingsState.value = null
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Allow Jandal to modify system settings?")
            .assertIsNotDisplayed()
    }

    // ── CTA: "Open settings access" ────────────────────────────────────────────

    @Test
    fun openSettingsButtonTriggersCallback() {
        val writeSettingsState = mutableStateOf<ActionsViewModel.WriteSettingsState?>(
            ActionsViewModel.WriteSettingsState(intentName = "set_brightness")
        )
        var openSettingsCalled = false
        composeTestRule.setContent {
            WriteSettingsDialogContent(
                writeSettingsState = writeSettingsState,
                onOpenSettings = { openSettingsCalled = true },
                onDismiss = {},
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Open settings access").performClick()
        assertTrue("Open settings access callback was not invoked", openSettingsCalled)
    }

    // ── CTA: "Not now" ─────────────────────────────────────────────────────────

    @Test
    fun notNowButtonTriggersDismiss() {
        val writeSettingsState = mutableStateOf<ActionsViewModel.WriteSettingsState?>(
            ActionsViewModel.WriteSettingsState(intentName = "set_brightness")
        )
        var dismissCalled = false
        composeTestRule.setContent {
            WriteSettingsDialogContent(
                writeSettingsState = writeSettingsState,
                onOpenSettings = {},
                onDismiss = { dismissCalled = true; writeSettingsState.value = null },
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Not now").performClick()
        assertTrue("Not now dismiss callback was not invoked", dismissCalled)
        composeTestRule.onNodeWithText("Allow Jandal to modify system settings?")
            .assertIsNotDisplayed()
    }

    @Test
    fun dialogDismissesViaBackdropTap() {
        val writeSettingsState = mutableStateOf<ActionsViewModel.WriteSettingsState?>(
            ActionsViewModel.WriteSettingsState(intentName = "set_brightness")
        )
        var dismissCalled = false
        composeTestRule.setContent {
            WriteSettingsDialogContent(
                writeSettingsState = writeSettingsState,
                onOpenSettings = {},
                onDismiss = { dismissCalled = true; writeSettingsState.value = null },
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Allow Jandal to modify system settings?")
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
        composeTestRule.onNodeWithText("Allow Jandal to modify system settings?")
            .assertIsNotDisplayed()
    }

    // ── Test wrapper composable ────────────────────────────────────────────────
    /**
     * Wraps [PermissionOverlayDialog] with the same settings-specific title, body,
     * and actions that [ActionsScreen] uses, driven by test-controllable state.
     *
     * @param writeSettingsState The write-settings permission state. Dialog shown when non-null.
     * @param onOpenSettings Called when "Open settings access" is tapped.
     * @param onDismiss Called when dismiss is triggered (scrim, back, "Not now").
     */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun WriteSettingsDialogContent(
        writeSettingsState: MutableState<ActionsViewModel.WriteSettingsState?>,
        onOpenSettings: () -> Unit,
        onDismiss: () -> Unit,
    ) {
        writeSettingsState.value?.let { state ->
            PermissionOverlayDialog(
                title = if (state.isAccessBlocked) {
                    "Jandal still needs settings access"
                } else {
                    "Allow Jandal to modify system settings?"
                },
                body = if (state.isAccessBlocked) {
                    "Jandal still does not have access to modify " +
                        "system settings."
                } else {
                    "Android requires special access before Jandal can " +
                        "change settings such as screen brightness."
                },
                actions = listOf(
                    PermissionDialogAction(
                        label = "Open settings access",
                        testTag = "permission_dialog_write_settings_open_settings",
                        onClick = onOpenSettings,
                    ),
                    PermissionDialogAction(
                        label = "Not now",
                        testTag = "permission_dialog_write_settings_not_now",
                        onClick = onDismiss,
                    ),
                ),
                dialogTestTag = "permission_dialog_write_settings",
                onDismissRequest = onDismiss,
            )
        }
    }
}
