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

# ── 2026-05-30 Update: PR #1020 Review + Additional STT Options ──────────────

## PR #1020 Review — whisper.cpp + Parakeet CTC Implementation

PR #1020 (`feature/703-700-stt-engines`) adds `WhisperVoiceInputController` and
`ParakeetVoiceInputController`, plus wiring through `SelectableVoiceInputController`,
UI download cards in `VoiceScreen`, and model entries in `KernelModel`.

### Architecture assessment

Both controllers correctly implement `VoiceInputController` and route through the
existing `SelectableVoiceInputController` seam. Hilt `@Inject` constructors work
without explicit `VoiceModule` binds. The UI follows the existing download-card
pattern used by Sherpa-ONNX STT.

### Blocking issues

#### 1. whisper_jni native library missing — controller crashes at init

`WhisperVoiceInputController.init` calls `System.loadLibrary("whisper_jni")` but
the PR contains no CMakeLists.txt, no C++ source, and no prebuilt `.so`. This will
throw `UnsatisfiedLinkError` on first construction. The plan mentions forking Box's
whisper module but that work was not done.

**Fix:** Either add the NDK build (CMake + whisper.cpp source in `:app` or a new
`:core:voice-jni` module), or gate `System.loadLibrary` behind a try/catch and
return `Unavailable` from `ensureModel()`.

#### 2. ParakeetModelSize._2B selectable in UI but never downloaded

`VoiceViewModel.parakeetModels` only lists `PARAKEET_CTC_0_25B` and
`PARAKEET_CTC_TOKENIZER`. When the user selects "2.0B (~1.2 GB)" in the FilterChip
and clicks Download, they get the 0.25B model — the 2B model is defined in
`KernelModel` but never downloaded by any action.

**Fix:** Make `downloadParakeetCtc()` respect `selectedParakeetModelSize` and
download the corresponding model variant.

#### 3. 200+ generated TTS files committed to repo root

`tts_*.{json,srt,tsv,txt,vtt}` files (0000–0041) and the `training/wakeword/`
directory are training pipeline artifacts, not source code. They bloat the diff and
working tree with ~200 files.

**Fix:** Remove them and add to `.gitignore`.

### Non-blocking concerns

| # | Issue | Severity |
|---|-------|----------|
| 4 | **Hardcoded `vocabSize = 128`** in `runInference()` — should derive from output tensor shape. If a different model variant uses a different vocab size, this silently produces garbage. | Medium |
| 5 | **Fragile SentencePiece protobuf parser** — `readVocabEntry` assumes fixed field order (string, float, varint). Protobuf fields can appear in any order; the parser works for the specific file but is not robust. | Medium |
| 6 | **Naive DFT instead of FFT** — `computeMagnitudeSpectrum` is O(N²) per frame. For 5s of 16kHz audio: ~500 frames × 512² ≈ 130M float ops. A Cooley-Tukey FFT would be ~12× faster. Acceptable for push-to-talk but should have a FIXME. | Low |
| 7 | **No wake word integration** — `WakeWordService` calls `sherpaOnnxVoiceInputController.transcribeBlocking(pcm).containsWakePhrase()`. Neither new controller exposes a `transcribeBlocking` method, so wake word verification fails silently when these engines are selected. | Medium |
| 8 | **`containsWakePhrase()` duplicated 3×** — identical function in Whisper, Parakeet, and Sherpa controllers' companion objects. Should live in a shared utility. | Low |
| 9 | **`.vscode/settings.json` committed** — IDE-specific config; should be in `.gitignore`. | Low |

### Test coverage

`SelectableVoiceInputControllerTest` and `VoiceViewModelTest` are updated. The new
controllers themselves have no unit tests — no mock-based test for event flow or
error handling. Given that `WhisperVoiceInputController` cannot load its native
library and `ParakeetVoiceInputController` requires a TFLite model file, mocking
these at the controller level is the right approach.

### Verdict

The Parakeet CTC path is functionally complete but has correctness issues (vocab
size, parser). The whisper.cpp path is a non-functional shell without the native
library. Both paths lack wake word support. The PR should not merge until items 1
(whisper_jni) and 3 (committed artifacts) are resolved.

## Comprehensive STT Options Analysis

### Current landscape (5 engines)

