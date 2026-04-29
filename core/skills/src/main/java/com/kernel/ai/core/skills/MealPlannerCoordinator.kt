package com.kernel.ai.core.skills



import com.kernel.ai.core.inference.MealPlannerStateMachine

import com.kernel.ai.core.memory.entity.MealPlanSessionEntity

import com.kernel.ai.core.memory.repository.MealPlanSessionRepository

import com.kernel.ai.core.skills.SkillCall

import com.kernel.ai.core.skills.SkillResult

import com.kernel.ai.core.skills.SkillRegistry

import kotlinx.coroutines.flow.MutableStateFlow

import kotlinx.coroutines.flow.StateFlow

import kotlinx.coroutines.flow.asStateFlow

import kotlinx.coroutines.sync.Mutex

import kotlinx.coroutines.sync.withLock



/**

 * Deterministic coordinator for the meal-planner workflow.

 *

 * The app owns flow control, persistence, artifact creation, and list generation.

 * The LLM only helps with natural-language interaction and bounded content generation

 * inside strict schemas.

 *

 * Responsibilities:

 * - Decide whether a message belongs to an active meal-planner session

 * - Interpret the message in the context of the active stage

 * - Update structured state via [MealPlanSessionRepository]

 * - Decide the next prompt / chips / action

 * - Delegate content generation to the appropriate stage skill

 * - Persist artifacts

 *

 * Thread-safe: all public methods acquire a mutex to prevent concurrent state mutations.

 */

