# Implementation Plan: #703 (whisper.cpp) + #700 (Parakeet CTC) STT Engines

## Context

Issues #703 and #700 are research spikes for two new STT engine candidates. Both should be evaluated as optional engines alongside the existing `Vosk`, `AndroidNative`, and now `SherpaOnnx` paths.

**IMPORTANT:** Sherpa-ONNX streaming STT (Zipformer int8) is now merged to main via PR #995. However, **Sherpa-ONNX STT quality is poor** — it was merged for streaming capability, not recognition quality. Android native remains the quality benchmark for Kiwi-accent speech on S23 Ultra.

**Test device:** Samsung Galaxy S23 Ultra (SM8550, 12GB RAM, Android 16).

## Current Architecture

```
User voice input
    │
    ▼
┌──────────────────────────────────────────────────┐
│  SelectableVoiceInputController (singleton)       │
│  Routes to active controller based on             │
│  VoiceInputPreferences.selectedEngine             │
└──┬──────────┬──────────────┬──────────────┬───────┘
   │          │              │              │
   ▼          ▼              ▼              │
 Vosk     AndroidNative   SherpaOnnx       [NEW]
 (bundled)  (platform)    (streaming)      whisper.cpp, Parakeet
```

**Current engine status:**
- `Vosk` — bundled, offline-first, baseline quality
- `AndroidNative` — platform SpeechRecognizer, best quality for NZ accent on S23 Ultra
- `SherpaOnnx` — streaming Zipformer int8, poor recognition quality, merged for streaming capability

## Phase 1 — whisper.cpp integration (#703)

### 1A. Research & dependency decision

**Goal:** Determine how to integrate whisper.cpp into the Android app without NDK complexity in the Gradle build.

**Key findings from existing research (`docs/research/stt-engine-comparison.md`):**
- Box repo (`jegly/Box`) demonstrates a viable pattern: dedicated JNI/NDK bridge module
- whisper.cpp models: tiny (~75MB), base (~130MB), small (~460MB)
- No streaming partial results — batch transcription only
- Heavier RAM/CPU than Vosk and Android native

**Approach options:**

| Option | Pros | Cons |
|--------|------|------|
| A. Fork Box whisper module | Proven Android integration, JNI already written | Adds large C++ dependency, NDK build complexity |
| B. Use `ai.lug.moses:whisper` AAR if available | Minimal integration effort | May not exist / may be unmaintained |
| C. LiteRT `.tflite` export of whisper model | Uses existing LiteRT infrastructure, no NDK | No published whisper TFLite model found |

**Decision:** Start with **Option A** (Box whisper module pattern) since it's the only proven path. If the NDK complexity proves too high for a spike, fall back to evaluating whether a TFLite export exists.

**Research deliverables:**
- [ ] Confirm whisper.cpp Android build can be integrated as a local AAR or subproject
- [ ] Identify smallest viable model (tiny-encoder first, then base)
- [ ] Measure RAM usage alongside Gemma-4 resident model on S23 Ultra
- [ ] Measure cold start + inference latency on S23 Ultra for 5s audio clip
- [ ] Document findings in `docs/research/stt-engine-comparison.md`

### 1B. `WhisperVoiceInputController` implementation

**File:** `core/voice/src/main/java/com/kernel/ai/core/voice/WhisperVoiceInputController.kt`

