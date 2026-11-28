package com.kernel.ai.core.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** #2041: the Sherpa voices must not speak a Wikipedia reply's "Read more" URL or mangle its title. */
class WikipediaReplySpeechTest {
    private val reply = "Kiwi are flightless birds native to New Zealand.\n\n" +
        "Read more: https://en.wikipedia.org/wiki/Kiwi_(bird)"

    @Test
    fun `sherpa preparation drops the read-more url and keeps the body`() {
        val spoken = SherpaSpeechText.prepare(reply)

        assertTrue(spoken.startsWith("Kiwi are flightless birds"), spoken)
        assertFalse(spoken.contains("http"), spoken)
        assertFalse(spoken.contains("Read more"), spoken)
        assertFalse(spoken.contains("wikipedia"), spoken)
    }

    @Test
    fun `sherpa preparation drops a title's underscore and keeps the title words`() {
        val spoken = SherpaSpeechText.prepare("Kiwi_(bird) is a bird.")

        assertFalse(spoken.contains("_"), spoken)
        assertTrue(spoken.startsWith("Kiwi"), spoken)
        assertTrue(spoken.contains("bird"), spoken)
    }

    /** #2192: the screen keeps "(14 mi)"; every English voice speaks the kilometres only. */
    @Test
    fun ruapehu_bracketed_conversion_is_spoken_in_kilometres_only() {
        val reply = "Mount Ruapehu is 23 km (14 mi) northeast of Ohakune."

        assertEquals("Mount Ruapehu is 23 km northeast of Ohakune.", SpeechCardText.speakable(reply))
        assertEquals(
            "Mount Ruapehu is twenty three kilometres northeast of Ohakune.",
            EnglishSpeechTextNormaliser.normalize(SpeechCardText.speakable(reply)),
        )
        assertEquals(
            "Mount Ruapehu is twenty three kilometres northeast of Ohakune.",
            InflectMicroTextFrontend.normalize(SpeechCardText.speakable(reply)),
        )
    }
}
