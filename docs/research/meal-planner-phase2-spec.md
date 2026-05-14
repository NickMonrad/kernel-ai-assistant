# Meal Planner Phase 2 — Progressive Reveal and Plan Approval Specification
> **Issue:** [#869](https://github.com/NickMonrad/kernel-ai-assistant/issues/869)
> **Depends on:** [#859](https://github.com/NickMonrad/kernel-ai-assistant/issues/859), PR #864
> **Status:** Draft for product/UX review
> **Last updated:** 2026-05-14

---

## 1. Purpose

Phase 2 should improve the user experience of the deterministic meal planner without reopening the prompt-heavy architecture that `#859` replaced.

The merged v1 foundation is correct but still too monolithic for longer plans:
- the user gives the final required slots and then waits through one long generation run
- recipes are revealed only after the full run completes
- the current foreground experience is fragile if the user backgrounds the app or turns the screen off
- the user spends expensive recipe-generation time on meals they may want to reject at the high-level plan stage

This phase should keep the app-owned coordinator, bounded JSON contracts, and deterministic persistence model, but change the UX so the expensive work is deferred until the user approves the proposed plan and then revealed incrementally.

---

## 2. Current v1 behaviour

Current implementation anchors:
- `core/skills/src/main/java/com/kernel/ai/core/skills/mealplan/MealPlannerCoordinator.kt`
- `core/memory/src/main/java/com/kernel/ai/core/memory/mealplan/MealPlanModels.kt`
- `core/memory/src/main/java/com/kernel/ai/core/memory/repository/MealPlanSessionRepository.kt`

Current state/status seams already available in the repo:
- session statuses: `COLLECTING_REQUIRED_SLOTS`, `PLAN_REVIEW`, `RECIPES_IN_PROGRESS`, `AWAITING_USER_EDIT_OR_RECOVERY`, `COMPLETED`, `CANCELLED`
- day statuses: `PENDING`, `DRAFTED`, `PERSISTED`, `FAILED`
- generation kinds: `PLAN`, `RECIPE`

Today the planner already has a useful architectural seam:
1. collect required slots deterministically
2. generate the high-level plan in one bounded step
3. persist plan days in Room
4. generate recipes one day at a time
5. persist each day's recipe and shopping projection
6. allow `replace day N`, `regenerate day N`, and `done meal planning`

The main behavioural gap is that after saving the high-level plan, `MealPlannerCoordinator.generatePlanAndPendingRecipes()` immediately continues into `generatePendingRecipesFrom(...)` instead of pausing for user approval.

---

## 3. Recommended flow for Phase 2

### 3.1 Recommended product decision

**Recommendation:** insert a single **plan-approval checkpoint** after the high-level plan is generated, then generate recipes sequentially with progressive reveal.

This is the preferred tradeoff over both alternatives:
- **Worse option:** generate all recipes immediately, then let the user replace disliked ones afterward
- **Also worse option:** ask the user to accept/replace each recipe one by one during generation

Why the checkpoint is the best tradeoff:
- high-level plan generation is cheap relative to full recipe generation
- full recipe generation is the expensive step on-device
- one approval turn is much lower-friction than per-recipe confirmation loops
- the user can reject or replace a meal before paying the full recipe-generation cost

### 3.2 Target user flow

1. User starts meal planning and fills required slots.
2. App generates **high-level plan only**.
3. App presents the plan summary and pauses in an approval state.
4. User chooses one of:
   - `generate recipes`
   - `replace day 2`
   - `change preferences`
   - `cancel`
5. After approval, app generates recipes sequentially.
6. Each recipe appears as soon as that day is persisted.
7. User sees explicit progress throughout the run.
8. When all days are persisted, planner moves to the existing finalize/edit state.

### 3.3 Example conversation

```text
User: Plan my meals
Assistant: I need a few details before I build the plan:
- How many people are you cooking for?
- How many days do you want to plan for?
- Any dietary requirements?
- What protein preferences should I use?

User: 4 people, 5 days, low lactose, chicken and beef

Assistant:
Here’s a 5-day meal plan for 4 people (low lactose):
- Day 1: Lemon herb roasted chicken with veg
- Day 2: Beef and broccoli stir-fry
- Day 3: Chicken meatballs with rice
- Day 4: Beef mince pasta bake
- Day 5: Roast chicken wraps

Say `generate recipes`, `replace day 3`, `change preferences`, or `cancel`.

User: Generate recipes

Assistant: Generating recipes 1 of 5…
Assistant: Day 1: Lemon herb roasted chicken with veg
...
Assistant: Generating recipes 2 of 5…
Assistant: Day 2: Beef and broccoli stir-fry
...
```

---

## 4. Proposed state machine

### 4.1 Session states

Recommended session-level states for Phase 2:
- `COLLECTING_REQUIRED_SLOTS`
- `PLAN_REVIEW`
- `RECIPES_IN_PROGRESS`
- `AWAITING_USER_EDIT_OR_RECOVERY`
- `COMPLETED`
- `CANCELLED`

No new persistent state enum is strictly required if `PLAN_REVIEW` is repurposed as the true approval checkpoint instead of a transient internal seam.

### 4.2 Locked behaviour per state

| State | User-visible meaning | Allowed actions |
|---|---|---|
| `COLLECTING_REQUIRED_SLOTS` | Planner is still gathering required inputs | answer slots, cancel |
| `PLAN_REVIEW` | High-level days are drafted; recipes have not started yet | generate recipes, replace day N, change preferences, cancel |
| `RECIPES_IN_PROGRESS` | Recipes are actively generating sequentially | passive progress updates, cancel, resume after interruption |
| `AWAITING_USER_EDIT_OR_RECOVERY` | All recipes done, or one/more days failed and need intervention | replace day N, regenerate day N, done meal planning |
| `COMPLETED` | Session finalized | start a new plan |
| `CANCELLED` | Session abandoned | start or resume a new plan |

### 4.3 Important transition change from v1

Current v1 does this:
- collect slots -> generate plan -> immediately generate all pending recipes

Phase 2 should do this instead:
- collect slots -> generate plan -> save days -> enter `PLAN_REVIEW` -> wait for explicit approval -> generate recipes sequentially

---

## 5. UX model

### 5.1 Plan review step

After the plan draft is persisted, the user should always see the plan summary before recipe generation begins.

Recommended prompt:
- `Say 'generate recipes', 'replace day 2', 'change preferences', or 'cancel'.`

Recommended semantics:
- `generate recipes` begins the recipe loop from Day 1 or the next pending day
- `replace day N` rewrites only the selected draft day while still in high-level-plan mode
- `change preferences` returns to slot collection while preserving the active session and already-known values where possible
- `cancel` abandons the session

### 5.2 Progressive reveal

During recipe generation:
- show progress before each day starts
- reveal the persisted recipe immediately after each day finishes
- keep day order deterministic
- do not hold all completed recipes until the full run ends

### 5.3 Progress language

Recommended copy shape:
- `Generating recipes 2 of 5…`
- `Still working on Day 3…`
- after resume: `Resuming your meal plan at Day 3 of 5…`

The UI should never leave the user in a dead-air state after the approval step.

---

## 6. Backgrounding and resume model

### 6.1 Goal

The generation loop should survive normal interruption well enough that the user does not need to keep the chat screen open and awake the whole time.

### 6.2 Recommended direction

Treat multi-day recipe generation as **foreground-visible resumable work**, not as a fragile in-screen coroutine chain.

Design goal:
- planner state remains authoritative in Room
- the execution loop can resume from the next non-persisted day
- the chat screen becomes a consumer of session state, not the sole owner of the long-running recipe loop

### 6.3 Resume rule

Resume should always derive from persisted state:
- find next day with `status != PERSISTED`
- resume from that day in deterministic order
- never regenerate already-persisted days unless the user explicitly asks to regenerate or replace them

### 6.4 Failure rule

If a day fails:
- mark that day `FAILED`
- stop automatic progression
- surface recovery actions for that day only
- do not continue to later days while an earlier day is unresolved

---

## 7. Projections and list-writing

### 7.1 Recommended behaviour

Keep canonical planner data in planner-owned Room tables exactly as in `#859`.

Recommended for Phase 2:
- persist each recipe day as it completes
- write/update the relevant meal-plan projections incrementally after each successful day
- preserve the existing finalization step for the final episodic summary and end-of-session closure

Why:
- if the app is interrupted after Day 2 of 5, the user should still retain the first two recipes and their accumulated shopping data
- incremental persistence is already aligned with the existing architecture

### 7.2 Non-goal

Phase 2 should not broaden into the full artifact-platform work from `#235`.

---

## 8. Cancellation, editing, and control policy

### 8.1 During `PLAN_REVIEW`

Allowed:
- approve recipe generation
- replace a draft day
- revise preferences
- cancel

Not allowed:
- recipe-level regenerate commands, because no recipe exists yet

### 8.2 During `RECIPES_IN_PROGRESS`

Recommended default:
- generation is not interactively editable mid-flight except for `cancel`
- after cancellation or failure, the next user turn can resume or recover deterministically

This avoids complicated races such as replacing Day 2 while Day 3 is already generating.

### 8.3 After all recipes complete

Keep the current post-generation controls:
- `replace day N`
- `regenerate day N`
- `done meal planning`

---

## 9. Why not per-recipe confirmation?

Per-recipe confirmation sounds attractive but is the wrong next step for this product slice.

Costs:
- too many mandatory turns
- higher friction for 5-day plans
- slower completion for users who already like the proposed plan
- more state complexity with little additional correctness benefit

The approval checkpoint should exist at the **high-level plan** boundary, because that is the moment where the user can still cheaply reject expensive downstream work.

---

## 10. Implementation notes for the future coding phase

This document does not prescribe exact code changes yet, but the intended seam is clear:
- stop `generatePlanAndPendingRecipes()` from auto-continuing into the recipe loop
- make `PLAN_REVIEW` a real user-visible pause state
- start recipe generation only from an explicit approval command
- emit incremental progress/reveal events as each day persists
- move the long-running recipe loop behind a lifecycle-safe execution seam
- keep repository state as the source of truth for resume

---

## 11. Explicit exclusions

Phase 2 does **not** include:
- recipe datasource grounding / regional produce (`#43`)
- the broader artifact/document platform (`#235`)
- direct rich editing UI for recipe ingredients or steps
- reopening prompt-driven workflow ownership
- per-recipe mandatory accept/reject loops

---

## 12. Recommended defaults needing owner confirmation

These are the product decisions that should be confirmed before implementation starts:

1. **Approval checkpoint default**
   - recommended: require explicit `generate recipes` approval after the high-level plan is shown

2. **Preference editing model**
   - recommended: `change preferences` returns to slot collection and keeps already captured values editable instead of starting a brand-new session

3. **During-generation controls**
   - recommended: allow `cancel` only while recipes are actively generating; defer `replace day N` / `regenerate day N` until the current loop stops or completes

4. **Projection timing**
   - recommended: update meal-plan projections incrementally after each successful day, not only at finalization

5. **Background execution posture**
   - recommended: design the recipe loop as resumable foreground-visible work rather than a chat-screen-only coroutine path
