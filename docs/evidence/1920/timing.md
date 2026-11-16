# #1920 device evidence: conversation rebuilds per chat voice command

## Result

Release-like builds, chat-path voice command (Actions fallthrough → chat), warm model, medians with range.
t0 = the command's `am start`. Every rebuild happens between t0 and `generate()`.

**S23U** (`RFCW11265XH`, Gemma-4 E-4B on GPU, thinking on), 6 measured commands per build and query:

| Query | Build | Rebuilds | t0 → accepted (ms) | accepted → `generate()` (ms) | t0 → `generate()` (ms) | t0 → first model text (ms) | t0 → first answer token (ms) | thinking chars |
|---|---|---|---|---|---|---|---|---|
| tool (weather) | BEFORE | 5 (5–5) | 3306 (3268–3391) | 4804 (4701–4938) | 8104 (8085–8206) | 15518 (14892–16165) | 45630 (33798–56891) | 1272 (508–1900) |
| tool (weather) | AFTER | 1 (1–1) | 158 (150–190) | 1650 (1621–1767) | 1808 (1777–1930) | 9106 (8667–10206) | 34619 (27349–42354) | 1086 (759–1259) |
| chat | BEFORE | 5 (5–5) | 3305 (3265–3400) | 5438 (5289–5602) | 8762 (8568–8919) | 15270 (15056–16347) | 30010 (25567–33184) | 826 (623–873) |
| chat | AFTER (n=5) | 1 (1–1) | 145 (130–166) | 2087 (2072–2168) | 2217 (2213–2334) | 8631 (8385–8692) | 26420 (19812–37516) | 941 (720–1519) |

- Rebuilds per voice command: 5 → 1 on every command. `createConversation` takes 1554 ms median on BEFORE
  (1482–1730, n=48) and 1518 ms on AFTER (1496–1650, n=11), so four removed rebuilds are about 6.2 s.
- t0 → `generate()` drops by 6.3 s (tool) and 6.5 s (chat), with ranges under 0.4 s; t0 → first model text drops by
  6.4 s and 6.6 s. Both are thinking-independent, and LiteRT's prefill TTFT is unchanged (7.2 vs 7.1 s tool,
  6.4 vs 6.2 s chat).
- t0 → first answer token adds thinking and, for the tool query, the weather fetch. Its medians fall by 11.0 s (tool)
  and 3.6 s (chat), but the ranges overlap; the rebuild saving is the 6.3–6.5 s above.
