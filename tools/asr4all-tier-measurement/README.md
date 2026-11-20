# asr4all per-device-tier measurement (benchmark only)

Standalone Gradle project, separate from the KernelAI build. It installs only the isolated package
`ai.jandal.spike.asr4alltier` (+ `.test`), never `ai.jandal.assistant*`, and changes no production code, model
selection or default. Results: [`docs/evidence/asr4all-tier-measurement/`](../../docs/evidence/asr4all-tier-measurement/).

## Licence boundary: the ExecuTorch driver is not in this repository

FUTO's reference driver for the ExecuTorch bundles (`executorch/asr4all_executorch.py` in the asr4all model repos)
carries no licence. This project therefore does **not** publish a port of it. The Kotlin driver the ExecuTorch lane
needs (`EtAsr4allDecoder` and `Asr4allEtGeometry`, package `ai.jandal.asr4alltier`) is kept in a **private
directory outside Git** and compiled in at build time:

```sh
./gradlew -p tools/asr4all-tier-measurement :probe:assembleDebug :probe:assembleDebugAndroidTest \
    -PprivateEtDecoderDir=$HOME/.local/share/jandal-private/asr4all-et-decoder   # default location
```

The build fails with a clear message if the directory is missing. What the driver does is fully specified by
FUTO's script (stream tier window/commit loop, CTC argmax over all 257 logit columns, PCEC `pcec_c16` /
`pcec_c16_flush` pooling and rendering, no word spotter) and was verified frame-for-frame against it in #2002.
The private copy additionally asserts every output tensor shape on the first call
(logits `[1, commit, len(vocab)+1]`, caches, hidden), the bug class behind the original #1988 false divergence.
Host oracles in #2002 import FUTO's script unmodified at run time from the pinned Hugging Face snapshot.

The public code here is Jandal's own: lane plumbing, pacing, timing, memory sampling, CPU-affinity control,
scoring. Jandal's shipping `TranscriptNormaliser` is compiled unmodified from Git (`-PnormaliserCommit`, default
`origin/main` `00025895`). The Zipformer lane uses the app's own Sherpa AAR (`third_party/sherpa-onnx`).

## Lanes

| Runtime | What | Config |
|---|---|---|
| `et` | FUTO asr4all S/M/L ExecuTorch XNNPACK INT8 (`executorch/xnnpack_int8/asr_encoder.pte`) | Maven `executorch-android:1.5.1`; threads = JNI default (cores/2 = 4) unless `variant=tN` |
| `zipformer` | Shipping offline default, `sherpa-onnx-streaming-zipformer-en-2023-02-21` int8 | the app's `initOnlineRecognizer` config: 2 threads, cpu, greedy, maxActivePaths 4, default endpoint rules |
| `native` | Android `SpeechRecognizer`, shipping intent and backend choice (on-device when available) | file PCM via `EXTRA_AUDIO_SOURCE` + `EXTRA_SEGMENTED_SESSION` (see below) |

**Low-end proxy (S21 only).** `cpus=07` runs `taskset -a -p 07 <pid>` (toybox, as the app's own uid) before load
and again before every clip, so every thread of the process, including XNNPACK and ONNX Runtime pool threads
created later, is restricted to CPUs 0–2: three of the Exynos 2100's four Cortex-A55 efficiency cores at
≤2.21 GHz (cpu3 is outside Android's foreground cpuset, so a `0f` mask yields 0–2 anyway). ExecuTorch is
additionally limited to 2 threads (`variant=t2`); Zipformer already uses 2. Every report records the
`Cpus_allowed_list` actually observed on all threads at the end of each clip. **This is a proxy**, not a real
low-end device: it keeps the S21's memory bandwidth, caches and thermal headroom, and A55 cores at 2.21 GHz are
likely faster than most budget phones' little cores ([INFERENCE]: commonly A53/A55 at ≤2.0 GHz), while budget
SoCs usually also have two mid/big cores that this proxy does not have.

**Android native caveat.** With a file audio source both Google recognisers on these phones (on-device
`AiAiSpeechRecognitionService` and `GoogleTTSRecognitionService`) return an empty `onResults` bundle; results
arrive only in segmented-session mode, so the lane adds `EXTRA_SEGMENTED_SESSION=EXTRA_AUDIO_SOURCE` and joins
`onSegmentResults`. That differs from the shipping intent and decodes the whole clip rather than stopping at the
first endpoint. The recogniser runs in another process: no load time, memory, RTFx or lag is attributable.

## Measurements

`BenchmarkTest`, one lane per fresh process (load and peak memory per lane and repeat):
- **owner**: 20 #1984 owner recordings, paced 100 ms feed (per-chunk lag, first/corrected partials, partial
  updates, final after audio end), EOF-flushed finals;
- **long**: a 66.7 s public stream (Narsil/asr_dummy `1,2,3,mlk` ×2), paced, for sustained backlog;
- **breadth**: 100 #1984 voice-cloned clips, compute-only, repeat 1 only (WER is deterministic for the
  in-process engines).

Every final is stored as emitted and also through `TranscriptNormaliser` after each engine's shipping
pre-processing (Zipformer: trim, strip trailing punctuation, lowercase; native: trim; asr4all: none, since the
#1987 engine does not call the normaliser, so its "normalised" column shows what it would score if it did).

## Running

```sh
# models/clips cache: FUTO pins as in #2002, Zipformer pinned at
# csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-02-21@bfbb11950302dad9955f8ede01f0e768595e4fba
scripts/run_tier.sh s21 <agent-id> <private-dir> <repeat>      # one locked session = one repeat of lanes-s21.txt
python scripts/aggregate.py phrases.json breadth-phrases.json <out> S21=<private-dir> S23U=<private-dir>
python scripts/tables.py <out>/summary.json
```

Phrase references are public on the #1984 branch: owner `docs/evidence/stt-eval/phrases.json` (SHA-256
`ea7d480c…`, `origin/chore/1984-stt-eval-phrases`) and breadth `docs/evidence/stt-eval/breadth-phrases.json`
(`72790822…`, commit `437de7ae`). Owner/breadth audio is private (#1984).

`run_tier.sh` installs with `adb install -r` and runs `am instrument` directly (never Gradle connected tasks),
follows the device lock protocol, rotates lane order per repeat, removes staged owner audio and
outputs, restores stay-awake and (with `UNINSTALL=1`) removes only the isolated probe package; it never touches
`ai.jandal.assistant*`. Raw reports contain owner transcripts
and stay private; `aggregate.py` emits only counts, timings and per-clip edit counts keyed by the public phrase
IDs.
