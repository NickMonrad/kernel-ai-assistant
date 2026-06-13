package com.kernel.ai.navigation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

/**
 * Catalogue integrity tests for the "Learn what Jandal can do" catalogue.
 *
 * These tests assert that every [LearnExample] in [allLearnExamples] has complete
 * metadata. They enumerate the actual catalogue — not a separate hard-coded list —
 * so adding a new example without metadata fails loudly.
 */
class LearnExampleCatalogueTest {

    @Test
    fun `catalogue is not empty`() {
        assertTrue(allLearnExamples.isNotEmpty(), "Catalogue must have at least one example")
    }

    @ParameterizedTest(name = "[{index}] id={0}")
    @MethodSource("allExamples")
    fun `every example has non-empty id`(ex: LearnExample) {
        assertTrue(ex.id.isNotBlank(), "id must not be blank")
    }

    @ParameterizedTest(name = "[{index}] id={0}")
    @MethodSource("allExamples")
    fun `every example has non-empty title`(ex: LearnExample) {
        assertTrue(ex.title.isNotBlank(), "title must not be blank for '${ex.id}'")
    }

    @ParameterizedTest(name = "[{index}] id={0}")
    @MethodSource("allExamples")
    fun `every example has non-empty prompt`(ex: LearnExample) {
        assertTrue(ex.prompt.isNotBlank(), "prompt must not be blank for '${ex.id}'")
    }

    @ParameterizedTest(name = "[{index}] id={0}")
    @MethodSource("allExamples")
    fun `every example has non-empty category`(ex: LearnExample) {
        assertTrue(ex.category.isNotBlank(), "category must not be blank for '${ex.id}'")
    }

    @Test
    fun `ids are unique`() {
        val ids = allLearnExamples.map { it.id }
        val duplicates = ids.groupBy { it }.filter { it.value.size > 1 }
        assertTrue(
            duplicates.isEmpty(),
            "Duplicate ids: ${duplicates.keys.joinToString(", ")}"
        )
    }

    @Test
    fun `prompts are unique`() {
        val prompts = allLearnExamples.map { it.prompt }
        val duplicates = prompts.groupBy { it }.filter { it.value.size > 1 }
        assertTrue(
            duplicates.isEmpty(),
            "Duplicate prompts: ${duplicates.keys.joinToString(", ")}"
        )
    }

    @ParameterizedTest(name = "[{index}] id={0}")
    @MethodSource("allExamples")
    fun `QirIntent examples declare expectedRoute`(ex: LearnExample) {
        if (ex.expectedMode == ExpectedLearnMode.QirIntent) {
            assertNotNull(ex.expectedRoute, "QirIntent '${ex.id}' must declare expectedRoute")
            assertTrue(
                ex.expectedRoute!!.isNotBlank(),
                "QirIntent '${ex.id}' must declare non-blank expectedRoute"
            )
        }
    }


    @ParameterizedTest(name = "[{index}] id={0}")
    @MethodSource("allExamples")
    fun `QirIntent examples should not declare expectedMissingSlot`(ex: LearnExample) {
        if (ex.expectedMode == ExpectedLearnMode.QirIntent) {
            assertNull(ex.expectedMissingSlot,
                "QirIntent '${ex.id}' should not declare expectedMissingSlot")
        }
    }
    @ParameterizedTest(name = "[{index}] id={0}")
    @MethodSource("allExamples")
    fun `QirSlotFill examples declare expectedRoute`(ex: LearnExample) {
        if (ex.expectedMode == ExpectedLearnMode.QirSlotFill) {
            assertNotNull(ex.expectedRoute, "QirSlotFill '${ex.id}' must declare expectedRoute")
            assertTrue(
                ex.expectedRoute!!.isNotBlank(),
                "QirSlotFill '${ex.id}' must declare non-blank expectedRoute"
            )
        }
    }


    @ParameterizedTest(name = "[{index}] id={0}")
    @MethodSource("allExamples")
    fun `QirSlotFill examples declare expectedMissingSlot`(ex: LearnExample) {
        if (ex.expectedMode == ExpectedLearnMode.QirSlotFill) {
            assertNotNull(ex.expectedMissingSlot, "QirSlotFill '${ex.id}' must declare expectedMissingSlot")
            assertTrue(
                ex.expectedMissingSlot!!.isNotBlank(),
                "QirSlotFill '${ex.id}' must declare non-blank expectedMissingSlot"
            )
        }
    }
    @ParameterizedTest(name = "[{index}] id={0}")
    @MethodSource("allExamples")
    fun `MealPlannerHandoff examples declare start_meal_planner route`(ex: LearnExample) {
        if (ex.expectedMode == ExpectedLearnMode.MealPlannerHandoff) {
            assertEquals("start_meal_planner", ex.expectedRoute,
                "MealPlannerHandoff '${ex.id}' must route to start_meal_planner")
        }
    }

    @ParameterizedTest(name = "[{index}] id={0}")
    @MethodSource("allExamples")
    fun `qir examples do not expect llm_fallthrough`(ex: LearnExample) {
        val qirModes = setOf(
            ExpectedLearnMode.QirIntent,
            ExpectedLearnMode.QirSlotFill,
            ExpectedLearnMode.MealPlannerHandoff,
        )
        if (ex.expectedMode in qirModes) {
            assertNotNull(ex.expectedRoute)
            assertFalse(
                ex.expectedRoute!!.equals("llm_fallthrough", ignoreCase = true),
                "'${ex.id}' with mode ${ex.expectedMode} must not use llm_fallthrough as expectedRoute"
            )
        }
    }

    @ParameterizedTest(name = "[{index}] id={0}")
    @MethodSource("allExamples")
    fun `example has valid expectedMode`(ex: LearnExample) {
        assertNotNull(ex.expectedMode, "expectedMode must not be null for '${ex.id}'")
    }

    @Test
    fun `all sections reference valid example ids`() {
        val validIds = allLearnExamples.map { it.id }.toSet()
        val usedIds = allExampleSections.flatMap { section ->
            (section.defaultExamples + section.moreExamples).map { it.id }
        }.toSet()
        val unknownIds = usedIds - validIds
        assertTrue(
            unknownIds.isEmpty(),
            "Section references unknown ids: ${unknownIds.joinToString(", ")}"
        )
    }

    @Test
    fun `every example appears in at least one section`() {
        val sectionIds = allExampleSections.flatMap { section ->
            (section.defaultExamples + section.moreExamples).map { it.id }
        }.toSet()
        val unlisted = allLearnExamples.map { it.id }.toSet() - sectionIds
        assertTrue(
            unlisted.isEmpty(),
            "Examples not listed in any section: ${unlisted.joinToString(", ")}"
        )
    }

    companion object {
        @JvmStatic
        fun allExamples(): Stream<LearnExample> = allLearnExamples.stream()
    }
}
