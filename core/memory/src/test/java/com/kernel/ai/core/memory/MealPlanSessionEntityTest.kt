package com.kernel.ai.core.memory

import com.kernel.ai.core.memory.entity.MealPlanSessionEntity
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class MealPlanSessionEntityTest {

    @Test
    fun `create entity with minimal fields uses defaults`() {
        val entity = MealPlanSessionEntity(conversationId = "conv-1")

        assertEquals("conv-1", entity.conversationId)
        assertEquals("collecting_preferences", entity.status)
        assertEquals(null, entity.peopleCount)
        assertEquals(null, entity.days)
        assertEquals("[]", entity.dietaryRestrictionsJson)
        assertEquals("[]", entity.proteinPreferencesJson)
        assertEquals(null, entity.highLevelPlanJson)
        assertEquals(null, entity.currentDayIndex)
        assertNotNull(entity.updatedAt)
    }

    @Test
    fun `create entity with all fields populated`() {
        val entity = MealPlanSessionEntity(
            conversationId = "conv-2",
            status = "generating_recipes",
            peopleCount = 4,
            days = 5,
            dietaryRestrictionsJson = """["vegetarian","gluten-free"]""",
            proteinPreferencesJson = """["chicken","tofu"]""",
            highLevelPlanJson = """{"day1":"Pasta","day2":"Salad"}""",
            currentDayIndex = 2,
            updatedAt = 12345L,
        )

        assertEquals("conv-2", entity.conversationId)
        assertEquals("generating_recipes", entity.status)
        assertEquals(4, entity.peopleCount)
        assertEquals(5, entity.days)
        assertEquals("""["vegetarian","gluten-free"]""", entity.dietaryRestrictionsJson)
        assertEquals("""["chicken","tofu"]""", entity.proteinPreferencesJson)
        assertEquals("""{"day1":"Pasta","day2":"Salad"}""", entity.highLevelPlanJson)
        assertEquals(2, entity.currentDayIndex)
        assertEquals(12345L, entity.updatedAt)
    }

    @Test
    fun `copy preserves unspecified fields`() {
        val original = MealPlanSessionEntity(
            conversationId = "conv-3",
            status = "high_level_plan_ready",
            peopleCount = 2,
            days = 3,
        )

        val updated = original.copy(status = "generating_recipes")

        assertEquals("conv-3", updated.conversationId)
        assertEquals("generating_recipes", updated.status)
        assertEquals(2, updated.peopleCount)
        assertEquals(3, updated.days)
        assertEquals("[]", updated.dietaryRestrictionsJson)
    }

    @Test
    fun `entities with same conversationId are equal`() {
        val e1 = MealPlanSessionEntity(conversationId = "same-conv")
        val e2 = MealPlanSessionEntity(conversationId = "same-conv")

        assertEquals(e1, e2)
    }

    @Test
    fun `entities with different conversationId are not equal`() {
        val e1 = MealPlanSessionEntity(conversationId = "conv-a", updatedAt = 100L)
        val e2 = MealPlanSessionEntity(conversationId = "conv-b", updatedAt = 200L)

        assertNotEquals(e1, e2)
    }

    @Test
    fun `copy with null fields produces valid entity`() {
        val original = MealPlanSessionEntity(
            conversationId = "conv-4",
            peopleCount = 4,
            days = 7,
        )

        val cleared = original.copy(peopleCount = null, days = null, highLevelPlanJson = null)

        assertEquals(null, cleared.peopleCount)
        assertEquals(null, cleared.days)
        assertEquals(null, cleared.highLevelPlanJson)
    }
}
