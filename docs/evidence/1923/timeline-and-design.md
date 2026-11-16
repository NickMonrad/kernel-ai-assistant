# #1923 chat voice: why speech never overlaps generation, and a design note

Status: **timeline and design note only. Live speech is not shipped.** Under the scope note's rule
("speak only text confirmed not to precede a tool call"), speech cannot start before generation completes,
because the model is allowed to call a tool after any text it has streamed. Details and options below.

## Summary

1. **No token lag.** `ChatViewModel` collects each token 0–5 ms after `LiteRtInferenceEngine` emits it. Tokens are
   not buffered and not collected after Complete.
2. **Speech starts at finalize, by design.** Since #1915 round 5 the reply is spoken once, from the final text,
   when `GenerationResult.Complete` is handled. Nothing is queued during generation.
3. **The 1.1–1.2 s in the issue is gone on current main.** In the builds measured for #1892 (main 6e0c34cd,
   branch ccd694b8), the Complete handler persisted the reply and ran `ragRepository.indexMessage` for the user
   and assistant messages *before* `finalizeVoicePlaybackForResponse` (code order, both commits). #1915 round 4
   moved speech before persistence. Current main (c93200bb) queues the first chunk 86–124 ms after `lastToken`.
4. **What is left after `lastToken` is first-chunk synthesis.** Cori high takes ~2.0 s (S23U) and 2.2–2.5 s
   (S21) to synthesise the first ~40–57-character chunk. `lastToken→SpeakingStarted` is +2.1–2.2 s (S23U)
   and +2.3–2.6 s (S21).
5. **The visible answer is short.** With thinking on, E-4B streams visible text for only 2.9–4.8 s of a 24–55 s
   generation (S21 E-2B: 3.4–4.3 s). The first sentence is complete 0.5–1.6 s (S21: 0.65 s) after the first
   visible token.
6. **Even unrestricted live speech would not go negative on the S23U for these replies.** Speaking the first
   sentence as soon as it is complete would start speech −0.19 to −0.32 s from `lastToken` if synthesis ran at its
   idle speed. Measured with decode running, Cori synthesises ~30% slower on the S23U (real-time factor 0.79 vs
   0.60), which moves that to **+0.22 to +0.38 s**. On the S21 it would be −1.2 to −1.5 s.
7. **Decode slowdown with TTS overlapping generation:** S23U median −4.1% (6 pairs, −16.0% to +1.4%); S21 median
   −10.1% (7 pairs, −32.3% to +12.3%; its two faster, likely unthrottled pairs were −19% and −32%). Live speech
   overlaps only the visible-answer phase (3–5 s), so this adds ~0.1 s (S23U) and ~0.35 s (S21) to generation.
   Details in "Decode overlap".
8. **Recommendation: option A, synthesise ahead and play on confirmation.** Projected `lastToken→SpeakingStarted`
   +0.22 to +0.38 s (S23U) and ~0 s (S21), against +2.1 to +2.6 s today, and no unconfirmed text is ever audible.
   It cannot be negative, so it needs the owner to accept that change to Done-when bullet 2. See "Options".

## Timeline: current main (c93200bb), S23U

Device `RFCW11265XH` (SM-S918B, Android 16), Gemma-4 E-4B on GPU, thinking on, Sherpa Cori high (4 threads).
Prompt "Tell me three short facts about kiwi birds, one sentence each." through the debug harness route
(`quick_action_input` + `quick_action_is_voice` + self-generated `adb_harness_token`), app foregrounded, 60 s idle
after install, 1 warm-up + 3 measured runs. Times are ms after the turn's `generate_called`.

