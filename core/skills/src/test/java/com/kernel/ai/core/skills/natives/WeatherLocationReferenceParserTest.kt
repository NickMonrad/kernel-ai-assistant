package com.kernel.ai.core.skills.natives

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WeatherLocationReferenceParserTest {

    @Test
    fun `extracts country from capital query`() {
        assertEquals("New Zealand", WeatherLocationReferenceParser.extractCountryFromCapitalQuery("the capital of New Zealand"))
        assertEquals("France", WeatherLocationReferenceParser.extractCountryFromCapitalQuery("capital city of France"))
    }

    @Test
    fun `normalizes country aliases`() {
        assertEquals("New Zealand", WeatherLocationReferenceParser.normalizeCountryName("nz"))
        assertEquals("United States", WeatherLocationReferenceParser.normalizeCountryName("USA"))
    }

    @Test
    fun `returns null for non-capital query`() {
        assertNull(WeatherLocationReferenceParser.extractCountryFromCapitalQuery("Wellington"))
    }
}
