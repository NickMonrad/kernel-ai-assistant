# `llm_tools` Harness — Deep Reference

See [`docs/automated-testing.md`](../automated-testing.md) for the operational index, commands,
and report inspection. This document provides deeper technical detail for contributors working
with the `llm_tools` harness phase.

## Purpose

The `llm_tools` harness phase validates end-to-end LLM tool-call generation. It tests the
path where a user query bypasses the deterministic QIR (Tier 2) and classifier routers and
falls through to Gemma (Tier 3) for reasoning and tool-call generation.

It does **not** test:
- QIR deterministic routing (that is covered by the main `skills` suite)
- Classifier zero-shot intent matching
- Slot filling or confirmation dialogs
- Hallucination retry paths

## What it validates

For every selected golden prompt, the harness checks:

1. **Route to Gemma** — the harness confirms a `llm_tools_route` marker was emitted,
   indicating the query reached Gemma (via fallthrough from deterministic QIR/classifier paths)
2. **Tool call generation** — Gemma produced either a native SDK tool call or a legacy
   text-format tool call
3. **Correct tool** — the expected top-level tool name matches the actual tool called
4. **Result observability** — the tool execution result is logged via `llm_tools_skill_result`
5. **Persistence policy** — normal cases require a saved tool-call message; isolated safe probes
   require no persisted marker.
6. **UI evidence** — a chip for the tool call is visible on screen
7. **No retry** — no unexpected hallucination retry path was triggered
8. **No slot fill** — no QIR slot-fill or confirmation path was used

## Golden prompts

| Case | Prompt | Expected tool | Key expectations |
|------|--------|--------------|------------------|
| `query_wikipedia_natural` | "Look up the history of the Battle of Hastings on Wikipedia for me" | `query_wikipedia` | `no_regex_match=True`, `no_classifier=True`, `no_slot_fill=True`, `no_retry=True` |
| `save_memory_durable_fact` | "Here is a lasting fact I want you to know: my preferred dry cleaner is Star Dry Cleaning" | `save_memory` | Same + `content` field must be present and non-empty |
| `run_intent_get_date_direct` | "Can you tell me the day, date, and exact local time right now on this device? Please use the read-only run_intent get_date action rather than an estimate." | `run_intent` | Debug-only isolated probe; nested action `get_date`; ordered sequence `run_intent`; successful `direct_reply` contains the device-local date/time |
| `run_intent_get_date_after_skill_load` | "Please read the run_intent instructions first; then use its get_date action to tell me the current local date and time." | `run_intent` | Same controlled fallthrough guard; `load_skill` succeeds and returns to Gemma; ordered sequence `load_skill → run_intent`; nested action `get_date`; successful `direct_reply` contains the device-local date/time |
| `get_system_info_natural` | "Can you inspect this device and summarise its current system status?" | `get_system_info` | Dedicated top-level tool remains available; ordered sequence `get_system_info`; direct reply |

The #1593 cases use the read-only `get_date` action. Do not use `get_date_diff` for
this safety acceptance: its native implementation may consult Important Dates.

## Runtime markers

These are the structured logcat markers the harness reads. They are emitted by the app code
(`ChatViewModel`, `NativeIntentHandler`, and the tool-call path).

| Marker | Report field | Format | Example |
|--------|-------------|--------|---------|
| `llm_tools_route` | `route_marker` | `result=<value> best_guess=<intent> confidence=<float>` | `result=fallthrough best_guess=null confidence=0.0` |
| `llm_tools_native_tool` | `native_tool_marker` | Full tool-call JSON | `{"name":"query_wikipedia","arguments":{...}}` |
| `llm_tools_legacy_tool` | `legacy_tool_marker` | Raw `<\|tool_call\|>` text | `<\|tool_call\|>call:query_wikipedia{query:...}` |
| `llm_tools_skill_result` | `skill_result_marker` | `skill=... mode=<mode> success=<bool>` | `skill={"name":"query_wikipedia",...} mode=direct_reply success=true` |
| `llm_tools_message_toolcall_saved` | `message_saved_marker` | `id=<uuid> tool=<name>` | `id=7e195582-... tool=query_wikipedia` |
| `tool_chip_visible` | `chip_text` | `tool=<name>` | `tool=query_wikipedia` |
| `event_seq` | `tool_event_evidence` | `tool_call` or `tool_result` name and safe result fields | `tool_result name=load_skill resultType=Success ...` |
| `llm_tools_tool_sequence` | `tool_sequence_marker` | `attempt=<tool> turn=<ordered tools> terminal=<tool>` | `attempt=run_intent turn=load_skill>run_intent terminal=run_intent` |

