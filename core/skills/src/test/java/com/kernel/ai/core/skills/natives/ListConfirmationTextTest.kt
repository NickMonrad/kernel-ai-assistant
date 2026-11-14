package com.kernel.ai.core.skills.natives

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ListConfirmationTextTest {

    @Test
    fun `single add confirmation is a plain sentence with the item before the list`() {
        assertEquals(
            "Added milk to your shopping list.",
            ListConfirmationText.added(listOf("milk"), "shopping list"),
        )
    }

    @Test
    fun `multi-item add lists items with commas and a final and`() {
        assertEquals(
            "Added milk, bread and eggs to your shopping list.",
            ListConfirmationText.added(listOf("milk", "bread", "eggs"), "shopping list"),
        )
    }

    @Test
    fun `spoken add keeps the full sentence when it fits the budget`() {
        assertEquals(
            "Added milk, bread, eggs and tea to your shopping list.",
            ListConfirmationText.spokenAdded(listOf("milk", "bread", "eggs", "tea"), "shopping list"),
        )
    }

    @Test
    fun `spoken add of many items counts them and keeps the list name`() {
        val items = (1..12).map { "Grocery item %02d".format(it) }
        assertEquals(
            "Added 12 items to your shopping list.",
            ListConfirmationText.spokenAdded(items, "shopping list"),
        )
    }

    @Test
    fun `spoken add of one very long item counts it instead of cutting the name`() {
        val longItem = "x".repeat(200)
        val spoken = ListConfirmationText.spokenAdded(listOf(longItem), "shopping list")
        assertEquals("Added 1 item to your shopping list.", spoken)
        assertTrue(spoken.length <= ListConfirmationText.SPOKEN_BUDGET)
    }

    @Test
    fun `remove confirmation names the item and the list`() {
        assertEquals(
            "Removed milk from your shopping list.",
            ListConfirmationText.removed("milk", "shopping list"),
        )
    }

    @Test
    fun `not found reply is a natural sentence`() {
        assertEquals(
            "I couldn't find milk in your shopping list.",
            ListConfirmationText.notFound("milk", "shopping list"),
        )
    }

    @Test
    fun `item trailing punctuation does not leak into the sentence`() {
        assertEquals(
            "Added milk to your shopping list.",
            ListConfirmationText.added(listOf("milk."), "shopping list"),
        )
        assertEquals(
            "Removed eggs from your to-do list.",
            ListConfirmationText.removed(" eggs, ", "to-do list"),
        )
    }

    @Test
    fun `punctuation-only items are dropped from add confirmations`() {
        assertEquals(
            "Added milk to your shopping list.",
            ListConfirmationText.added(listOf("milk", ".", " - ", ""), "shopping list"),
        )
        assertEquals(
            "No items added to your shopping list.",
            ListConfirmationText.added(listOf(".", ""), "shopping list"),
        )
    }

    @Test
    fun `list read of a short list names every item`() {
        assertEquals(
            "Your shopping list has 3 items: milk, eggs and bread.",
            ListConfirmationText.spokenListed("shopping list", listOf("milk", "eggs", "bread")),
        )
    }

    @Test
    fun `list read of a single item uses the singular`() {
        assertEquals(
            "Your shopping list has 1 item: milk.",
            ListConfirmationText.spokenListed("shopping list", listOf("milk")),
        )
    }

    @Test
    fun `list read of ten short items names all of them`() {
        val items = listOf("milk", "eggs", "bread", "rice", "tea", "oats", "jam", "salt", "pepper", "honey")
        assertEquals(
            "Your shopping list has 10 items: milk, eggs, bread, rice, tea, oats, jam, salt, pepper and honey.",
            ListConfirmationText.spokenListed("shopping list", items),
        )
    }

    @Test
    fun `list read over the budget names what fits and counts only the rest`() {
        val items = (1..12).map { "Grocery item %02d".format(it) }
        // 8 names fit the 180-char budget; the remaining 4 are counted.
        val expected = "Your shopping list has 12 items: " +
            (1..8).joinToString(", ") { "Grocery item %02d".format(it) } + " and 4 more."
        assertEquals(expected, ListConfirmationText.spokenListed("shopping list", items))
    }

    @Test
    fun `list read of a list whose names cannot fit counts everything`() {
        val items = (1..3).map { "x".repeat(200) + it }
        assertEquals(
            "Your shopping list has 3 items.",
            ListConfirmationText.spokenListed("shopping list", items),
        )
    }

    @Test
    fun `list read counts raw stored items even when some are punctuation-only`() {
        assertEquals(
            "Your shopping list has 3 items: milk, eggs and 1 more.",
            ListConfirmationText.spokenListed("shopping list", listOf("milk", ".", "eggs")),
        )
    }

    @Test
    fun `speakable name is null for punctuation-only items`() {
        assertEquals(null, ListConfirmationText.speakableName(" - "))
        assertEquals("milk", ListConfirmationText.speakableName(" milk. "))
    }

    @Test
    fun `custom list name is used verbatim after your`() {
        assertEquals(
            "Added golden journey item to your widget test.",
            ListConfirmationText.added(listOf("golden journey item"), "widget test"),
        )
    }
}
