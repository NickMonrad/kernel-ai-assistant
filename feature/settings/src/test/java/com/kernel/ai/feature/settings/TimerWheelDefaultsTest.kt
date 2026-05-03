package com.kernel.ai.feature.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TimerWheelDefaultsTest {
    @Test
    fun centeredIndexUsesRequestedValueAsFirstVisibleItem() {
        assertEquals(5_000, timerWheelCenteredIndexForValue(0..99, 0))
        assertEquals(5_005, timerWheelCenteredIndexForValue(0..99, 5))
        assertEquals(3_000, timerWheelCenteredIndexForValue(0..59, 0))
        assertEquals(3_059, timerWheelCenteredIndexForValue(0..59, 59))
    }

    @Test
    fun centeredIndexRejectsValuesOutsideRange() {
        assertThrows(IllegalArgumentException::class.java) {
            timerWheelCenteredIndexForValue(0..59, 60)
        }
    }
}
