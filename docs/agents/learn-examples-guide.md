# Adding a New Learn Example

The "Learn what Jandal can do" catalogue in `ToolsLearnScreen.kt` is derived from a shared
`LearnExample` data model defined in `app/src/main/java/com/kernel/ai/navigation/LearnExample.kt`.

## Steps to add a new example

1. **Add a `LearnExample` entry** to the `allLearnExamples` list in `LearnExample.kt`.
   
   ```kotlin
   LearnExample(
       id = "my_new_example",           // Unique stable identifier
       title = "My Example Title",      // User-visible label
       prompt = "My example prompt",    // Text sent when selected (defaults to title)
       category = "My Category",        // Section name
       expectedMode = ExpectedLearnMode.QirIntent,  // See modes below
       expectedRoute = "my_intent",     // QIR intent name (required for QirIntent/QirSlotFill/MealPlannerHandoff)
       prefillOnly = true,              // True = opens as draft only, no auto-execution
   )
   ```

2. **Add the example to a section** in `ToolsLearnScreen.kt` using `toPrompts()`.
   
   `toPrompts("my_new_example")` looks up the `LearnExample` by id.

3. **Verify QIR routing** if `expectedMode` is `QirIntent` or `QirSlotFill`:
   - Run `./gradlew :core:skills:testDebugUnitTest` — the Learn examples in
     `QuickIntentRouterTest` must pass.
   - If routing doesn't work, add or fix the regex/intent pattern in `QuickIntentRouter.kt`.

4. **Run catalogue integrity tests**:
   ```bash
   ./gradlew :app:testDebugUnitTest
   ```
   These assert every example has complete metadata.

## Expected modes

| Mode | Description | expectedRoute required? |
|------|-------------|------------------------|
| `QirIntent` | Routes through QIR (deterministic). Must not llm_fallthrough. | Yes |
| `QirSlotFill` | Routes through QIR but triggers slot-fill flow. | Yes |
| `MealPlannerHandoff` | Intentional handoff to meal planner via start_meal_planner. | Yes ("start_meal_planner") |
| `FreeformChatAllowed` | Deliberately open-ended chat example. May use llm_fallthrough. | No |
| `PrefillOnly` | Opens Actions as draft without auto-execution. | Usually no |
| `NonExecutableInfo` | Informational content only, not an actionable example. | No |

## Test gating

- Adding an example without metadata → catalogue integrity tests fail.
- Adding a `QirIntent` example that routes to `llm_fallthrough` → QIR regression tests fail.
- Adding a prompt already used by another example → uniqueness test fails.
