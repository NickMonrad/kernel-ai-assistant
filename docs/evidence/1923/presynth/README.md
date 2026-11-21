# #1923 first-sentence pre-synthesis: device evidence

Build under test: `eb35eb52d2` (`feat(#1923)`) merged with `origin/fix/1961-e2b-tool-prompt-length` (Room v60, `MIGRATION_59_60`), as test-only local builds. The base build is `8e294c41` plus the same merge. Both are `versionCode 1`, debug variant, not pushed. The merge is needed because the S21 app DB was already at Room v60 and the unmerged v59 build crashes on open (`A migration from 60 to 59 was required but not found`).

Scenario: "Tell me three short facts about kiwi birds, one sentence each." Voice turn on the Sherpa backend, warm app. Times are relative to `generate_called` (timeline zero) unless stated. `lastToken` is the last LiteRT token callback.

## Interleaved control: S21 (same session; thermal status 2 before every run, `interleave-s21-thermal.txt`)

Base and presynth alternated: base, presynth, base, presynth. Each capture has a warmup run, then one measured run (`logs/il-*-run1`).

| run | lastToken | SpeakingStarted | lastToken to speech | decode |
|---|---|---|---|---|
| base 1 | +18328 ms | +23554 ms | +5226 ms | 68 tok @ 7.80 tok/s |
| presynth 1 | +18541 ms | +18596 ms | +55 ms | 76 tok @ 8.74 tok/s |
| base 2 | +19946 ms | +24322 ms | +4376 ms | 81 tok @ 7.71 tok/s |
| presynth 2 | +17384 ms | +17436 ms | +52 ms | 74 tok @ 9.50 tok/s |

Speech start after generation: base +23.6 and +24.3 s; presynth +18.6 and +17.4 s. Presynth decode is not slower in this set.

## Capture sets, not interleaved: S21 and S23U

- **S21 capture** (`logs/capture-s21-*`, `timeline-s21-capture.txt`): base lastToken to speech +2.81 to +2.86 s; presynth +54 to +66 ms. Decode base 14.0 to 16.2 tok/s, presynth 7.6 to 8.6 tok/s. This set ran as a base block then a presynth block, with no thermal log. The interleaved control does not reproduce the decode gap, so the gap is attributed to thermal state (not confirmed by a log), not to pre-synthesis. The S21 capture set is kept as evidence, not as a decode comparison.
- **S23U capture** (`logs/capture-s23u-*`, `timeline-s23u-capture.txt`): base runs 2 and 3 lastToken to speech +2.05 and +3.17 s; presynth runs 1 to 3 +15 ms, +35 ms, and +2.37 s. Base run 1 is excluded: its log holds a tool-call turn (`load_skill`, `query_wikipedia`), not the kiwi reply. Decode base 16.3 and 16.0 tok/s (377 and 436 tokens); presynth 15.3, 14.9, and 14.9 tok/s (220, 319, and 645 tokens). Presynth decode is lower in this set, but the reply lengths differ and the sets are n=2 vs n=3, so it does not show whether pre-synthesis costs decode speed; the overlap probe below is the decode test.

Presynth run3 on S23U: the held first chunk (94 chars, about 5.7 s of audio) started synthesising at lastToken minus 1.4 s and took 3.7 s, so it was still running when the reply ended. Playback started 2.37 s after lastToken. The 0.5 s target holds when the first chunk's synthesis fits in the decode window; a long first sentence does not.

## Decode overlap: probe against none (presynth build)

`overlap.sh` runs all `none` runs first, then all `probe` runs, so the pairs are not back-to-back. Decode tok/s from the benchmark line:

| device | none (3) | probe (3) | probe vs none (medians) |
|---|---|---|---|
| S21 | 7.85, 9.11, 8.00 | 7.65, 7.92, 8.09 | -1.0% |
| S23U | 15.18, 14.03, 15.29 | 13.27, 12.95, 13.98 | -12.6% |

The S23U overlap decode counts (413 to 946 tokens) are much longer than the capture replies. Treat the S23U overlap rates as indicative only.

## Limits

- Capture sets have no thermal log. The S21 interleaved set has wrapper readings in `interleave-s21-thermal.txt` (status 2 before each of its four runs). The overlap runs log `thermal_status_at_start`; their gate is status 2 or lower.
- Overlap is not back-to-back, n=3 per arm.
- Tool-call turns: only `logs/capture-s23u-base-run1.log` contains a tool call (`load_skill`, `query_wikipedia`). It spoke (`SpeakingStarted chars=89`), so the base build does speak after a tool turn; that run is excluded from the S23U range above. The earlier statement that the tool reply path does not speak is withdrawn; no kept log supports it.
- `firstChunkQueued` is logged when `speakInSentenceChunks` confirms a chunk for playback. `timeline.py` now takes the first one after `engine_lastToken`; before that fix it took the first in the log, which in `logs/s21-il-il-pre-1-run1.log` (line 2, `chars=34`) is not reply speech. The untrimmed capture (not kept; the repo logs drop ActionsViewModel lines) shows `390.352 ActionsViewModel: FallThrough ... navigating to chat`, then `390.406 firstChunkQueued chars=34`, then `390.431 pauseTransientVoiceUi reason=navigateToChat`. That line has no `SpeakingStarted`; the same log later shows `ignoring SpeakingStarted after cancelled speech`. [INFERENCE] the pause cancels the speech job that logged it. The base capture has navigation at `179.915` and pause at `179.987` with no `firstChunkQueued` between them; its reply chunk follows at `204.192`. One run each, so this is an observed timing race in shared app code, not pre-synthesis behaviour.
- Not verified: S23U interleaved control; a device run of the retry path (unit test only: `a retry pass discards the presynthesised first sentence and speaks the retried reply`); a first sentence longer than the decode window.
- Tools: `docs/evidence/1923/timeline.py` (per-run timings) and `docs/evidence/1923/overlap.sh` (probe and none runs), both tracked at those paths. The `timeline-*.txt` files here were regenerated with the fixed `timeline.py`.
