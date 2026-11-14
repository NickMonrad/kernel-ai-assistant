package com.kernel.ai.core.voice

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.File

class PronunciationLexiconTest {

    @AfterEach
    fun clear() {
        PronunciationLexicon.install(emptyList())
    }

    private fun install(vararg entries: Pair<String, String>) {
        PronunciationLexicon.install(entries.map { (w, s) -> PronunciationLexicon.Entry(w, s, verify = false) })
    }

    @Test
    fun `parses word, say and verify and defaults verify to false`() {
        val entries = PronunciationLexicon.parse(
            """{"entries":[{"word":"Petone","say":"Pih-toh-nee"},{"word":"Taupō","say":"Tow-paw","verify":true}]}""",
        )
        assertEquals(
            listOf(
                PronunciationLexicon.Entry("Petone", "Pih-toh-nee", verify = false),
                PronunciationLexicon.Entry("Taupō", "Tow-paw", verify = true),
            ),
            entries,
        )
    }

    @Test
    fun `entry without say is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PronunciationLexicon.parse("""{"entries":[{"word":"Petone"}]}""")
        }
    }

    @Test
    fun `shipped asset parses and contains the owner-verified Petone entry`() {
        // Unit tests run with the module directory as the working directory.
        val json = File("src/main/assets/${PronunciationLexicon.ASSET_NAME}").readText()
        val petone = PronunciationLexicon.parse(json).single { it.word == "Petone" }
        assertEquals("Pih-toh-nee", petone.say)
        assertFalse(petone.verify)
    }

    @Test
    fun `replaces the whole word and keeps surrounding punctuation`() {
        install("Petone" to "Pih-toh-nee")
        assertEquals("Pih-toh-nee, Lower Hutt.", PronunciationLexicon.apply("Petone, Lower Hutt."))
        assertEquals("Pih-toh-nee's station?", PronunciationLexicon.apply("Petone's station?"))
    }

    @Test
    fun `matching is case insensitive`() {
        install("Petone" to "Pih-toh-nee")
        assertEquals("Pih-toh-nee", PronunciationLexicon.apply("petone"))
        assertEquals("Pih-toh-nee", PronunciationLexicon.apply("PETONE"))
    }

    @Test
    fun `does not match inside a longer word, including after a macron`() {
        install("Petone" to "Pih-toh-nee", "Ōtaki" to "Oh-tah-kee")
        assertEquals("Petonei", PronunciationLexicon.apply("Petonei"))
        assertEquals("xŌtaki", PronunciationLexicon.apply("xŌtaki"))
        assertEquals("Oh-tah-kee", PronunciationLexicon.apply("Ōtaki"))
    }

    @Test
    fun `empty lexicon leaves text unchanged`() {
        PronunciationLexicon.install(emptyList())
        assertEquals("Petone", PronunciationLexicon.apply("Petone"))
    }

    @Test
    fun `Sherpa speech preparation applies the lexicon before number handling`() {
        install("Petone" to "Pih-toh-nee")
        assertEquals(
            "Pih-toh-nee is twelve minutes away.",
            SherpaSpeechText.prepare("Petone is 12 minutes away."),
        )
    }
}
