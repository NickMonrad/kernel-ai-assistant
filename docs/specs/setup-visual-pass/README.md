# Setup guide and Voice settings: visual pass

**Status:** approved by the owner on 2026-11-16, for Build 7.
**Issues:**
- #1936: setup guide visual pass, plus the online-speech and Inflect gaps;
- #1937: Voice settings online switch;
- #1938: Jandal colours by default;
- #1929: scroll position;
- #1931: Hey Jandal download size.

**Implementation notes:** [`compose-notes.md`](compose-notes.md).

This is the authoritative look-and-content spec for the first-run setup guide and the speech-recognition part of Settings › Voice. It changes how things look and what they say. **Behaviour stays as it is today** unless a section below says otherwise under "Behaviour change". That includes:
- when choices are saved: on Finish or Skip, never on Back;
- what is downloaded, and when: queued on Finish or Skip;
- the permission flows in [`permissions-ux.md`](../permissions-ux.md).

The mockups are static pictures drawn in the dark Jandal scheme at 390 dp wide. Where this text and a picture disagree, this text wins.

| Screen | Screenshot | Static HTML |
|---|---|---|
| 1. Welcome | [01-welcome.png](screenshots/01-welcome.png) | [01-welcome.html](mockups/01-welcome.html) |
| 2. Hear and talk (full scroll) | [02-hear-and-talk.png](screenshots/02-hear-and-talk.png) | [02-hear-and-talk.html](mockups/02-hear-and-talk.html) |
| 3. Downloads (full scroll) | [03-downloads.png](screenshots/03-downloads.png) | [03-downloads.html](mockups/03-downloads.html) |
| 4. Make Jandal yours (full scroll) | [04-make-jandal-yours.png](screenshots/04-make-jandal-yours.png) | [04-make-jandal-yours.html](mockups/04-make-jandal-yours.html) |
| 4b. Same step on an Honor, Hey Jandal chosen, as first seen | [04b-honor-needs-access.png](screenshots/04b-honor-needs-access.png) | [04b-honor-needs-access.html](mockups/04b-honor-needs-access.html) |
| 5. Settings › Voice, speech recognition | [05-settings-voice.png](screenshots/05-settings-voice.png) | [05-settings-voice.html](mockups/05-settings-voice.html) |

Sizes in the pictures are examples for a flagship phone; the app shows real sizes (see Data).

To regenerate a screenshot after changing its HTML:
- run headless Chromium with `--window-size=390,<root height> --force-device-scale-factor=2`;
- be online, because the HTML loads Roboto from Google Fonts. Offline renders fall back to a different font.

## Code

- **Setup guide:** `feature/chat/src/main/java/com/kernel/ai/feature/chat/setup/`.
  - `SetupWizardScreen.kt`: the steps, footer and the `COMPACT_HEIGHT` short-window mode.
  - `SetupWizardViewModel.kt`, which owns `SetupWizardUiState`.
  - `SetupSpeechOptions.kt`, which includes the `inputReason` line.
  - `SetupVoiceDevice.kt`.
  - `MakeJandalYours.kt`: `MakeYoursRows`, `MakeYoursStatus` (`On`, `Off`, `NotAvailable`), `HeyJandalBlock` reasons, `MakeYoursPlatformReader`.
  - Step enum and flow: `core/inference/.../setup/SetupWizardState.kt` (`SetupStep`, `SetupWizardFlow`).
- **Voice settings:** `feature/settings/src/main/java/com/kernel/ai/feature/settings/VoiceScreen.kt`, in the engine list around `onlineSpeechToggleVisible`.
- **Theme:** `core/ui/src/main/java/com/kernel/ai/core/ui/theme/` (`Color.kt`, `Theme.kt`). The theme sets no custom typography; it uses Material 3 defaults.
- **Tests to keep passing and extend:**
  - `SetupWizardLargeFontTest`: its text matchers use today's copy, so update them with the copy here.
  - `MakeYoursResumeRefreshTest`.

## Theme

Use `MaterialTheme.colorScheme` roles, or the extended colours below, never hex values in a screen. #1938 makes the Jandal scheme the default, but people can opt into wallpaper colours, and light mode must work.

**New extended colours.** Add them in `core/ui` theme, for example through a `JandalExtendedColors` `CompositionLocal`, each with light and dark values. Under wallpaper colours, derive them from the dynamic scheme or keep the Jandal values; either way, keep the contrast.

