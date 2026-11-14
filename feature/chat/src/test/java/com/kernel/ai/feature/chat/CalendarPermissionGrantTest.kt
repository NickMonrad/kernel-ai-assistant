package com.kernel.ai.feature.chat

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CalendarPermissionGrantTest {

    @Test
    fun `cancelled request with an empty result map is not a grant`() {
        assertFalse(calendarPermissionsGranted(emptyMap()))
    }

    @Test
    fun `all requested permissions granted is a grant`() {
        assertTrue(calendarPermissionsGranted(mapOf("READ_CALENDAR" to true, "WRITE_CALENDAR" to true)))
    }

    @Test
    fun `any denied permission is not a grant`() {
        assertFalse(calendarPermissionsGranted(mapOf("READ_CALENDAR" to true, "WRITE_CALENDAR" to false)))
    }
}
