# #1557 Kokoro via MediaPipe tasks-audio 1.2.3: S23U evidence

Status: **NO-GO for coexistence with E4B on the S23U as tested.** Kokoro on its own also hangs at first synthesis in two fresh-process runs, so this is not an E4B conflict.

Refs #1557. Depends on #2092 (lane-agnostic TTS harness); this branch merges `chore/2088-litertlm-tts-harness` at `feaf745f`.

## Setup

- Device: Samsung S23U `RFCW11265XH` only. The Honor and S21 were not used.
- Build: debug variant only. `com.google.mediapipe:tasks-audio:1.2.3` is `debugImplementation`; release runtime classpath has no tasks-audio or mediapipe (verified with `:app:dependencies --configuration releaseRuntimeClasspath`).
- Model: `kokoro_int8_gpu_fp16.litertlm`, 294,750,530 bytes, sha256 `1ef178b1...bac52`, in the app's external `files/models/kokoro/`, outside the APK.
- Voice: `af_heart`. Corpus: `docs/evidence/2088/corpus.tsv` (8 lines).
- Debug app `ai.jandal.assistant.debug` only. The Play build was not touched.

## Runs

### Run 1: Kokoro alone (`alone-run1`), pid 366

- Init completed: LiteRT env, XNNPACK CPU delegate for the acoustic path (weight cache `kokoro_acoustic_*.xnnpack_cache` loaded), OpenCL `InferenceContext` initialised from serialized data for the GPU subgraphs, espeak-ng voice pack `af_heart` loaded, `KokoroPhonemizer initialized successfully` at 16:45:51.
- No further log lines from the TTS path. The last app line is a GC at 16:48:09.
- Process still alive at 16:50 (state S, RSS about 585 MB). Foreground service up. No result or failure JSON written.
- The worker thread `debug-tts-basel` used about 138 user ticks, then slept.
- SIGQUIT (`run-as ... kill -3`) wrote a trace to `tombstoned` `trace_00` (16:51:01). That file is root-only, so the Java stack could not be read from shell or `run-as`.
- `debuggerd -b` requires root; no native stack captured.
- Force-stopped at 16:51.

### Run 2: Kokoro alone, fresh process (`alone-run2`), pid 3492

- Repeats the same init sequence, completing at 16:51:34 (`Initialized InferenceContext from serialized data`, `KokoroPhonemizer initialized`).
- No TTS-path log line for the next 4+ minutes (to 16:56). The harness logs nothing per sample, so the stall is in the first synthesis call inside tasks-audio.
- Memory at hang (`/proc/3492/status`, `dumpsys meminfo`):
  - VmHWM 605,996 kB; VmRSS 590,948 kB.
  - Native Heap about 75.7 MB PSS.
  - GL mtrack 234,044 kB; EGL mtrack 39,744 kB.
  - Dalvik Heap about 34 MB.
- Force-stopped at 16:56.

### Coexistence (E4B on GPU + Kokoro)

- **Not run.** The plan gated it on Kokoro alone working. It did not.
- Earlier coexistence attempt (16:06 build): E4B `Engine ready — backend: GPU, maxTokens: 8000`; Kokoro loaded; no result; process hung at 0% CPU with VmHWM 1,110,164 kB. The stack capture for that run was not obtained.

### Cancel probe, RTF, first-chunk latency, cold-run comparison with Inflect v2

- **Not measured.** Neither Kokoro run reached a synthesis result, so no `cancel_probe`, RTF, first-chunk, or `lane_metadata` values exist. Cold-run comparison against `docs/evidence/2088/s23u-inflect-v2-run{1,2,3}.json` is not possible from these runs.

## Backend actually used

- Requested delegate: GPU for the GPU subgraphs (`LITERT_CL` partitions for prefill and decode subgraphs reported in run 1 logs). The GPU `InferenceContext` initialised, so GPU is in use at init.
- Acoustic path: XNNPACK CPU delegate (weight cache). The GPU/CPU split is per-subgraph; the synthesis path that stalled was not observed.
- No NPU (`kLiteRtStatusErrorNotFound`).

## Memory and device state (aggregate; personal app names excluded)

- Before run 2: about 1.1 GB free of 11.0 GB, about 10 GB used across all apps, several GB of swap in use.
- Earlier leftover debug-app process held about 4.1 GB GL mtrack and was force-stopped before run 1; that is a separate state from these runs.
- The app's own `kernel_wake_word` loop (`OnnxWakeWordDetector.runDetectionLoop`) starts on MainActivity launch. It was running during both Kokoro runs. This is a confound I did not remove.
- Device temperature 40 to 42 °C at 16:39 on battery (discharging); cold-run caveat.
- Memory is **not ruled out** as a contributor: the device was memory-saturated during both runs, and tasks-audio GPU buffer allocation may be waiting on memory. Run 2 shows the hang without E4B, so E4B coexistence is not the cause.

## Interpretation

- The hang is reproducible in two fresh processes, and reproduces with no E4B in memory. The E4B-coexistence hypothesis is not supported by these runs.
- Whether the stall is a GPU-delegate deadlock or a memory-starved allocation was not determined. A Kokoro-alone run forced to the CPU delegate would separate those; it is the next diagnostic and was not run because the plan conditioned it on a working Kokoro-alone baseline.
- Native and Java stacks were not obtainable (root-only tombstone/trace paths; `debuggerd` requires root). Obtaining them needs a rooted or `userdebug` device, or a debug build that writes its own stack to app storage.

## Licence inventory (from the tasks-audio 1.2.3 AAR `META-INF/NOTICE`)

Attribution by position in the NOTICE file is [INFERENCE]; none of the components below have been confirmed against their dependency tree. Not decided here; no change to `allowedLicenses`.

- GPLv2 with Classpath exception: under a J2ObjC heading (about lines 31985, 33897). Possibly the `checker-compat-qual` path; unconfirmed.
- GPLv2 (no exception): two blocks (about lines 32216, 34128), under J2ObjC by position.
- GPLv3 with Bison exception: about line 45080, component unidentified.
- MPL-2.0: about line 21900, near Eigen sources; unconfirmed.
- CPL-1.0: about line 33221, after ICU; unconfirmed.
- ICU License: about lines 30163, 32892.

## Recommendation

- Kokoro via tasks-audio 1.2.3 is **not** a go on the S23U as tested. Do not promote it.
- ExecuTorch: worth a spike only after the Kokoro-alone CPU-delegate diagnostic. If the CPU delegate also stalls, the library is the problem and ExecuTorch is the fallback. If CPU passes and GPU stalls, the GPU delegate is the problem and ExecuTorch is a candidate. **Not started.**

Do not merge — wait for review.