| Name | Use | Dark | Light |
|---|---|---|---|
| `accent` | Text buttons, links, section labels, selected-card border, progress segments done or current | `#9FCB8F` (8.9:1 on `#202020`) | `#3B6230` (6.2:1 on `#F5F0E8`) |
| `successContainer` / `onSuccessContainer` | On chip, Downloaded chip, choice-card tag "Recommended" | `#23361D` / `#A9D99A` | `#DCEFD3` / `#1E3A16` |
| `neutralChip` / `onNeutralChip` | Off, Not available, size tags | `#2E2E2E` / `#CFCFCF` | `#E8E4DC` / `#3A3A3A` |
| `warningContainer` / `onWarningContainer` / `warningOutline` | Needs access chip, the caveat line, the card border | `#3D2E12` / `#F2C46D` / `#5A4520` | `#FCEFD2` / `#5C3D00` / `#D9B260` |

Don't use `primary` for text: the dark `primary` is `#4F7942`, which is only 3.4:1 on the background. `primary` / `onPrimary` stays for the filled **Continue/Finish** button (white on `#4F7942` is 5.1:1).

| Mockup element | Role |
|---|---|
| Page background | `background` (dark `#1A1A1A`; light `SandLight`, so white cards keep an edge) |
| Cards | `surfaceContainerHigh` |
| Icon tiles | `surfaceContainerHighest` |
| Selected choice card fill | `primaryContainer` at about 35% alpha over `surfaceContainerHigh` |
| Selected choice card border (2 dp) | `accent` |
| Tonal action button ("Turn on", "Allow microphone") | `primaryContainer` / `onPrimaryContainer` |
| Body and secondary text | `onSurface` / `onSurfaceVariant` |
| Dividers, footer top border | `outlineVariant` |

**Type:** Material 3 defaults.
- Page title: `headlineSmall` (24 sp; the mockups draw 26–30 px, so follow the type scale, not the picture).
- Card title: `titleMedium`.
- Descriptions: `bodyMedium`.
- Caveat lines: `bodySmall`.
- Chips and section labels: `labelMedium` / `labelLarge`.

## Shared parts

1. **Step header.** "Step N of 4" (`labelLarge`, `onSurfaceVariant`) above a 4-segment bar: 4 dp tall, 6 dp gaps, rounded.
   - Done and current segments use `accent`; the rest use `surfaceVariant`.
   - It is a single node for TalkBack: "Step 2 of 4".
2. **Choice card** (radio).
   - **Card:** corner radius 16 dp, padding 14–16 dp.
     - Selected: a 2 dp `accent` border and the tinted fill.
     - Unselected: card colour only.
   - **Selectable part:** only the **header** (radio, title, tag, description) is the selectable row: `Modifier.selectable(role = Role.RadioButton)`, at least 56 dp tall. The radio itself has no separate click target.
   - **Group:** cards in one group sit in a `selectableGroup()`. Each header sets `collectionItemInfo`, and the group sets `collectionInfo`, so TalkBack says "1 of 2".
   - **Tag chips** after the title stay on one line (no wrapping); if they don't fit, move them below the title. They're used for "Recommended", a size or "Downloaded", with the colours in Theme.
   - **Sub-content** sits **inside** the card, below a divider, indented to the title's start. Examples are the online switch, Hear a sample and Download. It is not part of the selectable header, and each control is its own target of at least 48 dp. Switch rows use `Modifier.toggleable(role = Role.Switch)` on the whole row.
3. **Status chip.** The status shows in three ways at once: colour, icon and text.

   | State | Icon | Text | Colours |
   |---|---|---|---|
   | On | check | On | success |
   | Turns on at Finish | clock | On at Finish | success, outlined (border `accent`, no fill) |
   | Off | open circle | Off | neutral |
   | Not available | dash | Not available | neutral |
   | Needs access | warning triangle | Needs access | warning, plus a 1 dp `warningOutline` card border |

   **Needs access** (amber) appears only when the person has **chosen** the feature (pending or stored on) and a fixable block stops it. A feature they haven't chosen shows **Off**, with the reason as plain secondary text. This follows the "repair dashboard, not a nag screen" rule in `permissions-ux.md`.
4. **Setting card** (step 4).
   - **Header:** an icon tile (40 dp, corner radius 12 dp), a title with the status chip on the same line, one line of description, and an optional caveat line (`bodySmall`; `onWarningContainer` colour when it explains Needs access).
   - **Actions:** a row below, aligned to the title's start. A tonal button for the main action, and a text button for **Why?** or **Change**.
   - **Buttons that open a system page** say so, for example **Open settings**, **Open Usage Access** or **Allow microphone** (the in-app permission dialog), per principle 5 of `permissions-ux.md`.
   - **Why?** opens a `ModalBottomSheet` with the longer explanation that today's row shows inline.
