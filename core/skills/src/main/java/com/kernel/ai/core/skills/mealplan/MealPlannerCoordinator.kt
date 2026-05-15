package com.kernel.ai.core.skills.mealplan

import com.kernel.ai.core.inference.EmbeddingEngine
import com.kernel.ai.core.inference.InferenceEngine
import com.kernel.ai.core.memory.mealplan.MealPlanDayStatus
import com.kernel.ai.core.memory.mealplan.MealPlanSessionStatus
import com.kernel.ai.core.memory.mealplan.MealPlanSnapshot
import com.kernel.ai.core.memory.mealplan.PendingGenerationKind
import com.kernel.ai.core.memory.mealplan.RecipeDraft
import com.kernel.ai.core.memory.repository.MealPlanSessionRepository
import com.kernel.ai.core.memory.repository.MemoryRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MealPlannerCoordinator @Inject constructor(
    private val sessionRepository: MealPlanSessionRepository,
    private val slotExtractor: MealPlannerSlotExtractor,
    private val jsonParser: MealPlanJsonParser,
    private val quantityValidator: MealPlanQuantityValidator,
    private val inferenceEngine: InferenceEngine,
    private val embeddingEngine: EmbeddingEngine,
    private val memoryRepository: MemoryRepository,
) {
    private val activeGenerationCounts = mutableMapOf<String, Int>()
    private val activeGenerationMutex = Mutex()
    suspend fun hasActiveSession(conversationId: String): Boolean =
        sessionRepository.hasActiveSessionForConversation(conversationId)

    suspend fun hasAnySession(conversationId: String): Boolean =
        sessionRepository.hasAnySessionForConversation(conversationId)

    suspend fun startOrResume(conversationId: String): MealPlannerReply {
        val snapshot = sessionRepository.startOrResume(conversationId)
        return MealPlannerReply(promptForSnapshot(snapshot))
    }

    suspend fun ingestUserMessage(
        conversationId: String,
        text: String,
        onPlannerMessage: suspend (String) -> Unit = {},
    ): MealPlannerReply {
        val snapshot = sessionRepository.getActiveSession(conversationId)
            ?: return startOrResume(conversationId)

        if (slotExtractor.isCancelRequest(text)) {
            sessionRepository.cancelSession(snapshot.sessionId)
            return MealPlannerReply("Okay — I’ve cancelled this meal plan session.")
        }

        if (isGenerationActive(snapshot.sessionId)) {
            return MealPlannerReply(generationInProgressMessage(snapshot))
        }

        return when (snapshot.status) {
            MealPlanSessionStatus.COLLECTING_REQUIRED_SLOTS -> handleCollecting(snapshot, text)
            MealPlanSessionStatus.PLAN_REVIEW -> handlePlanReview(snapshot, text, onPlannerMessage)
            MealPlanSessionStatus.RECIPES_IN_PROGRESS,
            MealPlanSessionStatus.AWAITING_USER_EDIT_OR_RECOVERY -> handleActiveOrRecovery(snapshot, text, onPlannerMessage)
            MealPlanSessionStatus.COMPLETED -> MealPlannerReply(
                "This meal plan is already finalized. Start a new one by asking me to plan meals again.",
            )
            MealPlanSessionStatus.CANCELLED -> startOrResume(conversationId)
        }
    }

    private suspend fun handleCollecting(snapshot: MealPlanSnapshot, text: String): MealPlannerReply {
        val peopleCount = slotExtractor.extractPeopleCount(text)
        val daysCount = slotExtractor.extractDaysCount(text)
        val dietaryRestrictions = slotExtractor.extractDietaryRestrictions(text)
        val proteinPreferences = slotExtractor.extractProteinPreferences(text)
        val updated = sessionRepository.updateRequiredSlots(
            sessionId = snapshot.sessionId,
            peopleCount = peopleCount,
            daysCount = daysCount,
            dietaryRestrictions = dietaryRestrictions,
            proteinPreferences = proteinPreferences,
        )
        val missing = missingSlots(updated)
        if (missing.isNotEmpty()) {
            return MealPlannerReply(promptForMissingSlots(updated, missing))
        }
        val hadAllSlotsBefore = missingSlots(snapshot).isEmpty()
        val hasAnySlotUpdates =
            peopleCount != null || daysCount != null || dietaryRestrictions != null || proteinPreferences != null
        if (hadAllSlotsBefore && !hasAnySlotUpdates) {
            return MealPlannerReply(promptForPreferenceEditing(updated))
        }
        return generatePlanForReview(updated)
    }

    private suspend fun handlePlanReview(
        snapshot: MealPlanSnapshot,
        text: String,
        onPlannerMessage: suspend (String) -> Unit,
    ): MealPlannerReply {
        val replaceDayIndex = slotExtractor.extractReplaceDayIndex(text)
        if (replaceDayIndex != null) {
            return try {
                val replaced = generateReplacementDay(snapshot, replaceDayIndex)
                MealPlannerReply(planReviewPrompt(replaced))
            } catch (e: IllegalArgumentException) {
                MealPlannerReply(replacementFailureMessage(replaceDayIndex, e.message))
            } catch (e: MealPlanValidationException) {
                MealPlannerReply(replacementFailureMessage(replaceDayIndex, e.message))
            }
        }
        if (slotExtractor.isChangePreferencesRequest(text)) {
            val editable = sessionRepository.returnToSlotCollection(snapshot.sessionId)
            return MealPlannerReply(promptForPreferenceEditing(editable))
        }
        if (slotExtractor.isGenerateRecipesRequest(text)) {
            return if (snapshot.days.isEmpty()) {
                generatePlanForReview(snapshot)
            } else {
                generatePendingRecipesFrom(snapshot, onPlannerMessage = onPlannerMessage)
            }
        }
        return MealPlannerReply(planReviewPrompt(snapshot))
    }

    private suspend fun handleActiveOrRecovery(
        snapshot: MealPlanSnapshot,
        text: String,
        onPlannerMessage: suspend (String) -> Unit,
    ): MealPlannerReply {
        val failedDay = snapshot.days.firstOrNull { it.status == MealPlanDayStatus.FAILED }
        val pendingDay = snapshot.days.firstOrNull { it.status != MealPlanDayStatus.PERSISTED && it.status != MealPlanDayStatus.FAILED }
        if (failedDay == null && pendingDay != null) {
            if (slotExtractor.isGenerateRecipesRequest(text)) {
                return generatePendingRecipesFrom(
                    snapshot,
                    intro = "Resuming your meal plan at Day ${pendingDay.dayIndex + 1} of ${snapshot.days.size}…",
                    onPlannerMessage = onPlannerMessage,
                )
            }
            return MealPlannerReply(resumePrompt(snapshot, pendingDay.dayIndex))
        }

        val replaceDayIndex = slotExtractor.extractReplaceDayIndex(text)
        if (replaceDayIndex != null) {
            return try {
                val replaced = generateReplacementDay(snapshot, replaceDayIndex)
                generateSpecificDayRecipe(replaced, replaceDayIndex, intro = "I replaced Day ${replaceDayIndex + 1}. Here’s the updated recipe:\n")
            } catch (e: IllegalArgumentException) {
                MealPlannerReply(replacementFailureMessage(replaceDayIndex, e.message))
            } catch (e: MealPlanValidationException) {
                MealPlannerReply(replacementFailureMessage(replaceDayIndex, e.message))
            }
        }
        val regenerateDayIndex = slotExtractor.extractRegenerateDayIndex(text)
        if (regenerateDayIndex != null) {
            return generateSpecificDayRecipe(snapshot, regenerateDayIndex, intro = "I regenerated Day ${regenerateDayIndex + 1}. Here’s the updated recipe:\n")
        }
        if (isFinalizeRequest(text) && snapshot.days.all { it.status == MealPlanDayStatus.PERSISTED }) {
            val completed = sessionRepository.completeSession(snapshot.sessionId)
            writeFinalSummaryIfNeeded(completed)
            return MealPlannerReply("Meal planning is finalized. Your per-plan shopping list and recipe lists are ready.")
        }
        if (failedDay != null) {
            return MealPlannerReply(recoveryPrompt(failedDay.dayIndex))
        }
        return MealPlannerReply(
            "Your meal plan is ready. Say 'regenerate day 2', 'replace day 1', or 'done meal planning' to finalize it.",
        )
    }

    private suspend fun generatePlanForReview(snapshot: MealPlanSnapshot): MealPlannerReply =
        withSessionGeneration(snapshot.sessionId) {
            sessionRepository.markPendingGeneration(snapshot.sessionId, PendingGenerationKind.PLAN)
            val rawPlan = inferenceEngine.generateOnce(
                prompt = buildPlanUserPrompt(snapshot),
                systemPrompt = buildPlanSystemPrompt(),
                thinkingEnabled = false,
                stopOnFirstJsonObject = true,
            )
            if (rawPlan.isBlank()) {
                sessionRepository.markGenerationFailure(
                    snapshot.sessionId,
                    null,
                    "PLAN_NO_OUTPUT",
                    "The model did not return a plan.",
                )
                return@withSessionGeneration MealPlannerReply(
                    "I couldn't finish building the plan because the model didn't return one. Try replying with the same requirements again.",
                )
            }
            val planDraft = try {
                jsonParser.parsePlanDraft(rawPlan, snapshot.daysCount ?: 0)
            } catch (e: MealPlanValidationException) {
                sessionRepository.markGenerationFailure(snapshot.sessionId, null, "PLAN_JSON_INVALID", e.message ?: "Invalid plan JSON")
                return@withSessionGeneration MealPlannerReply("I couldn't generate a valid high-level plan yet. ${e.message} Try replying with the same requirements again or adjust them.")
            }
            val planned = sessionRepository.savePlanDraft(snapshot.sessionId, planDraft.days)
            MealPlannerReply(planReviewPrompt(planned))
        }

    private suspend fun generatePendingRecipesFrom(
        snapshot: MealPlanSnapshot,
        intro: String? = null,
        onPlannerMessage: suspend (String) -> Unit = {},
    ): MealPlannerReply = withSessionGeneration(snapshot.sessionId) {
        var currentSnapshot = sessionRepository.getSession(snapshot.sessionId) ?: snapshot
        if (!intro.isNullOrBlank()) {
            onPlannerMessage(intro.trim())
        }
        val totalDays = currentSnapshot.days.size
        val pendingDays = currentSnapshot.days
            .filter { it.status != MealPlanDayStatus.PERSISTED && it.status != MealPlanDayStatus.FAILED }
            .sortedBy { it.dayIndex }
        for (day in pendingDays) {
            onPlannerMessage("Generating recipe ${day.dayIndex + 1} of $totalDays…")
            val recipeResult = try {
                generateAndPersistRecipe(currentSnapshot, day.dayIndex)
            } catch (e: MealPlanValidationException) {
                return@withSessionGeneration MealPlannerReply(
                    "I hit a validation problem while generating Day ${day.dayIndex + 1}: ${e.message}\nSay 'regenerate day ${day.dayIndex + 1}' or 'replace day ${day.dayIndex + 1}' to recover.",
                )
            }
            onPlannerMessage(formatRecipeSection(day.dayIndex, recipeResult.recipe))
            currentSnapshot = recipeResult.snapshot
        }
        if (currentSnapshot.days.all { it.status == MealPlanDayStatus.PERSISTED }) {
            MealPlannerReply("Your meal plan is ready. Say 'regenerate day 2', 'replace day 1', or 'done meal planning' to finalize it.")
        } else {
            val nextPending = currentSnapshot.days.firstOrNull { it.status != MealPlanDayStatus.PERSISTED && it.status != MealPlanDayStatus.FAILED }
            MealPlannerReply(nextPending?.let { resumePrompt(currentSnapshot, it.dayIndex) } ?: "Your meal plan is ready.")
        }
    }

    private suspend fun generateSpecificDayRecipe(snapshot: MealPlanSnapshot, dayIndex: Int, intro: String): MealPlannerReply =
        withSessionGeneration(snapshot.sessionId) {
            val result = try {
                generateAndPersistRecipe(snapshot, dayIndex)
            } catch (e: MealPlanValidationException) {
                return@withSessionGeneration MealPlannerReply(
                    "I couldn't generate a valid recipe for Day ${dayIndex + 1}: ${e.message} Say 'regenerate day ${dayIndex + 1}' or 'replace day ${dayIndex + 1}'.",
                )
            }
            val builder = StringBuilder()
            builder.append(intro)
            builder.append(formatRecipeSection(dayIndex, result.recipe))
            if (result.snapshot.days.all { it.status == MealPlanDayStatus.PERSISTED }) {
                builder.append("\n\nYour meal plan is ready. Say 'done meal planning' to finalize it, or regenerate another day.")
            }
            MealPlannerReply(builder.toString().trim())
        }

    private suspend fun generateAndPersistRecipe(
        snapshot: MealPlanSnapshot,
        dayIndex: Int,
    ): GeneratedRecipeResult {
        val day = snapshot.days.firstOrNull { it.dayIndex == dayIndex }
            ?: throw MealPlanValidationException("Unknown meal-plan day ${dayIndex + 1}.")
        val servings = snapshot.peopleCount ?: throw MealPlanValidationException("Meal-plan session is missing people count.")
        sessionRepository.markPendingGeneration(snapshot.sessionId, PendingGenerationKind.RECIPE, dayIndex)
        val rawRecipe = inferenceEngine.generateOnce(
            prompt = buildRecipeUserPrompt(snapshot, dayIndex),
            systemPrompt = buildRecipeSystemPrompt(),
            thinkingEnabled = false,
            stopOnFirstJsonObject = true,
        )
        if (rawRecipe.isBlank()) {
            sessionRepository.markGenerationFailure(
                snapshot.sessionId,
                dayIndex,
                "RECIPE_NO_OUTPUT",
                "The model did not return a recipe.",
            )
            throw MealPlanValidationException("The model didn't return a recipe.")
        }
        val recipe = try {
            jsonParser.parseRecipeDraft(rawRecipe, servings)
        } catch (e: MealPlanValidationException) {
            sessionRepository.markGenerationFailure(snapshot.sessionId, dayIndex, "RECIPE_JSON_INVALID", e.message ?: "Invalid recipe JSON")
            throw e
        }
        val groceries = try {
            quantityValidator.validateAndNormalize(recipe)
        } catch (e: MealPlanValidationException) {
            sessionRepository.markGenerationFailure(snapshot.sessionId, dayIndex, "RECIPE_QUANTITY_INVALID", e.message ?: "Invalid recipe quantities")
            throw e
        }
        val updated = sessionRepository.persistRecipeDraft(
            sessionId = snapshot.sessionId,
            dayIndex = dayIndex,
            recipeDraft = recipe,
            rawModelJson = rawRecipe,
            groceries = groceries,
        )
        return GeneratedRecipeResult(updated, recipe, day.title ?: recipe.title)
    }

    private suspend fun generateReplacementDay(snapshot: MealPlanSnapshot, dayIndex: Int): MealPlanSnapshot =
        withSessionGeneration(snapshot.sessionId) {
            require(dayIndex in snapshot.days.indices) { "Invalid day index: $dayIndex" }
            val raw = inferenceEngine.generateOnce(
                prompt = buildReplacementDayUserPrompt(snapshot, dayIndex),
                systemPrompt = buildReplacementDaySystemPrompt(dayIndex),
                thinkingEnabled = false,
                stopOnFirstJsonObject = true,
            )
            if (raw.isBlank()) {
                throw MealPlanValidationException("The model didn't return a replacement day.")
            }
            val replacement = jsonParser.parseSinglePlanDay(raw, dayIndex)
            sessionRepository.replaceDayDraft(
                sessionId = snapshot.sessionId,
                dayIndex = dayIndex,
                title = replacement.title,
                summary = replacement.summary,
                proteinTags = replacement.proteinTags,
            )
        }

    private suspend fun writeFinalSummaryIfNeeded(snapshot: MealPlanSnapshot) {
        if (snapshot.finalSummaryWritten) return
        val summary = sessionRepository.buildFinalSummary(snapshot.sessionId)
        val embedding = embeddingEngine.embed(summary)
        memoryRepository.addEpisodicMemory(snapshot.conversationId, summary, embedding)
        sessionRepository.markFinalSummaryWritten(snapshot.sessionId)
    }

    private suspend fun isGenerationActive(sessionId: String): Boolean = activeGenerationMutex.withLock {
        (activeGenerationCounts[sessionId] ?: 0) > 0
    }

    private suspend fun <T> withSessionGeneration(sessionId: String, block: suspend () -> T): T {
        activeGenerationMutex.withLock {
            activeGenerationCounts[sessionId] = (activeGenerationCounts[sessionId] ?: 0) + 1
        }
        return try {
            block()
        } finally {
            activeGenerationMutex.withLock {
                val remaining = (activeGenerationCounts[sessionId] ?: 1) - 1
                if (remaining <= 0) {
                    activeGenerationCounts.remove(sessionId)
                } else {
                    activeGenerationCounts[sessionId] = remaining
                }
            }
        }
    }

    private fun generationInProgressMessage(snapshot: MealPlanSnapshot): String = when (snapshot.pendingGenerationKind) {
        PendingGenerationKind.PLAN ->
            "I'm still building your meal plan. Give me a moment."
        PendingGenerationKind.RECIPE ->
            snapshot.pendingGenerationDayIndex?.let { "I'm still finishing Day ${it + 1}. Give me a moment." }
                ?: "I'm still finishing your meal plan. Give me a moment."
        null -> "I'm still working on your meal plan. Give me a moment."
    }

    private fun promptForSnapshot(snapshot: MealPlanSnapshot): String = when (snapshot.status) {
        MealPlanSessionStatus.COLLECTING_REQUIRED_SLOTS -> {
            val missing = missingSlots(snapshot)
            if (missing.isEmpty()) {
                promptForPreferenceEditing(snapshot)
            } else {
                promptForMissingSlots(snapshot, missing)
            }
        }
        MealPlanSessionStatus.PLAN_REVIEW -> planReviewPrompt(snapshot)
        MealPlanSessionStatus.RECIPES_IN_PROGRESS,
        MealPlanSessionStatus.AWAITING_USER_EDIT_OR_RECOVERY -> {
            val failedDay = snapshot.days.firstOrNull { it.status == MealPlanDayStatus.FAILED }
            when {
                failedDay != null -> recoveryPrompt(failedDay.dayIndex)
                snapshot.days.any { it.status != MealPlanDayStatus.PERSISTED && it.status != MealPlanDayStatus.FAILED } -> {
                    val nextPending = snapshot.days.first { it.status != MealPlanDayStatus.PERSISTED && it.status != MealPlanDayStatus.FAILED }
                    resumePrompt(snapshot, nextPending.dayIndex)
                }
                else -> "Your meal plan is ready. Say 'regenerate day 2', 'replace day 1', or 'done meal planning' to finalize it."
            }
        }
        MealPlanSessionStatus.COMPLETED -> "Your meal plan is already finalized."
        MealPlanSessionStatus.CANCELLED -> "That meal plan session was cancelled."
    }

    private fun promptForMissingSlots(snapshot: MealPlanSnapshot, missing: List<String>): String {
        val knownBits = buildKnownBits(snapshot)
        val prompt = missing.joinToString("\n") { missingSlotPrompt(it) }
        return buildString {
            if (knownBits.isNotEmpty()) {
                append("Got it so far: ${knownBits.joinToString(", ")}.\n\n")
            }
            append("I need a few details before I build the plan:\n")
            append(prompt)
        }
    }

    private fun promptForPreferenceEditing(snapshot: MealPlanSnapshot): String = buildString {
        append("Current plan details: ")
        append(buildKnownBits(snapshot).ifEmpty { listOf("no saved details yet") }.joinToString(", "))
        append(".\n\n")
        append("Reply with any updated people count, days, dietary requirements, or protein preferences.")
    }

    private fun buildKnownBits(snapshot: MealPlanSnapshot): List<String> = buildList {
        snapshot.peopleCount?.let { add("$it people") }
        snapshot.daysCount?.let { add("$it days") }
        if (snapshot.dietaryRestrictions.isNotEmpty()) add(snapshot.dietaryRestrictions.joinToString())
        if (snapshot.proteinPreferences.isNotEmpty()) add(snapshot.proteinPreferences.joinToString())
    }

    private fun missingSlots(snapshot: MealPlanSnapshot): List<String> = buildList {
        if (snapshot.peopleCount == null) add("people")
        if (snapshot.daysCount == null) add("days")
        if (snapshot.dietaryRestrictions.isEmpty()) add("dietary")
        if (snapshot.proteinPreferences.isEmpty()) add("protein")
    }

    private fun missingSlotPrompt(slot: String): String = when (slot) {
        "people" -> "- How many people are you cooking for?"
        "days" -> "- How many days do you want to plan for?"
        "dietary" -> "- Any dietary requirements?"
        "protein" -> "- What protein preferences should I use?"
        else -> "- $slot"
    }

    private fun planReviewPrompt(snapshot: MealPlanSnapshot): String =
        if (snapshot.days.isEmpty()) {
            "I still need to rebuild your meal plan draft. Say 'generate recipes' to try again, 'change preferences', or 'cancel'."
        } else {
            buildPlanSummary(snapshot) + "\n\nSay 'generate recipes', 'replace day 2', 'change preferences', or 'cancel'."
        }

    private fun resumePrompt(snapshot: MealPlanSnapshot, dayIndex: Int): String =
        "I still need to finish Day ${dayIndex + 1} of ${snapshot.days.size}. Say 'generate recipes' to continue or 'cancel' to stop."

    private fun recoveryPrompt(dayIndex: Int): String =
        "I still need to finish Day ${dayIndex + 1}. Say 'regenerate day ${dayIndex + 1}' or 'replace day ${dayIndex + 1}'."

    private fun replacementFailureMessage(dayIndex: Int, detail: String?): String =
        "I couldn't replace Day ${dayIndex + 1}. ${detail ?: "Try again with a different day."}"

    private fun buildPlanSummary(snapshot: MealPlanSnapshot): String = buildString {
        append("Here’s the meal plan I built for ${snapshot.peopleCount} people over ${snapshot.daysCount} days")
        if (snapshot.dietaryRestrictions.isNotEmpty()) {
            append(" (${snapshot.dietaryRestrictions.joinToString()})")
        }
        append(":\n")
        snapshot.days.sortedBy { it.dayIndex }.forEach { day ->
            append("- Day ${day.dayIndex + 1}: ${day.title ?: "Meal"}")
            day.summary?.let { append(" — $it") }
            append('\n')
        }
    }

    private fun formatRecipeSection(dayIndex: Int, recipe: RecipeDraft): String = buildString {
        append("Day ${dayIndex + 1}: ${recipe.title}\n")
        append("Serves ${recipe.servings}\n\n")
        append("Ingredients:\n")
        recipe.ingredients.forEach { ingredient ->
            append("- ${ingredient.originalText}\n")
        }
        append("\nMethod:\n")
        recipe.methodSteps.forEach { step ->
            append("${step.stepNumber}. ${step.text}\n")
        }
    }.trim()

    private fun buildPlanSystemPrompt(): String = """
You generate a high-level meal plan for a local-first Android assistant.
Output ONLY valid JSON with this exact shape:
{
  "days": [
    {
      "day_index": 0,
      "title": "...",
      "summary": "...",
      "protein_tags": ["..."]
    }
  ]
}
Rules:
- return exactly the requested number of days
- day_index values must be contiguous starting at 0
- titles must be realistic family dinner dish names
- summaries must be short and plain
- do not include ingredients, quantities, steps, markdown, commentary, or code fences
""".trimIndent()

    private fun buildPlanUserPrompt(snapshot: MealPlanSnapshot): String {
        val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH))
        return """
Build a ${snapshot.daysCount}-day dinner meal plan for ${snapshot.peopleCount} people.
Date: $now
Dietary requirements: ${snapshot.dietaryRestrictions.ifEmpty { listOf("none provided") }.joinToString()}
Protein preferences: ${snapshot.proteinPreferences.ifEmpty { listOf("no preference provided") }.joinToString()}
Use practical weeknight meal ideas suitable for Australia/New Zealand households.
""".trimIndent()
    }

    private fun buildRecipeSystemPrompt(): String = """
You generate one recipe day for a local-first Android assistant.
Output ONLY valid JSON with this exact shape:
{
  "title": "...",
  "servings": 4,
  "ingredients": [
    "500 g chicken breast, sliced",
    "1 tbsp olive oil"
  ],
  "method_steps": [
    "Heat the oven to 220C.",
    "Roast the chicken and vegetables until cooked through."
  ]
}
Rules:
- output JSON only
- servings must exactly match the requested value
- keep the recipe compact: 6-8 ingredients and 3-5 method steps
- keep ingredient quantities realistic for a household recipe
- use metric-friendly units when certain
- every ingredient line must be a single concise string
- every method step must be a single concise string with the action only
- never emit absurd magnitudes such as thousands of kilograms, litres, or spoonfuls
- do not emit markdown, commentary, or code fences
""".trimIndent()

    private fun buildRecipeUserPrompt(snapshot: MealPlanSnapshot, dayIndex: Int): String {
        val day = snapshot.days.first { it.dayIndex == dayIndex }
        return """
Generate the full recipe for Day ${dayIndex + 1}.
Servings: ${snapshot.peopleCount}
Dietary requirements: ${snapshot.dietaryRestrictions.ifEmpty { listOf("none provided") }.joinToString()}
Protein preferences: ${snapshot.proteinPreferences.ifEmpty { listOf("no preference provided") }.joinToString()}
Dish title: ${day.title}
Dish summary: ${day.summary ?: ""}
Provide a practical dinner recipe with a concise ingredient list and clear numbered method steps.
""".trimIndent()
    }

    private fun buildReplacementDaySystemPrompt(dayIndex: Int): String = """
You generate a replacement high-level meal-plan day for a local-first Android assistant.
Output ONLY valid JSON with this exact shape:
{
  "days": [
    {
      "day_index": $dayIndex,
      "title": "...",
      "summary": "...",
      "protein_tags": ["..."]
    }
  ]
}
Rules:
- output exactly one replacement day object for day_index $dayIndex
- do not repeat the existing day title verbatim if you can avoid it
- do not include ingredients, quantities, steps, markdown, commentary, or code fences
""".trimIndent()

    private fun buildReplacementDayUserPrompt(snapshot: MealPlanSnapshot, dayIndex: Int): String = """
Replace Day ${dayIndex + 1} in this meal plan.
Current plan:
${snapshot.days.joinToString("\n") { "Day ${it.dayIndex + 1}: ${it.title}" }}
Dietary requirements: ${snapshot.dietaryRestrictions.ifEmpty { listOf("none provided") }.joinToString()}
Protein preferences: ${snapshot.proteinPreferences.ifEmpty { listOf("no preference provided") }.joinToString()}
Return one alternative day that fits the plan without duplicating the current Day ${dayIndex + 1} dish.
""".trimIndent()

    private fun isFinalizeRequest(text: String): Boolean =
        Regex("\\b(?:done meal planning|done with meal planning|finalize meal plan|finalise meal plan|done)\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(text)
}

data class MealPlannerReply(
    val content: String,
)

private data class GeneratedRecipeResult(
    val snapshot: MealPlanSnapshot,
    val recipe: RecipeDraft,
    val dayTitle: String,
)
