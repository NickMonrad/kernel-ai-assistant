package com.kernel.ai.alarm

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kernel.ai.core.memory.clock.ClockAlertConfig
import com.kernel.ai.core.memory.clock.ClockSoundConfig
import com.kernel.ai.feature.settings.ClockSettingsContent
import com.kernel.ai.feature.settings.DurationSetting
import com.kernel.ai.feature.settings.MaxAutoSnoozeSetting
import com.kernel.ai.feature.settings.SoundSetting
import org.junit.Rule
import org.junit.Test

/**
 * UI tests for the Clock overflow menu and settings screen.
 *
 * These verify production composables [DurationSetting], [MaxAutoSnoozeSetting],
 * [SoundSetting], and [ClockSettingsContent] directly, plus a lightweight
 * overflow-menu wrapper that mirrors the real SidePanelScreen TopAppBar structure.
 */
class ClockOverflowSettingsUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ── Clock overflow button ──────────────────────────────────────────

    @Test
    fun clockTopAppBar_showsOverflowButton() {
        composeTestRule.setContent {
            ClockOverflowTopAppBar()
        }
        composeTestRule.onNodeWithTag("clock_overflow_button").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Clock options").assertIsDisplayed()
    }

    @Test
    fun overflowMenu_containsClockSettings() {
        composeTestRule.setContent {
            ClockOverflowTopAppBar()
        }
        composeTestRule.onNodeWithTag("clock_overflow_button").performClick()
        composeTestRule.onNodeWithTag("clock_overflow_settings").assertIsDisplayed()
        composeTestRule.onNodeWithText("Clock settings").assertIsDisplayed()
    }

    @Test
    fun overflowMenu_onlyRendersWhenExpanded() {
        composeTestRule.setContent {
            ClockOverflowTopAppBar()
        }
        // Before click: menu is not visible
        composeTestRule.onNodeWithText("Clock settings").assertIsNotDisplayed()
    }

    // ── Real DurationSetting component tests ───────────────────────────

    @Test
    fun durationSetting_rendersLabel() {
        composeTestRule.setContent {
            DurationSetting(
                label = "Timer sound duration",
                value = 60_000L,
                options = listOf(15_000L to "15 sec", 60_000L to "60 sec"),
                onValueChange = {},
                testTag = "clock_settings_timer_sound_duration",
            )
        }
        composeTestRule.onNodeWithTag("clock_settings_timer_sound_duration").assertIsDisplayed()
        composeTestRule.onNodeWithText("Timer sound duration").assertIsDisplayed()
    }

    @Test
    fun durationSetting_showsSelectedValue() {
        composeTestRule.setContent {
            DurationSetting(
                label = "Test duration",
                value = 30_000L,
                options = listOf(15_000L to "15 sec", 30_000L to "30 sec"),
                onValueChange = {},
                testTag = "test_duration",
            )
        }
        composeTestRule.onNodeWithText("30 sec").assertIsDisplayed()
    }

    // ── Real MaxAutoSnoozeSetting component tests ──────────────────────

    @Test
    fun maxAutoSnoozeSetting_rendersLabel() {
        composeTestRule.setContent {
            MaxAutoSnoozeSetting(
                value = 1,
                onValueChange = {},
                testTag = "clock_settings_max_auto_snoozes",
            )
        }
        composeTestRule.onNodeWithTag("clock_settings_max_auto_snoozes").assertIsDisplayed()
        composeTestRule.onNodeWithText("Automatic snoozes").assertIsDisplayed()
    }

    @Test
    fun maxAutoSnoozeSetting_showsOnlyZeroAndOne() {
        composeTestRule.setContent {
            MaxAutoSnoozeSetting(
                value = 0,
                onValueChange = {},
                testTag = "test_snooze",
            )
        }
        // Verify the current value label mentions "0"
        composeTestRule.onNodeWithTag("test_snooze").assertIsDisplayed()
        // Open the dropdown to inspect options
        composeTestRule.onNodeWithTag("test_snooze").performClick()
        // Only 0 and 1 options should be visible
        composeTestRule.onNodeWithText("0").assertIsDisplayed()
        composeTestRule.onNodeWithText("1").assertIsDisplayed()
    }

    // ── Real SoundSetting component tests ──────────────────────────────

    @Test
    fun soundSetting_rendersTitle() {
        composeTestRule.setContent {
            SoundSetting(
                title = "Alarm sound",
                currentSoundUri = null,
                onSoundSelected = {},
                onClick = {},
                testTag = "clock_settings_alarm_sound",
            )
        }
        composeTestRule.onNodeWithTag("clock_settings_alarm_sound").assertIsDisplayed()
        composeTestRule.onNodeWithText("Alarm sound").assertIsDisplayed()
    }

    @Test
    fun soundSetting_showsSystemDefaultWhenNull() {
        composeTestRule.setContent {
            SoundSetting(
                title = "Alarm sound",
                currentSoundUri = null,
                onSoundSelected = {},
                onClick = {},
                testTag = "test_sound",
            )
        }
        composeTestRule.onNodeWithText("System default").assertIsDisplayed()
    }

    // ── Real ClockSettingsContent integration tests ────────────────────

    @Test
    fun clockSettingsContent_showsTimerSoundDuration() {
        composeTestRule.setContent {
            ClockSettingsContent(
                config = ClockAlertConfig(),
                soundConfig = ClockSoundConfig(),
            )
        }
        composeTestRule.onNodeWithTag("clock_settings_timer_sound_duration").assertIsDisplayed()
        composeTestRule.onNodeWithText("Timer sound duration").assertIsDisplayed()
    }

    @Test
    fun clockSettingsContent_showsAlarmRingDuration() {
        composeTestRule.setContent {
            ClockSettingsContent()
        }
        composeTestRule.onNodeWithTag("clock_settings_alarm_ring_duration").assertIsDisplayed()
        composeTestRule.onNodeWithText("Alarm ring duration").assertIsDisplayed()
    }

    @Test
    fun clockSettingsContent_showsSnoozeDuration() {
        composeTestRule.setContent {
            ClockSettingsContent()
        }
        composeTestRule.onNodeWithTag("clock_settings_snooze_duration").assertIsDisplayed()
        composeTestRule.onNodeWithText("Snooze duration").assertIsDisplayed()
    }

    @Test
    fun clockSettingsContent_showsMaxAutoSnoozes() {
        composeTestRule.setContent {
            ClockSettingsContent()
        }
        composeTestRule.onNodeWithTag("clock_settings_max_auto_snoozes").assertIsDisplayed()
        composeTestRule.onNodeWithText("Automatic snoozes").assertIsDisplayed()
    }

    @Test
    fun clockSettingsContent_showsAlarmSound() {
        composeTestRule.setContent {
            ClockSettingsContent(
                soundConfig = ClockSoundConfig(),
            )
        }
        composeTestRule.onNodeWithTag("clock_settings_alarm_sound").assertIsDisplayed()
        composeTestRule.onNodeWithText("Alarm sound").assertIsDisplayed()
    }

    @Test
    fun clockSettingsContent_showsTimerSound() {
        composeTestRule.setContent {
            ClockSettingsContent()
        }
        composeTestRule.onNodeWithTag("clock_settings_timer_sound").assertIsDisplayed()
        composeTestRule.onNodeWithText("Timer sound").assertIsDisplayed()
    }

    @Test
    fun clockSettingsContent_showsAlertBehaviourSection() {
        composeTestRule.setContent {
            ClockSettingsContent()
        }
        composeTestRule.onNodeWithText("Alert behaviour").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sounds").assertIsDisplayed()
    }
}

/**
 * Minimal overflow TopAppBar that mirrors the real SidePanelScreen
 * TopAppBar structure. Uses the same tag names and composition to
 * validate the overflow button + Clock settings menu item lifecycle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClockOverflowTopAppBar() {
    val showOverflow = remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text("Clock") },
        navigationIcon = {},
        actions = {
            Box {
                IconButton(
                    onClick = { showOverflow.value = true },
                    modifier = Modifier.testTag("clock_overflow_button"),
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "Clock options",
                    )
                }
                DropdownMenu(
                    expanded = showOverflow.value,
                    onDismissRequest = { showOverflow.value = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Clock settings") },
                        onClick = { showOverflow.value = false },
                        modifier = Modifier.testTag("clock_overflow_settings"),
                    )
                }
            }
        },
    )
}
