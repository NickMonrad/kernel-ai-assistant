package com.kernel.ai.alarm

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kernel.ai.feature.settings.ClockScreenTopBar
import com.kernel.ai.feature.settings.ClockSettingsContent
import com.kernel.ai.feature.settings.DurationSetting
import com.kernel.ai.feature.settings.MaxAutoSnoozeSetting
import com.kernel.ai.feature.settings.MAX_AUTO_SNOOZE_OPTIONS
import com.kernel.ai.feature.settings.SoundSetting
import org.junit.Rule
import org.junit.Test
/**
 * UI tests for the Clock screen toolbar (back button, Clock title, settings cog)
 * and the Clock settings screen components.
 *
 * These verify production composables [DurationSetting], [MaxAutoSnoozeSetting],
 * [SoundSetting], and [ClockSettingsContent] directly, plus the real shared
 * [ClockScreenTopBar] composition (back button, Clock title, settings cog).
 */
class ClockSettingsActionUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ── Clock toolbar (mirrors real SidePanelScreen TopAppBar) ────────

    @Test
    fun clockToolbar_showsBackButton() {
        composeTestRule.setContent { ClockScreenTopBar(onBack = {}) }
        composeTestRule.onNodeWithTag("back_button").assertIsDisplayed()
    }

    @Test
    fun clockToolbar_showsClockTitle() {
        composeTestRule.setContent { ClockScreenTopBar() }
        composeTestRule.onNodeWithText("Clock").assertIsDisplayed()
    }

    @Test
    fun clockToolbar_showsSettingsAction() {
        composeTestRule.setContent { ClockScreenTopBar() }
        composeTestRule.onNodeWithTag("clock_settings_button").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Clock settings").assertIsDisplayed()
    }

    @Test
    fun settingsAction_navigatesToClockSettings() {
        var clockSettingsOpened = false
        composeTestRule.setContent {
            ClockScreenTopBar(onNavigateToClockSettings = { clockSettingsOpened = true })
        }
        composeTestRule.onNodeWithTag("clock_settings_button").performClick()
        assert(clockSettingsOpened) { "Clock settings callback was not triggered" }
    }


    @Test
    fun backButton_triggersOnBackCallback() {
        var backPressed = false
        composeTestRule.setContent {
            ClockScreenTopBar(onBack = { backPressed = true })
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
        assert(labels.any { it == "0 — Don't auto-snooze" }) { "Missing option for auto-snooze count 0" }
        assert(labels.any { it == "1 — Snooze once, then stop" }) { "Missing option for auto-snooze count 1" }
        assert(labels.any { it == "2 — Snooze twice, then stop" }) { "Missing option for auto-snooze count 2" }
        assert(labels.any { it == "3 — Snooze 3 times, then stop" }) { "Missing option for auto-snooze count 3" }
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
