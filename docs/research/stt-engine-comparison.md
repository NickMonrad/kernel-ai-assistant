# STT engine comparison — Android native vs Vosk vs whisper.cpp vs Parakeet

## Summary

This note captures the current speech-to-text research findings for the Phase 3F STT wave:

- `#678` — optional native Android STT alongside Vosk
- `#700` — Parakeet CTC evaluation
- `#703` — whisper.cpp vs Vosk evaluation

Current product signal:

- on the Samsung Galaxy S23 Ultra test device, **Android native STT is a clear improvement over Vosk for Kiwi-accent speech**
- **Vosk** still has the strongest privacy/offline guarantees today
- **whisper.cpp** looks like a realistic medium-term candidate for push-to-talk quality experiments
- **Parakeet CTC** is more credible than a purely theoretical candidate because a LiteRT-community artifact exists with Qualcomm-specific builds, including **SM8550**
- **Parakeet CTC** should still remain research-only for now until runtime/backend behavior is validated on device

## Engine comparison

| Engine | Strengths | Weaknesses | Recommendation |
| --- | --- | --- | --- |
| **Vosk** | Fully local, deterministic offline behavior, already integrated, low package/runtime cost | Weaker recognition quality for NZ/Kiwi accent on the tested device | Keep as the current privacy/offline baseline and fallback |
| **Android native STT** | Best observed recognition quality so far on S23 Ultra, fast partial results, no model download in app | Privacy/offline guarantees depend on recognizer implementation and language-pack availability | Keep non-default for now, but continue QA as the leading quality option |
| **whisper.cpp** | Stronger general STT quality potential, fully local, proven Android integration pattern in Box | Higher integration complexity, larger models, more RAM/CPU cost, no streaming partials | Proceed with a scoped spike in `#703`, starting with tiny/base push-to-talk only |
| **Parakeet CTC** | LiteRT-community artifact exists, Qualcomm-specific variants exist including SM8550, could be a strong dedicated STT path if backend/runtime works well | Published card is sparse, runtime backend is unclear from files alone, very large model sizes, higher integration risk than Android native | Keep deferred to research-only in `#700`, but track it as a more credible Android candidate than before |

## Findings

### Android native STT

- The current manual device result is strong enough to justify `#678` as a real product path rather than a speculative fallback.
- Android native STT should remain **non-default** for now because privacy and offline guarantees are still weaker than Vosk.
- Follow-up work should focus on broader device QA and on-device/offline behavior validation before any default-engine decision.

### Vosk

- Vosk remains the safest baseline where a guaranteed local path matters more than recognition quality.
- It still makes sense as the default while the STT wave is in progress, especially until broader QA confirms how well Android native behaves across devices and language-pack states.

### whisper.cpp

- `jegly/Box` demonstrates a viable Android integration pattern using a dedicated JNI/NDK bridge:
  - Box whisper module: https://github.com/jegly/Box/tree/3f973472b46f610e085d7e7cebaad4afbe3bc944/Android/src/whisper
  - Box whisper engine wrapper: https://github.com/jegly/Box/blob/3f973472b46f610e085d7e7cebaad4afbe3bc944/Android/src/whisper/src/main/java/com/google/ai/edge/gallery/whisper/WhisperEngine.kt
- whisper.cpp is realistic enough to spike, but should be treated as:
  - **push-to-talk first**
  - **tiny/base class models first**
  - **not a default-engine candidate yet**
- Main tradeoffs versus the current engines:
  - heavier memory/runtime cost than Vosk and Android native
  - no equivalent streaming partial-result UX
  - extra NDK/JNI/build complexity

### Parakeet CTC

- `litert-community/parakeet-ctc-0.6b` now provides stronger Android evidence than the first pass assumed:
  - model page: https://huggingface.co/litert-community/parakeet-ctc-0.6b
  - files page: https://huggingface.co/litert-community/parakeet-ctc-0.6b/tree/main
- The file list includes Qualcomm-targeted variants for:
  - `SA8255`
  - `SA8295`
  - `SM8450`
  - `SM8550`
  - `SM8650`
  - `SM8750`
  - `SM8850`
