package com.kernel.ai.core.skills.natives

import org.junit.jupiter.api.Assertions.assertEquals
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
    fun `two-item add joins with and`() {
        assertEquals(
            "Added milk and bread to your shopping list.",
            ListConfirmationText.added(listOf("milk", "bread"), "shopping list"),
        )
    }

    @Test
    fun `spoken add names short lists in full`() {
        assertEquals(
            "Added milk, bread, eggs and tea to your shopping list.",
            ListConfirmationText.spokenAdded(listOf("milk", "bread", "eggs", "tea"), "shopping list"),
        )
    }

    @Test
    fun `spoken add of more than the item limit speaks a count and still ends with the list name`() {
        val items = listOf("milk", "bread", "eggs", "tea", "rice", "oats", "jam", "salt", "pepper", "honey")
        assertEquals(
            "Added 10 items to your shopping list.",
            ListConfirmationText.spokenAdded(items, "shopping list"),
        )
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
    fun `list read of a long list names the first items and counts the rest`() {
        val items = listOf("milk", "eggs", "bread", "rice", "tea", "oats", "jam", "salt", "pepper", "honey")
        assertEquals(
            "Your shopping list has 10 items: milk, eggs, bread, rice and 6 more.",
            ListConfirmationText.spokenListed("shopping list", items),
        )
    }

    @Test
    fun `custom list name is used verbatim after your`() {
        assertEquals(
            "Added golden journey item to your widget test.",
            ListConfirmationText.added(listOf("golden journey item"), "widget test"),
        )
    }
}
