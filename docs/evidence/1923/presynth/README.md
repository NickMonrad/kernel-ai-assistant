# #1923 first-sentence pre-synthesis: device evidence

Build under test: `eb35eb52d2` (`feat(#1923)`) merged with `origin/fix/1961-e2b-tool-prompt-length` (Room v60, `MIGRATION_59_60`), as test-only local builds. The base build is `8e294c41` plus the same merge. Both are `versionCode 1`, debug variant, not pushed. The merge is needed because the S21 app DB was already at Room v60 and the unmerged v59 build crashes on open (`A migration from 60 to 59 was required but not found`).

Scenario: "Tell me three short facts about kiwi birds, one sentence each." Voice turn on the Sherpa backend, warm app. Times are relative to `generate_called` (timeline zero) unless stated. `lastToken` is the last LiteRT token callback.

## Interleaved control: S21 (same session, thermal status 2 at the start of every run)

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
- **S23U capture** (`logs/capture-s23u-*`, `timeline-s23u-capture.txt`): base lastToken to speech +2.05, +3.17, +3.61 s. Presynth +15 ms, +35 ms, and +2.37 s. Decode base 11.5, 16.3, 16.0 tok/s; presynth 15.3, 14.9, 14.9 tok/s. Reply lengths vary (95 to 436 tokens base, 220 to 645 presynth), so decode rates are not strictly comparable.

Presynth run3 on S23U: the held first chunk (94 chars, about 5.7 s of audio) started synthesising at lastToken minus 1.4 s and took 3.7 s, so it was still running when the reply ended. Playback started 2.37 s after lastToken. The 0.5 s target holds when the first chunk's synthesis fits in the decode window; a long first sentence does not.

## Decode overlap: probe against none (presynth build)

`overlap.sh` runs all `none` runs first, then all `probe` runs, so the pairs are not back-to-back. Decode tok/s from the benchmark line:

| device | none (3) | probe (3) | probe vs none (medians) |
|---|---|---|---|
| S21 | 7.85, 9.11, 8.00 | 7.65, 7.92, 8.09 | -1.0% |
| S23U | 15.18, 14.03, 15.29 | 13.27, 12.95, 13.98 | -12.6% |

The S23U overlap decode counts (413 to 946 tokens) are much longer than the capture replies. Treat the S23U overlap rates as indicative only.

## Limits

- No thermal-status log on the capture sets; the overlap sets are gated at status 2 or lower.
- Overlap is not back-to-back, n=3 per arm.
- Tool-call turns are excluded. Two warmup turns called `get_weather`; the tool reply path does not speak in either build, as before this change.
- `firstChunkQueued` in `timeline.py` is logged when the held session is created, before generation starts. It is not the playback start.
- Not verified: S23U interleaved control, a retry-pass device run, and a first sentence longer than the decode window.
