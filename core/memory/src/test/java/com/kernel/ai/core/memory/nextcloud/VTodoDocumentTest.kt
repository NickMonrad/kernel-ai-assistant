package com.kernel.ai.core.memory.nextcloud

import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VTodoDocumentTest {
    @Test
    fun `known fields change without dropping unknown properties or unrelated relationships`() {
        val original = """
            BEGIN:VCALENDAR
            VERSION:2.0
            X-WR-CALNAME:Tasks
            BEGIN:VTODO
            UID:remote-1
            SUMMARY:Old\, text
            STATUS:NEEDS-ACTION
            RELATED-TO;RELTYPE=CHILD:other-task
            X-NEXTCLOUD-UNKNOWN:keep-me
            END:VTODO
            END:VCALENDAR
        """.trimIndent()

        val document = VTodoDocument.parse(original)
        document.replaceSingle("SUMMARY", "New; text", escapeText = true)
        document.replaceParent("parent-task")
        document.replaceSingle("DUE", formatUtcMillis(0L))
        val rendered = document.render()

        assertTrue(rendered.contains("X-NEXTCLOUD-UNKNOWN:keep-me"))
        assertTrue(rendered.contains("RELATED-TO;RELTYPE=CHILD:other-task"))
        assertTrue(rendered.contains("RELATED-TO;RELTYPE=PARENT:parent-task"))
        assertTrue(rendered.contains("SUMMARY:New\\; text"))
        assertEquals("New; text", VTodoDocument.parse(rendered).decoded("SUMMARY"))
        assertEquals(0L, parseUtcMillis(VTodoDocument.parse(rendered).first("DUE")?.value))
    }

    @Test
    fun `replacing due keeps compatible date-only semantics and removes TZID`() {
        val document = VTodoDocument.parse(
            """
                BEGIN:VCALENDAR
                BEGIN:VTODO
                UID:all-day
                DTSTART;VALUE=DATE:20260930
                DUE;VALUE=DATE;TZID=Pacific/Auckland:20260930
                X-NEXTCLOUD-UNKNOWN:keep-me
                END:VTODO
                END:VCALENDAR
            """.trimIndent(),
        )

        document.replaceDueAt(parseUtcMillis("20261001")!!)
        val rendered = document.render()

        assertTrue(rendered.contains("DTSTART;VALUE=DATE:20260930"))
        assertTrue(rendered.contains("DUE;VALUE=DATE:20261001"))
        assertFalse(rendered.contains("TZID="))
        assertFalse(rendered.contains("DUE:20261001T000000Z"))
    }

    @Test
    fun `replacing due uses the local calendar date for a non-UTC local midnight`() {
        val zone = ZoneId.of("Australia/Brisbane")
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(zone))
            val localMidnight = LocalDate.of(2026, 10, 1)
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli()
            val document = VTodoDocument.parse(
                """
                    BEGIN:VCALENDAR
                    BEGIN:VTODO
                    UID:all-day
                    DTSTART;VALUE=DATE:20260930
                    DUE;VALUE=DATE:20260930
                    END:VTODO
                    END:VCALENDAR
                """.trimIndent(),
            )

            document.replaceDueAt(localMidnight)

            assertTrue(document.render().contains("DUE;VALUE=DATE:20261001"))
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test
    fun `folded lines and escaped text round trip`() {
        val document = VTodoDocument.parse(
            "BEGIN:VCALENDAR\r\nBEGIN:VTODO\r\nUID:1\r\nSUMMARY:hello\\, world\r\nDESCRIPTION:first\r\n second\r\nEND:VTODO\r\nEND:VCALENDAR\r\n",
        )
        assertEquals("hello, world", document.decoded("SUMMARY"))
        assertTrue(document.render().contains("DESCRIPTION:firstsecond"))
    }
    @Test
    fun `description preserves multiline text and clearing leaves unrelated properties`() {
        val description = "line one\nhttps://example.com/a?x=full\nline three"
        val document = VTodoDocument.new(
            uid = "item-1",
            summary = "Item",
            checked = false,
            dueAt = null,
            parentUid = null,
            orderKey = "0",
            description = description,
        )
        document.replaceSingle("X-NEXTCLOUD-UNKNOWN", "keep")
        val rendered = document.render()
        assertEquals(description, VTodoDocument.parse(rendered).decoded("DESCRIPTION"))
        assertTrue(rendered.contains("X-NEXTCLOUD-UNKNOWN:keep"))

        document.remove("DESCRIPTION")
        assertEquals(null, VTodoDocument.parse(document.render()).decoded("DESCRIPTION"))
        assertTrue(document.render().contains("X-NEXTCLOUD-UNKNOWN:keep"))
    }
}
