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
    fun `extracts country from embedded capital reference`() {
        assertEquals(
            "New Zealand",
            WeatherLocationReferenceParser.extractCountryFromCapitalQuery("in the capital of New Zealand"),
        )
        assertEquals(
            "New Zealand",
            WeatherLocationReferenceParser.extractCountryFromCapitalQuery("how's the weather in the capital of New Zealand"),
        )
    }

    @Test
    fun `normalizes country aliases`() {
        assertEquals("New Zealand", WeatherLocationReferenceParser.normalizeCountryName("nz"))
        assertEquals("United States", WeatherLocationReferenceParser.normalizeCountryName("USA"))
    }

    @Test
    fun `returns known capitals for normalized countries`() {
        assertEquals("Wellington", WeatherLocationReferenceParser.knownCapitalForCountry("nz"))
        assertEquals("London", WeatherLocationReferenceParser.knownCapitalForCountry("United Kingdom"))
    }

    @Test
    fun `returns null for non-capital query`() {
        assertNull(WeatherLocationReferenceParser.extractCountryFromCapitalQuery("Wellington"))
    }
}
