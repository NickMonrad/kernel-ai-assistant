# Legal, licence, and attribution review

This document supports #868 and the Play Store launch gate. It is a practical engineering checklist, not legal advice.

## Source licence

Jandal AI source code is distributed under the Apache License 2.0. See [`../LICENSE`](../LICENSE).

The repository also includes a [`../NOTICE`](../NOTICE) file for attribution notices that should travel with source or binary distributions where applicable.

## Launch principle

Before Play Store release, every shipped or downloadable third-party component should be in one of these states:

1. **Bundled in the APK/source distribution** - licence and required notice are included in `NOTICE`, app documentation, or an in-app notices screen.
2. **Downloaded at runtime** - user-facing documentation explains the upstream provider and that the asset remains subject to its upstream licence/model card/terms.
3. **Development-only** - not listed as an end-user runtime component, but documented if required for reproducible development or testing.
4. **Research / not shipped** - clearly marked as future/research so it is not accidentally represented as launch capability.

## Launch-blocking decisions

### #1258 - Semaine voice pack

The app currently exposes `Semaine` as a Sherpa/Piper voice option. The voice entry is roughly 70 MB and uses the `vits-piper-en_GB-semaine-medium` release asset.

The launch concern is that Semaine may be derived from a non-commercial Creative Commons licence path. If the applicable licence is **CC BY-NC-SA 4.0** or similar, attribution alone is not enough: Creative Commons describes the licence as requiring attribution, restricting use to NonCommercial purposes, and requiring ShareAlike terms for adaptations.

Decision required before launch:

- [ ] Verify the exact upstream Semaine voice/model/dataset licence.
- [ ] Decide whether Semaine is removed/hidden from release, kept dev-only, replaced, or shipped only with explicit compatible permission.
- [ ] Update code/docs to match the decision.
- [ ] Do not close #868 as fully launch-complete while #1258 remains unresolved.

Suggested default: **do not ship Semaine in the Play Store release unless compatible rights are confirmed.**

## Component inventory

### Source-code adaptations

| Component | Role | Current attribution status | Action |
|---|---|---|---|
| Google AI Edge Gallery | Adapted LiteRT-LM inference, model download, and chat streaming patterns | Listed in `NOTICE` | Keep current. |
| Google LiteRT-LM | Android LiteRT-LM library | Listed in `NOTICE` | Keep current. |

### Gradle / Maven dependencies

Release dependency notices must be generated from the resolved release variant. The hand-written list below is only a review aid.

| Dependency group | Examples | Launch action |
|---|---|---|
| AndroidX / Jetpack | Compose, Navigation, Core, Lifecycle, Room, DataStore, Browser, WorkManager, Glance, AndroidX Test, UIAutomator | Generate/review release notices. |
| Runtime libraries | Hilt, Coroutines, OkHttp, AppAuth, Security Crypto, Commons Compress, ONNX Runtime, TFLite, MediaPipe, Coil, Vosk | Include runtime notices where applicable. |
| Debug/test only | LeakCanary, MockK, JUnit | Exclude unless bundled in release artefacts. |

### Bundled native source and assets

| Component / asset | Evidence in repo | Launch action |
|---|---|---|
| `sqlite-vec` | `core/memory/src/main/cpp/sqlite-vec.c` and `.h` | Verify upstream licence and add exact notice before launch. |
| SQLite amalgamation | `core/memory/src/main/cpp/sqlite3.c` is referenced by CMake | Confirm whether SQLite is actually bundled in release native artefacts. |
| Wake-word ONNX model(s) | bundled wake-word model asset exists | Record training/provenance and ownership before release. |
| Sherpa AAR under `third_party/sherpa-onnx/` | gitignored, downloaded by local setup script | Confirm release packaging. |

## Model and speech asset inventory

### Chat and embedding models

| Model / file family | Approx. size | Gated in app code | Launch action |
|---|---:|---|---|
| Gemma-4 E-2B LiteRT-LM | ~2.58 GB | No | Record exact model-card/licence terms before release. |
| Gemma-4 E-4B LiteRT-LM | ~3.65 GB | No | Record exact model-card/licence terms before release. |
| EmbeddingGemma 300M generic | ~171 MB | Yes | Keep Hugging Face sign-in/licence acceptance wording. |
| EmbeddingGemma 300M SM8550 | ~350 MB | Yes; currently deprecated in code | Confirm whether hidden/deprecated status is intended for launch. |
| MiniLM-L6 intent classifier | ~23 MB | No; marked bundled | Confirm file provenance, licence, and whether the asset is actually present in release artefacts. |

### Speech-to-text assets

| Engine | Approx. size | Launch action |
|---|---:|---|
| Android native STT | N/A | Document that offline behaviour depends on Android/device support. |
| Vosk | model status to verify | Confirm bundled/downloaded model provenance and licence. |
| Sherpa Zipformer | ~72 MB | Record upstream model licence/source. |
| Sherpa SenseVoice | ~100 MB | Record upstream model licence/source. |
| Sherpa Whisper tiny.en | ~117 MB | Code comments say Apache 2.0; verify upstream before launch. |
| Sherpa Paraformer | ~220 MB | Code comments say Apache 2.0; verify upstream before launch. |

### Text-to-speech assets

| Engine / voice pack | Approx. size | Status | Launch action |
|---|---:|---|---|
| Android TTS | N/A | Safe fallback | Document platform dependency. |
| Sherpa Piper/VITS voices other than Semaine | ~64-116 MB each | Needs exact per-voice licence verification | Record upstream model-card/dataset licence before release. |
| Sherpa Piper/VITS Semaine | ~70 MB | **Launch blocker: #1258** | Verify licence and decide ship/disable/replace. |
| Kokoro experimental | ~130 MB | Experimental | If exposed in release, verify model/voice licence before launch; otherwise hide as research/dev-only. |

## Native skills and external services

Most native skills are local Android actions. Some skills use external data sources or Android platform services:

- Weather and forecast data.
- Currency exchange rates.
- Wikipedia lookups.
- Maps/navigation intents.
- Email/SMS/calendar/contacts flows, depending on granted permissions and installed apps.

Launch action:

- [ ] Document any external web/API data sources that remain enabled in release builds.
- [ ] Confirm privacy disclosures match actual network behaviour.
- [ ] Confirm user-facing docs distinguish local skills from skills that open another app or call an external source.

## Suggested README / Play Store wording

Use concise wording such as:

> Jandal runs chat and memory locally on your device where supported. Some optional models, speech engines, and voices are downloaded separately and may require accepting the provider's licence or model terms. Jandal does not sell user data or send chat/memory content to a cloud model by default.

Avoid promising that every feature is offline if a skill may call an external source, open another app, or depend on Android system services.

## Release checklist

- [x] README has a concise feature overview rather than a long implementation changelog.
- [x] README includes the voice/STT/TTS tech stack.
- [x] README links to model setup and licence/attribution docs.
- [x] `models/README.md` documents approximate model sizes and gated-model requirements.
- [x] `NOTICE` includes source-code adaptation notices and points to this review for runtime/downloadable assets.
- [ ] #1258 Semaine launch decision is resolved.
- [ ] Release dependency notices are generated from the release variant.
- [ ] Native bundled source licences/provenance are verified, especially `sqlite-vec` and bundled wake-word ONNX assets.
- [ ] Any in-app open-source licences screen is implemented or consciously deferred with a launch decision.
- [ ] Play Store privacy/data disclosures match the actual release build.
- [ ] #868 is closed only after the final release scope is known and the checklist above has been reviewed.
