# Issue #1107 — llm_tools harness review

Reviewed against:

- `issue://1107`
- `scripts/adb_skill_test.py`
- `feature/chat/src/main/java/com/kernel/ai/feature/chat/ChatViewModel.kt`
- `feature/chat/src/main/java/com/kernel/ai/feature/chat/ChatScreen.kt`
- `feature/chat/src/main/java/com/kernel/ai/feature/chat/ToolCallExtractor.kt`
- `feature/chat/src/main/java/com/kernel/ai/feature/chat/model/ToolCallInfo.kt`
- `feature/chat/src/main/java/com/kernel/ai/feature/chat/model/ToolCallInfoJson.kt`
- `core/skills/src/main/java/com/kernel/ai/core/skills/KernelAIToolSet.kt`
- `core/skills/src/main/java/com/kernel/ai/core/skills/MiniLMIntentClassifier.kt`
- `core/skills/src/main/java/com/kernel/ai/core/skills/QuickIntentRouter.kt`
- `core/skills/src/main/java/com/kernel/ai/core/skills/SkillExecutor.kt`
- `core/skills/src/main/java/com/kernel/ai/core/skills/natives/NativeIntentHandler.kt`
- `docs/testing/automated-test-specification.md`

## 1. Acceptance-criteria gaps

### 1.1 “Expected tool name” is currently underspecified, and the examples are wrong for this codebase

The issue body talks about prompts like `set_timer`, `create_reminder`, and `get_weather`, but the LLM-facing tool surface is not the same thing as the routed intent surface.

Observed code:

- `KernelAIToolSet` exposes top-level SDK tools such as `runIntent`, `getWeather`, `saveMemory`, `getSystemInfo`, `loadSkill`, `runJs`, `searchMemory`, `convertCurrency` (`core/skills/src/main/java/com/kernel/ai/core/skills/KernelAIToolSet.kt`)
- `ToolCallInfo.skillName` stores the top-level tool name, not the nested intent name (`feature/chat/src/main/java/com/kernel/ai/feature/chat/model/ToolCallInfo.kt`)
- `set_timer` is usually reached via `run_intent` / `runIntent(intentName="set_timer", ...)`, not as a top-level tool
- there is no `create_reminder` intent in the current router/handler; the actual intent is `add_reminder` (`core/skills/src/main/java/com/kernel/ai/core/skills/QuickIntentRouter.kt`, `core/skills/src/main/java/com/kernel/ai/core/skills/natives/NativeIntentHandler.kt`)

What to tighten:

- define whether “expected tool name” means:
  - top-level SDK tool (`run_intent`, `get_weather`, `save_memory`), or
  - nested routed intent inside the tool payload (`set_timer`, `add_reminder`)
- replace `create_reminder` with `add_reminder`
- for `set_timer`, acceptance should expect `toolName=run_intent` plus `intent_name=set_timer`, not `toolName=set_timer`

### 1.2 Requiring parseable tool-call JSON is incompatible with the native SDK happy path unless you define the acceptable evidence

`ChatViewModel` has two distinct LLM tool paths:

1. native SDK tool calling: `kernelAIToolSet.wasToolCalled()` is true, and the final UI metadata comes from `lastToolName/lastToolRequest/lastToolResult` (`feature/chat/src/main/java/com/kernel/ai/feature/chat/ChatViewModel.kt:2013-2068`)
2. legacy raw-text fallback: `tryExecuteToolCall(fullContent)` parses raw JSON or `<|tool_call>` text from the model output (`feature/chat/src/main/java/com/kernel/ai/feature/chat/ChatViewModel.kt:2032-2037,2425-2477`)

In case (1), the model may never emit a raw JSON block into the final assistant text. The current acceptance wording can accidentally fail the preferred implementation.

What to tighten:

- allow either:
  - native SDK evidence: `KernelAIToolSet` tool invocation + request payload + execution result, or
  - legacy raw-output evidence: parseable JSON / `<|tool_call>` extraction
- do not require raw JSON in the final assistant text for every llm_tools pass case

### 1.3 “ChatMessage.toolCall populated” is not directly observable from the current ADB harness

Observed code:

