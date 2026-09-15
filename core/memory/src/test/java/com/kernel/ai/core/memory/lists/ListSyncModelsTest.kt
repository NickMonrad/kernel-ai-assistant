package com.kernel.ai.core.memory.lists

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ListSyncModelsTest {
    @Test
    fun `version stamps compare by logical clock then actor id`() {
        assertTrue(VersionStamp(2, "a") < VersionStamp(3, "z"))
        assertTrue(VersionStamp(4, "a") < VersionStamp(4, "b"))
        assertEquals(0, VersionStamp(5, "same").compareTo(VersionStamp(5, "same")))
    }

    @Test
    fun `payload round trips nullable fields`() {
        val payload = ListChangePayload(
            canonicalTitle = "Groceries",
            text = "Milk",
            checked = false,
            dueAt = null,
            parentItemId = null,
            orderKey = "2.50",
        )

        assertEquals(payload, ListChangePayload.decode(payload.encode()))
    }

    @Test
    fun `order keys use canonical decimal form`() {
        assertEquals("2.5", OrderKey.canonical("02.500"))
        assertEquals("0", OrderKey.canonical("-0"))
        assertTrue(OrderKey.compare("1.10", "1.2") < 0)
    }

    @Test
    fun `hierarchy suppresses lower priority cycles and preserves top level items`() {
        val result = EffectiveHierarchyNormalizer.derive(
            listOf(
                HierarchyItem("a", "b", "0", VersionStamp(1, "actor")),
                HierarchyItem("b", "a", "1", VersionStamp(2, "actor")),
                HierarchyItem("c", null, "2", VersionStamp(1, "actor")),
            ),
        )

        assertEquals(mapOf("b" to "a"), result.parentByChild)
        assertEquals(listOf("a", "c"), result.topLevelItemIds)
        assertNull(result.parentByChild["a"])
    }
}
