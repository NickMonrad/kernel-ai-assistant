# Adding a New Learn Example

The "Learn what Jandal can do" catalogue in `ToolsLearnScreen.kt` is derived from a shared
`LearnExample` data model defined in `app/src/main/java/com/kernel/ai/navigation/LearnExample.kt`.

## Steps to add a new example

1. **Add a `LearnExample` entry** to the `allLearnExamples` list in `LearnExample.kt`.

   ```kotlin
   // For a fully routed QIR intent (no slot-fill needed):
   LearnExample(
       id = "my_new_example",           // Unique stable identifier
       title = "My Example Title",      // User-visible label
       prompt = "My example prompt",    // Text sent when selected (defaults to title)
       category = "My Category",        // Section name
       expectedMode = ExpectedLearnMode.QirIntent,      // See modes below
       expectedRoute = "my_intent",     // QIR intent name (required for QirIntent/QirSlotFill/MealPlannerHandoff)
       prefillOnly = true,              // True = opens as draft only, no auto-execution
   )

   // For a QIR intent that needs slot-fill (returns NeedsSlot):
   LearnExample(
       id = "calendar_soccer_training",
       title = "Create a calendar event for soccer training",
       category = "Time & planning",
       expectedMode = ExpectedLearnMode.QirSlotFill,
       expectedRoute = "create_calendar_event",
       expectedMissingSlot = "date",    // Required for QirSlotFill
   )
   ```

2. **Determine the correct mode** by running the prompt through `QuickIntentRouter`:

   ```
   router.route("Your example prompt")
   ```

   - Returns `RegexMatch` or `ClassifierMatch` with all slots → `QirIntent`
   - Returns `NeedsSlot` with a missing parameter → `QirSlotFill`
   - Returns `FallThrough` → `FreeformChatAllowed` (or add QIR support)

3. **Add the example to a section** in `ToolsLearnScreen.kt` using `toPrompts()`.

   `toPrompts("my_new_example")` looks up the `LearnExample` by id.

4. **Verify QIR routing** if `expectedMode` is `QirIntent` or `QirSlotFill`:
   - Run `./gradlew :core:skills:testDebugUnitTest` — the Learn examples in
     `QuickIntentRouterTest` must pass.
   - If routing doesn't work, add or fix the regex/intent pattern in `QuickIntentRouter.kt`.

5. **Run catalogue integrity tests**:
   ```bash
   ./gradlew :app:testDebugUnitTest
   ```
   These assert every example has complete metadata.

## Expected modes

| Mode | Description | expectedRoute required? | expectedMissingSlot required? |
|------|-------------|------------------------|------------------------------|
| `QirIntent` | Fully deterministic QIR route with no missing-slot follow-up. The prompt provides all required parameters. | Yes | No |
| `QirSlotFill` | Deterministic QIR route where the prompt is missing a required parameter. The app will ask a follow-up question for the missing slot. | Yes | Yes (the name of the missing slot) |
| `MealPlannerHandoff` | Intentional handoff to meal planner via `start_meal_planner`. | Yes ("start_meal_planner") | No |
| `FreeformChatAllowed` | Deliberately open-ended chat example. May produce `llm_fallthrough`. | No | No |
| `PrefillOnly` | Opens Actions as draft without auto-execution. | Usually no | No |
| `NonExecutableInfo` | Informational content only, not an actionable example. | No | No |

### Mode decision guide

| Router result | Use mode |
|---|---|
| `RegexMatch` or `ClassifierMatch` (all params filled) | `QirIntent` |
| `NeedsSlot` (a required param is missing) | `QirSlotFill` |
| `FallThrough` (no QIR route matches) | `FreeformChatAllowed` or add QIR support |
| Starts the meal planner flow | `MealPlannerHandoff` |

### `QirIntent` (fully deterministic)

- The prompt provides all parameters the intent needs.
- Example: "Set a timer for 10 minutes" → `set_timer` (both action and duration provided).
- Test asserts: `RegexMatch`/`ClassifierMatch`, route matches `expectedRoute`, not `FallThrough`, not `NeedsSlot`.

### `QirSlotFill` (deterministic + needs slot-fill)

- The prompt triggers an intent but is missing a required parameter.
- Example: "Create a calendar event for soccer training" → `create_calendar_event` with title "soccer training" but no date → `NeedsSlot(date)`.
- `expectedMissingSlot` must be set to the exact slot name returned by the router (e.g. `"date"`, `"body"`).
- Test asserts: `NeedsSlot`, route matches `expectedRoute`, missing slot name matches `expectedMissingSlot`.

## Common slot names by intent

| Intent | Slots |
|---|---|
| `create_calendar_event` | `title`, `date` |
| `send_email` | `contact`, `subject`, `body` |
| `send_sms` | `contact`, `message` |
| `make_call` | `contact` |
| `add_reminder` | `item`, `day`, `time` |
| `create_note` | `content` |
| `save_memory` | `content` |
| `save_important_date` | `label`, `date` |
| `add_to_list` | `item`, `list_name` |
| `create_list` | `list_name` |

## Test gating

- Adding an example without metadata → catalogue integrity tests fail.
- Adding a `QirIntent` example that routes to `llm_fallthrough` → QIR regression tests fail.
- Adding a `QirIntent` example that returns `NeedsSlot` → QIR regression tests fail with message suggesting `QirSlotFill`.
- Adding a `QirSlotFill` example without `expectedMissingSlot` → catalogue integrity tests fail.
- Adding a prompt already used by another example → uniqueness test fails.
