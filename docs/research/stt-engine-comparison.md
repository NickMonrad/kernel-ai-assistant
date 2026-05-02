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
- **Parakeet CTC** should remain research-only for now

## Engine comparison

| Engine | Strengths | Weaknesses | Recommendation |
| --- | --- | --- | --- |
| **Vosk** | Fully local, deterministic offline behavior, already integrated, low package/runtime cost | Weaker recognition quality for NZ/Kiwi accent on the tested device | Keep as the current privacy/offline baseline and fallback |
| **Android native STT** | Best observed recognition quality so far on S23 Ultra, fast partial results, no model download in app | Privacy/offline guarantees depend on recognizer implementation and language-pack availability | Keep non-default for now, but continue QA as the leading quality option |
| **whisper.cpp** | Stronger general STT quality potential, fully local, proven Android integration pattern in Box | Higher integration complexity, larger models, more RAM/CPU cost, no streaming partials | Proceed with a scoped spike in `#703`, starting with tiny/base push-to-talk only |
| **Parakeet CTC** | Potential long-term local STT candidate | No clean Android path, large model/runtime footprint, higher integration risk, unclear mobile fit | Keep deferred to research-only in `#700` |

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

- Parakeet may still be worth watching, but it is not the best next implementation candidate.
- Main blockers right now:
  - no straightforward Android integration path
  - larger model/runtime burden
  - more uncertain maintainability than whisper.cpp or Android native STT

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
   - document blockers
   - revisit only if the Android/LiteRT path becomes materially clearer

## Product direction for now

- **Default engine:** Vosk
- **Best tested quality path:** Android native STT on S23 Ultra
- **Next research candidate:** whisper.cpp
- **Deferred candidate:** Parakeet CTC

## References

- Android `SpeechRecognizer` docs: https://developer.android.com/reference/android/speech/SpeechRecognizer
- whisper.cpp models and Android example:
  - https://github.com/ggerganov/whisper.cpp/blob/master/models/README.md
  - https://github.com/ggerganov/whisper.cpp/tree/master/examples/whisper.android
- Box reference implementation:
  - https://github.com/jegly/Box
  - https://github.com/jegly/Box/tree/3f973472b46f610e085d7e7cebaad4afbe3bc944/Android/src/whisper
