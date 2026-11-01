package com.kernel.ai.feature.chat

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReportResponseTest {
    @Test
    fun `mailto includes only selected response and editable reason with app and model metadata`() {
        val reply = "This is the reported reply, not prior conversation history."
        val uri = URI(reportResponseMailtoUri(reply, "0.1.0", "Gemma-4 E-4B"))
        val query = uri.rawQuery.split('&').associate { part ->
            val (key, value) = part.split('=', limit = 2)
            key to URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        }

        assertEquals("mailto", uri.scheme)
        assertEquals(SUPPORT_EMAIL, uri.schemeSpecificPart.substringBefore('?'))
        assertEquals("Report inappropriate AI response", query["subject"])
        assertTrue(query.getValue("body").contains("Reason (please describe why this response is inappropriate):"))
        assertTrue(query.getValue("body").contains(reply))
        assertTrue(query.getValue("body").contains("App version: 0.1.0"))
        assertTrue(query.getValue("body").contains("Model: Gemma-4 E-4B"))
        assertTrue(!query.getValue("body").contains("previous user prompt"))
    }
}
