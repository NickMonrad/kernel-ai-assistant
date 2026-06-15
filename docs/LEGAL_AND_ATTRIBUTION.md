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
