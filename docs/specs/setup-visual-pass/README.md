# Setup guide and Voice settings: visual pass

**Status:** approved by the owner on 2026-11-16, for Build 7.
**Issues:**
- #1936: setup guide visual pass, plus the online-speech and Inflect gaps;
- #1937: Voice settings online switch;
- #1938: Jandal colours by default;
- #1929: scroll position;
- #1931: Hey Jandal download size.

This is the authoritative look-and-content spec for the first-run setup guide and the speech-recognition part of Settings › Voice. Behaviour that isn't mentioned here stays as it is today, including the permission flows in [`permissions-ux.md`](../permissions-ux.md).

The mockups are static pictures, not code. Build them with Compose and Material 3 components and the app's theme roles. Where this spec and the pictures disagree, this spec wins.

| Screen | Screenshot | Static HTML |
|---|---|---|
| 1. Welcome | [01-welcome.png](screenshots/01-welcome.png) | [01-welcome.html](mockups/01-welcome.html) |
| 2. Hear and talk (full scroll) | [02-hear-and-talk.png](screenshots/02-hear-and-talk.png) | [02-hear-and-talk.html](mockups/02-hear-and-talk.html) |
| 3. Downloads | [03-downloads.png](screenshots/03-downloads.png) | [03-downloads.html](mockups/03-downloads.html) |
| 4. Make Jandal yours (full scroll) | [04-make-jandal-yours.png](screenshots/04-make-jandal-yours.png) | [04-make-jandal-yours.html](mockups/04-make-jandal-yours.html) |
| 4b. Same step on an Honor, as first seen | [04b-honor-needs-access.png](screenshots/04b-honor-needs-access.png) | [04b-honor-needs-access.html](mockups/04b-honor-needs-access.html) |
| 5. Settings › Voice, speech recognition | [05-settings-voice.png](screenshots/05-settings-voice.png) | [05-settings-voice.html](mockups/05-settings-voice.html) |

Screenshots are 2x, at a 390 dp-wide phone, in the dark Jandal scheme. Regenerate them from the HTML with headless Chromium (`--window-size=390,<height> --force-device-scale-factor=2`) if the HTML changes.

## Code

- Setup guide:
  - `feature/chat/src/main/java/com/kernel/ai/feature/chat/setup/SetupWizardScreen.kt`;
  - `SetupWizardViewModel.kt`, `SetupSpeechOptions.kt` and `SetupVoiceDevice.kt` in the same package;
  - state in `core/inference/.../setup/SetupWizardState.kt`.
- Voice settings: `feature/settings/src/main/java/com/kernel/ai/feature/settings/VoiceScreen.kt`, in the engine list around `onlineSpeechToggleVisible`.
- Theme: `core/ui/src/main/java/com/kernel/ai/core/ui/theme/` (`Color.kt`, `Theme.kt`).
- Large-font regression test: `feature/chat/src/androidTest/.../setup/SetupWizardLargeFontTest.kt`. Keep it passing and extend it.

## Theme

Use `MaterialTheme.colorScheme` roles, never the hex values below. #1938 makes the Jandal scheme the default, but people can opt into wallpaper colours, and light mode must work too. The hex values are the dark Jandal scheme the mockups were drawn in, listed only so you can check you picked the right role.

| Use in mockups | Role | Dark Jandal value |
|---|---|---|
| Page background | `surface` / `background` | `#1A1A1A`; mockups use `#141414` (`surfaceContainerLow`) |
| Card | `surfaceContainerHigh` | `#202020` |
| Icon tile, neutral chip | `surfaceContainerHighest` / `surfaceVariant` | `#262626`–`#2A2A2A` |
| Selected card fill | `primaryContainer` at low alpha over the card, or `secondaryContainer` | `#1F2A1B` |
| Selected card border (2 dp) | `primary` (light tone in dark mode) | `#7AAD6A` |
| Primary button | `primary` / `onPrimary` | `#4F7942` / white |
| Tonal button ("Turn on", "Allow") | `primaryContainer` / `onPrimaryContainer` | `#2D5023` / `#E7F5DE` |
| Text buttons, section labels, links | `primary` (light tone) | `#9FCB8F` |
| Body text, secondary text | `onSurface`, `onSurfaceVariant` | `#E8E8E8`, `#B0B0B0` |
| Dividers, footer top border | `outlineVariant` | `#383838` |

Type uses the app's `MaterialTheme.typography`:
- page title: `headlineSmall` (26–30 sp);
- card title: `titleMedium`;
- descriptions: `bodyMedium`;
- chip and section label: `labelMedium` / `labelLarge`.

No new fonts.

## Shared parts

Build each of these once and use it on every step.

