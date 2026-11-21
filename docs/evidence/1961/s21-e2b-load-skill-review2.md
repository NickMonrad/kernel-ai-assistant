# S21 E-2B `load_skill("run_intent")` at 7000 (#1961, re-review item 1)

Device: S21 (`R5CR605B71K`), package `ai.jandal.assistant.debug`, build with `local.properties` pointing at the Android SDK, E-2B on GPU, MID_RANGE, `maxTokens` 7000 (`requested: 7000`).
Engine init: `Initializing engine — ... maxTokens: 7000 (requested: 7000)` at 01:52:37, `Engine ready — backend: GPU, maxTokens: 7000` at 01:52:49.
Query: test-only text, typed into a new empty conversation. The harness prefix `__orchtest:safe_run_intent:` is stripped from the bubble.

## Result: load_skill path completed

Query text: "Please read the run_intent instructions first, then check whether the device stopwatch is running and report its status. Do not start, pause, or reset it."

Markers from logcat, same run (generation `1222f294-1`):

| Marker | Observed |
|---|---|
| `generation_context_budget` attempt=1 | `app_context_window=7000 estimated_prior_fixed_tokens=2400 current_prompt_excluding_rag_tokens=1668 response_reserve_tokens=1024 max_tool_result_tokens=1908` |
| `event_seq: tool_call name=run_intent` (attempt 1) | `args={"intent_name":"stopwatch"}` |
| `event_seq: tool_result name=run_intent` (attempt 1) | `resultType=Failure directReply=false returnedToGemma=true` (the model skipped `load_skill` on its first call; `stopwatch` is not a catalogue intent) |
| `generation_context_budget` attempt=2 | `current_prompt_excluding_rag_tokens=51 max_tool_result_tokens=3525` |
| `event_seq: tool_call name=load_skill` | `args={"skill_name":"run_intent"}` |
| `event_seq: tool_result name=load_skill` | `resultType=Success directReply=false returnedToGemma=true` |
| `generation_diagnostic ... tool_dispatch_result tool=load_skill` | `returned=true serialized_result_tokens_estimate=1507` |
| `event_seq: tool_call name=run_intent` (attempt 2) | `args={"intent_name":"get_stopwatch_status"}` |
| `event_seq: tool_result name=run_intent` (attempt 2) | `resultType=DirectReply directReply=true returnedToGemma=false` |
| `llm_tools_tool_sequence` | `attempt=load_skill>run_intent turn=run_intent>load_skill>run_intent terminal=run_intent` |
| Completion | `Generation complete: total=20307ms` (attempt 2); tool-direct reply shown as "No active stopwatch." |

Max serialized load_skill result observed: 1507 tokens. Budget at attempt 1 was 1908 (`max_tool_result_tokens`), so the 1507 envelope fits.

Absent in the whole run (grep over the captured logcat):
- `load_skill_tool_result_context_abort`: 0 matches
- `ContextBudgetExceeded`: 0 matches
- `Input token ids are too long`: 0 matches

A second `load_skill("run_intent")` run in the same process (generation `fcc1c4b8-6`, 01:59) also returned `resultType=Success`, `serialized_result_tokens_estimate=1507`, and terminal `run_intent`.

## Not run

- The 4000 control was not run. Expected from the budget maths: `max_tool_result_tokens` 0 and `load_skill_tool_result_context_abort`. Not observed on device.
- The Quick Action weather tool path was not re-run at 7000 (see the PR body).
