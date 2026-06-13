package com.kernel.ai.navigation

/**
 * Expected routing mode for a [LearnExample] in the "Learn what Jandal can do" catalogue.
 *
 * Every Learn example must declare one of these modes so the test harness can assert
 * correct routing behaviour and detect regressions.
 */
enum class ExpectedLearnMode {
    /**
     * Routes through the Quick Intent Router deterministic regex/classifier.
     * Must NOT produce "llm_fallthrough".
     */
    QirIntent,

    /**
     * Routes through QIR but triggers a slot-fill flow because required fields
     * are missing from the prompt (e.g. "Create a calendar event" without a title).
     */
    QirSlotFill,

    /**
     * Intentional handoff to the meal planner via QIR's "start_meal_planner" route.
     * Must NOT be generic "llm_fallthrough".
     */
    MealPlannerHandoff,

    /**
     * Deliberately open-ended chat example outside the deterministic action scope.
     * May produce "llm_fallthrough" but only because the metadata explicitly allows it.
     */
    FreeformChatAllowed,

    /**
     * Opens Actions with the prompt text pre-filled but does NOT auto-execute.
     * Examples that require user context or confirmation before execution.
     */
    PrefillOnly,

    /**
     * Non-executable educational item — displayed as informational content only.
     * Not expected to route or trigger any action.
     */
    NonExecutableInfo,
}

/**
 * A single example in the "Learn what Jandal can do" catalogue.
 *
 * This model is the shared source of truth for both the UI and the test harness.
 * Tests enumerate the actual [allLearnExamples] list rather than maintaining a
 * separate hard-coded list that can drift.
 *
 * @property id Stable unique identifier (used for test tags and evidence keys).
 * @property title User-visible label shown on the Learn screen.
 * @property prompt The text sent to the app when the example is selected.
 *   Defaults to [title] when not specified.
 * @property category Section/category name.
 * @property expectedMode How this example should be routed (see [ExpectedLearnMode]).
 * @property expectedRoute Expected QIR intent name (e.g. "create_calendar_event").
 *   Required for [QirIntent] and [QirSlotFill]; null for others.
 * @property prefillOnly When true, selecting this example opens Actions as a draft
 *   without auto-execution. Defaults to true for safety.
 */
data class LearnExample(
    val id: String,
    val title: String,
    val prompt: String = title,
    val category: String,
    val expectedMode: ExpectedLearnMode,
    val expectedRoute: String? = null,
    val prefillOnly: Boolean = true,
)

/**
 * All Learn examples — single source of truth.
 *
 * To add a new example:
 * 1. Add a [LearnExample] entry in the appropriate section below.
 * 2. Ensure [id] is unique, [title] and [category] are non-empty.
 * 3. Set [expectedMode] and [expectedRoute] according to the actual behaviour.
 * 4. If the example should route through QIR, verify the routing works first
 *    and add a corresponding test phrase to the QIR test data.
 * 5. Run the catalogue integrity tests — they will fail if metadata is missing.
 */
