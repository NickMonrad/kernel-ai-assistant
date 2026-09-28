package com.kernel.ai.core.memory.nextcloud

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
    fun `replacing due preserves date-only semantics from DTSTART and existing parameters`() {
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
        assertTrue(rendered.contains("DUE;VALUE=DATE;TZID=Pacific/Auckland:20261001"))
        assertFalse(rendered.contains("DUE:20261001T000000Z"))
    }

    @Test
    fun `folded lines and escaped text round trip`() {
        val document = VTodoDocument.parse(
            "BEGIN:VCALENDAR\r\nBEGIN:VTODO\r\nUID:1\r\nSUMMARY:hello\\, world\r\nDESCRIPTION:first\r\n second\r\nEND:VTODO\r\nEND:VCALENDAR\r\n",
        )
        assertEquals("hello, world", document.decoded("SUMMARY"))
        assertTrue(document.render().contains("DESCRIPTION:firstsecond"))
    }
}
