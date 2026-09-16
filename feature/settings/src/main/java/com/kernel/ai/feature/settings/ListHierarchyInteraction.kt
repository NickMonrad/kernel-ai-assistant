package com.kernel.ai.feature.settings

import com.kernel.ai.core.memory.entity.ListItemEntity
import com.kernel.ai.core.memory.lists.EffectiveHierarchyGroup

/** The only placement intents exposed by the Lists drag interaction. */
enum class ItemDropIntent {
    NEST,
    INSERT_BEFORE,
    INSERT_AFTER,
}

internal fun resolveFinalDropIntent(
    current: ItemDropIntent?,
    cached: ItemDropIntent?,
): ItemDropIntent? = current ?: cached


/**
 * Rejects the only invalid local drop: nesting a top-level group or nesting into a child.
 * The caller chooses an insertion intent when the pointer is in an insertion slot.
 */
internal fun resolveItemDropIntent(
    requested: ItemDropIntent,
    sourceHasChildren: Boolean,
    targetIsChild: Boolean,
): ItemDropIntent? = if (
    requested == ItemDropIntent.NEST && (sourceHasChildren || targetIsChild)
) {
    null
} else {
    requested
}

/** Returns the explicit intent represented by the pointer's target row position. */

internal fun itemDropIntentForPosition(
    pointerY: Float,
    targetTop: Float,
    targetBottom: Float,
    sourceHasChildren: Boolean,
    targetIsChild: Boolean,
): ItemDropIntent {
    val edge = (targetBottom - targetTop) * 0.25f
    val requested = when {
        pointerY < targetTop + edge -> ItemDropIntent.INSERT_BEFORE
        pointerY > targetBottom - edge -> ItemDropIntent.INSERT_AFTER
        else -> ItemDropIntent.NEST
    }
    return resolveItemDropIntent(requested, sourceHasChildren, targetIsChild)
        ?: if (pointerY < (targetTop + targetBottom) / 2f) {
            ItemDropIntent.INSERT_BEFORE
        } else {
            ItemDropIntent.INSERT_AFTER
        }
}
/** Applies the optimistic row movement used while the reorderable library is dragging. */
internal fun moveHierarchyRows(
    current: List<ListItemEntity>,
    groups: List<EffectiveHierarchyGroup<ListItemEntity>>,
    draggedId: Long,
    targetId: Long,
    intent: ItemDropIntent,
): List<ListItemEntity> {
    val sourceGroup = groups.firstOrNull { group ->
        group.parent.id == draggedId && group.children.isNotEmpty()
    }
    val targetGroup = groups.firstOrNull { group ->
        group.parent.id == targetId || group.children.any { it.id == targetId }
    }
    val sourceIds = (sourceGroup?.let { listOf(it.parent.id) + it.children.map(ListItemEntity::id) }
        ?: listOf(draggedId)).toSet()
    val sourceRows = current.filter { it.id in sourceIds }
    if (sourceRows.isEmpty() || intent == ItemDropIntent.NEST && sourceGroup != null) return current

    val remaining = current.filterNot { it.id in sourceIds }
    val targetRows = targetGroup?.let { group ->
        val targetIsChild = group.children.any { it.id == targetId }
        val ids = if (sourceGroup != null || !targetIsChild) {
            listOf(group.parent.id) + group.children.map(ListItemEntity::id)
        } else {
            listOf(targetId)
        }.toSet()
        remaining.filter { it.id in ids }
    }.orEmpty()
    val targetAnchorId = if (sourceGroup != null) targetGroup?.parent?.id ?: targetId else targetId
    val targetIndex = remaining.indexOfFirst { it.id == targetAnchorId }
    if (targetIndex < 0) return current

    val insertionIndex = when (intent) {
        ItemDropIntent.NEST -> targetIndex + targetRows.size
        ItemDropIntent.INSERT_BEFORE -> targetIndex
        ItemDropIntent.INSERT_AFTER -> targetIndex + if (targetRows.isEmpty()) 1 else targetRows.size
    }.coerceIn(0, remaining.size)
    return remaining.toMutableList().apply { addAll(insertionIndex, sourceRows) }
}

internal fun visibleSelectableItemIds(
    activeRows: List<ListItemEntity>,
    completedRows: List<ListItemEntity>,
    completedExpanded: Boolean,
): List<Long> = activeRows.map(ListItemEntity::id) +
    if (completedExpanded) completedRows.map(ListItemEntity::id) else emptyList()
