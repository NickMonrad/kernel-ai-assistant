package com.kernel.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class Asr4allMeasurementMetricsTest {
    @Test fun normalizationUsesNfcCasePunctuationAndApostrophePolicy() {
        assertEquals(
            Asr4allMeasurementMetrics.Wer(0, 3),
            Asr4allMeasurementMetrics.wer("Kia ora, Pūkekohe!", "KIA ORA Pūkekohe"),
        )
        assertEquals(Asr4allMeasurementMetrics.Wer(0, 1), Asr4allMeasurementMetrics.wer("what’s", "whats"))
        assertEquals(Asr4allMeasurementMetrics.Wer(0, 1), Asr4allMeasurementMetrics.wer("what's", "whats"))
        assertEquals(Asr4allMeasurementMetrics.Wer(0, 1), Asr4allMeasurementMetrics.wer("Pūkekohe", "Pu\u0304kekohe"))
        assertEquals(Asr4allMeasurementMetrics.Wer(1, 1), Asr4allMeasurementMetrics.wer("Taupō", "Taupo"))
    }

    @Test fun summarizeWeightsWerAndDoesNotMixLatencyOriginsOrSets() {
        val summaries = Asr4allMeasurementMetrics.summarize(
            listOf(
                Asr4allMeasurementMetrics.Row(
                    "owner-decision", "S", "c16r4", "forced_clip_flush_final",
                    Asr4allMeasurementMetrics.wer("a b", "a"), 120.0, null,
                ),
                Asr4allMeasurementMetrics.Row(
                    "owner-decision", "S", "c16r4", "no_final",
                    Asr4allMeasurementMetrics.wer("c d", ""), null, null,
                ),
                Asr4allMeasurementMetrics.Row(
                    "owner-decision", "S", "c16r4", "decoder_endpoint_final",
                    Asr4allMeasurementMetrics.wer("e", "e"), null, 40.0,
                ),
                Asr4allMeasurementMetrics.Row(
                    "owner-decision", "M", "c16r4", "decoder_endpoint_final",
                    Asr4allMeasurementMetrics.wer("a b", "a b"), null, 90.0,
                ),
                Asr4allMeasurementMetrics.Row(
                    "owner-decision", "S", "c16r4", "error", null, null, null,
                ),
                Asr4allMeasurementMetrics.Row(
                    "owner-decision", "S", "c32r8", "forced_clip_flush_final",
                    Asr4allMeasurementMetrics.wer("x", "x"), 60.0, null,
                ),
                Asr4allMeasurementMetrics.Row(
                    "voice-cloned-breadth", "S", "c16r4", "error", null, null, null,
                ),
            ),
        )
        val ownerS = summaries.single { it.set == "owner-decision" && it.model == "S" && it.tier == "c16r4" }
        assertEquals(3, ownerS.wer?.edits)
        assertEquals(5, ownerS.wer?.referenceWords)
        assertEquals(0.6, ownerS.wer?.rate ?: -1.0, 0.0)
        assertEquals(4, ownerS.rows)
        assertEquals(2, ownerS.finals)
        assertEquals(1, ownerS.noFinal)
        assertEquals(1, ownerS.errors)
        assertEquals(3, ownerS.werCoverage)
        assertEquals(1, ownerS.clipEndLatencyCount)
        assertEquals(120.0, ownerS.meanClipEndLatencyMs ?: -1.0, 0.0)
        assertEquals(1, ownerS.endpointLatencyCount)
        assertEquals(40.0, ownerS.meanEndpointLatencyMs ?: -1.0, 0.0)
        val ownerSOtherTier = summaries.single { it.set == "owner-decision" && it.model == "S" && it.tier == "c32r8" }
        assertEquals(0, ownerSOtherTier.wer?.edits)
        assertEquals(1, ownerSOtherTier.clipEndLatencyCount)
        val breadth = summaries.single { it.set == "voice-cloned-breadth" }
        assertEquals(null, breadth.wer)
        assertEquals(0, breadth.werCoverage)
        assertEquals(1, breadth.errors)
        assertEquals(0, breadth.clipEndLatencyCount)
        assertEquals(0, breadth.endpointLatencyCount)
    }

    @Test fun counterbalancingRotatesEachLaneThroughEveryPosition() {
        val lanes = listOf(
            Asr4allMeasurementMetrics.Lane("S", "c16r4"),
            Asr4allMeasurementMetrics.Lane("S", "c32r8"),
            Asr4allMeasurementMetrics.Lane("M", "c16r4"),
            Asr4allMeasurementMetrics.Lane("M", "c32r8"),
            Asr4allMeasurementMetrics.Lane("zipformer", "shipping"),
        )
        val runs = lanes.indices.map { Asr4allMeasurementMetrics.counterbalanced(lanes, it) }
        assertEquals(5, runs.map { it.first() }.distinct().size)
        for (lane in lanes) assertEquals(5, runs.count { lane in it })
        for (position in lanes.indices) assertEquals(5, runs.map { it[position] }.distinct().size)
    }
}
