# Meal Planner Salvage List

> Preserved from abandoned PR [#698](https://github.com/NickMonrad/kernel-ai-assistant/pull/698)
> Related issue: [#689](https://github.com/NickMonrad/kernel-ai-assistant/issues/689)
> Companion design note: [`meal-planner-skill-design.md`](./meal-planner-skill-design.md)

## Decision

PR #698 should be treated as abandoned as a runtime implementation.

The LLM-driven coordinator, stage-skill orchestration, prompt-heavy routing, and list-writing flow are not reliable enough to merge. The value worth preserving is narrower:

1. the redesign analysis in `meal-planner-skill-design.md`
2. the minimal conversation-scoped session-store seam from PR #698, as reference material for a future deterministic rebuild

## Keep as reference

These PR #698 files are worth revisiting when a deterministic meal-planner implementation is started:

### Session persistence seam
- `core/memory/src/main/java/com/kernel/ai/core/memory/entity/MealPlanSessionEntity.kt`
- `core/memory/src/main/java/com/kernel/ai/core/memory/dao/MealPlanSessionDao.kt`
- `core/memory/src/main/java/com/kernel/ai/core/memory/repository/MealPlanSessionRepository.kt`
- `core/memory/src/test/java/com/kernel/ai/core/memory/MealPlanSessionEntityTest.kt`
- `core/memory/src/test/java/com/kernel/ai/core/memory/MealPlanSessionRepositoryTest.kt`

Why keep them:
- they move meal-planner continuity out of prompt-only memory
- they create a concrete persistence seam for an app-owned workflow
- they are reusable even if the orchestration above them is completely redesigned

### Useful supporting idea, not merge-ready code
- the broad design intent to route explicit meal-planner entry via `QuickIntentRouter`
- the design direction to suppress stale RAG/memory context during active meal-planner sessions

These should be reimplemented cleanly in a future deterministic design, not copied forward wholesale from PR #698.

## Do not keep from PR #698

These parts should be abandoned rather than salvaged:

- `MealPlannerCoordinator` implementation from PR #698
- stage skills:
  - `MealPlannerCollectSkill`
  - `MealPlannerPlanSkill`
  - `MealPlannerRecipeSkill`
  - `MealPlannerCompleteSkill`
  - `SaveMealPlanStateSkill`
- `ChatViewModel` meal-planner prompt/context injection from PR #698
- `ModelConfig` prompt inflation for meal-planner workflow control
- the Room schema bump to version 24 from that branch as a carry-forward artifact

Why not keep them:
- they still depend on the LLM to drive state transitions and artifact creation
- PR review on #698 still had a blocking correctness issue open (`high_level_plan_ready` without persisted plan)
- list creation and quantity grounding remained fragile
- the installed PR build caused a Room downgrade crash when switching back to the current app, confirming the branch is not safe to partially revive as-is

## Recommended future shape

If meal planning is revisited, start from the design doc and rebuild around these principles:

- app-owned state machine
- structured recipe and grocery persistence
- app-driven list creation
- LLM limited to bounded interpretation and content generation under strict schemas

## Practical next step

When a follow-up issue is opened, link this file and `meal-planner-skill-design.md`, then selectively re-create the session-store layer from PR #698 instead of reviving the abandoned branch.