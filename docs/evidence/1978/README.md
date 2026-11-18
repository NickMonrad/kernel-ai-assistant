# #1978 asr4all streaming STT: evidence

Scope: criteria 1–3 (Kotlin port of FUTO's pinned HF asr4all ONNX driver, engine parity, app wiring). Criteria 4–5 (owner audio accuracy and tier decision) remain pending #1984.

## Pinned upstream
- Repo: asr4all-s, revision `87a6b5d093723fe37e9f69ad26307a3b95a31af0`, path `onnx/`.
- Files: `metadata.json`, `stream_c16r4.onnx`, `stream_c32r8.onnx`, `pcec_c16.onnx`, `pcec_c16_flush.onnx`, `asr4all.onnx.data`.
- Not pinned by sha256: `KernelModel` has no checksum field. Downloads are not verified beyond HTTP success.

## Parity reference (Python driver, same ONNX files)
Fixture: synthetic espeak clip, 16 kHz mono s16. Generated at runtime, not committed.

| Tier | Mode | Expected text |
|---|---|---|
| c16r4 | PCEC | `Weher like in p on tomorrow. Remind me to call them at five.` |
| c16r4 | plain | `weather like in p on tomorrow remind me to call m at five` |
| c32r8 | PCEC | `Hey gandle, what weather like in Peton tomorrow? Remind me to call them at five.` |

Kotlin `Asr4allEngineParityTest` (6 tests) matched all reference strings exactly. It is env-gated (`ASR4ALL_MODEL_DIR`, `ASR4ALL_FIXTURE_WAV`), so CI reports it as skipped.

Command:
```
ASR4ALL_MODEL_DIR=/tmp/asr4all-s/onnx-min ASR4ALL_FIXTURE_WAV=/tmp/asr4all-ref/clip.wav ./gradlew --offline :core:voice:testDebugUnitTest --tests 'com.kernel.ai.core.voice.asr4all.*'
```

Routing tests: `SelectableVoiceInputControllerTest` (25) and `SelectableVoiceInputHandoffTest` (15) pass, 0 failures.

## S23U smoke (RFCW11265XH), debug build
- Installed `app-debug.apk` via `adb install -r`; `lastUpdateTime` 2026-11-18 10:49:12.
- Six model files pushed to the app's models folder under prefixed names (not committed).
- Settings → Voice: asr4all row shows **Downloaded**; selecting it shows **Ready**; no `AndroidRuntime` errors in logcat.
- Engine selection was restored to Automatic after the check. Hey Jandal toggle was not touched.
- **Not done:** speech transcription on device. No owner audio and no mic playback path were available. Criteria 4–5 stay pending #1984.

Correction to the line above: trial 001 ran with no S23U capture active (not measured). Trial 002 (volume 7) was the S23U capture that timed out with `No speech heard`. Trial 003 (volume 15) was the captured one.

Correction to the line above: trial 001 ran with no S23U capture active (not measured). Trial 002 (volume 7) was the S23U capture that timed out with `No speech heard`. Trial 003 (volume 15) was the captured one.

Correction to the line above: trial 001 ran with no S23U capture active (not measured). Trial 002 (volume 7) was the S23U capture that timed out with `No speech heard`. Trial 003 (volume 15) was the captured one.

Correction to the line above: trial 001 ran with no S23U capture active (not measured). Trial 002 (volume 7) was the S23U capture that timed out with `No speech heard`. Trial 003 (volume 15) was the captured one.
