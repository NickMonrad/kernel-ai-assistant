# #1556 Qwen3-TTS x-vector clone feasibility: evidence (Build 9)

Refs #1556. Docs-only. No app integration, no Jandal code changes. No audio, x-vector, voice sample, or container is committed. All private artifacts stay on the PC under `~/private-acoustic-fixtures/qwen3-litert-1556/`.

## Status

| Gate | Result |
|---|---|
| 1. x-vector extraction (PC) | Done. 1024-d float32, L2 10.28, from the 51.95 s owner reference. |
| 2. Package int8_v2 with a named voice | Done. `owner_xvector` GenericBinaryData section (4096 B) added to the container. |
| 3. `omni_runner` build, S23U run (RTF, TTFA, RSS, CPU/GPU, with E4B) | Done. CPU and GPU runs for all 6 sentences alone; E4B-resident repeat for CPU and GPU (RTF/RSS only). TTFA measured alone via a local log patch; not measured with E4B resident. |
| 4. Listening set (a) LiteRT x-vector, (b) upstream ICL, (c) demo_speaker | Clips produced and kept private. Listening verdict is the owner's; no subjective judgment is recorded here. |
| 5. MediaPipe tasks-audio `voiceId` mapping | Partly checked, not proven. See Gate 5. |

## Versions and pins

- LiteRT-LM `main` @ `097bd757af3dd00b3fe802af28e9e4753ceee7ca`.
- Bazel 7.6.1 (via bazelisk, `.bazelversion`); `--output_user_root=~/.cache/bazel-1556`.
- Android NDK 28.2.13676358; build `-c opt --config=android_arm64 //omni:omni_runner`. Output: aarch64 ELF, 45,028,632 B. Build EXIT 0, about 945 s.
- Builder CLI: `//python/litert_lm_builder:litertlm_builder_cli` (same commit), run through a local venv with Python 3.12 and `absl-py`. The bazel-bundled venv failed to import numpy on this host.
- Base container: `litert-community/Qwen3-TTS-12Hz-0.6B-Base` `qwen3_tts_int8_v2.litertlm`, 2,444,070,912 B, sha256 prefix `467b160502ad945e`.
- Derived container: `qwen3_tts_int8_v2_owner.litertlm`, 2,444,087,296 B. Adds section `GenericBinaryData` named `owner_xvector` (4096 B). Source: `unpack`, edit `model.toml`, `toml` rebuild.
- Device: Samsung S23U (`RFCW11265XH`), Android build as on device at run time. S21 and Honor not used.
- Upstream x-vector model: `Qwen/Qwen3-TTS-12Hz-0.6B-Base` @ `5d83992436eae1d760afd27aff78a71d676296fc`; `qwen_tts` 0.1.1.

## Method notes and a packaging finding

- Repacking with the builder default (`weights_in_model_graph=False`) externalized TFLite weights to a `tflite_weights` sidecar that was not packed into the output. Runtime then failed with `Failed to open tflite_weights`. Rebuilding with `--weights-in-model-graph=true` produced a working container (size within 16 KB of the original). Any future repack must use that flag.
- The unknown-voice fallback is silent. `--voice=__no_such_voice__` produced a WAV byte-identical to `demo_speaker`. A voice-applied check must compare against the demo output, not only check exit code.

## Corpus

`corpus.json` (`qwen3-litert-1556-v1`), six neutral sentences written for this test:
S1 short confirmation; S2 names and places; S3 numbers and currency; S4 date, time, abbreviation; S5 multi-sentence weather; S6 longer transport reply. No personal data.

## Gate 3: device runs (S23U, lock held by `omp-1556-build9` for the run, released afterwards)

Runner: `omni_runner --model_path=<container> --backend=<cpu|gpu> --cache_dir=<dir> --generate_audio=true [--voice=owner_xvector] --text=<S> --output_path=<wav>`. Inference time, audio length, and peak VmHWM are read from the process (`/proc/<pid>/status`) and the runner's "Saved … (inference: …)" line. RTF = inference / audio.

Alone (no other app foreground):

| Run | Sentence audio s (range) | RTF (range) | Peak VmHWM |
|---|---|---|---|
| CPU, owner x-vector | 3.04–13.92 | 2.22–2.51 | 1.78–1.85 GB |
| CPU, demo_speaker | 2.48–13.60 | 2.54–3.17 | 1.78–1.84 GB |
| GPU, owner x-vector | 3.20–13.60 | 2.88–3.24 | 1.49–1.54 GB |