- AFTER chat has n=5: in `AFTER-c2-chat-r1` the model ended after thinking with no answer text, and the blank-reply
  retry (#841) ran a second generation; the run has no first token in the capture window.

**S21** (`R5CR605B71K`, Gemma-4 E-2B on GPU, thinking off in that install's settings), chat query, 9 measured
commands per build:

| Query | Build | Rebuilds | t0 → accepted (ms) | accepted → `generate()` (ms) | t0 → `generate()` (ms) | t0 → first answer token (ms) |
|---|---|---|---|---|---|---|
| chat | BEFORE | 5 (5–5) | 7748 (5133–8066) | 14908 (8333–15216) | 22694 (13494–23282) | 31705 (18507–32443) |
| chat | AFTER | 1 (1–1) | 341 (319–410) | 5403 (5134–5656) | 5784 (5453–6066) | 15095 (14352–15552) |

- `createConversation` on the S21 took about 2.3 s in the first BEFORE cycle and about 4.1 s in every later cycle of both
  builds (the phone warmed up), which is why BEFORE has two clusters. In either regime the four removed rebuilds are
  9–16 s.
- The tool query fails on the S21 on both builds before any rebuild question arises: LiteRT rejects the prompt with
  `Input token ids are too long` (E-2B with that install's context setting). It was not measured there.

A command whose conversation is still clean and whose prompt is unchanged does no rebuild: in a probe after a
quick-routed command (no model turn), the next chat-path command logged no `CONV_REBUILD` and a 0 ms prompt install.

## What rebuilds, from code

`shouldForceGpuConversationResetEachTurn` (`ChatViewModel.kt`) is `GPU && (!BuildConfig.DEBUG || debugSwitch)`. The debug
switch is a per-ViewModel `MutableStateFlow(false)`, so every new chat starts with it off. A debug build therefore never
runs the per-turn GPU history replay (#684/#1089) for a voice command; a release build on GPU runs it on every turn. The
builder's claim is correct; it is why the first round's debug-build timing could not show the effect.

A voice command reaches chat as Actions fallthrough → new chat route with `initialQuery` → `submitInitialQueryIfNeeded`
(independent command). Each engine call below creates a new native conversation on `main`:

| # | Call | When | `main`, release GPU | this PR |
|---|---|---|---|---|
| 1 | `resetInferenceForFreshConversation` → `updateSystemPrompt(FULL)` | chat open | rebuild | deferred: no engine call |
| 2 | `initEngineWhenReady` warm path → `updateSystemPrompt(FULL)` | chat open | rebuild | deferred |
| 3 | persona collector, first emission → `updateSystemPrompt(FULL)` | chat open | rebuild | deferred |
| 4 | independent-command `resetConversation()` | turn start | rebuild | skipped: the replay is the reset |
| 5 | history replay → `updateSystemPrompt(MINIMAL or FULL prompt)` | before `generate()` | rebuild | the one rebuild |

Debug builds on `main` skip #5, which matches the four rebuilds in the issue. On this PR's debug path (no replay) the
deferred FULL install runs at #4 instead, so it is also one rebuild. The engine still rebuilds whenever the conversation
has been used or the prompt/config changed, so persona, profile, history and tool changes are never skipped; the unit
tests cover a persona change during the deferral.

## Builds

- BEFORE: `origin/main` `3bf3a968` plus a logging-only patch that logs `CONV_REBUILD reason=…` before each of the three
  `createConversation` rebuild sites in `LiteRtInferenceEngine` (no behaviour change).
- AFTER: this branch at `b4d66412` (code identical to the pushed head; later commits touch only this folder).
- Both release-like, built with a local Gradle init script that is not committed: every Android library module's debug
  build type is non-debuggable, so `BuildConfig.DEBUG` is `false` in `:feature:chat` and `:core:inference` and the release
  paths run (per-turn GPU replay, no debug-only engine logging); `:app` is R8-minified with the release ProGuard files.
  `:app` stays debuggable and debug-signed with the `.debug` id: `run-as` (harness token) and the app's
  `BuildConfig.DEBUG` gate on the harness need it, and the release id `ai.jandal.assistant` is the owner's installed
  build. No upload key was used. The normal debug build of this branch was reinstalled afterwards.

## Method

`measure-first-token.sh` (this folder), one phone at a time per lock:

- Device lock protocol from `.docs/agents/risk-based-evidence-policy.md`: `mkdir` lock plus owner file, bounded 25-minute
  wait, released only by its owner. No other agent held either phone lock; screen awake and unlocked; media volume 1,
  restored afterwards; Brisbane daytime.
- Builds alternate ABBA over 3 install cycles. After each install: launch, 60 s idle, one discarded warm-up command per
  query (the first command after an install also loads the model), then the measured commands. 20 s idle between
  commands; `dumpsys thermalservice` status and battery temperature are recorded per command.
- Commands: the debug harness route (`quick_action_input`, `quick_action_is_voice=true`, a fresh `adb_harness_token`
  written with `run-as`). Actions falls through to chat, as in the owner's 0.1.7 log.
  - tool: `Look up the weather in Apia this afternoon` (the daypart veto sends it past the weather quick route; MINIMAL
    tier; Gemma calls `get_weather`).
  - chat: `In one sentence, why is the sky blue?` (FULL tier with RAG).
- Model: S23U Gemma-4 E-4B on GPU; S21 Gemma-4 E-2B on GPU. Thinking stays at the app default; its length is reported.

Metrics (device clock, `parse_run.py`; t0 = `ActivityTaskManager START` of the command):

- `rebuilds`: `CONV_REBUILD` lines between t0 and `generate()`; `create_ms` per `createConversation`.
- `t0_to_gen`: t0 → `generate()` holds the generation lock. All rebuilds of a command sit inside this window, and it
  does not depend on thinking. `accepted_to_gen` is the part after chat accepts the command.
- `litert_ttft_ms`: LiteRT benchmark time to first token of the last prefill (prefill cost; thinking-independent).
- `t0_to_first_output`: t0 → first model text of any kind (first thinking text, or first answer token).
- `engine_ttft`, `t0_to_first_token`: to the first visible answer token; these include thinking (`thinking_chars`) and,
  for the tool query, the network weather fetch, so they carry the run-to-run noise.

Raw logcat stays on the host (it can contain prompt and reply text); only the parsed lines are committed:
`s23u-runs.txt`, `s21-runs.txt`. `summarize_runs.py` produces the tables above.

## Limits

- Release-like, not release: `:app` is debuggable (needed for the harness), and LeakCanary stays in the debug
  variant. Both builds share these conditions; the absolute numbers are not the Play build's.
- Thermal: commands ran back to back with 20 s gaps, so the S23U reached thermal status 2 for 22 of its 36 commands
  (status 0: 3, 1: 11) and the S21 status 1 for most (0: 2, 1: 18, 2: 4). ABBA ordering spreads this over both builds;
  the rebuild count and t0 → `generate()` ranges stay tight in every cycle.
- Not measured: time to speech. The spoken reply starts after the capture window closes (3 s after the generation
  completes), so `SpeakingStarted` is missing from most logs. The first answer token is the closest measured point.
- The owner's 41 s is from a Honor on 0.1.7; these phones and builds differ, so this evidence shows the removed
  rebuild time, not that exact figure.
- Each chat-path command opens a new chat in the debug app: 38 test chats on the S23U and 30 on the S21, titled with the
  two test queries. They were not deleted.