**Design:**
- Implements `VoiceInputController` interface (same as Vosk, AndroidNative, SherpaOnnx)
- Records audio to a temporary WAV/PCM file at 16kHz mono
- On `stopListening()`, passes the file to the whisper.cpp JNI bridge
- Emits `VoiceInputEvent.Transcript` with the result
- No partial results (whisper.cpp doesn't stream) — this is a known limitation documented in the engine warning

**Model management:**
- whisper.cpp model files stored alongside other STT models
- Downloaded via `ModelDownloadManager` using a new `KernelModel` entry or via the voice pack download system
- Model selection (tiny/base) exposed as a sub-setting under the Whisper engine

**Key differences from existing controllers:**
- No partial transcript support — emits only final `Transcript` event
- Batch-only: transcribes after recording stops, not during

### 1C. Wire up

- [ ] Add `WhisperCpp` to `VoiceInputEngine` enum
- [ ] Add `WhisperVoiceInputController` to `SelectableVoiceInputController` routing
- [ ] Wire DI in `VoiceModule.kt`
- [ ] Update `VoiceScreen.kt` settings UI to show Whisper as an option with model download status

## Phase 2 — Parakeet CTC integration (#700)

### 2A. Research & model selection

**Goal:** Evaluate Parakeet CTC as a LiteRT-based STT engine leveraging existing TFLite infrastructure.

**Key findings from existing research:**
- Parakeet is a CTC-based model family (similar architecture to Vosk but different training)
- Can run via LiteRT (TFLite) — uses existing `:core:inference` infrastructure
- Model sizes: ~600MB for full precision, ~300MB for quantized variants
- No streaming support in CTC models — batch transcription only
- **RAM constraint**: 600MB model alongside Gemma-4's ~3.4GB = 4GB+ just for models. On 12GB device this is tight; on 8GB it's impossible.

**Research deliverables:**
- [ ] Locate Parakeet CTC model files (TFLite export)
- [ ] Verify LiteRT compatibility with existing inference engine
- [ ] Measure RAM usage alongside Gemma-4 resident model on S23 Ultra
- [ ] Measure cold start + inference latency on S23 Ultra for 5s audio clip
- [ ] Document findings in `docs/research/stt-engine-comparison.md`

### 2B. `ParakeetVoiceInputController` implementation

**File:** `core/voice/src/main/java/com/kernel/ai/core/voice/ParakeetVoiceInputController.kt`

**Design:**
- Implements `VoiceInputController` interface
- Uses LiteRT inference engine for model execution
- Records audio to temporary buffer, transcribes in batch mode
- Emits `VoiceInputEvent.Transcript` with the result

**Model management:**
- Parakeet model files stored in app's model directory
- Downloaded via `ModelDownloadManager` with new `KernelModel` entry
- Model selection (if multiple variants) exposed as a sub-setting under the Parakeet engine

### 2C. Wire up

- [ ] Add `ParakeetCtc` to `VoiceInputEngine` enum
- [ ] Add `ParakeetVoiceInputController` to `SelectableVoiceInputController` routing
- [ ] Wire DI in `VoiceModule.kt`
- [ ] Update `VoiceScreen.kt` settings UI to show Parakeet as an option with model download status

## Phase 3 — Verification & testing

- [ ] Unit tests for `WhisperVoiceInputController` (mock whisper JNI, test event flow)
- [ ] Unit tests for `ParakeetVoiceInputController` (mock LiteRT, test event flow)
- [ ] Update `SelectableVoiceInputControllerTest` to cover new routing paths
- [ ] Verify build passes with `./gradlew assembleDebug`
- [ ] Manual testing on S23 Ultra: record audio, transcribe, compare accuracy vs Vosk/SherpaOnnx

## Key Files

- `core/voice/src/main/java/com/kernel/ai/core/voice/VoiceInputController.kt` — interface
- `core/voice/src/main/java/com/kernel/ai/core/voice/SelectableVoiceInputController.kt` — router
- `core/voice/src/main/java/com/kernel/ai/core/voice/VoiceInputEngine.kt` — enum
- `core/voice/src/main/java/com/kernel/ai/core/voice/di/VoiceModule.kt` — DI
- `core/voice/src/main/java/com/kernel/ai/core/voice/VoiceInputPreferences.kt` — preferences
- `feature/settings/src/main/java/com/kernel/ai/feature/settings/VoiceScreen.kt` — UI
- `feature/settings/src/main/java/com/kernel/ai/feature/settings/VoiceViewModel.kt` — view model
- `core/inference/src/main/java/com/kernel/ai/core/inference/download/KernelModel.kt` — model definitions
- `docs/research/stt-engine-comparison.md` — research findings

## Risks

- **whisper.cpp NDK build complexity**: If Box module fork is too complex, fall back to research-only (document findings, no integration)
- **Parakeet model size/RAM**: ~300-600MB alongside Gemma-4 (~3.4GB) may exceed available RAM on 8GB devices; test on S23 Ultra first
- **Research scope creep**: Both issues are spikes — if integration proves too complex, document findings and close as research-complete without shipping the engine

## Acceptance criteria

Per issue:
- **#703**: whisper.cpp evaluated as STT candidate; findings documented in research doc; working controller shell if feasible
- **#700**: Parakeet CTC evaluated as STT candidate; findings documented in research doc; working controller shell if feasible

Both issues closed with a PR containing:
1. Research findings added to `docs/research/stt-engine-comparison.md`
2. Working controller implementation (if feasible) OR research-only documentation
3. Settings UI updated to show new engine options (if integrated)