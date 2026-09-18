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
    fun `hierarchy editing stays available under any sort while the projection is complete`() {
        // Sort is deliberately not part of the rule: handles and gestures stay discoverable under
        // Created/Updated/Name/Due/Favourites, and the first interaction materialises the visible
        // order as the Manual baseline.
        assertTrue(isHierarchyEditingEnabled(ItemFilter.ALL, "", false))
    }

    @Test
    fun `hierarchy editing is disabled when the visible projection is incomplete or ambiguous`() {
        assertFalse(isHierarchyEditingEnabled(ItemFilter.ALL, "milk", false))
        assertFalse(isHierarchyEditingEnabled(ItemFilter.FAVOURITES_ONLY, "", false))
        assertFalse(isHierarchyEditingEnabled(ItemFilter.ACTIVE_ONLY, "", false))
        assertFalse(isHierarchyEditingEnabled(ItemFilter.COMPLETED_ONLY, "", false))
        assertFalse(isHierarchyEditingEnabled(ItemFilter.ALL, "", true))
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
    fun `child dragged down one sibling lands directly after it`() {
        val parent = item(1)
        val a = item(2, parentItemId = parent.itemId)
        val b = item(3, parentItemId = parent.itemId)
        val c = item(4, parentItemId = parent.itemId)
        val groups = listOf(EffectiveHierarchyGroup(parent, listOf(a, b, c)))

        val moved = moveHierarchyRows(
            current = listOf(parent, a, b, c),
            groups = groups,
            draggedId = a.id,
            targetId = b.id,
        )

        assertEquals(listOf(1L, 3L, 2L, 4L), moved.map { it.id })
        assertEquals(parent.itemId, dragPlacementFor(moved, topLevelRowIds(groups), a.id)?.parentItemId)
    }

    @Test
    fun `child dragged down several siblings lands directly after the target`() {
        val parent = item(1)
        val a = item(2, parentItemId = parent.itemId)
        val b = item(3, parentItemId = parent.itemId)
        val c = item(4, parentItemId = parent.itemId)
        val d = item(5, parentItemId = parent.itemId)
        val groups = listOf(EffectiveHierarchyGroup(parent, listOf(a, b, c, d)))

        val moved = moveHierarchyRows(
            current = listOf(parent, a, b, c, d),
            groups = groups,
            draggedId = a.id,
            targetId = c.id,
        )

        assertEquals(listOf(1L, 3L, 4L, 2L, 5L), moved.map { it.id })
        assertEquals(parent.itemId, dragPlacementFor(moved, topLevelRowIds(groups), a.id)?.parentItemId)
    }

    @Test
    fun `child dragged up lands directly before the target`() {
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
    fun `child dragged down into another group lands at the indicated sibling slot`() {
        val first = item(1)
        val a = item(2, parentItemId = first.itemId)
        val b = item(3, parentItemId = first.itemId)
        val second = item(4)
        val c = item(5, parentItemId = second.itemId)
        val d = item(6, parentItemId = second.itemId)
        val groups = listOf(
            EffectiveHierarchyGroup(first, listOf(a, b)),
            EffectiveHierarchyGroup(second, listOf(c, d)),
        )

        val moved = moveHierarchyRows(
            current = listOf(first, a, b, second, c, d),
            groups = groups,
            draggedId = a.id,
            targetId = c.id,
        )

        assertEquals(listOf(1L, 3L, 4L, 5L, 2L, 6L), moved.map { it.id })
        assertEquals(second.itemId, dragPlacementFor(moved, topLevelRowIds(groups), a.id)?.parentItemId)
    }

    @Test
    fun `child dragged up into another group lands directly before the target`() {
        val first = item(1)
        val a = item(2, parentItemId = first.itemId)
        val b = item(3, parentItemId = first.itemId)
        val second = item(4)
        val c = item(5, parentItemId = second.itemId)
        val d = item(6, parentItemId = second.itemId)
        val groups = listOf(
            EffectiveHierarchyGroup(first, listOf(a, b)),
            EffectiveHierarchyGroup(second, listOf(c, d)),
        )

        val moved = moveHierarchyRows(
            current = listOf(first, a, b, second, c, d),
            groups = groups,
            draggedId = d.id,
            targetId = b.id,
        )

        assertEquals(listOf(1L, 2L, 6L, 3L, 4L, 5L), moved.map { it.id })
        assertEquals(first.itemId, dragPlacementFor(moved, topLevelRowIds(groups), d.id)?.parentItemId)
    }

    @Test
    fun `sibling reorder inside its group stays with the same parent`() {
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
    fun `make sub-item is offered to a top-level row with a group above it`() {
        val frozen = item(1)
        val iceCream = item(2, parentItemId = frozen.itemId)
        val peas = item(3)
        val groups = listOf(
            EffectiveHierarchyGroup(frozen, listOf(iceCream)),
            EffectiveHierarchyGroup(peas, emptyList()),
        )
        val rows = listOf(frozen, iceCream, peas)

        assertTrue(canMakeSubItemRow(rows, groups, peas.id))
        assertFalse(canMakeSubItemRow(rows, groups, frozen.id), "first group has nothing above it")
        assertFalse(canMakeSubItemRow(rows, groups, iceCream.id), "a child moves to top level instead")
    }

    @Test
    fun `a parent with children can be made a sub-item and is flattened instead`() {
        val frozen = item(1)
        val iceCream = item(2, parentItemId = frozen.itemId)
        val other = item(3)
        val groups = listOf(
            EffectiveHierarchyGroup(frozen, listOf(iceCream)),
            EffectiveHierarchyGroup(other, emptyList()),
        )

        assertFalse(
            canMakeSubItemRow(listOf(frozen, iceCream, other), groups, frozen.id),
            "the first group has nothing above it",
        )
        assertTrue(
            canMakeSubItemRow(listOf(other, frozen, iceCream), groups, frozen.id),
            "a group below another group moves right as a whole and is flattened",
        )
    }

    @Test
    fun `move to top level is offered only to effective children`() {
        val frozen = item(1)
        val iceCream = item(2, parentItemId = frozen.itemId)
        val bakery = item(3)
        val groups = listOf(
            EffectiveHierarchyGroup(frozen, listOf(iceCream)),
            EffectiveHierarchyGroup(bakery, emptyList()),
        )

        assertTrue(canMoveToTopLevelRow(groups, iceCream.id))
        assertFalse(canMoveToTopLevelRow(groups, frozen.id))
        assertFalse(canMoveToTopLevelRow(groups, bakery.id))
    }

    @Test
    fun `rows travelling with a dragged block are never drop targets for it`() {
        val parent = item(1)
        val firstChild = item(2, parentItemId = parent.itemId)
        val secondChild = item(3, parentItemId = parent.itemId)
        val other = item(4)
        val groups = listOf(
            EffectiveHierarchyGroup(parent, listOf(firstChild, secondChild)),
            EffectiveHierarchyGroup(other, emptyList()),
        )

        // Dragging the parent blocks its own rows: the block lands on group boundaries, so the
        // library must not report a move that can never happen.
        assertTrue(isInsideDraggedBlock(groups, parent.id, parent.id))
        assertTrue(isInsideDraggedBlock(groups, parent.id, firstChild.id))
        assertTrue(isInsideDraggedBlock(groups, parent.id, secondChild.id))
        assertFalse(isInsideDraggedBlock(groups, parent.id, other.id))

        // Dragging a child only blocks that child; its sibling stays a valid sibling target.
        assertTrue(isInsideDraggedBlock(groups, firstChild.id, firstChild.id))
        assertFalse(isInsideDraggedBlock(groups, firstChild.id, secondChild.id))
        assertFalse(isInsideDraggedBlock(groups, firstChild.id, parent.id))

        // Outside a drag every row is a target.
        assertFalse(isInsideDraggedBlock(groups, null, parent.id))
    }

    @Test
    fun `a move-handle gesture locks to the dominant axis once touch slop is crossed`() {
        val slop = 24f

        assertEquals(MoveAxis.Undecided, moveAxisFor(0f, 0f, slop))
        assertEquals(MoveAxis.Undecided, moveAxisFor(slop - 1f, slop - 1f, slop))

        assertEquals(MoveAxis.Vertical, moveAxisFor(4f, slop, slop))
        assertEquals(MoveAxis.Vertical, moveAxisFor(-4f, -slop * 2f, slop))
        assertEquals(MoveAxis.Horizontal, moveAxisFor(slop, 4f, slop))
        assertEquals(MoveAxis.Horizontal, moveAxisFor(-slop * 2f, 6f, slop))

        // The axis is read from the whole gesture, so it cannot flip once it has been locked.
        assertEquals(MoveAxis.Horizontal, moveAxisFor(slop * 3f, -slop * 2f, slop))
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
