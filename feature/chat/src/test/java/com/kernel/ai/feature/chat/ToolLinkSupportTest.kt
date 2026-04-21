package com.kernel.ai.feature.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ToolLinkSupportTest {

    @Test
    fun `extractUrls returns distinct raw urls`() {
        assertEquals(
            listOf("https://example.com/foo", "https://wikipedia.org/wiki/Mother's_Day"),
            extractUrls(
                """
                See https://example.com/foo and https://wikipedia.org/wiki/Mother's_Day
                plus https://example.com/foo again.
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `collectAdditionalUrls excludes urls already visible in assistant text`() {
        assertEquals(
            listOf("https://wikipedia.org/wiki/Mother's_Day"),
            collectAdditionalUrls(
                visibleText = "I found a summary for you.",
                """Result: Summary only. Source: https://wikipedia.org/wiki/Mother's_Day""",
                """{"url":"https://wikipedia.org/wiki/Mother's_Day"}""",
            ),
        )
    }

    @Test
    fun `collectAdditionalUrls removes urls already present in visible text`() {
        assertEquals(
            emptyList<String>(),
            collectAdditionalUrls(
                visibleText = "Open https://example.com/article for the full article.",
                """Result: https://example.com/article""",
            ),
        )
    }
}
