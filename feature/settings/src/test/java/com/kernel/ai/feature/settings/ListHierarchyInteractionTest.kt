package com.kernel.ai.feature.settings

import com.kernel.ai.core.memory.entity.ListItemEntity
import com.kernel.ai.core.memory.lists.EffectiveHierarchyGroup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Hierarchy gestures are split in two: swipe changes depth, drag only changes order.
 * These cover the projection and eligibility rules that decide both.
 */
class ListHierarchyInteractionTest {

    @Test
    fun `hierarchy gestures are enabled only for manual all unsearched single-select state`() {
        assertTrue(isHierarchyDragEnabled(ItemSort.MANUAL, ItemFilter.ALL, "", false))
        assertFalse(isHierarchyDragEnabled(ItemSort.NAME_ASC, ItemFilter.ALL, "", false))
        assertFalse(isHierarchyDragEnabled(ItemSort.MANUAL, ItemFilter.FAVOURITES_ONLY, "", false))
        assertFalse(isHierarchyDragEnabled(ItemSort.MANUAL, ItemFilter.ALL, "milk", false))
        assertFalse(isHierarchyDragEnabled(ItemSort.MANUAL, ItemFilter.ALL, "", true))
    }

    @Test
    fun `top-level drag never nests the dragged row`() {
        val a = item(1)
        val b = item(2)
        val c = item(3)
        val groups = listOf(a, b, c).map { EffectiveHierarchyGroup(it, emptyList()) }

        val moved = moveHierarchyRows(
            current = listOf(a, b, c),
            groups = groups,
            draggedId = c.id,
            targetId = a.id,
        )

        assertEquals(listOf(3L, 1L, 2L), moved.map { it.id })
        assertNull(dragPlacementFor(moved, topLevelRowIds(groups), c.id)?.parentItemId)
    }

    @Test
    fun `top-level group only lands on another group boundary`() {
        val frozen = item(1)
        val iceCream = item(2, parentItemId = frozen.itemId)
        val peas = item(3, parentItemId = frozen.itemId)
        val bakery = item(4)
        val bread = item(5, parentItemId = bakery.itemId)
        val groups = listOf(
            EffectiveHierarchyGroup(frozen, listOf(iceCream, peas)),
            EffectiveHierarchyGroup(bakery, listOf(bread)),
        )

        val moved = moveHierarchyRows(
            current = listOf(frozen, iceCream, peas, bakery, bread),
            groups = groups,
            draggedId = frozen.id,
            targetId = bread.id,
        )

        assertEquals(listOf(4L, 5L, 1L, 2L, 3L), moved.map { it.id })
    }

    @Test
    fun `child reorder inside its group stays with the same parent`() {
        val parent = item(1)
        val a = item(2, parentItemId = parent.itemId)
        val b = item(3, parentItemId = parent.itemId)
        val c = item(4, parentItemId = parent.itemId)
        val groups = listOf(EffectiveHierarchyGroup(parent, listOf(a, b, c)))

        val moved = moveHierarchyRows(
            current = listOf(parent, a, b, c),
            groups = groups,
            draggedId = c.id,
            targetId = b.id,
        )

        assertEquals(listOf(1L, 2L, 4L, 3L), moved.map { it.id })
        assertEquals(parent.itemId, dragPlacementFor(moved, topLevelRowIds(groups), c.id)?.parentItemId)
    }

    @Test
    fun `child dragged into another group reparents to that group parent`() {
        val frozen = item(1)
        val iceCream = item(2, parentItemId = frozen.itemId)
        val bakery = item(4)
        val bread = item(5, parentItemId = bakery.itemId)
        val groups = listOf(
            EffectiveHierarchyGroup(frozen, listOf(iceCream)),
            EffectiveHierarchyGroup(bakery, listOf(bread)),
        )
        val current = listOf(frozen, iceCream, bakery, bread)

        val moved = moveHierarchyRows(
            current = current,
            groups = groups,
            draggedId = iceCream.id,
            targetId = bread.id,
        )

        assertEquals(listOf(1L, 4L, 5L, 2L), moved.map { it.id })
        assertEquals(bakery.itemId, dragPlacementFor(moved, topLevelRowIds(groups), iceCream.id)?.parentItemId)
        assertEquals(bakery.id, crossGroupDestinationRowId(groups, moved, iceCream.id))
    }

