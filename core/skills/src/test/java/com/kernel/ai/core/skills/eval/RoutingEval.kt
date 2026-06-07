package com.kernel.ai.core.skills.eval

import com.kernel.ai.core.skills.QuickIntentRouter
import org.junit.jupiter.api.Test

class RoutingEval : RecoveryEvalBase() {

    private val router = QuickIntentRouter()

    @Test
    fun `routing accuracy eval against golden corpus`() {
        val fixtures = loadFixtures().filter { 1 in it.evalLayers }
        var passed = 0
        val messages = mutableListOf<String>()

        for (fixture in fixtures) {
            val result = router.route(fixture.input)
            val bestGuess = when (result) {
                is QuickIntentRouter.RouteResult.RegexMatch -> result.intent.intentName
                is QuickIntentRouter.RouteResult.ClassifierMatch -> result.intent.intentName
                is QuickIntentRouter.RouteResult.NeedsSlot -> result.intent.intentName
                is QuickIntentRouter.RouteResult.FallThrough -> result.bestGuess?.intentName
            }
            if (bestGuess == fixture.candidateIntent) {
                passed++
            } else {
                messages.add("'${fixture.input}': expected=${fixture.candidateIntent}, got=$bestGuess")
            }
        }

        val byCategory = mapOf<String, ScoreDetail>(
            "Layer 1 — Routing" to ScoreDetail(fixtures.size, passed, "Routing accuracy")
        )
        val scorecard = Scorecard(
            totalFixtures = fixtures.size,
            passedFixtures = passed,
            failedFixtures = messages.size,
            scoresByCategory = byCategory,
        )
        printScorecard(scorecard)

        if (messages.isNotEmpty()) {
            throw AssertionError("Routing eval failures (${messages.size}/${fixtures.size}):\n  ${messages.joinToString("\n  ")}")
        }
    }
}