| Event (log line) | Run 1 | Run 2 | Run 3 | Warm-up |
|---|---|---|---|---|
| Engine emits first visible token (`TTFT (Time to First Token)`) | 44503 | 23948 | 44036 | 50395 |
| ViewModel collects it (`TTFT_STAGE first_token`) | 44506 | 23951 | 44036 | 50400 |
| First sentence complete in the stream (rebuilt from `event_seq` callbacks) | 45316 | 24620 | 44565 | 52016 |
| Engine generation complete (`VoiceTiming lastToken`) | 47598 | 26868 | 46902 | 55214 |
| ViewModel handles Complete (`thinking_save`) | 47626 | 26894 | 46903 | 55214 |
| Finalize queues first chunk (`VoiceTiming firstChunkQueued`) | 47710 | 26961 | 46988 | 55338 |
| First chunk synthesised (`VoiceTiming synth … chunk=0`) | 49806 | 28992 | 49005 | 60248 |
| Playback starts (`SpeakingStarted`) | 49806 | 28993 | 49006 | 60249 |
| **lastToken → firstChunkQueued** | **+112** | **+93** | **+86** | **+124** |
| **lastToken → SpeakingStarted** | **+2208** | **+2125** | **+2104** | **+5035** |
| First chunk (chars / synth ms / audio ms) | 57 / 2096 / 3725 | 53 / 2031 / 3253 | 57 / 2016 / 3409 | 104 / 4909 / 6418 |
| Visible-answer phase (first visible token → lastToken) | 3095 | 2920 | 2866 | 4819 |
| Decode (tokens @ tok/s) | 649 @ 15.38 | 327 @ 15.52 | 678 @ 16.17 | 732 @ 15.03 |

Reading the chain for run 1: the engine streams the first visible token at 44.50 s; the ViewModel has it 3 ms later;
the first sentence ("Kia ora. Here are three short facts about the kiwi birds.") is complete at 45.32 s, 2.3 s
before generation ends; nothing is queued until Complete at 47.60 s; finalize queues it 0.11 s later; Sherpa takes
2.10 s to synthesise it; playback starts 2.21 s after generation completed.

## Timeline: current main (c93200bb), S21

Device `R5CR605B71K` (SM-G991B, Android 15), Gemma-4 E-2B on GPU (no thinking text in these replies), Sherpa
Cori high. Same prompt and protocol.

| Event | Run 1 | Run 2 | Run 3 | Warm-up |
|---|---|---|---|---|
| Engine emits first visible token | 5052 | 4775 | 4978 | 6957 |
| ViewModel collects it | 5053 | 4776 | 4978 | 6961 |
| First sentence complete in the stream | 5721 | 5449 | 5651 | 7604 |
| Engine generation complete (`lastToken`) | 9252 | 8963 | 9282 | 10348 |
| ViewModel handles Complete | 9256 | 8965 | 9286 | 10349 |
| Finalize queues first chunk | 9362 | 9074 | 9403 | 10530 |
| Playback starts (`SpeakingStarted`) | 11810 | 11246 | 11871 | 13326 |
| **lastToken → firstChunkQueued** | **+110** | **+111** | **+121** | **+182** |
| **lastToken → SpeakingStarted** | **+2558** | **+2283** | **+2589** | **+2978** |
| First chunk (chars / synth ms / audio ms) | 41 / 2447 / 2850 | 41 / 2170 / 2625 | 47 / 2466 / 2904 | 47 / 2790 / 2949 |
| Decode (tokens @ tok/s) | 72 @ 16.09 | 72 @ 16.34 | 78 @ 17.24 | 68 @ 15.29 |

## Why live speech cannot meet both Done-when and the scope note

The scope note allows speech only for text "confirmed not to precede a tool call". In this app that confirmation
exists only when the generation pass ends:

- Every chat turn registers the native tools (`ModelConfig.toolProvider` is set on every chat config;
  `LiteRtInferenceEngine.buildConversationConfig` passes it as `tools`), so the model can call a tool in any turn,
  including turns the router did not classify as tool queries. Observed in this run set: one kiwi prompt on the
  S23U (`llm_tools_route: result=fallthrough best_guess=null`, a plain-chat turn) called `query_wikipedia`
  (`logs/s23u-ab3-probe-run1.log`: `tool_call_received` at +23.4 s, continuation pass, answer at +72.2 s). In that
  run the call came straight after thinking, with no visible text before it; the grammar below allows text there.
- LiteRT-LM v0.19.1 (`litertlm = "0.19.1"`) constrains tool turns with the text-and/or-call grammar
  (`runtime/components/constrained_decoding/llg_fc_tool_calls.cc`, `kTextAndOrFunctionCalls`):
  `start: TEXT_CONTENT? function_block_opt`. Any amount of visible text may be followed by a tool call in the same
  pass. Without constrained decoding nothing stops it either.
- The engine streams that text as it is decoded (`emitVisibleToken`), then delivers the call as a separate message
  (`message.toolCalls`) and runs the tool. Whether a pass ends in a call is known only at the pass's `onDone`.
- After a tool response the continuation pass has the same property: the engine allows up to 25 calls
  (`RECURRING_TOOL_CALL_LIMIT`) and `KernelAIToolSet` accepts a further terminal call and overwrites the turn's
  terminal-tool state.

