# S21 E-2B fall-through tool query at 7000 (#1961)

Device: S21 (SM-G991B), debug build, head `84e834f343`, E-2B on GPU, `maxTokens` 7000 (`requested: 7000`).
Engine init for this run: 01:11:38 to 01:11:46.

## Result: fall-through completes; load_skill NOT exercised

Not verified: a `load_skill`-routed query. No `load_skill` call appeared in logcat for either query below.

### Query 1: calendar, read-only

- Routed deterministically, not by the LLM: `llm_tools_route: result=classified intent=read_calendar_events`, `NativeIntentHandler.handle: intent=read_calendar_events`, `ADB_TOOL_COMPLETE ... result=capability_required_no_llm`.
- The reply asked for calendar permission. The permission dialog was answered "Don't allow". No grant was made.
- No `generation_context_budget` line, so the LLM path was not run.

### Query 2: explanation prompt, no tools

- Route: `ADB_ROUTE_DECISION result=fallthrough best_guess=null confidence=0.0`, `ADB_MODEL_STATE status=generating route=llm_fallthrough`.
- Budget: `generation_context_budget generation_id=ff9e6ead-2-g1 attempt=1 source=app_estimate resolved_token_cap=7000 app_context_window=7000 estimated_prior_fixed_tokens=2598 current_prompt_excluding_rag_tokens=182 response_reserve_tokens=1024 max_tool_result_tokens=3196`.
- Completion: `Generation complete: total=13272ms, TTFT=7572ms, visibleChunks=40 [backend=GPU]`.
- Errors: no `ContextBudgetExceeded`, no `too long`, and no `load_skill` in the captured logcat.

## Gap

- The `load_skill` path itself was not reached. The model did not call `load_skill` for the fall-through query, and the calendar query is handled by a native intent before the LLM. A separate query that the model routes through `load_skill` is needed to close this check.

## Not included

- Raw logcat with prompt text stays out of the repo.
