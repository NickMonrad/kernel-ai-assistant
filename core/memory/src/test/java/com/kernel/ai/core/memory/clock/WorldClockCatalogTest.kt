package com.kernel.ai.core.memory.clock

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorldClockCatalogTest {
    @Test
    fun `resolve handles city example from issue`() {
        val resolution = WorldClockCatalog.resolve("London")
        assertTrue(resolution is WorldClockResolution.Resolved)
        assertEquals("Europe/London", (resolution as WorldClockResolution.Resolved).candidate.zoneId)
    }

    @Test
    fun `resolve handles unambiguous timezone abbreviation`() {
        val resolution = WorldClockCatalog.resolve("JST")
        assertTrue(resolution is WorldClockResolution.Resolved)
        assertEquals("Asia/Tokyo", (resolution as WorldClockResolution.Resolved).candidate.zoneId)
    }

    @Test
    fun `resolve handles ottawa city alias`() {
        val resolution = WorldClockCatalog.resolve("Ottawa")
        assertTrue(resolution is WorldClockResolution.Resolved)
        assertEquals("America/Toronto", (resolution as WorldClockResolution.Resolved).candidate.zoneId)
    }

    @Test
    fun `search returns popular clocks when blank`() {
        val results = WorldClockCatalog.search("")
        assertTrue(results.isNotEmpty())
        assertTrue(results.any { it.zoneId == "Pacific/Auckland" })
    }
}