So text is confirmed only at the final pass's `onDone`, which is `lastToken`. Speech of confirmed text therefore
starts at `lastToken` or later: `lastToken→SpeakingStarted` cannot be negative. Holding a sentence until the next one
starts (#1915 round 3) is a heuristic, not a confirmation: a multi-sentence preamble before a call is still spoken
(the known gap #1915 round 3 listed before round 5 removed live speech).

## Options

**A. Synthesise ahead, play on confirmation (recommended).** During generation, open the speech session and queue
the first sentence (and the following ones) for *synthesis*, with playback held. At Complete, if the final spoken
text starts with the queued text, release playback and append the rest; otherwise cancel the session. Never audible
before confirmation, so every scope-note case is covered by construction:
- pre-tool text: a native call (`tool_call_received` / terminal tool called) or a text `<|tool_call>` cancels the
  held session; nothing was audible;
- native tool with no follow-up text: same cancel; finalize speaks the tool's spoken summary as today;
- retries (blank, hallucination, raw tool call, system-only tool, incomplete chain): cancel the held session before
  the retry pass;
- `cancel()` must stop the session's own synthesis and emit no `SpeakingStopped` (Sherpa: close the chunk channel
  and end the worker without `markStarted`, which the gate already guarantees for a session that never played;
  Android TTS: drop the session's queued text; it never called `speak()`);
- mic re-arm: `awaitingVoicePlaybackCompletion` is set only when playback is released for the final reply;
- Home/stop (#1927): `suppressVoiceOutputForCurrentResponse` cancels the held session and blocks new ones for the turn;
- thinking text: only `GenerationResult.Token` text is queued, never `Thinking`.

Projected from the runs above, playback starts at max(Complete handled, first chunk synthesised), with synthesis
slowed by the measured contention (S23U ×1.32, S21 ×1.08) and `lastToken` pushed back by the measured decode
slowdown over the visible phase after the first sentence:

| | Today (measured) | A: synthesise ahead, play on confirmation | B: live speech |
|---|---|---|---|
| S23U runs 1–3 | +2.10 to +2.21 s | +0.22 to +0.38 s | +0.22 to +0.38 s |
| S23U warm-up (104-char first chunk) | +5.04 s | +3.13 s | +3.13 s |
| S21 runs 1–3 | +2.28 to +2.59 s | ~0.00 s (+2 to +4 ms) | −1.24 to −1.52 s |

(`lastToken→SpeakingStarted`. A and B are projections, not measurements.)

On the S23U both options are bound by first-chunk synthesis, so A loses nothing against B for these replies. On the
S21 A trails B by ~1.2–1.5 s. `lastToken→SpeakingStarted` is never negative under A, so Done-when bullet 2 would need
to read "about zero, or synthesis-bound" instead. Android TTS (system engine) cannot synthesise without playing, so
it gains nothing from A; it only needs the held-text path, which is today's behaviour.

**B. Live speech, accepting speculative audio.** Speak the first sentence when it is complete. Gains over A in these
runs: none on the S23U, 1.2–1.5 s on the S21. Cost: a sentence before a tool call or from a retried pass can be
audible and cannot be recalled; the case #1915's review rounds removed live speech over.

**C. Change the rule for plain chat only.** Live speech when the router did not classify the turn as a tool query,
A otherwise. Still speaks a pre-tool sentence when the model calls a tool in a plain-chat turn (one kiwi run here
called `query_wikipedia`). Needs an owner decision on that risk.

A is the only option that satisfies the scope note. It needs a held-playback session API in `:core:voice` (Sherpa:
hold the playback consumer on a release signal; Fallback/Inflect: pass through) and the ChatViewModel wiring above,
with unit tests per edge case (tool call after partial text, native tool with no follow-up, retry, cancel without
`SpeakingStopped`, Home mid-stream). Not started here, pending the owner's choice.

Cheaper levers that help today's path and any option: a shorter first chunk (the 40-char minimum merges "Kia ora."
into the next sentence; the warm-up's 104-char first chunk took 4.9 s to synthesise) and Sherpa thread count while
the LLM decodes (4 threads since #1951).

## Decode overlap

Method: the same voice prompt with and without the debug `SpeechProbeReceiver` speaking `probe.txt` (1277 chars of
made-up text) through the production chunked path, broadcast as soon as the turn logs `generate_called`. Sherpa
synthesis ran for 92–100% of every probe run's decode window (first engine callback → `lastToken`), and audio played
for 57–100% of it. This is the worst case: live speech or option A would overlap only the visible-answer phase.
Runs alternate in back-to-back pairs (none first in pairs 1–4, probe first in pairs 5–8). Metric: LiteRT's own
`decode=… tokens/s`. Excluded: one S23U pair whose turn called a tool (the benchmark covers only the post-tool pass)
and one S21 pair with a 17-token reply.

| Pair | S23U none → probe (tok/s) | Change | S21 none → probe (tok/s) | Change |
|---|---|---|---|---|
| 1 | 17.47 → 14.67 | −16.0% | 18.05 → 14.59 | −19.2% |
| 2 | 14.28 → 13.56 | −5.0% | 17.95 → 12.15 | −32.3% |
| 3 | (tool call, excluded) | | 10.00 → 10.25 | +2.5% |
| 4 | (incomplete, discarded) | | 9.96 → 9.02 | −9.5% |
| 5 | 16.64 → 13.99 | −16.0% | 8.18 → 9.18 | +12.3% |
| 6 | 13.37 → 13.55 | +1.4% | 9.79 → 8.80 | −10.1% |
| 7 | 14.64 → 14.23 | −2.9% | 10.01 → 8.05 | −19.5% |
| 8 | 13.64 → 13.21 | −3.1% | (17-token reply, excluded) | |
| **Median** | | **−4.1%** (n=6) | | **−10.1%** (n=7) |

TTS under contention (Cori high, real-time factor = synthesis ms / audio ms): S23U 0.79 during decode (41 chunks)
vs 0.60 after generation (19 chunks), i.e. ~32% slower. S21 1.05 vs 0.97 (~8% slower).

Caveats:
- Thermal: both phones were at thermal status 3 (severe) when checked after pair 4 and stayed at 2 at rest on USB
  power with the screen on; pairs 6–8 started at status 1–2 (logged in each run). The S21 decode fell from ~18 to
  ~8–10 tok/s after pair 2 and stayed there (throttling is the likely cause; status was not logged for pairs 1–5).
  Its two faster pairs show the largest slowdown (−19%, −32%), around the issue's ~13% for the S21.
- The kiwi reply on the S21 is short (47–82 decode tokens), so its per-run rates are noisy.
- n is small and the ranges are wide; the medians are directional.

Is it large? Not for this decision. The overlap lasts only the visible-answer phase, so a −4% (S23U) or −10% (S21)
decode costs ~0.1 s or ~0.35 s of generation time, and it does not delay speech that has already started. The larger
effect is on the TTS side: on the S23U, decoding slows synthesis by ~30%, which is why live speech cannot beat
option A there. If the S21's −19% to −32% (its two faster pairs) holds up on longer replies, the trade-off to
consider is Sherpa threads during generation (fewer threads: less decode slowdown, slower first chunk), not dropping
the overlap.

## Method and files

- `capture.sh SERIAL APK LABEL RUNS [PROMPT]`: install, 60 s idle, then RUNS voice prompts through the harness
  route (first is a warm-up), saving the app's `logcat -v epoch` per run. Hold the phone's lock first
  (`.docs/agents/risk-based-evidence-policy.md`).
- `overlap.sh SERIAL LABEL RUNS none|probe [PROMPT]`: the same prompt with or without the debug
  `SpeechProbeReceiver` speaking `probe.txt` (made-up text) from `generate_called` onward.
- `timeline.py LOG…`: the timeline rows above. `overlap.py LOG…`: decode speed and synthesis/playback overlap.
- `trim.py IN OUT`: keeps the Jandal process's timing lines only; thinking text is replaced by its length.
- `logs/`: trimmed logs of every run in the tables (`timeline.py` and `overlap.py` give the same numbers on them).
  Before-runs: `s23u-before-*`, `s21-before-*`. Overlap pairs: `*-abN-none|probe-*`. Prompt for the S21 pairs: "Tell
  me ten short facts about kiwi birds, one sentence each." (the model still answered with three facts).
- APK: debug build of c93200bb (current main), installed on both phones for these runs.
- Volume: media 1 during runs, restored afterwards (S23U 11, S21 2). S21 stay-awake set to `usb` for the runs and
  restored to off. No sleep/power keys. Harness token and probe file deleted after each script.
