package com.kernel.ai.feature.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kernel.ai.core.memory.entity.ListItemEntity
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Captures the four quick-entry states as PNGs for PR evidence. Runs only when the instrument
 * argument `quickEntryScreenshotDir` is set, so the normal suite skips it.
 */
class QuickEntryScreenshotCaptureTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun outputDir(): File {
        val path = InstrumentationRegistry.getArguments().getString("quickEntryScreenshotDir")
        Assume.assumeTrue("quickEntryScreenshotDir not set", path != null)
        return File(path!!).apply { mkdirs() }
    }

    /** Host: a test list ("Groceries") behind the add sheet, so the captures show context. */
    private fun setHost(items: MutableList<String>) {
        composeTestRule.setContent {
            MaterialTheme {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    Text("Groceries", style = MaterialTheme.typography.headlineSmall)
                    items.forEach { entry ->
                        Text(entry, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 12.dp))
                    }
                }
                ItemDetailsSheet(
                    item = ListItemEntity(listId = 1L, text = ""),
                    title = "Add item",
                    primaryActionLabel = "Add",
                    canAddSubItem = false,
                    onAddSubItem = {},
                    quickEntry = ItemQuickEntry(
                        hintRetired = false,
                        onAddNext = { items += it.text },
                        onEnterAdded = {},
                        onHintDismissed = {},
                    ),
                    onSave = {},
                    onDismiss = {},
                )
            }
        }
    }

    private fun capture(dir: File, name: String) {
        composeTestRule.waitForIdle()
        val bitmap: Bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun captureTypingBatchAndDescriptionStates() {
        val dir = outputDir()
        val items = mutableStateListOf("Pasta", "Rice")
        setHost(items)
        val field = composeTestRule.onNodeWithTag("list_item_details_title")

        field.performTextInput("milk")
        capture(dir, "1-hint-while-typing")

        field.performImeAction()
        field.performTextInput("eggs")
        field.performImeAction()
        field.performTextInput("bread")
        capture(dir, "2-counter-two-added")

        field.performImeAction()
        field.performTextInput("brisket")
        composeTestRule.onNodeWithTag("list_item_details_description").performClick()
        composeTestRule.onNodeWithTag("list_item_details_description")
            .performTextInput("Ask for the fatty end\nAbout 1.5 kg")
        capture(dir, "3-counter-description-focused")
    }

    @Test
    fun capturePasteSplitDialog() {
        val dir = outputDir()
        setHost(mutableStateListOf("Pasta", "Rice"))
        val raw = "- tacos\n• carnitas\n1. fried chicken\n2) wagyu brisket"
        val clipboard = InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("list", raw))

        composeTestRule.onNodeWithTag("list_item_details_title").performTextInput(raw)
        capture(dir, "4-paste-split-dialog")
    }
}
