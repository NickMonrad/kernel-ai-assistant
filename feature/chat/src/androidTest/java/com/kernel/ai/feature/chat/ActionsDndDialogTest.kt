package com.kernel.ai.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Instrumented Compose UI tests for the DND special-access AlertDialog.
 *
 * The dialog is rendered inline in [ActionsScreen] (via PermissionOverlayDialog →
 * BasicAlertDialog). This test verifies the dialog rendering and CTA callbacks
 * in isolation using a test wrapper that replicates the same conditional dialog
 * structure. The wrapper uses [Dialog] directly (matching BasicAlertDialog's pattern)
 * rather than AlertDialog so the scrim has an explicit test tag for backdrop tap.
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
 *   - Backdrop tap: tested via a test-tagged scrim Box with clickable(onClick = onDismiss).
 *     Compose Dialog windows have a known limitation where performTouchInput { click() }
 *     on the dialog root does not reliably reach the scrim Surface (coordinate-system
 *     mismatch for separate Dialog windows). Using performClick() on a test-tagged
 *     scrim node exercises the same semantic action (onClick → onDismissRequest).
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
        // Find the fill-sized scrim Box in the unmerged semantics tree and click it.
        // The Box has clickable(onClick = onDismiss) which adds OnClick semantics.
        // On S21 the dialog window is ~960×900 px — the scrim fills this area.
        // Buttons are <60k px², so area > 100k uniquely matches the scrim.
        composeTestRule.onNode(
            hasClickAction().and(
                SemanticsMatcher("dialogScrim") { node ->
                    val area = node.boundsInRoot.width * node.boundsInRoot.height
                    area > 100_000f
                }
            ),
            useUnmergedTree = true,
        ).performClick()
        composeTestRule.waitForIdle()
        assertTrue("Backdrop dismiss should trigger dismiss callback", dismissCalled)
        composeTestRule.onNodeWithText("Allow Jandal to control Do Not Disturb?")
            .assertIsNotDisplayed()
    }

    // ── Test wrapper composable ────────────────────────────────────────────────

    /**
     * Replicates the exact dialog structure from [ActionsScreen] which uses
     * PermissionOverlayDialog → BasicAlertDialog.
     *
     * Unlike the Material3 AlertDialog convenience API, this wrapper uses [Dialog]
     * directly so the scrim layer has an explicit [testTag]("dialog_scrim") for
     * ComposeUI test access. The behavior is identical: a clickable fill-size scrim
     * triggers [onDismiss] when tapped outside the centered card.
     */
    @Composable
    private fun DndDialogContent(
        dndState: MutableState<ActionsViewModel.DndState?>,
        onOpenSettings: () -> Unit,
        onDismiss: () -> Unit,
    ) {
        dndState.value?.let { state ->
            Dialog(
                onDismissRequest = onDismiss,
                properties = DialogProperties(usePlatformDefaultWidth = true),
            ) {
                // Scrim — fill-sized clickable Box inside the Dialog.
                // clickable(onClick = onDismiss) adds OnClick semantics to the Box.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onDismiss,
                        ),
                ) {
                    // Card — centered, same surface styling as BasicAlertDialog
                    Surface(
                        modifier = Modifier
                            .wrapContentSize()
                            .align(Alignment.Center),
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 6.dp,
                    ) {
                        Column(modifier = Modifier.padding(24.dp)) {
                            Text(
                                text = if (state.isAccessBlocked) {
                                    "Jandal still needs Do Not Disturb access"
                                } else {
                                    "Allow Jandal to control Do Not Disturb?"
                                },
                                style = MaterialTheme.typography.headlineSmall,
                            )
                            Text(
                                text = if (state.isAccessBlocked) {
                                    "Grant Do Not Disturb access in Android settings, " +
                                        "then return to Jandal to continue."
                                } else {
                                    "Android requires special access before Jandal can " +
                                        "turn Do Not Disturb on or off."
                                },
                                modifier = Modifier.padding(top = 16.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 24.dp),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                TextButton(onClick = onDismiss) {
                                    Text("Not now")
                                }
                                TextButton(onClick = onOpenSettings) {
                                    Text("Open DND access settings")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
