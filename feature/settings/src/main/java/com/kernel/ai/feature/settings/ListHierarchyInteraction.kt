package com.kernel.ai.feature.settings

import com.kernel.ai.core.memory.entity.ListItemEntity
import com.kernel.ai.core.memory.lists.EffectiveHierarchyGroup

/**
 * Hierarchy editing is offered whenever the visible projection is complete enough to derive
 * placement from it: the full All-items view, no search, and not multi-select.
 *
 * The current item sort is deliberately not part of this. Handles and gestures stay available
 * under automatic sorts, and the first interaction materialises the visible order as the Manual
 * baseline before applying itself.
 */
internal fun isHierarchyEditingEnabled(
    itemFilter: ItemFilter,
    searchQuery: String,
    isMultiSelectMode: Boolean,
): Boolean = itemFilter == ItemFilter.ALL &&
    searchQuery.isBlank() &&
    !isMultiSelectMode

/** Stable ids of every effective top-level row, standalone or parent. */
internal fun topLevelRowIds(groups: List<EffectiveHierarchyGroup<ListItemEntity>>): Set<Long> =
    groups.map { it.parent.id }.toSet()

/** The row that owns [rowId]: itself when top-level, otherwise its effective parent. */
internal fun owningRowId(
    groups: List<EffectiveHierarchyGroup<ListItemEntity>>,
    rowId: Long,
): Long? = if (groups.any { it.parent.id == rowId }) {
    rowId
} else {
    groups.firstOrNull { group -> group.children.any { it.id == rowId } }?.parent?.id
}

/**
 * True when [rowId] can be made a sub-item: an effective top-level row with another group directly
 * above it in the visible projection.
 *
 * A row that already has children is eligible too. It moves as its whole group and is flattened
 * beneath the destination, so the two-level invariant is preserved instead of blocking the gesture.
 */
internal fun canMakeSubItemRow(
    rows: List<ListItemEntity>,
    groups: List<EffectiveHierarchyGroup<ListItemEntity>>,
    rowId: Long,
): Boolean {
    val index = rows.indexOfFirst { it.id == rowId }
    if (index <= 0) return false
    return owningRowId(groups, rowId) == rowId
}

/** True when [rowId] is an effective child and can therefore move to top level. */
internal fun canMoveToTopLevelRow(
    groups: List<EffectiveHierarchyGroup<ListItemEntity>>,
    rowId: Long,
): Boolean = groups.any { group -> group.children.any { it.id == rowId } }

/**
 * Applies the optimistic row movement used while the reorderable library is dragging.
 *
 * Drag never changes hierarchy depth. A top-level row moves as its whole group and lands on
 * another group's boundary, so it can never end up inside a group. A child stays a child of
 * whichever top-level group now contains it, which is what reparents a cross-group child.
 */
internal fun moveHierarchyRows(
    current: List<ListItemEntity>,
    groups: List<EffectiveHierarchyGroup<ListItemEntity>>,
    draggedId: Long,
    targetId: Long,
): List<ListItemEntity> {
    val draggedIndex = current.indexOfFirst { it.id == draggedId }
    val targetIndex = current.indexOfFirst { it.id == targetId }
    if (draggedIndex < 0 || targetIndex < 0 || draggedIndex == targetIndex) return current

    val topLevelIds = topLevelRowIds(groups)
    val draggedIsTopLevel = draggedId in topLevelIds
    val sourceIds = if (draggedIsTopLevel) {
        groups.firstOrNull { it.parent.id == draggedId }
            ?.let { listOf(it.parent.id) + it.children.map(ListItemEntity::id) }
            ?.toSet()
            ?: setOf(draggedId)
    } else {
        setOf(draggedId)
    }
    val sourceRows = current.filter { it.id in sourceIds }
    val remaining = current.filterNot { it.id in sourceIds }
    val ownerId = owningRowId(groups, targetId) ?: return current
    val ownerIndex = remaining.indexOfFirst { it.id == ownerId }
    if (ownerIndex < 0) return current
    // The target's index must be resolved against the list the row is actually inserted into:
    // the source block is already gone, so the original index is stale for downward moves.
    val targetIndexInRemaining = remaining.indexOfFirst { it.id == targetId }
    if (targetIndexInRemaining < 0) return current
    var groupEnd = ownerIndex + 1
    while (groupEnd < remaining.size && remaining[groupEnd].id !in topLevelIds) groupEnd++

    val movingDown = draggedIndex < targetIndex
    val insertIndex = when {
        draggedIsTopLevel -> if (movingDown) groupEnd else ownerIndex
        targetId == ownerId -> if (movingDown) groupEnd else ownerIndex + 1
        movingDown -> targetIndexInRemaining + 1
        else -> targetIndexInRemaining
    }
    return remaining.toMutableList().apply {
        addAll(insertIndex.coerceIn(0, size), sourceRows)
    }
}

