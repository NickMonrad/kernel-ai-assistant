package com.kernel.ai.debug.llamaprobe

import com.kernel.ai.core.memory.mealplan.GroceryNormalizationStatus
import com.kernel.ai.core.skills.mealplan.MealPlanJsonParser
import com.kernel.ai.core.skills.mealplan.MealPlanQuantityValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MealPlanProbeSchemaTest {
    private val parser = MealPlanJsonParser()
    private val quantityValidator = MealPlanQuantityValidator()

    @Test
    fun `plan and recipe schemas stay within the native grammar subset`() {
        JsonSchemaSubset.validate(MealPlanProbeSchemas.plan(expectedDays = 2))
        JsonSchemaSubset.validate(MealPlanProbeSchemas.recipe(expectedServings = 4))
    }

    @Test
    fun `unsupported JSON Schema keyword fails instead of being relaxed`() {
        val error = assertThrows(UnsupportedSchemaException::class.java) {
            JsonSchemaSubset.validate(
                """{"type":"array","items":{"type":"string"},"uniqueItems":true}""",
            )
        }
        assertTrue(error.message.orEmpty().contains("uniqueItems"))
    }

    @Test
    fun `existing meal-plan parser accepts nested plan and contiguous indexes`() {
        val plan = parser.parsePlanDraft(
            """{"days":[
                {"day_index":0,"title":"Lemon chicken","summary":"Tray bake","protein_tags":["chicken"]},
                {"day_index":1,"title":"Beef and rice bowls","summary":"Quick skillet","protein_tags":["beef"]}
            ]}""",
            expectedDays = 2,
        )

        assertEquals(listOf(0, 1), plan.days.map { it.dayIndex })
        assertTrue(plan.days.all { it.title.isNotBlank() })
    }

    @Test
    fun `existing recipe parser and quantity validator accept explicit ingredients`() {
        val recipe = parser.parseRecipeDraft(
            """{"title":"Lemon chicken and kumara tray bake","servings":4,"ingredients":[
                {"original_text":"500 g chicken breast","amount":"500","unit":"g","item":"chicken breast"},
                {"original_text":"400 g kumara","amount":"400","unit":"g","item":"kumara"},
                {"original_text":"2 carrots","amount":"2","unit":"whole","item":"carrot"},
                {"original_text":"1 red onion","amount":"1","unit":"whole","item":"red onion"},
                {"original_text":"15 ml olive oil","amount":"15","unit":"ml","item":"olive oil"},
                {"original_text":"1 lemon","amount":"1","unit":"whole","item":"lemon"}
            ],"method_steps":[
                {"step_number":1,"text":"Heat the oven."},
                {"step_number":2,"text":"Toss the ingredients."},
                {"step_number":3,"text":"Roast until cooked through."}
            ]}""",
            expectedServings = 4,
        )
        val groceries = quantityValidator.validateAndNormalize(recipe)

        assertEquals(6, groceries.size)
        assertTrue(groceries.all { it.normalizationStatus == GroceryNormalizationStatus.NORMALIZED })
        assertEquals("500", groceries.first().quantity)
        assertEquals("chicken breast", groceries.first().ingredientName)
    }

    @Test
    fun `invalid quantity is rejected by the application semantic validator`() {
        val recipe = parser.parseRecipeDraft(
            """{"title":"Chicken","servings":4,"ingredients":[
                {"original_text":"0 g chicken","amount":"0","unit":"g","item":"chicken"}
            ],"method_steps":["Cook the chicken.","Serve it."]}""",
            expectedServings = 4,
        )

        assertThrows(IllegalArgumentException::class.java) {
            quantityValidator.validateAndNormalize(recipe)
        }
    }
}
