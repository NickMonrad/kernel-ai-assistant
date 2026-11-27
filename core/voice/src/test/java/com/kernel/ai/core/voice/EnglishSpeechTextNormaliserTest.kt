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
        assertEquals("five to ten kilometres", kokoroText("5–10 km"))
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
        assertEquals("It's five kilometres to gate five eh for flight en zed one two three, and the five gee signal is fine.",
            kokoroText("It's 5km to gate 5A for flight NZ123, and the 5G signal is fine."))
        assertEquals("Qwen three runs on ten eighty p.", kokoroText("Qwen3 runs on 1080p."))
        assertEquals("Watch in seven twenty p.", kokoroText("Watch in 720p."))
        assertEquals("em pee three files, four kay and three dee", kokoroText("mp3 files, 4K and 3D"))
        assertEquals("aitch two oh and bee twelve and four bee", kokoroText("H2O and B12 and 4B"))
    }

    @Test
    fun spelled_letters_use_names_both_engines_read_as_the_letter() {
        assertEquals("five eh", kokoroText("5A"))
        assertEquals("five e", kokoroText("5E"))
        assertEquals("five F", kokoroText("5F"))
        assertEquals("five are", kokoroText("5R"))
        assertEquals("five zed", kokoroText("5Z"))
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
    fun seconds_after_a_number_are_seconds_and_decades_need_the_or_an_apostrophe_or_four_digits() {
        assertEquals("wait thirty seconds", kokoroText("wait 30s"))
        assertEquals("a forty five second clip", kokoroText("a 45s clip"))
        assertEquals("the nineties", kokoroText("the 90s"))
        assertEquals("the eighties", kokoroText("the '80s"))
        assertEquals("nineteen nineties", kokoroText("1990s"))
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

    @Test
    fun fractions_and_ratios_read_as_words() {
        assertEquals("half a cup", kokoroText("1/2 cup"))
        assertEquals("three quarters", kokoroText("3/4"))
        assertEquals("three quarters of a cup", kokoroText("3/4 cup"))
        assertEquals("a quarter of a cup", kokoroText("1/4 cup"))
        assertEquals("twenty four seven", kokoroText("24/7"))
        assertEquals("sixteen by nine", kokoroText("16:9"))
        assertEquals("four out of five stars", kokoroText("4/5 stars"))
    }

    @Test
    fun money_symbols_and_scale_words_read_naturally() {
        assertEquals("five dollars a month", kokoroText("\$5/month"))
        assertEquals("thirty euros", kokoroText("€30"))
        assertEquals("four pounds and twenty pence", kokoroText("£4.20"))
        assertEquals("one thousand yen", kokoroText("¥1000"))
        assertEquals("ninety nine cents", kokoroText("\$0.99"))
        assertEquals("one point five billion dollars", kokoroText("\$1.5 billion dollars"))
        assertEquals("minus five dollars", kokoroText("\$-5"))
    }

    @Test
    fun durations_time_ranges_and_spaced_ranges() {
        assertEquals("one hour, thirty minutes and forty five seconds", kokoroText("1:30:45"))
        assertEquals("ten thirty to eleven thirty", kokoroText("10:30–11:30"))
        assertEquals("nine a m to five p m", kokoroText("9am-5pm"))
        assertEquals("eighteen to twenty two degrees", kokoroText("18 – 22°C"))
    }

    @Test
    fun spaced_units_read_in_full_and_a_number_adjective_takes_the_singular() {
        assertEquals("five kilometres", kokoroText("5 km"))
        assertEquals("ten minutes", kokoroText("10 min"))
        assertEquals("five kilohertz", kokoroText("5 kHz"))
        assertEquals("one minute", kokoroText("1 min"))
        assertEquals("wait thirty seconds", kokoroText("wait 30s"))
    }

    @Test
    fun hash_plus_greater_than_and_equals_read_as_words() {
        assertEquals("number one", kokoroText("#1"))
        assertEquals("plus five percent", kokoroText("+5%"))
        assertEquals("plus three degrees", kokoroText("+3°C"))
        assertEquals("two plus two equals four", kokoroText("2+2=4"))
        assertEquals("three or more people", kokoroText("3+ people"))
        assertEquals("more than five", kokoroText(">5"))
        assertEquals("minus five", kokoroText("--5"))
    }

    @Test
    fun slash_dates_without_a_year_or_with_a_two_digit_year_read_as_dates() {
        assertEquals("the twenty sixth of November", kokoroText("26/11"))
        assertEquals("the first of February, twenty twenty six", kokoroText("1/2/26"))
        assertEquals("Due the first of February, twenty twenty six", kokoroText("Due 1/2/26"))
    }

    @Test
    fun a_year_before_was_or_is_reads_as_a_year() {
        assertEquals("nineteen ninety nine was a great year", kokoroText("1999 was a great year"))
        assertEquals("one thousand three hundred people were there", kokoroText("1300 people were there"))
    }

    @Test
    fun nz_phone_forms_with_hyphens_and_a_bracketed_area_code() {
        assertEquals("plus six four, two one, one two three, four five six seven", kokoroText("+64-21-123-4567"))
        assertEquals("oh nine, one two three, four five six seven", kokoroText("(09) 123 4567"))
    }

    @Test
    fun dotted_versions_read_with_version_and_point() {
        assertEquals("version one point two point three", kokoroText("v1.2.3"))
    }

    @Test
    fun flat_numbers_with_a_slash_read_as_flat_and_number() {
        assertEquals("flat three, forty five", kokoroText("Flat 3/45"))
    }

    @Test
    fun new_forms_leave_no_digits_or_symbols() {
        val inputs = listOf(
            "1/2 cup, 3/4, 24/7, 16:9, 4/5 stars, Flat 3/45.",
            "\$5/month, €30, £4.20, ¥1000, \$0.99, \$1.5 billion dollars, \$-5, --5.",
            "1:30:45, 10:30–11:30, 9am-5pm, 18 – 22°C, 5 km, 10 min, 5 kHz, 5–10 km.",
            "a 45s clip, #1, +5%, +3°C, 2+2=4, 3+ people, >5, 26/11, 1/2/26, v1.2.3.",
            "+64-21-123-4567, (09) 123 4567, 1999 was a great year.",
        )
        val symbol = Regex("""[/+#€£¥<>=]|\S[-–−:]\S""")
        inputs.forEach { input ->
            val spoken = kokoroText(input)
            assertEquals(false, spoken.any { it.isDigit() }, "$input → $spoken")
            assertEquals(false, symbol.containsMatchIn(spoken), "$input → $spoken")
        }
    }

    @Test
    fun superscript_and_subscript_digits_pass_through_without_throwing() {
        assertEquals("fifty m²", kokoroText("50 m²"))
        assertEquals("CO₂", kokoroText("CO₂"))
        assertEquals("H₂O", kokoroText("H₂O"))
        assertEquals("E=mc²", kokoroText("E=mc²"))
        assertEquals("x²", kokoroText("x²"))
    }

    @Test
    fun expand_digits_skips_non_ascii_digits_instead_of_indexing_them() {
        assertEquals("one", EnglishSpeechTextNormaliser.expandDigits("1²"))
        assertEquals("oh", EnglishSpeechTextNormaliser.expandDigits("0₁"))
    }

    @Test
    fun fractions_read_as_fractions_or_out_of_never_as_misreads() {
        assertEquals("three quarters done", kokoroText("3/4 done"))
        assertEquals("four out of four tests passed", kokoroText("4/4 tests passed"))
        assertEquals("ten out of ten", kokoroText("10/10"))
        assertEquals("Do half now", kokoroText("Do 1/2 now"))
        assertEquals("half an hour", kokoroText("1/2 hour"))
        assertEquals("half an hour", kokoroText("1/2 an hour"))
        assertEquals("an eighth of a teaspoon", kokoroText("1/8 teaspoon"))
        assertEquals("fifty fifty", kokoroText("50/50"))
        assertEquals("nine eleven", kokoroText("9/11"))
    }

    @Test
    fun sequence_positions_read_as_n_of_m() {
        assertEquals("Step two of four", kokoroText("Step 2/4"))
        assertEquals("page one of three", kokoroText("page 1/3"))
        assertEquals("Score seven of ten", kokoroText("Score 7/10"))
        assertEquals("Score ten of ten", kokoroText("Score 10/10"))
    }

    @Test
    fun day_first_dates_after_a_date_cue_read_as_dates_not_fractions() {
        assertEquals("Appointment on the fifth of December", kokoroText("Appointment on 5/12"))
        assertEquals("Due the fifth of December", kokoroText("Due 5/12"))
        assertEquals("from the third of April to the tenth of April", kokoroText("from 3/4 to 10/4"))
        assertEquals("Monday the third of April", kokoroText("Monday 3/4"))
    }

    @Test
    fun nz_street_and_flat_addresses_read_as_house_numbers() {
        assertEquals("three, forty five Queen Street", kokoroText("3/45 Queen Street"))
        assertEquals("two, ten Ponsonby Road", kokoroText("2/10 Ponsonby Road"))
        assertEquals("unit three, forty five", kokoroText("Unit 3/45"))
        assertEquals("apt two, ten", kokoroText("Apt 2/10"))
    }

    @Test
    fun article_check_matches_whole_words_only() {
        assertEquals("Japan five kilometres away", kokoroText("Japan 5 km away"))
        assertEquals("Logan ten minutes drive", kokoroText("Logan 10 min drive"))
        assertEquals("the five kilometre walk", kokoroText("the 5 km walk"))
    }

    @Test
    fun clock_times_with_seconds_after_at_read_as_clock_times() {
        assertEquals("Starts at two thirty p m", kokoroText("Starts at 14:30:00"))
        assertEquals("Run time one hour and thirty minutes", kokoroText("Run time 1:30:00"))
    }

    @Test
    fun plus_and_times_forms_read_in_nz_English() {
        assertEquals("eighteen plus only", kokoroText("18+ only"))
        assertEquals("eighteen plus", kokoroText("18+"))
        assertEquals("three or more people", kokoroText("3+ people"))
        assertEquals("four by four ute", kokoroText("4x4 ute"))
    }

    @Test
    fun slash_numbers_after_a_cue_or_before_a_measure_word_read_as_fractions_not_dates() {
        assertEquals("Reduce the sauce by half", kokoroText("Reduce the sauce by 1/2"))
        assertEquals("Reduce by a third before serving", kokoroText("Reduce by 1/3 before serving"))
        assertEquals("after half an hour add salt", kokoroText("after 1/2 hour add salt"))
        assertEquals("after half an hour", kokoroText("after 1/2 an hour"))
        assertEquals("Use from half to three quarters of a cup of water", kokoroText("Use from 1/2 to 3/4 cup of water"))
        assertEquals("Between a third and half of users", kokoroText("Between 1/3 and 1/2 of users"))
        assertEquals("Fill until half full", kokoroText("Fill until 1/2 full"))
        assertEquals("Microwave on half power", kokoroText("Microwave on 1/2 power"))
        assertEquals("After a quarter of the year", kokoroText("After 1/4 of the year"))
        assertEquals("Set half a cup aside", kokoroText("Set 1/2 cup aside"))
        assertEquals("part half a cup water", kokoroText("part 1/2 cup water"))
        assertEquals("level half a teaspoon", kokoroText("level 1/2 teaspoon"))
    }

    @Test
    fun slash_numbers_after_a_cue_followed_by_the_or_a_read_as_dates() {
        assertEquals("On the fifth of December the team meets", kokoroText("On 5/12 the team meets"))
        assertEquals("Monday the third of April the shop opens late", kokoroText("Monday 3/4 the shop opens late"))
        assertEquals("Friday the sixth of August a new store opens", kokoroText("Friday 6/8 a new store opens"))
    }

    @Test
    fun mixed_numbers_read_as_a_whole_and_a_fraction() {
        assertEquals("Add one and a half cups flour", kokoroText("Add 1 1/2 cups flour"))
        assertEquals("two and a quarter tsp yeast", kokoroText("2 1/4 tsp yeast"))
        assertEquals("one and a half hours", kokoroText("1 1/2 hours"))
    }

    @Test
    fun tenths_for_ratings_read_out_of_ten() {
        assertEquals("Rated seven out of ten", kokoroText("Rated 7/10"))
        assertEquals("I'd give it eight out of ten", kokoroText("I'd give it 8/10"))
        assertEquals("nine out of ten dentists agree", kokoroText("9/10 dentists agree"))
    }

    @Test
    fun apt_with_a_dot_reads_as_a_flat_address() {
        assertEquals("apt two, ten", kokoroText("Apt. 2/10"))
    }

    @Test
    fun pixel_sizes_read_as_two_dimensions_joined_by_by() {
        assertEquals("nineteen twenty by ten eighty", kokoroText("1920x1080"))
    }

    @Test
    fun euro_and_pound_amounts_with_a_scale_suffix_read_the_scale() {
        assertEquals("two billion euros", kokoroText("€2bn"))
        assertEquals("three thousand pounds", kokoroText("£3k"))
        assertEquals("one point five million euros", kokoroText("€1.5m"))
    }

    @Test
    fun unit_after_an_article_stays_singular_across_a_range_and_the_needs_a_following_word() {
        assertEquals("A five to ten kilometre ride.", kokoroText("A 5–10 km ride."))
        assertEquals("five to ten kilometres", kokoroText("5–10 km"))
        assertEquals("I ran the ten kilometres.", kokoroText("I ran the 10 km."))
        assertEquals("the five kilometre walk", kokoroText("the 5 km walk"))
        assertEquals("the ten kilometres of road", kokoroText("the 10 km of road"))
        assertEquals("the ten kilometres to Wellington", kokoroText("the 10 km to Wellington"))
        assertEquals("Did you do the five kilometres and the ten kilometres?", kokoroText("Did you do the 5 km and the 10 km?"))
    }

    @Test
    fun integer_words_returns_non_ascii_digits_unchanged_instead_of_dropping_them() {
        assertEquals("\u0663", EnglishSpeechTextNormaliser.integerWords("\u0663"))
        assertEquals("\uFF13", EnglishSpeechTextNormaliser.integerWords("\uFF13"))
        assertEquals("", EnglishSpeechTextNormaliser.integerWords(""))
    }
}
