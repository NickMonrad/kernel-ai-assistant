# Release attribution sign-off snapshot

Parent issue: #1263  
Related: #868, #1257, #1258, #1268, #1014, #441, #1474

This is the current engineering release-attribution snapshot for Jandal v0.1. It is not legal advice and is not a Play release approval.

## v0.1 release scope

The owner rule recorded on #1263 is fail-closed: any Sherpa/Piper/eSpeak voice or component whose distribution, corresponding-source, attribution, or notice obligations remain inadequately evidenced is excluded from v0.1.

The release build therefore uses:

- Android native speech recognition for voice input;
- Android text-to-speech for spoken output;
- the bundled openWakeWord-based Hey Jandal detector, using its primary high-confidence detector path;
- no custom Sherpa/Piper/eSpeak AAR, Sherpa STT runtime, Piper voice pack, Inflect Micro runtime, or Kokoro runtime.

Debug builds retain the full Sherpa/Inflect/Piper/Kokoro development paths.

## Generated release evidence

PR #1793 adds reproducible release evidence from the resolved release variant:

- AboutLibraries release dependency data for the offline Open-source licences view;
- CycloneDX JSON and XML SBOMs from `releaseRuntimeClasspath`;
- `:app:verifyReleaseAttributionMetadata`, which fails the build when a resolved release module lacks licence metadata, a local runtime file lacks explicit reviewed metadata, or the exact release AAB native-library set differs from the reviewed native inventory;
- `app/release-attribution-metadata.json` for packaged native libraries that need explicit source/licence mapping beyond the Gradle dependency graph.

The verifier runs against the exact AAB produced by `bundleRelease`. CI evidence for the final PR head must be green before this snapshot is treated as review-ready.

## Release-exposed assets

| Area | v0.1 status | Attribution position |
|---|---|---|
| Gemma 4 E-2B / E-4B LiteRT-LM | Download on demand | Named upstream LiteRT-LM repositories; Apache-2.0 metadata recorded. |
| Arctic Embed M v1.5 + vocabulary | Download/bundled vocabulary | Snowflake source revision `e58a8f756156a1293d763f17e3aae643474e9b8a`; Apache-2.0; pinned vocabulary identity recorded. |
| MiniLM-L6 intent classifier | Bundled | Exact pre-converted asset from `Nihal2000/all-MiniLM-L6-v2-quant.tflite` commit `ad6c27c81b2c156925c1fb038d533f52f94ccd77`; Apache-2.0; bundled SHA-256 `0aac5b0b76be23ab94f065a7fab6e0daead5e57f6ff7d55e19a2641d6a81f276`; upstream lineage is `sentence-transformers/all-MiniLM-L6-v2`. |
| Android native STT | Release-visible | Android platform/provider terms; offline availability is device/provider dependent. |
| Sherpa STT engines/models | Debug/internal only | Excluded from v0.1 with the unresolved Sherpa runtime distribution path. |
| Android TTS | Release-visible | Android platform/installed-engine terms. |
| Piper/Cori/Semaine voices | Debug/internal only | Excluded from v0.1. Cori provenance remains documented but is not release clearance. |
| Inflect Micro | Debug/internal only | Upstream ONNX model is documented; runtime depends on the excluded custom Sherpa AAR. |
| Kokoro | Debug/post-launch only | Sherpa package and upstream Kokoro lineage remain documented. |
| openWakeWord Stage 1/2 | Bundled | Apache-2.0; bundled asset hashes recorded in the in-app catalogue. |
| Hey Jandal Stage 3 | Bundled | Jandal-generated classifier. The training record includes consented maintainer/spouse recordings plus Piper-generated synthetic clips; no third-party human recordings were used. |

Vosk has been removed and is not part of the current release.

## In-app legal surfaces

Settings/About → Legal & Privacy provides:

- Privacy Policy;
- Terms of Use;
- offline Open-source licences generated from the resolved release variant plus the bundled notice text;
- offline Models, voices & wake word attribution with source, terms/lineage, and release status;
- Data & Privacy Summary navigation owned by #1260.

The app's existing About surface displays version name and version code.

## Native release inventory

The exact release AAB is checked against `app/release-attribution-metadata.json`. Current expected source families are:

- LiteRT / LiteRT OpenCL accelerator — Apache-2.0;
- AndroidX graphics-path and DataStore native support — Apache-2.0;
- Android NDK libc++ — Apache-2.0 WITH LLVM-exception;
- Jandal `libkernelvec.so` — Jandal JNI plus sqlite-vec (MIT) and SQLite (public domain);
- LiteRT-LM JNI — Apache-2.0;
- ONNX Runtime JNI/runtime — MIT.

The custom Sherpa AAR, Sherpa JNI, Vosk and JNA are not expected in v0.1 after the release-scope correction. The fail-closed verifier must confirm the actual AAB rather than relying on this list.

## Remaining release acceptance

The code/metadata work in #1793 is complete only when exact-head CI passes the release bundle, attribution verifier, unit tests, lint, Android test compilation and repository checks.

The following remain release-acceptance activities outside this code repair and must not be claimed without evidence:

- release-build offline navigation/display validation on a device;
- TalkBack/accessibility validation;
- light, dark and system-colour validation;
- final #1014 and #441 status updates tied to the selected release candidate.