1. **Step header.** "Step N of 4" (`labelLarge`, `onSurfaceVariant`) above a 4-segment progress bar: 4 dp tall, 6 dp gaps, rounded. Done and current segments use `primary`; the rest use `surfaceVariant`. Expose it to TalkBack as "Step 2 of 4".
2. **Choice card** (radio). The whole card is one selectable row (`Modifier.selectable` with `Role.RadioButton`, in a `selectableGroup`). It holds a radio, a title and a one-line description.
   - Selected: 2 dp `primary` border and a tinted fill.
   - Unselected: plain card colour, no visible border.
   - Corner radius 16 dp; padding 14–16 dp; minimum touch height 56 dp.
   - Optional parts: a **tag chip** after the title (Recommended, a size such as `116 MB`), and an **action** under the description (an outlined "Hear a sample" button).
   - **Sub-settings** of a choice (the online-speech switch) sit **inside the selected card**, below a divider and indented to the title's start. They appear only while that choice is selected.
3. **Status chip.** Every status says what it is in three ways: colour, icon and text. Never colour alone.

   | State | Icon | Text | Colours (dark) |
   |---|---|---|---|
   | On | check | On | green container `#23361D`, content `#A9D99A` |
   | Off | open circle | Off | neutral `#2E2E2E`, content `#CFCFCF` |
   | Needs access | warning triangle | Needs access | amber container `#3D2E12`, content `#F2C46D`, plus a 1 dp amber card border |

   There's no M3 role for amber. Add one pair of colours to the theme (for example `warningContainer` and `onWarningContainer` in an extension, for light and dark) rather than hard-coding them in the screen. Check 4.5:1 contrast for chip text in both themes.
