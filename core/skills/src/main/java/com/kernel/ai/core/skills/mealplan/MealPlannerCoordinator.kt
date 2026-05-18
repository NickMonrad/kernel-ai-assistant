package com.kernel.ai.core.skills.mealplan

import android.util.Log
import com.kernel.ai.core.inference.EmbeddingEngine
import com.kernel.ai.core.inference.InferenceEngine
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

        if (isGenerationActive(snapshot.sessionId)) {
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
        val hasAnySlotUpdates =
            peopleCount != null ||
                daysCount != null ||
                updatedDietaryRestrictions != null ||
                updatedProteinPreferences != null
        if (hadAllSlotsBefore && !hasAnySlotUpdates) {
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
        val replaceDayIndex = slotExtractor.extractReplaceDayIndex(text)
        if (replaceDayIndex != null) {
            return replaceDayForReview(snapshot, replaceDayIndex)
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
        val replaceDayIndex = slotExtractor.extractReplaceDayIndex(text)
        val regenerateDayIndex = slotExtractor.extractRegenerateDayIndex(text)
        if (slotExtractor.isShowCurrentPlanRequest(text)) {
            return MealPlannerReply(currentPlanReply(snapshot))
        }
        val interruptedReviewReplacementDayIndex = snapshot.pendingGenerationDayIndex?.takeIf {
            snapshot.pendingGenerationKind == PendingGenerationKind.REPLACEMENT &&
                snapshot.days.isNotEmpty() &&
                snapshot.days.all { day -> day.status == MealPlanDayStatus.DRAFTED }
        }
        if (interruptedReviewReplacementDayIndex != null) {
            if (slotExtractor.isRetryRequest(text) || replaceDayIndex == interruptedReviewReplacementDayIndex) {
                return replaceDayForReview(snapshot, interruptedReviewReplacementDayIndex)
            }
            if (replaceDayIndex == null && regenerateDayIndex == null) {
                return MealPlannerReply(replacementRetryPrompt(interruptedReviewReplacementDayIndex))
            }
        }
        val interruptedReplacementDayIndex = snapshot.pendingGenerationDayIndex?.takeIf {
            snapshot.pendingGenerationKind == PendingGenerationKind.REPLACEMENT &&
                snapshot.days.isNotEmpty() &&
                snapshot.days.all { day -> day.status == MealPlanDayStatus.PERSISTED }
        }
        if (interruptedReplacementDayIndex != null) {
            if (slotExtractor.isRetryRequest(text) || replaceDayIndex == interruptedReplacementDayIndex) {
                return replaceDayAndGenerateRecipe(
                    snapshot = snapshot,
                    dayIndex = interruptedReplacementDayIndex,
                    intro = "I replaced Day ${interruptedReplacementDayIndex + 1}. Here’s the updated recipe:\n",
                    onPlannerActivityChanged = onPlannerActivityChanged,
                )
            }
            if (replaceDayIndex == null && regenerateDayIndex == null) {
                return MealPlannerReply(replacementRetryPrompt(interruptedReplacementDayIndex))
            }
        }
        val interruptedRecipeDayIndex = snapshot.pendingGenerationDayIndex?.takeIf {
            snapshot.pendingGenerationKind == PendingGenerationKind.RECIPE && snapshot.days.all { day -> day.status == MealPlanDayStatus.PERSISTED }
        }
        if (interruptedRecipeDayIndex != null) {
            if (slotExtractor.isRetryRequest(text) || regenerateDayIndex == interruptedRecipeDayIndex) {
                return generateSpecificDayRecipe(
                    snapshot,
                    interruptedRecipeDayIndex,
                    intro = "I regenerated Day ${interruptedRecipeDayIndex + 1}. Here’s the updated recipe:\n",
                    mutationSummary = regenerationChangeMessage(snapshot, interruptedRecipeDayIndex),
                    includeCurrentPlanReply = true,
                    onPlannerActivityChanged = onPlannerActivityChanged,
                )
            }
            if (replaceDayIndex == null && regenerateDayIndex == null) {
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

        if (replaceDayIndex != null) {
            return replaceDayAndGenerateRecipe(
                snapshot = snapshot,
                dayIndex = replaceDayIndex,
                intro = "I replaced Day ${replaceDayIndex + 1}. Here’s the updated recipe:\n",
                onPlannerActivityChanged = onPlannerActivityChanged,
            )
        }
        if (regenerateDayIndex != null) {
            return generateSpecificDayRecipe(
                snapshot,
                regenerateDayIndex,
                intro = "I regenerated Day ${regenerateDayIndex + 1}. Here’s the updated recipe:\n",
                mutationSummary = regenerationChangeMessage(snapshot, regenerateDayIndex),
                includeCurrentPlanReply = true,
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
        return MealPlannerReply(
            "Your meal plan is ready. Say 'regenerate day 2', 'replace day 1', or 'done meal planning' to finalize it.",
        )
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
            val rawPlan = inferenceEngine.generateOnce(
                prompt = buildPlanUserPrompt(snapshot, recentHistory),
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
        repeat(MAX_DAY_VARIETY_REPAIR_ATTEMPTS) {
            val raw = inferenceEngine.generateOnce(
                prompt = buildReplacementDayUserPrompt(snapshot, currentDays, dayIndex, recentHistory),
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
        if (added?.contains("no dietary requirements") == true) {
            return listOf("no dietary requirements")
        }
        val updated = current
            .filterNot { it == "no dietary requirements" || it in removed }
            .toMutableList()
        added.orEmpty()
            .filterNot { it == "no dietary requirements" || it in removed }
            .forEach { restriction ->
                if (restriction !in updated) {
                    updated += restriction
                }
            }
        return updated
    }

    private fun mergeUpdatedProteinPreferences(
        current: List<String>,
        added: List<String>?,
        removed: List<String>,
    ): List<String>? {
        if (added == null && removed.isEmpty()) return null
        if (added?.contains("no protein preference") == true) {
            return listOf("no protein preference")
        }
        val updated = current
            .filterNot { it == "no protein preference" || it in removed }
            .toMutableList()
        added.orEmpty()
            .filterNot { it == "no protein preference" || it in removed }
            .forEach { protein ->
                if (protein !in updated) {
                    updated += protein
                }
            }
        return updated
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
                    "I hit a validation problem while generating Day ${day.dayIndex + 1}: ${e.message}\nSay 'regenerate day ${day.dayIndex + 1}' or 'replace day ${day.dayIndex + 1}' to recover.",
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
            MealPlannerReply("Your meal plan is ready. Say 'regenerate day 2', 'replace day 1', or 'done meal planning' to finalize it.")
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
                return@withSessionGeneration MealPlannerReply(replacementFailureMessage(dayIndex, e.message))
            }
            onPlannerActivityChanged(replacingDayActivity(generationSnapshot, dayIndex))
            val replaced = try {
                generateReplacementDayInternal(generationSnapshot, dayIndex, markPendingGeneration = false)
            } catch (e: IllegalArgumentException) {
                return@withSessionGeneration MealPlannerReply(replacementFailureMessage(dayIndex, e.message))
            } catch (e: MealPlanValidationException) {
                return@withSessionGeneration MealPlannerReply(replacementFailureMessage(dayIndex, e.message))
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
    ): MealPlannerReply =
        withSessionGeneration(
            sessionId = snapshot.sessionId,
            onBusy = { MealPlannerReply(generationInProgressMessage(snapshot)) },
        ) {
            try {
                val replaced = generateReplacementDayInternal(snapshot, dayIndex)
                MealPlannerReply(
                    replacementChangeMessage(snapshot, replaced, dayIndex) + "\n\n" + currentPlanReply(replaced),
                )
            } catch (e: IllegalArgumentException) {
                MealPlannerReply(replacementFailureMessage(dayIndex, e.message))
            } catch (e: MealPlanValidationException) {
                MealPlannerReply(replacementFailureMessage(dayIndex, e.message))
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
            val enforceRecentPatternDiversity = shouldEnforceRecentPatternDiversity(snapshot)
            val raw = inferenceEngine.generateOnce(
                prompt = buildReplacementDayUserPrompt(snapshot, dayIndex, recentHistory),
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
                subtitle = "Say 'show current plan', 'generate recipes', 'replace day 2', or 'change preferences'.",
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
                subtitle = "Say 'generate' to rebuild the meal plan with these changes.",
                state = MealPlannerActivityState.WAITING,
                suggestions = listOf(
                    suggestion("Generate", "generate"),
                    suggestion("Show current plan", "show current plan"),
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
                subtitle = "Day ${failedDay.dayIndex + 1} failed. Say 'regenerate day ${failedDay.dayIndex + 1}' or 'replace day ${failedDay.dayIndex + 1}'.",
                state = MealPlannerActivityState.WAITING,
                suggestions = listOf(
                    suggestion("Regenerate day ${failedDay.dayIndex + 1}", "regenerate day ${failedDay.dayIndex + 1}"),
                    suggestion("Replace day ${failedDay.dayIndex + 1}", "replace day ${failedDay.dayIndex + 1}"),
                    suggestion("Show current plan", "show current plan"),
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
                    subtitle = "Retry Day ${pendingGenerationDayIndex + 1} or inspect the current plan.",
                    state = MealPlannerActivityState.WAITING,
                    suggestions = listOf(
                        suggestion("Retry", "retry"),
                        suggestion("Replace day ${pendingGenerationDayIndex + 1}", "replace day ${pendingGenerationDayIndex + 1}"),
                        suggestion("Show current plan", "show current plan"),
                        suggestion("Cancel plan", "cancel plan"),
                    ),
                )
            snapshot.pendingGenerationKind == PendingGenerationKind.RECIPE && pendingGenerationDayIndex != null &&
                snapshot.days.all { it.status == MealPlanDayStatus.PERSISTED } ->
                MealPlannerActivity(
                    title = "Recipe generation paused",
                    subtitle = "Retry Day ${pendingGenerationDayIndex + 1} or inspect the current plan.",
                    state = MealPlannerActivityState.WAITING,
                    suggestions = listOf(
                        suggestion("Retry", "retry"),
                        suggestion("Regenerate day ${pendingGenerationDayIndex + 1}", "regenerate day ${pendingGenerationDayIndex + 1}"),
                        suggestion("Show current plan", "show current plan"),
                        suggestion("Cancel plan", "cancel plan"),
                    ),
                )
            pendingDay != null -> MealPlannerActivity(
                title = "Meal planner paused",
                subtitle = "Ready to resume at Day ${pendingDay.dayIndex + 1} of ${snapshot.days.size}. Say 'generate recipes' to continue.",
                state = MealPlannerActivityState.WAITING,
                suggestions = listOf(
                    suggestion("Generate recipes", "generate recipes"),
                    suggestion("Show current plan", "show current plan"),
                    suggestion("Cancel plan", "cancel plan"),
                ),
            )
            else -> MealPlannerActivity(
                title = "Meal plan ready",
                subtitle = "Say 'show current plan', 'replace day 1', 'regenerate day 2', or 'done meal planning'.",
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
        append("Reply with any updated people count, days, dietary requirements, allergens, ingredients to avoid, or protein preferences.")
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
        "dietary" -> "- Any dietary requirements, allergens, or ingredients to avoid?"
        "protein" -> "- What protein preferences should I use?"
        else -> "- $slot"
    }

    private fun planReviewPrompt(snapshot: MealPlanSnapshot): String =
        if (snapshot.days.isEmpty()) {
            "I still need to rebuild your meal plan draft. Say 'generate recipes' to try again, 'change preferences', or 'cancel'."
        } else {
            buildPlanSummary(snapshot) + "\n\n" + planReviewActionsPrompt()
        }

    private fun currentPlanReply(snapshot: MealPlanSnapshot): String =
        if (snapshot.days.isEmpty()) {
            promptForSnapshot(snapshot)
        } else {
            buildPlanSummary(snapshot) + "\n\n" + statusPrompt(snapshot)
        }

    private fun planReviewActionsPrompt(): String =
        "Say 'generate recipes', 'replace day 2', 'change preferences', or 'cancel'."

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
                else -> "Your meal plan is ready. Say 'regenerate day 2', 'replace day 1', or 'done meal planning' to finalize it."
            }
        }
        MealPlanSessionStatus.COMPLETED -> "Your meal plan is already finalized."
        MealPlanSessionStatus.CANCELLED -> "That meal plan session was cancelled."
    }

    private fun resumePrompt(snapshot: MealPlanSnapshot, dayIndex: Int): String =
        "I still need to finish Day ${dayIndex + 1} of ${snapshot.days.size}. Say 'generate recipes' to continue or 'cancel' to stop."

    private fun replacementRetryPrompt(dayIndex: Int): String =
        "I still need to replace Day ${dayIndex + 1}. Say 'retry' or 'replace day ${dayIndex + 1}' to try again, or 'cancel' to stop."

    private fun regenerateRetryPrompt(snapshot: MealPlanSnapshot, dayIndex: Int): String =
        "I still need to finish Day ${dayIndex + 1} of ${snapshot.days.size}. Say 'retry' or 'regenerate day ${dayIndex + 1}' to try again, or 'cancel' to stop."

    private fun recoveryPrompt(dayIndex: Int): String =
        "I still need to finish Day ${dayIndex + 1}. Say 'regenerate day ${dayIndex + 1}' or 'replace day ${dayIndex + 1}'."

    private fun replacementFailureMessage(dayIndex: Int, detail: String?): String =
        "I couldn't replace Day ${dayIndex + 1}. ${detail ?: "Try again with a different day."}"

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
- treat dietary requirements, allergens, and ingredient exclusions as strict requirements
- prefer novelty by default across recent meal plans and within the current draft
- avoid exact repeats and obvious same-protein same-cooking-style repeats unless the user explicitly asks for familiar meals
- do not include ingredients, quantities, steps, markdown, commentary, or code fences
""".trimIndent()

    private fun buildPlanUserPrompt(
        snapshot: MealPlanSnapshot,
        recentHistory: List<RecentMealHistoryEntry>,
    ): String {
        val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH))
        val recentHistoryBlock = buildRecentHistoryPromptBlock(recentHistory)
        return buildString {
            appendLine("Build a ${snapshot.daysCount}-day dinner meal plan for ${snapshot.peopleCount} people.")
            appendLine("Date: $now")
            appendLine("Dietary requirements: ${snapshot.dietaryRestrictions.ifEmpty { listOf("none provided") }.joinToString()}")
            appendLine("Protein preferences: ${snapshot.proteinPreferences.ifEmpty { listOf("no preference provided") }.joinToString()}")
            appendLine("Use practical weeknight meal ideas suitable for Australia/New Zealand households.")
            if (recentHistoryBlock.isNotBlank()) {
                appendLine()
                append(recentHistoryBlock)
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
        )

    private fun buildReplacementDayUserPrompt(
        snapshot: MealPlanSnapshot,
        currentDays: List<MealPlanDraftDay>,
        dayIndex: Int,
        recentHistory: List<RecentMealHistoryEntry>,
    ): String {
        val currentTitle = currentDays.firstOrNull { it.dayIndex == dayIndex }?.title ?: "Day ${dayIndex + 1}"
        val recentHistoryBlock = buildRecentHistoryPromptBlock(recentHistory)
        return buildString {
            appendLine("Replace Day ${dayIndex + 1} in this meal plan.")
            appendLine("Current plan:")
            appendLine(currentDays.sortedBy { it.dayIndex }.joinToString("\n") { day -> "Day ${day.dayIndex + 1}: ${day.title}" })
            appendLine("Dietary requirements: ${snapshot.dietaryRestrictions.ifEmpty { listOf("none provided") }.joinToString()}")
            appendLine("Protein preferences: ${snapshot.proteinPreferences.ifEmpty { listOf("no preference provided") }.joinToString()}")
            if (recentHistoryBlock.isNotBlank()) {
                appendLine()
                appendLine(recentHistoryBlock)
            }
            appendLine()
            appendLine("Return one alternative day that fits the plan without duplicating '$currentTitle' or clashing with the surrounding days.")
            append("Remember: this is user-visible Day ${dayIndex + 1}, but the JSON day_index must be zero-based and equal $dayIndex.")
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

    private fun generatingPlanActivity(snapshot: MealPlanSnapshot): MealPlannerActivity = MealPlannerActivity(
        title = "Generating meal plan",
        subtitle = buildString {
            val days = snapshot.daysCount ?: snapshot.days.size
            val people = snapshot.peopleCount
            if (days > 0) append("Drafting $days dinners") else append("Drafting your dinners")
            people?.let { append(" for $it ${if (it == 1) "person" else "people"}") }
            append(".")
        },
        state = MealPlannerActivityState.WORKING,
    )

    private fun generatingRecipeActivity(
        snapshot: MealPlanSnapshot,
        dayIndex: Int,
        totalDays: Int,
    ): MealPlannerActivity = MealPlannerActivity(
        title = "Generating recipe ${dayIndex + 1} of $totalDays",
        subtitle = snapshot.days.firstOrNull { it.dayIndex == dayIndex }?.title
            ?: "Building the recipe for Day ${dayIndex + 1}.",
        state = MealPlannerActivityState.WORKING,
    )

    private fun replacingDayActivity(snapshot: MealPlanSnapshot, dayIndex: Int): MealPlannerActivity = MealPlannerActivity(
        title = "Updating Day ${dayIndex + 1}",
        subtitle = snapshot.days.firstOrNull { it.dayIndex == dayIndex }?.title?.let {
            "Replacing $it and regenerating its recipe."
        } ?: "Replacing Day ${dayIndex + 1} and regenerating its recipe.",
        state = MealPlannerActivityState.WORKING,
    )

    private fun humanizeSlot(slot: String): String = when (slot) {
        "people" -> "people count"
        "days" -> "number of days"
        "dietary" -> "dietary requirements"
        "protein" -> "protein preferences"
        else -> slot
    }
    private fun planReviewSuggestions(snapshot: MealPlanSnapshot): List<MealPlannerSuggestion> = buildList {
        add(suggestion("Generate recipes", "generate recipes"))
        add(suggestion("Show current plan", "show current plan"))
        primaryEditableDay(snapshot)?.let { dayIndex ->
            add(suggestion("Replace day ${dayIndex + 1}", "replace day ${dayIndex + 1}"))
        }
        add(suggestion("Change preferences", "change preferences"))
    }

    private fun collectingSuggestions(snapshot: MealPlanSnapshot, missing: List<String>): List<MealPlannerSuggestion> {
        val proteinOnlyMissing = missing == listOf("protein")
        val dietaryOnlyMissing = missing == listOf("dietary")
        return buildList {
            if ("people" in missing) {
                add(suggestion("2 people", "2 people"))
                if (missing.size == 1) {
                    add(suggestion("4 people", "4 people"))
                }
            }
            if ("days" in missing) {
                add(suggestion("4 days", "4 days"))
                if (missing.size == 1) {
                    add(suggestion("7 days", "7 days"))
                }
            }
            if ("dietary" in missing) {
                val dietarySuggestions = if (dietaryOnlyMissing) {
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
                } else {
                    listOf("no dietary requirements", "kid friendly", "gluten free", "nut free")
                }
                dietarySuggestions.forEach { dietary ->
                    add(suggestion(dietary.replaceFirstChar { ch -> ch.titlecase() }, dietary))
                }
            }
            if ("protein" in missing) {
                val proteinSuggestions = if (proteinOnlyMissing) {
                    compatibleProteinSuggestions(snapshot.dietaryRestrictions)
                } else {
                    listOf("chicken")
                }
                proteinSuggestions.forEach { protein ->
                    add(suggestion(protein.replaceFirstChar { ch -> ch.titlecase() }, protein))
                }
            }
            add(suggestion("Cancel plan", "cancel plan"))
        }.distinctBy(MealPlannerSuggestion::command)
    }
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
    }

    private fun primaryEditableDay(snapshot: MealPlanSnapshot): Int? =
        snapshot.days.firstOrNull { it.status == MealPlanDayStatus.PERSISTED }?.dayIndex
            ?: snapshot.days.firstOrNull()?.dayIndex

    private fun suggestion(label: String, command: String): MealPlannerSuggestion =
        MealPlannerSuggestion(label = label, command = command)

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
)

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