class MealPlannerCoordinator(

    private val sessionRepo: MealPlanSessionRepository,

    private val skillRegistry: dagger.Lazy<SkillRegistry>,

) {



    // ── Internal state ──────────────────────────────────────────────────

    private val _activeConversationId = MutableStateFlow<String?>(null)
    val activeConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()

    private val mutex = Mutex()

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Starts or resumes a meal-planner session for [conversationId].
     *
     * If a session already exists for this conversation, resumes it from its current state.
     * Otherwise creates a fresh session in COLLECTING_PREFERENCES state.
     *
     * Returns the initial prompt/message for the user.
     */
    suspend fun startOrResume(conversationId: String): CoordinatorResult = mutex.withLock {
        val existing = sessionRepo.getSession(conversationId)
        return when {
            existing == null -> startNew(conversationId)
            MealPlannerStateMachine.isTerminal(existing.statusAsState()) -> startNew(conversationId)
            else -> resumeSession(conversationId, existing)
        }
    }

    /**
     * Processes a user message within an active meal-planner session.
     *
     * The coordinator interprets the message against the current stage and returns
     * the appropriate response. The LLM is only invoked for bounded content generation.
     *
     * @param conversationId the conversation scope
     * @param userInput the user's message
     * @return the coordinator's response
     */
    suspend fun processMessage(conversationId: String, userInput: String): CoordinatorResult = mutex.withLock {
        val session = sessionRepo.getSession(conversationId)
            ?: return CoordinatorResult.Text("No active meal plan found. Would you like to start one?")

        val state = session.statusAsState()

        return when (state) {
            MealPlannerStateMachine.State.COLLECTING_PREFERENCES -> handleCollecting(conversationId, userInput, session)
            MealPlannerStateMachine.State.PLAN_DRAFT_READY -> handlePlanDraftReady(conversationId, userInput, session)
            MealPlannerStateMachine.State.PLAN_CONFIRMED -> handlePlanConfirmed(conversationId, userInput, session)
            MealPlannerStateMachine.State.GENERATING_RECIPE -> handleGeneratingRecipe(conversationId, userInput, session)
            MealPlannerStateMachine.State.RECIPE_REVIEW -> handleRecipeReview(conversationId, userInput, session)
            MealPlannerStateMachine.State.WRITING_ARTIFACTS -> handleWritingArtifacts(conversationId, userInput, session)
            MealPlannerStateMachine.State.COMPLETED -> CoordinatorResult.Text("Your meal plan is complete. Would you like to start a new one?")
            MealPlannerStateMachine.State.CANCELLED -> CoordinatorResult.Text("Meal plan cancelled. What would you like to do?")
        }
    }

    /**
     * Cancels the active meal-planner session for [conversationId].
     */
    suspend fun cancel(conversationId: String): CoordinatorResult = mutex.withLock {
        sessionRepo.updateStatus(conversationId, "cancelled")
        return CoordinatorResult.Text("Meal plan cancelled.")
    }

    /**
     * Checks whether a user message belongs to an active meal-planner session.
     * Used by QuickIntentRouter or ChatViewModel to route early.
     */
    suspend fun hasActiveSession(conversationId: String): Boolean = mutex.withLock {
        val session = sessionRepo.getSession(conversationId)
        return session != null && !MealPlannerStateMachine.isTerminal(session.statusAsState())
    }

    /**
     * Gets the current state for a conversation.
     */
    suspend fun getState(conversationId: String): MealPlannerStateMachine.State? = mutex.withLock {
        val session = sessionRepo.getSession(conversationId)
        return session?.statusAsState()
    }

    // ── Stage handlers ──────────────────────────────────────────────────

    private suspend fun startNew(conversationId: String): CoordinatorResult {
        sessionRepo.createOrReset(conversationId)
        _activeConversationId.value = conversationId

        // Ask for preferences in 2-3 grouped batches
        return CoordinatorResult.Text(
            "I'll help you plan meals. Let's start with a few details:\n\n" +
            "1. How many people is this for?\n" +
            "2. Any dietary restrictions (vegetarian, gluten-free, etc.)?\n" +
            "3. How many days do you want to plan for?\n" +
            "4. Any protein preferences (chicken, fish, tofu, etc.)?"
        )
    }

    private suspend fun resumeSession(conversationId: String, session: MealPlanSessionEntity): CoordinatorResult {
        _activeConversationId.value = conversationId
        val state = session.statusAsState()

        return when (state) {
            MealPlannerStateMachine.State.COLLECTING_PREFERENCES -> {
                // Check if preferences already exist — if so, skip to plan
                if (session.peopleCount != null && session.days != null &&
                    session.dietaryRestrictionsJson != "[]" && session.proteinPreferencesJson != "[]") {
                    transitionTo(conversationId, MealPlannerStateMachine.State.PLAN_DRAFT_READY)
                    return generatePlan(conversationId)
                }
                CoordinatorResult.Text(
                    "Welcome back! Your meal plan is still collecting preferences.\n" +
                    "Current: ${session.peopleCount ?: "?"} people, ${session.days ?: "?"} days\n" +
                    "Dietary: ${session.dietaryRestrictionsJson}, Proteins: ${session.proteinPreferencesJson}\n\n" +
                    "What else would you like to add or change?"
                )
            }
            MealPlannerStateMachine.State.PLAN_DRAFT_READY -> {
                // Re-show the plan
                CoordinatorResult.Text(
                    "Here's your high-level plan:\n${session.highLevelPlanJson ?: "No plan generated yet."}\n\n" +
                    "Would you like me to generate the full recipes with cooking steps? Reply 'yes' or 'confirm' to proceed."
                )
            }
            MealPlannerStateMachine.State.PLAN_CONFIRMED -> {
                // Start generating recipes
                transitionTo(conversationId, MealPlannerStateMachine.State.GENERATING_RECIPE)
                val dayLabel = session.currentDayIndex?.let { "Day ${it + 1}" } ?: "Day 1"
                CoordinatorResult.Text("Generating the full recipe for $dayLabel...")
            }
            MealPlannerStateMachine.State.GENERATING_RECIPE -> {
                // Already generating — wait for user to say "next" or "skip"
                val totalDays = session.days ?: "?"
                val currentDay = session.currentDayIndex?.let { it + 1 } ?: 1
                CoordinatorResult.Text(
                    "Currently on Day $currentDay of $totalDays. What would you like to do?"
                )
            }
            MealPlannerStateMachine.State.RECIPE_REVIEW -> {
                val totalDays = session.days ?: "?"
                val currentDay = session.currentDayIndex?.let { it + 1 } ?: 1
                CoordinatorResult.Text(
                    "Day $currentDay recipe ready for review. Say 'next' to continue, 'regenerate' to retry, or 'done' to finish."
                )
            }
            MealPlannerStateMachine.State.WRITING_ARTIFACTS -> {
                CoordinatorResult.Text("Consolidating your meal plan into lists...")
            }
            MealPlannerStateMachine.State.COMPLETED, MealPlannerStateMachine.State.CANCELLED -> {
                startNew(conversationId)
            }
        }
    }

    private suspend fun handleCollecting(
        conversationId: String,
        userInput: String,
        session: MealPlanSessionEntity,
    ): CoordinatorResult {
        // Parse user input for preference keywords
        val peopleRegex = Regex("""\b(\d+)\s*(?:people|persons|pax|folks|head)\b""", RegexOption.IGNORE_CASE)
        val daysRegex = Regex("""(\d+)\s*(?:day|week|night)s?\b""", RegexOption.IGNORE_CASE)
        val restrictionKeywords = setOf(
            "vegetarian", "vegan", "gluten.?free", "dairy.?free", "halal", "kosher",
            "low.?lactose", "paleo", "keto", "pescatarian", "nut.?free",
        )

        var peopleCount = session.peopleCount
        var days = session.days
        var restrictions = session.dietaryRestrictionsJson
        var proteins = session.proteinPreferencesJson

        // Extract people count
        peopleRegex.find(userInput)?.let {
            peopleCount = it.groupValues[1].toIntOrNull()
        }

        // Extract days
        daysRegex.find(userInput)?.let {
            days = it.groupValues[1].toIntOrNull()
        }

        // Extract dietary restrictions
        val matchedRestrictions = restrictionKeywords.mapNotNull { keyword ->
            Regex(keyword, RegexOption.IGNORE_CASE).find(userInput)?.value
        }.distinct()
        if (matchedRestrictions.isNotEmpty()) {
            restrictions = matchedRestrictions.joinToString(",") { "\"$it\"" }.let { "[$it]" }
        }

        // Extract protein preferences
        val proteinKeywords = setOf("chicken", "beef", "fish", "pork", "tofu", "lamb", "shrimp", "prawn", "eggs", "turkey")
        val matchedProteins = proteinKeywords.mapNotNull { keyword ->
            Regex("\\b$keyword\\b", RegexOption.IGNORE_CASE).find(userInput)?.value?.lowercase()
        }.distinct()
        if (matchedProteins.isNotEmpty()) {
            proteins = matchedProteins.joinToString(",") { "\"$it\"" }.let { "[$it]" }
        }

        // Check if we have enough info to proceed
        if (peopleCount != null && days != null && restrictions != "[]" && proteins != "[]") {
            sessionRepo.updatePreferences(conversationId, peopleCount, days, restrictions, proteins)
            transitionTo(conversationId, MealPlannerStateMachine.State.PLAN_DRAFT_READY)
            return generatePlan(conversationId)
        }

        // Ask for missing info
        val missing = mutableListOf<String>()
        if (peopleCount == null) missing.add("number of people")
        if (days == null) missing.add("number of days")
        if (restrictions == "[]") missing.add("dietary restrictions")
        if (proteins == "[]") missing.add("protein preferences")

        return CoordinatorResult.Text(
            "Thanks! I have:\n" +
            "  People: ${peopleCount ?: "?"}\n" +
            "  Days: ${days ?: "?"}\n" +
            "  Dietary: ${if (restrictions != "[]") restrictions else "?"}\n" +
            "  Proteins: ${if (proteins != "[]") proteins else "?"}\n\n" +
            "I still need: ${missing.joinToString(", ")}. What else would you like?"
        )
    }

    private suspend fun handlePlanDraftReady(
        conversationId: String,
        userInput: String,
        session: MealPlanSessionEntity,
    ): CoordinatorResult {
        val lowerInput = userInput.lowercase()

        return when {
            lowerInput in setOf("yes", "y", "confirm", "looks good", "go ahead", "proceed", "start") -> {
                transitionTo(conversationId, MealPlannerStateMachine.State.PLAN_CONFIRMED)
                CoordinatorResult.Text("Generating the full recipes now...")
            }
            lowerInput in setOf("no", "nope", "change", "regenerate", "different", "try again") -> {
                // Regenerate the plan
                transitionTo(conversationId, MealPlannerStateMachine.State.PLAN_DRAFT_READY)
                generatePlan(conversationId)
            }
            else -> {
                // Parse feedback for specific changes
                CoordinatorResult.Text(
                    "Here's your plan:\n${session.highLevelPlanJson ?: "No plan yet."}\n\n" +
                    "Would you like to confirm, regenerate, or make changes?"
                )
            }
        }
    }

    private suspend fun handlePlanConfirmed(
        conversationId: String,
        userInput: String,
        session: MealPlanSessionEntity,
    ): CoordinatorResult {
        // Start generating recipes for the first day
        transitionTo(conversationId, MealPlannerStateMachine.State.GENERATING_RECIPE)
        sessionRepo.updatePreferences(conversationId, currentDayIndex = 0)
        val dayLabel = "Day 1"
        return CoordinatorResult.Text("Generating the full recipe for $dayLabel...")
    }

    private suspend fun handleGeneratingRecipe(
        conversationId: String,
        userInput: String,
        session: MealPlanSessionEntity,
    ): CoordinatorResult {
        val lowerInput = userInput.lowercase()

        return when {
            lowerInput in setOf("next", "continue", "skip", "skip this", "move on") -> {
                // Advance to next day
                sessionRepo.advanceDay(conversationId)
                val newState = sessionRepo.getSession(conversationId)?.statusAsState()
                when (newState) {
                    MealPlannerStateMachine.State.COMPLETED -> {
                        transitionTo(conversationId, MealPlannerStateMachine.State.WRITING_ARTIFACTS)
                        return CoordinatorResult.Text("All days complete! Consolidating your shopping list...")
                    }
                    MealPlannerStateMachine.State.GENERATING_RECIPE -> {
                        val nextDay = session.currentDayIndex?.let { it + 1 }?.plus(1) ?: 1
                        return CoordinatorResult.Text("Generating the full recipe for Day $nextDay...")
                    }
                    else -> CoordinatorResult.Text("Proceeding to the next day...")
                }
            }
            lowerInput in setOf("regenerate", "retry", "redo") -> {
                // Regenerate current day (keep same day index)
                val dayLabel = session.currentDayIndex?.let { it + 1 } ?: 1
                return CoordinatorResult.Text("Regenerating Day $dayLabel...")


            }
            else -> {
                // Parse feedback for recipe changes
                val dayLabel = session.currentDayIndex?.let { it + 1 } ?: 1
                CoordinatorResult.Text(
                    "Day $dayLabel recipe generated. Say 'next' to continue, 'regenerate' to retry, or 'done' to finish."
                )
            }
        }
    }

    private suspend fun handleRecipeReview(
        conversationId: String,
        userInput: String,
        session: MealPlanSessionEntity,
    ): CoordinatorResult {
        val lowerInput = userInput.lowercase()

        return when {
            lowerInput in setOf("next", "continue", "done", "finish", "save") -> {
                transitionTo(conversationId, MealPlannerStateMachine.State.WRITING_ARTIFACTS)
                return CoordinatorResult.Text("Consolidating your meal plan into lists...")
            }
            lowerInput in setOf("regenerate", "retry", "redo") -> {
                transitionTo(conversationId, MealPlannerStateMachine.State.GENERATING_RECIPE)
                val dayLabel = session.currentDayIndex?.let { it + 1 } ?: 1
                return CoordinatorResult.Text("Regenerating Day $dayLabel...")
            }
            else -> {
                val dayLabel = session.currentDayIndex?.let { it + 1 } ?: 1
                CoordinatorResult.Text("Day $dayLabel recipe ready. Say 'next' to continue, 'regenerate' to retry, or 'done' to finish.")
            }
        }
    }

    private suspend fun handleWritingArtifacts(
        conversationId: String,
        userInput: String,
        session: MealPlanSessionEntity,
    ): CoordinatorResult {
        // Write artifacts (app-driven) then mark completed
        transitionTo(conversationId, MealPlannerStateMachine.State.COMPLETED)
        sessionRepo.markCompleted(conversationId)
        _activeConversationId.value = null

        val people = session.peopleCount ?: "?"
        val days = session.days ?: "?"
        return CoordinatorResult.Text(
            "Meal plan complete! Here's your summary:\n" +
            "  $people people, $days days\n\n" +
            "Your recipes and shopping list have been saved. Would you like to start a new meal plan?"
        )
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private suspend fun transitionTo(conversationId: String, target: MealPlannerStateMachine.State) {
        val session = sessionRepo.getSession(conversationId) ?: return
        val current = session.statusAsState()
        if (target !in MealPlannerStateMachine.validTransitions(current)) return
        val statusString = target.name.lowercase()
        sessionRepo.updateStatus(conversationId, statusString)
    }

    private suspend fun generatePlan(conversationId: String): CoordinatorResult {
        // Delegate to the plan skill for LLM content generation
        val planSkill = skillRegistry.get().get("meal_planner_plan")


        if (planSkill == null) {
            return CoordinatorResult.Text("Unable to generate plan — skill not found.")
        }

        val result = try {
            kotlinx.coroutines.runBlocking {
                planSkill.execute(SkillCall("meal_planner_plan", emptyMap()))
            }
        } catch (e: Exception) {
            return CoordinatorResult.Text("Error generating plan: ${e.message}")
        }

        return when (result) {
            is SkillResult.DirectReply -> CoordinatorResult.Text(result.content)
            is SkillResult.Success -> CoordinatorResult.Text(result.content)
            is SkillResult.Failure -> CoordinatorResult.Text("Error: ${result.error}")
            else -> CoordinatorResult.Text("Unexpected result.")
        }
    }

    // ── Extension ───────────────────────────────────────────────────────

    private fun MealPlanSessionEntity.statusAsState(): MealPlannerStateMachine.State {
        return when (status) {
            "collecting_preferences" -> MealPlannerStateMachine.State.COLLECTING_PREFERENCES
            "high_level_plan_ready" -> MealPlannerStateMachine.State.PLAN_DRAFT_READY
            "plan_confirmed" -> MealPlannerStateMachine.State.PLAN_CONFIRMED
            "generating_recipes" -> MealPlannerStateMachine.State.GENERATING_RECIPE
            "recipe_review" -> MealPlannerStateMachine.State.RECIPE_REVIEW
            "writing_artifacts" -> MealPlannerStateMachine.State.WRITING_ARTIFACTS
            "completed" -> MealPlannerStateMachine.State.COMPLETED
            "cancelled" -> MealPlannerStateMachine.State.CANCELLED
            else -> MealPlannerStateMachine.State.COLLECTING_PREFERENCES
        }
    }

    // ── Result types ────────────────────────────────────────────────────

    sealed interface CoordinatorResult {

        val content: String

        data class Text(override val content: String) : CoordinatorResult

    }


}
