package com.kernel.ai.feature.settings

import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ListItemTextTest {
    @Test
    fun `finds http and https URLs while leaving surrounding punctuation outside the link`() {
        val text = "Read https://example.com/tasks, then http://localhost:8080/today."

        assertEquals(
            listOf(
                ListItemUrlSpan(
                    start = text.indexOf("https://example.com/tasks"),
                    endExclusive = text.indexOf("https://example.com/tasks") + "https://example.com/tasks".length,
                    url = "https://example.com/tasks",
                ),
                ListItemUrlSpan(
                    start = text.indexOf("http://localhost:8080/today"),
                    endExclusive = text.indexOf("http://localhost:8080/today") + "http://localhost:8080/today".length,
                    url = "http://localhost:8080/today",
                ),
            ),
            findListItemUrls(text),
        )
    }

    @Test
    fun `annotated rendering keeps full URL annotation and text after a long URL`() {
        val url = "https://example.com/a/very/long/path/with/a/query?filter=upcoming&owner=jandal"
        val source = "Open $url when ready, then continue with the next item."

        val rendered = buildListItemAnnotatedText(source, linkColor = Color.Blue)
        val annotation = rendered
            .getStringAnnotations("LIST_ITEM_URL", 0, rendered.length)
            .single()

        assertEquals(url, annotation.item)
        assertTrue(rendered.text.startsWith("Open https://example.com/"))
        assertTrue(rendered.text.contains(" when ready, then continue with the next item."))
        assertTrue(rendered.text.length < source.length)
    }

    @Test
    fun `short URLs stay unchanged and non-http schemes are plain text`() {
        val source = "https://example.com and ftp://example.com"

        val rendered = buildListItemAnnotatedText(source, linkColor = Color.Blue)

        assertEquals(source, rendered.text)
        assertEquals(
            listOf("https://example.com"),
            rendered.getStringAnnotations("LIST_ITEM_URL", 0, rendered.length).map { it.item },
        )
    }

    @Test
    fun `URL truncation retains a bounded recognizable prefix and suffix`() {
        val url = "https://example.com/a/very/long/path/that/should/not/fill/the/row"

        val display = truncateListItemUrl(url, maxLength = 24)

        assertEquals(24, display.length)
        assertTrue(display.startsWith("https://ex"))
        assertTrue(display.endsWith("/row"))
        assertTrue(display.contains('…'))
    }
}
