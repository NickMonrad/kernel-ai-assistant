package com.kernel.ai.alarm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AlarmDeliveryTest {
    @Test
    fun `timers use the exact wakeup API when exact alarms are allowed (#1990)`() {
        assertEquals(AlarmDelivery.EXACT_WAKEUP, alarmDeliveryFor(canScheduleExactAlarms = true))
    }

    @Test
    fun `timers fall back to inexact delivery when exact alarms are denied (#1990)`() {
        assertEquals(AlarmDelivery.INEXACT_WAKEUP, alarmDeliveryFor(canScheduleExactAlarms = false))
    }
}
