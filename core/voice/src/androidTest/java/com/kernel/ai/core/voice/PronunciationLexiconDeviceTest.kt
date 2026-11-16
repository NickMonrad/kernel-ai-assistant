package com.kernel.ai.core.voice

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on a device/emulator because the JVM unit tests use a different regex engine from Android's
 * ICU. A JVM-only construct (e.g. the `(?U:...)` inline flag, #1968) passes JVM tests and only fails
 * here, at `PronunciationLexicon` static init. `SherpaSpeechText.prepare` swallows RuntimeException,
 * so the exact-output assertions are what catch a silently skipped lexicon.
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
    }
}
