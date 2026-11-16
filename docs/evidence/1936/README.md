# #1936 / #1929 / #1931 setup guide evidence

- Device: Samsung SM-S918B (S23 Ultra, `RFCW11265XH`), Android 16, package `ai.jandal.assistant.debug`, 2026-11-16. Round 2 re-capture of every image (title and chip in a `FlowRow` on setting cards; divider under the pinned step header while scrolled).
- Opened from Settings › Set up Jandal again, so the guide runs in re-run mode: this phone already has every model, so step 1 says "Chat is ready" and step 3 says "Already downloaded". The guide was left with Back on step 1; nothing was saved.
- Status bar cropped. App content only; no personal data.
- Device settings changed for the run and restored afterwards: `font_scale` (was 1.15), night mode (was yes), media volume (was 11, set to 3).

## Real app, every step

`<theme>-font<scale>-step<N>-top.png` is the step as it opens; `-end.png` is the same step scrolled to the end (omitted when the step does not scroll). Themes: dark and light. Font scales: 1.0 (default) and 2.0 (largest).

Extra real-app captures:
- `*-step4-heyjandal-pending.png`: Hey Jandal after **Turn on** in this run: "On at Finish" chip, "Turns on when you tap Finish.", **Turn off**. Undone afterwards. Dark and light at font 1.0, and dark at font 2.0 (the title stays on one line; the chip moves under it).
- `*-step3-inflect-chosen.png`: step 3 after choosing Inflect Micro in step 2: one Inflect Micro row and its 40 MB total (Cori is already on this phone, so no Cori row).
- `dark-font1.0-step2-sample-playing.png`: the Inflect **Hear a sample** button while its clip plays ("Stop sample"); a second tap stopped it.

## Hey Jandal states (`states/`)

The S23U cannot show the blocked states (it has a microphone, the model and no Usage Access gate), so these were rendered **on the S23U** by a throwaway instrumentation test that drew `SetupWizardContent` with each `MakeYoursPlatform` (the same states as the previews in `SetupWizardPreviews.kt`). Cropped to the top of step 4; dark and light, font 1.0 and 2.0.

| File prefix | README row |
|---|---|
| `pending-on` | Chosen in this run, not applied yet: On at Finish, Turn off |
| `pending-off` | Stored on, turned off in this run: Off, Turn on, "Turns off when you tap Finish." |
| `needs-access-mic` | Chosen, microphone not granted: Needs access, Allow microphone + Turn off, amber caveat and border |
| `needs-access-usage` | Chosen, Honor without Usage Access (screen 4b): Needs access, Open Usage Access + Turn off |
| `not-chosen-fixable` | Not chosen, fixable block: Off, Allow microphone only, reason as plain text |
| `not-available-chosen` | No microphone, choice on: Not available, Turn off, "Stays off until this is fixed." |
| `not-available` | No microphone, choice off: Not available, no action button, Why? only |

## Instrumented tests

`TEST-SM-S918B-setup-wizard-androidTest.xml` (raw output, after the owner's welcome-row decision): `SetupWizardLargeFontTest` (19, including the four last-item-above-footer tests, More below, scroll reset and `heyJandalTitleStaysOnOneLineWithNeedsAccessAtLargeFont`) and `MakeYoursResumeRefreshTest` (1), run with `ANDROID_SERIAL=RFCW11265XH ./gradlew :feature:chat:connectedDebugAndroidTest`: 20 tests, 0 failures. The `states/` images came from a separate throwaway render test in round 2 (not committed).

Step 1 (`*-step1-*.png`) was re-captured after the owner's decision: with nothing left to download the third row's title is "Chat is ready" and it has no body line.
