package com.kernel.ai.feature.chat

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Instrumented Compose UI tests for the write-settings special-access AlertDialog.
 *
 * The dialog is rendered inline in [ActionsScreen]. This test verifies the dialog
 * rendering and CTA callbacks in isolation using a test wrapper that replicates
 * the same conditional [AlertDialog] structure.
 *
 * OS-boundary notes:
 *   - The "Open settings access" CTA triggers [ActionsViewModel.onWriteSettingsOpenSettings],
 *     which emits [ActionsViewModel.UiEvent.OpenWriteSettings]. The actual
 *     [android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS] intent launch is
 *     handled in [ActionsScreen]'s LaunchedEffect event collector via
 *     [android.content.Context.startActivity]. This final OS call is not directly
 *     verified at the Compose UI layer — it is covered by the ViewModel unit test
 *     (`write settings open settings emits OpenWriteSettings event`) and by manual
 *     smoke test.
 *   - Full grant/revoke automation (toggling the write-settings switch) is
 *     OEM-specific and may be unstable across Samsung One UI vs AOSP — no stable
 *     third-party helper exists for this.
 *   - The lifecycle resume path (ON_RESUME → onWriteSettingsResumeCheck) is tested
 *     at the ViewModel unit-test layer (retry-with-grant and blocked-without-grant).
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

        // Click outside the dialog (top-left corner of the screen on the scrim)
        // to trigger AlertDialog's onDismissRequest.
        composeTestRule.onRoot().performTouchInput {
            click(position = Offset(0f, 0f))
        }

        assertTrue("Backdrop dismiss should trigger dismiss callback", dismissCalled)
        composeTestRule.onNodeWithText("Allow Jandal to modify system settings?")
            .assertIsNotDisplayed()
    }

    // ── Test wrapper composable ────────────────────────────────────────────────

    /**
     * Replicates the exact [AlertDialog] structure from [ActionsScreen].
     *
     * Kept as a local composable so the test mirrors the production rendering
     * without depending on the full [ActionsViewModel] or lifecycle infrastructure.
     */
    @Composable
    private fun WriteSettingsDialogContent(
        writeSettingsState: MutableState<ActionsViewModel.WriteSettingsState?>,
        onOpenSettings: () -> Unit,
        onDismiss: () -> Unit,
    ) {
        writeSettingsState.value?.let { state ->
            AlertDialog(
                onDismissRequest = onDismiss,
                title = {
                    Text(
                        if (state.isAccessBlocked) {
                            "Jandal still needs settings access"
                        } else {
                            "Allow Jandal to modify system settings?"
                        },
                    )
                },
                text = {
                    Text(
                        if (state.isAccessBlocked) {
                            "Jandal still does not have access to modify system settings."
                        } else {
                            "Android requires special access before Jandal can " +
                                "change settings such as screen brightness."
                        },
                    )
                },
                confirmButton = {
                    TextButton(onClick = onOpenSettings) {
                        Text("Open settings access")
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text("Not now")
                    }
                },
            )
        }
    }
}
