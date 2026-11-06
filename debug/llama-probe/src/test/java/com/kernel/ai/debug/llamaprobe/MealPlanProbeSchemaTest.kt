package com.kernel.ai.debug.llamaprobe

import com.kernel.ai.core.memory.mealplan.GroceryNormalizationStatus
import com.kernel.ai.core.skills.mealplan.MealPlanJsonParser
import com.kernel.ai.core.skills.mealplan.MealPlanQuantityValidator
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MealPlanProbeSchemaTest {
    private val parser = MealPlanJsonParser()
    private val quantityValidator = MealPlanQuantityValidator()

    @Test
    fun `plan schema matches canonical three to seven day contract`() {
        val schema = JSONObject(MealPlanProbeSchemas.plan())
        JsonSchemaSubset.validate(schema.toString())

        val days = schema.getJSONObject("properties").getJSONObject("days")
        assertEquals(3, days.getInt("minItems"))
        assertEquals(7, days.getInt("maxItems"))
        val day = days.getJSONObject("items")
        assertEquals(
            setOf("day_index", "title", "summary"),
            day.getJSONArray("required").toStringSet(),
        )
        assertTrue(day.getJSONObject("properties").has("protein_tags"))

        for (count in 3..7) {
            val plan = parser.parsePlanDraft(planJson(count), expectedDays = count)
            assertEquals((0 until count).toList(), plan.days.map { it.dayIndex })
        }
    }

    @Test
    fun `recipe schema uses parser-compatible ingredients and canonical string steps`() {
        val schema = JSONObject(MealPlanProbeSchemas.recipe(expectedServings = 4))
        JsonSchemaSubset.validate(schema.toString())
        val properties = schema.getJSONObject("properties")
        val ingredient = properties.getJSONObject("ingredients").getJSONObject("items")
        assertEquals(
            setOf("original_text", "amount", "unit", "item"),
            ingredient.getJSONArray("required").toStringSet(),
        )
        assertEquals("string", properties.getJSONObject("method_steps")
            .getJSONObject("items").getString("type"))

        val recipe = parser.parseRecipeDraft(
            """{"title":"Lemon chicken and kumara tray bake","servings":4,"ingredients":[
                {"original_text":"500 g chicken breast","amount":"500","unit":"g","item":"chicken breast"},
                {"original_text":"400 g kumara","amount":"400","unit":"g","item":"kumara"},
                {"original_text":"2 carrots","amount":"2","unit":"whole","item":"carrot"},
                {"original_text":"1 red onion","amount":"1","unit":"whole","item":"red onion"},
                {"original_text":"15 ml olive oil","amount":"15","unit":"ml","item":"olive oil"},
                {"original_text":"1 lemon","amount":"1","unit":"whole","item":"lemon"}
            ],"method_steps":[
                "Heat the oven.",
                "Toss the ingredients.",
                "Roast until cooked through."
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
    fun `unsupported schema keywords and union types fail instead of being relaxed`() {
        val keywordError = assertThrows(UnsupportedSchemaException::class.java) {
            JsonSchemaSubset.validate(
                """{"type":"array","items":{"type":"string"},"uniqueItems":true}""",
            )
        }
        assertTrue(keywordError.message.orEmpty().contains("uniqueItems"))
        val unionError = assertThrows(UnsupportedSchemaException::class.java) {
            JsonSchemaSubset.validate("""{"type":["string","number"]}""")
        }
        assertTrue(unionError.message.orEmpty().contains("type must be a string"))
    }

    @Test
    fun `invalid quantity is rejected by the application semantic validator`() {
        val recipe = parser.parseRecipeDraft(
            """{"title":"Chicken","servings":4,"ingredients":[
                {"original_text":"0 g chicken","amount":"0","unit":"g","item":"chicken"}
            ],"method_steps":["Cook the chicken.","Serve it.","Plate it."]}""",
            expectedServings = 4,
        )

        assertThrows(IllegalArgumentException::class.java) {
            quantityValidator.validateAndNormalize(recipe)
        }
    }

    private fun planJson(days: Int): String = buildString {
        append("""{"days":[""")
        append((0 until days).joinToString(",") { index ->
            """{"day_index":$index,"title":"Dinner ${index + 1}","summary":"Family meal"}"""
        })
        append("]}")
    }

    private fun JSONArray.toStringSet(): Set<String> =
        (0 until length()).map { getString(it) }.toSet()
}
