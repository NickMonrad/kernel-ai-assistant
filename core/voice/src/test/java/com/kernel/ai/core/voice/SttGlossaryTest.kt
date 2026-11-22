package com.kernel.ai.core.voice

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SttGlossaryTest {

    private val glossary = SttGlossary.fromJson(
        File("src/lexicon/assets/${SttGlossary.ASSET_NAME}").readText(),
    )

    @Test
    fun `corrects observed mis-hearings of place names and Maori words`() {
        assertEquals("Tauranga", glossary.apply("todang"))
        assertEquals("Pūkekohe", glossary.apply("pkokoi"))
        assertEquals("Taupō", glossary.apply("tapo"))
        assertEquals("Whakatāne", glossary.apply("wakatani"))
        assertEquals("Te Awamutu", glossary.apply("tawyuto"))
        assertEquals("Paraparaumu Beach", glossary.apply("paraporamuach"))
    }

    @Test
    fun `corrects Jandal words inside a sentence`() {
        assertEquals("Kia ora Jandal", glossary.apply("kyotogendo"))
        assertEquals("Can I wear my Jandals to Raglan", glossary.apply("Can I wear my jandles to Raglan"))
        assertEquals("add Jandal", glossary.apply("add gandal"))
    }

    @Test
    fun `matching ignores case and macrons in the heard text`() {
        assertEquals("Pūkekohe", glossary.apply("PKOKOI"))
        assertEquals("Taupō", glossary.apply("tāpo"))
        assertEquals("Porirua", glossary.apply("pori rua"))
    }

    @Test
    fun `matches any run of whitespace between the words of a wrong form`() {
        assertEquals("Pūkekohe", glossary.apply("puka  koi"))
        assertEquals("Taihape", glossary.apply("tai\thae"))
        assertEquals("Matamata", glossary.apply("mam mata"))
    }

    @Test
    fun `is whole-word so longer words containing a wrong form are untouched`() {
        assertEquals("todangle", glossary.apply("todangle"))
        assertEquals("xtodang", glossary.apply("xtodang"))
        assertEquals("tapos", glossary.apply("tapos"))
    }

    @Test
    fun `leaves English words that share sounds with wrong forms alone`() {
        listOf("gender", "gentle breeze", "gal", "in", "auntie", "aunty", "mum", "mom", "set", "add").forEach { phrase ->
            assertEquals(phrase, glossary.apply(phrase), phrase)
        }
    }

    @Test
    fun `leaves correct forms and ordinary text unchanged`() {
        listOf(
            "Tauranga",
            "Pūkekohe",
            "Pukekohe",
            "Porirua harbour",
            "Waikato River",
            "Hey Jandal",
            "my Jandals",
            "Paraparaumu Beach",
            "Kia ora Jandal",
            "",
        ).forEach { phrase ->
            assertEquals(phrase, glossary.apply(phrase), phrase)
        }
    }

    @Test
    fun `applying the glossary twice changes nothing more`() {
        listOf(
            "todang and pkokoi",
            "kyotogendo, jandles and paraporamuach",
            "wakatani at Raglan",
        ).forEach { sample ->
            val once = glossary.apply(sample)
            assertEquals(once, glossary.apply(once), sample)
        }
    }

    @Test
    fun `an empty glossary never changes text`() {
        val empty = SttGlossary.fromJson("""{"entries":[]}""")
        assertEquals("todang", empty.apply("todang"))
    }

    @Test
    fun `an entry without wrong forms fails loudly instead of silently emptying`() {
        assertThrows(IllegalArgumentException::class.java) {
            SttGlossary.fromJson("""{"entries":[{"correct":"Tauranga","wrong":[]}]}""")
        }
    }
}
