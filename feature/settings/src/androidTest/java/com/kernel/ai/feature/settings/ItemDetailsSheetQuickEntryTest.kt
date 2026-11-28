package com.kernel.ai.feature.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.kernel.ai.core.memory.entity.ListItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ItemDetailsSheetQuickEntryTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val added = mutableListOf<ListItemEntity>()
    private var enterAdds = 0
    private var hintDismissals = 0
    private var saved: ListItemEntity? = null
    private var dismissed = false

    private fun setAddSheet(
        title: String = "Add item",
        hintRetired: Boolean = false,
        item: ListItemEntity = ListItemEntity(listId = 1L, text = ""),
    ) {
        composeTestRule.setContent {
            MaterialTheme {
                ItemDetailsSheet(
                    item = item,
                    title = title,
                    primaryActionLabel = "Add",
                    canAddSubItem = false,
                    onAddSubItem = {},
                    quickEntry = ItemQuickEntry(
                        hintRetired = hintRetired,
                        onAddNext = { added += it },
                        onEnterAdded = { enterAdds++ },
                        onHintDismissed = { hintDismissals++ },
                    ),
                    onSave = { saved = it },
                    onDismiss = { dismissed = true },
                )
            }
        }
    }

    @Test
    fun hintAppearsWhenTypingAndDismissStopsItAndIsPersisted() {
        setAddSheet()
        composeTestRule.onNodeWithTag("list_item_details_quick_hint").assertDoesNotExist()

        composeTestRule.onNodeWithTag("list_item_details_title").performTextInput("m")
        composeTestRule.onNodeWithText("Press Enter to add this and type the next one").assertIsDisplayed()

        composeTestRule.onNodeWithTag("list_item_details_quick_hint_dismiss").performClick()
        composeTestRule.onNodeWithTag("list_item_details_quick_hint").assertDoesNotExist()
        assertEquals(1, hintDismissals)
    }

    @Test
    fun enterAddsItemClearsNameKeepsSheetOpenAndShowsCounter() {
        setAddSheet()

        composeTestRule.onNodeWithTag("list_item_details_title").performTextInput("milk")
        composeTestRule.onNodeWithTag("list_item_details_title").performImeAction()

        assertEquals(listOf("milk"), added.map { it.text })
        assertEquals(1, enterAdds)
        assertNull(saved)
        assertTrue(!dismissed)
        composeTestRule.onNodeWithText("1 added · ↵ for the next one").assertIsDisplayed()
        composeTestRule.onNodeWithText("Done").assertIsDisplayed()
        composeTestRule.onNodeWithText("Add & next").assertIsDisplayed()
        composeTestRule.onNodeWithTag("list_item_details_save").assertDoesNotExist()

        composeTestRule.onNodeWithTag("list_item_details_title").performTextInput("eggs")
        composeTestRule.onNodeWithTag("list_item_details_title").performImeAction()

        assertEquals(listOf("milk", "eggs"), added.map { it.text })
        composeTestRule.onNodeWithText("2 added · ↵ for the next one").assertIsDisplayed()
    }

    @Test
    fun retiredHintStaysHiddenWhileCounterKeepsCounting() {
        setAddSheet(hintRetired = true)

        composeTestRule.onNodeWithTag("list_item_details_title").performTextInput("milk")
        composeTestRule.onNodeWithTag("list_item_details_quick_hint").assertDoesNotExist()

        composeTestRule.onNodeWithTag("list_item_details_title").performImeAction()
        composeTestRule.onNodeWithText("1 added · ↵ for the next one").assertIsDisplayed()
    }

    @Test
    fun addAndNextCarriesStarDueAndDescriptionThenResetsThem() {
        setAddSheet()
        composeTestRule.onNodeWithTag("list_item_details_title").performTextInput("milk")
        composeTestRule.onNodeWithTag("list_item_details_title").performImeAction()

        composeTestRule.onNodeWithTag("list_item_details_title").performTextInput("brisket")
        composeTestRule.onNodeWithTag("list_item_details_description").performTextInput("ask for the fatty end")
        composeTestRule.onNodeWithTag("list_item_details_favourite").performClick()
        composeTestRule.onNodeWithText("Set due date").performClick()
        composeTestRule.onNodeWithText("OK").performClick()
        composeTestRule.onNodeWithText("Add & next").performClick()

        val brisket = added.last()
        assertEquals("brisket", brisket.text)
        assertEquals("ask for the fatty end", brisket.description)
        assertTrue(brisket.isFavourite)
        assertTrue(brisket.dueAt != null)
        assertNull(brisket.notificationTime)
        assertEquals(1, enterAdds) // only the Enter-add in setup counts; Add & next does not

        composeTestRule.onNodeWithText("Add a description").assertIsDisplayed()
        composeTestRule.onNodeWithText("Set due date").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Add to favourites").assertIsDisplayed()
        composeTestRule.onNodeWithText("2 added · ↵ for the next one").assertIsDisplayed()
    }

    @Test
    fun doneAddsTextLeftInNameBoxAndCloses() {
        setAddSheet()
        composeTestRule.onNodeWithTag("list_item_details_title").performTextInput("milk")
        composeTestRule.onNodeWithTag("list_item_details_title").performImeAction()
        composeTestRule.onNodeWithTag("list_item_details_title").performTextInput("eggs")

        composeTestRule.onNodeWithText("Done").performClick()

        assertEquals("eggs", saved?.text)
        assertEquals(listOf("milk"), added.map { it.text })
        assertTrue(!dismissed)
    }

    @Test
    fun doneWithEmptyNameJustCloses() {
        setAddSheet()
        composeTestRule.onNodeWithTag("list_item_details_title").performTextInput("milk")
        composeTestRule.onNodeWithTag("list_item_details_title").performImeAction()

        composeTestRule.onNodeWithText("Done").performClick()

        assertNull(saved)
        assertTrue(dismissed)
    }

    @Test
    fun cancelBeforeBatchDiscardsTypedText() {
        setAddSheet()
        composeTestRule.onNodeWithTag("list_item_details_title").performTextInput("milk")

        composeTestRule.onNodeWithTag("list_item_details_cancel").performClick()

        assertNull(saved)
        assertTrue(added.isEmpty())
        assertTrue(dismissed)
    }

    @Test
    fun swipeDownWithTextInNameBoxAddsIt() {
        setAddSheet()
        composeTestRule.onNodeWithTag("list_item_details_title").performTextInput("eggs")

        // A focused name box can have the soft keyboard up: the first back hides it, the next closes the sheet.
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.pressBack()
        composeTestRule.waitForIdle()
        if (saved == null) {
            device.pressBack()
            composeTestRule.waitForIdle()
        }

        assertEquals("eggs", saved?.text)
        assertTrue(added.isEmpty())
    }

    @Test
    fun pastedListOffersSplitAndSplitAddsEachLineWithoutBullets() {
        setAddSheet()
        val raw = "- tacos\n• carnitas\n1. fried chicken\n2) wagyu brisket"
        clipboard().setPrimaryClip(ClipData.newPlainText("list", raw))

        composeTestRule.onNodeWithTag("list_item_details_title").performTextInput(raw)
        composeTestRule.onNodeWithText("Add as 4 separate items?").assertIsDisplayed()
        composeTestRule.onNodeWithTag("list_item_details_paste_split").performClick()

        assertEquals(
            listOf("tacos", "carnitas", "fried chicken", "wagyu brisket"),
            added.map { it.text },
        )
        composeTestRule.onNodeWithText("Add as 4 separate items?").assertDoesNotExist()
        composeTestRule.onNodeWithText("Done").assertIsDisplayed()
    }

    @Test
    fun pastedListKeepAsOneJoinsLinesWithCommaSpace() {
        setAddSheet()
        val raw = "tacos\ncarnitas\nfried chicken"
        clipboard().setPrimaryClip(ClipData.newPlainText("list", raw))

        composeTestRule.onNodeWithTag("list_item_details_title").performTextInput(raw)
        composeTestRule.onNodeWithTag("list_item_details_paste_keep").performClick()

        composeTestRule.onNodeWithText("Add as 3 separate items?").assertDoesNotExist()
        composeTestRule.onNodeWithTag("list_item_details_title").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("tacos, carnitas, fried chicken")),
        )
        assertTrue(added.isEmpty())
        assertNull(saved)
    }

    @Test
    fun subItemEnterAddUsesTheSameFlow() {
        setAddSheet(title = "Add sub-item")
        composeTestRule.onNodeWithText("Add sub-item").assertIsDisplayed()

        composeTestRule.onNodeWithTag("list_item_details_title").performTextInput("salt")
        composeTestRule.onNodeWithTag("list_item_details_title").performImeAction()

        assertEquals(listOf("salt"), added.map { it.text })
        composeTestRule.onNodeWithText("Add sub-item").assertIsDisplayed()
    }

    @Test
    fun editSheetKeepsMultiLineNameAndHasNoQuickEntryControls() {
        composeTestRule.setContent {
            MaterialTheme {
                ItemDetailsSheet(
                    item = ListItemEntity(id = 3L, listId = 1L, text = "tacos\ncarnitas"),
                    canAddSubItem = false,
                    onAddSubItem = {},
                    onSave = { saved = it },
                    onDismiss = { dismissed = true },
                )
            }
        }

        composeTestRule.onNodeWithTag("list_item_details_quick_hint").assertDoesNotExist()
        composeTestRule.onNodeWithText("Add & next").assertDoesNotExist()
        composeTestRule.onNodeWithTag("list_item_details_save").performClick()

        assertEquals("tacos\ncarnitas", saved?.text)
    }

    private fun clipboard(): ClipboardManager =
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
}
