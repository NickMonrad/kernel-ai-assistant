package com.kernel.ai.feature.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.kernel.ai.core.memory.clock.ClockStopwatch
import com.kernel.ai.core.memory.clock.StopwatchLap
import com.kernel.ai.core.memory.clock.StopwatchStatus
import org.junit.Rule
import org.junit.Test

class StopwatchDashboardTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun runningStopwatchShowsLapsInsideActiveCard() {
        composeTestRule.setContent {
            StopwatchDashboard(
                stopwatch = ClockStopwatch(
                    id = "primary",
                    status = StopwatchStatus.RUNNING,
                    accumulatedElapsedMs = 5_000L,
                    runningSinceElapsedRealtimeMs = 1_000L,
                    runningSinceWallClockMs = 10_000L,
                    updatedAtMillis = 10_000L,
                    laps = listOf(
                        StopwatchLap(
                            id = 2L,
                            lapNumber = 2,
                            elapsedMs = 8_000L,
                            splitMs = 3_000L,
                            createdAtMillis = 12_000L,
                        ),
                        StopwatchLap(
                            id = 1L,
                            lapNumber = 1,
                            elapsedMs = 5_000L,
                            splitMs = 5_000L,
                            createdAtMillis = 11_000L,
                        ),
                    ),
                ),
                nowElapsedRealtimeMs = 2_000L,
                nowWallClockMs = 11_000L,
                onStart = {},
                onPause = {},
                onResume = {},
                onReset = {},
                onLap = {},
            )
        }

        composeTestRule.onNodeWithTag("active_stopwatch_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("stopwatch_lap_summary").assertIsDisplayed()
        composeTestRule.onNodeWithText("2 laps recorded · latest split 00:03").assertIsDisplayed()
        composeTestRule.onNodeWithText("Lap 2").assertIsDisplayed()
        composeTestRule.onNodeWithText("Split 00:03").assertIsDisplayed()
        composeTestRule.onNodeWithText("Lap 1").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Record lap").assertCountEquals(0)
}

}