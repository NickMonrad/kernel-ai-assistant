package com.kernel.ai.feature.widget

import kotlin.math.floor

/** Maximum number of active item rows the widget renders before an overflow hint. */
internal const val MAX_WIDGET_ITEMS = 8

/** Item rows and overflow hint that fit in the widget's allocated height. */
internal data class ActiveItemLayout(
    val visibleCount: Int,
    val overflowCount: Int,
)

/**
 * Calculate the active-item rows that fit in an exact-height Glance widget.
 *
 * The fixed allowance covers the widget's title, spacer, and vertical padding. One row is
 * reserved for the overflow hint whenever there is more content and enough room to show it.
 */
internal fun calculateActiveItemLayout(
    widgetHeightDp: Float,
    activeItemCount: Int,
): ActiveItemLayout {
    val heightDp = widgetHeightDp.coerceAtLeast(0f)
    val itemCount = activeItemCount.coerceAtLeast(0)
    val rowCapacity = floor((heightDp - 58f) / 24f).toInt().coerceAtLeast(0)
    val visibleCapacity = rowCapacity.coerceAtMost(MAX_WIDGET_ITEMS)
    val visibleCount = when {
        itemCount == 0 || visibleCapacity == 0 -> 0
        itemCount <= visibleCapacity -> itemCount
        rowCapacity >= 2 -> (visibleCapacity - 1).coerceAtLeast(1)
        else -> visibleCapacity
    }
    val overflowCount = if (rowCapacity >= 2) {
        (itemCount - visibleCount).coerceAtLeast(0)
    } else {
        0
    }
    return ActiveItemLayout(visibleCount, overflowCount)
}
