# #1892 first-audio on S23U: main vs branch, length-independent timing

## Conclusion

Streamed path (kiwi prompt, n=3 per build, `path=stream` on every run): branch median lastToken→SpeakingStarted
is +2469 ms; main +5879 ms. The ranges overlap (MAIN 3211–7524 ms, BRANCH 1405–3258 ms), so this is directional
only, not a significant difference. See "Streamed-path re-measurement" below.

Caveat: these runs used cd619981, before the round-3 first-sentence hold (SF-A). The hold keeps the first sentence
until a second sentence starts, so first audio on streamed turns will be later than measured here. Not re-measured
on the final build; re-measure before quoting a number for it.

## Finalize-path result (secondary; router-classified prompt, not streamed)

The branch starts speaking 3.4–4.7 s after generation completes (median 3.8 s). Main starts 12.7–17.3 s after
completion (median 15.7 s). The branch's first chunk is queued ~1.1–1.2 s after completion and is spoken about
2.2–3.5 s later. Main queues one whole-reply chunk about 1.1–1.2 s after completion and plays it 11.5–16.2 s later.

The branch did NOT speak before generation ended. Expected: `SpeakingStarted − lastToken` negative on the branch.
Measured: positive on both builds. The first chunk is queued after `lastToken`, so the sentence-level overlap the
change targets does not occur in these runs. Cause unverified [INFERENCE]: tokens reach `ChatViewModel` after the
engine logs Complete (consumer lag), or the first sentence is not complete until late in the stream. Not confirmed.

## Setup

- Device: S23U (`RFCW11265XH`, SM-S918B), unlocked, screen kept on. Voice: Settings > Voice shows "Cori · Ready"
  and "Cori is selected" before the run. Download was not tapped.
- MAIN: `origin/main` 6e0c34cd + VoiceTiming logs applied to a `git archive` export (not committed).
- BRANCH: `fix/chat-voice-chunking-1892` commit `ccd694b8` (VoiceTiming logs), on top of `6f6567c2`.
- Logs (debug build only for the first-queue line, `BuildConfig.DEBUG`):
  - `VoiceTiming lastToken epochMs=…`: engine Complete (`LiteRtInferenceEngine`). Gated by `BuildConfig.DEBUG`; one line per generation.
  - `VoiceTiming: firstChunkQueued epochMs=… chars=…`: first chat speech chunk queued. Branch: first `speakVoiceChunk`
    / finalize tail / sentence-loop chunk of the turn. Main: the single `finalChunk` at finalize.
  - `KernelAI: SpeakingStarted epochMs=…` (existing).
  - `decode=… tokens @ … tokens/s` (existing, `logLiteRtBenchmarkInfo`). Same generation as the run.
  - `KernelAI_TTS speaking_started … text=…` with `log.tag.KernelAI_TTS DEBUG`, used to count sentences in what was
    spoken (count of `.`, `!`, `?` in the spoken text).
- Prompt: "Give me three short tips for a rainy day in Auckland, one sentence each."
- Protocol: install (`install -r`), 60 s idle, one unmeasured warm-up, then 3 measured runs per build, alternating
  MAIN, BRANCH, MAIN, BRANCH, MAIN, BRANCH. Media volume 1 during runs, restored to 11. Harness token created on-device and removed.
- Terms: `lastToken→SpeakingStarted = SpeakingStarted − lastToken`; `firstChunkQueued→SpeakingStarted =
  SpeakingStarted − firstChunkQueued`. Negative means speech started before generation completed.

## Per-run results (measured runs only)

| Run | lastToken→SpeakingStarted (ms) | firstChunkQueued→SpeakingStarted (ms) | firstChunkQueued − lastToken (ms) | chars queued first | sentences spoken | decode tok/s (same run) |
|---|---|---|---|---|---|---|
| MAIN 1 | +15719 | +14583 | +1136 | 272 | 3 | 10.44 |
| BRANCH 1 | +3409 | +2239 | +1170 | 48 | 4 | 11.65 |
| MAIN 2 | +12696 | +11536 | +1160 | 213 | 3 | 10.35 |
| BRANCH 2 | +3763 | +2582 | +1181 | 54 | 3 | 11.42 |
| MAIN 3 | +17344 | +16176 | +1168 | 296 | 5 | 10.46 |
| BRANCH 3 | +4660 | +3517 | +1143 | 64 | 3 | 9.48 |

Summary (medians of 3):
- lastToken→SpeakingStarted: MAIN +15719 ms, BRANCH +3763 ms.
- firstChunkQueued→SpeakingStarted: MAIN +14583 ms, BRANCH +2582 ms.
- decode tok/s: MAIN mean 10.42, BRANCH mean 10.85. Decode is comparable, so the timing gap is not explained by decode speed.

Warm-up runs were executed and excluded; their values are in `/tmp/s23u-timing-1892.txt` (not committed).

## Caveats

- Sentence counts are terminators in the spoken text; "e.g.", numbered items, or merged chunks can shift them by one.
- Three runs per build. Spread is ~3 s on the branch and ~3.5 s on main; the gap (~12 s) is much larger than the spread.
- The first-queue line is logged for the first chunk only (branch via `activeVoiceChunksSent == 0`; main once).
- `lastToken` is gated behind `BuildConfig.DEBUG` like the first-queue line; the measured runs used the debug build.
- Earlier run (not used for this conclusion): with the wrong `firstChunkQueued` grep, the first measurement set
  (`lastToken` only) gave the same sign: `SpeakingStarted − lastToken` = +5.0 to +15.4 s on both builds.