/** Placement of a row after a drag, expressed the way the mutation seam consumes it. */
internal data class RowPlacement(
    val parentItemId: String?,
    val lowerOrderKey: String?,
    val upperOrderKey: String?,
)

/**
 * Derives the placement of [draggedId] from the projection it now sits in.
 *
 * A top-level row is placed among top-level rows; a child is placed among the children of the
 * nearest preceding top-level row, so crossing into another group reparents the child.
 */
internal fun dragPlacementFor(
    rows: List<ListItemEntity>,
    topLevelIds: Set<Long>,
    draggedId: Long,
): RowPlacement? {
    val index = rows.indexOfFirst { it.id == draggedId }
    if (index < 0) return null

    if (draggedId in topLevelIds) {
        val topLevel = rows.filter { it.id in topLevelIds }
        val position = topLevel.indexOfFirst { it.id == draggedId }
        if (position < 0) return null
        return RowPlacement(
            parentItemId = null,
            lowerOrderKey = topLevel.getOrNull(position - 1)?.orderKey,
            upperOrderKey = topLevel.getOrNull(position + 1)?.orderKey,
        )
    }

    val ownerIndex = rows.subList(0, index).indexOfLast { it.id in topLevelIds }
    if (ownerIndex < 0) return null
    val owner = rows[ownerIndex]
    val siblings = rows.subList(ownerIndex + 1, rows.size).takeWhile { it.id !in topLevelIds }
    val position = siblings.indexOfFirst { it.id == draggedId }
    if (position < 0) return null
    return RowPlacement(
        parentItemId = owner.itemId,
        lowerOrderKey = siblings.getOrNull(position - 1)?.orderKey,
        upperOrderKey = siblings.getOrNull(position + 1)?.orderKey,
    )
}

/**
 * Row id of the group that will own [draggedId] if the drag is released as projected, or null when
 * the drag leaves the row in its current group. Drives the cross-group destination highlight.
 */
internal fun crossGroupDestinationRowId(
    groups: List<EffectiveHierarchyGroup<ListItemEntity>>,
    rows: List<ListItemEntity>,
    draggedId: Long,
): Long? {
    val currentParent = groups.firstOrNull { group -> group.children.any { it.id == draggedId } }
        ?.parent?.itemId ?: return null
    val destination = dragPlacementFor(rows, topLevelRowIds(groups), draggedId)
        ?.parentItemId ?: return null
    if (destination == currentParent) return null
    return rows.firstOrNull { it.itemId == destination }?.id
}

/** Ids selected by a Select All action over the rows the user can currently see. */
internal fun visibleSelectableItemIds(
    activeRows: List<ListItemEntity>,
    completedRows: List<ListItemEntity>,
    completedExpanded: Boolean,
): List<Long> = activeRows.map(ListItemEntity::id) +
    if (completedExpanded) completedRows.map(ListItemEntity::id) else emptyList()

/** Locked intent of a move-handle gesture once touch slop has been crossed. */
internal enum class MoveAxis { Undecided, Vertical, Horizontal }

/**
 * Classifies a move-handle gesture by its dominant axis.
 *
 * Returns [MoveAxis.Undecided] until the gesture passes [touchSlop], so the intent is only locked
 * once the user has actually moved, and never switches afterwards for that gesture.
 */
internal fun moveAxisFor(totalX: Float, totalY: Float, touchSlop: Float): MoveAxis = when {
    maxOf(kotlin.math.abs(totalX), kotlin.math.abs(totalY)) < touchSlop -> MoveAxis.Undecided
    kotlin.math.abs(totalX) > kotlin.math.abs(totalY) -> MoveAxis.Horizontal
    else -> MoveAxis.Vertical
}

/** Horizontal distance a handle gesture must cover before it commits to a depth change. */
internal const val DEPTH_GESTURE_COMMIT_FRACTION = 0.25f

/**
 * True when [rowId] travels with a drag of [draggedId], so it can never be that drag's drop target.
 *
 * A moved block lands on group boundaries, never inside itself: reporting one of its own rows as a
 * target would only make the reorderable library compensate against a move that cannot happen.
 */
internal fun isInsideDraggedBlock(
    groups: List<EffectiveHierarchyGroup<ListItemEntity>>,
    draggedId: Long?,
    rowId: Long,
): Boolean = draggedId != null && (rowId == draggedId || owningRowId(groups, rowId) == draggedId)

/**
 * True when a horizontal move-handle movement of [deltaX] may drive the depth reveal and commit.
 *
 * The feedback must never advertise an action the row cannot perform: the first effective top-level
 * row has no group above it, and a row that is not an effective child cannot move to top level.
 * A zero movement carries no direction and is therefore never enabled.
 */
internal fun isDepthHandleDirectionEnabled(
    deltaX: Float,
    canMakeSubItem: Boolean,
    canMoveToTopLevel: Boolean,
): Boolean = when {
    deltaX > 0f -> canMakeSubItem
    deltaX < 0f -> canMoveToTopLevel
    else -> false
}
