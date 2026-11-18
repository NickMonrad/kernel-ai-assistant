package com.kernel.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class Asr4allMeasurementMetricsTest {
    @Test fun normalizationIgnoresCaseAndPunctuationButPreservesMacrons() {
        assertEquals(Asr4allMeasurementMetrics.Wer(0, 3), Asr4allMeasurementMetrics.wer("Kia ora, Pūkekohe!", "KIA ORA Pūkekohe"))
        assertEquals(Asr4allMeasurementMetrics.Wer(1, 1), Asr4allMeasurementMetrics.wer("Taupō", "Taupo"))
    }

    @Test fun aggregateUsesEditAndReferenceWordTotals() {
        val aggregate = Asr4allMeasurementMetrics.aggregate(listOf(
            Asr4allMeasurementMetrics.wer("one two", "one"),
            Asr4allMeasurementMetrics.wer("three", "three four"),
        ))
        assertEquals(2, aggregate.edits)
        assertEquals(3, aggregate.referenceWords)
        assertEquals(2.0 / 3.0, aggregate.rate, 0.0)
    }
}
