# Meal Planner Skill Design

> **Issue:** [#689](https://github.com/NickMonrad/kernel-ai-assistant/issues/689)
> **Related bug:** [#687](https://github.com/NickMonrad/kernel-ai-assistant/issues/687)
> **Current PR:** [#698](https://github.com/NickMonrad/kernel-ai-assistant/pull/698)
> **Status:** Proposed redesign
> **Last updated:** 2026-04-29

---

## 1. Executive summary

The current meal planner approach is unlikely to become reliable enough if the LLM remains the primary workflow engine.

The branch on PR #698 improves continuity by storing a conversation-scoped `MealPlanSession`, but the critical path still depends on the model to:

1. choose the correct stage,
2. call `load_skill` instead of `run_intent` / `run_js`,
3. serialize valid state parameters,
4. remember when to advance,
5. generate recipes,
6. create recipe lists,
7. add shopping items,
8. consolidate ingredients,
9. avoid stale memories and repeated questions.

That is too much procedural responsibility for the current model. The observed failures in PR comments are exactly the failures this design invites.

**Recommendation:** move meal planning to a **deterministic app-driven state machine**. Let the app own flow control, persistence, artifact creation, and list generation. Let the LLM help only with natural-language interaction and optionally with bounded content generation inside strict schemas.

---

## 2. What the current branch does well

PR #698 adds an important seam that should be kept:

- `MealPlanSessionEntity` stores conversation-scoped state (`status`, people count, days, restrictions, proteins, high-level plan, current day index)
- `MealPlanSessionRepository` gives a stable persistence layer outside prompt memory
- `ChatViewModel` injects a `[Meal Planner Session]` block and suppresses RAG during active meal-planner turns
- `QuickIntentRouter` can route "plan meals" into the flow

This is a good start for continuity. It is **not** a good final architecture for reliable meal planning.

---

## 3. Why the current approach is still fragile

### 3.1 The stage skills are mostly prompt wrappers

`meal_planner_collect`, `meal_planner_plan`, `meal_planner_recipe`, and `meal_planner_complete` are mostly instruction carriers. They do not deterministically execute the workflow. They tell the LLM what it should do next.

That means the real orchestrator is still the model.

### 3.2 The stored state is too coarse

The session currently stores only:

- preferences,
- high-level plan,
- current day index,
- stage.

It does **not** persist the actual structured artifacts that matter:

- per-day recipe title,
- per-day ingredient list,
- per-day method steps,
- shopping-list line items,
- consolidated grocery totals,
- which artifacts have already been written to Android lists,
- whether a given day is drafted, approved, regenerated, or saved.

So even with session persistence, the hardest work is still left to prompt continuity.

### 3.3 The workflow asks the model to behave like a transaction coordinator

The current design expects the model to correctly sequence:

1. collect preferences,
2. save state,
3. generate high-level plan,
4. save state,
5. generate recipe,
6. write shopping items,
7. write recipe method steps,
8. advance state,
9. repeat for the next day,
10. complete the session.

This is exactly the kind of long, stateful, tool-heavy workflow the model is weakest at.

### 3.4 Reserved-name interception is only a soft guardrail

The branch tries to recover when the model calls `run_intent` or `run_js` with a skill name by returning instructions instead of failing hard. That is useful, but it still relies on the model to read that correction and recover.

The PR comments show that recovery is inconsistent.

### 3.5 The failure modes in PR comments are structural, not incidental

The PR feedback shows repeated failures of the same kinds:

- calling `run_intent` with skill names instead of `load_skill`
- calling `run_js` for non-JS meal planner stages
- using placeholder conversation IDs and invalid enum values
- leaking stale preferences from memory instead of current user input
- skipping protein collection
- failing to create recipe lists or shopping items
- hanging after "Yes" / "Continue"
- generating malformed quantities and repeated text

These are not one bug. They are signs that the model currently owns too much control.

---

## 4. Why Wikipedia works better

`query_wikipedia` is a good example of a skill shape that fits the current tool architecture:

- one user intent,
- one focused contract,
- one required parameter (`query`),
- one tool execution,
- one grounded result,
- no long-lived workflow state,
- no multi-turn artifact creation.

Meal planning is the opposite:

- multi-turn,
- stateful,
- partially transactional,
- artifact-producing,
- requires deterministic progression,
- requires consolidation and validation.

So "Wikipedia works" does **not** imply "meal planning can be made reliable with more prompt instructions."

---

## 5. Proposed architecture

## 5.1 Core principle

**Meal planning should be an app-owned workflow, not an LLM-owned workflow.**

The app should intercept meal-planner turns and route them through a dedicated coordinator/state machine. The LLM should only:

- help interpret flexible user language,
- ask or answer small conversational questions,
- optionally generate plan/recipe content in a strict JSON schema,
- never own progression, persistence, or list writing.

---

## 5.2 New components

### A. `MealPlannerCoordinator`

Single deterministic entry point for all meal-planner turns.

Responsibilities:

- decide whether a message belongs to an active meal-planner session
- interpret the message in the context of the active stage
- update structured state
- decide the next prompt / chips / action
- call the content generator when needed
- persist artifacts
- trigger Android list creation

### B. `MealPlannerStateMachine`

Explicit state machine, for example:

- `COLLECTING_PREFERENCES`
- `PLAN_DRAFT_READY`
- `PLAN_CONFIRMED`
- `GENERATING_RECIPE`
- `RECIPE_REVIEW`
- `WRITING_ARTIFACTS`
- `COMPLETED`
- `CANCELLED`

The state machine, not the LLM, decides transitions.

### C. Structured storage

Keep `MealPlanSessionEntity`, but add durable structured entities such as:

- `MealPlanDayEntity`
  - `conversationId`
  - `dayIndex`
  - `dishName`
  - `status`
- `MealRecipeEntity`
  - `conversationId`
  - `dayIndex`
  - `title`
  - `ingredientsJson`
  - `methodStepsJson`
- `MealGroceryItemEntity`
  - `conversationId`
  - `normalizedName`
  - `displayName`
  - `unit`
  - `quantity`
  - `sourceDayIndex`
- optional `MealPlanListWriteEntity`
  - track whether the recipe list and shopping items were already written

This is still narrower than the full generic artifact system in #235, but it solves the actual meal-planner problem.

### D. `MealPlannerContentGenerator`

Small interface with implementations such as:

- `LlmMealPlannerGenerator`
- `TemplateMealPlannerGenerator`

The coordinator can request:

- high-level meal plan draft
- single recipe draft for one day
- recipe revision for a specific day

The generator returns **strict structured JSON**, not free text.

### E. `IngredientNormalizer`

App-side utility to:

- normalize names (`capsicum` vs `bell pepper` policy, singular/plural handling)
- parse quantities and units
- reject garbage quantities
- consolidate grocery totals across days

This must be code-driven, not left to the LLM.

---

## 6. Pathways

## 6.1 Quick actions / direct meal-planner entry

For messages like:

- "plan meals"
- "make a meal plan"
- "plan dinners for 3 days"

`QuickIntentRouter` should route into a dedicated **meal planner entry action**, not `load_skill`.

Recommended route target:

- `start_meal_planner`

That action should:

1. create or reset the meal-planner session,
2. enter `COLLECTING_PREFERENCES`,
3. return the first deterministic question or chips.

Do **not** ask the LLM to start the workflow by loading a long skill prompt.

## 6.2 Conversation pathway

While a meal-planner session is active, `ChatViewModel` should check the coordinator **before** normal LLM chat.

Flow:

1. active meal-planner session exists,
2. user sends message,
3. coordinator interprets it against the current state,
4. coordinator either:
   - updates structured state and replies directly, or
   - asks the LLM a bounded content-generation question and validates the result.

The user should experience a conversation, but the app should own the state.

## 6.3 Hybrid conversational moments

The LLM is still useful for side conversation:

- "Can we make day 2 cheaper?"
- "Swap pork for chicken"
- "Can day 1 be more kid-friendly?"

But the coordinator should translate those into deterministic mutations:

- regenerate day 2 only,
- replace a protein constraint,
- lower complexity level,
- recalculate grocery totals,
- rewrite the affected list artifacts.

---

## 7. Proposed deterministic flow

### Stage 1: collect preferences

The coordinator collects and stores:

- people count
- days
- dietary restrictions
- protein preferences
- optional constraints:
  - budget
  - complexity / time
  - cuisine preferences
  - dislikes

If the initial user message already contains enough information, skip follow-up questions.

### Stage 2: generate high-level plan

The coordinator requests a structured plan from the content generator, for example:

```json
{
  "days": [
    { "dayIndex": 0, "dishName": "Chicken stir-fry", "protein": "chicken" },
    { "dayIndex": 1, "dishName": "Beef mince patties", "protein": "beef" },
    { "dayIndex": 2, "dishName": "Vegetable frittata", "protein": "eggs" }
  ]
}
```

The app stores it and presents it to the user. Approval is tracked explicitly.

### Stage 3: generate a recipe for one day

Generate **one day at a time** into strict structure:

```json
{
  "title": "Chicken stir-fry",
  "serves": 4,
  "ingredients": [
    { "name": "chicken breast", "quantity": 600, "unit": "g" },
    { "name": "broccoli", "quantity": 1, "unit": "head" }
  ],
  "methodSteps": [
    "Slice the chicken into bite-sized pieces.",
    "Heat oil in a large frying pan over medium-high heat."
  ]
}
```

The app validates:

- units
- quantity ranges
- required fields
- step count
- no empty strings
- no clearly corrupted numeric output

If invalid, retry generation or fall back to a template path.

### Stage 4: persist recipe artifacts

Once valid structured recipe data exists, the app should:

1. save recipe data locally,
2. create or replace the recipe-specific list,
3. write method steps as list items,
4. merge the ingredients into the grocery accumulator,
5. update grocery totals.

These writes should be app-driven, not LLM-driven.

### Stage 5: finalise grocery list

After all days are approved/generated:

1. consolidate normalized ingredients,
2. create or update the shopping list,
3. present a summary to the user,
4. mark the plan completed.

---

## 8. The LLM's new role

The LLM should be limited to two jobs.

### 8.1 Conversation helper

- interpret free-form replies
- paraphrase the app's deterministic state into natural language
- explain options and revisions

### 8.2 Bounded content generator

- propose a high-level plan in strict JSON
- propose a single recipe in strict JSON
- optionally revise one recipe in strict JSON

It should **not**:

- decide stage transitions,
- choose which internal tool to load,
- manage session advancement,
- write Android lists directly,
- consolidate grocery totals,
- decide whether state was already saved.

---

## 9. Why this is safer

This design removes the hardest failure classes:

- no more `load_skill` vs `run_intent` confusion on the critical path
- no more placeholder `conversation_id` values generated by the model
- no more invalid enum names controlling flow
- no more recipe/list creation depending on the model remembering a sequence of tool calls
- no more grocery consolidation entrusted to prompt obedience
- no more accidental re-entry into the wrong stage after "Yes", "Ok", or "Continue"

The LLM can still fail at content generation, but those failures become bounded and recoverable.

---

## 10. Suggested implementation plan

### Phase A — Replace prompt orchestration

1. Keep `MealPlanSessionRepository`.
2. Add `MealPlannerCoordinator` and `MealPlannerStateMachine`.
3. Change QIR meal-planner routing from `load_skill` to `start_meal_planner`.
4. Intercept active meal-planner turns before normal LLM chat.

### Phase B — Add structured artifact storage

1. Add day/recipe/grocery entities.
2. Persist one day at a time.
3. Track whether lists were written already.

### Phase C — Bound LLM generation

1. Replace text-style meal-planner stage skills with internal generation requests.
2. Require strict JSON outputs for plan and recipe generation.
3. Add validator + retry path.

### Phase D — Deterministic list writing

1. Write recipe method steps from app code.
2. Write shopping list from consolidated grocery data.
3. Support regenerate-day and rewrite-artifacts flows.

### Phase E — UX improvements

1. Add chips for common answers.
2. Add visible progress ("Preferences", "Plan", "Day 1", "Shopping list").
3. Add "swap meal", "regenerate day", and "finish later" actions.

---

## 11. Scope boundaries

### In scope for the redesign

- deterministic session state
- deterministic stage transitions
- structured recipe and grocery persistence
- app-driven list creation
- hybrid quick-action + conversation handling

### Out of scope for the first redesign pass

- full generic artifact/document system from #235
- nutrition analysis
- cost estimation
- calendar scheduling
- cloud recipe sources

---

## 12. Recommendation

Do **not** continue scaling the current prompt-driven stage-skill design as the primary meal-planner architecture.

Keep the session-store work from PR #698, but use it as the persistence foundation for a **deterministic meal-planner coordinator**. Treat the LLM as a helper, not the workflow engine.

That gives the app a realistic path to:

- reliable quick-action entry,
- reliable continuation from chat,
- stable saved state,
- per-day recipe artifacts,
- consolidated grocery lists,
- graceful fallback when the model is weak or inconsistent.
