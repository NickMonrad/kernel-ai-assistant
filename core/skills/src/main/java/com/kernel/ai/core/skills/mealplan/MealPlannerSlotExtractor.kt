package com.kernel.ai.core.skills.mealplan

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MealPlannerSlotExtractor @Inject constructor() {
    fun extractPeopleCount(text: String): Int? {
        val normalized = normalizeWords(text)
        PEOPLE_PATTERN.find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        if (normalized.matches(Regex("^\\d{1,2}$"))) return normalized.toIntOrNull()
        return null
    }

    fun extractDaysCount(text: String): Int? {
        val normalized = normalizeWords(text)
        DAYS_PATTERN.find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return null
    }

    fun extractDietaryRestrictions(text: String): List<String>? {
        val normalized = normalizeWords(text)
        if (Regex("\\b(?:none|no dietary restrictions|anything is fine)\\b").containsMatchIn(normalized)) {
            return emptyList()
        }
        val matches = DIETARY_KEYWORDS.filter { normalized.contains(it.first) }.map { it.second }.distinct()
        return matches.takeIf { it.isNotEmpty() }
    }

    fun extractProteinPreferences(text: String): List<String>? {
        val normalized = normalizeWords(text)
        if (Regex("\\b(?:anything|any protein|surprise me)\\b").containsMatchIn(normalized)) {
            return emptyList()
        }
        val matches = PROTEIN_KEYWORDS.filter { normalized.contains(it.first) }.map { it.second }.distinct().toMutableList()
        if ("beef mince" in matches) matches.remove("beef")
        return matches.takeIf { it.isNotEmpty() }
    }

    fun isCancelRequest(text: String): Boolean =
        Regex("\\b(?:cancel|stop|nevermind|never mind|forget it|abort)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)

    fun extractReplaceDayIndex(text: String): Int? =
        Regex("\\b(?:replace|swap|change)\\s+day\\s+(\\d+)\\b", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.minus(1)

    fun extractRegenerateDayIndex(text: String): Int? =
        Regex("\\b(?:regenerate|redo|retry)\\s+day\\s+(\\d+)\\b", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.minus(1)

    private fun normalizeWords(text: String): String {
        var normalized = text.lowercase()
        WORD_NUMBERS.forEach { (word, digit) ->
            normalized = normalized.replace(Regex("\\b$word\\b"), digit)
        }
        return normalized
    }

    private companion object {
        val PEOPLE_PATTERN = Regex("\\b(\\d{1,2})\\s+(?:people|person|adults?|kids?|children|servings?)\\b")
        val DAYS_PATTERN = Regex("\\b(\\d{1,2})\\s+days?\\b")
        val WORD_NUMBERS = mapOf(
            "one" to "1",
            "two" to "2",
            "three" to "3",
            "four" to "4",
            "five" to "5",
            "six" to "6",
            "seven" to "7",
            "eight" to "8",
            "nine" to "9",
            "ten" to "10",
        )
        val DIETARY_KEYWORDS = listOf(
            "low lactose" to "low lactose",
            "lactose intolerant" to "lactose intolerant",
            "lactose free" to "lactose free",
            "vegetarian" to "vegetarian",
            "vegan" to "vegan",
            "gluten free" to "gluten free",
            "dairy free" to "dairy free",
            "nut free" to "nut free",
            "halal" to "halal",
            "keto" to "keto",
            "pescatarian" to "pescatarian",
            "pescetarian" to "pescetarian",
        )
        val PROTEIN_KEYWORDS = listOf(
            "chicken" to "chicken",
            "beef mince" to "beef mince",
            "beef" to "beef",
            "pork" to "pork",
            "lamb" to "lamb",
            "fish" to "fish",
            "salmon" to "salmon",
            "tuna" to "tuna",
            "tofu" to "tofu",
            "lentils" to "lentils",
            "beans" to "beans",
            "egg" to "eggs",
        )
    }
}