With E4B resident (Jandal debug app loaded Gemma E4B; app TOTAL PSS about 5.3–5.5 GB during the run):

| Run | RTF (range) | Peak VmHWM |
|---|---|---|
| CPU, owner x-vector | 2.33–2.75 | 1.70–1.83 GB |
| CPU, demo_speaker | 2.78–3.59 | 1.57–1.80 GB |
| GPU, owner x-vector | 2.87–4.15 | 1.41–1.49 GB |

Notes: the E4B run was measured on a phone with the app foreground-loaded. RSS from VmHWM is the runner process only; the app's PSS is reported separately. Single runs per cell; no variance estimate.

Time to first audio (TTFA), alone, owner x-vector, measured from the runner's session-loop start (after model load, includes text prefill) to the first non-empty audio chunk. Added by a one-line log patch in the spike clone (not committed; the upstream runner does not log it). Attribution caveat: for S1 TTFA equals total inference, so the runner appears to emit the first chunk only at end for short inputs.

| Run | TTFA s (range) | Inference s (range) | RTF (range) |
|---|---|---|---|
| CPU, owner x-vector | 10.3–11.8 (S1: 8.2) | 15.2–33.5 | 2.22–2.68 |
| GPU, owner x-vector | 14.0–15.2 (S1: 10.4) | 18.8–41.5 | 2.84–3.24 |

The E4B-resident TTFA was not measured (the Jandal app released its inference session when backgrounded; a re-load was not run).

Side effect: to load E4B I opened a new chat in the Jandal debug app on the S23U (empty conversation remains). Screen was kept on with `svc power stayon usb`; the `stay_on_while_plugged_in` setting is 2, unchanged from the start.

## Gate 4: listening set and objective similarity

Private, not committed: `clips/b_S1..S6.wav` (upstream 1.7B ICL clone), `device/cpu_owner_S*.wav` (LiteRT x-vector), `device/cpu_demo_S*.wav` (demo_speaker), `device/gpu_owner_S*.wav`, `device_e4b/`. Index: `LISTEN_INDEX.md` (private).

Cosine similarity to the owner x-vector (0.6B encoder, CPU; `speaker_sim.py`):

| Clip | Cosine |
|---|---|
| Owner reference (self-check) | 1.000 |
| LiteRT CPU, owner x-vector (S1–S6) | 0.974–0.988 |
| LiteRT GPU, owner x-vector (S1–S6) | 0.980–0.987 |
| LiteRT CPU, demo_speaker (S1–S6) | 0.898–0.907 |
| Upstream 1.7B ICL clone (S1–S6) | 0.971–0.989 (cross-model caveat: 1.7B clone scored with the 0.6B encoder) |

The owner x-vector moves LiteRT output clearly toward the owner's speaker embedding, and makes it closer than the demo voice. This is an embedding-space measure only, not a listening result. Owner listening decides quality. Per the 23 Nov owner decision: the x-vector-only path is the LiteRT option; ICL cloning is not available in LiteRT.

## Gate 5: MediaPipe tasks-audio `voiceId`

Read-only check of `tasks-audio-1.2.3` (local Gradle cache, `jni/arm64-v8a/libmediapipe_tasks_audio_jni.so` and `classes.jar`). Found: a Qwen3-TTS session factory registered with `session_config_type_id "Text2Speech"` and model URL `qwen3_tts_int8_v2.litertlm`; `Speaker embedding` and `Speaker embedding size is not correct` strings; `nativeListVoices` / `GetAvailableVoices`; `VoiceInfo` and `voiceId` / `defaultVoiceId` in the Kotlin API. Not found: a public API for supplying a speaker embedding or x-vector. Whether `voiceId` reaches the engine's voice selection remains unproven; a probe is needed. The Kotlin app is not integrated.

## Next steps

- Owner listening on clip sets a (CPU and GPU), c, and b.
- If quality is acceptable: decide on a Kotlin path (requires a tasks-audio `voiceId` probe, out of scope here).
- If not: defer per the 23 Nov decision; no third runtime.

Do not merge — wait for review.