| Engine | Mode | Quality | Size | Streaming | Status |
|--------|------|---------|------|-----------|--------|
| Vosk | Offline CPU | Baseline (weak NZ) | ~50MB | Yes | Default |
| Android Native | Platform | Best NZ accent | ~0MB | Yes | Selectable |
| Sherpa-ONNX Zipformer | Offline GPU/CPU | Poor (merged for streaming) | ~72MB | Yes | Selectable |
| whisper.cpp (PR #1020) | Offline CPU | Unknown (shell only) | ~75MB | No | Broken |
| Parakeet CTC (PR #1020) | Offline CPU | Unknown | 100MB–1.2GB | No | Buggy |

### Options worth exploring (ordered by recommendation)

#### Option A (Top Priority): Sherpa-ONNX alternative model families

The Sherpa-ONNX AAR is already integrated (used for TTS + Zipformer STT).
Sherpa-ONNX supports additional model architectures:

| Model | Type | Quality | Size | Streaming | Notes |
|-------|------|---------|------|-----------|-------|
| **SenseVoice** (Alibaba) | CTC + AED hybrid | High | ~100MB INT8 | Yes | Multilingual, excellent accuracy |
| **Whisper tiny.en** | Encoder-decoder | High | ~75MB INT8 | No | Same model as whisper.cpp, no NDK |
| **Paraformer** (Alibaba) | Fast Conformer | High | ~150MB INT8 | Yes | Streaming-capable |

**How it integrates:**

The existing `SherpaOnnxVoiceInputController` uses reflection to load Sherpa-ONNX
classes. It currently hardcodes Zipformer model files (encoder, decoder, joiner,
tokens). To support other model families:

1. Add a model-family selector to `VoiceInputEngine` (e.g. `SherpaZipformer`,
   `SherpaSenseVoice`, `SherpaWhisper`)
2. Parameterize the model file paths and recognizer type in
   `SherpaOnnxVoiceInputController`:
   - Zipformer → `OnlineRecognizer` (current path)
   - SenseVoice/Whisper → `OfflineRecognizer` (batch API)
3. Add new `KernelModel` entries for the model files
4. UI toggle in `VoiceScreen`

The `OfflineRecognizer` API already exists in the Sherpa-ONNX AAR. The same
reflection pattern would apply:

```kotlin
// Reflected class for offline/batch recognizer
private const val CLS_OFFLINE_REC_CFG = "com.k2fsa.sherpa.onnx.OfflineRecognizerConfig"
private const val CLS_OFFLINE_REC = "com.k2fsa.sherpa.onnx.OfflineRecognizer"
```

**Why this beats whisper.cpp + Parakeet:**
- Zero new dependencies (same AAR)
- No NDK compilation (whisper.cpp needs CMake + JNI)
- No TFLite complexity (Parakeet needs model mapping + MFCC pipeline)
- Streaming models available (SenseVoice, Paraformer)
- Proven runtime with existing audio pipeline and ORT integration

**Whisper tiny.en via Sherpa-ONNX** is the same model architecture as the
custom JNI approach, but loads through the already-debugged Sherpa runtime.

#### Option B: Moonshine (Chicory Wasm, Phase 4 aligned)

[Moonshine](https://github.com/usefulsensors/moonshine) is an optimised
WebAssembly Whisper implementation for edge devices.

**Integration path:**
1. Compile Moonshine to Wasm (already Wasm-targeted)
2. Load via Chicory in `:core:wasm`
3. Implement `VoiceInputController` passing PCM → Wasm linear memory → call
   Moonshine → read transcript

**Tradeoffs:** Aligns with Phase 4 Wasm skill architecture. Sandboxed by design.
But Chicory isn't hardened yet, and Wasm Whisper performance on Android is
uncharacterised. Lower priority than Option A.

#### Option C: Sherpa-ncnn

[sherpa-ncnn](https://github.com/k2-fsa/sherpa-ncnn) uses ncnn instead of ONNX
Runtime — potentially lighter with no ORT dependency.

**Integration path:**
1. Fork the existing Sherpa-onnx controller pattern
2. Replace ONNX Runtime with ncnn JNI calls
3. Add Kotlin bindings for ncnn ASR

**Tradeoffs:** Requires a new AAR dependency (sherpa-ncnn). ncnn may perform
better on Qualcomm GPUs than ORT's NNAPI delegate. Worth tracking but lower
priority than Option A since the existing Sherpa-ONNX AAR already works.

#### Option D: LiteRT Whisper export (not recommended)

Export Whisper tiny to `.tflite`. Would reuse TFLite infrastructure from
Parakeet CTC.

**Integration path:** Similar to `ParakeetVoiceInputController` with Whisper
encoder-decoder splits. The autoregressive decoder requires multiple inference
passes, which is slow on CPU without GPU delegate.

**Tradeoffs:** Avoids NDK but the autoregressive decoder complexity outweighs
benefits over Option A. Don't pursue — Redundant with Option 1 (Sherpa Whisper).

### Strategic recommendation

| Priority | Option | Rationale |
|----------|--------|----------|
| **1** | **Sherpa-ONNX SenseVoice/Whisper** | Zero new dependencies. Same AAR. Better quality than Zipformer. Whisper tiny.en via Sherpa = same model quality as custom JNI without NDK. |
| 2 | Fix PR #1020 Parakeet CTC | Already implemented. Fix the issues identified above. Good TFLite-native path if QNN delegate becomes viable. |
| 3 | Moonshine via Chicory | Strategic for Phase 4 Wasm skills. Not urgent. |
| 4 | Sherpa-ncnn | Track as lighter alternative. Pursue if ORT becomes a constraint. |
| 5 | TFLite Whisper | Redundant with Option 1. Don't pursue. |

### Integration architecture with Sherpa-ONNX alternative models

```
SherpaOnnxVoiceInputController
  ├── OnlineRecognizer (streaming)
  │   ├── Zipformer (current, poor quality)
  │   └── SenseVoice/Paraformer (recommended, high quality)
  └── OfflineRecognizer (batch, push-to-talk)
      └── Whisper tiny.en (recommended, replaces whisper.cpp JNI)
```

### Per-model requirements

| Model | Files Required | Total Size | API |
|-------|---------------|------------|-----|
| SenseVoice (int8) | encoder.onnx, decoder.onnx, tokens.txt | ~100MB | OfflineRecognizer |
| Whisper tiny.en (int8) | encoder.onnx, decoder.onnx | ~75MB | OfflineRecognizer |
| Paraformer (int8) | encoder.onnx, decoder.onnx, tokens.txt | ~150MB | OnlineRecognizer |

All models are available from the Sherpa-ONNX HuggingFace mirrors.

### References

- Sherpa-ONNX pretrained models: https://k2-fsa.github.io/sherpa/onnx/pretrained_models/index.html
- Sherpa-ONNX Android simulate-streaming ASR: https://k2-fsa.github.io/sherpa/onnx/android/apk-simulate-streaming-asr.html
- Sherpa-ONNX offline transducer models: https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-transducer/nemo-transducer-models.html
- sherpa-ncnn: https://github.com/k2-fsa/sherpa-ncnn
- SenseVoice model: https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17
- Moonshine: https://github.com/usefulsensors/moonshine
