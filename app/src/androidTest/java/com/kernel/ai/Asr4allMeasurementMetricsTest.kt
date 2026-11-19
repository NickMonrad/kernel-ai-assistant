package com.kernel.ai

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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

    @Test fun phraseParserUsesSeparateOwnerAndBreadthSchemas() {
        assertEquals(
            mapOf("A-01" to "Kia ora Jandal."),
            parsePhraseFile(
                """{"schema_version":1,"phrases":[{"id":"A-01","text":"Kia ora Jandal."}]}""",
                "owner-decision",
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            parsePhraseFile(
                """{"schema_version":1,"set":"owner-decision","synthetic":false,"decision_set":true,"phrases":[{"id":"A-01","text":"Kia ora"}]}""",
                "owner-decision",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            parsePhraseFile(
                """{"schema_version":1,"phrases":[{"id":"A-01","text":"Kia ora","skill":"extra"}]}""",
                "owner-decision",
            )
        }
        assertEquals(
            mapOf("CL-001" to "Set an alarm for six thirty tomorrow."),
            parsePhraseFile(
                """{"schema_version":1,"issue":1984,"set":"voice-cloned-breadth","synthetic":true,"decision_set":false,"note":"breadth only","phrases":[{"id":"CL-001","skill":"alarm","text":"Set an alarm for six thirty tomorrow."}]}""",
                "voice-cloned-breadth",
            ),
        )
    }

    private fun parsePhraseFile(contents: String, role: String): Map<String, String> {
        val file = File.createTempFile("phrase-schema-", ".json")
        return try {
            file.writeText(contents)
            Asr4allMeasurementMetrics.parsePhraseReferences(file.readBytes(), role)
        } finally {
            file.delete()
        }
    }

    @Test fun durationsUseIntegerNearestMillisecondTiesToEvenAndRejectMismatch() {
        assertEquals(2_500L, Asr4allMeasurementMetrics.roundDurationMsTiesToEven(40_008, 16_000))
        assertEquals(2_502L, Asr4allMeasurementMetrics.roundDurationMsTiesToEven(40_024, 16_000))
        assertEquals(2_501L, Asr4allMeasurementMetrics.roundDurationMsTiesToEven(40_020, 16_000))

        Asr4allMeasurementMetrics.requireDurationMatches(40_008, 16_000, 2_500)
        Asr4allMeasurementMetrics.requireDurationMatches(40_024, 16_000, 2_502)
        assertThrows(IllegalArgumentException::class.java) {
            Asr4allMeasurementMetrics.requireDurationMatches(40_020, 16_000, 2_502)
        }
    }

    @Test fun endpointTrialDoesNotAttributeBlankEndpointFlushOrNoEndpointFlushAsEndpoint() {
        assertEquals(
            Asr4allMeasurementMetrics.EndpointTrialResult("no_final", null, forcedClipFlush = false),
            Asr4allMeasurementMetrics.endpointTrialResult(
                endpointObserved = true, finalPresent = false, endpointLatencyMs = 15.0,
            ),
        )
        assertEquals(
            Asr4allMeasurementMetrics.EndpointTrialResult(
                "forced_clip_flush_final", null, forcedClipFlush = true,
            ),
            Asr4allMeasurementMetrics.endpointTrialResult(
                endpointObserved = false, finalPresent = true, endpointLatencyMs = null,
            ),
        )
    }

    @Test fun summariesKeepLatencyOriginsAndSetsSeparate() {
        val summaries = Asr4allMeasurementMetrics.summarize(
            listOf(
                Asr4allMeasurementMetrics.Row(
                    "owner-decision", "S", "c16r4", "forced_clip_flush_final",
                    Asr4allMeasurementMetrics.wer("a b", "a"), 120.0, null, 70.0,
                ),
                Asr4allMeasurementMetrics.Row(
                    "owner-decision", "S", "c16r4", "no_final",
                    Asr4allMeasurementMetrics.wer("c d", ""), 220.0, 320.0, 420.0,
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
                    "owner-decision", "S", "c16r4", "forced_clip_flush_final",
                    null, 820.0, null, 920.0, finalPresent = false,
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
        assertEquals(5, ownerS.rows)
        assertEquals(2, ownerS.finals)
        assertEquals(1, ownerS.noFinal)
        assertEquals(1, ownerS.errors)
        assertEquals(3, ownerS.werCoverage)
        assertEquals(1, ownerS.clipEndLatencyCount)
        assertEquals(120.0, ownerS.meanClipEndLatencyMs ?: -1.0, 0.0)
        assertEquals(1, ownerS.endpointLatencyCount)
        assertEquals(40.0, ownerS.meanEndpointLatencyMs ?: -1.0, 0.0)
        assertEquals(1, ownerS.speechEndLatencyCount)
        assertEquals(70.0, ownerS.meanSpeechEndLatencyMs ?: -1.0, 0.0)
        val ownerSOtherTier = summaries.single { it.set == "owner-decision" && it.model == "S" && it.tier == "c32r8" }
        assertEquals(1, ownerSOtherTier.clipEndLatencyCount)
        val breadth = summaries.single { it.set == "voice-cloned-breadth" }
        assertEquals(0, breadth.werCoverage)
        assertEquals(1, breadth.errors)
        assertEquals(0, breadth.clipEndLatencyCount)
        assertEquals(0, breadth.endpointLatencyCount)
    }

    @Test fun pairedDecisionAggregatesPerLaneAndScoresBlankFinalAsDeletions() {
        val lanes = listOf(Asr4allMeasurementMetrics.Lane("S", "c16r4"), Asr4allMeasurementMetrics.Lane("zipformer", "shipping"))
        val rows = buildList {
            for (lane in lanes) {
                add(decisionRow(lane, "f1", "0".repeat(64), "no_final", Asr4allMeasurementMetrics.wer("a b c", "")))
                add(decisionRow(lane, "f2", "1".repeat(64), "forced_clip_flush_final", Asr4allMeasurementMetrics.Wer(0, 2)))
            }
        }
        val result = Asr4allMeasurementMetrics.pairedDecision(rows, setOf("f1", "f2"), 1, lanes)
        assertEquals(true, result.decisionValid)
        val small = result.lanes.single { it.model == "S" }
        assertEquals(Asr4allMeasurementMetrics.Wer(3, 5), small.wer)
        val zip = result.lanes.single { it.model == "zipformer" }
        assertEquals(Asr4allMeasurementMetrics.Wer(3, 5), zip.wer)
    }

    @Test fun pairedDecisionFailsClosedForErrorsMissingRowsAndDifferentPcm() {
        val laneA = Asr4allMeasurementMetrics.Lane("S", "c16r4")
        val laneB = Asr4allMeasurementMetrics.Lane("zipformer", "shipping")
        val lanes = listOf(laneA, laneB)
        val expectedHash = "a".repeat(64)
        val validA = decisionRow(laneA, "f1", expectedHash, "forced_clip_flush_final", Asr4allMeasurementMetrics.Wer(0, 1))
        val validB = decisionRow(laneB, "f1", expectedHash, "forced_clip_flush_final", Asr4allMeasurementMetrics.Wer(0, 1))
        val error = decisionRow(laneB, "f1", expectedHash, "error", null)
        val missing = Asr4allMeasurementMetrics.pairedDecision(listOf(validA), setOf("f1"), 1, lanes)
        assertEquals(false, missing.decisionValid)
        assertEquals(null, missing.lanes.first().wer)
        val errored = Asr4allMeasurementMetrics.pairedDecision(listOf(validA, error), setOf("f1"), 1, lanes)
        assertEquals(false, errored.decisionValid)
        val mismatch = Asr4allMeasurementMetrics.pairedDecision(
            listOf(validA, validB.copy(pairedPcmSha256 = "b".repeat(64))), setOf("f1"), 1, lanes,
        )
        assertEquals(false, mismatch.decisionValid)
        assertEquals(1, mismatch.inputMismatchKeys)
        val unexpectedSet = Asr4allMeasurementMetrics.pairedDecision(
            listOf(validA, validB, validA.copy(set = "voice-cloned-breadth")), setOf("f1"), 1, lanes,
        )
        assertEquals(false, unexpectedSet.decisionValid)
        assertEquals(1, unexpectedSet.unexpectedLaneRows)
    }

    private fun decisionRow(
        lane: Asr4allMeasurementMetrics.Lane,
        id: String,
        hash: String,
        outcome: String,
        wer: Asr4allMeasurementMetrics.Wer?,
    ) = Asr4allMeasurementMetrics.DecisionRow(
        set = "owner-decision", model = lane.model, tier = lane.tier,
        fixtureId = id, repetition = 0, pairedPcmSha256 = hash, outcome = outcome, wer = wer,
    )

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
