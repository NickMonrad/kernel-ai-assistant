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
    fun `top-level insertion moves a row to the exact root gap`() {
        val first = item(1)
        val second = item(2)
        val dragged = item(3)
        val groups = listOf(first, second, dragged).map { EffectiveHierarchyGroup(it, emptyList()) }

        val placement = pendingHierarchyPlacement(
            completeGroups = groups,
            visibleGroups = groups,
            collapsedParentItemIds = emptySet(),
            draggedRowId = dragged.id,
            target = HierarchyDropTarget.TopLevelInsertion(beforeParentRowId = second.id),
        )

        assertEquals(listOf(1L, 3L, 2L), placement?.resultGroups?.map { it.parent.id })
        assertNull(placement?.parentItemId)
        assertEquals(first.orderKey, placement?.lowerOrderKey)
        assertEquals(second.orderKey, placement?.upperOrderKey)
    }

    @Test
    fun `dropping on a collapsed parent appends after its complete hidden child sequence`() {
        val parent = item(1)
        val first = item(2, parentItemId = parent.itemId)
        val second = item(3, parentItemId = parent.itemId)
        val dragged = item(4)
        val complete = listOf(
            EffectiveHierarchyGroup(parent, listOf(first, second)),
            EffectiveHierarchyGroup(dragged, emptyList()),
        )
        val collapsed = setOf(parent.itemId)
        val visible = visibleHierarchyGroups(complete, collapsed, "")

        val placement = pendingHierarchyPlacement(
            completeGroups = complete,
            visibleGroups = visible,
            collapsedParentItemIds = collapsed,
            draggedRowId = dragged.id,
            target = HierarchyDropTarget.ParentRow(parent.id),
        )

        assertEquals(listOf(2L, 3L, 4L), placement?.resultGroups?.first()?.children?.map { it.id })
        assertEquals(parent.itemId, placement?.parentItemId)
        assertEquals(second.orderKey, placement?.lowerOrderKey)
        assertNull(placement?.upperOrderKey)
        assertTrue(placement?.reparents == true)

        val dragPreview = placement?.resultGroups?.let {
            visibleHierarchyGroups(
                it,
                collapsed,
                searchQuery = "",
                previewedChildRowId = dragged.id,
            )
        }
        val committedProjection = placement?.resultGroups?.let {
            visibleHierarchyGroups(it, collapsed, searchQuery = "")
        }
        assertEquals(listOf(dragged.id), dragPreview?.firstOrNull()?.children?.map { it.id })
        assertEquals(emptyList<Long>(), committedProjection?.firstOrNull()?.children?.map { it.id })
        assertEquals(setOf(parent.itemId), collapsed)
    }

    @Test
    fun `reparenting an existing child onto a lower collapsed parent appends it last`() {
        val sourceParent = item(1)
        val dragged = item(2, parentItemId = sourceParent.itemId)
        val destinationParent = item(3)
        val existingChild = item(4, parentItemId = destinationParent.itemId)
        val complete = listOf(
            EffectiveHierarchyGroup(sourceParent, listOf(dragged)),
            EffectiveHierarchyGroup(destinationParent, listOf(existingChild)),
        )
        val collapsed = setOf(destinationParent.itemId)
        val visible = visibleHierarchyGroups(complete, collapsed, searchQuery = "")

        val placement = pendingHierarchyPlacement(
            completeGroups = complete,
            visibleGroups = visible,
            collapsedParentItemIds = collapsed,
            draggedRowId = dragged.id,
            target = HierarchyDropTarget.ParentRow(destinationParent.id),
        )

        assertEquals(listOf(1L, 3L), placement?.resultGroups?.map { it.parent.id })
        assertEquals(
            listOf(existingChild.id, dragged.id),
            placement?.resultGroups?.last()?.children?.map { it.id },
        )
        assertEquals(destinationParent.itemId, placement?.parentItemId)
        assertEquals(existingChild.orderKey, placement?.lowerOrderKey)
        assertNull(placement?.upperOrderKey)
        assertTrue(placement?.reparents == true)
    }

    @Test
    fun `child insertion indicator inserts before the exact expanded sibling`() {
        val parent = item(1)
        val first = item(2, parentItemId = parent.itemId)
        val second = item(3, parentItemId = parent.itemId)
        val dragged = item(4)
        val groups = listOf(
            EffectiveHierarchyGroup(parent, listOf(first, second)),
            EffectiveHierarchyGroup(dragged, emptyList()),
        )

        val placement = pendingHierarchyPlacement(
            completeGroups = groups,
            visibleGroups = groups,
            collapsedParentItemIds = emptySet(),
            draggedRowId = dragged.id,
            target = HierarchyDropTarget.ChildInsertion(parent.id, second.id),
        )

        assertEquals(listOf(2L, 4L, 3L), placement?.resultGroups?.first()?.children?.map { it.id })
        assertEquals(parent.itemId, placement?.parentItemId)
        assertEquals(first.orderKey, placement?.lowerOrderKey)
        assertEquals(second.orderKey, placement?.upperOrderKey)
    }

    @Test
    fun `moving a child within its parent preserves ownership and exact sibling order`() {
        val parent = item(1)
        val first = item(2, parentItemId = parent.itemId)
        val second = item(3, parentItemId = parent.itemId)
        val dragged = item(4, parentItemId = parent.itemId)
        val groups = listOf(EffectiveHierarchyGroup(parent, listOf(first, second, dragged)))

        val placement = pendingHierarchyPlacement(
            completeGroups = groups,
            visibleGroups = groups,
            collapsedParentItemIds = emptySet(),
            draggedRowId = dragged.id,
            target = HierarchyDropTarget.ChildInsertion(parent.id, first.id),
        )

        assertEquals(listOf(4L, 2L, 3L), placement?.resultGroups?.single()?.children?.map { it.id })
        assertEquals(parent.itemId, placement?.parentItemId)
        assertFalse(placement?.reparents == true)
    }

    @Test
    fun `a parent group cannot be implicitly flattened by a child insertion target`() {
        val parent = item(1)
        val child = item(2, parentItemId = parent.itemId)
        val other = item(3)
        val groups = listOf(
            EffectiveHierarchyGroup(parent, listOf(child)),
            EffectiveHierarchyGroup(other, emptyList()),
        )

        assertNull(
            pendingHierarchyPlacement(
                completeGroups = groups,
                visibleGroups = groups,
                collapsedParentItemIds = emptySet(),
                draggedRowId = parent.id,
                target = HierarchyDropTarget.ParentRow(other.id),
            ),
        )
    }

    @Test
    fun `collapsed children stay in automatic-sort materialisation while root rows reorder`() {
        val parent = item(1)
        val first = item(2, parentItemId = parent.itemId)
        val second = item(3, parentItemId = parent.itemId)
        val dragged = item(4)
        val complete = listOf(
            EffectiveHierarchyGroup(parent, listOf(first, second)),
            EffectiveHierarchyGroup(dragged, emptyList()),
        )
        val collapsed = setOf(parent.itemId)

        val placement = pendingHierarchyPlacement(
            completeGroups = complete,
            visibleGroups = visibleHierarchyGroups(complete, collapsed, ""),
            collapsedParentItemIds = collapsed,
            draggedRowId = dragged.id,
            target = HierarchyDropTarget.TopLevelInsertion(beforeParentRowId = parent.id),
        )

        assertEquals(listOf(4L, 1L), placement?.resultGroups?.map { it.parent.id })
        assertEquals(listOf(2L, 3L), placement?.resultGroups?.last()?.children?.map { it.id })
    }

    @Test
    fun `search reveals matching child rows without changing saved collapse IDs`() {
        val parent = item(1)
        val matchingChild = item(2, parentItemId = parent.itemId)
        val collapsed = setOf(parent.itemId)
        val searchProjection = listOf(EffectiveHierarchyGroup(parent, listOf(matchingChild)))

        val visible = visibleHierarchyGroups(searchProjection, collapsed, "matching")

        assertEquals(listOf(matchingChild.id), visible.single().children.map { it.id })
        assertTrue(parent.itemId in collapsed)
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
    fun `an ineligible depth direction never drives handle feedback`() {
        // The first effective top-level row: no group above it, and not a child.
        assertEquals(0f, permittedDepthDisplacement(200f, canMakeSubItem = false, canMoveToTopLevel = false))
        assertEquals(0f, permittedDepthDisplacement(-200f, canMakeSubItem = false, canMoveToTopLevel = false))

        // A later top-level row that is not an effective child cannot move to top level, but its
        // rightward make-sub-item is unaffected.
        assertEquals(0f, permittedDepthDisplacement(-200f, canMakeSubItem = true, canMoveToTopLevel = false))
        assertEquals(200f, permittedDepthDisplacement(200f, canMakeSubItem = true, canMoveToTopLevel = false))
    }

    @Test
    fun `an eligible depth direction still drives handle feedback`() {
        // A later top-level row with a group above it can become a child.
        assertEquals(80f, permittedDepthDisplacement(80f, canMakeSubItem = true, canMoveToTopLevel = false))
        // An effective child can move to top level, and that never enables the rightward side.
        assertEquals(-80f, permittedDepthDisplacement(-80f, canMakeSubItem = false, canMoveToTopLevel = true))
        assertEquals(0f, permittedDepthDisplacement(80f, canMakeSubItem = false, canMoveToTopLevel = true))
    }

    @Test
    fun `reversing an eligible rightward gesture retracts the feedback towards zero`() {
        // Rightwards with a group above: the row may move right.
        assertEquals(200f, permittedDepthDisplacement(200f, canMakeSubItem = true, canMoveToTopLevel = false))
        // Reversing towards the origin keeps the smaller cumulative displacement, so the reveal
        // retracts instead of staying where the outward movement left it.
        assertEquals(40f, permittedDepthDisplacement(40f, canMakeSubItem = true, canMoveToTopLevel = false))
        assertFalse(isDepthCommitReached(40f, commitDistancePx = 120f))
        // Past the origin nothing is revealed, because leftwards is ineligible for this row.
        assertEquals(0f, permittedDepthDisplacement(-60f, canMakeSubItem = true, canMoveToTopLevel = false))
        assertFalse(isDepthCommitReached(0f, commitDistancePx = 120f))
        // Only a displacement that is still past the commit distance commits.
        assertTrue(isDepthCommitReached(200f, commitDistancePx = 120f))
    }

    @Test
    fun `reversing an eligible leftward gesture retracts the feedback towards zero`() {
        // An effective child may move left.
        assertEquals(-200f, permittedDepthDisplacement(-200f, canMakeSubItem = false, canMoveToTopLevel = true))
        assertEquals(-40f, permittedDepthDisplacement(-40f, canMakeSubItem = false, canMoveToTopLevel = true))
        assertFalse(isDepthCommitReached(-40f, commitDistancePx = 120f))
        // Reversing past the origin reveals nothing, because rightwards is ineligible here.
        assertEquals(0f, permittedDepthDisplacement(60f, canMakeSubItem = false, canMoveToTopLevel = true))
        assertFalse(isDepthCommitReached(0f, commitDistancePx = 120f))
        assertTrue(isDepthCommitReached(-200f, commitDistancePx = 120f))
    }

    @Test
    fun `no horizontal movement carries no depth direction`() {
        assertEquals(0f, permittedDepthDisplacement(0f, canMakeSubItem = true, canMoveToTopLevel = true))
        assertFalse(isDepthCommitReached(0f, commitDistancePx = 48f))
    }

    @Test
    fun `vertical handle movement is classified the same whatever the depth eligibility`() {
        val slop = 24f

        // Axis locking is independent of eligibility, so a row that can perform neither depth
        // action still reorders vertically from the same handle.
        assertEquals(MoveAxis.Vertical, moveAxisFor(4f, slop, slop))
        assertEquals(MoveAxis.Horizontal, moveAxisFor(slop, 4f, slop))
        assertEquals(0f, permittedDepthDisplacement(slop, canMakeSubItem = false, canMoveToTopLevel = false))
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

    @Test
    fun `select all excludes children hidden only by a collapsed parent`() {
        val parent = item(1)
        val child = item(2, parentItemId = parent.itemId)
        val standalone = item(3)
        val completeGroups = listOf(
            EffectiveHierarchyGroup(parent, listOf(child)),
            EffectiveHierarchyGroup(standalone, emptyList()),
        )
        val collapsedRows = visibleHierarchyGroups(
            completeGroups,
            collapsedParentItemIds = setOf(parent.itemId),
            searchQuery = "",
        ).flatMap { listOf(it.parent) + it.children }

        assertEquals(
            listOf(parent.id, standalone.id),
            visibleSelectableItemIds(collapsedRows, emptyList(), completedExpanded = false),
        )
        assertEquals(listOf(child.id), completeGroups.first().children.map { it.id })
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
