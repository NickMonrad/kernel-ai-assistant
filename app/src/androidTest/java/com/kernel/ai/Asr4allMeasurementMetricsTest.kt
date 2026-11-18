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
    @Test fun aggregateGroupsCandidateConfigsIndependently() {
        val grouped = Asr4allMeasurementMetrics.aggregateBySystem(listOf(
            Asr4allMeasurementMetrics.SystemWer("S", "c16r4", Asr4allMeasurementMetrics.wer("one two", "one")),
            Asr4allMeasurementMetrics.SystemWer("S", "c16r4", Asr4allMeasurementMetrics.wer("three", "three four")),
            Asr4allMeasurementMetrics.SystemWer("zipformer", "shipping", Asr4allMeasurementMetrics.wer("a", "b")),
        ))
        assertEquals(2, grouped.size)
        assertEquals(2.0 / 3.0, grouped.getValue("S/c16r4").rate, 0.0)
        assertEquals(1.0, grouped.getValue("zipformer/shipping").rate, 0.0)
    }
}
