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
    fun years_read_as_cardinals_like_inflect() {
        assertEquals(
            "It happened in one thousand nine hundred and ninety nine.",
            kokoroText("It happened in 1999."),
        )
        assertEquals("Founded in two thousand and twenty six.", kokoroText("Founded in 2026."))
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
    fun negative_temperatures_keep_the_hyphen_and_spell_the_number() {
        // Hyphen is left for the engine, as in Inflect: "-5 degrees" → "-five degrees".
        assertEquals("-five degrees", kokoroText("-5°C"))
        assertEquals("Low of -three degrees overnight.", kokoroText("Low of -3°C overnight."))
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
    }

    @Test
    fun numeric_dates_and_phone_style_groups() {
        assertEquals("January twenty first two thousand and twenty six", kokoroText("1/21/2026"))
        assertEquals("two oh three, zero six seven eight", kokoroText("203-0678"))
    }

    @Test
    fun output_has_no_digits_for_the_phonemiser() {
        val spoken = kokoroText("Mon 23 Nov: 27°C, 92% rain, 1999, 7:05 am, \$3.50, 2 500 ml")
        assertEquals(false, spoken.any { it.isDigit() }, spoken)
    }
}
