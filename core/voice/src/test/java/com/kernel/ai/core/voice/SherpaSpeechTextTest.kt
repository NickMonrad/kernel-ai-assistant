package com.kernel.ai.core.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.api.Test

class SherpaSpeechTextTest {
    @Test
    fun `date difference reply from issue 1751`() {
        assertEquals(
            "one hundred and twenty six days from now, eighteen weeks, Sunday, the 14th of March 2027.",
            SherpaSpeechText.prepare("126 days from now (18 weeks) — Sunday, 14 March 2027."),
        )
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '`',
        value = [
            "It's 8:31 am                                | It's eight thirty one a.m.",
            "It's 8:31 am (NZT)                          | It's eight thirty one a.m. NZT.",
            "Done!                                       | Done!",
            "Next,                                       | Next.",
            "Added \"bananas\" to your shopping list.      | Added \"bananas\" to your shopping list.",
            "It's -3 degrees Celsius                     | It's -3 degrees Celsius.",
            "In Wellington, it's 17°C, high of 19 degrees | In Wellington, it's 17°C, high of nineteen degrees.",
            "That's 15,000 steps                         | That's fifteen thousand steps.",
            "1,234.5 km away                             | 1,234.5 km away.",
            "Call 0211234567                             | Call 0211234567.",
            "Call 021 123 4567                           | Call 021 123 4567.",
            "Born in 1985, moved in 2020                 | Born in 1985, moved in 2020.",
            "It costs $345                               | It costs three hundred and forty five dollars.",
            "About 50% done                              | About 50% done.",
            "Version 2.14.1                              | Version 2.14.1.",
            "See https://example.com/page-2              | See https://example.com/page-2.",
            "Takes 5-10 minutes                          | Takes five to ten minutes.",
            "It takes 5–10 minutes                       | It takes five to ten minutes.",
            "Open 9–5                                    | Open nine to five.",
            "Open 14-16 March                            | Open the 14th to the 16th of March.",
            "14 – 16 March                               | the 14th to the 16th of March.",
            "On 2026-11-08                               | On 2026-11-08.",
            "Call 021-123-4567                           | Call 021-123-4567.",
            "(Note: it's late.) Then                     | Note: it's late. Then.",
            "Read (see note (a) below) now               | Read, see note, a, below, now.",
            "Source [1].                                 | Source.",
            "It's −3 out and ¥500                        | It's −3 out and ¥500.",
            "The 3rd item                                | The 3rd item.",
            "Room 101 and 2 cats                         | Room one hundred and one and two cats.",
            "That is VERY good                           | That is VERY good.",
            "Kia ora, 1 June works                       | Kia ora, the 1st of June works.",
        ],
    )
    fun `only plain integers, money, times, dates, brackets, dashes and the final pause change`(input: String, expected: String) {
        assertEquals(expected, SherpaSpeechText.prepare(input))
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        delimiter = '|',
        value = [
            // #1485 / #1487 fixed corpus
            "$49.95            | forty nine dollars and ninety five cents.",
            "$12.75            | twelve dollars and seventy five cents.",
            "$21.50            | twenty one dollars and fifty cents.",
            "8:00              | eight o'clock.",
            "4:00              | four o'clock.",
            "7:00              | seven o'clock.",
            // neighbouring forms
            "$1                | one dollar.",
            "$0.99             | ninety nine cents.",
            "$1.01             | one dollar and one cent.",
            "$5.00             | five dollars.",
            "$1,200            | one thousand two hundred dollars.",
            "7:30 pm           | seven thirty p.m.",
            "7:05am            | seven oh five a.m.",
            "14:00             | fourteen hundred.",
            "14:30             | fourteen thirty.",
            "Ratio 3:2         | Ratio 3:2.",
            "At 10:15:30       | At 10:15:30.",
        ],
    )
    fun `money and clock times are spelled out for Piper`(input: String, expected: String) {
        assertEquals(expected, SherpaSpeechText.prepare(input))
    }

    @Test
    fun `money and times keep surrounding punctuation`() {
        assertEquals(
            "Your alarm is set for eight o'clock, and lunch is twelve dollars and seventy five cents.",
            SherpaSpeechText.prepare("Your alarm is set for 8:00, and lunch is $12.75."),
        )
    }

    @Test
    fun `earlier streaming chunks keep the chunker's ending`() {
        assertEquals("I found three things,", SherpaSpeechText.prepare("I found 3 things,", chunkEnd = false))
        assertEquals("and then", SherpaSpeechText.prepare("and then", chunkEnd = false))
    }

    @Test
    fun `words cover the integer range used`() {
        assertEquals("zero", SherpaSpeechText.words(0))
        assertEquals("forty", SherpaSpeechText.words(40))
        assertEquals("one thousand and five", SherpaSpeechText.words(1005))
        assertEquals("twelve thousand three hundred and forty five", SherpaSpeechText.words(12_345))
        assertEquals("nine hundred and ninety nine million nine hundred and ninety nine thousand nine hundred and ninety nine",
            SherpaSpeechText.words(999_999_999))
    }

    @Test
    fun `blank text stays blank`() {
        assertEquals("", SherpaSpeechText.prepare("   "))
    }
}
