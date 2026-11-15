package com.kernel.ai.alarm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class BootReminderRestorationTest {
    @Test
    fun `denied reminder does not prevent later list and important-date scheduling`() {
        val attempted = mutableListOf<String>()
        val scheduled = mutableListOf<String>()

        listOf("list-denied", "list-next").forEach { reminder ->
            scheduleBootReminder {
                attempted += reminder
                if (reminder == "list-denied") throw SecurityException()
                scheduled += reminder
            }
        }
        listOf("date-denied", "date-next").forEach { reminder ->
            scheduleBootReminder {
                attempted += reminder
                if (reminder == "date-denied") throw SecurityException()
                scheduled += reminder
            }
        }

        assertEquals(listOf("list-denied", "list-next", "date-denied", "date-next"), attempted)
        assertEquals(listOf("list-next", "date-next"), scheduled)
    }

    @Test
    fun `boot restoration does not swallow unrelated failures`() {
        assertThrows(IllegalStateException::class.java) {
            scheduleBootReminder { throw IllegalStateException("unexpected scheduler failure") }
        }
    }
}
