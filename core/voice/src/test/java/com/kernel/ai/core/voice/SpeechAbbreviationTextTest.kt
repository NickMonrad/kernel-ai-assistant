package com.kernel.ai.core.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpeechAbbreviationTextTest {

    @Test
    fun `weekday and day-month abbreviations from the forecast card are spoken in full`() {
        assertEquals("Monday the 23rd of November", SpeechAbbreviationText.expand("Mon 23 Nov"))
        assertEquals("Tuesday the 24th of November", SpeechAbbreviationText.expand("Tue 24 Nov"))
        assertEquals("Sunday the 29th of November", SpeechAbbreviationText.expand("Sun 29 Nov"))
    }

    @Test
    fun `day-month ordinals follow English suffix rules`() {
        assertEquals("the 1st of January", SpeechAbbreviationText.expand("1 Jan"))
        assertEquals("the 2nd of February", SpeechAbbreviationText.expand("2 Feb"))
        assertEquals("the 3rd of March", SpeechAbbreviationText.expand("3 Mar"))
        assertEquals("the 11th of October", SpeechAbbreviationText.expand("11 Oct"))
        assertEquals("the 12th of June", SpeechAbbreviationText.expand("12 Jun"))
        assertEquals("the 21st of July", SpeechAbbreviationText.expand("21 Jul"))
        assertEquals("the 31st of December", SpeechAbbreviationText.expand("31 Dec"))
    }

    @Test
    fun `month before day is expanded`() {
        assertEquals("November the 23rd", SpeechAbbreviationText.expand("Nov 23"))
    }

    @Test
    fun `abbreviation with a sentence full stop keeps the stop`() {
        assertEquals("Friday the 4th of December.", SpeechAbbreviationText.expand("Fri 4 Dec."))
    }

    @Test
    fun `weather units and symbols are spoken in words`() {
        assertEquals("11 degrees", SpeechAbbreviationText.expand("11°C"))
        assertEquals("-5 degrees", SpeechAbbreviationText.expand("-5°C"))
        assertEquals("77 degrees Fahrenheit", SpeechAbbreviationText.expand("77°F"))
        assertEquals("92 percent", SpeechAbbreviationText.expand("92%"))
        assertEquals("12.5 percent", SpeechAbbreviationText.expand("12.5%"))
        assertEquals("0 millimetres rain", SpeechAbbreviationText.expand("0mm rain"))
        assertEquals("20 kilometres per hour", SpeechAbbreviationText.expand("20 km/h"))
    }

    @Test
    fun `weekday before a comma or a month is expanded`() {
        assertEquals("Monday, the 23rd of November", SpeechAbbreviationText.expand("Mon, 23 Nov"))
        assertEquals("Monday November the 23rd", SpeechAbbreviationText.expand("Mon Nov 23"))
    }

    @Test
    fun `ranges, slashes and decimals are not read as single dates`() {
        assertEquals("5-10 Nov", SpeechAbbreviationText.expand("5-10 Nov"))
        assertEquals("5 - 10 Nov", SpeechAbbreviationText.expand("5 - 10 Nov"))
        assertEquals("2/3 May", SpeechAbbreviationText.expand("2/3 May"))
        assertEquals("1.5 Mar", SpeechAbbreviationText.expand("1.5 Mar"))
        assertEquals("Nov 5-10", SpeechAbbreviationText.expand("Nov 5-10"))
    }

    @Test
    fun `degree sign next to a letter keeps a space`() {
        assertEquals("45 degrees N 170 degrees E", SpeechAbbreviationText.expand("45°N 170°E"))
        assertEquals("20 degrees Celsius", SpeechAbbreviationText.expand("20°Celsius"))
        assertEquals("1.9 metres per second", SpeechAbbreviationText.expand("1.9 m/s"))
    }

    @Test
    fun `words that are not dates are left alone`() {
        assertEquals("Sun is shining in May.", SpeechAbbreviationText.expand("Sun is shining in May."))
        assertEquals("Sunday the 29th of November", SpeechAbbreviationText.expand("Sunday the 29th of November"))
        assertEquals("the 2026 plan", SpeechAbbreviationText.expand("the 2026 plan"))
    }

    @Test
    fun `expansion is idempotent`() {
        val once = SpeechAbbreviationText.expand("Mon 23 Nov: 27°C, 92% rain 0mm")
        assertEquals(once, SpeechAbbreviationText.expand(once))
    }

    @Test
    fun `speakable applies the forecast card cleanup and expansion in one pass`() {
        assertEquals(
            "Monday the 23rd of November: Overcast, high 27 degrees, low 19 degrees, 0 millimetres rain, " +
                "UV max: 9 (Very High), 04:46, 18:22",
            SpeechCardText.speakable(
                "Mon 23 Nov: ☁️ Overcast, high 27°C, low 19°C, 0mm rain | UV max: 9 (Very High) | 🌅 04:46 / 18:22",
            ),
        )
        assertEquals(
            "Tuesday the 24th of November: Overcast, high 28 degrees, low 19 degrees, 0 millimetres rain, " +
                "UV max: 9 (Very High), 04:46, 18:23",
            SpeechCardText.speakable(
                "Tue 24 Nov: ☁️ Overcast, high 28°C, low 19°C, 0mm rain | UV max: 9 (Very High) | 🌅 04:46 / 18:23",
            ),
        )
    }

    @Test
    fun `bullet separators from cards are not spoken`() {
        assertEquals("High 27 degrees, sunrise 04:46", SpeechCardText.speakable("High 27°C • sunrise 04:46"))
    }
}
