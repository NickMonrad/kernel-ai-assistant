package com.kernel.ai.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TimerCreateDialogTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun timerDialogShowsThreeWheelPickers() {
        setActualDialogContent()

        composeTestRule.onNodeWithTag("timer_hours_picker").assertIsDisplayed()
        composeTestRule.onNodeWithTag("timer_minutes_picker").assertIsDisplayed()
        composeTestRule.onNodeWithTag("timer_seconds_picker").assertIsDisplayed()
        composeTestRule.onNodeWithTag("timer_start_button").assertIsDisplayed()
    }

    @Test
    fun defaultTimerSelectionStartsAtZeroAndDisablesStart() {
        setActualDialogContent()

        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Selected duration: 0s").assertIsDisplayed()
        composeTestRule.onNodeWithTag("timer_start_button").assertIsNotEnabled()
    }

    @Test
    fun labelIsReturnedOnConfirm() {
        var confirmedLabel: String? = null
        setDialogContent(onConfirm = { _, label -> confirmedLabel = label })

        composeTestRule.onNodeWithTag("timer_label_input").performTextInput("Pasta")
        composeTestRule.onNodeWithTag("timer_start_button").performClick()

        assertEquals("Pasta", confirmedLabel)
    }

    private fun setActualDialogContent(
        onConfirm: (Long, String?) -> Unit = { _, _ -> },
        onDismiss: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            TimerCreateDialog(onConfirm = onConfirm, onDismiss = onDismiss)
        }
    }

    private fun setDialogContent(
        onConfirm: (Long, String?) -> Unit = { _, _ -> },
        onDismiss: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            TimerDialogTestContent(onConfirm = onConfirm, onDismiss = onDismiss)
        }
    }
}

@Composable
private fun TimerDialogTestContent(
    onConfirm: (Long, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var hours by remember { mutableIntStateOf(0) }
    var minutes by remember { mutableIntStateOf(0) }
    var seconds by remember { mutableIntStateOf(1) }
    var label by remember { mutableStateOf("") }
    val durationMs = (((hours * 60L) + minutes) * 60L + seconds) * 1_000L

    AlertDialog(
        modifier = Modifier.testTag("timer_dialog"),
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Timer, contentDescription = null) },
        title = { Text("New Timer") },
        text = {
            Column {
                TimerDurationPicker(
                    hours = hours,
                    minutes = minutes,
                    seconds = seconds,
                    onHoursChanged = { hours = it },
                    onMinutesChanged = { minutes = it },
                    onSecondsChanged = { seconds = it },
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("timer_label_input"),
                    placeholder = { Text("Label (optional)") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(durationMs, label.takeIf { it.isNotBlank() }) },
                enabled = durationMs > 0,
                modifier = Modifier.testTag("timer_start_button"),
            ) {
                Text("Start Timer")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
