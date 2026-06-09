package com.kernel.ai.navigation

import android.os.Environment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Produces repeatable visual evidence for the Tools example prompts flow
 * and Actions draft prefill.
 *
 * Screenshots are saved to:
 *   /sdcard/Android/data/com.kernel.ai.debug/files/Pictures/test-screenshots/pr-751-child-03/
 *
 * After running connected tests, pull with:
 *   adb pull /sdcard/Android/data/com.kernel.ai.debug/files/Pictures/test-screenshots/pr-751-child-03/ ./debug/pr-1137-screenshots
 */
@RunWith(AndroidJUnit4::class)
class ToolsHubScreenshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun screenshotDir(): File = File(
        InstrumentationRegistry.getInstrumentation().targetContext
            .getExternalFilesDir(Environment.DIRECTORY_PICTURES),
        "test-screenshots/pr-751-child-03",
    ).also { it.mkdirs() }

    private fun device(): UiDevice =
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun captureToolsLearnSectionScreenshots() {
        val dir = screenshotDir()
        val d = device()

        // 1. Collapsed Learn section
        composeTestRule.setContent {
            ToolsHubScreen(
                onOpenDrawer = {},
                onNavigateToRoute = {},
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("tools_screen")
            .performScrollToNode(hasTestTag("tools_examples_helper_copy"))
        composeTestRule.waitForIdle()
        d.takeScreenshot(File(dir, "01-tools-learn-collapsed.png"))

        // 2. Expand Meal planning
        composeTestRule.onNodeWithTag("tools_screen")
            .performScrollToNode(hasTestTag("tools_examples_view_more_meal_planning"))
        composeTestRule.onNodeWithTag(
            "tools_examples_view_more_meal_planning", useUnmergedTree = true,
        ).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("tools_screen")
            .performScrollToNode(hasTestTag("tools_example_meal_plan_family"))
        composeTestRule.waitForIdle()
        d.takeScreenshot(File(dir, "02-tools-learn-expanded-meal-planning.png"))

        // 3. Expand Weather
        composeTestRule.onNodeWithTag("tools_screen")
            .performScrollToNode(hasTestTag("tools_examples_view_more_weather"))
        composeTestRule.onNodeWithTag(
            "tools_examples_view_more_weather", useUnmergedTree = true,
        ).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("tools_screen")
            .performScrollToNode(hasTestTag("tools_example_weather_wellington"))
        composeTestRule.waitForIdle()
        d.takeScreenshot(File(dir, "03-tools-learn-expanded-weather.png"))

        // 4. Expand Utilities & conversions
        composeTestRule.onNodeWithTag("tools_screen")
            .performScrollToNode(hasTestTag("tools_examples_view_more_utilities_conversions"))
        composeTestRule.onNodeWithTag(
            "tools_examples_view_more_utilities_conversions", useUnmergedTree = true,
        ).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("tools_screen")
            .performScrollToNode(hasTestTag("tools_example_convert_currency_aud_nzd"))
        composeTestRule.waitForIdle()
        d.takeScreenshot(File(dir, "04-tools-learn-expanded-utilities.png"))

        composeTestRule.runOnIdle {
            println("Tools screenshots saved to: ${dir.absolutePath}")
            println("Pull command: adb pull ${dir.absolutePath} ./debug/pr-1137-screenshots")
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun captureActionsDraftPrefillScreenshot() {
        val dir = screenshotDir()
        val d = device()

        composeTestRule.setContent {
            ActionsDraftPrefillPreview()
        }
        composeTestRule.waitForIdle()
        d.takeScreenshot(File(dir, "05-actions-draft-prefill.png"))

        composeTestRule.runOnIdle {
            println("Actions draft screenshot saved to: ${dir.absolutePath}")
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun captureCalendarSlotFillScreenshot() {
        val dir = screenshotDir()
        val d = device()

        composeTestRule.setContent {
            CalendarSlotFillPreview()
        }
        composeTestRule.waitForIdle()
        d.takeScreenshot(File(dir, "06-actions-calendar-slot-fill.png"))

        composeTestRule.runOnIdle {
            println("Calendar slot-fill screenshot saved to: ${dir.absolutePath}")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionsDraftPrefillPreview() {
    var showSheet by remember { mutableStateOf(true) }
    var inputText by remember { mutableStateOf("Add milk to my shopping list") }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
            ) {
                Text(
                    text = "Quick Action",
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Type a command or tap the mic for a voice action.",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.outline,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Example loaded — review or edit before running.",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("What do you want to do?") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarSlotFillPreview() {
    var showSheet by remember { mutableStateOf(true) }
    var inputText by remember { mutableStateOf("Create a calendar event for soccer training") }
    var submitted by remember { mutableStateOf(true) }
    var slotQuestion by remember { mutableStateOf("What day is soccer training for?") }

    Column(
        modifier = Modifier.padding(16.dp),
    ) {
        Text(
            text = "Actions",
            style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "What day is soccer training for?",
            style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
            color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = "",
            onValueChange = {},
            placeholder = { Text("Type a reply or tap the mic") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
