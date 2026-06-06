package com.kernel.ai.core.skills.eval

import com.kernel.ai.core.skills.intent.CalendarSlotExtractor
import com.kernel.ai.core.skills.intent.ExtractionResult
import org.junit.jupiter.api.Test

class SlotExtractionEval : RecoveryEvalBase() {

    private val extractor = CalendarSlotExtractor()
    private val contract = dummyContract("create_calendar_event", "MEDIUM")

    @Test
    fun `slot extraction eval against golden corpus`() {
        val fixtures = loadFixtures().filter { 2 in it.evalLayers }
        var exactPassed = 0
        var partialPassed = 0
        var naFromExtractor = 0
        val failures = mutableListOf<String>()

        for (fixture in fixtures) {
            val result = extractor.extract(fixture.input, contract)

            // ── NotActionable assertion ──
            // Only pass NotActionable when the fixture actually expects it
            val expectsNotActionable = fixture.expectedPolicy.decision == "NotActionable"
            if (result is ExtractionResult.NotActionable) {
                if (expectsNotActionable) {
                    naFromExtractor++; exactPassed++; partialPassed++
                } else {
                    failures.add("'${fixture.input}': unexpected NotActionable")
                }
                continue
            }
            if (expectsNotActionable) {
                failures.add("'${fixture.input}': expected NotActionable but got Extracted")
                continue
            }

            // ── Empty slots assertion ──
            // Assert that the extractor returned Extracted with empty params
            if (fixture.expectedSlots.isEmpty()) {
                val emptyParams = (result as ExtractionResult.Extracted).params
                if (emptyParams.isEmpty()) {
                    exactPassed++; partialPassed++
                } else {
                    failures.add("'${fixture.input}': expected empty slots but got $emptyParams")
                }
                continue
            }

            val params = (result as ExtractionResult.Extracted).params
            val exactMatch = fixture.expectedSlots.all { (key, value) ->
                params[key]?.lowercase() == value.lowercase()
            }
            if (exactMatch) {
                exactPassed++
            }

            val partialMatch = fixture.expectedSlots.keys.all { key ->
                params.containsKey(key)
            }
            if (partialMatch) {
                partialPassed++
            }

            if (!exactMatch) {
                val details = fixture.expectedSlots.map { (k, v) ->
                    "$k: expected='$v', got='${params[k]}'"
                }
                failures.add("'${fixture.input}': slot mismatch — ${details.joinToString(", ")}")
            }
        }

        val scorecard = Scorecard(
            totalFixtures = fixtures.size,
            passedFixtures = exactPassed,
            failedFixtures = failures.size,
            scoresByCategory = mapOf<String, ScoreDetail>(
                "layer2_slot_exact" to ScoreDetail(fixtures.size, exactPassed, "Layer 2 — Slot exact match"),
                "layer2_slot_partial" to ScoreDetail(fixtures.size, partialPassed, "Layer 2 — Slot partial match"),
                "layer2_not_actionable" to ScoreDetail(
                    fixtures.count { f ->
                        extractor.extract(f.input, contract) is ExtractionResult.NotActionable
                    },
                    naFromExtractor,
                    "Layer 2 — NotActionable from extractor",
                ),
            ),
        )
        printScorecard(scorecard)

        if (failures.isNotEmpty()) {
            throw AssertionError("Slot extraction failures (${failures.size}/${fixtures.size}):\n  ${failures.joinToString("\n  ")}")
        }
    }
}
