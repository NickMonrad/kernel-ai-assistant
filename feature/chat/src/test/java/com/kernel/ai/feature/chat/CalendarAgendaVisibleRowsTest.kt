package com.kernel.ai.feature.chat

import com.kernel.ai.core.skills.ToolPresentation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CalendarAgendaVisibleRowsTest {
    private fun row(title: String) = ToolPresentation.CalendarAgendaRow("9:00 \u2013 9:30 AM", title)

    private val week = ToolPresentation.CalendarAgenda(
        title = "This week",
        days = listOf(
            ToolPresentation.CalendarAgendaDay("Monday 17 November", listOf(row("A"), row("B"))),
            ToolPresentation.CalendarAgendaDay("Tuesday 18 November", listOf(row("C"), row("D"))),
            ToolPresentation.CalendarAgendaDay("Wednesday 19 November", listOf(row("E"))),
        ),
    )

    @Test
    fun `collapsed card shows the first rows across days and counts the rest for Show more`() {
        val visible = calendarAgendaVisibleRows(week, collapsedCount = 3, expanded = false)

        assertEquals(listOf("Monday 17 November", "Tuesday 18 November"), visible.days.map { it.label })
        assertEquals(listOf("A", "B", "C"), visible.days.flatMap { it.rows }.map { it.title })
        assertEquals(2, visible.hiddenCount)
    }

    @Test
    fun `expanded card shows every row and day and keeps the hidden count for Show less`() {
        val visible = calendarAgendaVisibleRows(week, collapsedCount = 3, expanded = true)

        assertEquals(week.days, visible.days)
        assertEquals(2, visible.hiddenCount)
    }

    @Test
    fun `a card that fits has nothing hidden`() {
        val visible = calendarAgendaVisibleRows(week, collapsedCount = 5, expanded = false)

        assertEquals(week.days, visible.days)
        assertEquals(0, visible.hiddenCount)
    }
}