5. **Fixed footer.** Primary button (Continue or Finish), with **Back** (left) and **Skip** (right) text buttons below it.
   - It sits on `background`, with a 1 dp `outlineVariant` top border.
   - **Fade:** when content scrolls under it, show a 48 dp fade from transparent to `background` above the footer. The fade is drawn only and doesn't intercept touches.
   - **"More below" hint:** a down chevron with "More below" while there's unseen content. A count such as "1 more below" is optional.
     - TalkBack doesn't announce it as a separate element. Content stays reachable by swiping.
     - It hides when the page can't scroll further (`ScrollState.canScrollForward`).
   - **End of the page:** the last item must be fully visible, at default and largest font.
   - **Scroll position:** each step opens scrolled to the top (#1929).
   - **Short windows** (below `COMPACT_HEIGHT`, 480 dp, for split screen or large font plus display size): keep today's behaviour. The footer scrolls with the content, with no fade or hint.
6. **Section label** (step 2, Settings › Voice). Uppercase `labelLarge` in `accent`, with a 16 dp icon: mic for Listening, speaker for Speaking. Mark it `heading()`.

## Data

**Sizes.** Use the real catalogue sizes, never hard-coded figures.
- **Step 3 rows:** come from `plannedFirstRunModels()` plus the voice chosen in step 2 (and Inflect Micro's files, if chosen).
- **Welcome total:**
  - first run: the same plan for this phone's hardware tier, using the default choices;
  - re-run from Settings: what is still to download. The state has no re-run flag today (`choiceRecorded` can be true on a first run), so add an explicit `isRerun` to `SetupWizardUiState`, set when the guide is opened from Settings.
  - Nothing left to download: drop the size from the third welcome row ("Chat is ready" instead).
- **Hey Jandal size:** the wake-word model is bundled. What downloads is the Whisper tiny.en verifier (`WakeWordService.wakeVerifierKernelModels()`, about 117 MB today), plus Zipformer when the input engine is Android native (`queueHeyJandalSttModel`).
  - `wakeVerifierKernelModels()` is `internal` to `:app`, so move the list (or its size) to a shared module that `:feature:chat` can read.
  - Show the extra amount for this phone ("Adds a {size} download."). Show nothing if it's already downloaded.

**Inflect Micro eligibility:** use the same rule as Settings, `VoiceOutputEngine.entriesForBuild(isRelease, inflectEligible)`.
- Release builds offer it only where `InflectMicroModelSpec.isReleaseEligible` is true.
- Debug builds offer it everywhere, as Settings does today.
- The copy never mentions a device requirement.

## Screens

### 1. Welcome

- **App mark:** in a 72 dp tile (`primaryContainer`). Use the real launcher icon or brand mark from `docs/brand/`; the sandal outline in the mockup is a placeholder.
- **Text:** title "Kia ora, welcome to Jandal"; subtitle "A private assistant that runs on your phone."
- **Three rows**, each with a 44 dp icon tile:
  - lock: **Private by default**: "Your chats and what you tell Jandal stay on this phone."
  - bolt: **Timers, weather and lists work now**: "No download needed for these."
  - download: **Chat needs a one-time download**: "About {total}. You choose Wi-Fi or mobile data in step 3." See Data for the re-run wording.
- **Footer:** Continue, **Back**, **Skip setup**.
  - Back stays on step 1. It exits the guide (`onExit`), which a re-run from Settings needs.

### 2. Hear and talk

- **Text:** title "How should Jandal hear and talk to you?"; subtitle "Nothing downloads until the last step."
- **Listening:**
  - **Automatic** (tag: Recommended): "Works offline on this phone."
  - **Android's own listener**: "Your phone's built-in speech recognition."
  - **When options are missing**, keep today's lines from `SetupSpeechOptions`:
    - the `inputReason` line when Android's own listener isn't offered;
    - "Your current speech input stays as it is" when the stored engine isn't one of these.
  - **Behaviour change: online speech switch.** Inside the selected Automatic or Android's own listener card, show a switch row:
    - label **"Use online speech recognition"**;
    - secondary line "Sends your voice to your phone's speech service (usually Google)." and a separate **More** text button, its own 48 dp target (the mockups draw it as an inline link; follow this text).
    - The phone's service comes from `Settings.Secure` `voice_recognition_service` and isn't always Google, so the copy says "usually".
    - **More** opens a sheet with Settings' full explanation. With the switch on, voice commands retry online when on-device recognition fails, and Hey Jandal and alarm commands use the online service straight away.
    - **Stored value:** the switch starts at the stored `VoiceInputPreferences.allowOnlineSpeechFallback`, which is off on a new install.
    - **Saving:** like every other choice in the guide, it's saved on Finish or Skip, not when toggled.
- **Speaking:** one choice card per voice this build and phone offers.
  - **Cori, natural voice** (tag: size, or "Downloaded"): "Sounds natural and works offline. Can be slower on older phones."
  - **Inflect Micro** (tag: size, or "Downloaded"): "Higher-quality voice. Works offline." Shown only when eligible (see Data). **Behaviour change:** if it's chosen, the guide queues its two files (`duration.onnx`, `decode.onnx`, about 40 MB) on Finish or Skip, as Settings does (`VoiceViewModel.downloadInflectMicro()`). Inflect also needs the Cori pack, which that call starts too, so its size tag and step 3 count Cori as well when Cori isn't already downloaded (about 40 MB + 116 MB). In debug builds `entriesForBuild` also returns Kokoro; setup doesn't offer it.
  - **Phone's built-in voice** (Android TTS): "No download. Sounds more robotic."
  - **Default selection:** follows `VoiceOutputEngine.defaultForTier`, as today.
  - **Behaviour change: Hear a sample.** Bundle one short pre-recorded clip per downloadable voice in the APK, made from that voice. The guide plays it with no download.
    - The clip says a single sentence such as "Kia ora, I'm Jandal. What can I help with?".
    - Keep each clip at or under 150 KB (Opus or AAC).
    - If a voice has no clip, hide its button.
    - The button stops playback on a second tap, and when the step changes.
- **Footer:** Continue, Back, Skip setup.

### 3. Downloads

- **Text:** title "Download Jandal's models"; subtitle "A one-time download. Timers, weather and lists work while it runs."
- **Summary card:** a **Total** row, then one row per planned item. Each row has an icon tile, a plain name, the technical name as secondary text, and the size.
  - **Plain names:**
    - "Chat and answers": the tier's Gemma model, E-4B or E-2B;
    - "Memory and search": Arctic Embed;
    - "Offline speech recognition": Zipformer, when in the plan;
    - the chosen voice;
    - Inflect Micro, when chosen.
  - **Already downloaded:** leave out items already on the phone, and say "Already downloaded" in place of the total if nothing is left.
  - **Re-run:** keep today's wording that the network choice applies to downloads already in progress.
- **"Which network should downloads use?"**
  - **Wi-Fi only** (tag: Recommended): "Waits until you're on Wi-Fi."
  - **Wi-Fi or mobile data**: "Can use your mobile data plan."
  - Nothing is queued until Finish or Skip in step 4, so no copy may say a download "starts now".
- **Footer:** Continue, Back, Skip setup.

### 4. Make Jandal yours

- **Text:** title "Make Jandal yours"; subtitle "All optional. Change any of these later in Settings."
- **Setting cards**, in this order. Status comes from `MakeYoursRows` and refreshes on resume, as today.
  - **Hey Jandal** (mic): "Say \"Hey Jandal\" to start talking, hands-free." Caveat: "Adds a {size} download. Uses a little battery." (#1931; see Data).

    | State | Chip | Action |
    |---|---|---|
    | Off, not chosen, no block | Off | **Turn on**. This records the choice; it's applied on Finish (`applyWakeWordChoice`), as today. |
    | Chosen in this run, not applied yet, no block | On at Finish | **Turn off** (text button) undoes the choice. Description adds "Turns on when you tap Finish." |
    | Stored on, no block | On | **Turn off** (text button) |
    | Stored on, turned off in this run, no block | Off | **Turn on** undoes it. Description adds "Turns off when you tap Finish." |
    | Not chosen (or turned off in this run), fixable block | Off | The fix button only (**Allow microphone** or **Open Usage Access**), no Turn on. The block reason is plain secondary text, not amber. |
    | Chosen or on, microphone not granted | Needs access | **Allow microphone**, plus **Turn off** (text button). Caveat: "Hey Jandal needs microphone access. Stays off until this is fixed." |
    | Chosen or on, Honor/MagicOS without Usage Access | Needs access | **Open Usage Access**, plus **Turn off** (text button). Caveat: "On this phone it needs Usage Access, so it can pause while the camera's in use. Stays off until this is fixed." (screen 4b) |
    | No microphone, model not installed, or the phone can't support it | Not available | **Turn off** only if the choice is on; otherwise none. Today's `HeyJandalBlock` reason is the description, plus "Stays off until this is fixed." when the choice is on. |

    Finish never applies a blocked choice (`applyWakeWordChoice` writes nothing while a block remains), so a blocked card never says "Turns on when you tap Finish". These states and buttons follow today's `SetupWizardScreen` row; the chip, layout and shortened copy are new.
    Skip throws a pending choice away, as today.
  - **Default assistant** (assistant sparkle): "Opens Jandal from your phone's assistant button or gesture. Hey Jandal works best with this on."
    - On: chip **On**, text button **Change**.
    - Off: chip **Off**, tonal button **Open settings**.
    - No assistant role on the phone: **Not available**.
  - **Notifications** (bell): "Download progress, alarms and reminders. Downloads keep going either way."
    - Off: chip **Off**, tonal button **Allow**, which shows the in-app permission dialog.
  - **Alarms and timers** (alarm clock): "So timers ring on time. Without this, Android can delay them."
    - Off: chip **Off**, tonal button **Open settings**, which opens `exactAlarmSettingsIntents`.
- Every card has **Why?** when the longer text from today's row doesn't fit in one line.
- **Footer:** **Finish**, Back, **Skip these**. Choices are applied and downloads queued on Finish or Skip, as today.

### 5. Settings › Voice, speech recognition (#1937)

- **Section label:** "Speech recognition".
- **Choice cards:** the engine choices use the same choice card as step 2.
  - **Names:** keep each engine's existing `displayName` (for example "Sherpa-ONNX Zipformer").
  - **Descriptions:** can be shortened to one line, keeping their meaning.
- **Automatic** (tag: Recommended): its existing description, shortened.
- **Android native:** its description, plus the language as a third line ("English (Australia)").
- **Downloadable engines:**
  - a **Downloaded** (success) or **Not downloaded** (neutral) tag;
  - when not downloaded, the radio is disabled and the card's sub-content holds the existing download card, or a **Download** button;
  - warnings for an engine move inside that engine's card.
- **Online switch:** moves inside the selected Automatic or Android native card, using the same row as step 2: "Use online speech recognition", "Sends your voice to your phone's speech service (usually Google).", and the **More** button.
  - Visibility stays `onlineSpeechToggleVisible`.
  - The full explanation moves to the **More** sheet.
  - Settings saves the switch straight away, as today.

## Accessibility and fit

- **Touch targets:** at least 48 dp.
- **Choice cards:** the header is the radio target (see Shared parts, 2).
- **Status:** never colour alone.
- **Contrast:** text meets 4.5:1 in light, dark and wallpaper colours. Check every extended colour.
- **Large fonts:** every screen works at the largest font and display size. Use compact mode below `COMPACT_HEIGHT`.
- **TalkBack:**
  - choice cards read as "Automatic, Recommended, selected, 1 of 2";
  - setting cards read title, status and action;
  - section labels and page titles are headings;
  - the progress header reads "Step N of 4".

## Out of scope

- New setup steps, or changes to the step order.
- Changes to permission flows beyond the button labels and states above.
- Changes to what downloads, or when, beyond queuing Inflect Micro when it's chosen.
- A colour picker in setup. Wallpaper colours stay a Settings option (#1938).
- Renaming the speech engines.

## Done when

- [ ] All four setup steps and Settings › Voice match this spec in light and dark mode.
- [ ] Setup offers the online switch (stored value, saved on Finish or Skip) and, where eligible, Inflect Micro (queued on Finish or Skip).
- [ ] Bundled sample clips play in step 2 with nothing downloaded; each clip is 150 KB or less.
- [ ] Every size and total comes from the catalogue. The Hey Jandal size comes from a shared source.
- [ ] Unit tests cover:
  - step 3 rows: driven by the plan and choices, by tier (E-4B vs E-2B), with downloaded items hidden, and Inflect only when eligible;
  - every step 4 Hey Jandal row in the table, including pending off and the blocked rows' "Stays off until this is fixed."
- [ ] Tests:
  - `SetupWizardLargeFontTest` is updated for the new copy and asserts the last item is fully visible above the footer;
  - compact mode still scrolls the footer;
  - `MakeYoursResumeRefreshTest` still passes.
- [ ] The PR has screenshots of each screen on the S23U:
  - default and largest font;
  - light and dark;
  - the Hey Jandal pending, Needs access and Not available states (from previews if no Honor is available);
  - no personal data in any screenshot.
