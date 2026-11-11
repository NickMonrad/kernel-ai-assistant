package com.kernel.ai.alarm

import android.os.Environment
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.kernel.ai.core.memory.clock.ClockEventType
import com.kernel.ai.core.ui.theme.KernelAITheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RingingScreenScreenshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val alert = TriggeredClockAlert(
        ownerId = "screenshot-timer",
        type = ClockEventType.TIMER,
        title = "Timer",
        label = "Tea is ready",
        occurrenceTriggerAtMillis = 1_000L,
    )

    @Test
    fun capturesLightAndDarkRingingScreens() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val screenshotDir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: File(context.filesDir, "pictures"),
            "test-screenshots/issue-1797",
        ).also { it.mkdirs() }
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        listOf(false to "ringing-light.png", true to "ringing-dark.png").forEach { (dark, name) ->
            composeTestRule.setContent {
                KernelAITheme(darkTheme = dark, dynamicColor = false) {
                    RingingScreen(
                        alert = alert,
                        timeText = "Ringing since 1:28 PM · 0:07 elapsed",
                        onStop = {},
                        onSnooze = {},
                    )
                }
            }
            composeTestRule.onNodeWithTag("ringing_label").assertIsDisplayed()
            composeTestRule.onNodeWithTag("ringing_elapsed_time").assertIsDisplayed()
            composeTestRule.onNodeWithTag("ringing_stop").assertIsDisplayed()
            composeTestRule.onNodeWithTag("ringing_snooze").assertIsDisplayed()
            composeTestRule.waitForIdle()
            device.takeScreenshot(File(screenshotDir, name))
        }
    }

    @Test
    fun stopAndSnoozeButtonsCallTheirActions() {
        var stopped = false
        var snoozed = false
        composeTestRule.setContent {
            KernelAITheme(dynamicColor = false) {
                RingingScreen(
                    alert = alert,
                    timeText = "Ringing now",
                    onStop = { stopped = true },
                    onSnooze = { snoozed = true },
                )
            }
        }

        composeTestRule.onNodeWithTag("ringing_stop").performClick()
        composeTestRule.runOnIdle { assertEquals(true, stopped) }
        composeTestRule.onNodeWithTag("ringing_snooze").performClick()
        composeTestRule.runOnIdle { assertEquals(true, snoozed) }
    }
}
