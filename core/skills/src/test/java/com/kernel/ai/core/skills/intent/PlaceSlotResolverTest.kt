package com.kernel.ai.core.skills.intent

import com.kernel.ai.core.skills.QuickIntentRouter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlaceSlotResolverTest {

    @Test
    fun `snaps a one-letter-off spelling in a navigation destination`() {
        assertEquals(mapOf("destination" to "Hamilton"), PlaceSlotResolver.resolve("navigate_to", mapOf("destination" to "Hamiltn")))
        assertEquals(mapOf("destination" to "Napier"), PlaceSlotResolver.resolve("navigate_to", mapOf("destination" to "Napiar")))
    }

    @Test
    fun `snaps a macron-free or misspelt place in a weather location and a world-time location`() {
        assertEquals(mapOf("location" to "Whangārei"), PlaceSlotResolver.resolve("get_weather", mapOf("location" to "Whangarai")))
        assertEquals(mapOf("location" to "Taupō"), PlaceSlotResolver.resolve("get_time", mapOf("location" to "Taupo")))
    }

    @Test
    fun `leaves a value that is already the gazetteer spelling unchanged`() {
        assertEquals(mapOf("destination" to "Tauranga"), PlaceSlotResolver.resolve("navigate_to", mapOf("destination" to "Tauranga")))
    }

    @Test
    fun `never rewrites non-place text in a place slot`() {
        // Short words are never snapped, however close they are to a name.
        assertEquals(mapOf("destination" to "home"), PlaceSlotResolver.resolve("navigate_to", mapOf("destination" to "home")))
        assertEquals(mapOf("destination" to "Nelsn"), PlaceSlotResolver.resolve("navigate_to", mapOf("destination" to "Nelsn")))
        assertEquals(mapOf("destination" to "the beach"), PlaceSlotResolver.resolve("navigate_to", mapOf("destination" to "the beach")))
        assertEquals(mapOf("destination" to "Christchurch hospital"), PlaceSlotResolver.resolve("navigate_to", mapOf("destination" to "Christchurch hospital")))
        assertEquals(mapOf("location" to "Porter"), PlaceSlotResolver.resolve("get_weather", mapOf("location" to "Porter")))
    }

    @Test
    fun `touches only the place slots of place intents`() {
        assertEquals(mapOf("destination" to "Hamiltn"), PlaceSlotResolver.resolve("play_music", mapOf("destination" to "Hamiltn")))
        assertEquals(mapOf("query" to "Hamiltn"), PlaceSlotResolver.resolve("navigate_to", mapOf("query" to "Hamiltn")))
    }

    @Test
    fun `resolves NZ sound-alikes from the owner report without glossary entries`() {
        assertEquals("Whangārei", PlaceSlotResolver.snap("funga day"))
        assertEquals("Paraparaumu", PlaceSlotResolver.snap("pararapr"))
    }

    @Test
    fun `phonetic key applies NZ and Maori sound rules`() {
        assertEquals(PlaceSlotResolver.phoneticKey("Whangārei"), PlaceSlotResolver.phoneticKey("Fangarei"))
        assertEquals(PlaceSlotResolver.phoneticKey("Whangarei"), PlaceSlotResolver.phoneticKey("Fungaday"))
        // Repeated consonants collapse: "rr" counts once.
        assertEquals("prpr", PlaceSlotResolver.phoneticKey("pararapr"))
    }

    @Test
    fun `common non-place words are never snapped to a place`() {
        val nonPlaces = listOf(
            "airport", "hospital", "pharmacy", "supermarket", "dinner", "library", "station",
            "school", "office", "hotel", "the beach", "the office", "my place", "home", "work",
            "petrol station", "post office", "the dentist", "tomorrow", "this afternoon",
            "the lake", "Mum's house", "the gym", "the bank", "a cafe", "the mall",
        )
        for (word in nonPlaces) {
            assertEquals(null, PlaceSlotResolver.snap(word), "'$word' must not snap")
        }
    }

    @Test
    fun `router snaps a misspelt destination in a regex navigation match`() {
        val result = QuickIntentRouter().route("navigate to Hamiltn")
        assertTrue(result is QuickIntentRouter.RouteResult.RegexMatch, "expected a regex match, got $result")
        val intent = (result as QuickIntentRouter.RouteResult.RegexMatch).intent
        assertEquals("navigate_to", intent.intentName)
        assertEquals("Hamilton", intent.params["destination"])
    }
}
