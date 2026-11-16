# #1937 Settings › Voice speech recognition evidence

- Device: Samsung SM-S918B (S23 Ultra, `RFCW11265XH`), Android 16, package `ai.jandal.assistant.debug`, 2026-11-16, built from this branch (it includes #1957).
- Settings › Voice, "Speech recognition" section. Status bar cropped; app content only, no personal data.
- Device settings changed for the run and restored afterwards: `font_scale` (was 1.15), night mode (was yes), media volume (was 11, set to 3).
- Speech input was Automatic before the run. Android native was selected for its captures (Settings saves at once) and Automatic was selected again afterwards; the switch was not toggled.

Files: `<theme>-font<scale>-<view>.png`, themes dark and light, font scales 1.0 (default) and 2.0 (largest).

| View | Shows |
|---|---|
| `auto-top` | Automatic selected: inset cards, "Automatic" + Recommended tag, short description, the online switch inside the card below a divider, **More** |
| `auto-next-card` | The bottom of the Automatic card (switch and More inside its border) and the Android native card after it |
| `native-top` | Android native selected: language as the third line ("English (Australia)"), the switch inside its card |
| `native-next-card` | The bottom of the Android native card and the Zipformer card after it |
| `sherpa` | Sherpa engines: Downloaded / Not downloaded chips; a not-downloaded engine's radio disabled, its download card and outlined **Download** button inside the card |
| `more-sheet` (font 1.0) | **More** opens the full explanation in a bottom sheet |

`TEST-SM-S918B-speech-recognition-section-androidTest.xml`: `SpeechRecognitionSectionTest` on the S23U (`ANDROID_SERIAL=RFCW11265XH ./gradlew :feature:settings:connectedDebugAndroidTest`): 3 tests, 0 failures. The switch is a descendant of the selected Automatic card, of the selected Android native card and not of the card after it, and absent when a Sherpa engine is selected.
