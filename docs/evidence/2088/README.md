# #2088 evidence: Inflect Micro baseline on S23U

Tier: Medium (harness improvement, per `.docs/agents/risk-based-evidence-policy.md`). Refs #2088.

## Scope

Inflect Micro v2 synthesis timing on a debug build, over the fixed 8-sample corpus. Not measured: AudioTrack/first-audio latency, chat-model-resident memory, Kokoro or Qwen3-TTS.

## Corpus

- `corpus.tsv`: `id<TAB>text`, 8 samples, generated from the frozen Drive manifest `~/GoogleDrive/Jandal/TTS/Inflect-launch-validation/2026-08-25-s23u-43f7e7c7/manifest.json` (`.corpus.samples[]`). The manifest is not in this repo; the TSV is its verbatim copy.
- sha256 `d3f0422e1aa3be629f708740d4f1498e3b751fe88b2a6ea16571c40c371d3bbc` (recorded in each JSON).

## Method

- Build: debug `ai.jandal.assistant.debug` 0.1.0.
- Lane: `InflectMicroDebugLane` (`lane` = `inflect-micro-v2`) runs the production sequence: `SpeechCardText.speakable` → `InflectMicroTextFrontend.normalize` → `phonemizeChunks` (eSpeak via `InflectPhonemizer`, then `applyPhonemeOverrides`) → `InflectMicroOnnxRunner.synthesize` with default speed 1.0, variation 0.667, seed 0. Stops before AudioTrack.
- Runner: ONNX Runtime 1.24.3, CPU execution provider, default `SessionOptions` (no NNAPI/XNNPACK, intra-op threads not set). 8 logical processors reported.
- Voice: `CoriHigh` (selected in Settings → Voice; eSpeak data `vits-piper-en_GB-cori-high/espeak-ng-data`, 355 files). The Settings voice had been left on Inflect by the setup wizard; Cori was re-selected for these runs.
- Models: `duration.onnx` `b728ca25…`, `decode.onnx` `7940923a…` (same hashes as the 1485 run).
- Each run: app force-stopped, `MainActivity` brought to foreground, one broadcast to `DebugTtsBaselineReceiver` (`lane`, `corpus_path`, `label`, `repeats`), which starts `DebugTtsBaselineService` (foreground, specialUse). Output JSON at `files/tts_baseline/<lane>/<label>.json`.
- Timing: `first_chunk_ms` = frontend + first chunk synth. `rtf` = synth_ms / audio_ms per row.
- Memory: `VmHWM` / `VmRSS` from `/proc/self/status` at start, after init, and at end. These are whole-process figures; they are not comparable with the PSS numbers from the earlier 10-sentence runs (superseded, deleted).
- Cancel probe: the longest sample is started on a worker thread, `cancel()` is called 1000 ms in, and the time from the cancel call to the exception is measured. Then `resetCancellation()` and the shortest sample is re-synthesised to check recovery.

## Schemas

- `debug-tts-baseline/2` (`s23u-inflect-v2-run{1,2,3}.json`, code commit `e675fe8d`): runtime, voice and model fields at top level. Output was `files/inflect_baseline/<label>.json`.
- `debug-tts-baseline/3` (`s23u-inflect-v3-shape-check.json`, code commit `383408dc`): the same measurements; runtime, voice and model identity moved under `lane_metadata` (from `DebugTtsLane.metadata()`), and the output directory is per lane. Only one pass (`repeats=1`) was run to check the new shape; the three cold v2 runs are not repeated.

## Results (schema /2, commit e675fe8d)

| Run | runner_init_ms | RTF median | RTF mean | RTF max | first_chunk median (ms) | VmHWM after init (KB) | VmHWM end (KB) | cancel → throw (ms) | recovery |
|---|---|---|---|---|---|---|---|---|---|
| 1 | 180 | 0.2412 | 0.2416 | 0.2573 | 2513 | 389,188 | 1,017,472 | 5,935 | ok |
| 2 | 212 | 0.2422 | 0.2414 | 0.2556 | 2510 | 384,168 | 1,043,956 | 5,985 | ok |
| 3 | 184 | 0.2732 | 0.2730 | 0.2769 | 2930 | 384,720 | 1,043,860 | 6,489 | ok |

## Results (schema /3, commit 383408dc, shape check, one pass)

| Run | runner_init_ms | RTF median | RTF min | RTF max | first_chunk median (ms) | VmHWM after init (KB) | VmHWM end (KB) | cancel → throw (ms) | recovery |
|---|---|---|---|---|---|---|---|---|---|
| v3 shape-check | 1 pass (`repeats=1`, 8 rows) | 0.2384 | 0.2268 | 0.2448 | 2585 | 385,256 | 923,748 | 5,607 | ok |

The v3 run used the same voice (`CoriHigh`), ORT 1.24.3, CPU delegate, and model hashes as the v2 runs; `lane_metadata` carries them. One pass only, so its end-of-run VmHWM is not directly comparable with the three-pass v2 runs.

Run 1 per-sample (pass 1): RTF 0.217–0.240; first-chunk 1.7–6.6 s; every sample is a single chunk.


## Findings

- **Cancel latency.** `cancel()` is checked only at the runner's stage boundaries (`InflectMicroOnnxRunner.kt` lines 74, 124, 179). A cancel 1 s into the 27 s-audio reply returned after ~6 s, because the in-flight decode finishes first. Production `stop()` pauses and flushes the `AudioTrack` immediately, so audible stop is not delayed. The costs are elsewhere: a `speak()` issued during cancellation waits on `synthesisLock` (`InflectMicroVoiceOutputController.kt:50`) for up to that long, CPU is spent on discarded synthesis, and each sample is a single chunk, so first audio on long replies is about 7 s.
- Corpus is single-chunk per sample. A chunk-boundary cancel probe (first attempt) never fired; the probe now cancels mid-synthesis from another thread.
- **Memory.** Process VmHWM rises from ~0.38 GB after init to ~1.02–1.04 GB by the end of the three-pass v2 runs (0.92 GB for the single-pass v3 check), and VmRSS at end matches VmHWM, so the memory stays resident rather than spiking. [INFERENCE] This fits an ORT CPU arena sized by the longest decode, and production reaches the same peak on any reply near the length cap (`MAX_PHONEME_TEXT_LENGTH` = 1024 keeps text in one chunk). Not yet attributed: per-row VmHWM sampling and a run with the CPU arena and memory pattern disabled would separate it.

## Deviations and gaps

- No AudioTrack or first-audio latency (harness stops before playback).
- No chat-model-resident memory: the Gemma chat model's residency during these runs was not measured or controlled, so VmHWM includes whatever the app had loaded.
- Device fingerprint on this run: `samsung/dm3qxxx/dm3q:16/BP4A.251205.006/S918BXXSAFZI1:user/release-keys`. The 1485 manifest recorded `…S918BXXSAFZG1`; firmware differs.
- Part 2 (LiteRT-LM bump) skipped; no E4B/E2B regression run.
- Old 10-sentence runs (`s23u-inflect-run{1,2,3}.json`, PSS-based) are superseded and removed.

## Files

- `corpus.tsv`: frozen corpus.
- `s23u-inflect-v2-run{1,2,3}.json`: schema `debug-tts-baseline/2`, code commit `e675fe8d`.
- `s23u-inflect-v3-shape-check.json`: schema `debug-tts-baseline/3`, code commit `383408dc`, one pass.
