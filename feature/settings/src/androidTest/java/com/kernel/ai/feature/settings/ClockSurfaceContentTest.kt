package com.kernel.ai.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kernel.ai.core.memory.clock.AlarmRepeatRule
import com.kernel.ai.core.memory.clock.ClockAlarm
import com.kernel.ai.core.memory.clock.ClockStopwatch
import com.kernel.ai.core.memory.clock.StopwatchStatus
import com.kernel.ai.core.memory.clock.WorldClock
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ClockSurfaceContentTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun allClockTabsExposeSharedVoiceFab() {
        // Use a stateful host to check the shared voice FAB across all non-selection-mode tabs.
        composeTestRule.setContent {
            var selectedTab by remember { mutableStateOf(ClockSurfaceTab.TIMERS) }
            MaterialTheme {
                Scaffold(
                    floatingActionButton = {
                        ClockScreenFloatingActionButton(
                            isInSelectionMode = false,
                            onNavigateToVoiceActions = {},
                        )
                    },
                ) { innerPadding ->
                    ClockSurfaceContent(
                        selectedTab = selectedTab,
                        alarms = emptyList(),
                        timers = emptyList(),
                        recentCompletedTimers = emptyList(),
                        stopwatch = idleStopwatch(),
                        worldClocks = emptyList(),
                        nowMs = 1_710_000_000_000L,
                        nowElapsedRealtimeMs = 5_000L,
                        isInSelectionMode = false,
                        selectedIds = emptySet(),
                        onTabSelected = { selectedTab = it },
                        onCreateCustomTimer = {},
                        onPresetTimer = {},
                        onTimerTap = {},
                        onTimerLongPress = {},
                        onCancelTimer = {},
                        onRestartTimer = {},
                        onDeleteCompletedTimer = {},
                        onClearCompletedTimers = {},
                        onNewAlarm = {},
                        onAlarmTap = {},
                        onAlarmLongPress = {},
                        onDismissAlarm = {},
                        onToggleAlarm = {},
                        onStartStopwatch = {},
                        onPauseStopwatch = {},
                        onResumeStopwatch = {},
                        onResetStopwatch = {},
                        onLapStopwatch = {},
                        onAddWorldClock = {},
                        onMoveWorldClockUp = {},
                        onMoveWorldClockDown = {},
                        onRemoveWorldClock = {},
                        modifier = androidx.compose.ui.Modifier.padding(innerPadding),
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        // The voice FAB is shared by all tabs — verify it's present on each.
        // Each tab's label also appears as a headline in its dashboard content.
        // To avoid duplicate matches, iterate in a sequence where we always click
        // the label of a tab NOT currently visible in content.
        composeTestRule.onNodeWithTag(CLOCK_VOICE_FAB_TEST_TAG).assertIsDisplayed()

        // Switch through tabs: ALARMS → WORLD_CLOCK → STOPWATCH → done
        // (TIMERS is already shown; clicking its label would find 2 matches)
        composeTestRule.onNodeWithText("Alarms").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(CLOCK_VOICE_FAB_TEST_TAG).assertIsDisplayed()

        composeTestRule.onNodeWithText("World Clock").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(CLOCK_VOICE_FAB_TEST_TAG).assertIsDisplayed()

        composeTestRule.onNodeWithText("Stopwatch").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(CLOCK_VOICE_FAB_TEST_TAG).assertIsDisplayed()
    }

    @Test
    fun selectionModeHidesSharedVoiceFab() {
        setClockSurfaceContent(
            selectedTab = ClockSurfaceTab.ALARMS,
            isInSelectionMode = true,
            alarms = listOf(sampleAlarm()),
        )

        composeTestRule.onAllNodesWithTag(CLOCK_VOICE_FAB_TEST_TAG).assertCountEquals(0)
    }

    @Test
    fun alarmsTabKeepsInContentNewAlarmActionWithoutBottomExtendedFab() {
        setClockSurfaceContent(
            selectedTab = ClockSurfaceTab.ALARMS,
            alarms = listOf(sampleAlarm()),
        )

        composeTestRule.onNodeWithTag(CLOCK_VOICE_FAB_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText("New alarm").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("New Alarm").assertCountEquals(0)
    }

    @Test
    fun worldClockTabKeepsInContentAddCityActionWithoutBottomExtendedFab() {
        setClockSurfaceContent(
            selectedTab = ClockSurfaceTab.WORLD_CLOCK,
            worldClocks = listOf(sampleWorldClock()),
        )

        composeTestRule.onNodeWithTag(CLOCK_VOICE_FAB_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText("Add city").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Add City").assertCountEquals(0)
    }
    @Test
    fun timerPrimaryActionsStayInsideContent() {
        setClockSurfaceContent(
            selectedTab = ClockSurfaceTab.TIMERS,
        )

        // Timer tab primary actions are inside content, not in the bottom extended FAB.
        // The shared voice FAB is present, but stopwatch-only actions ("Start stopwatch")
        // should not appear in the timer tab.
        composeTestRule.onNodeWithTag(CLOCK_VOICE_FAB_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText("Custom timer").assertIsDisplayed()
        composeTestRule.onNodeWithText("1 min").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Start stopwatch").assertCountEquals(0)
    }

    @Test
    fun stopwatchPrimaryActionsStayInsideContent() {
        setClockSurfaceContent(
            selectedTab = ClockSurfaceTab.STOPWATCH,
        )

        // Stopwatch tab primary action ("Start stopwatch") is inside content,
        // not duplicated by the bottom extended FAB.
        composeTestRule.onNodeWithTag(CLOCK_VOICE_FAB_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText("Start stopwatch").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Start stopwatch").assertCountEquals(1)
    }

    private fun setClockSurfaceContent(
        selectedTab: ClockSurfaceTab,
        isInSelectionMode: Boolean = false,
        alarms: List<ClockAlarm> = emptyList(),
        worldClocks: List<WorldClock> = emptyList(),
        stopwatch: ClockStopwatch = idleStopwatch(),
    ) {
        composeTestRule.setContent {
            MaterialTheme {
                Scaffold(
                    floatingActionButton = {
                        ClockScreenFloatingActionButton(
                            isInSelectionMode = isInSelectionMode,
                            onNavigateToVoiceActions = {},
                        )
                    },
                ) { innerPadding ->
                    ClockSurfaceContent(
                        selectedTab = selectedTab,
                        alarms = alarms,
                        timers = emptyList(),
                        recentCompletedTimers = emptyList(),
                        stopwatch = stopwatch,
                        worldClocks = worldClocks,
                        nowMs = 1_710_000_000_000L,
                        nowElapsedRealtimeMs = 5_000L,
                        isInSelectionMode = isInSelectionMode,
                        selectedIds = emptySet(),
                        onTabSelected = {},
                        onCreateCustomTimer = {},
                        onPresetTimer = {},
                        onTimerTap = {},
                        onTimerLongPress = {},
                        onCancelTimer = {},
                        onRestartTimer = {},
                        onDeleteCompletedTimer = {},
                        onClearCompletedTimers = {},
                        onNewAlarm = {},
                        onAlarmTap = {},
                        onAlarmLongPress = {},
                        onDismissAlarm = {},
                        onToggleAlarm = {},
                        onStartStopwatch = {},
                        onPauseStopwatch = {},
                        onResumeStopwatch = {},
                        onResetStopwatch = {},
                        onLapStopwatch = {},
                        onAddWorldClock = {},
                        onMoveWorldClockUp = {},
                        onMoveWorldClockDown = {},
                        onRemoveWorldClock = {},
                        modifier = androidx.compose.ui.Modifier.padding(innerPadding),
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun sampleAlarm() = ClockAlarm(
        id = "alarm-1",
        label = "Wake up",
        createdAtMillis = 1_710_000_000_000L,
        enabled = true,
        hour = 7,
        minute = 30,
        repeatRule = AlarmRepeatRule.OneOff(LocalDate.of(2026, 6, 30).toEpochDay()),
        timeZoneId = ZoneId.systemDefault().id,
        triggerAtMillis = 1_710_028_200_000L,
    )

    private fun sampleWorldClock() = WorldClock(
        id = "wc-1",
        zoneId = "Europe/Copenhagen",
        displayName = "Copenhagen",
        sortOrder = 0,
        createdAtMillis = 1_710_000_000_000L,
    )

    private fun idleStopwatch() = ClockStopwatch(
        id = "stopwatch-1",
        status = StopwatchStatus.IDLE,
        accumulatedElapsedMs = 0L,
        updatedAtMillis = 1_710_000_000_000L,
    )
}
