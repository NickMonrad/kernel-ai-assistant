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
    fun `custom list name is used verbatim after your`() {
        assertEquals(
            "Added golden journey item to your widget test.",
            ListConfirmationText.added(listOf("golden journey item"), "widget test"),
        )
    }
}