- persistence happens as `toolCallJson` in Room (`feature/chat/src/main/java/com/kernel/ai/feature/chat/ChatViewModel.kt:2063-2067`, `feature/chat/src/main/java/com/kernel/ai/feature/chat/model/ToolCallInfoJson.kt`)
- `ChatMessage.toolCall` is reconstructed in memory from `toolCallJson` (`feature/chat/src/main/java/com/kernel/ai/feature/chat/ChatViewModel.kt:783-792`)
- `scripts/adb_skill_test.py` currently has no DB query, no exported debug hook, and no log marker for this persistence step

What to tighten:

- acceptance must name a stable proof point for this step:
  - a debug log emitted at the persistence/update site, or
  - an adb-readable DB/debug hook
- “assert `ChatMessage.toolCall` populated” is otherwise untestable from `scripts/adb_skill_test.py`

### 1.4 The chip assertion is ambiguous if the implementation relies on Compose-only test tags

Observed code:

- the chip uses `Modifier.testTag("tool_chip")` (`feature/chat/src/main/java/com/kernel/ai/feature/chat/ChatScreen.kt:1750-1782`)
- the collapsed chip exposes:
  - visible text = `toolCall.skillName`
  - icon content description = `Tool succeeded` / `Tool failed`
- `testTag` is useful for Compose UI tests, not ADB/UIAutomator text dumps

What to tighten:

- specify the exact user-visible surface allowed for ADB assertions:
  - visible chip label text (`run_intent`, `get_weather`, etc.)
  - icon `contentDescription`
  - or a stable debug log
- do not say “chip visible” without defining whether Compose test tags count; from this harness, they do not

### 1.5 DirectReply cannot be a blanket pass/fail condition across the proposed prompt set

Observed code:

- `setTimer()` returns `SkillResult.Success` (`NativeIntentHandler.kt:433-452`)
- `addReminder()` returns `SkillResult.DirectReply` (`NativeIntentHandler.kt:1938-2026`)
- `saveMemory()` returns `SkillResult.Success` after successful save (`NativeIntentHandler.kt:2713-2734`)
- `get_weather` through the native tool path returns structured weather data, but the top-level LLM-facing tool is still `get_weather` / `getWeather`, not a generic “DirectReply case” marker
- current harness only has `expect_reply_contains`, which is warning-only (`scripts/adb_skill_test.py:1016-1023`)

What to tighten:

- make reply-mode expectations per-case, not suite-wide
- define an explicit expected result mode for each llm_tools case, e.g.:
  - `expected_tool_name`
  - `expected_nested_intent`
  - `expected_result_mode = direct_reply | llm_wrapped_success`
- otherwise a valid `set_timer` llm_tools run can fail for the wrong reason

### 1.6 MiniLM “low/no confidence” is not currently assertable with the existing log collection

Observed code:

- `MiniLMIntentClassifier` logs under tag `MiniLMIntentClassifier`, not `KernelAI` (`core/skills/src/main/java/com/kernel/ai/core/skills/MiniLMIntentClassifier.kt`)
- `read_logcat()` only reads `KernelAI` (`scripts/adb_skill_test.py:440-441`)
- `read_logcat_all()` reads `KernelAI` and `LiteRtInferenceEngine`, but still not `MiniLMIntentClassifier` (`scripts/adb_skill_test.py:444-446`)
- `QuickIntentRouter.route()` makes the final `ClassifierMatch` vs `FallThrough` decision in memory and does not currently emit a stable route-decision log (`core/skills/src/main/java/com/kernel/ai/core/skills/QuickIntentRouter.kt:4183-4252`)

What to tighten:

- acceptance should require either:
  - a route-decision marker under `KernelAI`, or
  - widening harness logcat collection to include `MiniLMIntentClassifier`
- define the difference between:
  - classifier below threshold / ambiguous (real llm_tools path)
  - classifier still initializing / unavailable (infra failure)

### 1.7 “RecoveryOrchestrator” is stale terminology in the current repo

`issue://1107` still references `RecoveryOrchestrator`, but there is no `RecoveryOrchestrator` symbol in this checkout.

Current deterministic non-LLM exits are:

- `QuickIntentRouter.RouteResult.RegexMatch`
- `QuickIntentRouter.RouteResult.ClassifierMatch`
- `QuickIntentRouter.RouteResult.NeedsSlot`
- pending-confirmation fast path in `ChatViewModel`
- anaphoric fast path in `ChatViewModel`
- weather-follow-up resolution in `ChatViewModel`

What to tighten:

