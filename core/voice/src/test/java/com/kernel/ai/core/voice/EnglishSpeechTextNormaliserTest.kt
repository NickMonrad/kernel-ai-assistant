package com.kernel.ai.core.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The Kokoro lane's text path: [SpeechCardText.speakable], then the shared number normaliser (#2115). Inflect's own
 * output for the same phrasings is pinned in [InflectMicroTextFrontendTest].
 */
class EnglishSpeechTextNormaliserTest {
    private fun kokoroText(input: String): String =
        EnglishSpeechTextNormaliser.normalize(SpeechCardText.speakable(input))

    @Test
    fun years_after_a_year_cue_read_as_year_pairs() {
        assertEquals(
            "It happened in nineteen ninety nine.",
            kokoroText("It happened in 1999."),
        )
        assertEquals("Founded in twenty twenty six.", kokoroText("Founded in 2026."))
        assertEquals("Renamed in two thousand and five.", kokoroText("Renamed in 2005."))
        assertEquals("Built in nineteen hundred.", kokoroText("Built in 1900."))
        assertEquals("a band from the nineteen nineties.", kokoroText("a band from the 1990s."))
    }

    @Test
    fun bare_four_digit_quantities_stay_cardinals() {
        assertEquals("one thousand three hundred people", kokoroText("1300 people"))
        assertEquals("two thousand and twenty five", kokoroText("2025"))
    }

    @Test
    fun weather_readback_percent_and_celsius_become_words() {
        assertEquals(
            "High twenty seven degrees, sunrise four forty six",
            kokoroText("High 27°C • sunrise 04:46"),
        )
        assertEquals("Rain chance ninety two percent.", kokoroText("Rain chance 92%."))
        assertEquals("Rain: zero millimetres.", kokoroText("Rain: 0mm."))
    }

    @Test
    fun negative_numbers_read_minus_when_a_digit_follows_and_no_word_precedes() {
        assertEquals("minus five degrees", kokoroText("-5°C"))
        assertEquals("minus five degrees", kokoroText("−5 degrees"))
        assertEquals("Low of minus three degrees overnight.", kokoroText("Low of -3°C overnight."))
        assertEquals("minus five dollars", kokoroText("-\$5"))
    }

    @Test
    fun ranges_read_to() {
        assertEquals("three to five days", kokoroText("3-5 days"))
        assertEquals("five to ten km", kokoroText("5–10 km"))
    }

    @Test
    fun big_numbers_and_decimals_read_as_words() {
        assertEquals("fifteen thousand people", kokoroText("15,000 people"))
        assertEquals("one million people", kokoroText("1,000,000 people"))
        assertEquals("one thousand point five litres", kokoroText("1,000.50 litres"))
        assertEquals("twelve point three four five dollars", kokoroText("\$12.345"))
        assertEquals("twenty New Zealand dollars", kokoroText("NZ\$20"))
    }

    @Test
    fun glued_units_and_mixed_tokens_read_naturally() {
        assertEquals("It's five kilometres to gate five ay for flight en zee one two three, and the five gee signal is fine.",
            kokoroText("It's 5km to gate 5A for flight NZ123, and the 5G signal is fine."))
        assertEquals("Qwen three runs on one thousand and eighty pee.", kokoroText("Qwen3 runs on 1080p."))
        assertEquals("em pee three files, four kay and three dee", kokoroText("mp3 files, 4K and 3D"))
        assertEquals("aitch two oh and bee twelve and four bee", kokoroText("H2O and B12 and 4B"))
    }

    @Test
    fun times_and_timer_phrasings() {
        assertEquals("Sunrise seven oh five a m.", kokoroText("Sunrise 7:05 am."))
        assertEquals("Timer set for two minutes thirty seconds.", kokoroText("Timer set for 2 minutes 30 seconds."))
        assertEquals("Meet at eight o clock.", kokoroText("Meet at 8:00."))
        assertEquals("Ready at six forty five p m.", kokoroText("Ready at 6:45 pm."))
    }

    @Test
    fun abbreviated_weekday_and_month_dates_are_spelled_out_then_ordinal_read() {
        assertEquals("Friday the fifth of December.", kokoroText("Fri 5 Dec."))
        assertEquals("Monday the twenty third of November:", kokoroText("Mon 23 Nov:"))
        assertEquals("November the twenty third", kokoroText("Nov 23"))
        assertEquals("the first of January", kokoroText("1 Jan"))
    }

    @Test
    fun money_and_decimals() {
        assertEquals("It costs twelve dollars and seventy five cents.", kokoroText("It costs \$12.75."))
        assertEquals("Wind three point seven five metres per second.", kokoroText("Wind 3.75 m/s."))
        assertEquals("one point five and three point one", kokoroText("1.50 and 3.10"))
    }

    @Test
    fun numeric_dates_iso_dates_and_phone_style_groups() {
        assertEquals("January twenty first twenty twenty six", kokoroText("1/21/2026"))
        assertEquals("November twenty sixth twenty twenty six", kokoroText("2026-11-26"))
        assertEquals("two oh three, zero six seven eight", kokoroText("203-0678"))
    }

    @Test
    fun output_has_no_digits_for_the_phonemiser() {
        val inputs = listOf(
            "Mon 23 Nov: 27°C, 92% rain, 1999, 7:05 am, \$3.50, 2 500 ml",
            "Founded in 1999, renamed in 2005, a band from the 1990s.",
            "The stadium holds 15,000 people and cost \$1,200,000.50.",
            "1,000,000 people, 1,000.50 litres, \$12.345, NZ\$20, -\$5, −5 degrees",
            "Booked for 2026-11-26, about 3-5 days away, 5–10 km, 2/3 cup.",
            "It's 5km to gate 5A for flight NZ123, the 5G signal and 4K and 3D.",
            "Qwen3 runs on 1080p, mp3 files, H2O, B12, 4B, 0800-555-019.",
            "Version 1.2.3 and 10.0.1 and 1/21/2026.",
            "12345678901234567890123 and 99999999999999999999 people.",
        )
        inputs.forEach { input ->
            val spoken = kokoroText(input)
            assertEquals(false, spoken.any { it.isDigit() }, "$input → $spoken")
        }
    }

    @Test
    fun very_long_digit_runs_are_read_digit_by_digit_without_overflow() {
        assertEquals(
            "one two three four five six seven eight nine oh one two three four five six seven eight nine oh one two three",
            kokoroText("12345678901234567890123"),
        )
    }
}
