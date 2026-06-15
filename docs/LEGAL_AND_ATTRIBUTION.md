# Legal, licence, and attribution review

This document supports #868 and the Play Store launch gate. It is a practical engineering checklist, not legal advice.

## Source licence

Jandal AI source code is distributed under the Apache License 2.0. See [`../LICENSE`](../LICENSE).

The repository also includes a [`../NOTICE`](../NOTICE) file for attribution notices that should travel with source or binary distributions where applicable.

## Launch principle

Before Play Store release, every shipped or downloadable third-party component should be in one of these states:

1. **Bundled in the APK/source distribution** — licence and required notice are included in `NOTICE`, app documentation, or an in-app notices screen.
2. **Downloaded at runtime** — user-facing documentation explains the upstream provider and that the asset remains subject to its upstream licence/model card/terms.
3. **Development-only** — not listed as an end-user runtime component, but documented if required for reproducible development or testing.
4. **Research / not shipped** — clearly marked as future/research so it is not accidentally represented as launch capability.

## Components to verify before launch

### Android and Kotlin libraries

Declared in [`../gradle/libs.versions.toml`](../gradle/libs.versions.toml):

- Android Gradle Plugin, Kotlin, KSP.
- AndroidX / Jetpack libraries: Compose, Navigation, Core, Lifecycle, Room, DataStore, Browser, WorkManager, Glance, AndroidX Test, UIAutomator.
- Hilt / Dagger.
- Kotlin coroutines.
- OkHttp.
- AppAuth.
- AndroidX Security Crypto.
- JUnit, MockK, LeakCanary for test/debug use.

Launch action:

- [ ] Generate or manually review third-party dependency notices for release dependencies.
- [ ] Keep debug/test-only dependencies out of end-user notices unless they are bundled in release artefacts.
- [ ] Confirm whether the app needs an in-app open-source licences screen for Play Store readiness.

### Local inference and model runtime

Runtime / implementation components:

- Google AI Edge LiteRT / LiteRT-LM.
- TensorFlow Lite runtime / GPU delegate where used.
- MediaPipe Tasks Text where used.
- ONNX Runtime for wake-word / voice inference paths.

Existing source NOTICE entries already cover Google AI Edge Gallery adaptation and LiteRT-LM usage. Keep them current if more code is adapted from upstream projects.

Launch action:

- [ ] Confirm the final release APK contains only expected runtime libraries.
- [ ] Add any required runtime library notices to `NOTICE` or an in-app notices surface.
- [ ] Document whether runtime libraries are bundled in APK or only used in local/dev builds.

### Chat and embedding models

Model files are not committed to the repository. Current model setup is documented in [`../models/README.md`](../models/README.md).

Launch-relevant model families include:

- Gemma-4 E-2B LiteRT-LM.
- Gemma-4 E-4B LiteRT-LM.
- EmbeddingGemma 300M.
- Optional FunctionGemma/mobile-actions model where used.

Important user-facing rule:

> Some model downloads are gated. Users may need to sign in to Hugging Face and accept the upstream model licence/terms before Jandal can download or use those models.

Launch action:

- [ ] For each model exposed in release builds, record the upstream repository/model card in the final launch notes.
- [ ] Confirm which models are required, optional, or device-tier-specific.
- [ ] Confirm download sizes shown in the app match the documented approximate sizes.
- [ ] Confirm the app handles gated/download-pending states without implying Jandal owns or sublicenses the upstream model.
- [ ] Add model-provider and licence/terms wording to Play Store listing/privacy/disclosure text if required.

### Speech-to-text

Launch STT paths currently include:

- Android native STT.
- Vosk.
- Sherpa-ONNX Zipformer.
- Sherpa-ONNX SenseVoice.
- Sherpa-ONNX Whisper tiny.en.
- Sherpa-ONNX Paraformer.

Launch action:

- [ ] Confirm which STT engines are available in the launch build.
- [ ] Confirm whether each engine is bundled, downloaded, or optional.
- [ ] Add upstream model/source references for any bundled or downloadable STT models.
- [ ] Make it clear in docs/app UI which STT paths are fully offline and which depend on Android system services.

### Text-to-speech and voice packs

Launch TTS paths currently include:

- Sherpa-ONNX Piper/VITS voice packs.
- Android TTS fallback.
- Kokoro research path where present.

Current Piper/VITS voice packs are downloaded from Sherpa-ONNX release assets by voice key. Approximate sizes are encoded in `SherpaPiperVoice` and surfaced in the app UI.

Launch action:

- [ ] Confirm which TTS voice packs are exposed in release.
- [ ] For each voice pack, record upstream source, licence/model-card terms, and whether attribution is required.
- [ ] Check special dataset attribution requirements for multi-speaker voices such as VCTK and Semaine before launch.
- [ ] Ensure any Kokoro references remain clearly marked as research if not shipped.

### Native skills and external services

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

- [ ] README has a concise feature overview rather than a long implementation changelog.
- [ ] README includes the voice/STT/TTS tech stack.
- [ ] README links to model setup and licence/attribution docs.
- [ ] `models/README.md` documents approximate model sizes and gated-model requirements.
- [ ] `NOTICE` includes source-code adaptation notices and points to this review for runtime/downloadable assets.
- [ ] Any in-app open-source licences screen is either implemented or consciously deferred with a launch decision.
- [ ] Play Store privacy/data disclosures match the actual release build.
- [ ] #868 is closed only after the final release scope is known and the checklist above has been reviewed.
