package com.kernel.ai.core.voice

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
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
            """{"entries":[{"word":"Petone","say":"Pih-toe-nee"},{"word":"Taupō","say":"Toe-paw","verify":true}]}""",
        )
        assertEquals(
            listOf(
                PronunciationLexicon.Entry("Petone", "Pih-toe-nee", verify = false),
                PronunciationLexicon.Entry("Taupō", "Toe-paw", verify = true),
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
    fun `shipped asset parses and contains the owner-confirmed Petone entry`() {
        // Unit tests run with the module directory as the working directory.
        val json = File("src/lexicon/assets/${PronunciationLexicon.ASSET_NAME}").readText()
        val petone = PronunciationLexicon.parse(json).single { it.word == "Petone" }
        assertEquals("Pih-toe-nee", petone.say)
        assertEquals(false, petone.verify)
    }

    @Test
    fun `replaces the whole word and keeps surrounding punctuation`() {
        install("Petone" to "Pih-toe-nee")
        assertEquals("Pih-toe-nee, Lower Hutt.", PronunciationLexicon.apply("Petone, Lower Hutt."))
        assertEquals("Pih-toe-nee's station?", PronunciationLexicon.apply("Petone's station?"))
    }

    @Test
    fun `matching is case insensitive`() {
        install("Petone" to "Pih-toe-nee")
        assertEquals("Pih-toe-nee", PronunciationLexicon.apply("petone"))
        assertEquals("Pih-toe-nee", PronunciationLexicon.apply("PETONE"))
    }

    @Test
    fun `macrons fold both ways and NFD input matches`() {
        install("Taupō" to "Toe-paw")
        assertEquals("Toe-paw", PronunciationLexicon.apply("Taupō"))
        assertEquals("Toe-paw", PronunciationLexicon.apply("Taupo"))
        assertEquals("Toe-paw", PronunciationLexicon.apply("TAUPO"))
        assertEquals("Toe-paw", PronunciationLexicon.apply("Tau\u0304po"))
        assertEquals("Toe-paw", PronunciationLexicon.apply("Tau\u0304po".uppercase()))
    }

    @Test
    fun `macron-less entry matches macron input`() {
        install("Taupo" to "Toe-paw")
        assertEquals("Toe-paw", PronunciationLexicon.apply("Taupō"))
    }

    @Test
    fun `NFD output keeps surrounding text in NFC`() {
        install("Taupo" to "Toe-paw")
        assertEquals("to Toe-paw.", PronunciationLexicon.apply("to Tau\u0304po."))
    }

    @Test
    fun `does not match inside a longer word, including after a macron`() {
        install("Petone" to "Pih-toe-nee", "Ōtaki" to "Oh-tah-kee")
        assertEquals("Petonei", PronunciationLexicon.apply("Petonei"))
        assertEquals("xŌtaki", PronunciationLexicon.apply("xŌtaki"))
        assertEquals("Oh-tah-kee", PronunciationLexicon.apply("Ōtaki"))
    }

    @Test
    fun `multi-word entry matches as one unit`() {
        install("Te Awamutu" to "Tay ah-wah-moo-too")
        assertEquals("Tay ah-wah-moo-too is north.", PronunciationLexicon.apply("Te Awamutu is north."))
        assertEquals("Tay ah-wah-moo-too", PronunciationLexicon.apply("te awamutu"))
    }

    @Test
    fun `multi-word entry matches across any whitespace between its words`() {
        install("Te Awamutu" to "Tay ah-wah-moo-too", "Lower Hutt" to "Lower Hut-uh")
        assertEquals("Tay ah-wah-moo-too is north.", PronunciationLexicon.apply("Te  Awamutu is north."))
        assertEquals("Off at Lower Hut-uh.", PronunciationLexicon.apply("Off at Lower\nHutt."))
        assertEquals("Lower Hut-uh", PronunciationLexicon.apply("lower\t\u00A0hutt"))
    }

    @Test
    fun `longer entry wins over a shorter entry that starts the same way`() {
        install("Hutt" to "Hut", "Lower Hutt" to "Lower Hut-uh")
        assertEquals("Lower Hut-uh and Hut", PronunciationLexicon.apply("Lower Hutt and Hutt"))
    }

    @Test
    fun `replacements are not re-scanned, so one entry cannot trigger another`() {
        install("Petone" to "Taupō", "Taupō" to "Toe-paw")
        assertEquals("Taupō", PronunciationLexicon.apply("Petone"))
    }

    @Test
    fun `empty lexicon leaves text unchanged`() {
        PronunciationLexicon.install(emptyList())
        assertEquals("Petone", PronunciationLexicon.apply("Petone"))
    }

    @Test
    fun `shipped asset matches both Whanganui and Wanganui spellings`() {
        val json = File("src/lexicon/assets/${PronunciationLexicon.ASSET_NAME}").readText()
        PronunciationLexicon.install(PronunciationLexicon.parse(json))
        assertEquals("Wonga-noo-ee", PronunciationLexicon.apply("Whanganui"))
        assertEquals("Wonga-noo-ee", PronunciationLexicon.apply("Wanganui"))
    }

    @Test
    fun `shipped asset respells Jandal so Cori says JAN-dl wherever it is spoken`() {
        val json = File("src/lexicon/assets/${PronunciationLexicon.ASSET_NAME}").readText()
        PronunciationLexicon.install(PronunciationLexicon.parse(json))
        assertEquals("Hey Jandle, add milk.", SherpaSpeechText.prepare("Hey Jandal, add milk."))
        assertEquals("Jandle's reply: two Jandles.", SherpaSpeechText.prepare("jandal's reply: two jandals."))
    }

    @Test
    fun `shipped asset respells Kia ora as Kee-ora in any case and inside sentences`() {
        val json = File("src/lexicon/assets/${PronunciationLexicon.ASSET_NAME}").readText()
        PronunciationLexicon.install(PronunciationLexicon.parse(json))
        assertEquals("Kee-ora, I'm Jandle.", SherpaSpeechText.prepare("Kia ora, I'm Jandal."))
        assertEquals("Well, Kee-ora to you!", SherpaSpeechText.prepare("Well, kia ora to you!"))
        assertEquals("Kee-ora.", SherpaSpeechText.prepare("KIA ORA"))
        // Speech prep turns a line break into a sentence break first, so the lexicon-level test covers \n.
        assertEquals("Kee-ora, mate.", SherpaSpeechText.prepare("Kia  ora, mate."))
        assertEquals("Kee-ora!", SherpaSpeechText.prepare("Kiaora!"))
    }

    @Test
    fun `Kia ora inside a longer word or phrase is left alone`() {
        val json = File("src/lexicon/assets/${PronunciationLexicon.ASSET_NAME}").readText()
        PronunciationLexicon.install(PronunciationLexicon.parse(json))
        assertEquals("Kia orana.", SherpaSpeechText.prepare("Kia orana."))
        assertEquals("Akia ora.", SherpaSpeechText.prepare("Akia ora."))
    }

    @Test
    fun `shipped asset respells mahi as ma-hee in any case, with a macron, and inside a sentence`() {
        val json = File("src/lexicon/assets/${PronunciationLexicon.ASSET_NAME}").readText()
        PronunciationLexicon.install(PronunciationLexicon.parse(json))
        assertEquals("ma-hee", PronunciationLexicon.apply("mahi"))
        assertEquals("ma-hee", PronunciationLexicon.apply("Mahi"))
        assertEquals("ma-hee", PronunciationLexicon.apply("MAHI"))
        assertEquals("ma-hee", PronunciationLexicon.apply("Māhi"))
        assertEquals("Ka pai tō ma-hee.", SherpaSpeechText.prepare("Ka pai tō mahi."))
    }

    @Test
    fun `mahi is respelled only as a whole word`() {
        val json = File("src/lexicon/assets/${PronunciationLexicon.ASSET_NAME}").readText()
        PronunciationLexicon.install(PronunciationLexicon.parse(json))
        assertEquals("mahimahi", PronunciationLexicon.apply("mahimahi"))
        assertEquals("omahi", PronunciationLexicon.apply("omahi"))
        assertEquals("mahis", PronunciationLexicon.apply("mahis"))
        assertEquals("mahi2", PronunciationLexicon.apply("mahi2"))
    }

    @Test
    fun `Sherpa speech preparation applies the lexicon before number handling`() {
        install("Petone" to "Pih-toe-nee")
        assertEquals(
            "Pih-toe-nee is twelve minutes away.",
            SherpaSpeechText.prepare("Petone is 12 minutes away."),
        )
    }
}
