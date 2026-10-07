package com.kernel.ai.feature.settings

import com.kernel.ai.core.memory.entity.ListItemEntity
import com.kernel.ai.core.memory.lists.EffectiveHierarchyGroup
import com.kernel.ai.core.memory.lists.EffectiveHierarchyProjection
import com.kernel.ai.core.memory.lists.ListLifecycle
import com.kernel.ai.core.memory.lists.VersionStamp

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

/** Ids selected by a Select All action over the rows the user can currently see. */
internal fun visibleSelectableItemIds(
    activeRows: List<ListItemEntity>,
    completedRows: List<ListItemEntity>,
    completedExpanded: Boolean,
): List<Long> = activeRows.map(ListItemEntity::id) +
    if (completedExpanded) completedRows.map(ListItemEntity::id) else emptyList()

/**
 * Explicit drag destinations. Parent rows append as the last child; insertion indicators address
 * exact sibling gaps and never infer hierarchy from an invisible hover band.
 */
internal sealed interface HierarchyDropTarget {
    data class TopLevelInsertion(val beforeParentRowId: Long?) : HierarchyDropTarget
    data class ChildInsertion(val parentRowId: Long, val beforeChildRowId: Long?) : HierarchyDropTarget
    data class ParentRow(val parentRowId: Long) : HierarchyDropTarget
}

/** Complete, shared preview-and-commit result for one active hierarchy drag. */
internal data class PendingHierarchyPlacement(
    val draggedRowId: Long,
    val target: HierarchyDropTarget,
    /** Requested parent to persist; an unchanged effective owner preserves suppressed requests. */
    val parentItemId: String?,
    val lowerOrderKey: String?,
    val upperOrderKey: String?,
    val reparents: Boolean,
    val changed: Boolean,
    /** Full effective hierarchy after placement, including children hidden by collapse. */
    val resultGroups: List<EffectiveHierarchyGroup<ListItemEntity>>,
)

/** Derives the authoritative two-level groups from the complete active item set. */
internal fun completeEffectiveGroups(
    items: Collection<ListItemEntity>,
): List<EffectiveHierarchyGroup<ListItemEntity>> = EffectiveHierarchyProjection.derive(
    items,
    itemId = ListItemEntity::itemId,
    parentItemId = ListItemEntity::parentItemId,
    orderKey = ListItemEntity::orderKey,
    placementStamp = { VersionStamp(it.placementLogicalClock, it.placementStampActorId) },
    active = { it.lifecycle == ListLifecycle.ACTIVE.name },
)

/**
 * Hides only child rows of saved collapsed groups. A nonblank search projection already contains
 * matching children, so retaining those groups here reveals search results without changing prefs.
 */
internal fun visibleHierarchyGroups(
    groups: List<EffectiveHierarchyGroup<ListItemEntity>>,
    collapsedParentItemIds: Set<String>,
    searchQuery: String,
    previewedChildRowId: Long? = null,
): List<EffectiveHierarchyGroup<ListItemEntity>> = groups.map { group ->
    if (searchQuery.isBlank() && group.parent.itemId in collapsedParentItemIds) {
        group.copy(children = group.children.filter { it.id == previewedChildRowId })
    } else {
        group
    }
}

/**
 * Calculates a complete destination from the effective hierarchy plus the current visible group
 * order. Collapsed groups take hidden children from the authoritative hierarchy; expanded groups
 * take their visible sibling order (including the active automatic-sort presentation).
 */
