package com.kernel.ai.core.skills.natives

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeviceLocationPrecisionTest {
    @Test
    fun `device coordinates in reverse geocoding and weather URLs have at most two decimal places`() {
        val requestLatitude = roundDeviceLocationCoordinate(-27.4698123)
        val requestLongitude = roundDeviceLocationCoordinate(153.0251234)
        val reverseGeocodingUrl = "https://nominatim.openstreetmap.org/reverse?" +
            "${nominatimReverseCoordinateQuery(requestLatitude, requestLongitude)}&format=json"
        val weatherUrl = "https://api.open-meteo.com/v1/forecast?" +
            openMeteoCoordinateQuery(requestLatitude, requestLongitude)

        assertCoordinatePrecision(reverseGeocodingUrl, "lat", "lon")
        assertCoordinatePrecision(weatherUrl, "latitude", "longitude")
        assertTrue(reverseGeocodingUrl.contains("lat=-27.47&lon=153.03"))
        assertTrue(weatherUrl.contains("latitude=-27.47&longitude=153.03"))
    }

    @Test
    fun `named-place geocoding coordinates remain unchanged in weather request URL`() {
        val namedPlaceLatitude = -27.4698123
        val namedPlaceLongitude = 153.0251234
        val weatherUrl = "https://api.open-meteo.com/v1/forecast?" +
            openMeteoCoordinateQuery(namedPlaceLatitude, namedPlaceLongitude)

        assertTrue(weatherUrl.contains("latitude=-27.4698123&longitude=153.0251234"))
        assertFalse(weatherUrl.contains("latitude=-27.47&longitude=153.03"))
    }

    private fun assertCoordinatePrecision(url: String, latitudeParameter: String, longitudeParameter: String) {
        val coordinatePattern = Regex("(?:^|[?&])(?:$latitudeParameter|$longitudeParameter)=(-?\\d+(?:\\.(\\d+))?)")
        val coordinates = coordinatePattern.findAll(url).toList()

        assertEquals(2, coordinates.size)
        coordinates.forEach { match ->
            assertTrue((match.groups[2]?.value?.length ?: 0) <= 2, "Too much precision in $url")
        }
    }
}
