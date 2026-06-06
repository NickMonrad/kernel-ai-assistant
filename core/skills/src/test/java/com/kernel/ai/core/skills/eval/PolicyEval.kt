package com.kernel.ai.core.skills.eval

import com.kernel.ai.core.skills.SkillRegistry
import com.kernel.ai.core.skills.intent.CalendarSlotExtractor
import com.kernel.ai.core.skills.intent.ExtractionResult
import com.kernel.ai.core.skills.intent.IntentCandidate
import com.kernel.ai.core.skills.intent.IntentContractRegistry
import com.kernel.ai.core.skills.intent.IntentRecoveryOrchestrator
import com.kernel.ai.core.skills.intent.IntentSlotExtractor
import com.kernel.ai.core.skills.intent.RecoveryResult
import com.kernel.ai.core.skills.slot.SlotFillerManager
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

class PolicyEval : RecoveryEvalBase() {

    @Test
    fun `policy eval against golden corpus`() {
        val registry = IntentContractRegistry()
        val slotFillerManager = mockk<SlotFillerManager>(relaxed = true)
        val skillRegistry = mockk<SkillRegistry>(relaxed = true)
        val calendarExtractor = CalendarSlotExtractor()

        val fixtures = loadFixtures().filter { 3 in it.evalLayers }
        var passed = 0
        var dangerousAutoExecutes = 0
        val failures = mutableListOf<String>()

        for (fixture in fixtures) {
            // Mode B: precomputed slots → inject via mock extractor
            val orchestrator: IntentRecoveryOrchestrator
            if (fixture.precomputedSlots != null) {
                val mockExtractor = mockk<IntentSlotExtractor>(relaxed = true)
                every { mockExtractor.supports(fixture.candidateIntent) } returns true
                every { mockExtractor.extract(any(), any()) } returns
                    ExtractionResult.Extracted(fixture.precomputedSlots)
                orchestrator = IntentRecoveryOrchestrator(registry, slotFillerManager, skillRegistry, setOf(mockExtractor))
            } else {
                orchestrator = IntentRecoveryOrchestrator(registry, slotFillerManager, skillRegistry, setOf(calendarExtractor))
            }

            val candidate = IntentCandidate(fixture.candidateIntent, fixture.confidence, "eval")
            val result = orchestrator.recover("eval-conv", fixture.input, candidate)

            val resultDecision = when (result) {
                is RecoveryResult.Execute -> "Execute"
                is RecoveryResult.AskSlot -> "AskSlot"
                is RecoveryResult.AskConfirmation -> "AskConfirmation"
                is RecoveryResult.AskClarification -> "AskClarification"
                is RecoveryResult.NotActionable -> "NotActionable"
            }

            val expected = fixture.expectedPolicy.decision
            val decisionMatch = resultDecision == expected

            // Check missingSlot when AskSlot
            val missingSlotMatch = if (resultDecision == "AskSlot" && result is RecoveryResult.AskSlot) {
                val expectedMissingSlot = fixture.missingSlot
                if (expectedMissingSlot != null) result.missingSlot.name == expectedMissingSlot
                else true // no expectation set — don't fail on this
            } else true

            if (decisionMatch && missingSlotMatch) {
                passed++
            } else {
                val parts = mutableListOf<String>()
                if (!decisionMatch) parts.add("expected=$expected, got=$resultDecision")
                if (!missingSlotMatch) {
                    val actual = (result as? RecoveryResult.AskSlot)?.missingSlot?.name ?: "N/A"
                    parts.add("missingSlot: expected='${fixture.missingSlot}', got='$actual'")
                }
                failures.add("'${fixture.input}': ${parts.joinToString("; ")}")
            }

            // Track dangerous auto-executes
            if (result is RecoveryResult.Execute && fixture.expectedPolicy.confirmationRequired) {
                dangerousAutoExecutes++
                failures.add("DANGEROUS FP: '${fixture.input}' auto-executed despite confirmationRequired=true")
            }
        }

        val notActionableCount = fixtures.count { it.expectedPolicy.decision == "NotActionable" }
        val scorecard = Scorecard(
            totalFixtures = fixtures.size,
            passedFixtures = passed,
            failedFixtures = failures.size + dangerousAutoExecutes,
            scoresByCategory = mapOf<String, ScoreDetail>(
                "layer3_policy" to ScoreDetail(fixtures.size, passed, "Layer 3 — Policy accuracy"),
                "layer3_dangerous_fp" to ScoreDetail(
                    total = fixtures.size,
                    passed = fixtures.size - dangerousAutoExecutes,
                    label = "Layer 3 — Dangerous FP (must be 0)",
                ),
                "layer3_not_actionable" to ScoreDetail(
                    total = notActionableCount,
                    passed = fixtures.filter { it.expectedPolicy.decision == "NotActionable" }
                        .count { f ->
                            val candidate = IntentCandidate(f.candidateIntent, f.confidence, "eval")
                            val result = IntentRecoveryOrchestrator(
                                registry, slotFillerManager, skillRegistry, setOf(calendarExtractor)
                            ).recover("eval-conv", f.input, candidate)
                            result is RecoveryResult.NotActionable
                        },
                    label = "Layer 3 — NotActionable correct rejections",
                ),
            ),
        )
        printScorecard(scorecard)

        check(dangerousAutoExecutes == 0) {
            "Dangerous false positives detected: $dangerousAutoExecutes auto-executes when confirmation required"
        }

        if (failures.isNotEmpty()) {
            throw AssertionError("Policy eval failures (${failures.size}/${fixtures.size}):\n  ${failures.joinToString("\n  ")}")
        }
    }
}
