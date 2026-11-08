package com.kernel.ai.feature.widget

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ListsWidgetSizingTest {

    @Test
    fun `compact height reserves room for overflow hint`() {
        assertEquals(
            ActiveItemLayout(visibleCount = 1, overflowCount = 5),
            calculateActiveItemLayout(widgetHeightDp = 110f, activeItemCount = 6),
        )
    }

    @Test
    fun `larger height exposes more active rows`() {
        val compact = calculateActiveItemLayout(widgetHeightDp = 110f, activeItemCount = 6)
        val taller = calculateActiveItemLayout(widgetHeightDp = 160f, activeItemCount = 6)

        assertEquals(1, compact.visibleCount)
        assertEquals(3, taller.visibleCount)
        assertEquals(3, taller.overflowCount)
    }

    @Test
    fun `visible rows never exceed active items or maximum`() {
        assertEquals(
            ActiveItemLayout(visibleCount = 6, overflowCount = 0),
            calculateActiveItemLayout(widgetHeightDp = 204f, activeItemCount = 6),
        )
        assertEquals(
            ActiveItemLayout(visibleCount = MAX_WIDGET_ITEMS - 1, overflowCount = 5),
            calculateActiveItemLayout(widgetHeightDp = 260f, activeItemCount = 12),
        )
    }

    @Test
    fun `empty content produces no rows or overflow`() {
        assertEquals(
            ActiveItemLayout(visibleCount = 0, overflowCount = 0),
            calculateActiveItemLayout(widgetHeightDp = 204f, activeItemCount = 0),
        )
    }
}
