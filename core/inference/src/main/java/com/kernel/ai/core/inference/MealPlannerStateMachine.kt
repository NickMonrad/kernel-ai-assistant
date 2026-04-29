package com.kernel.ai.core.inference

/**
 * Deterministic state machine for the meal-planner workflow.
 *
 * The app owns flow control — the LLM never decides stage transitions.
 * Each state maps to a single [action] that the coordinator executes.
 */
object MealPlannerStateMachine {

    enum class State(val label: String, val statusString: String) {
        COLLECTING_PREFERENCES("Collecting preferences", "collecting_preferences"),
        PLAN_DRAFT_READY("High-level plan ready", "high_level_plan_ready"),
        GENERATING_RECIPE("Generating recipes", "generating_recipes"),
        RECIPE_REVIEW("Reviewing recipes", "recipe_review"),
        WRITING_ARTIFACTS("Writing artifacts", "writing_artifacts"),
        COMPLETED("Completed", "completed"),
        CANCELLED("Cancelled", "cancelled"),
    }

    /**
     * Valid transitions from the current state.
     * The coordinator checks this before applying any transition.
     */
    fun validTransitions(current: State): List<State> = when (current) {

        State.COLLECTING_PREFERENCES -> listOf(State.PLAN_DRAFT_READY, State.CANCELLED)

        State.PLAN_DRAFT_READY -> listOf(State.GENERATING_RECIPE, State.CANCELLED)
        State.GENERATING_RECIPE -> listOf(State.RECIPE_REVIEW, State.CANCELLED)

        State.RECIPE_REVIEW -> listOf(State.GENERATING_RECIPE, State.WRITING_ARTIFACTS, State.CANCELLED)

        State.WRITING_ARTIFACTS -> listOf(State.COMPLETED, State.CANCELLED)

        State.COMPLETED, State.CANCELLED -> emptyList()
    }

    /**
     * Returns the state-specific skill name the LLM should load for this state.
     * Only used for states where the LLM provides content (not for app-driven states).
     */
    fun stateSkillName(state: State): String? = when (state) {
        State.COLLECTING_PREFERENCES -> "meal_planner_collect"
        State.PLAN_DRAFT_READY -> "meal_planner_plan"
        State.GENERATING_RECIPE, State.RECIPE_REVIEW,
            State.WRITING_ARTIFACTS, State.COMPLETED, State.CANCELLED -> null
    }

    fun isTerminal(state: State): Boolean = state == State.COMPLETED || state == State.CANCELLED
}