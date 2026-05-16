package com.kernel.ai.core.skills.mealplan

import com.kernel.ai.core.memory.mealplan.MealPlanDraft
import com.kernel.ai.core.memory.mealplan.MealPlanDraftDay
import com.kernel.ai.core.memory.mealplan.RecipeDraft
import com.kernel.ai.core.memory.mealplan.RecipeDraftIngredient
import com.kernel.ai.core.memory.mealplan.RecipeDraftMethodStep
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MealPlanJsonParser @Inject constructor() {
    fun parsePlanDraft(raw: String, expectedDays: Int): MealPlanDraft {
        val days = parsePlanDays(raw)
        if (days.size != expectedDays) {
            throw MealPlanValidationException("Plan JSON returned ${days.size} days; expected $expectedDays.")
        }
        val expectedIndexes = (0 until expectedDays).toList()
        val actualIndexes = days.map { it.dayIndex }
        if (actualIndexes != expectedIndexes) {
            throw MealPlanValidationException("Plan JSON day_index values must be contiguous from 0 to ${expectedDays - 1}.")
        }
        validatePlanDayTitles(days)
        return MealPlanDraft(days)
    }

    fun parseSinglePlanDay(raw: String, expectedDayIndex: Int): MealPlanDraftDay {
        val days = parsePlanDays(raw)
        if (days.size != 1) {
            throw MealPlanValidationException("Replacement day JSON must contain exactly one day.")
        }
        val day = days.single()
        val normalizedDayIndex = when (day.dayIndex) {
            expectedDayIndex -> expectedDayIndex
            expectedDayIndex + 1 -> expectedDayIndex
            else -> throw MealPlanValidationException(
                "Replacement day JSON must use day_index $expectedDayIndex (zero-based for Day ${expectedDayIndex + 1}).",
            )
        }
        val normalizedDay = day.copy(dayIndex = normalizedDayIndex)
        validatePlanDayTitles(listOf(normalizedDay))
        return normalizedDay
    }

    private fun parsePlanDays(raw: String): List<MealPlanDraftDay> {
        val obj = parseRootObject(raw)
        val daysArray = obj.optJSONArray("days")
            ?: throw MealPlanValidationException("Plan JSON must contain a 'days' array.")
        return List(daysArray.length()) { index ->
            val item = daysArray.getJSONObject(index)
            MealPlanDraftDay(
                dayIndex = item.optInt("day_index", -1),
                title = item.optString("title").trim(),
                summary = item.optString("summary").trim().takeIf { it.isNotBlank() },
                proteinTags = jsonArrayToStrings(item.optJSONArray("protein_tags")),
            )
        }
    }

    private fun validatePlanDayTitles(days: List<MealPlanDraftDay>) {
        days.forEach {
            if (it.title.isBlank()) {
                throw MealPlanValidationException("Each plan day must include a non-blank title.")
            }
        }
    }
    fun parseRecipeDraft(raw: String, expectedServings: Int): RecipeDraft {
        val obj = parseRootObject(raw)
        val title = obj.optString("title").trim()
        if (title.isBlank()) {
            throw MealPlanValidationException("Recipe JSON must include a non-blank title.")
        }
        val servings = obj.optInt("servings", -1)
        if (servings <= 0) {
            throw MealPlanValidationException("Recipe JSON must include a positive servings value.")
        }
        if (expectedServings > 0 && servings != expectedServings) {
            throw MealPlanValidationException("Recipe servings $servings did not match requested servings $expectedServings.")
        }
        val ingredientsArray = obj.optJSONArray("ingredients")
            ?: throw MealPlanValidationException("Recipe JSON must include an ingredients array.")
        if (ingredientsArray.length() == 0) {
            throw MealPlanValidationException("Recipe JSON must contain at least one ingredient.")
        }
        val ingredients = List(ingredientsArray.length()) { index ->
            when (val item = ingredientsArray.get(index)) {
                is JSONObject -> {
                    val originalText = item.optString("original_text").trim()
                    if (originalText.isBlank()) {
                        throw MealPlanValidationException("Each ingredient must include original_text.")
                    }
                    RecipeDraftIngredient(
                        originalText = originalText,
                        amount = item.optNullableString("amount"),
                        unit = item.optNullableString("unit"),
                        item = item.optNullableString("item"),
                        note = item.optNullableString("note"),
                    )
                }
                is String -> {
                    val originalText = item.trim()
                    if (originalText.isBlank()) {
                        throw MealPlanValidationException("Each ingredient string must be non-blank.")
                    }
                    RecipeDraftIngredient(
                        originalText = originalText,
                        amount = null,
                        unit = null,
                        item = null,
                        note = null,
                    )
                }
                else -> throw MealPlanValidationException("Recipe ingredients must be objects or strings.")
            }
        }
        val methodArray = obj.optJSONArray("method_steps")
            ?: throw MealPlanValidationException("Recipe JSON must include a method_steps array.")
        if (methodArray.length() < 2) {
            throw MealPlanValidationException("Recipe JSON must contain at least two method steps.")
        }
        val methodSteps = List(methodArray.length()) { index ->
            when (val item = methodArray.get(index)) {
                is JSONObject -> {
                    val number = item.optInt("step_number", index + 1)
                    val text = item.optString("text").trim()
                    if (text.isBlank()) {
                        throw MealPlanValidationException("Each method step must include text.")
                    }
                    RecipeDraftMethodStep(number, text)
                }
                is String -> {
                    val text = item.trim()
                    if (text.isBlank()) {
                        throw MealPlanValidationException("Each method step string must be non-blank.")
                    }
                    RecipeDraftMethodStep(index + 1, text)
                }
                else -> throw MealPlanValidationException("Recipe method steps must be objects or strings.")
            }
        }
        val expectedStepNumbers = (1..methodSteps.size).toList()
        val actualStepNumbers = methodSteps.map { it.stepNumber }
        if (actualStepNumbers != expectedStepNumbers) {
            throw MealPlanValidationException("Recipe method steps must be numbered contiguously from 1.")
        }
        return RecipeDraft(
            title = title,
            servings = servings,
            ingredients = ingredients,
            methodSteps = methodSteps,
        )
    }

    private fun parseRootObject(raw: String): JSONObject {
        val trimmed = raw.trim()
        val candidate = when {
            trimmed.startsWith("{") && trimmed.endsWith("}") -> trimmed
            else -> extractJsonObject(trimmed)
                ?: throw MealPlanValidationException("Model output did not contain a JSON object.")
        }
        return try {
            JSONObject(candidate)
        } catch (e: JSONException) {
            throw MealPlanValidationException("Model output was not valid JSON: ${e.message}")
        }
    }

    private fun extractJsonObject(raw: String): String? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start == -1 || end == -1 || end <= start) return null
        return raw.substring(start, end + 1)
    }

    private fun jsonArrayToStrings(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return List(array.length()) { index -> array.optString(index).trim() }
            .filter { it.isNotBlank() }
    }

    private fun JSONObject.optNullableString(key: String): String? =
        optString(key).trim().takeIf { it.isNotBlank() && it != "null" }
}

class MealPlanValidationException(message: String) : IllegalArgumentException(message)
