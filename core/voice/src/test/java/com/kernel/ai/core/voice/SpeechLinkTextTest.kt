package com.kernel.ai.core.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpeechLinkTextTest {
    @Test
    fun `wikipedia read-more line is dropped with its url and parenthesised title`() {
        assertEquals(
            "Kiwi are flightless birds.",
            SpeechLinkText.strip("Kiwi are flightless birds.\n\nRead more: https://en.wikipedia.org/wiki/Kiwi_(bird)"),
        )
    }

    @Test
    fun `source line is dropped and the paragraphs around it stay apart`() {
        assertEquals("A.\n\nB.", SpeechLinkText.strip("A.\nSource: https://example.org/a\nB."))
    }

    @Test
    fun `read more line with a markdown link is dropped`() {
        assertEquals(
            "Kiwi are birds.",
            SpeechLinkText.strip("Kiwi are birds.\nRead more: [Wikipedia](https://en.wikipedia.org/wiki/Kiwi_(bird))"),
        )
    }

    @Test
    fun `bare url inside a sentence is removed`() {
        assertEquals("See for details.", SpeechLinkText.strip("See https://example.com/page-2 for details."))
    }

    @Test
    fun `url followed by sentence punctuation keeps the punctuation`() {
        assertEquals("Go there.", SpeechLinkText.strip("Go there https://example.com/a."))
    }

    @Test
    fun `markdown link keeps its label`() {
        assertEquals("Kiwi is a bird.", SpeechLinkText.strip("Kiwi is a [bird](https://example.com/bird)."))
    }

    @Test
    fun `title underscore before a parenthesis is a space`() {
        assertEquals("Kiwi (bird) is a bird.", SpeechLinkText.strip("Kiwi_(bird) is a bird."))
    }

    @Test
    fun `text without links is unchanged`() {
        val text = "It costs \$3.50 at 8:31 am, Room 101, see www-data and 2.5 km."
        assertEquals("It costs \$3.50 at 8:31 am, Room 101, see www-data and 2.5 km.", SpeechLinkText.strip(text))
    }

    @Test
    fun `stripping is idempotent`() {
        val once = SpeechLinkText.strip("Kiwi_(bird) is here.\n\nRead more: https://en.wikipedia.org/wiki/Kiwi_(bird)")
        assertEquals(once, SpeechLinkText.strip(once))
    }
}
