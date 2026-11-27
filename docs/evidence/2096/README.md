# #2096 evidence: Inflect Micro reply chunking on S23U

Tier: Medium (per `.docs/agents/risk-based-evidence-policy.md`; voice output path, same harness as #2088). Refs #2096.

## Scope

Synthesis timing of Inflect Micro v2 before and after reply chunking (speech chunks target ≤120 characters; a single word longer than 120 is kept whole as its own chunk; lazy per-chunk phonemization; cancellable ORT decode). Same debug harness, corpus, device, voice, and repeats as `docs/evidence/2088`. Not measured: AudioTrack/first-audio latency on the device, audible quality, the fallback controller path, chat-model-resident memory.

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
- `cancel return` = time from `cancel()` to `CancellationException` on the worker thread, for the longest sample cancelled 1000 ms in. Single probe per run: one sample, not a distribution. Before: the in-flight whole-reply decode finished first. After: `RunOptions.setTerminate` aborts the decode.
- Recovery after cancel: ok in both runs (short sample re-synthesised, RTF 0.2452 in both).
- All 24 rows finite in both runs.
- Per-row (after, pass 1, sample 01): 2 chunks, `first_chunk_ms` 482, `chunk_synth_ms` [444, 1412], RTF 0.2361.

## Findings

- **First audio.** Median first-chunk latency fell from 2477 ms to 1086 ms (−56%); worst case from 6798 ms to 1503 ms. This is the synth-plus-frontend time to the first chunk. It excludes AudioTrack, so it is not a first-audio measurement.
- **Throughput.** RTF is unchanged within noise (median 0.2352 → 0.2344). Chunking adds no measurable compute overhead.
- **Cancel.** Cancel return dropped from ~5.8 s to <1 ms. Stop now aborts the native decode rather than waiting for it.
- **Memory.** Peak VmHWM at end fell from ~1.03 GB to ~0.50 GB. Before, the single long-reply decode set the peak. [INFERENCE] The smaller per-chunk decodes size the ORT arena smaller. Not attributed: a run with `setCPUArenaAllocator(false)` / `setMemoryPatternOptimization(false)` would separate the two effects; not run.
- **Chunk size.** Target 120 chars chosen before measurement, sentence-first packing with clause and word fallbacks. A single word longer than 120 chars is emitted whole (`wordPieces`), so the limit is a target, not a hard cap. Measured first-chunk median 1086 ms is consistent with that choice; no tuning run was done.

## Deviations and gaps

- Not measured: AudioTrack first-audio latency, playback smoothness, audible join artefacts. The 5 ms edge fades (`fadeChunkEdges`) are unverified by ear. [INFERENCE] They could soften plosive onsets; the risk is untested.
- Not measured: `FallbackVoiceOutputController` with an Inflect stream. Inflect's streaming session does not implement `ReportsStreamPlaybackOutcome`, and does not override `openPreSynthesisedSession`.
- Synthesis failure: a chunk failure skips the rest of that append (logged, not spoken) and playback continues with later appends. Unavailable is returned only when nothing has been queued, so Android TTS speaks the buffered text once. Unit-tested via `InflectReplyStreamTest` and `FallbackVoiceOutputControllerTest`; not exercised on device.
- Single run per side, three passes each. Same device, firmware, and voice as #2088.
- Device left with `stay_on_while_plugged_in=2` (USB). The pre-session value was not captured, so it has not been restored.

## Files

- `before-2096-run1.json`: schema `debug-tts-baseline/3`, pre-change Inflect tree. It already contains the per-row `vmhwm_kb` field: the harness added it before this baseline run, so the Inflect change is the only difference between the two files.
- `after-2096-run1.json`: schema `debug-tts-baseline/3`, chunked tree.

## S21 playback check (R5CR605B71K, Exynos)

Tier: second test device for a voice-output change (`.docs/agents/review-gates-voice.md`). Done on the S21 only; the S23U was not connected for this step.

- Build: PR debug APK plus a temporary timing log (`KernelAI2096` tag: `stop`, `chunk N synth-start`, `chunk N write-start/end`) in `InflectMicroVoiceOutputController`. The timing log is not in the PR; the file was reverted to the committed version after the run.
- Setup: device lock `/tmp/jandal-s21.lock` held and released by this run. Inflect Micro downloaded in Settings → Voice and selected. Media volume left at 2/15 (not changed). `stay_on_while_plugged_in` left at 2 (not changed).
- Reply: a multi-sentence answer to an ocean-tides question, spoken via the message's Speak control. Three chunks per reply.

Run 1 (speak tap at 19:39:09.812, clean reply):

```
09.812 stop (openUtterance)     09.898 chunk 0 synth-start    11.579 chunk 1 synth-start
11.776 chunk 0 write-start      13.288 chunk 2 synth-start    17.996 chunk 0 write-end / chunk 1 write-start
23.218 chunk 1 write-end / chunk 2 write-start                29.757 chunk 2 write-end
```

First audio: 1.96 s after the tap. Chunk 1 started exactly when chunk 0 ended, and chunk 2 exactly when chunk 1 ended (no gap in the logged timestamps). Synthesis of later chunks overlapped playback.

Run 2 (Stop test, full log in `s21-playback-stop-run.log`):

- Speak at 19:39:48.983. Chunk 0 write-start at 52.004 (3.02 s after the tap; chunk 0 synth took 2.94 s in this run vs 1.88 s in run 1).
- Stop tapped during chunk 1 playback. `stop` logged at 00.597; the in-flight chunk-1 write returned at 00.598. No chunk 2 write started; chunk 2 had been synthesised but was discarded.

Not verified on the S21 in this step:
- Audible gaps or repeats: the log shows write timing, not sound. Listening check still needed.
- Audible silence on Stop: the write unblocking within 1 ms is logged, not heard.
- First-audio latency on the S23U: the S23U was not connected for this step, so only the S21 playback check was run.
- The S21 first-chunk time (1.96 s, 3.02 s) is not comparable with the S23U harness numbers above (different SoC, and the harness excludes AudioTrack).

Listening run (S23U playback, S21 mic): three long replies played, two takes recorded; audio kept outside git in the private fixtures folder, no audio committed here. Agent did not listen; only silence-detection pause timing was measured. Mid-reply Stop for that run was not done; the review-fix build's Stop runs are below.

## Review-fix build on S23U (`37716f9170`)

Build: `37716f9170` (HEAD of `fix/2096-inflect-long-reply-chunks`), debug APK built from that commit, no timing patch. Device: S23U `RFCW11265XH`, debug package `ai.jandal.assistant.debug`, lastUpdateTime 2026-11-26 17:34:22. Lock `/tmp/jandal-s23u.lock` owned by `claude-2096-review2`, released after the run. `stay_on_while_plugged_in` was 2 before and after. Media volume 4/15, not changed. Voice: Inflect Micro, Downloaded, selected under Voice → Speaking.

Reply: one ~1,300-character answer about tides, spoken via the message's Speak control. It contained 1234567 km, 98765432 people and 3.14159; the model wrote "3.1459" in its text. Log line: `SpeakingStarted engine=inflect chars=1306`.

| Run | Speak tap (epoch s) | First sound (SpeakingStarted, epoch s) | Tap to first sound | Stop tap (epoch s) | Stop after first sound | Audio focus |
|---|---|---|---|---|---|---|
| 1 | 1795679072.107 | 1795679072.799 | 0.69 s | 1795679089.021 | ~16.2 s | not captured |
| 2 | 1795679103.079 | 1795679103.301 | 0.22 s | 1795679106.300 | ~3.0 s | request 17:45:03.301, abandon 17:45:06.296 |
| 3 | not logged | 1795679114.940 | n/a | 1795679120.918 | ~6.0 s | stack shows ai.jandal (USAGE_ASSISTANT/CONTENT_TYPE_SPEECH, GAIN_TRANSIENT_MAY_DUCK) at +6 s; no ai.jandal entry after Stop; abandon 17:45:20.939 |
| 4 (natural finish) | 1795679134.063 | 1795679134.281 | 0.22 s | none | none | request 17:45:34.280, abandon 17:47:16.229 (~102 s, reply finished) |

- Three mid-reply Stops (runs 1–3). After each Stop the UI returned to idle; the next Speak tap started a new reply (runs 2→3→4).
- Audio focus is requested with `USAGE_ASSISTANT/CONTENT_TYPE_SPEECH` and abandoned on Stop and on normal completion.
- Tap to first sound measures the adb tap timestamp to the `SpeakingStarted` log, which is written when the track opens, before the first write. It includes adb dispatch overhead and does not measure the sound leaving the speaker.

Not measured or not verified on this build:
- Longest inter-chunk pause: not measured. The stock build logs no per-chunk write timing; the timing patch used on the S21 was not applied here, so no pause number is reported.
- Stop latency to silence, and the audible result of the numbers: not heard. Only UI state, log order and focus state were checked.
- Chunk-failure fallback on device: not exercised (unit-tested only).