- That means **SM8550 is explicitly in the target set**, which is directly relevant to the S23 Ultra test device.
- The files also suggest multiple packaging modes:
  - generic `parakeet_ctc_0.6b_5s_f32.tflite`
  - chipset-specific `..._Qualcomm_<chip>.tflite`
  - generic quantized `parakeet_ctc_0.6b_5s_i8.tflite`
- What this strongly suggests:
  - Parakeet is now a **real LiteRT Android candidate**
  - Qualcomm-specific optimisation work has already been done
  - the model appears to be packaged as a **5-second bounded inference path**, not obviously a continuous streaming path
- What we still **cannot** conclude from the filenames alone:
  - whether the Qualcomm files are intended for CPU, GPU, or QNN/NPU execution
  - whether they require special delegate/runtime setup beyond normal LiteRT APIs
  - actual RAM, latency, and battery behavior on device
  - whether partial-result UX is feasible in practice
- Model size remains a major concern:
  - generic FP32: ~2.35 GB
  - Qualcomm-specific FP32 variants: ~1.19–1.2 GB
  - generic INT8: ~596 MB
- Net result:
  - Parakeet is **more credible than previously documented**
  - but it still does **not** outrank Android native as the best current quality path or whisper.cpp as the next research spike with the clearest integration story

## Architecture note — keep fast STT separate from the main LLM

There is an important product/architecture consideration for future voice work:

- **push-to-talk** and especially future **wake-word / always-ready voice entry** should prefer a **small, fast-loading dedicated STT path**
- that STT path should minimise load-time, RAM pressure, and runtime conflict with the main resident reasoning model

Implications:

- Vosk, Android native STT, whisper tiny, or a suitably optimised Parakeet variant are all candidates for the **fast voice-entry path**
- a heavier model-assisted path may still make sense later for **chat voice** or richer conversational voice handling
- but the repo should avoid assuming that the same heavy model is ideal for:
  - wake word
  - quick push-to-talk commands
  - long-form chat voice

The practical design goal should be:

- **lightweight STT for quick entry surfaces**
- **heavier reasoning/model work after transcription**, not as the always-on or quick-load transcription engine

## Recommendations

1. **Merge and harden `#678` first**
   - keep `Vosk` as default
   - keep `Android native` available but non-default
   - expand structured QA while users test on real devices

2. **Use Android native as the current quality benchmark**
   - it is now the strongest tested engine for Kiwi-accent speech on the target device
   - future engine candidates should be judged against it, not just against Vosk

3. **Run `#703` next as the main research spike**
   - scope it to whisper.cpp only
   - focus on whether whisper tiny/base is viable on S23 Ultra for push-to-talk quality

4. **Keep `#700` research-only for now**
   - it is now worth tracking as a credible Qualcomm/LiteRT candidate because SM8550-targeted artifacts exist
   - but it should still wait behind broader Android native QA and the whisper.cpp spike
   - revisit once we know more about backend/runtime behavior and memory cost on the target device

## Product direction for now

- **Default engine:** Vosk
- **Best tested quality path:** Android native STT on S23 Ultra
- **Next research candidate:** whisper.cpp
- **More credible but still deferred candidate:** Parakeet CTC

## References

- Android `SpeechRecognizer` docs: https://developer.android.com/reference/android/speech/SpeechRecognizer
- LiteRT-community Parakeet artifact:
  - https://huggingface.co/litert-community/parakeet-ctc-0.6b
  - https://huggingface.co/litert-community/parakeet-ctc-0.6b/tree/main
- whisper.cpp models and Android example:
  - https://github.com/ggerganov/whisper.cpp/blob/master/models/README.md
  - https://github.com/ggerganov/whisper.cpp/tree/master/examples/whisper.android
- Box reference implementation:
  - https://github.com/jegly/Box
  - https://github.com/jegly/Box/tree/3f973472b46f610e085d7e7cebaad4afbe3bc944/Android/src/whisper
