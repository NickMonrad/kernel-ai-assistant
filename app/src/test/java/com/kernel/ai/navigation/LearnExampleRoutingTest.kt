package com.kernel.ai.navigation

import com.kernel.ai.core.skills.QuickIntentRouter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

/**
 * Catalogue-driven QIR routing tests.
 *
 * Enumerates every [LearnExample] in [allLearnExamples] and asserts routing
 * behaviour matches the [ExpectedLearnMode] declaration.
 *
 * Uses the **real catalogue** — not a duplicate list — so adding a new
 * deterministic example without QIR support fails tests immediately.
 */
class LearnExampleRoutingTest {

    private lateinit var router: QuickIntentRouter

    @BeforeEach
    fun setup() {
        router = QuickIntentRouter()
    }

    // ── QirIntent — must route deterministically ─────────────────────────────

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("qirIntentExamples")
    fun `QirIntent examples route to expected intent`(ex: LearnExample) {
        val result = router.route(ex.prompt)
        // QirIntent must NOT need slot-fill — that would be QirSlotFill
        if (result is QuickIntentRouter.RouteResult.NeedsSlot) {
            throw AssertionError(
                "Example '${ex.id}' (prompt='${ex.prompt}') is marked QirIntent but returned NeedsSlot" +
                    " for '${result.intent.intentName}' (missing: ${result.missingSlot.name})." +
                    " Change to QirSlotFill with expectedMissingSlot=\"${result.missingSlot.name}\"."
            )
        }
        val intentName = routeResultToIntentName(result)
        assertNotNull(intentName) {
            "Expected QIR route for '${ex.id}' (prompt='${ex.prompt}'), but got FallThrough"
        }
        assertEquals(ex.expectedRoute, intentName) {
            "Example '${ex.id}' expected route '${ex.expectedRoute}' but got '$intentName'"
        }
    }

    // ── QirSlotFill — must route deterministically with NeedsSlot ───────────

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("qirSlotFillExamples")
    fun `QirSlotFill examples route to expected intent with NeedsSlot`(ex: LearnExample) {
        val result = router.route(ex.prompt)
        assertNotNull(ex.expectedMissingSlot) {
            "QirSlotFill '${ex.id}' must declare expectedMissingSlot"
        }
        when (result) {
            is QuickIntentRouter.RouteResult.FallThrough -> {
                throw AssertionError(
                    "Expected QIR slot-fill route for '${ex.id}' (prompt='${ex.prompt}'), but got FallThrough"
                )
            }
            is QuickIntentRouter.RouteResult.NeedsSlot -> {
                assertEquals(ex.expectedRoute, result.intent.intentName) {
                    "Example '${ex.id}' expected route '${ex.expectedRoute}' but got '${result.intent.intentName}'"
                }
                assertEquals(ex.expectedMissingSlot, result.missingSlot.name) {
                    "Example '${ex.id}' expected missing slot '${ex.expectedMissingSlot}' but got '${result.missingSlot.name}'"
                }
            }
            else -> {
                // RegexMatch or ClassifierMatch when NeedsSlot was expected
                val intentName = routeResultToIntentName(result)
                throw AssertionError(
                    "Example '${ex.id}' (prompt='${ex.prompt}') expected NeedsSlot but got ${result::class.simpleName}" +
                        " (route='$intentName')"
                )
            }
        }
    }

    // ── MealPlannerHandoff — must route to start_meal_planner ────────────────

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("mealPlannerHandoffExamples")
    fun `MealPlannerHandoff examples route to start_meal_planner`(ex: LearnExample) {
        val result = router.route(ex.prompt)
        val intentName = routeResultToIntentName(result)
        assertNotNull(intentName) {
            "Expected 'start_meal_planner' for '${ex.id}' but got FallThrough"
        }
        assertEquals("start_meal_planner", intentName) {
            "Example '${ex.id}' expected 'start_meal_planner' but got '$intentName'"
        }
    }

    // ── FreeformChatAllowed — must NOT accidentally route to start_meal_planner

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("freeformExamples")
    fun `Freeform examples do not accidentally route to start_meal_planner`(ex: LearnExample) {
        val result = router.route(ex.prompt)
        val intentName = routeResultToIntentName(result)
        // Freeform examples are explicitly allowed to fall through to E4B.
        // The only thing we assert is they don't accidentally hit start_meal_planner.
        if (intentName != null) {
            assertNotEquals("start_meal_planner", intentName) {
                "Freeform example '${ex.id}' routed to 'start_meal_planner', but should be freeform"
            }
        }
    }

    // ── Coverage: every example is tested ────────────────────────────────────

    @Test
    fun `every LearnExample is covered by at least one routing test`() {
        val testedIds = qirIntentExamples().toList() +
            qirSlotFillExamples().toList() +
            mealPlannerHandoffExamples().toList() +
            freeformExamples().toList()
        val testedIdSet = testedIds.map { it.id }.toSet()
        val allIds = allLearnExamples.map { it.id }.toSet()
        val untested = allIds - testedIdSet
        assertEquals(emptySet<String>(), untested) {
            "These examples are not covered by any routing test: $untested"
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun routeResultToIntentName(result: QuickIntentRouter.RouteResult): String? {
        return when (result) {
            is QuickIntentRouter.RouteResult.RegexMatch -> result.intent.intentName
            is QuickIntentRouter.RouteResult.ClassifierMatch -> result.intent.intentName
            is QuickIntentRouter.RouteResult.NeedsSlot -> result.intent.intentName
            is QuickIntentRouter.RouteResult.FallThrough -> null
        }
    }

    companion object {
        @JvmStatic
        fun qirIntentExamples(): Stream<LearnExample> = allLearnExamples
            .filter { it.expectedMode == ExpectedLearnMode.QirIntent }
            .stream()

        @JvmStatic
        fun qirSlotFillExamples(): Stream<LearnExample> = allLearnExamples
            .filter { it.expectedMode == ExpectedLearnMode.QirSlotFill }
            .stream()

        @JvmStatic
        fun mealPlannerHandoffExamples(): Stream<LearnExample> = allLearnExamples
            .filter { it.expectedMode == ExpectedLearnMode.MealPlannerHandoff }
            .stream()

        @JvmStatic
        fun freeformExamples(): Stream<LearnExample> = allLearnExamples
            .filter { it.expectedMode == ExpectedLearnMode.FreeformChatAllowed }
            .stream()
    }
}