4. **Setting card** (step 4). An icon tile (40 dp, rounded 12 dp), a title with the status chip on the right of the same line, one line of description, and an optional smaller second line for a cost or caveat. Actions go on a row below, aligned to the title's start: a tonal button for the main action ("Turn on", "Allow", "Open Usage Access"), and a text button for "Why?" or "Change". "Why?" opens a bottom sheet with the longer explanation that today's screen shows inline.
5. **Fixed footer.** A full-width primary button with **Back** (left) and **Skip** (right) text buttons beneath it.
   - It sits on the page background with a 1 dp `outlineVariant` top border.
   - When content scrolls under it, show a 48 dp fade from transparent to the page background above the footer, and a small "More below" hint with a down chevron while there's unseen content.
   - The last item must be fully visible when scrolled to the end, at default and largest font. This fixes the clipped Cori text and the half-hidden Default assistant button in 0.1.7.
   - Each step opens scrolled to the top (#1929).
6. **Section label** (step 2, Settings › Voice). An uppercase `labelLarge` in `primary` with a 16 dp icon: mic for Listening, speaker for Speaking.

## Screens

### 1. Welcome

- An app mark in a 72 dp rounded tile (`primaryContainer`). Use the real launcher icon or brand mark from `docs/brand/`; the sandal outline in the mockup is a placeholder.
- Title "Kia ora, welcome to Jandal". Subtitle "A private assistant that runs on your phone."
- Three icon rows, each with a 44 dp tile, a title and one line:
  - lock: **Private by default**, "Your chats and what you tell Jandal stay on this phone."
  - bolt: **Timers, weather and lists work now**, "No download needed for these."
  - download: **Chat needs a one-time download**, "About {total}. You choose Wi-Fi or mobile data in step 3." Use the real total from the model catalogue, not a hard-coded "3.8 GB".
- Footer: Continue, Back (hidden or disabled on step 1 if there's nothing to go back to), and **Skip setup**.

### 2. Hear and talk

- Title "How should Jandal hear and talk to you?" Subtitle "Nothing downloads until the last step."
- **Listening** section:
  - **Automatic** (tag: Recommended). "Works offline on this phone."
  - **Android's own listener.** "Your phone's built-in speech recognition."
  - When either is selected, its card contains the sub-setting **"Use Google speech for better accuracy"**: a switch, **default off**, with the line "Your voice may leave the phone. More". "More" opens a sheet with today's full explanation from Settings › Voice. It writes the same preference as Settings (`VoiceInputPreferences.allowOnlineSpeechFallback`), so changing it in either place shows in the other.
- **Speaking** section, one choice card per voice the device can use:
  - **Cori, natural voice** (tag: real size, e.g. `116 MB`). "Sounds natural and works offline. Can be slower on older phones." Action: **Hear a sample**.
  - **Inflect Micro** (tag: real size). "Our most expressive voice. Works offline." Action: **Hear a sample**. Show it **only** when `InflectMicroModelSpec.isReleaseEligible(...)` is true, as Settings does. Don't mention the device requirement anywhere in the copy; on other devices the option just isn't there.
  - **Phone's built-in voice.** "No download. Sounds more robotic." This is Android TTS.
  - The default selection follows `VoiceOutputEngine.defaultForTier`, as today.
- Footer: Continue, Back, Skip setup.

### 3. Downloads

- Title "Download Jandal's models". Subtitle "A one-time download. Timers, weather and lists work while it runs."
- One summary card:
  - a **Total** row with the total size in large text;
  - a row per item to download, each with an icon tile, a plain-language name, the technical name as secondary text, and the size on the right;
  - the rows, from the selections in step 2 and the hardware tier:
    - "Chat and answers · Gemma 4 E-4B · 3.7 GB";
    - "Cori natural voice · Chosen in step 2 · 116 MB", or Inflect Micro when that was chosen;
    - Hey Jandal's model only if it is already set to download.
  - Hide rows that are already downloaded, and say "Already downloaded" in the total if nothing is left.
- "Which network should downloads use?" as two choice cards with icons:
  - **Wi-Fi only** (tag: Recommended), "Waits until you're on Wi-Fi.";
  - **Wi-Fi or mobile data**, "Starts now. May use your data plan."
- Footer: Continue, Back, Skip setup.

### 4. Make Jandal yours

- Title "Make Jandal yours". Subtitle "All optional. Change any of these later in Settings."
- Setting cards, in this order. Each status comes from the live state and updates on resume, as today.
  - **Hey Jandal** (mic): "Say \"Hey Jandal\" to start talking, hands-free." Second line: "Adds a {size} download. Uses a little battery." (#1931; use the real wake-word model size.)
    - Off: chip **Off**, button **Turn on**.
    - On: chip **On**, text button **Turn off**.
    - **Honor/MagicOS without Usage Access**: chip **Needs access**, amber card border, the amber line "On this phone it needs Usage Access, so it can pause while the camera's in use.", and the button **Open Usage Access**. See screen 4b.
  - **Default assistant** (sparkle): "Opens Jandal from your phone's assistant button or gesture. Hey Jandal works best with this on." On: chip **On**, text button **Change**. Off: chip **Off**, tonal button **Set as default**.
  - **Notifications** (bell): "Download progress, alarms and reminders." Off: tonal button **Allow**.
  - **Alarms and timers** (clock): "So timers ring on time. Without this, Android can delay them." Off: tonal button **Allow**.
- Every card has **Why?** for the longer text, unless the card is already fully explained.
- Footer: **Finish**, Back, and **Skip these**. Downloads start on Finish or Skip, as today.

### 5. Settings › Voice, speech recognition (#1937)

- The section label "Speech recognition".
- Engine choices use the same choice card as step 2.
- **Automatic** (tag: Recommended), "Picks the best recognizer on this phone. Works offline."
- **Android native**, "Your phone's own recognizer. Can suit some accents better.", with the language as a third line ("English (Australia)").
- **Downloadable engines** (Zipformer, SenseVoice, Whisper, Paraformer, …):
  - a status chip: **Downloaded** (On style) or **Not downloaded** (Off style);
  - when not downloaded, the radio is disabled and the card shows an outlined **Download** button.
  - The per-engine download card and warnings move **inside** that engine's card, as sub-content.
- The online switch moves inside the selected Automatic or Android native card, as in step 2. It uses the same label, "Use Google speech for better accuracy", and the same "Your voice may leave the phone. More" line. Visibility stays `onlineSpeechToggleVisible`; only the placement and copy change.

## Accessibility and fit

- Touch targets are at least 48 dp; whole cards are tappable for choices.
- Status is never colour alone: the chip always has an icon and text.
- Every screen works at the largest font and display size with no clipped or overlapping text. The footer never hides content (see Shared parts, 5).
- Light mode, dark mode, and wallpaper colours (when the person has opted in under #1938) all keep 4.5:1 contrast for text.
- TalkBack:
  - announces each choice card as "Automatic, Recommended, selected, 1 of 2";
  - announces each setting card's title, status and action;
  - gives the progress bar its "Step N of 4" text.

## Out of scope

- No new setup steps or permissions, and no change to the order of steps.
- No change to what downloads, or when; only how it's shown.
- No colour picker in setup. Wallpaper colours stay a Settings option (#1938).

## Done when

- [ ] All four setup steps and Settings › Voice match this spec.
- [ ] Setup offers the online-speech switch and, on eligible devices only, Inflect Micro, and both write the same preferences as Settings.
- [ ] Sizes and totals come from real data.
- [ ] `SetupWizardLargeFontTest` passes and covers the fade or footer case: the last item is fully visible at the largest font.
- [ ] Unit tests cover the step 3 download list:
  - the selection drives the items;
  - already-downloaded items are hidden;
  - Inflect appears only when eligible.
- [ ] Screenshots of each screen on the S23U are attached to the PR:
  - default and largest font;
  - light and dark;
  - Honor-style "Needs access" state (via a preview or test if no Honor is available).
  - No personal data in any screenshot.
