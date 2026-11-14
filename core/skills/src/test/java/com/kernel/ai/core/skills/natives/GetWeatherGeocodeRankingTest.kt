package com.kernel.ai.core.skills.natives

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.json.JSONObject

/** #1894 — geocode ranking, country naming and outage messaging. */
class GetWeatherGeocodeRankingTest {

    private val oaklandCa = GeocodeCandidate(37.80, -122.27, "us", "United States")
    private val oaklandMi = GeocodeCandidate(42.66, -83.38, "us", "United States")
    private val aucklandNz = GeocodeCandidate(-36.85, 174.76, "nz", "New Zealand")

    @Test
    fun `NZ result is preferred over a higher-ranked US result for NZ home`() {
        val picked = rankGeocodeCandidates(listOf(oaklandCa, aucklandNz), listOf("nz"))
        assertEquals(aucklandNz, picked)
    }

    @Test
    fun `falls back to provider order when no candidate is in a preferred country`() {
        assertEquals(oaklandCa, rankGeocodeCandidates(listOf(oaklandCa, oaklandMi), listOf("nz")))
    }

    @Test
    fun `no preferred countries keeps provider order`() {
        assertEquals(oaklandCa, rankGeocodeCandidates(listOf(oaklandCa, aucklandNz), emptyList()))
    }

    @Test
    fun `empty candidate list yields null`() {
        assertNull(rankGeocodeCandidates(emptyList(), listOf("nz")))
    }

    @Test
    fun `earlier preferred country wins over later one`() {
        val auckland = GeocodeCandidate(-36.85, 174.76, "nz", "New Zealand")
        val sydney = GeocodeCandidate(-33.87, 151.21, "au", "Australia")
        assertEquals(auckland, rankGeocodeCandidates(listOf(sydney, auckland), listOf("nz", "au")))
    }

    @Test
    fun `home-country match is labelled with the query only`() {
        assertEquals("Auckland", weatherLocationLabel("Auckland", aucklandNz, listOf("nz")))
    }

    @Test
    fun `overseas match names its country`() {
        assertEquals(
            "Oakland, United States",
            weatherLocationLabel("Oakland", oaklandCa, listOf("nz")),
        )
    }

    @Test
    fun `no home country means no country suffix`() {
        assertEquals("Oakland", weatherLocationLabel("Oakland", oaklandCa, emptyList()))
    }

    @Test
    fun `candidate without country name is not suffixed`() {
        val anon = GeocodeCandidate(1.0, 2.0, null, null)
        assertEquals("Somewhere", weatherLocationLabel("Somewhere", anon, listOf("nz")))
    }

    @Test
    fun `parses nominatim candidate with country fields`() {
        val json = JSONObject(
            """{"lat":"-36.852095","lon":"174.763180","address":{"country":"New Zealand","country_code":"nz"}}""",
        )
        assertEquals(
            GeocodeCandidate(-36.852095, 174.763180, "nz", "New Zealand"),
            parseGeocodeCandidate(json),
        )
    }

    @Test
    fun `unparseable nominatim candidate is dropped`() {
        assertNull(parseGeocodeCandidate(JSONObject("""{"lat":"x","lon":"1"}""")))
        assertNull(parseGeocodeCandidate(null))
    }

    @Test
    fun `outage message is distinct from not-found and generic API failure`() {
        val outage = weatherFailureMessage(WeatherLookupFailureReason.GEOCODE_UNAVAILABLE)
        assertTrue(outage.contains("location service"))
        assertTrue(outage != weatherFailureMessage(WeatherLookupFailureReason.NAMED_LOCATION_NOT_FOUND))
        assertTrue(outage != weatherFailureMessage(WeatherLookupFailureReason.API_UNAVAILABLE))
    }
}