- acceptance should talk about “no deterministic fast-path dispatch before Gemma” and name the current concrete paths, not `RecoveryOrchestrator`

### 1.8 The issue does not currently define how to treat optional intermediate `load_skill` calls

Observed code:

- `KernelAIToolSet` explicitly documents `loadSkill()` as a legitimate first step for complex gateway tools (`KernelAIToolSet.kt:80-92`)
- the SDK tool-state only keeps the last tool name/request/result in memory (`KernelAIToolSet.kt:45-74`)

This matters because a valid model trace could be:

1. `load_skill("run_intent")`
2. `run_intent(intent_name="set_timer", ...)`

If acceptance assumes a single tool call only, it will punish a legal path.

What to tighten:

- say whether `load_skill` before the terminal tool is:
  - allowed and ignored, or
  - disallowed and should fail the suite
- if allowed, require assertions on the terminal executable tool, not “exactly one call happened”

### 1.9 Repo-hygiene acceptance should not make a missing path a blocking failure

Observed repo state:

- `scripts/test-reports/` exists and is tracked
- `scripts/__pycache__/adb_skill_test.cpython-314.pyc` exists
- `scripts/testdata/intent_recovery/` does **not** exist in this checkout

What to tighten:

- phrase the duplicate-corpus item as “confirm absent or remove if present”
- otherwise the issue creates a fake failure mode for a path that is already gone

### 1.10 The issue should decide whether `scripts/test-reports/` remains the runtime output location

Observed code:

- `save_report()` always writes to `scripts/test-reports/` and auto-generates HTML there (`scripts/adb_skill_test.py:625-699`)

If repo hygiene removes tracked contents and ignores the directory, that is fine. But the issue should say whether runtime output still belongs there.

What to tighten:

- either keep `scripts/test-reports/` as ignored runtime output, or move report output somewhere else
- do not leave that implicit while also making “remove scripts/test-reports/” a checklist item

## 2. Common pitfalls

### 2.1 The current warmup proves QIR only, not the llm_tools path

Observed code:

- `run_tests()` warms up with `"what time is it"` and waits for `NativeIntentHandler.handle` (`scripts/adb_skill_test.py:854-884`)
- that is a deterministic QIR/DirectReply path, not a MiniLM/Gemma fallthrough path

Pitfall:

- llm_tools could start after a “successful” warmup even though MiniLM never initialized and Gemma tool calling is still cold/broken

Recommendation:

- use a dedicated llm_tools preflight, separate from deterministic warmup
- fail fast if the llm_tools preflight fails; do not “timeout (proceeding anyway)” for this suite

### 2.2 `MiniLMIntentClassifier` has a real startup race that can masquerade as a valid fallthrough

Observed code:

- `classify()` waits only 500 ms for `initJob` before giving up (`MiniLMIntentClassifier.kt:82-87`)
- if init is still running, the classifier returns `null` and the router falls through

Pitfall:

- a test can “prove” fallthrough because MiniLM was not ready, not because the prompt genuinely bypassed deterministic routing

Recommendation:

- llm_tools preflight must prove classifier readiness first
- then the actual prompt assertions can distinguish “below threshold” from “not ready”

### 2.3 Conversation state can contaminate later prompts unless each llm_tools case is isolated

Observed code:

- `pendingConfirmationIntent` can short-circuit the next user reply (`ChatViewModel.kt:1480-1533`)
- weather follow-ups can resolve location from prior chat history (`ChatViewModel.kt:1535-1552`, `WeatherConversationReferenceResolver.kt`)
- anaphoric save-memory fallback can turn a later utterance into `save_memory` based on the previous user message (`ChatViewModel.kt:1678-1733`)
- slot-fill state can intercept the next turn before QIR/LLM (`ChatViewModel.kt:1424-1476`)

Pitfall:

- the second or third llm_tools prompt may not be testing what the suite thinks it is testing

Recommendation:

- start a new conversation before every llm_tools case, or clear the existing conversation state deterministically
- do not reuse the shared conversation flow from the deterministic suite

### 2.4 A fixed `WAIT_SECONDS = 20` and one final `logcat -d` read is too brittle for the new suite

Observed code:

- deterministic tests sleep a fixed 20 seconds and then read logcat once (`scripts/adb_skill_test.py:44,1009-1011`)

Pitfall:

