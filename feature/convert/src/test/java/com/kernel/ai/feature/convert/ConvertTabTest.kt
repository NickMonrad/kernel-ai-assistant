package com.kernel.ai.feature.convert

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [ConvertTab] and its [mapQueryParamToConvertTab] helper.
 */
class ConvertTabTest {

    @Nested
    @DisplayName("mapQueryParamToConvertTab")
    inner class MapQueryParamToConvertTab {

        @Test
        fun `currency maps to CURRENCY`() {
            assertEquals(ConvertTab.CURRENCY, mapQueryParamToConvertTab("currency"))
        }

        @Test
        fun `CURRENCY uppercase maps to CURRENCY`() {
            assertEquals(ConvertTab.CURRENCY, mapQueryParamToConvertTab("CURRENCY"))
        }

        @Test
        fun `unit maps to UNIT`() {
            assertEquals(ConvertTab.UNIT, mapQueryParamToConvertTab("unit"))
        }

        @Test
        fun `units maps to UNIT`() {
            assertEquals(ConvertTab.UNIT, mapQueryParamToConvertTab("units"))
        }

        @Test
        fun `cooking maps to COOKING`() {
            assertEquals(ConvertTab.COOKING, mapQueryParamToConvertTab("cooking"))
        }

        @Test
        fun `unknown param returns null`() {
            assertNull(mapQueryParamToConvertTab("calculator"))
            assertNull(mapQueryParamToConvertTab(""))
            assertNull(mapQueryParamToConvertTab("   "))
            assertNull(mapQueryParamToConvertTab("bogus"))
        }
    }
}