val allLearnExamples: List<LearnExample> = listOf(
    // ── Lists ──────────────────────────────────────────────────────────────────
    LearnExample("lists_add_milk", "Add milk to my shopping list", category = "Lists",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "add_to_list"),
    LearnExample("lists_chuck_bananas", "Chuck bananas on the grocery list", category = "Lists",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "add_to_list"),
    LearnExample("lists_create", "Create a packing list", category = "Lists",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "create_list"),
    LearnExample("lists_show", "Show my shopping list", category = "Lists",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "get_list_items"),
    LearnExample("lists_remove", "Remove milk from my shopping list", category = "Lists",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "remove_from_list"),
    LearnExample("lists_bulk", "Add bread, eggs, and cheese to my grocery list", category = "Lists",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "add_to_list"),

    // ── Meal planning ──────────────────────────────────────────────────────────
    LearnExample("meal_plan_meals", "Plan meals", category = "Meal planning",
        expectedMode = ExpectedLearnMode.MealPlannerHandoff, expectedRoute = "start_meal_planner"),
    LearnExample("meal_plan_dinners_week", "Plan dinners for this week", category = "Meal planning",
        expectedMode = ExpectedLearnMode.MealPlannerHandoff, expectedRoute = "start_meal_planner"),
    LearnExample("meal_plan_shopping_list", "Make a shopping list for my meal plan", category = "Meal planning",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "create_list"),
    LearnExample("meal_plan_grocery", "Create a grocery list from my meal plan", category = "Meal planning",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "create_list"),
    LearnExample("meal_plan_lunches", "Plan school lunches for the week", category = "Meal planning",
        expectedMode = ExpectedLearnMode.FreeformChatAllowed),
    LearnExample("meal_plan_family", "Suggest family dinners with chicken and rice", category = "Meal planning",
        expectedMode = ExpectedLearnMode.FreeformChatAllowed),
    LearnExample("meal_plan_5day", "Make a 5-day meal plan for two adults and two kids", category = "Meal planning",
        expectedMode = ExpectedLearnMode.FreeformChatAllowed),
    LearnExample("meal_plan_vegetarian", "Plan vegetarian dinners for next week", category = "Meal planning",
        expectedMode = ExpectedLearnMode.FreeformChatAllowed),
    LearnExample("meal_plan_pantry", "Suggest meals using what I already have", category = "Meal planning",
        expectedMode = ExpectedLearnMode.FreeformChatAllowed),

    // ── Notes & memory ─────────────────────────────────────────────────────────
    LearnExample("notes_note_to_self", "Note to self: the garage code is 1234", category = "Notes & memory",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "create_note"),
    LearnExample("memory_dark_mode", "Remember that I prefer dark mode", category = "Notes & memory",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "save_memory"),
    LearnExample("notes_show", "Show my notes", category = "Notes & memory",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "list_notes"),
    LearnExample("memory_coffee", "Remember that my favourite coffee is a flat white", category = "Notes & memory",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "save_memory"),
    LearnExample("notes_freyja", "Write down that Freyja needs library books tomorrow", category = "Notes & memory",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "create_note"),
    LearnExample("notes_packing", "Create a note about packing for the school trip", category = "Notes & memory",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "create_note"),

    // ── Time & planning ────────────────────────────────────────────────────────
    LearnExample("time_timer_10", "Set a timer for 10 minutes", category = "Time & planning",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "set_timer"),
    LearnExample("calendar_soccer_training", "Create a calendar event for soccer training", category = "Time & planning",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "create_calendar_event"),
    LearnExample("calendar_dentist", "Add dentist appointment next Tuesday", category = "Time & planning",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "create_calendar_event"),
    LearnExample("calendar_assembly", "Schedule school assembly for Friday", category = "Time & planning",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "create_calendar_event"),
    LearnExample("reminder_bins", "Remind me to put the bins out tomorrow at 7pm", category = "Time & planning",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "add_reminder"),
    LearnExample("date_diff_christmas", "How many days until Christmas?", category = "Time & planning",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "get_date_diff"),
    LearnExample("alarm_7am", "Set an alarm for 7am tomorrow", category = "Time & planning",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "set_alarm"),
    LearnExample("date_birthday", "Save Mum's birthday as 12 March", category = "Time & planning",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "save_important_date"),
    LearnExample("date_diff_july", "How many weeks until 1 July?", category = "Time & planning",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "get_date_diff"),

    // ── Weather ────────────────────────────────────────────────────────────────
    LearnExample("weather_current", "What's the weather?", category = "Weather",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "get_weather"),
    LearnExample("weather_5_day_forecast", "What's the 5 day forecast?", category = "Weather",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "get_weather"),
    LearnExample("weather_wellington", "What's the weather like in Wellington?", category = "Weather",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "get_weather"),
    LearnExample("weather_rain_today", "Will it rain today?", category = "Weather",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "get_weather"),
    LearnExample("weather_tomorrow", "What's the weather tomorrow?", category = "Weather",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "get_weather"),
    LearnExample("weather_bundaberg", "What's the forecast for Bundaberg this weekend?", category = "Weather",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "get_weather"),
    LearnExample("weather_umbrella", "Do I need an umbrella today?", category = "Weather",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "get_weather"),
    LearnExample("weather_hot_tomorrow", "How hot will it be tomorrow?", category = "Weather",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "get_weather"),

    // ── People & communication ─────────────────────────────────────────────────
    LearnExample("people_email_alex", "Email Alex about the meeting", category = "People & communication",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "send_email"),
    LearnExample("people_text_running_late", "Text Sarah that I'm running late", category = "People & communication",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "send_sms"),
    LearnExample("people_email_school", "Email school that Freyja is sick today", category = "People & communication",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "send_email"),
    LearnExample("people_text_dad", "Text Dad that I'm on my way", category = "People & communication",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "send_sms"),
    LearnExample("people_call_mum", "Call Mum", category = "People & communication",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "make_call"),

    // ── Device & media ─────────────────────────────────────────────────────────
    LearnExample("device_flashlight_on", "Turn on the flashlight", category = "Device & media",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "toggle_flashlight_on"),
    LearnExample("media_play_music", "Play music", category = "Device & media",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "play_media"),
    LearnExample("device_flashlight_off", "Turn off the flashlight", category = "Device & media",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "toggle_flashlight_off"),
    LearnExample("media_next_track", "Play the next track", category = "Device & media",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "next_track"),
    LearnExample("media_pause", "Pause the music", category = "Device & media",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "pause_media"),
    LearnExample("media_youtube_bluey", "Play Bluey on YouTube", category = "Device & media",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "play_youtube"),
    LearnExample("media_youtube_music", "Play relaxing music on YouTube Music", category = "Device & media",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "play_youtube_music"),
    LearnExample("media_open_spotify", "Open Spotify", category = "Device & media",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "open_app"),
    LearnExample("media_workout_playlist", "Play my workout playlist", category = "Device & media",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "play_media_playlist"),

    // ── Maps & places ──────────────────────────────────────────────────────────
    LearnExample("maps_navigate_school", "Navigate to school", category = "Maps & places",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "navigate_to"),
    LearnExample("maps_find_coffee", "Find coffee near me", category = "Maps & places",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "find_nearby"),
    LearnExample("maps_navigate_home", "Navigate home", category = "Maps & places",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "navigate_to"),
    LearnExample("maps_find_petrol", "Find petrol stations nearby", category = "Maps & places",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "find_nearby"),
    LearnExample("maps_find_bunnings", "Find Bunnings on the map", category = "Maps & places",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "find_nearby"),
    LearnExample("maps_navigate_pharmacy", "Navigate to the nearest pharmacy", category = "Maps & places",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "navigate_to"),
    LearnExample("maps_find_parks", "Find parks near me", category = "Maps & places",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "find_nearby"),
    LearnExample("maps_find_supermarket", "Find the closest supermarket", category = "Maps & places",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "find_nearby"),

    // ── Utilities & conversions ────────────────────────────────────────────────
    LearnExample("convert_cups_ml", "Convert 2 cups to mL", category = "Utilities & conversions",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "convert_units"),
    LearnExample("convert_flour_grams", "Convert 1 cup of flour to grams", category = "Utilities & conversions",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "convert_cooking_measure"),
    LearnExample("convert_butter", "Convert 200 grams of butter to cups", category = "Utilities & conversions",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "convert_cooking_measure"),
    LearnExample("convert_currency_aud_nzd", "Convert 20 Australian dollars to New Zealand dollars",
        category = "Utilities & conversions",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "convert_currency"),
    LearnExample("convert_percentage", "What's 15% of 87?", category = "Utilities & conversions",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "calculate_arithmetic"),
    LearnExample("convert_distance", "Convert 5 kilometres to miles", category = "Utilities & conversions",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "convert_units"),
    LearnExample("convert_currency_usd_aud", "How much is 50 USD in AUD?", category = "Utilities & conversions",
        expectedMode = ExpectedLearnMode.QirIntent, expectedRoute = "convert_currency"),
)

/** Map from LearnExample id to [LearnExample] for fast lookup. */
val learnExamplesById: Map<String, LearnExample> = allLearnExamples.associateBy { it.id }
