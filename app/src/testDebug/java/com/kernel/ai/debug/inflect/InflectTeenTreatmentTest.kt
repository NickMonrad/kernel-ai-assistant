package com.kernel.ai.debug.inflect

import com.kernel.ai.debug.inflect.InflectListeningVariants.TeenTreatment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InflectTeenTreatmentTest {
    /** Raw eSpeak output for "<teen> thousand people", captured on the S23U (text path, no overrides). */
    private val rawTeens = linkedMapOf(
        "thirteen" to "θˈɜːtiːn",
        "fourteen" to "fˈoːɹtiːn",
        "fifteen" to "fˈɪftiːn",
        "sixteen" to "sˈɪkstiːn",
        "seventeen" to "sˈɛvəntˌiːn",
        "eighteen" to "ˈeɪtiːn",
        "nineteen" to "nˈaɪntiːn",
    )
    private val thousandPeople = "θˈaʊzənd pˈiːpəl"

    @Test
    fun `fifteen gets the exact phonemes for each treatment and nothing else changes`() {
        val raw = "fˈɪftiːn $thousandPeople"

        assertEquals("fˈɪftˈiːn $thousandPeople", InflectListeningVariants.rewriteTeen(TeenTreatment.V5, raw))
        assertEquals("fɪftˈiːːn $thousandPeople", InflectListeningVariants.rewriteTeen(TeenTreatment.TEEN_LONG, raw))
        assertEquals("fˈɪf tˈiːn $thousandPeople", InflectListeningVariants.rewriteTeen(TeenTreatment.FIF_TEEN, raw))
        assertEquals("fˈɪftˈiːn, $thousandPeople", InflectListeningVariants.rewriteTeen(TeenTreatment.COMMA, raw))
        assertEquals("fˈɪftˈiːnə $thousandPeople", InflectListeningVariants.rewriteTeen(TeenTreatment.SCHWA, raw))
    }

    @Test
    fun `every teen 13 to 19 has its tiːn token rewritten and the trailing phrase is untouched`() {
        for ((word, raw) in rawTeens) {
            for (treatment in TeenTreatment.values()) {
                val out = InflectListeningVariants.rewriteTeen(treatment, "$raw $thousandPeople")
                assertTrue(out.endsWith(thousandPeople), "$word/$treatment changed the trailing phrase: $out")
                assertFalse(out.contains("tiːn") || out.contains("tˌiːn"), "$word/$treatment kept unstressed tiːn: $out")
                assertTrue(out.contains("tˈiːn") || out.contains("tˈiːːn") || out.contains("tˈiːnə"),
                    "$word/$treatment has no rewritten teen token: $out")
            }
        }
    }
}
