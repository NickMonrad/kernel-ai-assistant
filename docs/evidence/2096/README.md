# #2096 evidence: Inflect Micro reply chunking on S23U

Tier: Medium (per `.docs/agents/risk-based-evidence-policy.md`; voice output path, same harness as #2088). Refs #2096.

## Scope

Synthesis timing of Inflect Micro v2 before and after reply chunking (≤120-char speech chunks, lazy per-chunk phonemization, cancellable ORT decode). Same debug harness, corpus, device, voice, and repeats as `docs/evidence/2088`. Not measured: AudioTrack/first-audio latency on the device, audible quality, the fallback controller path, chat-model-resident memory.

## Corpus and method

- Corpus: `docs/evidence/2088/corpus.tsv`, sha256 `d3f0422e…3bbc` (8 samples × 3 passes = 24 rows per run).
- Device: S23U `RFCW11265XH` (SM-S918B, Android 16). Package `ai.jandal.assistant.debug`.
- Lane: `inflect-micro-v2`, `InflectMicroDebugLane`. After this change it runs the same sequence as the controller: `normalize` → `phonemizeSpeechChunks` (lazy per chunk) → `synthesize` per chunk. `frontend_ms` is the sum of per-chunk phonemize time plus normalize. Edge fades are not timed.
- Voice: `CoriHigh`. ORT 1.24.3 CPU, default `SessionOptions`.
- Each run: force-stop, `MainActivity` start, `logcat -c`, one broadcast to `DebugTtsBaselineReceiver` with `repeats=3`.
- Build caveat: both JSONs report `git_sha` `f561e346`, which is the HEAD commit. The working tree carries these changes uncommitted. The `build_timestamp` differs (before `21:20`, after `21:27`), and the APK installed for "after" was built from the changed tree.

## Results

| Run | RTF median | RTF max | first_chunk median (ms) | first_chunk max (ms) | cancel return (ms) | VmHWM after init (KB) | VmHWM end (KB) |
|---|---|---|---|---|---|---|---|
| before-2096-run1 (pre-change) | 0.2352 | 0.2493 | 2477 | 6798 | 5820.5 | 401,192 | 1,029,924 |
| after-2096-run1 (chunked) | 0.2344 | 0.2485 | 1086 | 1503 | 0.75 | 410,160 | 502,860 |

- `first_chunk_ms` = frontend + first-chunk synth, from the harness's own clock.
- `cancel return` = time from `cancel()` to `CancellationException` on the worker thread, for the longest sample cancelled 1000 ms in. Before: the in-flight whole-reply decode finished first. After: `RunOptions.setTerminate` aborts the decode.
- Recovery after cancel: ok in both runs (short sample re-synthesised, RTF 0.2452 in both).
- All 24 rows finite in both runs.
- Per-row (after, pass 1, sample 01): 2 chunks, `first_chunk_ms` 482, `chunk_synth_ms` [444, 1412], RTF 0.2361.

## Findings

- **First audio.** Median first-chunk latency fell from 2477 ms to 1086 ms (−56%); worst case from 6798 ms to 1503 ms. This is the synth-plus-frontend time to the first chunk. It excludes AudioTrack, so it is not a first-audio measurement.
- **Throughput.** RTF is unchanged within noise (median 0.2352 → 0.2344). Chunking adds no measurable compute overhead.
- **Cancel.** Cancel return dropped from ~5.8 s to <1 ms. Stop now aborts the native decode rather than waiting for it.
- **Memory.** Peak VmHWM at end fell from ~1.03 GB to ~0.50 GB. Before, the single long-reply decode set the peak. [INFERENCE] The smaller per-chunk decodes size the ORT arena smaller. Not attributed: a run with `setCPUArenaAllocator(false)` / `setMemoryPatternOptimization(false)` would separate the two effects; not run.
- **Chunk size.** 120 chars chosen before measurement, sentence-first packing with clause and word fallbacks. Measured first-chunk median 1086 ms is consistent with that choice; no tuning run was done.

## Deviations and gaps

- Not measured: AudioTrack first-audio latency, playback smoothness, audible join artefacts. The 5 ms edge fades (`fadeChunkEdges`) are unverified by ear. [INFERENCE] They could soften plosive onsets; the risk is untested.
- Not measured: `FallbackVoiceOutputController` with an Inflect stream. Inflect's streaming session does not implement `ReportsStreamPlaybackOutcome`, and does not override `openPreSynthesisedSession`.
- Synthesis failure now returns Unavailable for the whole reply. If audio has already started, the fallback may repeat speech. Not exercised on device.
- Single run per side, three passes each. Same device, firmware, and voice as #2088.
- Device left with `stay_on_while_plugged_in=2` (USB). The pre-session value was not captured, so it has not been restored.

## Files

- `before-2096-run1.json`: schema `debug-tts-baseline/3`, pre-change tree.
- `after-2096-run1.json`: schema `debug-tts-baseline/3`, chunked tree.