- llm_tools needs multiple sequential milestones:
  - deterministic path miss
  - classifier result / fallthrough
  - Gemma generation
  - tool invocation / parse
  - skill execution
  - chip/message persistence
- a single read after a fixed sleep makes false negatives and slows every pass case

Recommendation:

- poll for explicit llm_tools markers with early exit
- keep a per-case deadline, but stop as soon as the terminal marker is seen

### 2.5 UIAutomator will not see `testTag("tool_chip")`

Observed code:

- the chip is tagged with `testTag("tool_chip")` only (`ChatScreen.kt:1781`)

Pitfall:

- an implementation that says “assert `tool_chip` exists” will work only in Compose instrumentation tests, not in the ADB shell harness

Recommendation:

- if using UIAutomator, assert visible chip text / content description
- otherwise prefer a stable log marker emitted from `ChatViewModel`, not from the composable

### 2.6 Do not emit chip-observability logs from the composable itself

Observed code:

- chip rendering lives in `ToolCallChip(...)` (`ChatScreen.kt:1750-1836`)
- Compose recomposition is not a stable one-shot event

Pitfall:

- logging from the composable can duplicate markers and make the harness flaky

Recommendation:

- emit the stable “tool call persisted / chip-worthy metadata attached” marker from:
  - `appendAssistantMessageWithToolCall(...)`, or
  - the native-tool / fallback-tool success branch in `ChatViewModel` (`ChatViewModel.kt:2052-2068`)
- if UI-level proof is still required, keep it as a second assertion, not the only one

### 2.7 The current success heuristic for native SDK tool calls is too weak for a harness assertion

Observed code:

- `ToolCallInfo.isSuccess` is set from `!result.startsWith("error")` in the native SDK path (`ChatViewModel.kt:2017-2029`)

Pitfall:

- if a tool returns a failure-like string that does not literally start with `error`, the chip can be marked successful even though the action failed semantically

Recommendation:

- do not build llm_tools pass/fail on the chip success color alone
- assert the execution-result marker or `SkillResult` path explicitly

### 2.8 `ToolCallExtractor` is intentionally permissive; harness assertions should not over-trust it

Observed code:

- `extractToolCallJson()` returns the first balanced `{...}` block containing `"name"` (`ToolCallExtractor.kt:57-91`)
- `tryExecuteToolCall()` executes the first extracted block only (`ChatViewModel.kt:2430-2475`)

Pitfall:

- verbose model output containing incidental JSON can be mis-parsed
- multiple JSON blocks or multiple tool calls are not handled as a rich trace

Recommendation:

- require the suite to assert exactly the parsed payload that was executed
- for reviewability, persist the canonical executed request string into the JSON report

### 2.9 Field-type assertions will be brittle because the execution path normalizes arguments to strings

Observed code:

- `SkillExecutor.parseSkillCall()` uses `args[key] = argsObj.optString(key)` (`SkillExecutor.kt:41-46`)
- `ToolCallExtractor.parseNativeArgs()` may parse numbers, but they become strings when executed

Pitfall:

- a harness that expects JSON numeric types (`180` vs `"180"`) will fail even when execution is correct

Recommendation:

- assert semantic field presence/value, not raw JSON type fidelity

### 2.10 `shlex.quote()` is already baked into the ADB extras path

Observed code:

- `send_text()`, `send_quick_action()`, `send_slot_reply()`, and warmup calls pass `shlex.quote(text)` into `adb shell am start --es ...` (`scripts/adb_skill_test.py:473-536,860-876`)

Pitfall:

- prompts with apostrophes, quotes, or shell-sensitive punctuation can behave differently from plain text prompts

Recommendation:

- keep llm_tools goldens plain ASCII unless the helper is hardened
- if a prompt corpus starts using quotes or apostrophes, verify what actually arrives in-app before trusting failures

## 3. Missing edge cases

### 3.1 Define whether a raw-tool-call retry counts as pass or fail

Observed code:

- if the model leaks raw tool syntax or hallucinates a confirmation, `ChatViewModel` can retry once and later log `raw_tool_call_retry_succeeded` / `hallucination_retry_succeeded` (`ChatViewModel.kt:2090-2149`)

Open question the issue should answer:

- if first-pass generation was bad but the automatic retry recovered, is llm_tools green or red?

My recommendation:

- treat any retry marker as a failure for this suite; issue #1107 is specifically about validating correct first-pass tool generation after fallthrough

