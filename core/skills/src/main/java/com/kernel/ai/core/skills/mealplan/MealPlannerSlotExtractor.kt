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
        if (Regex("\\b(?:none|no\\s+(?:(?:dietary(?:\\s+(?:requirements?|restrictions?))?)|requirements?|restrictions?)|anything is fine)\\b").containsMatchIn(normalized)) {
            return listOf(NO_DIETARY_REQUIREMENTS)
        }
        val matches = buildList {
            DIETARY_PATTERNS.forEach { (pattern, canonical) ->
                if (pattern.containsMatchIn(normalized)) {
                    add(canonical)
                }
            }
            extractIngredientExclusions(normalized).forEach { exclusion ->
                if (exclusion !in this) {
                    add(exclusion)
                }
            }
        }
        return matches.takeIf { it.isNotEmpty() }
    }

    private fun extractIngredientExclusions(normalized: String): List<String> =
        EXCLUSION_PATTERN.findAll(normalized)
            .flatMap { match -> splitExclusionTerms(match.groupValues[1]).asSequence() }
            .mapNotNull { rawTerm ->
                val cleaned = rawTerm
                    .trim()
                    .removePrefix("no ")
                    .removePrefix("the ")
                    .removePrefix("any ")
                    .trim()
                if (cleaned.isBlank() || cleaned in EXCLUSION_STOP_WORDS) {
                    null
                } else {
                    "no $cleaned"
                }
            }
            .distinct()
            .toList()

    private fun splitExclusionTerms(raw: String): List<String> =
        raw.split(EXCLUSION_SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotBlank() }

    fun extractProteinPreferences(text: String, allowBareNoPreference: Boolean = false): List<String>? {
        val normalized = normalizeWords(text)
        if (
            Regex("\\b(?:any\\s+protein(?:\\s+is\\s+fine)?|surprise me|no\\s+preferences?|no\\s+protein\\s+preferences?)\\b").containsMatchIn(normalized) ||
                (allowBareNoPreference && normalized.matches(Regex("^\\s*(?:none|any)\\s*[.!?]*$")))
        ) {
            return listOf(NO_PROTEIN_PREFERENCE)
        }
        val matches = PROTEIN_KEYWORDS.filter { normalized.contains(it.first) }.map { it.second }.distinct().toMutableList()
        if ("beef mince" in matches) matches.remove("beef")
        return matches.takeIf { it.isNotEmpty() }
    }

    fun isCancelRequest(text: String): Boolean =
        Regex("\\b(?:cancel|nevermind|never mind|forget it|abort)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)

    fun extractReplaceDayIndex(text: String): Int? =
        Regex("\\b(?:replace|swap|change)\\s+day\\s+(\\d+)\\b", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.minus(1)

    fun extractRegenerateDayIndex(text: String): Int? =
        Regex("\\b(?:regenerate|redo|retry)\\s+day\\s+(\\d+)\\b", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.minus(1)

    fun isGenerateRecipesRequest(text: String): Boolean {
        val normalized = text.trim()
        return Regex(
            "^(?:(?:ok(?:ay)?|yes(?:\\s+please)?|please|let'?s|can\\s+we|could\\s+we|i(?:'d|\\s+would)\\s+like\\s+to)[,\\s]+)*(?:generate(?:\\s+recipes?)?|make\\s+(?:recipes?|meal\\s+plan)|create\\s+(?:recipes?|meal\\s+plan)|start\\s+(?:recipes?|meal\\s+plan)|continue|resume|keep\\s+going)[.!?]*$",
            RegexOption.IGNORE_CASE,
        ).matches(normalized)
    }

    fun isShowCurrentPlanRequest(text: String): Boolean =
        Regex(
            "(?:\\bshow(?:\\s+me)?(?:\\s+(?:my|the))?\\s+current\\s+plan\\b|\\bwhat(?:'s| is)\\s+(?:(?:my|the)\\s+)?current\\s+plan\\b)",
            RegexOption.IGNORE_CASE,
        ).containsMatchIn(text)

    fun isRetryRequest(text: String): Boolean =
        Regex("\\b(?:retry|try again)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)

    fun isChangePreferencesRequest(text: String): Boolean =
        Regex("\\b(?:change|edit|update|revise)\\s+(?:preferences?|details?|requirements?)\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(text)


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
        const val NO_DIETARY_REQUIREMENTS = "no dietary requirements"
        const val NO_PROTEIN_PREFERENCE = "no protein preference"

        val DIETARY_PATTERNS = listOf(
            Regex("\\b(?:kid friendly|kid-friendly|family friendly|family-friendly)\\b") to "kid friendly",
            Regex("\\blow lactose\\b") to "low lactose",
            Regex("\\blactose intolerant\\b") to "lactose intolerant",
            Regex("\\b(?:lactose free|lactose-free|no lactose)\\b") to "lactose free",
            Regex("\\bvegetarian\\b") to "vegetarian",
            Regex("\\bvegan\\b") to "vegan",
            Regex("\\b(?:gluten free|gluten-free|no gluten)\\b") to "gluten free",
            Regex("\\b(?:celiac|coeliac|celiac safe|coeliac safe)\\b") to "celiac safe",
            Regex("\\b(?:wheat free|wheat-free|no wheat)\\b") to "wheat free",
            Regex("\\b(?:dairy free|dairy-free|milk free|milk-free|no dairy|dairy allergy|milk allergy)\\b") to "dairy free",
            Regex("\\b(?:egg free|egg-free|no eggs?|egg allergy)\\b") to "egg free",
            Regex("\\b(?:peanut free|peanut-free|no peanuts?|peanut allergy)\\b") to "peanut free",
            Regex("\\b(?:tree nut free|tree-nut free|tree nut allergy|tree nuts allergy|nut free|nut-free|no nuts?|nut allergy)\\b") to "nut free",
            Regex("\\b(?:soy free|soy-free|no soy|soy allergy)\\b") to "soy free",
            Regex("\\b(?:fish free|fish-free|no fish|fish allergy)\\b") to "fish free",
            Regex("\\b(?:shellfish free|shellfish-free|shellfish allergy|crustacean allergy|mollusc allergy|mollusk allergy|no shellfish|no prawns?|no shrimp|no crab|no lobster|no mussels?|no oysters?)\\b") to "shellfish free",
            Regex("\\b(?:sesame free|sesame-free|no sesame|sesame allergy)\\b") to "sesame free",
            Regex("\\bhalal\\b") to "halal",
            Regex("\\bpaleo\\b") to "paleo",
            Regex("\\b(?:keto|ketogenic)\\b") to "keto",
            Regex("\\b(?:pescatarian|pescetarian)\\b") to "pescatarian",
        )
        val EXCLUSION_PATTERN = Regex("(?:\\bno\\b|\\bwithout\\b|\\bexclude\\b|\\bexcluding\\b|\\bavoid\\b)\\s+([a-z][a-z\\s-]{1,40})(?=$|[,.;&])")
        val EXCLUSION_SEPARATOR = Regex("\\s*(?:,|/|\\band\\b|\\bor\\b)\\s*")
        val EXCLUSION_STOP_WORDS = setOf(
            "dietary",
            "dietary requirement",
            "dietary requirements",
            "dietary restriction",
            "dietary restrictions",
            "requirement",
            "requirements",
            "restriction",
            "restrictions",
            "preference",
            "preferences",
            "protein",
            "protein preference",
            "protein preferences",
            "kid friendly",
            "family friendly",
            "lactose",
            "gluten",
            "wheat",
            "dairy",
            "milk",
            "egg",
            "eggs",
            "peanut",
            "peanuts",
            "nut",
            "nuts",
            "tree nut",
            "tree nuts",
            "soy",
            "fish",
            "shellfish",
            "sesame",
        )
        val PROTEIN_KEYWORDS = listOf(
            "chicken" to "chicken",
            "beef mince" to "beef mince",
            "beef" to "beef",
            "turkey" to "turkey",
            "pork" to "pork",
            "lamb" to "lamb",
            "fish" to "fish",
            "salmon" to "salmon",
            "tuna" to "tuna",
            "tofu" to "tofu",
            "lentils" to "lentils",
            "beans" to "beans",
            "egg" to "eggs",
            "snapper" to "snapper",
            "prawn" to "prawns",
            "shrimp" to "prawns",
            "chickpea" to "chickpeas",
            "halloumi" to "halloumi",
        )
    }
}
