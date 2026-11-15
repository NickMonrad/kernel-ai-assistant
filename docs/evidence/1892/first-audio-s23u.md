# #1892 first-audio on S23U: main vs branch, length-independent timing

## Conclusion

Measured on the S23U with a 3–5 sentence reply, the branch starts speaking **3.4–4.7 s after generation completes**
(median 3.8 s). Main starts **12.7–17.3 s after generation completes** (median 15.7 s). The branch's first chunk is
queued (~1.1–1.2 s after generation completes) and is spoken about 2.2–3.5 s later. Main queues one whole-reply chunk
about 1.1–1.2 s after completion, and it plays 11.5–16.2 s later.

The branch did NOT speak before generation ended. Expected: `SpeakingStarted − lastToken` negative on the branch.
Measured: positive on both builds. The first chunk is queued after `lastToken`, so the sentence-level overlap the
change targets does not occur in these runs. Cause unverified [INFERENCE]: tokens reach `ChatViewModel` after the
engine logs Complete (consumer lag), or the first sentence is not complete until late in the stream. Not confirmed.

The win is real but smaller than a "speech before generation ends" design: the reply is spoken ~12 s sooner after
completion, because the branch synthesises sentence-sized chunks instead of one whole-reply chunk.

## Setup

- Device: S23U (`RFCW11265XH`, SM-S918B), unlocked, screen kept on. Voice: Settings > Voice shows "Cori · Ready"
  and "Cori is selected" before the run. Download was not tapped.
- MAIN: `origin/main` 6e0c34cd + VoiceTiming logs applied to a `git archive` export (not committed).
- BRANCH: `fix/chat-voice-chunking-1892` commit `ccd694b8` (VoiceTiming logs), on top of `6f6567c2`.
- Logs (debug build only for the first-queue line, `BuildConfig.DEBUG`):
  - `VoiceTiming lastToken epochMs=…`: engine Complete (`LiteRtInferenceEngine`). Not gated; one line per generation.
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
- `lastToken` is not gated behind debug (one line per generation in the engine).
- Earlier run (not used for this conclusion): with the wrong `firstChunkQueued` grep, the first measurement set
  (`lastToken` only) gave the same sign: `SpeakingStarted − lastToken` = +5.0 to +15.4 s on both builds.

## Earlier run (superseded)

An earlier alternating run on this device used a different prompt ("What is the capital of Samoa? …", 2 sentences) and
no queue log. Replies ranged 36–259 tokens, so send→SpeakingStarted mixed generation length with speech latency.
Median send→SpeakingStarted: MAIN 38344 ms, BRANCH 47364 ms. It is not used for the conclusion above.

Script: `measure-first-audio.sh` (token read on-device via `run-as`; no secrets committed).
