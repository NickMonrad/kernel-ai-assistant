# Jandal AI

*Play Store release in progress. Pre-release builds are available from [GitHub Releases](https://github.com/NickMonrad/kernel-ai-assistant/releases).*

Jandal AI is a local-first Android assistant. It combines on-device chat, long-term memory, deterministic Android actions, voice input/output, and native tool calling while keeping user data on the device by default.

## How it works

Jandal is built around a **Brain-Memory-Action** model:

- **Brain** - Gemma-4 E-2B / E-4B runs locally through Google AI Edge LiteRT / LiteRT-LM. A lightweight `QuickIntentRouter` handles deterministic fast paths for common device actions.
- **Memory** - local Room storage plus sqlite-vec / EmbeddingGemma support semantic recall, conversation history, core memories, and episodic summaries.
- **Action** - native Kotlin skills execute Android actions such as alarms, timers, lists, notes, weather, media controls, messages, email, calendar, navigation, Wikipedia, and unit/currency conversion.

## Current launch status

Launch readiness is tracked in GitHub rather than duplicated in this README:

- [#1014 - Play Store Launch Readiness & QA](https://github.com/NickMonrad/kernel-ai-assistant/issues/1014) is the parent launch epic and current launch tracking issue.
- The `launch:blocking` label and [Jandal Launch Backlog](https://github.com/users/NickMonrad/projects/5) project views are the live launch blocker queue and filtered work dashboard.
- [`docs/PLAN-launch-slice.md`](docs/PLAN-launch-slice.md) is a historical launch-slice snapshot, not the current plan.
- [#1255 - Launch Plan: ordered implementation sequence and release gates](https://github.com/NickMonrad/kernel-ai-assistant/issues/1255) is the now-completed model-readiness preflight tracker, not the active launch plan.

The current launch gates are:

1. Launch scope and backlog sanity.
2. Test/evidence foundation.
3. Core app, accessibility, and first-run reliability.
4. Permissions and Android capability repair.
5. Voice and wake-word launch-risk validation.
6. Final release QA and store readiness.

## Feature areas

Jandal currently includes:

- local chat with markdown rendering, multi-conversation management, streaming generation, and model/runtime status;
- local memory with core memories, episodic summaries, semantic search, and memory management screens;
- deterministic Android skills for lists, notes, alarms, timers, date arithmetic, media controls, unit/currency conversion, weather, messaging/email flows, navigation, and Wikipedia;
- drawer-accessible tools such as Lists, Alarms, Notes, Meal plans, and Settings;
- deterministic meal-planning sessions with plan approval, recipe persistence, favourites, replacement/regeneration, and ingredient export to lists;
- push-to-talk voice input, optional spoken chat replies, per-message speaker playback, configurable TTS voices, and wake-word/default-assistant infrastructure;
- local model management and model availability states for required, optional, preparing, gated, and unavailable model paths.

Future roadmap work includes Dreaming Engine background consolidation, Wasm skill extensibility, broader device optimisation, and richer multimodal/vision workflows. These are intentionally separated from the launch plan unless explicitly promoted.

## Tech stack

| Area | Technology |
|------|------------|
| Language | Kotlin |
| UI | Jetpack Compose, Material 3 Dynamic Color, Glance widgets |
| Dependency injection | Hilt |
| Persistence | Room, DataStore |
| Background work | WorkManager |
| Local inference | Google AI Edge LiteRT / LiteRT-LM |
| Chat models | Gemma-4 E-2B / E-4B LiteRT-LM packages |
| Embeddings / RAG | EmbeddingGemma-300M, sqlite-vec |
| Quick actions | Kotlin `QuickIntentRouter` with deterministic routing and slot filling |
| Tool calling | LiteRT-LM native `@Tool` annotations and app-owned native skills |
| STT | Android native STT, Vosk, Sherpa-ONNX Zipformer / SenseVoice / Whisper tiny.en / Paraformer |
| TTS | Sherpa-ONNX Piper/VITS voice packs, Android TTS fallback, Kokoro research path |
| Wake word | Sherpa-ONNX verification and ONNX Runtime wake-word pipeline |
| Auth for gated downloads | Hugging Face OAuth via AppAuth |
| Extensibility research | Chicory WebAssembly runtime |

## Models and downloads

Model files are **not committed to this repository**. Some model downloads are gated and require a Hugging Face account plus acceptance of the upstream model licence/terms before the app can download or use them.

See [`models/README.md`](models/README.md) for the current model file reference, approximate sizes, ADB setup notes, and device-specific guidance.

Key launch-relevant examples:

| Model / asset | Approx. size | Notes |
|---------------|--------------|-------|
| Gemma-4 E-2B LiteRT-LM | ~2.4 GB | Required launch-compatible chat model tier |
| Gemma-4 E-4B LiteRT-LM | ~3.4 GB | Optional flagship-tier chat model |
| EmbeddingGemma 300M | varies by file | Required for local embedding/RAG paths where enabled |
| Sherpa STT models | ~72-220 MB each | Downloaded per selected STT engine |
| Sherpa Piper/VITS voice packs | ~64-116 MB each | Downloaded per selected voice; Semaine launch decision tracked in #1258 |

## Privacy model

Jandal is designed as a local-first assistant:

- chat, memories, notes, lists, and model execution are local by default;
- production builds should not include telemetry;
- external network calls are limited to explicit web-backed skills or model/voice downloads where configured;
- Hugging Face sign-in is used only for gated model access.

## Documentation

- [`models/README.md`](models/README.md) - model file setup, sizes, and ADB paths.
- [`docs/LEGAL_AND_ATTRIBUTION.md`](docs/LEGAL_AND_ATTRIBUTION.md) - launch attribution and licence checklist.
- [`docs/SPECIFICATION.md`](docs/SPECIFICATION.md) - broader product and architecture specification.
- [`docs/ROADMAP.md`](docs/ROADMAP.md) - broader roadmap notes.
- [`docs/testing/`](docs/testing/) - automated test harness, evidence schema, and QA documentation.

## Licence and attribution

The source code is licensed under the Apache License 2.0. See [`LICENSE`](LICENSE).

Third-party notices are maintained in [`NOTICE`](NOTICE). Launch readiness for third-party libraries, voice assets, and gated model downloads is tracked in [`docs/LEGAL_AND_ATTRIBUTION.md`](docs/LEGAL_AND_ATTRIBUTION.md).
