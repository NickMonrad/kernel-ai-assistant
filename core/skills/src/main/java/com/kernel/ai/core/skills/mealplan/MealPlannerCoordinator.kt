package com.kernel.ai.core.skills.mealplan

import android.util.Log
import com.kernel.ai.core.inference.EmbeddingEngine
import com.kernel.ai.core.inference.InferenceEngine
import com.kernel.ai.core.memory.mealplan.FavouriteRecipeMode
import com.kernel.ai.core.memory.mealplan.FavouriteRecipeSummary
import com.kernel.ai.core.memory.mealplan.MealPlanDayStatus
import com.kernel.ai.core.memory.mealplan.MealPlanDraft
import com.kernel.ai.core.memory.mealplan.MealPlanDraftDay
import com.kernel.ai.core.memory.mealplan.MealPlanSessionStatus
import com.kernel.ai.core.memory.mealplan.MealPlanSnapshot
import com.kernel.ai.core.memory.mealplan.PendingGenerationKind
import com.kernel.ai.core.memory.mealplan.RecentMealHistoryEntry
import com.kernel.ai.core.memory.mealplan.RecipeDraft
import com.kernel.ai.core.memory.repository.MealPlanSessionRepository
import com.kernel.ai.core.memory.repository.MemoryRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_FAVOURITE_PROMPT_RECIPES = 6

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
    private val shownResumePromptsBySessionId = mutableMapOf<String, String>()
    private val shownResumePromptsMutex = Mutex()

    suspend fun hasActiveSession(conversationId: String): Boolean =
        sessionRepository.hasActiveSessionForConversation(conversationId)

    suspend fun hasAnySession(conversationId: String): Boolean =
        sessionRepository.hasAnySessionForConversation(conversationId)

    suspend fun activeSessionReply(conversationId: String): MealPlannerReply? {
        val snapshot = sessionRepository.getActiveSession(conversationId) ?: return null
        val prompt = promptForSnapshot(snapshot)
        return shownResumePromptsMutex.withLock {
            if (shownResumePromptsBySessionId[snapshot.sessionId] == prompt) {
                null
            } else {
                shownResumePromptsBySessionId[snapshot.sessionId] = prompt
                MealPlannerReply(prompt)
            }
        }
    }

    private suspend fun clearShownResumePrompt(sessionId: String) {
        shownResumePromptsMutex.withLock {
            shownResumePromptsBySessionId.remove(sessionId)
        }
    }

    suspend fun activeSessionActivity(conversationId: String): MealPlannerActivity? {
        val snapshot = sessionRepository.getActiveSession(conversationId) ?: return null
        return activityForSnapshot(snapshot, generationActive = isGenerationActive(snapshot.sessionId))
    }

    suspend fun startOrResume(conversationId: String): MealPlannerReply {
        writePendingCompletedSummariesIfNeeded()
        val snapshot = sessionRepository.startOrResume(conversationId)
        return MealPlannerReply(promptForSnapshot(snapshot))
    }

    private suspend fun writePendingCompletedSummariesIfNeeded() {
        sessionRepository.getPendingCompletedSummarySessions(PENDING_COMPLETED_SUMMARY_LIMIT)
            .forEach { snapshot ->
                runCatching { writeFinalSummaryIfNeeded(snapshot) }
                    .onFailure {
                        runCatching {
                            Log.w(TAG, "Failed to recover completed meal-plan summary for ${snapshot.sessionId}", it)
                        }
                    }
            }
    }

    suspend fun ingestUserMessage(
        conversationId: String,
        text: String,
        onPlannerMessage: suspend (String) -> Unit = {},
        onPlannerActivityChanged: suspend (MealPlannerActivity) -> Unit = {},
    ): MealPlannerReply {
        val snapshot = sessionRepository.getActiveSession(conversationId)
            ?: return startOrResume(conversationId)

        if (slotExtractor.isCancelRequest(text)) {
            sessionRepository.cancelSession(snapshot.sessionId)
            clearShownResumePrompt(snapshot.sessionId)
            return MealPlannerReply("Okay — I’ve cancelled this meal plan session.")
        }

        val generationActive = isGenerationActive(snapshot.sessionId)
        if (slotExtractor.isHelpRequest(text)) {
            return MealPlannerReply(helpPrompt(snapshot, generationActive))
        }

        if (generationActive) {
            return MealPlannerReply(generationInProgressMessage(snapshot))
        }

        return when (snapshot.status) {
            MealPlanSessionStatus.COLLECTING_REQUIRED_SLOTS -> handleCollecting(snapshot, text, onPlannerActivityChanged)
            MealPlanSessionStatus.PLAN_REVIEW -> handlePlanReview(snapshot, text, onPlannerMessage, onPlannerActivityChanged)
            MealPlanSessionStatus.RECIPES_IN_PROGRESS,
            MealPlanSessionStatus.AWAITING_USER_EDIT_OR_RECOVERY ->
                handleActiveOrRecovery(snapshot, text, onPlannerMessage, onPlannerActivityChanged)
            MealPlanSessionStatus.COMPLETED -> MealPlannerReply(
                "This meal plan is already finalized. Start a new one by asking me to plan meals again.",
            )
            MealPlanSessionStatus.CANCELLED -> startOrResume(conversationId)
        }
    }

    private suspend fun handleCollecting(
        snapshot: MealPlanSnapshot,
        text: String,
        onPlannerActivityChanged: suspend (MealPlannerActivity) -> Unit,
    ): MealPlannerReply {
        if (slotExtractor.isShowCurrentPlanRequest(text)) {
            return MealPlannerReply(currentPlanReply(snapshot))
        }
        val favouriteDayIndex = slotExtractor.extractFavouriteDayIndex(text)
        if (favouriteDayIndex != null) {
            return favouriteManagementMovedToUiReply(snapshot)
        }
        val unfavouriteDayIndex = slotExtractor.extractUnfavouriteDayIndex(text)
        if (unfavouriteDayIndex != null) {
            return favouriteManagementMovedToUiReply(snapshot)
        }
        val missingBefore = missingSlots(snapshot)
        val peopleCount = slotExtractor.extractPeopleCount(text)
        val daysCount = slotExtractor.extractDaysCount(text)
        val removedDietaryRestrictions = slotExtractor.extractRemovedDietaryRestrictions(text).orEmpty()
        val dietaryRestrictions = slotExtractor.extractDietaryRestrictions(text)
        val updatedDietaryRestrictions = mergeUpdatedDietaryRestrictions(
            current = snapshot.dietaryRestrictions,
            added = dietaryRestrictions,
            removed = removedDietaryRestrictions,
        )
        val removedProteinPreferences = slotExtractor.extractRemovedProteinPreferences(text).orEmpty()
        val proteinPreferences = slotExtractor.extractProteinPreferences(
            text,
            allowBareNoPreference = missingBefore == listOf("protein"),
        )
        val updatedProteinPreferences = mergeUpdatedProteinPreferences(
            current = snapshot.proteinPreferences,
            added = proteinPreferences,
            removed = removedProteinPreferences,
        )
        val removedCuisinePreferences = slotExtractor.extractRemovedCuisinePreferences(text).orEmpty()
        val cuisinePreferences = slotExtractor.extractCuisinePreferences(
            text,
            allowBareNoPreference = missingBefore == listOf("cuisine"),
        )
        val updatedCuisinePreferences = mergeUpdatedCuisinePreferences(
            current = snapshot.cuisinePreferences,
            added = cuisinePreferences,
            removed = removedCuisinePreferences,
        )
        val favouriteRecipeMode = slotExtractor.extractFavouriteRecipeMode(text)
        val mergedDietaryRestrictions = updatedDietaryRestrictions ?: snapshot.dietaryRestrictions
        val mergedProteinPreferences = updatedProteinPreferences ?: snapshot.proteinPreferences
        val shouldClearProteinPreferences =
            (updatedDietaryRestrictions != null || updatedProteinPreferences != null) &&
                detectProteinPreferenceConflicts(mergedDietaryRestrictions, mergedProteinPreferences).isNotEmpty()
        val updated = sessionRepository.updateRequiredSlots(
            sessionId = snapshot.sessionId,
            peopleCount = peopleCount,
            daysCount = daysCount,
            dietaryRestrictions = updatedDietaryRestrictions,
            proteinPreferences = if (shouldClearProteinPreferences) emptyList() else updatedProteinPreferences,
            cuisinePreferences = updatedCuisinePreferences,
            favouriteRecipeMode = favouriteRecipeMode,
        )
        if (shouldClearProteinPreferences) {
            val conflicts = detectProteinPreferenceConflicts(updated.dietaryRestrictions, mergedProteinPreferences)
            return MealPlannerReply(proteinCompatibilityPrompt(updated, conflicts))
        }
        val missing = missingSlots(updated)
        if (missing.isNotEmpty()) {
            return MealPlannerReply(promptForMissingSlots(updated, missing))
        }
        val hadAllSlotsBefore = missingSlots(snapshot).isEmpty()
        val hasStructuralSlotUpdates =
            peopleCount != null ||
                daysCount != null ||
                updatedDietaryRestrictions != null ||
                updatedProteinPreferences != null ||
                updatedCuisinePreferences != null
        if (hadAllSlotsBefore && !hasStructuralSlotUpdates) {
            if (slotExtractor.isGenerateRecipesRequest(text)) {
                return generatePlanForReview(updated, onPlannerActivityChanged)
            }
            return MealPlannerReply(promptForPreferenceEditing(updated))
        }
        return generatePlanForReview(updated, onPlannerActivityChanged)
    }

    private suspend fun handlePlanReview(
        snapshot: MealPlanSnapshot,
        text: String,
        onPlannerMessage: suspend (String) -> Unit,
        onPlannerActivityChanged: suspend (MealPlannerActivity) -> Unit,
    ): MealPlannerReply {
        if (slotExtractor.isShowCurrentPlanRequest(text)) {
            return MealPlannerReply(currentPlanReply(snapshot))
        }
        val favouriteDayIndex = slotExtractor.extractFavouriteDayIndex(text)
        if (favouriteDayIndex != null) {
            return favouriteManagementMovedToUiReply(snapshot)
        }
        val unfavouriteDayIndex = slotExtractor.extractUnfavouriteDayIndex(text)
        if (unfavouriteDayIndex != null) {
            return favouriteManagementMovedToUiReply(snapshot)
        }
        val favouriteRecipeMode = slotExtractor.extractFavouriteRecipeMode(text)
        if (favouriteRecipeMode != null) {
            sessionRepository.returnToSlotCollection(snapshot.sessionId)
            val updated = sessionRepository.updateRequiredSlots(
                sessionId = snapshot.sessionId,
                favouriteRecipeMode = favouriteRecipeMode,
            )
            return MealPlannerReply(promptForPreferenceEditing(updated))
        }
        val replaceDayIndices = slotExtractor.extractReplaceDayIndices(text)
        if (replaceDayIndices != null) {
            return replaceDaysForReview(snapshot, replaceDayIndices, onPlannerActivityChanged)
        }
        if (slotExtractor.isChangePreferencesRequest(text)) {
            val editable = sessionRepository.returnToSlotCollection(snapshot.sessionId)
            return MealPlannerReply(promptForPreferenceEditing(editable))
        }
        if (slotExtractor.isGenerateRecipesRequest(text)) {
            return if (snapshot.days.isEmpty()) {
                generatePlanForReview(snapshot, onPlannerActivityChanged)
            } else {
                generatePendingRecipesFrom(
                    snapshot,
                    onPlannerMessage = onPlannerMessage,
                    onPlannerActivityChanged = onPlannerActivityChanged,
                )
            }
        }
        return MealPlannerReply(planReviewPrompt(snapshot))
    }

    private suspend fun handleActiveOrRecovery(
        snapshot: MealPlanSnapshot,
        text: String,
        onPlannerMessage: suspend (String) -> Unit,
        onPlannerActivityChanged: suspend (MealPlannerActivity) -> Unit,
    ): MealPlannerReply {
        val failedDay = snapshot.days.firstOrNull { it.status == MealPlanDayStatus.FAILED }
        val pendingDay = snapshot.days.firstOrNull { it.status != MealPlanDayStatus.PERSISTED && it.status != MealPlanDayStatus.FAILED }
        val replaceDayIndices = slotExtractor.extractReplaceDayIndices(text)
        val regenerateDayIndices = slotExtractor.extractRegenerateDayIndices(text)
        val favouriteRecipeMode = slotExtractor.extractFavouriteRecipeMode(text)
        val favouriteDayIndex = slotExtractor.extractFavouriteDayIndex(text)
        val unfavouriteDayIndex = slotExtractor.extractUnfavouriteDayIndex(text)
        if (slotExtractor.isShowCurrentPlanRequest(text)) {
            return MealPlannerReply(currentPlanReply(snapshot))
        }
        if (favouriteRecipeMode != null) {
            sessionRepository.returnToSlotCollection(snapshot.sessionId)
            val updated = sessionRepository.updateRequiredSlots(
                sessionId = snapshot.sessionId,
                favouriteRecipeMode = favouriteRecipeMode,
            )
            return MealPlannerReply(promptForPreferenceEditing(updated))
        }
        val interruptedReviewReplacementDayIndex = snapshot.pendingGenerationDayIndex?.takeIf {
            snapshot.pendingGenerationKind == PendingGenerationKind.REPLACEMENT &&
                snapshot.days.isNotEmpty() &&
                snapshot.days.all { day -> day.status == MealPlanDayStatus.DRAFTED }
        }
        if (interruptedReviewReplacementDayIndex != null) {
            if (slotExtractor.isRetryRequest(text) || replaceDayIndices?.singleOrNull() == interruptedReviewReplacementDayIndex) {
                return replaceDayForReview(snapshot, interruptedReviewReplacementDayIndex, onPlannerActivityChanged)
            }
            if (replaceDayIndices == null && regenerateDayIndices == null) {
                return MealPlannerReply(replacementRetryPrompt(interruptedReviewReplacementDayIndex))
            }
        }
        val interruptedReplacementDayIndex = snapshot.pendingGenerationDayIndex?.takeIf {
            snapshot.pendingGenerationKind == PendingGenerationKind.REPLACEMENT &&
                snapshot.days.isNotEmpty() &&
                snapshot.days.all { day -> day.status == MealPlanDayStatus.PERSISTED }
        }
        if (interruptedReplacementDayIndex != null) {
            if (slotExtractor.isRetryRequest(text) || replaceDayIndices?.singleOrNull() == interruptedReplacementDayIndex) {
                return replaceDayAndGenerateRecipe(
                    snapshot = snapshot,
                    dayIndex = interruptedReplacementDayIndex,
                    intro = "I replaced Day ${interruptedReplacementDayIndex + 1}. Here’s the updated recipe:\n",
                    onPlannerActivityChanged = onPlannerActivityChanged,
                )
            }
            if (replaceDayIndices == null && regenerateDayIndices == null) {
                return MealPlannerReply(replacementRetryPrompt(interruptedReplacementDayIndex))
            }
        }
        val interruptedRecipeDayIndex = snapshot.pendingGenerationDayIndex?.takeIf {
            snapshot.pendingGenerationKind == PendingGenerationKind.RECIPE && snapshot.days.all { day -> day.status == MealPlanDayStatus.PERSISTED }
        }
        if (interruptedRecipeDayIndex != null) {
            if (slotExtractor.isRetryRequest(text) || regenerateDayIndices?.singleOrNull() == interruptedRecipeDayIndex) {
                return generateSpecificDayRecipe(
                    snapshot,
                    interruptedRecipeDayIndex,
                    intro = "I regenerated Day ${interruptedRecipeDayIndex + 1}. Here’s the updated recipe:\n",
                    mutationSummary = regenerationChangeMessage(snapshot, interruptedRecipeDayIndex),
                    includeCurrentPlanReply = true,
                    onPlannerActivityChanged = onPlannerActivityChanged,
                )
            }
            if (replaceDayIndices == null && regenerateDayIndices == null) {
                return MealPlannerReply(regenerateRetryPrompt(snapshot, interruptedRecipeDayIndex))
            }
        }
        if (failedDay == null && pendingDay != null) {
            if (slotExtractor.isGenerateRecipesRequest(text) || slotExtractor.isRetryRequest(text)) {
                return generatePendingRecipesFrom(
                    snapshot,
                    intro = "Resuming your meal plan at Day ${pendingDay.dayIndex + 1} of ${snapshot.days.size}…",
                    onPlannerMessage = onPlannerMessage,
                    onPlannerActivityChanged = onPlannerActivityChanged,
                )
            }
            return MealPlannerReply(resumePrompt(snapshot, pendingDay.dayIndex))
        }
        if (snapshot.pendingGenerationKind == PendingGenerationKind.PLAN && snapshot.days.isEmpty()) {
            return MealPlannerReply(planReviewPrompt(snapshot))
        }
        if (favouriteDayIndex != null) {
            return favouriteManagementMovedToUiReply(snapshot)
        }
        if (unfavouriteDayIndex != null) {
            return favouriteManagementMovedToUiReply(snapshot)
        }
        if (replaceDayIndices != null) {
            return replaceDaysAndGenerateRecipes(
                snapshot = snapshot,
                dayIndices = replaceDayIndices,
                onPlannerActivityChanged = onPlannerActivityChanged,
            )
        }
        if (regenerateDayIndices != null) {
            return regenerateSpecificDayRecipes(
                snapshot = snapshot,
                dayIndices = regenerateDayIndices,
                onPlannerActivityChanged = onPlannerActivityChanged,
            )
        }
        if (isFinalizeRequest(text) && snapshot.days.isNotEmpty() && snapshot.days.all { it.status == MealPlanDayStatus.PERSISTED }) {
            val completed = sessionRepository.completeSession(snapshot.sessionId)
            clearShownResumePrompt(snapshot.sessionId)
            runCatching { writeFinalSummaryIfNeeded(completed) }
                .onFailure {
                    runCatching {
                        Log.w(TAG, "Failed to finalize meal-plan summary for ${snapshot.sessionId}", it)
                    }
                }
            return MealPlannerReply("Meal planning is finalized. Your per-plan shopping list and recipe lists are ready.")
        }
        if (failedDay != null) {
            return MealPlannerReply(recoveryPrompt(failedDay.dayIndex))
        }
        return MealPlannerReply(readyToFinalizePrompt())
    }

    private suspend fun generatePlanForReview(
        snapshot: MealPlanSnapshot,
        onPlannerActivityChanged: suspend (MealPlannerActivity) -> Unit,
    ): MealPlannerReply =
        withSessionGeneration(
            sessionId = snapshot.sessionId,
            onBusy = { MealPlannerReply(generationInProgressMessage(snapshot)) },
        ) {
            sessionRepository.markPendingGeneration(snapshot.sessionId, PendingGenerationKind.PLAN)
            onPlannerActivityChanged(generatingPlanActivity(snapshot))
            val recentHistory = sessionRepository.getRecentMealHistory(RECENT_MEAL_HISTORY_LIMIT)
            val favouriteRecipes = sessionRepository.getFavouriteRecipes(MAX_FAVOURITE_PROMPT_RECIPES)
            val rawPlan = inferenceEngine.generateOnce(
                prompt = buildPlanUserPrompt(snapshot, recentHistory, favouriteRecipes),
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
            val parsedPlan = try {
                jsonParser.parsePlanDraft(rawPlan, snapshot.daysCount ?: 0)
            } catch (e: MealPlanValidationException) {
                sessionRepository.markGenerationFailure(snapshot.sessionId, null, "PLAN_JSON_INVALID", e.message ?: "Invalid plan JSON")
                return@withSessionGeneration MealPlannerReply("I couldn't generate a valid high-level plan yet. ${e.message} Try replying with the same requirements again or adjust them.")
            }
            val planDraft = try {
                repairPlanVariety(
                    snapshot = snapshot,
                    draft = parsedPlan,
                    recentHistory = recentHistory,
                )
            } catch (e: MealPlanValidationException) {
                sessionRepository.markGenerationFailure(snapshot.sessionId, null, "PLAN_VARIETY_REPAIR_FAILED", e.message ?: "Plan was too repetitive")
                return@withSessionGeneration MealPlannerReply("I couldn't generate a varied enough high-level plan yet. ${e.message} Try replying with the same requirements again or adjust them.")
            }
            val planned = sessionRepository.savePlanDraft(snapshot.sessionId, planDraft.days)
            MealPlannerReply(planReviewPrompt(planned))
        }

    private suspend fun repairPlanVariety(
        snapshot: MealPlanSnapshot,
        draft: MealPlanDraft,
        recentHistory: List<RecentMealHistoryEntry>,
    ): MealPlanDraft {
        val enforceRecentPatternDiversity = shouldEnforceRecentPatternDiversity(snapshot)
        var workingDays = draft.days.sortedBy { it.dayIndex }
        for (pass in 0 until MAX_PLAN_VARIETY_REPAIR_PASSES) {
            val conflicts = detectPlanVarietyConflicts(
                days = workingDays,
                recentHistory = recentHistory,
                enforceRecentPatternDiversity = enforceRecentPatternDiversity,
            ).distinctBy { it.dayIndex }
            if (conflicts.isEmpty()) {
                return MealPlanDraft(workingDays)
            }
            if (pass > 0 && conflicts.none(PlanVarietyConflict::isBlocking)) {
                return MealPlanDraft(workingDays)
            }
            conflicts.forEach { conflict ->
                val replacement = generateReplacementDraftForPlan(
                    snapshot = snapshot,
                    currentDays = workingDays,
                    dayIndex = conflict.dayIndex,
                    recentHistory = recentHistory,
                )
                workingDays = workingDays.toMutableList().also { days ->
                    days[conflict.dayIndex] = replacement
                }
            }
        }
        val remaining = detectPlanVarietyConflicts(
            days = workingDays,
            recentHistory = recentHistory,
            enforceRecentPatternDiversity = enforceRecentPatternDiversity,
        ).distinctBy { it.dayIndex }
        val blocking = remaining.filter(PlanVarietyConflict::isBlocking)
        if (blocking.isNotEmpty()) {
            throw MealPlanValidationException(
                blocking.joinToString(
                    prefix = "Plan repeated recent meals too closely: ",
                    separator = "; ",
                    postfix = ".",
                ) { conflict -> "Day ${conflict.dayIndex + 1} ${conflict.reason}" },
            )
        }
        return MealPlanDraft(workingDays)
    }

    private suspend fun generateReplacementDraftForPlan(
        snapshot: MealPlanSnapshot,
        currentDays: List<MealPlanDraftDay>,
        dayIndex: Int,
        recentHistory: List<RecentMealHistoryEntry>,
    ): MealPlanDraftDay {
        val enforceRecentPatternDiversity = shouldEnforceRecentPatternDiversity(snapshot)
        val favouriteRecipes = sessionRepository.getFavouriteRecipes(MAX_FAVOURITE_PROMPT_RECIPES)
        repeat(MAX_DAY_VARIETY_REPAIR_ATTEMPTS) {
            val raw = inferenceEngine.generateOnce(
                prompt = buildReplacementDayUserPrompt(snapshot, currentDays, dayIndex, recentHistory, favouriteRecipes),
                systemPrompt = buildReplacementDaySystemPrompt(dayIndex),
                thinkingEnabled = false,
                stopOnFirstJsonObject = true,
            )
            if (raw.isBlank()) {
                return@repeat
            }
            val replacement = runCatching {
                jsonParser.parseSinglePlanDay(raw, dayIndex)
            }.getOrNull() ?: return@repeat
            val candidateDays = currentDays.toMutableList().also { days ->
                days[dayIndex] = replacement
            }
            if (
                detectPlanVarietyConflicts(
                    days = candidateDays,
                    recentHistory = recentHistory,
                    enforceRecentPatternDiversity = enforceRecentPatternDiversity,
                ).none { it.dayIndex == dayIndex && it.isBlocking }
            ) {
                return replacement
            }
        }
        throw MealPlanValidationException("I couldn't make Day ${dayIndex + 1} distinct enough from the rest of the plan.")
    }

    private fun detectPlanVarietyConflicts(
        days: List<MealPlanDraftDay>,
        recentHistory: List<RecentMealHistoryEntry>,
        enforceRecentPatternDiversity: Boolean,
    ): List<PlanVarietyConflict> {
        val historyTitles = recentHistory.map { normalizeMealTitle(it.title) }
            .filter { it.isNotBlank() }
            .toSet()
        val historyPatterns = if (enforceRecentPatternDiversity) {
            recentHistory.mapNotNull { repeatPatternKey(it.title, it.summary, it.proteinTags) }
                .toSet()
        } else {
            emptySet()
        }
        val seenTitles = mutableSetOf<String>()
        val seenPatterns = mutableSetOf<String>()
        return buildList {
            days.sortedBy { it.dayIndex }.forEach { day ->
                val titleKey = normalizeMealTitle(day.title)
                val patternKey = repeatPatternKey(day.title, day.summary, day.proteinTags)
                when {
                    titleKey.isNotBlank() && titleKey in historyTitles -> add(
                        PlanVarietyConflict(day.dayIndex, "repeats a recent meal title", isBlocking = true),
                    )
                    titleKey.isNotBlank() && titleKey in seenTitles -> add(
                        PlanVarietyConflict(day.dayIndex, "duplicates another day in the same plan", isBlocking = true),
                    )
                    patternKey != null && patternKey in historyPatterns -> add(
                        PlanVarietyConflict(day.dayIndex, "matches a recent protein and cooking style too closely", isBlocking = false),
                    )
                    patternKey != null && patternKey in seenPatterns -> add(
                        PlanVarietyConflict(day.dayIndex, "uses the same protein and cooking style as another day in the plan", isBlocking = true),
                    )
                }
                if (titleKey.isNotBlank()) {
                    seenTitles += titleKey
                }
                patternKey?.let(seenPatterns::add)
            }
        }
    }

    private fun detectProteinPreferenceConflicts(
        dietaryRestrictions: List<String>,
        proteinPreferences: List<String>,
    ): List<String> {
        val normalizedRestrictions = dietaryRestrictions.map(::normalizeLooseText).toSet()
        return proteinPreferences
            .map(::normalizeProteinTag)
            .filter { it.isNotBlank() && it != "no protein preference" }
            .distinct()
            .filterNot { proteinAllowedForDietaryRestrictions(it, normalizedRestrictions) }
    }

    private fun proteinAllowedForDietaryRestrictions(protein: String, restrictions: Set<String>): Boolean {
        if (protein == "no protein preference") {
            return true
        }
        if ("vegan" in restrictions) {
            return protein in setOf("tofu", "lentils", "beans", "chickpeas")
        }
        if ("vegetarian" in restrictions) {
            return protein in setOf("tofu", "lentils", "beans", "chickpeas", "eggs", "halloumi")
        }
        if ("pescatarian" in restrictions && protein in setOf("chicken", "beef", "turkey", "pork", "lamb")) {
            return false
        }
        if ("dairy free" in restrictions && protein == "halloumi") {
            return false
        }
        if (("lactose free" in restrictions || "lactose intolerant" in restrictions) && protein == "halloumi") {
            return false
        }
        if ("egg free" in restrictions && protein == "eggs") {
            return false
        }
        if ("soy free" in restrictions && protein == "tofu") {
            return false
        }
        if ("fish free" in restrictions && protein == "fish") {
            return false
        }
        if ("shellfish free" in restrictions && protein == "prawns") {
            return false
        }
        if ("halal" in restrictions && protein == "pork") {
            return false
        }
        if ("paleo" in restrictions && protein in setOf("tofu", "lentils", "beans", "chickpeas", "halloumi")) {
            return false
        }
        if ("keto" in restrictions && protein in setOf("lentils", "beans", "chickpeas")) {
            return false
        }
        return restrictions
            .filter { it.startsWith("no ") }
            .none { exclusion -> proteinMatchesExclusion(protein, exclusion.removePrefix("no ").trim()) }
    }

    private fun proteinMatchesExclusion(protein: String, excluded: String): Boolean {
        val normalizedExcluded = normalizeProteinTag(excluded)
        return when (protein) {
            "beef" -> normalizedExcluded == "beef" || excluded.contains("beef mince")
            "fish" -> normalizedExcluded == "fish" || excluded in setOf("salmon", "tuna", "snapper")
            "prawns" -> normalizedExcluded == "prawns" || excluded in setOf("prawn", "shrimp", "shellfish")
            else -> normalizedExcluded == protein
        }
    }

    private fun mergeUpdatedDietaryRestrictions(
        current: List<String>,
        added: List<String>?,
        removed: List<String>,
    ): List<String>? {
        if (added == null && removed.isEmpty()) return null
        val filteredAdded = added.orEmpty().filterNot { it in removed }
        if (filteredAdded.contains("no dietary requirements")) {
            val updated = listOf("no dietary requirements")
            return updated.takeUnless { it == current }
        }
        val updated = current
            .filterNot { it == "no dietary requirements" || it in removed }
            .toMutableList()
        filteredAdded
            .filterNot { it == "no dietary requirements" }
            .forEach { restriction ->
                if (restriction !in updated) {
                    updated += restriction
                }
            }
        return updated.takeUnless { it == current }
    }

    private fun mergeUpdatedProteinPreferences(
        current: List<String>,
        added: List<String>?,
        removed: List<String>,
    ): List<String>? {
        if (added == null && removed.isEmpty()) return null
        val filteredAdded = added.orEmpty().filterNot { it in removed }
        if (filteredAdded.contains("no protein preference")) {
            val updated = listOf("no protein preference")
            return updated.takeUnless { it == current }
        }
        val updated = current
            .filterNot { it == "no protein preference" || it in removed }
            .toMutableList()
        filteredAdded
            .filterNot { it == "no protein preference" }
            .forEach { protein ->
                if (protein !in updated) {
                    updated += protein
                }
            }
        return updated.takeUnless { it == current }
    }
    private fun mergeUpdatedCuisinePreferences(
        current: List<String>,
        added: List<String>?,
        removed: List<String>,
    ): List<String>? {
        if (added == null && removed.isEmpty()) return null
        val filteredAdded = added.orEmpty().filterNot { it in removed }
        if (filteredAdded.contains("no cuisine preference")) {
            val updated = listOf("no cuisine preference")
            return updated.takeUnless { it == current }
        }
        val updated = current
            .filterNot { it == "no cuisine preference" || it in removed }
            .toMutableList()
        filteredAdded
            .filterNot { it == "no cuisine preference" }
            .forEach { cuisine ->
                if (cuisine !in updated) {
                    updated += cuisine
                }
            }
        return updated.takeUnless { it == current }
    }
    private fun shouldEnforceRecentPatternDiversity(snapshot: MealPlanSnapshot): Boolean {
        val preferredProteins = snapshot.proteinPreferences
            .map(::normalizeProteinTag)
            .filter { it.isNotBlank() && it != "no protein preference" }
            .toSet()
        return preferredProteins.size != 1
    }

    private fun repeatPatternKey(title: String, summary: String?, proteinTags: List<String>): String? {
        val proteinSignature = proteinSignature(proteinTags, title) ?: return null
        val mealShape = mealShape(title, summary) ?: return null
        return "$proteinSignature::$mealShape"
    }

    private fun proteinSignature(proteinTags: List<String>, fallbackText: String): String? {
        val tags = proteinTags.map(::normalizeProteinTag)
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
        if (tags.isNotEmpty()) {
            return tags.joinToString("/")
        }
        return inferProteinFromText(fallbackText)
    }

    private fun normalizeProteinTag(raw: String): String {
        val normalized = normalizeLooseText(raw)
        return when {
            "chicken" in normalized -> "chicken"
            "beef" in normalized -> "beef"
            "turkey" in normalized -> "turkey"
            "pork" in normalized -> "pork"
            "lamb" in normalized -> "lamb"
            "salmon" in normalized || "fish" in normalized || "tuna" in normalized || "snapper" in normalized -> "fish"
            "prawn" in normalized || "shrimp" in normalized -> "prawns"
            "tofu" in normalized -> "tofu"
            "lentil" in normalized -> "lentils"
            "bean" in normalized -> "beans"
            "chickpea" in normalized -> "chickpeas"
            "egg" in normalized -> "eggs"
            "halloumi" in normalized -> "halloumi"
            else -> normalized
        }
    }

    private fun inferProteinFromText(text: String): String? {
        val normalized = normalizeLooseText(text)
        return COMMON_PROTEINS.firstOrNull { it in normalized }
    }

    private fun mealShape(title: String, summary: String?): String? {
        val normalized = normalizeLooseText(listOfNotNull(title, summary).joinToString(" "))
        return when {
            "stir fry" in normalized || "stir fried" in normalized -> "stir fry"
            "curry" in normalized -> "curry"
            "tray bake" in normalized || "sheet pan" in normalized || "roast" in normalized || "bake" in normalized -> "bake"
            "skillet" in normalized || "pan fried" in normalized || "pan seared" in normalized -> "skillet"
            "bowl" in normalized -> "bowl"
            "taco" in normalized || "fajita" in normalized -> "tacos"
            "wrap" in normalized -> "wrap"
            "pasta" in normalized || "spaghetti" in normalized || "lasagna" in normalized || "lasagne" in normalized -> "pasta"
            "noodle" in normalized || "ramen" in normalized -> "noodles"
            "soup" in normalized -> "soup"
            "stew" in normalized || "casserole" in normalized -> "stew"
            "salad" in normalized -> "salad"
            "burger" in normalized || "patty" in normalized -> "burger"
            "pie" in normalized -> "pie"
            "risotto" in normalized -> "risotto"
            "fritter" in normalized -> "fritters"
            else -> null
        }
    }

    private fun normalizeMealTitle(title: String): String =
        normalizeLooseText(title)
            .replace(MEAL_TITLE_NOISE_REGEX, " ")
            .replace(MULTISPACE_REGEX, " ")
            .trim()

    private fun normalizeLooseText(text: String): String =
        text.lowercase(Locale.ENGLISH)
            .replace(NON_ALNUM_REGEX, " ")
            .replace(MULTISPACE_REGEX, " ")
            .trim()

    private data class PlanVarietyConflict(
        val dayIndex: Int,
        val reason: String,
        val isBlocking: Boolean,
    )

    private suspend fun generatePendingRecipesFrom(
        snapshot: MealPlanSnapshot,
        intro: String? = null,
        onPlannerMessage: suspend (String) -> Unit = {},
        onPlannerActivityChanged: suspend (MealPlannerActivity) -> Unit,
    ): MealPlannerReply = withSessionGeneration(
        sessionId = snapshot.sessionId,
        onBusy = { MealPlannerReply(generationInProgressMessage(snapshot)) },
    ) {
        var currentSnapshot = sessionRepository.getSession(snapshot.sessionId) ?: snapshot
        if (currentSnapshot.status == MealPlanSessionStatus.CANCELLED) {
            return@withSessionGeneration MealPlannerReply("Okay — I’ve cancelled this meal plan session.")
        }
        if (!intro.isNullOrBlank()) {
            onPlannerMessage(intro.trim())
        }
        val totalDays = currentSnapshot.days.size
        val pendingDays = currentSnapshot.days
            .filter { it.status != MealPlanDayStatus.PERSISTED && it.status != MealPlanDayStatus.FAILED }
            .sortedBy { it.dayIndex }
        for (day in pendingDays) {
            currentSnapshot = sessionRepository.getSession(snapshot.sessionId) ?: currentSnapshot
            if (currentSnapshot.status == MealPlanSessionStatus.CANCELLED) {
                return@withSessionGeneration MealPlannerReply("Okay — I’ve cancelled this meal plan session.")
            }
            val generationSnapshot = try {
                prepareRecipeGeneration(currentSnapshot, day.dayIndex)
            } catch (e: MealPlanValidationException) {
                return@withSessionGeneration MealPlannerReply(
                    "I hit a validation problem while generating Day ${day.dayIndex + 1}: ${e.message}\nSay 'regenerate day ${day.dayIndex + 1}' or 'replace day ${day.dayIndex + 1}' to recover.",
                )
            }
            onPlannerActivityChanged(generatingRecipeActivity(generationSnapshot, day.dayIndex, totalDays))
            onPlannerMessage("Generating recipe ${day.dayIndex + 1} of $totalDays…")
            val recipeResult = try {
                generateAndPersistRecipe(generationSnapshot, day.dayIndex, markPendingGeneration = false)
            } catch (e: MealPlanValidationException) {
                val latestSnapshot = sessionRepository.getSession(snapshot.sessionId)
                if (latestSnapshot?.status == MealPlanSessionStatus.CANCELLED) {
                    return@withSessionGeneration MealPlannerReply("Okay — I’ve cancelled this meal plan session.")
                }
                return@withSessionGeneration MealPlannerReply(
                    "I hit a validation problem while generating Day ${day.dayIndex + 1}: ${e.message}\nSay 'regenerate day ${day.dayIndex + 1}' or 'replace day ${day.dayIndex + 1}' to recover, or 'help' for more options.",
                )
            }
            if (recipeResult.snapshot.status == MealPlanSessionStatus.CANCELLED) {
                return@withSessionGeneration MealPlannerReply("Okay — I’ve cancelled this meal plan session.")
            }
            onPlannerMessage(formatRecipeSection(day.dayIndex, recipeResult.recipe))
            currentSnapshot = recipeResult.snapshot
        }
        if (currentSnapshot.status == MealPlanSessionStatus.CANCELLED) {
            MealPlannerReply("Okay — I’ve cancelled this meal plan session.")
        } else if (currentSnapshot.days.all { it.status == MealPlanDayStatus.PERSISTED }) {
            MealPlannerReply(readyToFinalizePrompt())
        } else {
            val nextPending = currentSnapshot.days.firstOrNull { it.status != MealPlanDayStatus.PERSISTED && it.status != MealPlanDayStatus.FAILED }
            MealPlannerReply(nextPending?.let { resumePrompt(currentSnapshot, it.dayIndex) } ?: "Your meal plan is ready.")
        }
    }

    private suspend fun generateSpecificDayRecipe(
        snapshot: MealPlanSnapshot,
        dayIndex: Int,
        intro: String,
        mutationSummary: String? = null,
        includeCurrentPlanReply: Boolean = false,
        onPlannerActivityChanged: suspend (MealPlannerActivity) -> Unit,
    ): MealPlannerReply =
        withSessionGeneration(
            sessionId = snapshot.sessionId,
            onBusy = { MealPlannerReply(generationInProgressMessage(snapshot)) },
        ) {
            generateSpecificDayRecipeInternal(
                snapshot = snapshot,
                dayIndex = dayIndex,
                intro = intro,
                mutationSummary = mutationSummary,
                includeCurrentPlanReply = includeCurrentPlanReply,
                onPlannerActivityChanged = onPlannerActivityChanged,
            )
        }

    private suspend fun generateSpecificDayRecipeInternal(
        snapshot: MealPlanSnapshot,
        dayIndex: Int,
        intro: String,
        mutationSummary: String? = null,
        includeCurrentPlanReply: Boolean = false,
        onPlannerActivityChanged: suspend (MealPlannerActivity) -> Unit,
    ): MealPlannerReply {
        val generationSnapshot = try {
            prepareRecipeGeneration(snapshot, dayIndex)
        } catch (e: MealPlanValidationException) {
            return MealPlannerReply(
                "I couldn't generate a valid recipe for Day ${dayIndex + 1}: ${e.message} Say 'regenerate day ${dayIndex + 1}' or 'replace day ${dayIndex + 1}'.",
            )
        }
        onPlannerActivityChanged(generatingRecipeActivity(generationSnapshot, dayIndex, generationSnapshot.days.size))
        val result = try {
            generateAndPersistRecipe(generationSnapshot, dayIndex, markPendingGeneration = false)
        } catch (e: MealPlanValidationException) {
            return MealPlannerReply(
                "I couldn't generate a valid recipe for Day ${dayIndex + 1}: ${e.message} Say 'regenerate day ${dayIndex + 1}' or 'replace day ${dayIndex + 1}'.",
            )
        }
        val builder = StringBuilder()
        builder.append(intro)
        builder.append(formatRecipeSection(dayIndex, result.recipe))
        mutationSummary?.let {
            builder.append("\n\n")
            builder.append(it)
        }
        if (includeCurrentPlanReply) {
            builder.append("\n\n")
            builder.append(currentPlanReply(result.snapshot))
        } else if (result.snapshot.days.all { it.status == MealPlanDayStatus.PERSISTED }) {
            builder.append("\n\nYour meal plan is ready. Say 'done meal planning' to finalize it, or regenerate another day.")
        }
        return MealPlannerReply(builder.toString().trim())
    }

    private suspend fun prepareRecipeGeneration(
        snapshot: MealPlanSnapshot,
        dayIndex: Int,
    ): MealPlanSnapshot {
        snapshot.days.firstOrNull { it.dayIndex == dayIndex }
            ?: throw MealPlanValidationException("Unknown meal-plan day ${dayIndex + 1}.")
        snapshot.peopleCount ?: throw MealPlanValidationException("Meal-plan session is missing people count.")
        sessionRepository.markPendingGeneration(snapshot.sessionId, PendingGenerationKind.RECIPE, dayIndex)
        return snapshot
    }

    private suspend fun generateAndPersistRecipe(
        snapshot: MealPlanSnapshot,
        dayIndex: Int,
        markPendingGeneration: Boolean = true,
    ): GeneratedRecipeResult {
        val day = snapshot.days.firstOrNull { it.dayIndex == dayIndex }
            ?: throw MealPlanValidationException("Unknown meal-plan day ${dayIndex + 1}.")
        val servings = snapshot.peopleCount ?: throw MealPlanValidationException("Meal-plan session is missing people count.")
        if (markPendingGeneration) {
            sessionRepository.markPendingGeneration(snapshot.sessionId, PendingGenerationKind.RECIPE, dayIndex)
        }
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
    private suspend fun replaceDaysAndGenerateRecipes(
        snapshot: MealPlanSnapshot,
        dayIndices: List<Int>,
        onPlannerActivityChanged: suspend (MealPlannerActivity) -> Unit,
    ): MealPlannerReply =
        withSessionGeneration(
            sessionId = snapshot.sessionId,
            onBusy = { MealPlannerReply(generationInProgressMessage(snapshot)) },
        ) {
            val orderedDays = dayIndices.distinct().sorted()
            var currentSnapshot = snapshot
            val completedDays = mutableListOf<Int>()
            val reply = StringBuilder()
            orderedDays.forEachIndexed { index, dayIndex ->
                val batchStep = index + 1
                val generationSnapshot = try {
                    prepareReplacementGeneration(currentSnapshot, dayIndex)
                } catch (e: IllegalArgumentException) {
                    return@withSessionGeneration batchEditFailureReply("replace", completedDays, dayIndex, currentSnapshot.days.size, e.message, currentSnapshot)
                }
                onPlannerActivityChanged(replacingDayActivity(generationSnapshot, dayIndex, batchStep, orderedDays.size))
                val replaced = try {
                    generateReplacementDayInternal(generationSnapshot, dayIndex, markPendingGeneration = false)
                } catch (e: IllegalArgumentException) {
                    return@withSessionGeneration batchEditFailureReply("replace", completedDays, dayIndex, currentSnapshot.days.size, e.message, currentSnapshot)
                } catch (e: MealPlanValidationException) {
                    return@withSessionGeneration batchEditFailureReply("replace", completedDays, dayIndex, currentSnapshot.days.size, e.message, currentSnapshot)
                }
                onPlannerActivityChanged(generatingRecipeActivity(replaced, dayIndex, replaced.days.size, batchStep, orderedDays.size))
                val recipeResult = try {
                    generateAndPersistRecipe(replaced, dayIndex, markPendingGeneration = false)
                } catch (e: MealPlanValidationException) {
                    return@withSessionGeneration batchEditFailureReply("replace", completedDays, dayIndex, currentSnapshot.days.size, e.message, currentSnapshot)
                }
                if (reply.isNotEmpty()) {
                    reply.append("\n\n")
                }
                reply.append("I replaced Day ${dayIndex + 1}. Here’s the updated recipe:\n")
                reply.append(formatRecipeSection(dayIndex, recipeResult.recipe))
                reply.append("\n\n")
                reply.append(replacementChangeMessage(currentSnapshot, recipeResult.snapshot, dayIndex))
                currentSnapshot = recipeResult.snapshot
                completedDays += dayIndex
            }
            reply.append("\n\n")
            reply.append(currentPlanReply(currentSnapshot))
            MealPlannerReply(reply.toString().trim())
        }

    private suspend fun regenerateSpecificDayRecipes(
        snapshot: MealPlanSnapshot,
        dayIndices: List<Int>,
        onPlannerActivityChanged: suspend (MealPlannerActivity) -> Unit,
    ): MealPlannerReply =
        withSessionGeneration(
            sessionId = snapshot.sessionId,
            onBusy = { MealPlannerReply(generationInProgressMessage(snapshot)) },
        ) {
            val orderedDays = dayIndices.distinct().sorted()
            var currentSnapshot = snapshot
            val completedDays = mutableListOf<Int>()
            val reply = StringBuilder()
            orderedDays.forEachIndexed { index, dayIndex ->
                val batchStep = index + 1
                val generationSnapshot = try {
                    prepareRecipeGeneration(currentSnapshot, dayIndex)
                } catch (e: MealPlanValidationException) {
                    return@withSessionGeneration batchEditFailureReply("regenerate", completedDays, dayIndex, currentSnapshot.days.size, e.message, currentSnapshot)
                }
                onPlannerActivityChanged(generatingRecipeActivity(generationSnapshot, dayIndex, generationSnapshot.days.size, batchStep, orderedDays.size))
                val recipeResult = try {
                    generateAndPersistRecipe(generationSnapshot, dayIndex, markPendingGeneration = false)
                } catch (e: MealPlanValidationException) {
                    return@withSessionGeneration batchEditFailureReply("regenerate", completedDays, dayIndex, currentSnapshot.days.size, e.message, currentSnapshot)
                }
                if (reply.isNotEmpty()) {
                    reply.append("\n\n")
                }
                reply.append("I regenerated Day ${dayIndex + 1}. Here’s the updated recipe:\n")
                reply.append(formatRecipeSection(dayIndex, recipeResult.recipe))
                reply.append("\n\n")
                reply.append(regenerationChangeMessage(currentSnapshot, dayIndex))
                currentSnapshot = recipeResult.snapshot
                completedDays += dayIndex
            }
            reply.append("\n\n")
            reply.append(currentPlanReply(currentSnapshot))
            MealPlannerReply(reply.toString().trim())
        }

    private suspend fun replaceDaysForReview(
        snapshot: MealPlanSnapshot,
        dayIndices: List<Int>,
        onPlannerActivityChanged: suspend (MealPlannerActivity) -> Unit,
    ): MealPlannerReply =
        withSessionGeneration(
            sessionId = snapshot.sessionId,
            onBusy = { MealPlannerReply(generationInProgressMessage(snapshot)) },
        ) {
            val orderedDays = dayIndices.distinct().sorted()
            var currentSnapshot = snapshot
            val completedDays = mutableListOf<Int>()
            orderedDays.forEachIndexed { index, dayIndex ->
                try {
                    onPlannerActivityChanged(replacingPlanDayActivity(currentSnapshot, dayIndex, index + 1, orderedDays.size))
                    currentSnapshot = generateReplacementDayInternal(currentSnapshot, dayIndex)
                    completedDays += dayIndex
                } catch (e: IllegalArgumentException) {
                    return@withSessionGeneration batchEditFailureReply("replace", completedDays, dayIndex, currentSnapshot.days.size, e.message, currentSnapshot)
                } catch (e: MealPlanValidationException) {
                    return@withSessionGeneration batchEditFailureReply("replace", completedDays, dayIndex, currentSnapshot.days.size, e.message, currentSnapshot)
                }
            }
            val summary = if (completedDays.size == 1) {
                replacementChangeMessage(snapshot, currentSnapshot, completedDays.single())
            } else {
                "Updated plan: ${formatDaySelection(completedDays)} replaced."
            }
            MealPlannerReply("$summary\n\n${currentPlanReply(currentSnapshot)}")
        }

    private fun batchEditFailureReply(
        action: String,
        completedDays: List<Int>,
        failedDayIndex: Int,
        totalDays: Int,
        detail: String?,
        snapshot: MealPlanSnapshot,
    ): MealPlannerReply {
        val intro = if (completedDays.isEmpty()) {
            when (action) {
                "replace" -> replacementFailureMessage(failedDayIndex, totalDays, detail)
                else -> "I couldn't regenerate Day ${failedDayIndex + 1}: ${detail ?: "Try again."}"
            }
        } else {
            "I completed ${formatDaySelection(completedDays)} but couldn't $action Day ${failedDayIndex + 1}: ${detail ?: "Try again."}"
        }
        return MealPlannerReply("$intro\n\n${currentPlanReply(snapshot)}".trim())
    }

    private fun formatDaySelection(dayIndices: List<Int>): String {
        val labels = dayIndices.distinct().sorted().map { "Day ${it + 1}" }
        return when (labels.size) {
            0 -> "no days"
            1 -> labels.single()
            2 -> "${labels[0]} and ${labels[1]}"
            else -> labels.dropLast(1).joinToString(", ") + ", and " + labels.last()
        }
    }

    private suspend fun replaceDayAndGenerateRecipe(
        snapshot: MealPlanSnapshot,
        dayIndex: Int,
        intro: String,
        onPlannerActivityChanged: suspend (MealPlannerActivity) -> Unit,
    ): MealPlannerReply =
        withSessionGeneration(
            sessionId = snapshot.sessionId,
            onBusy = { MealPlannerReply(generationInProgressMessage(snapshot)) },
        ) {
            val generationSnapshot = try {
                prepareReplacementGeneration(snapshot, dayIndex)
            } catch (e: IllegalArgumentException) {
                return@withSessionGeneration MealPlannerReply(replacementFailureMessage(dayIndex, snapshot.days.size, e.message))
            }
            onPlannerActivityChanged(replacingDayActivity(generationSnapshot, dayIndex))
            val replaced = try {
                generateReplacementDayInternal(generationSnapshot, dayIndex, markPendingGeneration = false)
            } catch (e: IllegalArgumentException) {
                return@withSessionGeneration MealPlannerReply(replacementFailureMessage(dayIndex, snapshot.days.size, e.message))
            } catch (e: MealPlanValidationException) {
                return@withSessionGeneration MealPlannerReply(replacementFailureMessage(dayIndex, snapshot.days.size, e.message))
            }
            generateSpecificDayRecipeInternal(
                snapshot = replaced,
                dayIndex = dayIndex,
                intro = intro,
                mutationSummary = replacementChangeMessage(snapshot, replaced, dayIndex),
                includeCurrentPlanReply = true,
                onPlannerActivityChanged = onPlannerActivityChanged,
            )
        }

    private suspend fun replaceDayForReview(
        snapshot: MealPlanSnapshot,
        dayIndex: Int,
        onPlannerActivityChanged: suspend (MealPlannerActivity) -> Unit,
    ): MealPlannerReply =
        withSessionGeneration(
            sessionId = snapshot.sessionId,
            onBusy = { MealPlannerReply(generationInProgressMessage(snapshot)) },
        ) {
            try {
                onPlannerActivityChanged(replacingPlanDayActivity(snapshot, dayIndex))
                val replaced = generateReplacementDayInternal(snapshot, dayIndex)
                MealPlannerReply(
                    replacementChangeMessage(snapshot, replaced, dayIndex) + "\n\n" + currentPlanReply(replaced),
                )
            } catch (e: IllegalArgumentException) {
                MealPlannerReply(replacementFailureMessage(dayIndex, snapshot.days.size, e.message))
            } catch (e: MealPlanValidationException) {
                MealPlannerReply(replacementFailureMessage(dayIndex, snapshot.days.size, e.message))
            }
        }

    private suspend fun prepareReplacementGeneration(
        snapshot: MealPlanSnapshot,
        dayIndex: Int,
    ): MealPlanSnapshot {
        require(dayIndex in snapshot.days.indices) { "Invalid day index: $dayIndex" }
        sessionRepository.markPendingGeneration(snapshot.sessionId, PendingGenerationKind.REPLACEMENT, dayIndex)
        return snapshot
    }

    private suspend fun generateReplacementDayInternal(
        snapshot: MealPlanSnapshot,
        dayIndex: Int,
        markPendingGeneration: Boolean = true,
    ): MealPlanSnapshot {
        require(dayIndex in snapshot.days.indices) { "Invalid day index: $dayIndex" }
        if (markPendingGeneration) {
            sessionRepository.markPendingGeneration(snapshot.sessionId, PendingGenerationKind.REPLACEMENT, dayIndex)
        }
        try {
            val recentHistory = sessionRepository.getRecentMealHistory(RECENT_MEAL_HISTORY_LIMIT)
            val favouriteRecipes = sessionRepository.getFavouriteRecipes(MAX_FAVOURITE_PROMPT_RECIPES)
            val enforceRecentPatternDiversity = shouldEnforceRecentPatternDiversity(snapshot)
            val raw = inferenceEngine.generateOnce(
                prompt = buildReplacementDayUserPrompt(snapshot, dayIndex, recentHistory, favouriteRecipes),
                systemPrompt = buildReplacementDaySystemPrompt(dayIndex),
                thinkingEnabled = false,
                stopOnFirstJsonObject = true,
            )
            if (raw.isBlank()) {
                throw MealPlanValidationException("The model didn't return a replacement day.")
            }
            val replacement = jsonParser.parseSinglePlanDay(raw, dayIndex)
            val candidateDays = snapshot.days.sortedBy { it.dayIndex }.map { day ->
                MealPlanDraftDay(
                    dayIndex = day.dayIndex,
                    title = day.title ?: "Meal",
                    summary = day.summary,
                    proteinTags = day.proteinTags,
                )
            }.toMutableList().also { days ->
                days[dayIndex] = replacement
            }
            if (
                detectPlanVarietyConflicts(
                    days = candidateDays,
                    recentHistory = recentHistory,
                    enforceRecentPatternDiversity = enforceRecentPatternDiversity,
                ).any { it.dayIndex == dayIndex && it.isBlocking }
            ) {
                throw MealPlanValidationException("Replacement day still duplicated another planned meal too closely.")
            }
            return sessionRepository.replaceDayDraft(
                sessionId = snapshot.sessionId,
                dayIndex = dayIndex,
                title = replacement.title,
                summary = replacement.summary,
                proteinTags = replacement.proteinTags,
                recipeGenerationPending = !markPendingGeneration,
            )
        } catch (e: MealPlanValidationException) {
            sessionRepository.clearPendingGeneration(snapshot.sessionId)
            throw e
        } catch (e: IllegalArgumentException) {
            sessionRepository.clearPendingGeneration(snapshot.sessionId)
            throw e
        }
    }

    private suspend fun writeFinalSummaryIfNeeded(snapshot: MealPlanSnapshot) {
        if (snapshot.finalSummaryWritten) return
        val summary = sessionRepository.buildFinalSummary(snapshot.sessionId)
        if (memoryRepository.hasEpisodicMemory(snapshot.conversationId, summary)) {
            runCatching {
                Log.d(TAG, "Skipping duplicate meal-plan summary memory for ${snapshot.sessionId}")
            }
            sessionRepository.markFinalSummaryWritten(snapshot.sessionId)
            return
        }
        val embedding = embeddingEngine.embed(summary)
        memoryRepository.addEpisodicMemory(snapshot.conversationId, summary, embedding)
        sessionRepository.markFinalSummaryWritten(snapshot.sessionId)
        runCatching {
            Log.d(TAG, "Stored meal-plan summary memory for ${snapshot.sessionId}")
        }
    }

    private suspend fun isGenerationActive(sessionId: String): Boolean = activeGenerationMutex.withLock {
        (activeGenerationCounts[sessionId] ?: 0) > 0
    }

    private suspend fun <T> withSessionGeneration(
        sessionId: String,
        onBusy: () -> T,
        block: suspend () -> T,
    ): T {
        activeGenerationMutex.withLock {
            if ((activeGenerationCounts[sessionId] ?: 0) > 0) {
                return onBusy()
            }
            activeGenerationCounts[sessionId] = 1
        }
        return try {
            block()
        } finally {
            withContext(NonCancellable) {
                activeGenerationMutex.withLock {
                    activeGenerationCounts.remove(sessionId)
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
        PendingGenerationKind.REPLACEMENT ->
            snapshot.pendingGenerationDayIndex?.let { "I'm still replacing Day ${it + 1}. Give me a moment." }
                ?: "I'm still updating your meal plan. Give me a moment."
        null -> "I'm still working on your meal plan. Give me a moment."
    }

    private fun activityForSnapshot(
        snapshot: MealPlanSnapshot,
        generationActive: Boolean = false,
    ): MealPlannerActivity? = when (snapshot.status) {
        MealPlanSessionStatus.COLLECTING_REQUIRED_SLOTS -> collectingActivity(snapshot)
        MealPlanSessionStatus.PLAN_REVIEW -> if (generationActive && snapshot.pendingGenerationKind == PendingGenerationKind.PLAN) {
            generatingPlanActivity(snapshot)
        } else {
            MealPlannerActivity(
                title = "Review your meal plan",
                subtitle = "Say 'show current plan', 'generate recipes', 'replace day 2', 'change preferences', or 'help'.",
                state = MealPlannerActivityState.WAITING,
                suggestions = planReviewSuggestions(snapshot),
            )
        }
        MealPlanSessionStatus.RECIPES_IN_PROGRESS,
        MealPlanSessionStatus.AWAITING_USER_EDIT_OR_RECOVERY -> activeOrRecoveryActivity(snapshot, generationActive)
        MealPlanSessionStatus.COMPLETED,
        MealPlanSessionStatus.CANCELLED,
        -> null
    }

    private fun collectingActivity(snapshot: MealPlanSnapshot): MealPlannerActivity {
        val missing = missingSlots(snapshot)
        return if (missing.isEmpty()) {
            MealPlannerActivity(
                title = "Preferences updated",
                subtitle = "Say 'generate' to rebuild the meal plan with these changes, or 'help' for examples.",
                state = MealPlannerActivityState.WAITING,
                suggestions = listOf(
                    suggestion("Generate", "generate"),
                    suggestion("Show current plan", "show current plan"),
                    suggestion("Help", "help"),
                    suggestion("Cancel plan", "cancel plan"),
                ),
            )
        } else {
            MealPlannerActivity(
                title = "Meal planner needs details",
                subtitle = "Still need: ${missing.joinToString(", ") { humanizeSlot(it) }}.",
                state = MealPlannerActivityState.WAITING,
                suggestions = collectingSuggestions(snapshot, missing),
            )
        }
    }

    private fun activeOrRecoveryActivity(
        snapshot: MealPlanSnapshot,
        generationActive: Boolean,
    ): MealPlannerActivity {
        val failedDay = snapshot.days.firstOrNull { it.status == MealPlanDayStatus.FAILED }
        val pendingDay = snapshot.days.firstOrNull { it.status != MealPlanDayStatus.PERSISTED && it.status != MealPlanDayStatus.FAILED }
        val pendingGenerationDayIndex = snapshot.pendingGenerationDayIndex
        return when {
            failedDay != null -> MealPlannerActivity(
                title = "Meal planner needs recovery",
                subtitle = "Day ${failedDay.dayIndex + 1} failed. Say 'regenerate day ${failedDay.dayIndex + 1}', 'replace day ${failedDay.dayIndex + 1}', or 'help'.",
                state = MealPlannerActivityState.WAITING,
                suggestions = listOf(
                    suggestion("Regenerate day ${failedDay.dayIndex + 1}", "regenerate day ${failedDay.dayIndex + 1}"),
                    suggestion("Replace day ${failedDay.dayIndex + 1}", "replace day ${failedDay.dayIndex + 1}"),
                    suggestion("Show current plan", "show current plan"),
                    suggestion("Help", "help"),
                    suggestion("Cancel plan", "cancel plan"),
                ),
            )
            generationActive && snapshot.pendingGenerationKind == PendingGenerationKind.PLAN -> generatingPlanActivity(snapshot)
            generationActive && snapshot.pendingGenerationKind == PendingGenerationKind.RECIPE && pendingGenerationDayIndex != null ->
                generatingRecipeActivity(snapshot, pendingGenerationDayIndex, snapshot.days.size)
            generationActive && snapshot.pendingGenerationKind == PendingGenerationKind.REPLACEMENT && pendingGenerationDayIndex != null ->
                replacingDayActivity(snapshot, pendingGenerationDayIndex)
            snapshot.pendingGenerationKind == PendingGenerationKind.REPLACEMENT && pendingGenerationDayIndex != null &&
                snapshot.days.isNotEmpty() &&
                (snapshot.days.all { it.status == MealPlanDayStatus.PERSISTED } || snapshot.days.all { it.status == MealPlanDayStatus.DRAFTED }) ->
                MealPlannerActivity(
                    title = "Replacement paused",
                    subtitle = "Retry Day ${pendingGenerationDayIndex + 1}, inspect the current plan, or say 'help'.",
                    state = MealPlannerActivityState.WAITING,
                    suggestions = listOf(
                        suggestion("Retry", "retry"),
                        suggestion("Replace day ${pendingGenerationDayIndex + 1}", "replace day ${pendingGenerationDayIndex + 1}"),
                        suggestion("Show current plan", "show current plan"),
                        suggestion("Help", "help"),
                        suggestion("Cancel plan", "cancel plan"),
                    ),
                )
            snapshot.pendingGenerationKind == PendingGenerationKind.RECIPE && pendingGenerationDayIndex != null &&
                snapshot.days.all { it.status == MealPlanDayStatus.PERSISTED } ->
                MealPlannerActivity(
                    title = "Recipe generation paused",
                    subtitle = "Retry Day ${pendingGenerationDayIndex + 1}, inspect the current plan, or say 'help'.",
                    state = MealPlannerActivityState.WAITING,
                    suggestions = listOf(
                        suggestion("Retry", "retry"),
                        suggestion("Regenerate day ${pendingGenerationDayIndex + 1}", "regenerate day ${pendingGenerationDayIndex + 1}"),
                        suggestion("Show current plan", "show current plan"),
                        suggestion("Help", "help"),
                        suggestion("Cancel plan", "cancel plan"),
                    ),
                )
            pendingDay != null -> MealPlannerActivity(
                title = "Meal planner paused",
                subtitle = "Ready to resume at Day ${pendingDay.dayIndex + 1} of ${snapshot.days.size}. Say 'generate recipes' to continue or 'help' for more options.",
                state = MealPlannerActivityState.WAITING,
                suggestions = listOf(
                    suggestion("Generate recipes", "generate recipes"),
                    suggestion("Show current plan", "show current plan"),
                    suggestion("Help", "help"),
                    suggestion("Cancel plan", "cancel plan"),
                ),
            )
            else -> MealPlannerActivity(
                title = "Meal plan ready",
                subtitle = "Say 'show current plan', 'replace day 1', 'regenerate day 2', 'done meal planning', or 'help'.",
                state = MealPlannerActivityState.WAITING,
                suggestions = finalizeSuggestions(snapshot),
            )
        }
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
            val pendingGenerationDayIndex = snapshot.pendingGenerationDayIndex
            when {
                failedDay != null -> recoveryPrompt(failedDay.dayIndex)
                snapshot.pendingGenerationKind == PendingGenerationKind.REPLACEMENT && pendingGenerationDayIndex != null &&
                    snapshot.days.isNotEmpty() &&
                    (snapshot.days.all { it.status == MealPlanDayStatus.PERSISTED } || snapshot.days.all { it.status == MealPlanDayStatus.DRAFTED }) ->
                    replacementRetryPrompt(pendingGenerationDayIndex)
                snapshot.pendingGenerationKind == PendingGenerationKind.RECIPE && pendingGenerationDayIndex != null &&
                    snapshot.days.all { it.status == MealPlanDayStatus.PERSISTED } ->
                    regenerateRetryPrompt(snapshot, pendingGenerationDayIndex)
                snapshot.days.any { it.status != MealPlanDayStatus.PERSISTED && it.status != MealPlanDayStatus.FAILED } -> {
                    val nextPending = snapshot.days.first { it.status != MealPlanDayStatus.PERSISTED && it.status != MealPlanDayStatus.FAILED }
                    resumePrompt(snapshot, nextPending.dayIndex)
                }
                else -> readyToFinalizePrompt()
            }
        }
        MealPlanSessionStatus.COMPLETED -> "Your meal plan is already finalized."
        MealPlanSessionStatus.CANCELLED -> "That meal plan session was cancelled."
    }

    private fun helpPrompt(snapshot: MealPlanSnapshot, generationActive: Boolean): String = when (snapshot.status) {
        MealPlanSessionStatus.COLLECTING_REQUIRED_SLOTS -> collectingHelpPrompt(snapshot)
        MealPlanSessionStatus.PLAN_REVIEW ->
            if (snapshot.days.isEmpty()) {
                "I still need to rebuild your meal plan draft. You can say 'generate recipes' to try again, 'change preferences' to edit the plan details, 'show current plan' to inspect what I have, or 'cancel' to stop."
            } else {
                "You're reviewing the draft meal plan. You can say 'show current plan' to inspect it again, 'generate recipes' to build the recipe details, 'replace day 1' to swap one meal, 'change preferences' to edit people, days, dietary needs, proteins, or cuisines, or 'cancel' to stop."
            }
        MealPlanSessionStatus.RECIPES_IN_PROGRESS,
        MealPlanSessionStatus.AWAITING_USER_EDIT_OR_RECOVERY -> activeOrRecoveryHelpPrompt(snapshot, generationActive)
        MealPlanSessionStatus.COMPLETED ->
            "This meal plan is already finalized. Ask me to plan meals again when you want a new one."
        MealPlanSessionStatus.CANCELLED -> "That meal plan session was cancelled."
    }

    private fun collectingHelpPrompt(snapshot: MealPlanSnapshot): String {
        val missing = missingSlots(snapshot)
        if (missing.isEmpty()) {
            return buildString {
                appendLine("You can edit any saved meal-planner detail in one message.")
                appendLine("Examples:")
                appendLine("- 4 people")
                appendLine("- 5 days")
                appendLine("- gluten free, kid friendly, no coriander")
                appendLine("- chicken, salmon")
                appendLine("- italian, thai, one pot")
                appendLine("- 4 people, 5 days, chicken, italian")
                appendLine("- remove gluten free")
                appendLine("- remove no chicken")
                append("Then say 'generate' to rebuild the plan, 'show current plan' to inspect it, or 'cancel' to stop.")
            }
        }
        return buildString {
            appendLine("Here’s what you can tell me at this step:")
            missing.forEach { slot -> appendLine(collectingHelpLine(snapshot, slot)) }
            appendLine()
            append("You can combine details in one reply, for example '4 people, 5 days, gluten free, chicken, italian'.")
        }
    }

    private fun collectingHelpLine(snapshot: MealPlanSnapshot, slot: String): String = when (slot) {
        "people" -> "- People count examples: '2 people', '4 people'."
        "days" -> "- Day-count examples: '4 days', '7 days'."
        "dietary" -> "- Dietary, allergen, or ingredient examples: '${fullDietarySuggestions().joinToString("', '")}', or a custom exclusion like 'no coriander'."
        "protein" -> "- Protein examples: '${compatibleProteinSuggestions(snapshot.dietaryRestrictions).joinToString("', '")}'."
        "cuisine" -> "- Cuisine examples: '${fullCuisineSuggestions().joinToString("', '")}'."
        else -> "- ${humanizeSlot(slot)}"
    }

    private fun activeOrRecoveryHelpPrompt(
        snapshot: MealPlanSnapshot,
        generationActive: Boolean,
    ): String {
        val failedDay = snapshot.days.firstOrNull { it.status == MealPlanDayStatus.FAILED }
        val pendingDay = snapshot.days.firstOrNull { it.status != MealPlanDayStatus.PERSISTED && it.status != MealPlanDayStatus.FAILED }
        val pendingGenerationDayIndex = snapshot.pendingGenerationDayIndex
        return when {
            generationActive ->
                generationInProgressMessage(snapshot) + " You can wait, say 'show current plan' to inspect the draft, or 'cancel' to stop."
            failedDay != null ->
                "Day ${failedDay.dayIndex + 1} needs recovery. Say 'regenerate day ${failedDay.dayIndex + 1}' to keep the meal but rebuild the recipe, 'replace day ${failedDay.dayIndex + 1}' to swap the meal, 'show current plan' to inspect the draft, or 'cancel' to stop."
            snapshot.pendingGenerationKind == PendingGenerationKind.REPLACEMENT && pendingGenerationDayIndex != null &&
                snapshot.days.isNotEmpty() &&
                (snapshot.days.all { it.status == MealPlanDayStatus.PERSISTED } || snapshot.days.all { it.status == MealPlanDayStatus.DRAFTED }) ->
                "Replacement for Day ${pendingGenerationDayIndex + 1} is paused. Say 'retry' or 'replace day ${pendingGenerationDayIndex + 1}' to try again, 'show current plan' to inspect the draft, or 'cancel' to stop."
            snapshot.pendingGenerationKind == PendingGenerationKind.RECIPE && pendingGenerationDayIndex != null &&
                snapshot.days.all { it.status == MealPlanDayStatus.PERSISTED } ->
                "Recipe generation for Day ${pendingGenerationDayIndex + 1} is paused. Say 'retry' or 'regenerate day ${pendingGenerationDayIndex + 1}' to try again, 'show current plan' to inspect the draft, or 'cancel' to stop."
            pendingDay != null ->
                "Recipe generation is paused at Day ${pendingDay.dayIndex + 1} of ${snapshot.days.size}. Say 'generate recipes' to continue, 'show current plan' to inspect the draft, or 'cancel' to stop."
            else ->
                "You're at the final review step. Say 'show current plan' to inspect it, 'done meal planning' to finalize it, 'regenerate day 1' or 'replace day 1' to revise a day, or 'cancel' to stop."
        }
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
            append("\n\nSay 'help' for examples of what you can reply with.")
        }
    }

    private fun promptForPreferenceEditing(snapshot: MealPlanSnapshot): String = buildString {
        append("Current plan details: ")
        append(buildKnownBits(snapshot).ifEmpty { listOf("no saved details yet") }.joinToString(", "))
        append(".\n\n")
        append("Reply with any updated people count, days, dietary requirements, allergens, ingredients to avoid, protein preferences, or cuisine preferences. Say 'help' for examples or 'cancel' to stop.")
    }

    private fun buildKnownBits(snapshot: MealPlanSnapshot): List<String> = buildList {
        snapshot.peopleCount?.let { add("$it people") }
        snapshot.daysCount?.let { add("$it days") }
        if (snapshot.dietaryRestrictions.isNotEmpty()) add(snapshot.dietaryRestrictions.joinToString())
        if (snapshot.proteinPreferences.isNotEmpty()) add(snapshot.proteinPreferences.joinToString())
        if (snapshot.cuisinePreferences.isNotEmpty()) add("cuisines: ${snapshot.cuisinePreferences.joinToString()}")
    }

    private fun missingSlots(snapshot: MealPlanSnapshot): List<String> = buildList {
        if (snapshot.peopleCount == null) add("people")
        if (snapshot.daysCount == null) add("days")
        if (snapshot.dietaryRestrictions.isEmpty()) add("dietary")
        if (snapshot.proteinPreferences.isEmpty()) add("protein")
        if (snapshot.cuisinePreferences.isEmpty()) add("cuisine")
    }

    private fun missingSlotPrompt(slot: String): String = when (slot) {
        "people" -> "- How many people are you cooking for?"
        "days" -> "- How many days do you want to plan for?"
        "dietary" -> "- Any dietary requirements, allergens, or ingredients to avoid?"
        "protein" -> "- What protein preferences should I use?"
        "cuisine" -> "- Any cuisine or meal-style preferences?"
        else -> "- $slot"
    }

    private fun planReviewPrompt(snapshot: MealPlanSnapshot): String =
        if (snapshot.days.isEmpty()) {
            "I still need to rebuild your meal plan draft. Say 'generate recipes' to try again, 'change preferences', 'help' for more options, or 'cancel'."
        } else {
            buildPlanSummary(snapshot) + "\n\n" + planReviewActionsPrompt()
        }

    private fun currentPlanReply(snapshot: MealPlanSnapshot): String =
        if (snapshot.days.isEmpty()) {
            promptForSnapshot(snapshot)
        } else {
            buildPlanSummary(snapshot) + "\n\n" + statusPrompt(snapshot)
        }


    private fun favouriteManagementMovedToUiReply(snapshot: MealPlanSnapshot): MealPlannerReply =
        MealPlannerReply(
            "Favourite and unfavourite actions will move to a dedicated favourites and recent meal plans screen instead of this chat.\n\n${currentPlanReply(snapshot)}",
        )

    private fun planReviewActionsPrompt(): String =
        "Say 'generate recipes', 'replace day 2', 'replace days 2 and 4', 'change preferences', 'help' for more options, or 'cancel'."

    private fun readyToFinalizePrompt(): String =
        "Say 'show current plan', 'replace day 1', 'replace days 2 and 4', 'regenerate day 2', 'done meal planning', or 'help' for more options."

    private fun statusPrompt(snapshot: MealPlanSnapshot): String = when (snapshot.status) {
        MealPlanSessionStatus.COLLECTING_REQUIRED_SLOTS -> {
            val missing = missingSlots(snapshot)
            if (missing.isEmpty()) {
                promptForPreferenceEditing(snapshot)
            } else {
                promptForMissingSlots(snapshot, missing)
            }
        }
        MealPlanSessionStatus.PLAN_REVIEW -> planReviewActionsPrompt()
        MealPlanSessionStatus.RECIPES_IN_PROGRESS,
        MealPlanSessionStatus.AWAITING_USER_EDIT_OR_RECOVERY -> {
            val failedDay = snapshot.days.firstOrNull { it.status == MealPlanDayStatus.FAILED }
            val pendingGenerationDayIndex = snapshot.pendingGenerationDayIndex
            when {
                failedDay != null -> recoveryPrompt(failedDay.dayIndex)
                snapshot.pendingGenerationKind == PendingGenerationKind.REPLACEMENT && pendingGenerationDayIndex != null &&
                    snapshot.days.isNotEmpty() &&
                    (snapshot.days.all { it.status == MealPlanDayStatus.PERSISTED } || snapshot.days.all { it.status == MealPlanDayStatus.DRAFTED }) ->
                    replacementRetryPrompt(pendingGenerationDayIndex)
                snapshot.pendingGenerationKind == PendingGenerationKind.RECIPE && pendingGenerationDayIndex != null &&
                    snapshot.days.all { it.status == MealPlanDayStatus.PERSISTED } ->
                    regenerateRetryPrompt(snapshot, pendingGenerationDayIndex)
                snapshot.days.any { it.status != MealPlanDayStatus.PERSISTED && it.status != MealPlanDayStatus.FAILED } -> {
                    val nextPending = snapshot.days.first { it.status != MealPlanDayStatus.PERSISTED && it.status != MealPlanDayStatus.FAILED }
                    resumePrompt(snapshot, nextPending.dayIndex)
                }
                else -> readyToFinalizePrompt()
            }
        }
        MealPlanSessionStatus.COMPLETED -> "Your meal plan is already finalized."
        MealPlanSessionStatus.CANCELLED -> "That meal plan session was cancelled."
    }

    private fun resumePrompt(snapshot: MealPlanSnapshot, dayIndex: Int): String =
        "I still need to finish Day ${dayIndex + 1} of ${snapshot.days.size}. Say 'generate recipes' to continue, 'help' for more options, or 'cancel' to stop."

    private fun replacementRetryPrompt(dayIndex: Int): String =
        "I still need to replace Day ${dayIndex + 1}. Say 'retry' or 'replace day ${dayIndex + 1}' to try again, 'help' for more options, or 'cancel' to stop."

    private fun regenerateRetryPrompt(snapshot: MealPlanSnapshot, dayIndex: Int): String =
        "I still need to finish Day ${dayIndex + 1} of ${snapshot.days.size}. Say 'retry' or 'regenerate day ${dayIndex + 1}' to try again, 'help' for more options, or 'cancel' to stop."

    private fun recoveryPrompt(dayIndex: Int): String =
        "I still need to finish Day ${dayIndex + 1}. Say 'regenerate day ${dayIndex + 1}', 'replace day ${dayIndex + 1}', or 'help' for more options."

    private fun replacementFailureMessage(dayIndex: Int, totalDays: Int, detail: String?): String =
        invalidReplacementDayMessage(dayIndex, totalDays)
            ?: "I couldn't replace Day ${dayIndex + 1}. ${detail ?: "Try again with a different day."}"

    private fun invalidReplacementDayMessage(dayIndex: Int, totalDays: Int): String? {
        if (dayIndex !in 0 until totalDays) {
            return when {
                totalDays <= 0 -> "I don't have a meal plan draft to edit yet. Say 'generate recipes' to build one first."
                totalDays == 1 -> "Your current plan only has Day 1, so I can only replace Day 1."
                else -> "Your current plan only has $totalDays days, so I can replace Day 1 to Day $totalDays."
            }
        }
        return null
    }

    private fun replacementChangeMessage(before: MealPlanSnapshot, after: MealPlanSnapshot, dayIndex: Int): String {
        val beforeTitle = before.days.firstOrNull { it.dayIndex == dayIndex }?.title ?: "Meal"
        val afterTitle = after.days.firstOrNull { it.dayIndex == dayIndex }?.title ?: "Meal"
        return if (beforeTitle.equals(afterTitle, ignoreCase = true)) {
            "Updated plan: Day ${dayIndex + 1} now uses $afterTitle."
        } else {
            "Updated plan: Day ${dayIndex + 1} changed from $beforeTitle to $afterTitle."
        }
    }

    private fun regenerationChangeMessage(snapshot: MealPlanSnapshot, dayIndex: Int): String {
        val title = snapshot.days.firstOrNull { it.dayIndex == dayIndex }?.title
        return title?.let { "Day ${dayIndex + 1} recipe regenerated; the meal choice stayed the same: $it." }
            ?: "Day ${dayIndex + 1} recipe regenerated; the meal choice stayed the same."
    }

    private fun buildPlanSummary(snapshot: MealPlanSnapshot): String = buildString {
        append("Here’s the meal plan I built for ${snapshot.peopleCount} people over ${snapshot.daysCount} days")
        val preferenceBits = buildList {
            if (snapshot.dietaryRestrictions.isNotEmpty()) add(snapshot.dietaryRestrictions.joinToString())
            if (snapshot.proteinPreferences.isNotEmpty()) add("proteins: ${snapshot.proteinPreferences.joinToString()}")
            if (snapshot.cuisinePreferences.isNotEmpty()) add("cuisines: ${snapshot.cuisinePreferences.joinToString()}")
        }
        if (preferenceBits.isNotEmpty()) {
            append(" (${preferenceBits.joinToString("; ")})")
        }
        append(":\n")
        snapshot.days.sortedBy { it.dayIndex }.forEach { day ->
            append("- Day ${day.dayIndex + 1}: ${day.title ?: "Meal"}")
            if (day.isFavouriteRecipe) append(" ★ favourite")
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
- treat dietary requirements, allergens, and ingredient exclusions as strict requirements
- prefer novelty by default across recent meal plans and within the current draft
- avoid exact repeats and obvious same-protein same-cooking-style repeats unless the user explicitly asks for familiar meals
- do not include ingredients, quantities, steps, markdown, commentary, or code fences
""".trimIndent()

    private fun buildPlanUserPrompt(
        snapshot: MealPlanSnapshot,
        recentHistory: List<RecentMealHistoryEntry>,
        favouriteRecipes: List<FavouriteRecipeSummary>,
    ): String {
        val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH))
        val recentHistoryBlock = buildRecentHistoryPromptBlock(recentHistory)
        val favouritePromptBlock = buildFavouriteRecipePromptBlock(snapshot.favouriteRecipeMode, favouriteRecipes)
        return buildString {
            appendLine("Build a ${snapshot.daysCount}-day dinner meal plan for ${snapshot.peopleCount} people.")
            appendLine("Date: $now")
            appendLine("Dietary requirements: ${snapshot.dietaryRestrictions.ifEmpty { listOf("none provided") }.joinToString()}")
            appendLine("Protein preferences: ${snapshot.proteinPreferences.ifEmpty { listOf("no preference provided") }.joinToString()}")
            appendLine("Cuisine preferences: ${snapshot.cuisinePreferences.ifEmpty { listOf("no preference provided") }.joinToString()}")
            appendLine("Use practical weeknight meal ideas suitable for Australia/New Zealand households and prefer New Zealand wording such as capsicum, coriander, kumara, and paua where relevant.")
            if (recentHistoryBlock.isNotBlank()) {
                appendLine()
                append(recentHistoryBlock)
            }
            if (favouritePromptBlock.isNotBlank()) {
                appendLine()
                append(favouritePromptBlock)
            }
        }.trim()
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
- never use length units such as inches, inch, in, cm, or mm for ingredients; use grams, ml, tsp, tbsp, cloves, or whole-item counts instead
- every ingredient line must be a single concise string
- every method step must be a single concise string with the action only
- treat dietary requirements, allergens, and ingredient exclusions as strict requirements
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
Cuisine preferences: ${snapshot.cuisinePreferences.ifEmpty { listOf("no preference provided") }.joinToString()}
Dish title: ${day.title}
Dish summary: ${day.summary ?: ""}
Provide a practical Australia/New Zealand dinner recipe with a concise ingredient list, clear numbered method steps, and New Zealand wording such as capsicum, coriander, kumara, and paua where relevant.
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
- output exactly one replacement day object
- day_index is zero-based, so user-visible Day ${dayIndex + 1} must use day_index $dayIndex
- do not repeat the existing day title verbatim if you can avoid it
- avoid obvious near-duplicates with the same protein and cooking style
- treat dietary requirements, allergens, and ingredient exclusions as strict requirements
- do not include ingredients, quantities, steps, markdown, commentary, or code fences
""".trimIndent()

    private fun buildReplacementDayUserPrompt(
        snapshot: MealPlanSnapshot,
        dayIndex: Int,
        recentHistory: List<RecentMealHistoryEntry>,
        favouriteRecipes: List<FavouriteRecipeSummary>,
    ): String =
        buildReplacementDayUserPrompt(
            snapshot = snapshot,
            currentDays = snapshot.days.sortedBy { it.dayIndex }.map { day ->
                MealPlanDraftDay(
                    dayIndex = day.dayIndex,
                    title = day.title ?: "Meal",
                    summary = day.summary,
                    proteinTags = day.proteinTags,
                )
            },
            dayIndex = dayIndex,
            recentHistory = recentHistory,
            favouriteRecipes = favouriteRecipes,
        )

    private fun buildReplacementDayUserPrompt(
        snapshot: MealPlanSnapshot,
        currentDays: List<MealPlanDraftDay>,
        dayIndex: Int,
        recentHistory: List<RecentMealHistoryEntry>,
        favouriteRecipes: List<FavouriteRecipeSummary>,
    ): String {
        val currentTitle = currentDays.firstOrNull { it.dayIndex == dayIndex }?.title ?: "Day ${dayIndex + 1}"
        val recentHistoryBlock = buildRecentHistoryPromptBlock(recentHistory)
        val favouritePromptBlock = buildFavouriteRecipePromptBlock(snapshot.favouriteRecipeMode, favouriteRecipes)
        return buildString {
            appendLine("Replace Day ${dayIndex + 1} in this meal plan.")
            appendLine("Current plan:")
            appendLine(currentDays.sortedBy { it.dayIndex }.joinToString("\n") { day -> "Day ${day.dayIndex + 1}: ${day.title}" })
            appendLine("Dietary requirements: ${snapshot.dietaryRestrictions.ifEmpty { listOf("none provided") }.joinToString()}")
            appendLine("Protein preferences: ${snapshot.proteinPreferences.ifEmpty { listOf("no preference provided") }.joinToString()}")
            appendLine("Cuisine preferences: ${snapshot.cuisinePreferences.ifEmpty { listOf("no preference provided") }.joinToString()}")
            if (recentHistoryBlock.isNotBlank()) {
                appendLine()
                appendLine(recentHistoryBlock)
            }
            if (favouritePromptBlock.isNotBlank()) {
                appendLine()
                appendLine(favouritePromptBlock)
            }
            appendLine()
            appendLine("Return one alternative day that fits the plan without duplicating '$currentTitle' or clashing with the surrounding days.")
            append("Remember: this is user-visible Day ${dayIndex + 1}, but the JSON day_index must be zero-based and equal $dayIndex. Prefer New Zealand wording where relevant.")
        }.trim()
    }

    private fun buildRecentHistoryPromptBlock(recentHistory: List<RecentMealHistoryEntry>): String {
        if (recentHistory.isEmpty()) {
            return ""
        }
        val recentTitles = recentHistory.map { it.title }
            .distinct()
            .take(MAX_PROMPT_HISTORY_TITLES)
        val recentPatterns = recentHistory.mapNotNull { history ->
            val protein = proteinSignature(history.proteinTags, history.title)
            val shape = mealShape(history.title, history.summary)
            if (protein != null && shape != null) "$protein $shape" else null
        }
            .distinct()
            .take(MAX_PROMPT_HISTORY_PATTERNS)
        return buildString {
            appendLine("Prefer novelty by default.")
            appendLine("Avoid exact repeats from recent meal plans unless the user explicitly asked for them.")
            if (recentTitles.isNotEmpty()) {
                appendLine("Recent meals to avoid repeating: ${recentTitles.joinToString()}")
            }
            if (recentPatterns.isNotEmpty()) {
                append("Also vary away from these recent protein + cooking styles: ${recentPatterns.joinToString()}")
            }
        }.trim()
    }

    private fun buildFavouriteRecipePromptBlock(
        mode: FavouriteRecipeMode,
        favouriteRecipes: List<FavouriteRecipeSummary>,
    ): String {
        if (mode == FavouriteRecipeMode.NONE) return ""
        if (favouriteRecipes.isEmpty()) {
            return when (mode) {
                FavouriteRecipeMode.INCLUDE -> "The user asked to include favourites, but no favourite recipes are saved yet. Build a fresh plan."
                FavouriteRecipeMode.PREFER -> "The user asked to prefer favourites, but no favourite recipes are saved yet. Build a fresh plan and avoid pretending favourites exist."
                FavouriteRecipeMode.AVOID -> "Avoid leaning on saved favourites; keep this plan fresh."
                FavouriteRecipeMode.NONE -> ""
            }
        }
        val favouritesText = favouriteRecipes.joinToString("; ") { favourite ->
            buildString {
                append(favourite.title)
                favourite.summary?.takeIf { it.isNotBlank() }?.let { append(" — $it") }
            }
        }
        return when (mode) {
            FavouriteRecipeMode.INCLUDE -> "Include 1-2 saved favourite meals if they fit naturally: $favouritesText"
            FavouriteRecipeMode.PREFER -> "Bias the plan toward these saved favourite meals when they fit naturally: $favouritesText"
            FavouriteRecipeMode.AVOID -> "Avoid these saved favourite meals for this plan: $favouritesText"
            FavouriteRecipeMode.NONE -> ""
        }
    }

    private fun generatingPlanActivity(snapshot: MealPlanSnapshot): MealPlannerActivity = MealPlannerActivity(
        title = "Generating meal plan",
        subtitle = buildString {
            val days = snapshot.daysCount ?: snapshot.days.size
            val people = snapshot.peopleCount
            if (days > 0) append("Drafting $days dinners") else append("Drafting your dinners")
            people?.let { append(" for $it ${if (it == 1) "person" else "people"}") }
            append(". Say 'help' for options.")
        },
        state = MealPlannerActivityState.WORKING,
        suggestions = listOf(
            suggestion("Help", "help"),
            suggestion("Cancel plan", "cancel plan"),
        ),
    )

    private fun generatingRecipeActivity(
        snapshot: MealPlanSnapshot,
        dayIndex: Int,
        totalDays: Int,
        batchStep: Int? = null,
        batchTotal: Int? = null,
    ): MealPlannerActivity = MealPlannerActivity(
        title = batchStep?.let { "Generating recipe ${it} of ${batchTotal ?: 1}" } ?: "Generating recipe ${dayIndex + 1} of $totalDays",
        subtitle = snapshot.days.firstOrNull { it.dayIndex == dayIndex }?.title?.let { title ->
            batchStep?.let { "$title (${it}/${batchTotal ?: 1}). Say 'help' for options." }
                ?: "$title. Say 'help' for options."
        } ?: "Building the recipe for Day ${dayIndex + 1}. Say 'help' for options.",
        state = MealPlannerActivityState.WORKING,
        suggestions = listOf(
            suggestion("Show current plan", "show current plan"),
            suggestion("Help", "help"),
            suggestion("Cancel plan", "cancel plan"),
        ),
    )

    private fun replacingDayActivity(
        snapshot: MealPlanSnapshot,
        dayIndex: Int,
        batchStep: Int? = null,
        batchTotal: Int? = null,
    ): MealPlannerActivity = MealPlannerActivity(
        title = batchStep?.let { "Updating day ${it} of ${batchTotal ?: 1}" } ?: "Updating Day ${dayIndex + 1}",
        subtitle = snapshot.days.firstOrNull { it.dayIndex == dayIndex }?.title?.let { title ->
            val prefix = batchStep?.let { "Step ${it}/${batchTotal ?: 1}: " }.orEmpty()
            "${prefix}Replacing $title and regenerating its recipe. Say 'help' for options."
        } ?: "Replacing Day ${dayIndex + 1} and regenerating its recipe. Say 'help' for options.",
        state = MealPlannerActivityState.WORKING,
        suggestions = listOf(
            suggestion("Show current plan", "show current plan"),
            suggestion("Help", "help"),
            suggestion("Cancel plan", "cancel plan"),
        ),
    )

    private fun replacingPlanDayActivity(
        snapshot: MealPlanSnapshot,
        dayIndex: Int,
        batchStep: Int? = null,
        batchTotal: Int? = null,
    ): MealPlannerActivity = MealPlannerActivity(
        title = batchStep?.let { "Updating day ${it} of ${batchTotal ?: 1}" } ?: "Updating Day ${dayIndex + 1}",
        subtitle = snapshot.days.firstOrNull { it.dayIndex == dayIndex }?.title?.let { title ->
            val prefix = batchStep?.let { "Step ${it}/${batchTotal ?: 1}: " }.orEmpty()
            "${prefix}Replacing $title in your plan. Say 'help' for options."
        } ?: "Replacing Day ${dayIndex + 1} in your plan. Say 'help' for options.",
        state = MealPlannerActivityState.WORKING,
        suggestions = listOf(
            suggestion("Show current plan", "show current plan"),
            suggestion("Help", "help"),
            suggestion("Cancel plan", "cancel plan"),
        ),
    )

    private fun humanizeSlot(slot: String): String = when (slot) {
        "people" -> "people count"
        "days" -> "number of days"
        "dietary" -> "dietary requirements"
        "protein" -> "protein preferences"
        "cuisine" -> "cuisine preferences"
        else -> slot
    }
    private fun planReviewSuggestions(snapshot: MealPlanSnapshot): List<MealPlannerSuggestion> = buildList {
        add(suggestion("Generate recipes", "generate recipes"))
        add(suggestion("Show current plan", "show current plan"))
        primaryEditableDay(snapshot)?.let { dayIndex ->
            add(suggestion("Replace day ${dayIndex + 1}", "replace day ${dayIndex + 1}"))
        }
        add(suggestion("Change preferences", "change preferences"))
        add(suggestion("Help", "help"))
    }

    private fun collectingSuggestions(snapshot: MealPlanSnapshot, missing: List<String>): List<MealPlannerSuggestion> {
        val proteinOnlyMissing = missing == listOf("protein")
        val dietaryOnlyMissing = missing == listOf("dietary")
        val cuisineOnlyMissing = missing == listOf("cuisine")
        return buildList {
            if ("people" in missing) {
                add(suggestion("2 people", "2 people", composeMode = MealPlannerSuggestionComposeMode.APPEND_COMMA))
                if (missing.size == 1) {
                    add(suggestion("4 people", "4 people", composeMode = MealPlannerSuggestionComposeMode.APPEND_COMMA))
                }
            }
            if ("days" in missing) {
                add(suggestion("4 days", "4 days", composeMode = MealPlannerSuggestionComposeMode.APPEND_COMMA))
                if (missing.size == 1) {
                    add(suggestion("7 days", "7 days", composeMode = MealPlannerSuggestionComposeMode.APPEND_COMMA))
                }
            }
            if ("dietary" in missing) {
                val dietarySuggestions = if (dietaryOnlyMissing) {
                    fullDietarySuggestions()
                } else {
                    starterDietarySuggestions()
                }
                dietarySuggestions.forEach { dietary ->
                    add(
                        suggestion(
                            dietary.replaceFirstChar { ch -> ch.titlecase() },
                            dietary,
                            composeMode = MealPlannerSuggestionComposeMode.APPEND_COMMA,
                        ),
                    )
                }
            }
            if ("protein" in missing) {
                val proteinSuggestions = if (proteinOnlyMissing) {
                    compatibleProteinSuggestions(snapshot.dietaryRestrictions)
                } else {
                    listOf("chicken")
                }
                proteinSuggestions.forEach { protein ->
                    add(
                        suggestion(
                            protein.replaceFirstChar { ch -> ch.titlecase() },
                            protein,
                            composeMode = MealPlannerSuggestionComposeMode.APPEND_COMMA,
                        ),
                    )
                }
            }
            if ("cuisine" in missing) {
                val cuisineSuggestions = if (cuisineOnlyMissing) {
                    fullCuisineSuggestions()
                } else {
                    starterCuisineSuggestions()
                }
                cuisineSuggestions.forEach { cuisine ->
                    add(
                        suggestion(
                            cuisine.replaceFirstChar { ch -> ch.titlecase() },
                            cuisine,
                            composeMode = MealPlannerSuggestionComposeMode.APPEND_COMMA,
                        ),
                    )
                }
            }
            add(suggestion("Help", "help"))
            add(suggestion("Cancel plan", "cancel plan"))
        }.distinctBy(MealPlannerSuggestion::command)
    }

    private fun starterDietarySuggestions(): List<String> =
        listOf("no dietary requirements", "kid friendly", "gluten free", "nut free")

    private fun fullDietarySuggestions(): List<String> =
        listOf(
            "no dietary requirements",
            "kid friendly",
            "gluten free",
            "celiac safe",
            "dairy free",
            "egg free",
            "peanut free",
            "nut free",
            "soy free",
            "fish free",
            "shellfish free",
            "sesame free",
            "vegetarian",
            "vegan",
            "pescatarian",
            "paleo",
            "keto",
            "halal",
        )
    private fun starterCuisineSuggestions(): List<String> =
        listOf("no cuisine preference", "italian", "mexican", "15 to 30 minute quick meals")

    private fun fullCuisineSuggestions(): List<String> =
        listOf(
            "no cuisine preference",
            "italian",
            "chinese",
            "mexican",
            "indian",
            "thai",
            "vietnamese",
            "japanese",
            "korean",
            "mediterranean",
            "pub food",
            "15 to 30 minute quick meals",
            "bbq and grill",
            "slow cooker",
            "one pot",
        )

    private fun compatibleProteinSuggestions(dietaryRestrictions: List<String>): List<String> {
        val allOptions = listOf(
            "chicken",
            "beef mince",
            "beef",
            "turkey",
            "pork",
            "lamb",
            "fish",
            "salmon",
            "tuna",
            "tofu",
            "lentils",
            "beans",
            "eggs",
            "no protein preference",
            "snapper",
            "prawns",
            "chickpeas",
            "halloumi",
        )
        return allOptions.filter { protein ->
            protein == "no protein preference" ||
                detectProteinPreferenceConflicts(dietaryRestrictions, listOf(protein)).isEmpty()
        }
    }

    private fun proteinCompatibilityPrompt(snapshot: MealPlanSnapshot, conflicts: List<String>): String {
        val compatibleExamples = compatibleProteinSuggestions(snapshot.dietaryRestrictions)
            .filter { it != "no protein preference" }
            .take(5)
        return buildString {
            append("Those protein preferences don't fit ${snapshot.dietaryRestrictions.joinToString()}: ${conflicts.joinToString()}. ")
            append("Pick a compatible protein")
            if (compatibleExamples.isNotEmpty()) {
                append(" like ${compatibleExamples.joinToString()}")
            }
            append(" or say 'no protein preference'.")
        }
    }

    private fun finalizeSuggestions(snapshot: MealPlanSnapshot): List<MealPlannerSuggestion> = buildList {
        add(suggestion("Show current plan", "show current plan"))
        add(suggestion("Done meal planning", "done meal planning"))
        primaryEditableDay(snapshot)?.let { dayIndex ->
            add(suggestion("Regenerate day ${dayIndex + 1}", "regenerate day ${dayIndex + 1}"))
            add(suggestion("Replace day ${dayIndex + 1}", "replace day ${dayIndex + 1}"))
        }
        add(suggestion("Help", "help"))
    }

    private fun primaryEditableDay(snapshot: MealPlanSnapshot): Int? =
        snapshot.days.firstOrNull { it.status == MealPlanDayStatus.PERSISTED }?.dayIndex
            ?: snapshot.days.firstOrNull()?.dayIndex



    private fun suggestion(
        label: String,
        command: String,
        composeMode: MealPlannerSuggestionComposeMode = MealPlannerSuggestionComposeMode.REPLACE,
    ): MealPlannerSuggestion =
        MealPlannerSuggestion(label = label, command = command, composeMode = composeMode)

    private fun isFinalizeRequest(text: String): Boolean =
        Regex(
            """\b(?:done(?:\s+with\s+meal\s+planning|\s+meal\s+planning)?|finali[sz]e(?:\s+(?:meal\s+plan|meal\s+planning))?)\b""",
            RegexOption.IGNORE_CASE,
        ).containsMatchIn(text)
    private companion object {
        private const val TAG = "MealPlannerCoordinator"
        private const val RECENT_MEAL_HISTORY_LIMIT = 18
        private const val PENDING_COMPLETED_SUMMARY_LIMIT = 3
        private const val MAX_PLAN_VARIETY_REPAIR_PASSES = 2
        private const val MAX_DAY_VARIETY_REPAIR_ATTEMPTS = 2
        private const val MAX_PROMPT_HISTORY_TITLES = 6
        private const val MAX_PROMPT_HISTORY_PATTERNS = 4
        private val COMMON_PROTEINS = listOf(
            "chicken",
            "beef",
            "turkey",
            "pork",
            "lamb",
            "fish",
            "salmon",
            "tuna",
            "snapper",
            "prawns",
            "shrimp",
            "tofu",
            "lentils",
            "beans",
            "chickpeas",
            "eggs",
            "halloumi",
        )
        private val NON_ALNUM_REGEX = Regex("[^a-z0-9 ]")
        private val MULTISPACE_REGEX = Regex("\\s+")
        private val MEAL_TITLE_NOISE_REGEX = Regex(
            "\\b(?:easy|quick|simple|family|favourite|favorite|weeknight|style|with|and|the|a|an)\\b",
        )
    }
}

data class MealPlannerReply(
    val content: String,
)

data class MealPlannerSuggestion(
    val label: String,
    val command: String,
    val composeMode: MealPlannerSuggestionComposeMode = MealPlannerSuggestionComposeMode.REPLACE,
)

enum class MealPlannerSuggestionComposeMode {
    REPLACE,
    APPEND_COMMA,
}

data class MealPlannerActivity(
    val title: String,
    val subtitle: String,
    val state: MealPlannerActivityState,
    val suggestions: List<MealPlannerSuggestion> = emptyList(),
)


enum class MealPlannerActivityState {
    WORKING,
    WAITING,
}

private data class GeneratedRecipeResult(
    val snapshot: MealPlanSnapshot,
    val recipe: RecipeDraft,
    val dayTitle: String,
)
