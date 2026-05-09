package com.kernel.ai.core.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SemaineSpeakerMetadataTest {

    @Test
    fun `all 4 speakers are exposed`() {
        assertEquals(4, SemaineSpeakerMetadata.speakers.size, "Expected exactly 4 Semaine speakers")
    }

    @Test
    fun `Prudence sid 0 Spike sid 1 Obadiah sid 2 Poppy sid 3`() {
        val prudence = SemaineSpeakerMetadata.speakers.firstOrNull { it.displayName == "Prudence" }
        val spike    = SemaineSpeakerMetadata.speakers.firstOrNull { it.displayName == "Spike" }
        val obadiah  = SemaineSpeakerMetadata.speakers.firstOrNull { it.displayName == "Obadiah" }
        val poppy    = SemaineSpeakerMetadata.speakers.firstOrNull { it.displayName == "Poppy" }

        assertEquals(0, prudence?.sid, "Prudence should be sid=0")
        assertEquals(1, spike?.sid,    "Spike should be sid=1")
        assertEquals(2, obadiah?.sid,  "Obadiah should be sid=2")
        assertEquals(3, poppy?.sid,    "Poppy should be sid=3")
    }

    @Test
    fun `Spike is male neutral and Obadiah is male melancholic`() {
        val spike   = SemaineSpeakerMetadata.speakers.first { it.displayName == "Spike" }
        val obadiah = SemaineSpeakerMetadata.speakers.first { it.displayName == "Obadiah" }

        assertEquals("male, neutral",     spike.description)
        assertEquals("male, melancholic", obadiah.description)
    }

    @Test
    fun `displayLabel returns label for all known sids`() {
        assertEquals("Prudence — female, calm",     SemaineSpeakerMetadata.displayLabel(0))
        assertEquals("Spike — male, neutral",       SemaineSpeakerMetadata.displayLabel(1))
        assertEquals("Obadiah — male, melancholic", SemaineSpeakerMetadata.displayLabel(2))
        assertEquals("Poppy — female, upbeat",      SemaineSpeakerMetadata.displayLabel(3))
    }

    @Test
    fun `displayLabel falls back to Prudence for unknown sids`() {
        // A sid outside the 0-3 range that might bleed through if a user was previously on VCTK
        assertEquals("Prudence — female, calm", SemaineSpeakerMetadata.displayLabel(50))
    }

    @Test
    fun `all speaker sids are within the Semaine model speakerCount`() {
        val modelSpeakerCount = SherpaPiperVoice.SemaineMedium.speakerCount
        assertTrue(
            SemaineSpeakerMetadata.speakers.all { it.sid in 0 until modelSpeakerCount },
            "All exposed sids must be within 0 until ${modelSpeakerCount}",
        )
    }
}