    @Test
    fun `child dropping onto a standalone row makes that row its parent`() {
        val frozen = item(1)
        val iceCream = item(2, parentItemId = frozen.itemId)
        val bakery = item(4)
        val groups = listOf(
            EffectiveHierarchyGroup(frozen, listOf(iceCream)),
            EffectiveHierarchyGroup(bakery, emptyList()),
        )

        val moved = moveHierarchyRows(
            current = listOf(frozen, iceCream, bakery),
            groups = groups,
            draggedId = iceCream.id,
            targetId = bakery.id,
        )

        assertEquals(listOf(1L, 4L, 2L), moved.map { it.id })
        assertEquals(bakery.itemId, dragPlacementFor(moved, topLevelRowIds(groups), iceCream.id)?.parentItemId)
        assertEquals(bakery.id, crossGroupDestinationRowId(groups, moved, iceCream.id))
    }

    @Test
    fun `a drag that keeps the current parent reports no destination highlight`() {
        val parent = item(1)
        val a = item(2, parentItemId = parent.itemId)
        val b = item(3, parentItemId = parent.itemId)
        val groups = listOf(EffectiveHierarchyGroup(parent, listOf(a, b)))

        val moved = moveHierarchyRows(
            current = listOf(parent, a, b),
            groups = groups,
            draggedId = b.id,
            targetId = a.id,
        )

        assertNull(crossGroupDestinationRowId(groups, moved, b.id))
    }

    @Test
    fun `a top-level drag never reports a destination highlight`() {
        val a = item(1)
        val b = item(2)
        val groups = listOf(a, b).map { EffectiveHierarchyGroup(it, emptyList()) }

        val moved = moveHierarchyRows(
            current = listOf(a, b),
            groups = groups,
            draggedId = b.id,
            targetId = a.id,
        )

        assertNull(crossGroupDestinationRowId(groups, moved, b.id))
    }

    @Test
    fun `indent is offered only to a top-level row with a group above it and no children`() {
        val frozen = item(1)
        val iceCream = item(2, parentItemId = frozen.itemId)
        val peas = item(3)
        val groups = listOf(
            EffectiveHierarchyGroup(frozen, listOf(iceCream)),
            EffectiveHierarchyGroup(peas, emptyList()),
        )
        val rows = listOf(frozen, iceCream, peas)

        assertTrue(canIndentRow(rows, groups, peas.id))
        assertFalse(canIndentRow(rows, groups, frozen.id), "first group has nothing above it")
        assertFalse(canIndentRow(rows, groups, iceCream.id), "a child is outdented, not indented")
    }

    @Test
    fun `a parent with children cannot be indented`() {
        val frozen = item(1)
        val iceCream = item(2, parentItemId = frozen.itemId)
        val other = item(3)
        val groups = listOf(
            EffectiveHierarchyGroup(frozen, listOf(iceCream)),
            EffectiveHierarchyGroup(other, emptyList()),
        )

        assertFalse(canIndentRow(listOf(frozen, iceCream, other), groups, frozen.id))
        assertFalse(
            canIndentRow(listOf(other, frozen, iceCream), groups, frozen.id),
            "a row with children is never indented, whatever sits above it",
        )
    }

    @Test
    fun `outdent is offered only to effective children`() {
        val frozen = item(1)
        val iceCream = item(2, parentItemId = frozen.itemId)
        val bakery = item(3)
        val groups = listOf(
            EffectiveHierarchyGroup(frozen, listOf(iceCream)),
            EffectiveHierarchyGroup(bakery, emptyList()),
        )

        assertTrue(canOutdentRow(groups, iceCream.id))
        assertFalse(canOutdentRow(groups, frozen.id))
        assertFalse(canOutdentRow(groups, bakery.id))
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
            orderKey = id.toString(),
        )
}