## Fresh per-case log boundary

Before each prompt, the harness force-stops the app, emits a unique boundary marker, waits for the persistent logcat reader to observe it, and seeds that synchronized snapshot into the marker poll.
Each case requires exactly one `ADB_INTENT_TRACE` input marker matching that prompt's logged 120-character prefix. Polling stops immediately on a mismatched or duplicate prompt; a missing marker fails closed at timeout. This excludes delayed preflight output and prevents another session's prompt/tool events from being attributed to the case.

## Ordered tool-sequence evidence (#1593)

The former stopwatch prompt could not reach Gemma: `get_stopwatch_status` is in
QIR's `FAST_PATH_INTENTS`. The earlier `get_date_diff` device probe is not used for
the personal-data-safe path because its native handler may consult Important Dates.
Its date parameter contract remains documented by `RunIntentSkill`; the natural
date-difference prompts remain in `QuickIntentRouterNegativeTest`.

The safe direct and discovery probes use read-only `get_date` with no parameters.
The direct probe requires `run_intent`; the discovery probe requires successful
`load_skill("run_intent")` returned to Gemma before `run_intent`. The natural
`get_system_info_natural` case remains a separate dedicated top-level SDK-tool control.

For these two cases only, the runner prefixes the selected prompt with
`__orchtest:safe_run_intent:`. The DEBUG-only chat path strips the prefix before model input,
resets model context, omits profile/date/history/RAG context, and forces a recorded
`FallThrough` without invoking QIR. It requires the model to be ready; it does not initialize
the model or read the active conversation for the probe. During generation, the tool set allows
only `load_skill("run_intent")` and `run_intent("get_date", {})`; all other tools and actions
fail closed before skill lookup.

These probes validate real-model tool execution under controlled debug fallthrough. They do not
claim an ordinary prompt naturally misses QIR or the classifier; normal routing is unchanged.
Issue #1593's natural device-routing acceptance remains pending.

The sandbox closes on every inference exit. Probe messages and the tool chip remain in memory:
the path does not persist them, index them in RAG, or distil the active conversation on close.
The runner fails if either safe case emits a saved-message marker.

The probe's route guard consumes the structured `result=fallthrough` marker emitted by the
controlled DEBUG branch. It is not evidence that QIR evaluated and missed the prompt.

For cases with `expected_tool_sequence`, the runner requires:

- the last `llm_tools_tool_sequence` marker's `turn=` value to equal the expected order;
- matching ordered `event_seq: tool_call` and `event_seq: tool_result` records;
- the expected `load_skill.skill_name` and `run_intent.intent_name` targets;
- `load_skill` to return `resultType=Success` and `returnedToGemma=true`;
- the terminal result and `llm_tools_skill_result` to succeed, the terminal tool chip to
  match, and a non-empty final reply with no raw tool protocol or loaded instructions;
- every `expected_reply_contains_all` term to appear in the final reply.

The report stores `tool_sequence_marker` and sanitized `tool_event_evidence`. Event evidence
contains event name, tool name, `skill_name` only for `load_skill`, and `intent_name` only
for `run_intent`, plus result type, direct-reply flag, and handoff flag. It omits raw
arguments and result content.

The runtime log forms are:

```text
event_seq: tool_call name=load_skill args=<omitted>
event_seq: tool_result name=load_skill resultType=Success directReply=false returnedToGemma=true content=<omitted>
llm_tools_tool_sequence: attempt=run_intent turn=load_skill>run_intent terminal=run_intent
```

## Result mode assertions

Each case expects a specific result mode, encoded in the `llm_tools_skill_result` marker:

| Mode | Meaning | Expected for |
|------|---------|-------------|
| `success` | Tool executed and returned a result | `save_memory` |
| `direct_reply` | Tool result was streamed directly as a chat reply | `query_wikipedia`, `get_system_info`, `run_intent` |
| `failure` | Tool execution failed | Not expected for golden prompts; seen during development |

The `query_wikipedia`, `get_system_info`, and read-only `run_intent` cases expect
`direct_reply` because their tool execution result is streamed directly as a chat reply.
The `save_memory` case expects `success` because the memory save operation confirms persistence.

## Report format

