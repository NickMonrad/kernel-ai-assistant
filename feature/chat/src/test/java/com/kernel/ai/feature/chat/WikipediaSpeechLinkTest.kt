package com.kernel.ai.feature.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** #2041: chat speech must drop the Wikipedia "Read more" link before LaTeX and Markdown conversion. */
class WikipediaSpeechLinkTest {
    @Test
    fun `chat speech drops the read-more url and keeps the title readable`() {
        assertEquals(
            "Kiwi are flightless birds. Kiwi (bird) is their name.",
            normalizeChatTextForSpeech(
                "Kiwi are flightless birds. Kiwi_(bird) is their name.\n\n" +
                    "Read more: https://en.wikipedia.org/wiki/Kiwi_(bird)",
            ),
        )
    }

    @Test
    fun `chat speech reads a markdown link as its label`() {
        assertEquals(
            "Read the article about kiwi.",
            normalizeChatTextForSpeech("Read the [article about kiwi](https://en.wikipedia.org/wiki/Kiwi_(bird))."),
        )
    }

    @Test
    fun `chat speech keeps lone subscripts as unicode digits`() {
        assertEquals(
            "Carbon dioxide is CO₂, and x₁ is set.",
            normalizeChatTextForSpeech("Carbon dioxide is CO_2, and x_1 is set."),
        )
    }
}