### 3.2 Define whether optional `load_skill` is acceptable

This is separate from the acceptance gap above because it changes case design and report shape.

If `load_skill` is allowed, the harness/report should capture:

- `intermediate_tools = [load_skill]`
- `terminal_tool = run_intent`
- nested intent assertions on the terminal request only

If `load_skill` is disallowed, say so explicitly and fail on any load-skill marker.

### 3.3 A blank final response can currently short-circuit tool-call inspection

Observed code:

- `fullContent.isBlank()` is handled before `nativeToolCall` / `tryExecuteToolCall()` inspection (`ChatViewModel.kt:1963-2011` before `2013-2039`)

Edge case:

- if the SDK invoked a tool but produced no final text tokens, the blank-response guard can mask the tool call path entirely

Recommendation:

- llm_tools should explicitly test or at least account for this path
- if you add observability, emit the tool-invocation marker before any blank-response fallback can swallow the evidence

### 3.4 Slot-fill is a distinct failure mode and needs its own explicit assertion

Observed code:

- `RouteResult.NeedsSlot` produces an immediate slot prompt and returns before LLM (`ChatViewModel.kt:1569-1585`)

Edge case:

- an llm_tools prompt that is underspecified may fail because it triggered slot-fill, not because tool calling broke

Recommendation:

- make “no slot-fill prompt / no `NeedsSlot` path” an explicit assertion
- keep reminder prompts fully specified with item + future day + time

### 3.5 Classifier fast-path confirmation is also a deterministic escape hatch

Observed code:

- `RouteResult.ClassifierMatch` can either execute directly or set `pendingConfirmationIntent` (`QuickIntentRouter.kt:4227-4247`, `ChatViewModel.kt:1555-1566`)

Edge case:

- a prompt may not regex-match, but still never reach Gemma because the classifier intercepted it

Recommendation:

- assert absence of:
  - `ClassifierMatch`
  - `ConfirmationFastPath:` logs

### 3.6 Weather prompts need explicit named locations to avoid GPS/permission/network variance

Observed code:

- the LLM-facing weather tool defaults to GPS when `location` is blank (`KernelAIToolSet.kt:185-205`)

Edge case:

- “what’s the weather like?” can fail for permissions/network/device-state reasons unrelated to llm tool generation

Recommendation:

- keep llm_tools weather prompts pinned to a named city
- do not use current-location weather as the golden llm fallthrough case

### 3.7 Reminder/timer prompts must be future-safe

Observed code:

- `addReminder()` rejects past trigger times (`NativeIntentHandler.kt:1947-2026`)
- timer execution succeeds only when duration parsing survives the model output (`NativeIntentHandler.kt:433-452`)

Edge case:

- “remind me tomorrow at 9” is safe only if run before tomorrow 9 in device-local context
- “remind me at 9” can flip into slot-fill or failure depending on date/time resolution

Recommendation:

- freeze reminder goldens to clearly future-safe phrasing
- avoid date/time cases that can become past-dated during normal CI/device use

### 3.8 Rich presentations can change what text is actually visible in the chat body

Observed code:

- when `message.toolCall.presentation != null` and success is true, the assistant bubble can be suppressed (`ChatScreen.kt:798-808`)

Edge case:

- weather may render as presentation content + chip, not the same free-text layout used by plain responses

Recommendation:

- do not make UI assertions depend only on assistant bubble text
- anchor on chip metadata and/or stable logging first

## 4. Implementation suggestions

### 4.1 Do not overload `TestCase` / `TestResult` for llm_tools

Current deterministic suite data model is intent-centric:

- `TestCase.expect_intent`
- `TestCase.expect_reply_contains`
- `TestResult.actual_intent`
- `TestResult.reply_warn`

That is the wrong shape for llm_tools.

Recommendation:

- add a dedicated llm-tools case/result model, e.g.:
  - `expected_top_level_tool`
  - `expected_nested_intent`
  - `expected_fields`
  - `expected_result_mode`
  - `expect_tool_chip`
  - `expect_no_regex_dispatch`
  - `expect_no_classifier_dispatch`
- keep it as a separate runner, similar to `run_profile_tests()` (`scripts/adb_skill_test.py:1220-1326`)
- if the CLI must still be `--phases llm_tools`, map that token to the dedicated runner instead of shoving incompatible logic into the existing per-phase loop

### 4.2 Add stable llm-tools log markers at the source-of-truth sites, not via inference

