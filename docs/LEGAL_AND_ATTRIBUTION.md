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

### Release dependencies

Generate release dependency notices from the resolved release variant before Play Store launch. The final notice set should cover runtime dependencies such as AndroidX/Jetpack, Hilt/Dagger, Kotlin coroutines, OkHttp, AppAuth, AndroidX Security Crypto, Apache Commons Compress, ONNX Runtime, TensorFlow Lite, MediaPipe Tasks Text, Coil, Vosk Android, and any other libraries bundled in the release artefact. Debug/test-only dependencies such as LeakCanary, MockK, and JUnit should not be included unless bundled in release.

### Bundled native/source assets

The release review must verify exact provenance and licence/notice requirements for bundled native/source assets, especially:

- `sqlite-vec` source in `core/memory/src/main/cpp/sqlite-vec.c` and `.h`;
- SQLite amalgamation if actually bundled in the release native artefact;
- bundled wake-word ONNX model assets;
- Sherpa-ONNX AAR if it is present in the release build rather than only local development.

### Model and speech assets

Model files are not committed to this repository, but release documentation must record model-card/licence terms for any model exposed in release builds.

Known launch-relevant model families:

- Gemma-4 E-2B LiteRT-LM (~2.58 GB);
- Gemma-4 E-4B LiteRT-LM (~3.65 GB);
- EmbeddingGemma 300M generic (~171 MB, gated);
- EmbeddingGemma 300M SM8550 (~350 MB, gated, currently deprecated in code);
- MiniLM-L6 intent classifier (~23 MB, marked bundled);
- Sherpa Zipformer STT (~72 MB);
- Sherpa SenseVoice STT (~100 MB);
- Sherpa Whisper tiny.en STT (~117 MB);
- Sherpa Paraformer STT (~220 MB);
- Sherpa Piper/VITS voice packs (~64-116 MB each);
- Semaine Piper/VITS voice pack (~70 MB, launch-blocking decision in #1258);
- Kokoro experimental voice pack (~130 MB, verify before release if exposed).

## Native skills and external services

Most native skills are local Android actions. Some skills use external data sources or Android platform services:

- Weather and forecast data;
- Currency exchange rates;
- Wikipedia lookups;
- Maps/navigation intents;
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
