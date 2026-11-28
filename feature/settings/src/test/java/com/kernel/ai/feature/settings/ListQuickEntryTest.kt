package com.kernel.ai.feature.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ListQuickEntryTest {

    @Test
    fun pastedBulletsAndNumbersAreStrippedAndBlankLinesDropped() {
        val lines = splitPastedLines("- tacos\n\n• carnitas\n1. fried chicken\r\n2) wagyu brisket\n  ")

        assertEquals(listOf("tacos", "carnitas", "fried chicken", "wagyu brisket"), lines)
    }

    @Test
    fun singleLineTextIsNotTreatedAsAPaste() {
        val lines = pastedNameLines(oldText = "", newText = "tacos carnitas", clipboardText = "tacos carnitas")

        assertNull(lines)
    }

    @Test
    fun newlineReachingTheNameFieldIsAPasteOfSeveralItems() {
        val lines = pastedNameLines(oldText = "", newText = "tacos\ncarnitas", clipboardText = null)

        assertEquals(listOf("tacos", "carnitas"), lines)
    }

    @Test
    fun newlinesCollapsedToSpacesByTheFieldAreRecoveredFromTheClipboard() {
        val lines = pastedNameLines(
            oldText = "",
            newText = "- tacos • carnitas 1. fried chicken",
            clipboardText = "- tacos\n• carnitas\n1. fried chicken",
        )

        assertEquals(listOf("tacos", "carnitas", "fried chicken"), lines)
    }

    @Test
    fun pasteIncludesTextAlreadyInTheNameBox() {
        val lines = pastedNameLines(
            oldText = "milk",
            newText = "milk tacos carnitas",
            clipboardText = "tacos\ncarnitas",
        )

        assertEquals(listOf("milk", "tacos", "carnitas"), lines)
    }

    @Test
    fun keepAsOneJoinsItemsWithCommaSpace() {
        assertEquals("tacos, carnitas", listOf("tacos", "carnitas").joinToString(", "))
    }

    @Test
    fun hintRetiresAfterThreeEnterAddsOrOnDismiss() {
        assertFalse(quickEntryHintRetired(enterAdds = 0, dismissed = false))
        assertFalse(quickEntryHintRetired(enterAdds = 2, dismissed = false))
        assertTrue(quickEntryHintRetired(enterAdds = 3, dismissed = false))
        assertTrue(quickEntryHintRetired(enterAdds = 0, dismissed = true))
    }

    @Test
    fun counterTextChangesWhenDescriptionHasFocus() {
        assertEquals("1 added · ↵ for the next one", quickEntryCounterText(added = 1, descriptionFocused = false))
        assertEquals("3 added · tap Add & next to keep going", quickEntryCounterText(added = 3, descriptionFocused = true))
    }
}
