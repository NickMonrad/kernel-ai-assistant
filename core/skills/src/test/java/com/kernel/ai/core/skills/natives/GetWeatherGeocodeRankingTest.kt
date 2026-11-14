package com.kernel.ai.core.skills.natives

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** #1894 — geocode ranking by importance, English country naming. */
class GetWeatherGeocodeRankingTest {

    private val aucklandNz = GeocodeCandidate(-36.85, 174.76, "nz", 0.71)
    private val oaklandCa = GeocodeCandidate(37.80, -122.27, "us", 0.686)
    private val parisFr = GeocodeCandidate(48.85, 2.35, "fr", 0.90)
    private val parisTexas = GeocodeCandidate(33.66, -95.55, "us", 0.50)
    private val parisTexasClose = GeocodeCandidate(33.66, -95.55, "us", 0.80)

    @Test
    fun `home-country match wins when nearly as important as the top match`() {
        assertEquals(aucklandNz, rankGeocodeCandidates(listOf(oaklandCa, aucklandNz), "nz"))
    }

    @Test
    fun `small home-country match does not displace a major overseas city`() {
        assertEquals(parisFr, rankGeocodeCandidates(listOf(parisFr, parisTexas), "us"))
    }

    @Test
    fun `home-country match with comparable importance is chosen`() {
        assertEquals(parisTexasClose, rankGeocodeCandidates(listOf(parisFr, parisTexasClose), "us"))
    }

    @Test
    fun `no home country keeps provider order`() {
        assertEquals(oaklandCa, rankGeocodeCandidates(listOf(oaklandCa, aucklandNz), null))
    }

    @Test
    fun `empty candidate list yields null`() {
        assertNull(rankGeocodeCandidates(emptyList(), "nz"))
    }

    @Test
    fun `home-country match is labelled with the query only`() {
        assertEquals("Auckland", weatherLocationLabel("Auckland", aucklandNz, "nz"))
    }

    @Test
    fun `overseas match names its English country`() {
        assertEquals("Oakland, United States", weatherLocationLabel("Oakland", oaklandCa, "au"))
    }

    @Test
    fun `no home country means no country suffix`() {
        assertEquals("Oakland", weatherLocationLabel("Oakland", oaklandCa, null))
    }

    @Test
    fun `English country name is locale-independent`() {
        assertEquals("New Zealand", englishCountryName("nz"))
        assertEquals("Germany", englishCountryName("DE"))
    }

    @Test
    fun `parses nominatim candidate with country code and importance`() {
        val json = JSONObject(
            """{"lat":"-36.852095","lon":"174.763180","importance":0.71,"address":{"country":"New Zealand","country_code":"nz"}}""",
        )
        assertEquals(GeocodeCandidate(-36.852095, 174.76318, "nz", 0.71), parseGeocodeCandidate(json))
    }

    @Test
    fun `unparseable nominatim candidate is dropped`() {
        assertNull(parseGeocodeCandidate(JSONObject("""{"lat":"x","lon":"1"}""")))
        assertNull(parseGeocodeCandidate(null))
    }
}
