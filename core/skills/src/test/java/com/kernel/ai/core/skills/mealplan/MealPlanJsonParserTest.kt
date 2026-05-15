package com.kernel.ai.core.skills.mealplan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class MealPlanJsonParserTest {
    private val parser = MealPlanJsonParser()

    @Test
    fun `parsePlanDraft parses contiguous days`() {
        val result = parser.parsePlanDraft(
            raw = """
                {
                  "days": [
                    {"day_index": 0, "title": "Chicken stir-fry", "summary": "Quick dinner", "protein_tags": ["chicken"]},
                    {"day_index": 1, "title": "Beef mince pasta", "summary": "Fast pasta", "protein_tags": ["beef mince"]}
                  ]
                }
            """.trimIndent(),
            expectedDays = 2,
        )

        assertEquals(2, result.days.size)
        assertEquals("Chicken stir-fry", result.days[0].title)
        assertEquals(listOf("beef mince"), result.days[1].proteinTags)
    }

    @Test
    fun `parsePlanDraft rejects non contiguous day indexes`() {
        assertThrows(MealPlanValidationException::class.java) {
            parser.parsePlanDraft(
                raw = """
                    {
                      "days": [
                        {"day_index": 1, "title": "Chicken stir-fry", "summary": "Quick dinner", "protein_tags": ["chicken"]},
                        {"day_index": 2, "title": "Beef mince pasta", "summary": "Fast pasta", "protein_tags": ["beef mince"]}
                      ]
                    }
                """.trimIndent(),
                expectedDays = 2,
            )
        }
    }

    @Test
    fun `parseSinglePlanDay accepts explicit replacement day index`() {
        val result = parser.parseSinglePlanDay(
            raw = """
                {
                  "days": [
                    {"day_index": 2, "title": "Lemon chicken", "summary": "Quick dinner", "protein_tags": ["chicken"]}
                  ]
                }
            """.trimIndent(),
            expectedDayIndex = 2,
        )

        assertEquals(2, result.dayIndex)
        assertEquals("Lemon chicken", result.title)
    }

    @Test
    fun `parseRecipeDraft parses structured recipe`() {
        val result = parser.parseRecipeDraft(
            raw = """
                {
                  "title": "Chicken stir-fry",
                  "servings": 4,
                  "ingredients": [
                    {"original_text": "500 g chicken breast, sliced", "amount": "500", "unit": "g", "item": "chicken breast", "note": "sliced"},
                    {"original_text": "Soy sauce, to taste", "amount": null, "unit": null, "item": "soy sauce", "note": "to taste"}
                  ],
                  "method_steps": [
                    {"step_number": 1, "text": "Heat oil in a pan."},
                    {"step_number": 2, "text": "Cook the chicken until browned."}
                  ]
                }
            """.trimIndent(),
            expectedServings = 4,
        )

        assertEquals("Chicken stir-fry", result.title)
        assertEquals(2, result.ingredients.size)
        assertEquals("to taste", result.ingredients[1].note)
        assertEquals(2, result.methodSteps.size)
    }

    @Test
    fun `parseRecipeDraft parses simplified string recipe`() {
        val result = parser.parseRecipeDraft(
            raw = """
                {
                  "title": "Lemon chicken tray bake",
                  "servings": 3,
                  "ingredients": [
                    "600 g chicken thighs",
                    "1 tbsp olive oil",
                    "2 carrots, chopped"
                  ],
                  "method_steps": [
                    "Heat the oven to 220C.",
                    "Toss the chicken and vegetables with oil.",
                    "Roast until the chicken is cooked through."
                  ]
                }
            """.trimIndent(),
            expectedServings = 3,
        )

        assertEquals("Lemon chicken tray bake", result.title)
        assertEquals(3, result.ingredients.size)
        assertEquals("600 g chicken thighs", result.ingredients.first().originalText)
        assertEquals(null, result.ingredients.first().amount)
        assertEquals(3, result.methodSteps.size)
        assertEquals(1, result.methodSteps.first().stepNumber)
        assertEquals("Heat the oven to 220C.", result.methodSteps.first().text)
    }

    @Test
    fun `parseRecipeDraft rejects servings mismatch`() {
        assertThrows(MealPlanValidationException::class.java) {
            parser.parseRecipeDraft(
                raw = """
                    {
                      "title": "Chicken stir-fry",
                      "servings": 2,
                      "ingredients": [
                        {"original_text": "500 g chicken breast", "amount": "500", "unit": "g", "item": "chicken breast", "note": null}
                      ],
                      "method_steps": [
                        {"step_number": 1, "text": "Heat oil in a pan."},
                        {"step_number": 2, "text": "Cook the chicken until browned."}
                      ]
                    }
                """.trimIndent(),
                expectedServings = 4,
            )
        }
    }
}
