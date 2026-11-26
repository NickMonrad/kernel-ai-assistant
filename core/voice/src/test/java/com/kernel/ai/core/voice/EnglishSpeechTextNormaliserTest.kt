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
    fun both_ends_of_a_year_range_read_as_years() {
        assertEquals("from twenty twenty to twenty twenty six", kokoroText("from 2020 to 2026"))
        assertEquals("nineteen ninety nine to two thousand", kokoroText("1999-2000"))
        assertEquals("nineteen ninety to nineteen ninety five", kokoroText("1990–1995"))
    }

    @Test
    fun year_and_season_cues_before_the_number_and_10xx_years_read_as_years() {
        assertEquals("the year twenty twenty six", kokoroText("the year 2026"))
        assertEquals("the twenty twenty six season", kokoroText("the 2026 season"))
        assertEquals("in ten sixty six", kokoroText("in 1066"))
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
        assertEquals("one millionth", kokoroText("1,000,000th"))
    }

    @Test
    fun money_with_a_scale_word_reads_the_amount_then_the_scale() {
        assertEquals("one point five million dollars", kokoroText("\$1.5 million"))
        assertEquals("one point five million dollars", kokoroText("\$1.5m"))
        assertEquals("two billion dollars", kokoroText("\$2bn"))
        assertEquals("one hundred thousand dollars", kokoroText("\$100k"))
        assertEquals("forty nine dollars and ninety five cents", kokoroText("\$49.95"))
    }

    @Test
    fun decimals_glued_to_units_or_letters_keep_their_point() {
        assertEquals("one point five kilometres", kokoroText("1.5km"))
        assertEquals("two point five kilograms", kokoroText("2.5kg"))
        assertEquals("one point five litres", kokoroText("1.5L"))
        assertEquals("zero point seven five times", kokoroText("0.75x"))
        assertEquals("two point four gigahertz", kokoroText("2.4GHz"))
        assertEquals("Qwen two point five", kokoroText("Qwen2.5"))
        assertEquals("Llama three point one", kokoroText("Llama3.1"))
    }

    @Test
    fun glued_units_and_mixed_tokens_read_naturally() {
        assertEquals("It's five kilometres to gate five ay for flight en zee one two three, and the five gee signal is fine.",
            kokoroText("It's 5km to gate 5A for flight NZ123, and the 5G signal is fine."))
        assertEquals("Qwen three runs on ten eighty p.", kokoroText("Qwen3 runs on 1080p."))
        assertEquals("Watch in seven twenty p.", kokoroText("Watch in 720p."))
        assertEquals("em pee three files, four kay and three dee", kokoroText("mp3 files, 4K and 3D"))
        assertEquals("aitch two oh and bee twelve and four bee", kokoroText("H2O and B12 and 4B"))
    }

    @Test
    fun short_unit_suffixes_read_as_units_and_decades_read_as_decades() {
        assertEquals("two hours", kokoroText("2hrs"))
        assertEquals("five minutes", kokoroText("5mins"))
        assertEquals("five kilometres", kokoroText("5kms"))
        assertEquals("five hundred millilitres", kokoroText("500mL"))
        assertEquals("thirty seconds", kokoroText("30sec"))
        assertEquals("one hundred and twenty hertz", kokoroText("120Hz"))
        assertEquals("the nineties", kokoroText("the 90s"))
        assertEquals("the nineties", kokoroText("'90s"))
    }

    @Test
    fun times_and_timer_phrasings() {
        assertEquals("Sunrise seven oh five a m.", kokoroText("Sunrise 7:05 am."))
        assertEquals("Timer set for two minutes thirty seconds.", kokoroText("Timer set for 2 minutes 30 seconds."))
        assertEquals("Meet at eight o clock.", kokoroText("Meet at 8:00."))
        assertEquals("Ready at six forty five p m.", kokoroText("Ready at 6:45 pm."))
        assertEquals("Meeting at three p m.", kokoroText("Meeting at 15:00."))
        assertEquals("Opens at midnight.", kokoroText("Opens at 00:00."))
    }

    @Test
    fun abbreviated_weekday_and_month_dates_are_spelled_out_then_ordinal_read() {
        assertEquals("Friday the fifth of December.", kokoroText("Fri 5 Dec."))
        assertEquals("Monday the twenty third of November:", kokoroText("Mon 23 Nov:"))
        assertEquals("November the twenty third", kokoroText("Nov 23"))
        assertEquals("the first of January", kokoroText("1 Jan"))
    }

    @Test
    fun day_first_dates_read_the_nth_of_month_form_with_the_year() {
        assertEquals("the twenty sixth of November, twenty twenty six", kokoroText("26/11/2026"))
        assertEquals("the thirty first of December, twenty twenty six", kokoroText("31/12/2026"))
        assertEquals("the twelfth of January, twenty twenty six", kokoroText("12/1/2026"))
        assertEquals("the fifth of June, twenty twenty six", kokoroText("05/06/2026"))
        assertEquals("the first of December, twenty twenty six", kokoroText("1/12/2026"))
    }

    @Test
    fun a_slash_date_falls_back_to_month_first_only_when_day_first_is_not_a_date() {
        assertEquals("the twenty first of January, twenty twenty six", kokoroText("1/21/2026"))
    }

    @Test
    fun written_dates_with_a_year_read_the_nth_of_month_form() {
        assertEquals("the twenty seventh of November, twenty twenty six", kokoroText("27 November 2026"))
        assertEquals("the twenty seventh of November, twenty twenty six", kokoroText("November 27, 2026"))
        assertEquals("the twenty seventh of November, twenty twenty six", kokoroText("27 Nov 2026"))
    }

    @Test
    fun money_and_decimals() {
        assertEquals("It costs twelve dollars and seventy five cents.", kokoroText("It costs \$12.75."))
        assertEquals("Wind three point seven five metres per second.", kokoroText("Wind 3.75 m/s."))
        assertEquals("one point five and one point oh", kokoroText("1.50 and 1.0"))
    }

    @Test
    fun iso_dates_and_phone_style_groups() {
        assertEquals("the twenty sixth of November, twenty twenty six", kokoroText("2026-11-26"))
        assertEquals("two oh three, oh six seven eight", kokoroText("203-0678"))
        assertEquals("one three oh oh, five five five, oh one nine", kokoroText("1300 555 019"))
    }

    @Test
    fun nz_phone_numbers_read_digit_by_digit_in_groups_with_oh() {
        assertEquals("oh two one, one two three, four five six seven", kokoroText("021-123-4567"))
        assertEquals("oh eight oh oh, five five five, oh one nine", kokoroText("0800 555 019"))
        assertEquals("oh eight oh oh, five five five, oh one nine", kokoroText("0800-555-019"))
        assertEquals(
            "plus six four, two one, one two three, four five six seven",
            kokoroText("+64 21 123 4567"),
        )
    }

    @Test
    fun emergency_111_reads_one_one_one_and_other_111s_stay_quantities() {
        assertEquals("Ring one one one in an emergency.", kokoroText("Ring 111 in an emergency."))
        assertEquals("Call one one one.", kokoroText("Call 111."))
        assertEquals("Emergency one one one", kokoroText("Emergency 111"))
        assertEquals("one hundred and eleven people", kokoroText("111 people"))
    }

    @Test
    fun output_has_no_digits_or_phonemiser_symbols_for_the_covered_forms() {
        val inputs = listOf(
            "Mon 23 Nov: 27°C, 92% rain, 1999, 7:05 am, \$3.50, 2 500 ml",
            "Founded in 1999, renamed in 2005, a band from the 1990s, from 2020 to 2026.",
            "The stadium holds 15,000 people and cost \$1,200,000.50.",
            "1,000,000 people, 1,000.50 litres, \$12.345, NZ\$20, -\$5, −5 degrees",
            "Booked for 2026-11-26, about 3-5 days away, 5–10 km.",
            "Booked for 26/11/2026 and 12/1/2026, and 27 November 2026.",
            "It's 5km to gate 5A for flight NZ123, the 5G signal and 4K and 3D.",
            "Qwen3 runs on 1080p, 720p, mp3 files, H2O, B12, 4B, 2.4GHz, Qwen2.5.",
            "Version 1.2.3 and 10.0.1 and \$1.5 million and \$2bn and \$100k.",
            "Ring 111 or call 0800 555 019, or +64 21 123 4567, or 021-123-4567.",
            "Booked for 1300 555 019, 203-0678, 2hrs, 5mins, 120Hz and 1.5km.",
            "12345678901234567890123 and 99999999999999999999 people.",
        )
        val symbol = Regex("""[/+#€£]|\S[-–−:]\S""")
        inputs.forEach { input ->
            val spoken = kokoroText(input)
            assertEquals(false, spoken.any { it.isDigit() }, "$input → $spoken")
            assertEquals(false, symbol.containsMatchIn(spoken), "$input → $spoken")
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
