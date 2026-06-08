package com.kernel.ai.navigation

import android.os.Environment
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Produces repeatable visual evidence for the Tools example prompts flow.
 *
 * Screenshots are saved to:
 *   /sdcard/Android/data/com.kernel.ai.debug/files/test-screenshots/pr-751-child-03/
 *
 * After running connected tests, pull with:
 *   adb pull /sdcard/Android/data/com.kernel.ai/files/test-screenshots ./debug/pr-751-child-03-screenshots
 */
@RunWith(AndroidJUnit4::class)
class ToolsHubScreenshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun captureToolsLearnSectionScreenshots() {
        val screenshotDir = File(
            InstrumentationRegistry.getInstrumentation().targetContext
                .getExternalFilesDir(Environment.DIRECTORY_PICTURES),
            "test-screenshots/pr-751-child-03",
        )
        screenshotDir.mkdirs()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        // 1. Collapsed Learn section
        composeTestRule.setContent {
            ToolsHubScreen(
                onOpenDrawer = {},
                onNavigateToRoute = {},
            )
        }
        composeTestRule.waitForIdle()
        // Scroll to bring Learn section into view
        composeTestRule.onNodeWithTag("tools_screen")
            .performScrollToNode(hasTestTag("tools_examples_helper_copy"))
        composeTestRule.waitForIdle()
        device.takeScreenshot(File(screenshotDir, "01-tools-learn-collapsed.png"))

        // 2. Expand Meal planning
        composeTestRule.onNodeWithTag("tools_screen")
            .performScrollToNode(hasTestTag("tools_examples_view_more_meal_planning"))
        composeTestRule.onNodeWithTag("tools_examples_view_more_meal_planning", useUnmergedTree = true).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("tools_screen")
            .performScrollToNode(hasTestTag("tools_example_meal_plan_family"))
        composeTestRule.waitForIdle()
        device.takeScreenshot(File(screenshotDir, "02-tools-learn-expanded-meal-planning.png"))

        // 3. Expand Weather (keep Meal planning expanded to show mixed state is fine)
        composeTestRule.onNodeWithTag("tools_screen")
            .performScrollToNode(hasTestTag("tools_examples_view_more_weather"))
        composeTestRule.onNodeWithTag("tools_examples_view_more_weather", useUnmergedTree = true).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("tools_screen")
            .performScrollToNode(hasTestTag("tools_example_weather_wellington"))
        composeTestRule.waitForIdle()
        device.takeScreenshot(File(screenshotDir, "03-tools-learn-expanded-weather.png"))

        // 4. Expand Utilities & conversions
        composeTestRule.onNodeWithTag("tools_screen")
            .performScrollToNode(hasTestTag("tools_examples_view_more_utilities_conversions"))
        composeTestRule.onNodeWithTag("tools_examples_view_more_utilities_conversions", useUnmergedTree = true).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("tools_screen")
            .performScrollToNode(hasTestTag("tools_example_convert_currency_aud_nzd"))
        composeTestRule.waitForIdle()
        device.takeScreenshot(File(screenshotDir, "04-tools-learn-expanded-utilities.png"))

        composeTestRule.runOnIdle {
            println("Screenshots saved to: ${screenshotDir.absolutePath}")
            println("Pull command: adb pull ${screenshotDir.absolutePath} ./debug/pr-751-child-03-screenshots")
        }
    }
}