internal fun pendingHierarchyPlacement(
    completeGroups: List<EffectiveHierarchyGroup<ListItemEntity>>,
    visibleGroups: List<EffectiveHierarchyGroup<ListItemEntity>>,
    collapsedParentItemIds: Set<String>,
    draggedRowId: Long,
    target: HierarchyDropTarget,
): PendingHierarchyPlacement? {
    if (completeGroups.isEmpty() || visibleGroups.isEmpty()) return null
    val completeByParent = completeGroups.associateBy { it.parent.id }
    if (visibleGroups.map { it.parent.id }.toSet() != completeByParent.keys) return null
    val visibleIds = visibleGroups.flatMap { listOf(it.parent.id) + it.children.map(ListItemEntity::id) }
    if (draggedRowId !in visibleIds) return null
    val baseline = visibleGroups.map { visible ->
        val complete = completeByParent[visible.parent.id] ?: return null
        val children = if (visible.parent.itemId in collapsedParentItemIds) {
            complete.children
        } else {
            visible.children
        }
        if (children.any { child -> complete.children.none { it.id == child.id } }) return null
        complete.copy(parent = visible.parent, children = children)
    }

    val sourceGroupIndex = baseline.indexOfFirst { it.parent.id == draggedRowId }
    val sourceChildGroupIndex = if (sourceGroupIndex < 0) {
        baseline.indexOfFirst { group -> group.children.any { it.id == draggedRowId } }
    } else {
        -1
    }
    val sourceItem = when {
        sourceGroupIndex >= 0 -> baseline[sourceGroupIndex].parent
        sourceChildGroupIndex >= 0 ->
            baseline[sourceChildGroupIndex].children.first { it.id == draggedRowId }
        else -> return null
    }
    val sourceGroup = baseline.getOrNull(sourceGroupIndex)
    val sourceHasChildren = sourceGroup?.children?.isNotEmpty() == true
    val currentParentItemId = baseline.getOrNull(sourceChildGroupIndex)?.parent?.itemId

    fun groupsWithoutSource(): MutableList<EffectiveHierarchyGroup<ListItemEntity>> {
        val result = baseline.toMutableList()
        if (sourceGroupIndex >= 0) {
            result.removeAt(sourceGroupIndex)
        } else {
            val source = result[sourceChildGroupIndex]
            result[sourceChildGroupIndex] = source.copy(
                children = source.children.filterNot { it.id == draggedRowId },
            )
        }
        return result
    }

    fun placement(
        result: List<EffectiveHierarchyGroup<ListItemEntity>>,
        parent: String?,
        lower: String?,
        upper: String?,
    ): PendingHierarchyPlacement {
        val reparents = currentParentItemId != parent
        val requestedParent = if (reparents) parent else sourceItem.parentItemId
        val unchanged = baseline.size == result.size && baseline.zip(result).all { (before, after) ->
            before.parent.id == after.parent.id &&
                before.children.map(ListItemEntity::id) == after.children.map(ListItemEntity::id)
        }
        return PendingHierarchyPlacement(
            draggedRowId = draggedRowId,
            target = target,
            parentItemId = requestedParent,
            lowerOrderKey = lower,
            upperOrderKey = upper,
            reparents = reparents,
            changed = !unchanged,
            resultGroups = result,
        )
    }

    return when (target) {
        is HierarchyDropTarget.TopLevelInsertion -> {
            if (target.beforeParentRowId == draggedRowId) return null
            val targetIndex = target.beforeParentRowId?.let { parentId ->
                baseline.indexOfFirst { it.parent.id == parentId }.takeIf { it >= 0 }
            } ?: if (target.beforeParentRowId == null) baseline.size else return null
            val result = groupsWithoutSource()
            val insertAt = target.beforeParentRowId?.let { parentId ->
                result.indexOfFirst { it.parent.id == parentId }.takeIf { it >= 0 } ?: return null
            } ?: result.size
            if (sourceGroupIndex >= 0 && sourceGroupIndex == targetIndex) return null
            val movedGroup = sourceGroup ?: EffectiveHierarchyGroup(sourceItem, emptyList())
            result.add(insertAt, movedGroup)
            val lower = result.getOrNull(insertAt - 1)?.parent?.orderKey
            val upper = result.getOrNull(insertAt + 1)?.parent?.orderKey
            placement(result, parent = null, lower = lower, upper = upper)
        }

        is HierarchyDropTarget.ChildInsertion,
        is HierarchyDropTarget.ParentRow -> {
            if (sourceHasChildren) return null
            val parentRowId = when (target) {
                is HierarchyDropTarget.ChildInsertion -> target.parentRowId
                is HierarchyDropTarget.ParentRow -> target.parentRowId
                else -> error("Unreachable drop target")
            }
            if (parentRowId == draggedRowId) return null
            val destination = baseline.firstOrNull { it.parent.id == parentRowId } ?: return null
            val result = groupsWithoutSource()
            val destinationIndex = result.indexOfFirst { it.parent.id == parentRowId }
            if (destinationIndex < 0) return null
            val children = result[destinationIndex].children.toMutableList()
            val insertAt = when (target) {
                is HierarchyDropTarget.ParentRow -> children.size
                is HierarchyDropTarget.ChildInsertion -> target.beforeChildRowId?.let { childId ->
                    if (childId == draggedRowId) return null
                    children.indexOfFirst { it.id == childId }.takeIf { it >= 0 } ?: return null
                } ?: children.size
                else -> error("Unreachable drop target")
            }
            val lower = children.getOrNull(insertAt - 1)?.orderKey
            val upper = children.getOrNull(insertAt)?.orderKey
            children.add(insertAt, sourceItem)
            result[destinationIndex] = result[destinationIndex].copy(children = children)
            placement(result, parent = destination.parent.itemId, lower = lower, upper = upper)
        }
    }
}

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
 * The cumulative horizontal displacement a move-handle gesture is allowed to show.
 *
 * Feedback must never advertise an action the row cannot perform: the first effective top-level row
 * has no group above it, and a row that is not an effective child cannot move to top level. The
 * returned value is the *cumulative* permitted displacement rather than a sum of filtered deltas,
 * so reversing an eligible gesture retracts the feedback towards the origin even though the
 * opposite direction is ineligible.
 */
internal fun permittedDepthDisplacement(
    totalX: Float,
    canMakeSubItem: Boolean,
    canMoveToTopLevel: Boolean,
): Float = when {
    totalX > 0f && canMakeSubItem -> totalX
    totalX < 0f && canMoveToTopLevel -> totalX
    else -> 0f
}

/** True when a permitted cumulative displacement is far enough to commit its depth change. */
internal fun isDepthCommitReached(permittedDepthX: Float, commitDistancePx: Float): Boolean =
    kotlin.math.abs(permittedDepthX) >= commitDistancePx
