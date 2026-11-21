# #2004 device evidence: asr4all engine on the S21

Implementer's run of the instrumentation tests (AC2, AC3 device side, AC4). Parity (AC1) is
recorded separately by the verifier.

## Setup

| item | value |
|---|---|
| Device | Samsung S21 `R5CR605B71K` (SM-G991B, Exynos 2100), `samsung/o1sxxx/o1s:15/AP3A.240905.015.A2/G991BXXSJHZC2` |
| Lock | `/tmp/jandal-s21.lock` held for the whole run, then released |
| Engine | branch `feat/2004-asr4all-executorch-cleanroom`, with the AC1 round-1 fixes (commit "fix(#2004): PCEC inputs and live text match …", rebased onto #2010 head `98e28b06`, plus the `TensorDim` API update); ExecuTorch `org.pytorch:executorch-android:1.5.1` through `:core:executorch` only |
| Test APK | `core/voice/build/outputs/apk/androidTest/debug/voice-debug-androidTest.apk`, package `com.kernel.ai.core.voice.test` (test-only; the app is untouched) |
| Model | `futo-org/asr4all-m` @ `7d6e9145109a5bece4f05831d046e93d27e38a99` (`asr_encoder.pte` SHA-256 `d08648b9…7763`); S `asr_encoder.pte` @ `87a6b5d0…1f0` (`7d567968…27ec`) for the mismatch test. Hashes checked on the device after the push |
| Threads | `Asr4AllEngine.DEFAULT_THREADS` = 4 |

How it was run (no Gradle connected tasks, no uninstall, no `pm clear`, screen left on):

```bash
./gradlew :core:voice:assembleDebugAndroidTest
adb -s R5CR605B71K install -r core/voice/build/outputs/apk/androidTest/debug/voice-debug-androidTest.apk
D=/sdcard/Android/data/com.kernel.ai.core.voice.test/files/asr4all
adb -s R5CR605B71K push <asr4all-m files> $D/m/     # config.json, release_geom.json, preprocessor_config.json,
adb -s R5CR605B71K push <asr4all-s files> $D/s/     # executorch/xnnpack_int8/{metadata.json,asr_encoder.pte}
adb -s R5CR605B71K shell svc power stayon usb        # recorded stay_on_while_plugged_in=0 first; restored after
adb -s R5CR605B71K shell am instrument -w -r -e package com.kernel.ai.core.voice.asr4all \
  com.kernel.ai.core.voice.test/androidx.test.runner.AndroidJUnitRunner
```

## Results (21 Nov 2026, after the AC1 fixes): `OK (3 tests)`

**AC2: `Asr4AllEngineDeviceTest.mediumExportTranscribesThePublicClip`, pass.** M, `stream_c16r4`,
LibriSpeech dev-clean `84-121550-0015` (CC BY 4.0, one of the verifier's AC1 clips, committed as an
androidTest asset, SHA-256 `71a4d201…39df4`) fed in 100 ms chunks. The expected text is **the
verifier's reference final** for this row, copied from its AC1 comment on #2011:

> The interval between these four contained a chariot, triumphal on two wheels, which by a griffin's neck came drawn along.

The S21 produced exactly that text, so there is no platform drift on this row. Before the fixes the
engine gave `… by a Griffin's neck …` here. 8155 ms of audio took 665 ms (RTF 0.08, including
PCEC). There were 10 live partials, all cased and punctuated; the first, `The interval`, equals the
reference's first live event for this row. A second run on the same engine (after `finish()`
reset it) gave identical partials and final.

The earlier run (engine `7568e9e`, before the fixes) used `1272-128104-0000` with the engine's own
output as the expected text. That clip is not in the verifier's set, so it was replaced.

**AC3 (device side): `programFromAnotherSizeIsATypedConfigErrorNotACrash`, pass.** M's config
files next to S's `asr_encoder.pte` throw `Asr4AllConfigException` at open:
`stream_c16r4 input 0 is FLOAT32[1, 22400] in the program but the config files imply audio:
FLOAT32[1, 24960]`. Nothing reached the runtime, and the process survived. Without the check, the
1.5.1 runtime would abort the process on the first method call. A correct M open afterwards
works.

**AC4: `Asr4AllLifecycleDeviceTest`, pass.** 21 cycles, each of open, then 3 × (3 s utterance, then
`reset()`), then (3 s utterance, then `finish()`), then `close()`. The utterances are the first 3 s
of `84-121550-0015`.

- After every `close()`, `acceptPcm16`, `finish` and `reset` throw `Asr4AllClosedException`, and
  `/proc/self/maps` shows no mapping of `asr_encoder.pte`. While the engine is open, the file is
  mapped (2 mappings).
- Final text is identical in every cycle.
- PSS after each cycle (KB): 170040 (after cycle 0, the baseline), then 169708, 170136, 169877,
  170085, 169753, 169805, 169733, 170068, 169713, 169797, 170083, 169861, 169816, 169864,
  169812, 168000, 169812, 170160, 169820, 169868. The peak over cycles 1–20 is +120 KB against
  the baseline, inside the stated bound of +24 MB. The run before the fixes measured −180 KB.

The leak check was tested against a deliberate leak, built locally and not committed (run before
the fixes): `Asr4AllEngine.close()` was changed to skip releasing the program. The test then
failed at cycle 0 with `program still mapped after close expected:<0> but was:<2>`. An earlier
version of the test, which checked only PSS growth after two warm-up cycles, did **not** fail on
that mutation. A leaked program raised PSS by about 46 MB once and then stayed flat, so the
warm-up baseline hid it. The committed test therefore uses the first cycle as its baseline and
adds the mapping check.

## Not covered here

- The S23U: #2004 names it for AC2. The operator directed this run to the S21 only.
- L and S transcription on the device, and tiers other than `stream_c16r4`. The host harness
  covers S/M/L × all six tiers.

## AC1 parity status

Parity is recorded in `CLEANROOM.md` ("AC1 round 2 verdict and acceptance"): finals 504/504; three partial-only differences accepted by the owner on 21 Nov 2026.
