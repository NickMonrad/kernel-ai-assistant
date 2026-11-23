# #2088 Inflect Micro S23U baseline

Synthesis-cost baseline from the debug-only `InflectBaselineReceiver`. It measures
normalize, phonemize and ONNX synthesis per chunk. It does **not** measure AudioTrack
playback latency, first-audio latency, cancel/recover, or chat-model-resident memory.

## Device and build

- Device: Galaxy S23 Ultra, serial `RFCW11265XH` (primary evidence device).
- Build: debug `ai.jandal.assistant.debug` 0.1.0, built from this branch (`:app:assembleDebug --offline`).
- Models: `duration.onnx` (7.3 MB) and `decode.onnx` (30.4 MB), downloaded through the
  Setup wizard "Inflect Micro" option (owensong/Inflect-Micro-v2-ONNX, Apache-2.0).
- Corpus: `corpus.txt` (10 generic sentences, sha256 `9d1d9403ee1663f601d091649f4e6289a293aa4adbabd211380da63ce2f9b333`).

## Method

Each run is one broadcast to the receiver with `repeats=3`, which runs the corpus three times
through a fresh `InflectMicroOnnxRunner`:

```
adb -s RFCW11265XH shell am broadcast -n ai.jandal.assistant.debug/com.kernel.ai.debug.inflect.InflectBaselineReceiver \
  --es corpus_path /data/local/tmp/jandal-2088-corpus.txt \
  --es data_directory /data/data/ai.jandal.assistant.debug/files/sherpa-tts/vits-piper-en_GB-cori-high/espeak-ng-data \
  --ei repeats 3 --es label s23u-inflect-runN
```

Results are read with `run-as ai.jandal.assistant.debug cat files/inflect_baseline/<label>.json`.
Each run has 30 rows (10 sentences x 3 passes).

## Results

| Run | init_ms | RTF mean | synth total (ms, 30 rows) | all finite | PSS before (KB) | PSS peak (KB) |
|---|---|---|---|---|---|---|
| 1 | 173 | 0.223 | 33,993 | yes | 252,923 | 483,400 |
| 2 | 164 | 0.225 | 34,334 | yes | 249,676 | 483,572 |
| 3 | 163 | 0.238 | 36,371 | yes | 478,914 | 505,586 |

RTF = synth_ms / audio_ms, computed per row. Lower is faster.

Run 3's `pss_before_kb` is higher because the process was reused (the receiver was not restarted between broadcasts), not a fresh cold start. Treat run 3 as a warm-process sample. Runs 1 and 2 each began after the app process had already been started; no process restart was forced between them.

## Deviations from #2088

- Part 2 (LiteRT-LM bump, E4B/E2B regression) was skipped; see the Part 1 finding on #2088. No Gemma regression numbers exist.
- Harness bypasses `VoiceOutputController.speak()`, so no AudioTrack or first-audio latency is measured.
- No cancel/recover test and no chat-model-resident memory measurement.
- Model download went through the production Setup wizard on the device. The wizard also left Voice output set to Inflect Micro on the S23U; the previous selection was Cori (Piper) and was not restored in this run.
