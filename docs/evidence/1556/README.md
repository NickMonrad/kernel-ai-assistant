# #1556 Qwen3-TTS x-vector clone feasibility (Build 9): partial evidence

Refs #1556. Docs-only. No app integration, no Jandal code changes. No audio, x-vector, or voice sample is committed.

## Status

| Gate | Result |
|---|---|
| 1. x-vector extraction (PC) | **Done.** 1024-d float32, matches `enc_dim=1024` and the `qwen3_tts_frontend_stage.cc` size check. |
| 2. Package `qwen3_tts_int8_v2` with named voice | **Not done.** Blocked by disk (see below). |
| 3. `omni_runner` build and S23U run (RTF, TTFT, RSS, GPU/CPU, with E4B) | **Not done.** Blocked by build and device lock (see below). |
| 4. Listening set (a) LiteRT x-vector, (b) upstream ICL, (c) demo_speaker | **Not done.** Depends on gate 3. |
| 5. MediaPipe tasks-audio `voiceId` mapping | **Not verified.** Carried forward from the 2088 verification notes: no speaker or embedding option in the public API; `voiceId` → `Text2SpeechSessionConfig.voice` unproven. |

No clone quality, RTF, or memory numbers exist yet. Do not infer any from upstream host figures.

## Gate 1: x-vector extraction

- Method: upstream `Qwen3TTSModel` → `model.model.extract_speaker_embedding(audio, sr=24000)`, float32, CPU.
- Input: owner reference clip, 24 kHz mono, 51.95 s (private; not in repo).
- Output: shape `(1024,)`, float32, L2 norm 10.28. Stored privately, not committed.
- Versions: `qwen_tts` 0.1.1; torch 2.14.1+rocm7.2 (CPU device used).
- Model: `Qwen/Qwen3-TTS-12Hz-0.6B-Base` at revision `5d83992436eae1d760afd27aff78a71d676296fc`.
- Dimension check: `enc_dim=1024` in the 0.6B `config.json`; the LiteRT path requires exactly 1024. The 1.7B Base was not checked [inference: its talker is wider], so the 0.6B extractor is required.

## Blockers

1. **Disk.** `/home` is 99% full, with 9.9 GB free at 15:10 AEST. `qwen3_tts_int8_v2.litertlm` is 2.44 GB. A bazel build of `//omni:omni_runner` would need far more than the remaining space, and a full disk would break other agents' work on the same volume. Build was not started.
2. **No prebuilt runner.** Upstream `litertlm` v0.19.1 AAR contains no `OmniEngine`, Kokoro, or Qwen3-TTS symbols (checked in the 2088 verification). `omni_runner` must be built from source.
3. **Device lock.** `/tmp/jandal-s23u.lock` is held by `fix-2077-weather-readback` (started 14:58 AEST). It is not yet stale, so the S23U was not used or touched. The S21 is not used for this run (7 GB RAM, out of scope per the owner).

## Pinned upstream refs (for the next attempt)

- LiteRT-LM `main` @ `097bd757af3dd00b3fe802af28e9e4753ceee7ca` (checked 2026-11-23)
- LiteRT-LM `v0.19.1` @ `7a03724cece417f1724c923b3d527b4319730841`

## Next step

Free roughly 25 GB on `/home`, or run the build on a volume with space. Then run gates 2 to 4 on the S23U once the lock is free. Gate 5 needs a read of the tasks-audio JNI or a small probe. Owner review is needed before any device run.

Do not merge — wait for review.
