package com.kernel.ai.core.voice

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the #2192 unit rules on a device. Android's `java.util.regex` is ICU-backed while JVM unit tests use
 * the JDK engine, so a construct that compiles or matches differently on Android passes JVM tests and fails
 * only here. One input per rule: glued and spaced units, feet and inches, fraction units, and the bracketed
 * conversion in [SpeechCardText.speakable]. Expected strings match the JVM unit tests.
 */
@RunWith(AndroidJUnit4::class)
class SpeechUnitRegexDeviceTest {

    @Test
    fun miles_and_inches_read_as_words_on_android_regex() {
        assertEquals("Walk fourteen miles north.", EnglishSpeechTextNormaliser.normalize("Walk 14 mi north."))
        assertEquals("twelve inches", EnglishSpeechTextNormaliser.normalize("12in"))
        assertEquals("They let four in.", EnglishSpeechTextNormaliser.normalize("They let 4 in."))
        assertEquals(
            "Born in nineteen ninety in Auckland",
            EnglishSpeechTextNormaliser.normalize("Born in 1990 in Auckland"),
        )
    }

    @Test
    fun feet_inches_and_fraction_units_read_on_android_regex() {
        assertEquals("five feet ten inches tall", EnglishSpeechTextNormaliser.normalize("5 ft 10 in tall"))
        assertEquals("half a mile", EnglishSpeechTextNormaliser.normalize("1/2 mi"))
        assertEquals("one and a half miles", EnglishSpeechTextNormaliser.normalize("1 1/2 mi"))
    }

    @Test
    fun bare_in_and_plural_fraction_units_read_on_android_regex() {
        assertEquals("She was five feet four in nineteen ninety.", EnglishSpeechTextNormaliser.normalize("She was 5 feet 4 in 1990."))
        assertEquals("half a yard", EnglishSpeechTextNormaliser.normalize("1/2 yards"))
        assertEquals("three quarters of a foot", EnglishSpeechTextNormaliser.normalize("3/4 feet"))
    }

    @Test
    fun bracketed_conversion_keeps_first_unit_on_android_regex() {
        assertEquals(
            "Mount Ruapehu is 23 km northeast of Ohakune.",
            SpeechCardText.speakable("Mount Ruapehu is 23 km (14 mi) northeast of Ohakune."),
        )
        assertEquals(
            "Mount Ruapehu (2,797 m) is an active volcano.",
            SpeechCardText.speakable("Mount Ruapehu (2,797 m) is an active volcano."),
        )
    }
}