Suggested marker sites:

- after `routeResult` resolution in `ChatViewModel`
- when `kernelAIToolSet.wasToolCalled()` becomes the chosen path
- when `tryExecuteToolCall()` succeeds
- immediately after `_messages.update(... toolCall = toolCall)` / `conversationRepository.addMessage(... toolCallJson = ...)`

Suggested marker payloads:

- `llm_tools_route: result=fallthrough best_guess=<...> confidence=<...>`
- `llm_tools_native_tool: tool=<...> request=<...>`
- `llm_tools_legacy_tool: request=<canonical-json>`
- `llm_tools_skill_result: tool=<...> mode=direct_reply|success|failure`
- `llm_tools_message_toolcall_saved: tool=<...> has_presentation=true|false`

This is much safer than trying to reconstruct the whole path indirectly from scattered logs.

### 4.3 Widen log collection or re-tag the important markers under `KernelAI`

Right now the harness misses MiniLM state entirely.

Recommendation:

- either:
  - make `read_logcat_all()` include `MiniLMIntentClassifier`, or
  - emit route-decision markers under `KernelAI`
- prefer the second option for long-term harness stability; the route decision is what the suite actually cares about

### 4.4 Make the llm_tools preflight explicit and fail-fast

Good preflight shape:

1. wake app
2. prove model stack ready
3. prove MiniLM ready
4. clear logs
5. start first llm_tools case

Do **not** reuse the current behavior of:

- warming with `what time is it`
- printing `timeout (proceeding anyway)`

For this suite, preflight failure should abort the run.

### 4.5 Keep the llm_tools goldens close to the harness, but do not create another duplicate corpus directory

Because `issue://1107` already calls out fixture drift risk, avoid a new `scripts/testdata/intent_recovery/...`-style duplicate.

Recommendation:

- keep the llm_tools goldens inline in `scripts/adb_skill_test.py`, or
- store one canonical file adjacent to the harness
- do not create a second corpus location for the same prompts

### 4.6 Use per-case fresh chat state

Minimum boring approach:

- start a new conversation before each llm_tools case
- then send the prompt
- then assert only against markers produced after that reset

That avoids contamination from:

- pending confirmations
- slot-fill
- weather follow-up inference
- anaphoric save-memory heuristics
- old tool chips still visible in the UI

### 4.7 Report the executed canonical request, not just pass/fail

If this suite fails, the useful debugging question will be “what exact tool request was executed?”

Recommendation:

- persist in the JSON report:
  - route markers seen
  - top-level tool name
  - canonical request JSON or request string
  - nested intent name (if gateway tool)
  - result mode
  - chip-observed marker
  - retry markers seen

The current `reply_warn` field is too weak for that job.

### 4.8 Prefer assertions on semantic field values, not raw formatting

Examples:

- assert `intent_name == set_timer`, not exact JSON spacing/order
- assert reminder payload contains `item`, `day`, `time`, not a specific key order
- assert weather request contains the expected city, not the entire request string byte-for-byte

That will survive harmless serialization differences between:

- native SDK tool calling
- legacy JSON fallback
- canonicalized request persistence

## 5. Anything else that could go wrong

### 5.1 The issue currently mixes two different deliverables: repo hygiene and new llm_tools behavior

That is manageable, but only if the acceptance text keeps them separate.

Recommendation:

- make repo hygiene a first checklist block
- make llm_tools behavior a second checklist block
- do not let missing cleanup hide a failed llm_tools implementation or vice versa

### 5.2 `docs/testing/automated-test-specification.md` already expects `tool_chip_shown`, but the app does not emit it

Observed code/doc mismatch:

- docs expect `tool_chip_shown:\s*intent=(\S+)` (`docs/testing/automated-test-specification.md:589-602,1545-1548`)
- no such marker exists in the app code today

Recommendation:

- either implement that marker as part of #1107, or
- update the acceptance text to use a different observability contract

### 5.3 If llm_tools is implemented inside the existing per-phase loop, dry-run output will become misleading unless the new assertions are surfaced there too

Current dry-run only prints:

- message
- expected intent
- optional `reply_contains`

That is not enough to review llm_tools cases.

Recommendation:

- if `--dry-run --phases llm_tools` is supported, print the llm-specific expectations too
- otherwise reviewers will not be able to sanity-check the corpus before running it on-device