See [`docs/automated-testing.md`](../automated-testing.md#reports-and-result-inspection) for
the full JSON schema.

Key `llm_tools`-specific report fields:

| Field | Type | Description |
|-------|------|-------------|
| `expected_top_level_tool` | string | The tool the model should call |
| `actual_top_level_tool` | string or null | The tool the model actually called (null if no call) |
| `route_marker` | string or null | Raw `llm_tools_route:` log line content |
| `native_tool_marker` | string or null | Raw native tool-call JSON |
| `legacy_tool_marker` | string or null | Raw legacy text-format tool call |
| `skill_result_marker` | string or null | Raw `llm_tools_skill_result:` content |
| `message_saved_marker` | string or null | Raw `llm_tools_message_toolcall_saved:` content |
| `retry_seen` | bool | Whether a retry marker was found in logs |
| `slot_fill_seen` | bool | Whether a slot-fill/confirmation marker was found |
| `chip_text` | string or null | UI chip text for the tool call |
| `failures` | array of strings | Descriptive failure messages |
| `expected_tool_sequence` | array of tool names or null | Required ordered SDK calls for the case |
| `actual_tool_sequence` | array of tool names or null | Parsed from the last turn-level sequence marker |
| `tool_sequence_marker` | string or null | Safe `attempt`/`turn`/`terminal` summary from the last sequence marker |
| `tool_event_evidence` | array of sanitized objects | Ordered `tool_call`/`tool_result` names and safe result metadata; excludes raw arguments/content |


## Local diagnostic transcripts

Each real `llm_tools` case asks the debug build for a local transcript after the turn completes.
The runner writes it under the ignored
`scripts/test-reports/local_llm_tools_diagnostics/` directory. The transcript contains persisted
user and assistant messages (including full thinking and tool-call metadata), each generation
attempt's exact output and raw thinking (including retries later replaced by guard fallbacks), full
tool arguments and results in call order, direct-reply/returned-to-Gemma state, the final visible
response, and the harness outcome.

These files contain unredacted private conversation data. Keep them on the local test host; do not
attach, upload, commit, or publish them. The debug-only provider uses app-private cache as a
temporary transfer path and clears it after export. Normal JSON/Markdown evidence is unchanged
and remains limited to its existing sanitized fields; full transcript content is not written to
production logcat.

## On-device commands

```bash
# Run just the llm_tools phase on a specific device
ANDROID_SERIAL=R5CR605B71K python3 scripts/adb_skill_test.py --phases=llm_tools

# Dry run (no device needed)
python3 scripts/adb_skill_test.py --dry-run --phases=llm_tools
```

The `--phases=llm_tools` flag is handled as a special case — it does not run any QIR skill
phases. `llm_tools` is intentionally separate from the normal QIR/skills phase list because
it has different data models, runtime markers, model/tool-call assertions, and device
reliability characteristics.

## Known model/device flakes

These are observed reliability patterns, not harness bugs:

| Case | Device | Failure mode | Frequency | Status |
|------|--------|-------------|-----------|--------|
| `save_memory_durable_fact` | S21 Exynos (GPU) | No tool call generated — model returns conversational response | ~50% | Tracked — backend difference |
| `get_system_info_natural` | S21 Exynos (GPU) | Wrong tool or no tool call | ~30% | Tracked — #1114 |
| Any `llm_tools` case | Any device (first run after app install) | Route marker not found — app still initialising | First run only | Expected — retry after engine ready |

> Flakes must be tracked in issues, not silenced by relaxing harness assertions.

## Troubleshooting

**All cases fail with "No route marker found"**
- The app may not have started or the engine may not be ready. Wait for
  `Engine ready` in logcat before running.
- Check that the app has the llm_tools marker logging enabled (build after PR #1111).

**All cases fail with "No native-tool or legacy-tool marker found"**
- Gemma is not producing tool-call output. Check:
  - Is the model loaded? (`adb logcat -s LiteRtInferenceEngine`)
  - Is the system prompt including the tool definitions correctly?
  - Is the query actually falling through to Gemma?

**A single case fails intermittently across runs**
- Model output is non-deterministic. Run 3 times before reporting a regression.
- Compare across inference backends (NPU vs GPU) — some backends produce different model
  output distributions.

**Failures change between `--dry-run` and on-device runs**
- `--dry-run` only validates test case definitions, not model behaviour. A passing dry-run
  with a failing on-device run means the test structure is correct but the model is not
  generating the expected output.

## Code structure

The harness code is in `scripts/adb_skill_test.py`:

- `LLMToolsTestCase` — test case data class (lines ~111–124)
- `LLMToolsResult` — result data class (lines ~143–162)
- `run_llm_tools()` — main runner function (lines ~693–944)
- `save_llm_tools_report()` — JSON report serialisation (lines ~1302–1360)
- Marker patterns — lines ~44–50

The llm_tools runner is invoked as a separate top-level path in `main()` (line ~2284) when
`--phases=llm_tools` is passed.
