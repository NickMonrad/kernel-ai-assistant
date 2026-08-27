package com.kernel.ai.feature.widget

import com.kernel.ai.core.memory.entity.ListItemEntity
import com.kernel.ai.core.memory.entity.ListNameEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ListsWidgetProjectionTest {

    private fun item(
        id: Long,
        text: String,
        checked: Boolean = false,
        displayOrder: Long = 0L,
        createdAt: Long = id,
    ) = ListItemEntity(
        id = id,
        listId = 1L,
        text = text,
        createdAt = createdAt,
        updatedAt = createdAt,
        checked = checked,
        displayOrder = displayOrder,
    )

    private val activeName = ListNameEntity(id = 1L, name = "shopping list", createdAt = 0L, updatedAt = 0L)

    @Test
    fun `not configured when no list selected`() {
        assertTrue(
            projectListsWidget(ListsWidgetConfig.INVALID, activeName, emptyList())
                is ListsWidgetProjection.NotConfigured,
        )
    }

    @Test
    fun `missing when the selected list no longer exists`() {
        assertTrue(projectListsWidget(1L, null, emptyList()) is ListsWidgetProjection.Missing)
    }

    @Test
    fun `empty when the active list has no active items`() {
        val projected = projectListsWidget(1L, activeName, listOf(item(1, "done", checked = true)))
        assertEquals(ListsWidgetProjection.Empty(1L, "shopping list"), projected)
    }

    @Test
    fun `configured shows only active items sorted by display order then recency`() {
        val items = listOf(
            item(3, "C", displayOrder = 2L, createdAt = 30L),
            item(1, "A", displayOrder = 0L, createdAt = 10L),
            item(2, "B", displayOrder = 1L, createdAt = 20L),
            item(4, "done", checked = true),
        )
        val projected = projectListsWidget(1L, activeName, items)
        assertTrue(projected is ListsWidgetProjection.Configured)
        projected as ListsWidgetProjection.Configured
        assertEquals("shopping list", projected.listName)
        assertEquals(listOf("A", "B", "C"), projected.activeItems)
    }

    @Test
    fun `archived list projects as archived but still surfaces active items`() {
        val archivedName = ListNameEntity(
            id = 1L,
            name = "old",
            createdAt = 0L,
            updatedAt = 0L,
            archivedAt = 100L,
        )
        val items = listOf(item(1, "A"), item(2, "B", checked = true))
        val projected = projectListsWidget(1L, archivedName, items)
        assertTrue(projected is ListsWidgetProjection.Archived)
        projected as ListsWidgetProjection.Archived
        assertEquals(listOf("A"), projected.activeItems)
    }
}