## Earlier run (superseded)

An earlier alternating run on this device used a different prompt ("What is the capital of Samoa? …", 2 sentences) and
no queue log. Replies ranged 36–259 tokens, so send→SpeakingStarted mixed generation length with speech latency.
Median send→SpeakingStarted: MAIN 38344 ms, BRANCH 47364 ms. It is not used for the conclusion above.

Script: `measure-first-audio.sh` (token read on-device via `run-as`; no secrets committed).

## Streamed-path re-measurement (path=stream, kiwi prompt)

Prompt: "Tell me three short facts about kiwi birds, one sentence each." (non-router). Alternating MAIN (origin/main 67a2365c + path/timing logs) / BRANCH (cd619981), 1 warm-up plus 3 measured runs per build, 60 s idle after each install. Both builds logged `VoiceTiming path=stream` on every run, so both exercise streaming. Script: `/tmp/s23u-stream-1892.sh` (not committed; same logic as `measure-first-audio.sh`).

| Build | Run | lastToken→SpeakingStarted (ms) | firstChunkQueued→SpeakingStarted (ms) | decode tok/s |
|---|---|---|---|---|
| MAIN | 1 | +7524 | +5539 | 16.60 |
| MAIN | 2 | +5879 | +3441 | 15.46 |
| MAIN | 3 | +3211 | +586 | 13.08 |
| BRANCH | 1 | +1405 | +4511 | 14.21 |
| BRANCH | 2 | +3258 | +5044 | 12.20 |
| BRANCH | 3 | +2469 | +3530 | 19.72 |

Medians (n=3): lastToken→SpeakingStarted MAIN +5879 ms, BRANCH +2469 ms. firstChunkQueued→SpeakingStarted MAIN +3441 ms, BRANCH +4511 ms.

Caveats:
- n=3 per build, and the ranges overlap (MAIN 3211–7524, BRANCH 1405–3258 for lastToken). No significant difference is shown.
- On MAIN, `fqChars=0` on every run: the logged first-chunk line is the empty finalize tail, so MAIN's firstChunkQueued is not a real first chunk. Use the lastToken column for MAIN.
- Warm-up runs (excluded): MAIN +5483/+5839/+3453, BRANCH +1325/+4692/+6563 ms.
- Earlier finalize-path table above is a different path (router-classified prompt); it is not comparable to this table.
- Cori Ready in Settings was not checked by navigation; speech was produced through the engine on every run (SpeakingStarted logged).

## Round 5: live streaming removed (finalize-only)

Speech now starts only after generation completes, from the final reply text, split at sentence ends. The streamed-path numbers above (`path=stream`, cd619981) describe the removed live path and are superseded; they are not the current design.

The finalize-path numbers in "Finalize-path result" are the current evidence: median 3.8 s from generation complete to speech on the branch (range 3.4–4.7 s) vs 15.7 s on main (range 12.7–17.3 s), S23U, router-classified prompt.

Tradeoff: a native tool turn speaks the tool result text (`toolCall.resultText`), not the model's wording after the call. Live speech during generation is tracked as #1923 (build 7).

## Round 6: plain-chat finalize timing (009c88d1)

S23U (`RFCW11265XH`), kiwi prompt (non-router, plain chat), `lastToken epochMs` (generation end) to `SpeakingStarted epochMs`. One warm-up and 3 measured runs per build, alternating installs. Script: `measure-first-audio.sh` (this directory). Device awake and unlocked; no sleep or power keys.

| Build | Run | lastToken→SpeakingStarted (ms) |
|---|---|---|
| MAIN (timing-instrumented main, `/tmp/apk-main-timing.apk`) | warm-up | 7313 |
| MAIN | 1 | 3016 |
| MAIN | 2 | 8368 |
| MAIN | 3 | 8798 |
| HEAD (009c88d1) | warm-up | 4371 |
| HEAD | 1 | 3441 |
| HEAD | 2 | 5627 |
| HEAD | 3 | 8503 |

Measured medians (n=3): MAIN 8368 ms (range 3016–8798); HEAD 5627 ms (range 3441–8503). The ranges overlap, so this is directional only. HEAD was not slower in this session. An earlier HEAD-only session on the same build gave 4276, 6024, 4010, 3440 ms.

Caveats:
- The MAIN build is the timing-instrumented main from before round 5 (`/tmp/apk-main-timing.apk`). Its base commit was not re-verified against current origin/main (`7d36a777`). The plain `apk-main-6e0c34cd.apk` has no `lastToken` log, so it cannot be measured on this anchor.
- Intent delivery warned "Activity not started, delivered to running instance" on each run. A SpeakingStarted was logged on every run, so speech ran in each case.
- The `Finalize-path result` section above (3.8 s vs 15.7 s) is from an earlier HEAD build and is not re-measured here.
- The round 5 native-tool tradeoff (speaks the tool result, not the answer) is narrowed. Only native model-answer turns (the reply is the model's text after the terminal tool call, and the bubble shows that text) speak the post-call answer; the spoken summary is the fallback when that text is blank. Direct replies, failures, and card replies speak what the bubble shows, or the tool's spoken summary where main used it, so tool chips and evidence are never spoken.
