package com.kernel.ai.feature.settings

import com.kernel.ai.core.memory.entity.ListItemEntity
import com.kernel.ai.core.memory.lists.EffectiveHierarchyGroup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ListHierarchyInteractionTest {
    @Test
    fun `drop position distinguishes nesting from bounded insertion slots`() {
        assertEquals(
            ItemDropIntent.NEST,
            itemDropIntentForPosition(50f, 0f, 100f, sourceHasChildren = false, targetIsChild = false),
        )
        assertEquals(
            ItemDropIntent.INSERT_BEFORE,
            itemDropIntentForPosition(10f, 0f, 100f, sourceHasChildren = false, targetIsChild = false),
        )
        assertEquals(
            ItemDropIntent.INSERT_AFTER,
            itemDropIntentForPosition(90f, 0f, 100f, sourceHasChildren = false, targetIsChild = false),
        )
        assertEquals(
            ItemDropIntent.INSERT_AFTER,
            itemDropIntentForPosition(50f, 0f, 100f, sourceHasChildren = true, targetIsChild = false),
        )
        assertEquals(
            ItemDropIntent.INSERT_AFTER,
            itemDropIntentForPosition(60f, 0f, 100f, sourceHasChildren = false, targetIsChild = true),
        )
    }

    @Test
    fun `parent drag moves parent and all children as one visual group`() {
        val parentOne = item(1)
        val childOne = item(2, parentItemId = parentOne.itemId)
        val parentTwo = item(3)
        val childTwo = item(4, parentItemId = parentTwo.itemId)
        val parentThree = item(5)
        val groups = listOf(
            EffectiveHierarchyGroup(parentOne, listOf(childOne)),
            EffectiveHierarchyGroup(parentTwo, listOf(childTwo)),
            EffectiveHierarchyGroup(parentThree, emptyList()),
        )

        val moved = moveHierarchyRows(
            current = listOf(parentOne, childOne, parentTwo, childTwo, parentThree),
            groups = groups,
            draggedId = parentTwo.id,
            targetId = parentThree.id,
            intent = ItemDropIntent.INSERT_AFTER,
        )

        assertEquals(listOf(1L, 2L, 5L, 3L, 4L), moved.map { it.id })
    }

    @Test
    fun `child insert and nest preserve explicit target semantics`() {
        val parentOne = item(1)
        val childOne = item(2, parentItemId = parentOne.itemId)
        val childTwo = item(3, parentItemId = parentOne.itemId)
        val parentTwo = item(4)
        val groups = listOf(
            EffectiveHierarchyGroup(parentOne, listOf(childOne, childTwo)),
            EffectiveHierarchyGroup(parentTwo, emptyList()),
        )
        val reordered = moveHierarchyRows(
            listOf(parentOne, childOne, childTwo, parentTwo), groups, 3L, 2L, ItemDropIntent.INSERT_BEFORE,
        )
        val nested = moveHierarchyRows(
            listOf(parentOne, childOne, parentTwo), groups, 2L, 4L, ItemDropIntent.NEST,
        )

        assertEquals(listOf(1L, 3L, 2L, 4L), reordered.map { it.id })
        assertEquals(listOf(1L, 4L, 2L), nested.map { it.id })
    }

    @Test
    fun `select all follows the projected completed section visibility`() {
        val active = listOf(item(1), item(2))
        val completed = listOf(item(3, checked = true))

        assertEquals(listOf(1L, 2L), visibleSelectableItemIds(active, completed, completedExpanded = false))
        assertEquals(listOf(1L, 2L, 3L), visibleSelectableItemIds(active, completed, completedExpanded = true))
    }

    private fun item(id: Long, parentItemId: String? = null, checked: Boolean = false) =
        ListItemEntity(
            id = id,
            listId = 1L,
            text = "item-$id",
            itemId = "stable-$id",
            parentItemId = parentItemId,
            checked = checked,
        )
}
