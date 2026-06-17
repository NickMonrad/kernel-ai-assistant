package com.kernel.ai.alarm

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
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
import com.kernel.ai.feature.settings.ClockSettingsContent
import com.kernel.ai.feature.settings.DurationSetting
import com.kernel.ai.feature.settings.MaxAutoSnoozeSetting
import com.kernel.ai.feature.settings.SoundSetting
import com.kernel.ai.feature.settings.MAX_AUTO_SNOOZE_OPTIONS
import org.junit.Rule
import org.junit.Test

/**
 * UI tests for the Clock screen toolbar, overflow menu, and settings screen.
 *
 * These verify production composables [DurationSetting], [MaxAutoSnoozeSetting],
 * [SoundSetting], and [ClockSettingsContent] directly, plus a lightweight
 * overflow-menu wrapper that mirrors the **real [SidePanelScreen] TopAppBar
 * structure** (back button, Clock title, overflow with settings).
 */
class ClockOverflowSettingsUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ── Clock toolbar (mirrors real SidePanelScreen TopAppBar) ────────

    @Test
    fun clockToolbar_showsBackButton() {
        composeTestRule.setContent { ClockToolbar(onBack = {}) }
        composeTestRule.onNodeWithTag("back_button").assertIsDisplayed()
    }

    @Test
    fun clockToolbar_showsClockTitle() {
        composeTestRule.setContent { ClockToolbar() }
        composeTestRule.onNodeWithText("Clock").assertIsDisplayed()
    }

    @Test
    fun clockToolbar_showsOverflowButton() {
        composeTestRule.setContent { ClockToolbar() }
        composeTestRule.onNodeWithTag("clock_overflow_button").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Clock options").assertIsDisplayed()
    }

    @Test
    fun overflowMenu_containsClockSettings() {
        composeTestRule.setContent { ClockToolbar() }
        composeTestRule.onNodeWithTag("clock_overflow_button").performClick()
        composeTestRule.onNodeWithTag("clock_overflow_settings").assertIsDisplayed()
        composeTestRule.onNodeWithText("Clock settings").assertIsDisplayed()
    }

    @Test
    fun overflowMenu_onlyRendersWhenExpanded() {
        composeTestRule.setContent { ClockToolbar() }
        composeTestRule.onNodeWithText("Clock settings").assertIsNotDisplayed()
    }

    @Test
    fun overflowMenu_triggersOnClockSettingsCallback() {
        var clockSettingsOpened = false
        composeTestRule.setContent {
            ClockToolbar(onNavigateToClockSettings = { clockSettingsOpened = true })
        }
        composeTestRule.onNodeWithTag("clock_overflow_button").performClick()
        composeTestRule.onNodeWithText("Clock settings").performClick()
        assert(clockSettingsOpened) { "Clock settings callback was not triggered" }
    }

    @Test
    fun backButton_triggersOnBackCallback() {
        var backPressed = false
        composeTestRule.setContent {
            ClockToolbar(onBack = { backPressed = true })
        }
        composeTestRule.onNodeWithTag("back_button").performClick()
        assert(backPressed) { "Back callback was not triggered" }
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

    @Test
    fun durationSetting_firesOnValueChange() {
        var changed = false
        composeTestRule.setContent {
            DurationSetting(
                label = "Duration",
                value = 15_000L,
                options = listOf(15_000L to "15 sec", 30_000L to "30 sec"),
                onValueChange = { changed = true },
                testTag = "test_duration",
            )
        }
        composeTestRule.onNodeWithTag("test_duration").performClick()
        composeTestRule.onNodeWithText("30 sec").performClick()
        assert(changed) { "Duration onValueChange was not triggered" }
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
    fun maxAutoSnoozeSetting_showsZeroToThree() {
        composeTestRule.setContent {
            MaxAutoSnoozeSetting(value = 0, onValueChange = {}, testTag = "test_snooze")
        }
        composeTestRule.onNodeWithTag("test_snooze").assertIsDisplayed()
        composeTestRule.onNodeWithTag("test_snooze").performClick()
        composeTestRule.onNodeWithText("0").assertIsDisplayed()
        composeTestRule.onNodeWithText("1").assertIsDisplayed()
        composeTestRule.onNodeWithText("2").assertIsDisplayed()
        composeTestRule.onNodeWithText("3").assertIsDisplayed()
    }

    @Test
    fun maxAutoSnoozeSetting_exposesCorrectOptions() {
        val labels = MAX_AUTO_SNOOZE_OPTIONS.map { it.second }
        assert(labels.any { it.startsWith("0 ") }) { "Missing option for auto-snooze count 0" }
        assert(labels.any { it.startsWith("1 ") }) { "Missing option for auto-snooze count 1" }
        assert(labels.any { it.startsWith("2 ") }) { "Missing option for auto-snooze count 2" }
        assert(labels.any { it.startsWith("3 ") }) { "Missing option for auto-snooze count 3" }
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
                title = "Test sound",
                currentSoundUri = null,
                onSoundSelected = {},
                onClick = {},
                testTag = "test_sound",
            )
        }
        composeTestRule.onNodeWithText("System default").assertIsDisplayed()
    }

    @Test
    fun soundSetting_firesOnClick() {
        var clicked = false
        composeTestRule.setContent {
            SoundSetting(
                title = "Test sound",
                currentSoundUri = null,
                onSoundSelected = {},
                onClick = { clicked = true },
                testTag = "test_sound",
            )
        }
        composeTestRule.onNodeWithTag("test_sound").performClick()
        assert(clicked) { "Sound onClick was not triggered" }
    }

    // ── Real ClockSettingsContent integration tests ────────────────────

    @Test
    fun clockSettingsContent_showsTimerSoundDuration() {
        composeTestRule.setContent { ClockSettingsContent() }
        composeTestRule.onNodeWithTag("clock_settings_timer_sound_duration").assertIsDisplayed()
        composeTestRule.onNodeWithText("Timer sound duration").assertIsDisplayed()
    }

    @Test
    fun clockSettingsContent_showsAlarmRingDuration() {
        composeTestRule.setContent { ClockSettingsContent() }
        composeTestRule.onNodeWithTag("clock_settings_alarm_ring_duration").assertIsDisplayed()
        composeTestRule.onNodeWithText("Alarm ring duration").assertIsDisplayed()
    }

    @Test
    fun clockSettingsContent_showsSnoozeDuration() {
        composeTestRule.setContent { ClockSettingsContent() }
        composeTestRule.onNodeWithTag("clock_settings_snooze_duration").assertIsDisplayed()
        composeTestRule.onNodeWithText("Snooze duration").assertIsDisplayed()
    }

    @Test
    fun clockSettingsContent_showsMaxAutoSnoozes() {
        composeTestRule.setContent { ClockSettingsContent() }
        composeTestRule.onNodeWithTag("clock_settings_max_auto_snoozes").assertIsDisplayed()
        composeTestRule.onNodeWithText("Automatic snoozes").assertIsDisplayed()
    }

    @Test
    fun clockSettingsContent_showsAlarmSound() {
        composeTestRule.setContent { ClockSettingsContent() }
        composeTestRule.onNodeWithTag("clock_settings_alarm_sound").assertIsDisplayed()
        composeTestRule.onNodeWithText("Alarm sound").assertIsDisplayed()
    }

    @Test
    fun clockSettingsContent_showsTimerSound() {
        composeTestRule.setContent { ClockSettingsContent() }
        composeTestRule.onNodeWithTag("clock_settings_timer_sound").assertIsDisplayed()
        composeTestRule.onNodeWithText("Timer sound").assertIsDisplayed()
    }

    @Test
    fun clockSettingsContent_showsAlertBehaviourSection() {
        composeTestRule.setContent { ClockSettingsContent() }
        composeTestRule.onNodeWithText("Alert behaviour").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sounds").assertIsDisplayed()
    }
}

/**
 * Clock toolbar that mirrors the real [SidePanelScreen] TopAppBar exactly.
 * Renders the back button, Clock title, and three-dot overflow with a
 * "Clock settings" dropdown item.
 *
 * @param onBack invoked when the back button is tapped
 * @param onNavigateToClockSettings invoked when "Clock settings" is tapped
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClockToolbar(
    onBack: () -> Unit = {},
    onNavigateToClockSettings: () -> Unit = {},
) {
    val showOverflow = remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text("Clock") },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.Default.KeyboardArrowLeft,
                    contentDescription = "Back",
                    modifier = Modifier.testTag("back_button"),
                )
            }
        },
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
                        onClick = {
                            showOverflow.value = false
                            onNavigateToClockSettings()
                        },
                        modifier = Modifier.testTag("clock_overflow_settings"),
                    )
                }
            }
        },
    )
}
