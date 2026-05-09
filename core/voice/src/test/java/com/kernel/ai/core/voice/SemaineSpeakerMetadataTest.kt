package com.kernel.ai.core.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SemaineSpeakerMetadataTest {

    @Test
    fun `only female speakers are exposed`() {
        assertTrue(SemaineSpeakerMetadata.speakers.isNotEmpty())
        assertTrue(
            SemaineSpeakerMetadata.speakers.all { it.description.contains("female") },
            "Expected all exposed Semaine speakers to be female",
        )
    }

    @Test
    fun `Prudence has sid 0 and Poppy has sid 3`() {
        val prudence = SemaineSpeakerMetadata.speakers.firstOrNull { it.displayName == "Prudence" }
        val poppy = SemaineSpeakerMetadata.speakers.firstOrNull { it.displayName == "Poppy" }

        assertEquals(0, prudence?.sid, "Prudence should be sid=0")
        assertEquals(3, poppy?.sid, "Poppy should be sid=3")
    }

    @Test
    fun `male speakers spike and obadiah are not exposed`() {
        val names = SemaineSpeakerMetadata.speakers.map { it.displayName.lowercase() }
        assertTrue("spike" !in names, "Spike (male) must not be exposed")
        assertTrue("obadiah" !in names, "Obadiah (male) must not be exposed")
    }

    @Test
    fun `displayLabel returns label for known sids`() {
        assertEquals("Prudence — female, calm", SemaineSpeakerMetadata.displayLabel(0))
        assertEquals("Poppy — female, upbeat", SemaineSpeakerMetadata.displayLabel(3))
    }

    @Test
    fun `displayLabel falls back to Prudence for unknown sids`() {
        // A sid that is a valid model sid but not exposed (e.g. Spike = 1)
        assertEquals("Prudence — female, calm", SemaineSpeakerMetadata.displayLabel(1))
        // A VCTK-range sid that might bleed through if a user was previously on VCTK
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
