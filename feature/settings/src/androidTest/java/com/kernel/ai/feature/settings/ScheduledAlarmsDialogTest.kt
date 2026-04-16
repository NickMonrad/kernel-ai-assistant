package com.kernel.ai.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Instrumented UI tests for the alarm creation dialog in [ScheduledAlarmsScreen].
 *
 * The production screen requires Hilt + ViewModel, so these tests exercise
 * extracted composable wrappers that mirror the real UI.
 */
class ScheduledAlarmsDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ── Helper: render a Scaffold with a FAB that opens the test dialog ──

    private fun setFabAndDialogContent(
        onConfirm: (String?) -> Unit = {},
        onDismiss: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            var showDialog by remember { mutableStateOf(false) }

            Scaffold(
                floatingActionButton = {
                    FloatingActionButton(
                        onClick = { showDialog = true },
                        modifier = Modifier.testTag("alarm_fab"),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Create alarm")
                    }
                },
            ) { _ ->
                Box(modifier = Modifier.fillMaxSize())
            }

            if (showDialog) {
                AlarmDialogTestWrapper(
                    onConfirm = { label ->
                        onConfirm(label)
                        showDialog = false
                    },
                    onDismiss = {
                        onDismiss()
                        showDialog = false
                    },
                )
            }
        }
    }

    // ── Tests ────────────────────────────────────────────────────────────

    @Test
    fun fabVisible_onScreenLoad() {
        setFabAndDialogContent()
        composeTestRule.onNodeWithTag("alarm_fab").assertIsDisplayed()
    }

    @Test
    fun tapFab_opensDialog() {
        setFabAndDialogContent()

        // Dialog should not be visible yet
        composeTestRule.onNodeWithTag("alarm_dialog").assertDoesNotExist()

        // Tap FAB
        composeTestRule.onNodeWithTag("alarm_fab").performClick()

        // Dialog should now be visible
        composeTestRule.onNodeWithTag("alarm_dialog").assertIsDisplayed()
    }

    @Test
    fun dialogHasLabelInput() {
        setFabAndDialogContent()
        composeTestRule.onNodeWithTag("alarm_fab").performClick()

        composeTestRule.onNodeWithTag("alarm_label_input").assertIsDisplayed()
    }

    @Test
    fun cancelDismissesDialog() {
        var dismissed = false
        setFabAndDialogContent(onDismiss = { dismissed = true })

        composeTestRule.onNodeWithTag("alarm_fab").performClick()
        composeTestRule.onNodeWithTag("alarm_dialog").assertIsDisplayed()

        // Tap Cancel
        composeTestRule.onNodeWithText("Cancel").performClick()

        // Dialog should be gone
        composeTestRule.onNodeWithTag("alarm_dialog").assertDoesNotExist()
        assertTrue("onDismiss should have been called", dismissed)
    }

    @Test
    fun fillLabelAndConfirm_invokesCallback() {
        var confirmedLabel: String? = "NOT_CALLED"
        setFabAndDialogContent(onConfirm = { confirmedLabel = it })

        composeTestRule.onNodeWithTag("alarm_fab").performClick()
        composeTestRule.onNodeWithTag("alarm_label_input").performTextInput("Morning run")
        composeTestRule.onNodeWithTag("alarm_save_button").performClick()

        assertEquals("Morning run", confirmedLabel)
    }
}
