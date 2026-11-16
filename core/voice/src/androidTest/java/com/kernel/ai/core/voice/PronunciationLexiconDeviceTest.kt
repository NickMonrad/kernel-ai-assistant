package com.kernel.ai.core.voice

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on a device because JVM unit tests use a different regex engine from Android's ICU; a JVM-only
 * construct such as `(?U:...)` (#1968) passes JVM tests and fails only on device. Three failure modes
 * are caught by these asserts:
 * - a bad pattern in `PronunciationLexicon` static init throws `ExceptionInInitializerError` from
 *   `ensureLoaded`, which only the test sees, since `prepare` does not catch it;
 * - a `Pattern.compile` error inside `install()` is caught by `ensureLoaded`, leaving an empty lexicon,
 *   which the exact-output assertion catches;
 * - a `RuntimeException` at apply time is swallowed by `prepare`, which the exact-output assertion catches.
 */
@RunWith(AndroidJUnit4::class)
class PronunciationLexiconDeviceTest {

    @Test
    fun shippedLexiconAndSherpaPreparationRunOnAndroidRegex() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PronunciationLexicon.ensureLoaded(context)

        // "Te\u00A0Awamutu" checks a no-break space between the words of a multi-word place name.
        assertEquals(
            "Kee-ora, Jandle, from Tee Ah-wa-moo-too.",
            SherpaSpeechText.prepare("Kia ora, Jandal, from Te\u00A0Awamutu."),
        )
        // Plain-number, money and clock-time patterns use \w, \d and lookarounds (the same engine
        // difference), so each gets one representative input from the JVM unit-test corpus.
        assertEquals("It costs three hundred and forty five dollars.", SherpaSpeechText.prepare("It costs $345"))
        assertEquals("It's eight thirty one A-M.", SherpaSpeechText.prepare("It's 8:31 am"))
        assertEquals("Room one hundred and one and two cats.", SherpaSpeechText.prepare("Room 101 and 2 cats"))
    }
}
