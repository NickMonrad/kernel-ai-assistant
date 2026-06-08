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

For each of the 3 golden prompts, the harness checks:

1. **Route to Gemma** — the harness confirms a `llm_tools_route` marker was emitted,
   indicating the query reached Gemma (via fallthrough from deterministic QIR/classifier paths)
2. **Tool call generation** — Gemma produced either a native SDK tool call or a legacy
   text-format tool call
3. **Correct tool** — the expected top-level tool name matches the actual tool called
4. **Result observability** — the tool execution result is logged via `llm_tools_skill_result`
5. **Message persistence** — the tool call message is saved in chat history
6. **UI evidence** — a chip for the tool call is visible on screen
7. **No retry** — no unexpected hallucination retry path was triggered
8. **No slot fill** — no QIR slot-fill or confirmation path was used

## Golden prompts

| Case | Prompt | Expected tool | Key expectations |
|------|--------|--------------|------------------|
| `query_wikipedia_natural` | "Look up the history of the Battle of Hastings on Wikipedia for me" | `query_wikipedia` | `no_regex_match=True`, `no_classifier=True`, `no_slot_fill=True`, `no_retry=True` |
| `save_memory_durable_fact` | "Here is a lasting fact I want you to know: my preferred dry cleaner is Star Dry Cleaning" | `save_memory` | Same + `content` field must be present and non-empty |
| `get_system_info_natural` | "Can you inspect this device and summarise its current system status?" | `get_system_info` | Same, no tool arguments expected |

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

## Result mode assertions

Each case expects a specific result mode, encoded in the `llm_tools_skill_result` marker:

| Mode | Meaning | Expected for |
|------|---------|-------------|
| `success` | Tool executed and returned a result | `save_memory` |
| `direct_reply` | Tool result was streamed directly as a chat reply | `query_wikipedia`, `get_system_info` |
| `failure` | Tool execution failed | Not expected for golden prompts; seen during development |

The `query_wikipedia` and `get_system_info` cases expect `direct_reply` because the tool
execution result is streamed directly as a chat reply. The `save_memory` case expects
`success` because the memory save operation confirms persistence.

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
