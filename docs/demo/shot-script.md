# Jandal showcase video — shot script (#1736)

**Status: DRAFT for Nick's review.** Nothing is recorded until Nick approves this script.

This script is the source of truth for the shot list. `docs/demo/shots.json` is its machine-readable copy, used by the capture runner (`scripts/demo_capture.py`) and the edit pipeline (`scripts/demo_edit.py`); keep the two in sync. Each shot has a stable id, so a clip, its caption and its manifest entry can be re-shot for every release without hand editing.

## Rules for every shot

1. **Real behaviour only.** Every frame comes from the release-candidate build running on a test device. No mock screens, composited replies, or edited reply text.
2. **Takes.** A model-generated reply may be re-taken up to 3 times; the manifest records the take count. Deterministic shots (timer, alarm, list, conversion) take 1 run unless the harness itself fails.
3. **Time compression is visible.** Waiting for the model may be cut or sped up, but a visible "⏩ sped up" marker sits on screen for the whole compressed span. App-visible time to first token is long today (#1618), so replies must never look instant.
4. **No real personal data.** Seeded demo data only. Names in shots are invented (Aroha, Sam). No real contacts, accounts, messages, location or photos. Location-revealing hand-offs (Maps, "near me") are excluded from the intro cut.
5. **Offline is literal.** Shots marked *Offline* run with airplane mode on and Wi-Fi off, and the airplane icon is visible in the status bar. Shots that need the network (weather, Nextcloud) are never shown with an offline caption.
6. **Shipped v0.1 features only.** No roadmap features, no debug screens, no developer-only labels where a user-facing alternative exists.

## Device and app setup

| Item | Value |
|---|---|
| Primary device | S23U `RFCW11265XH`, Gemma 4 E4B, FLAGSHIP, GPU |
| Second device | S21 `R5CR605B71K`, only as the acoustic source for S01 and as the second Nextcloud client in M05 |
| Build | Final v0.1 RC build. Record commit, versionName/versionCode and APK SHA-256 in the manifest |
| Persona | Settings → Persona: **Full Jandal** for S05; default **Half a Jandal** for everything else |
| Theme | Dark (matches the S23U screenshot set in `docs/play-store/screenshots/`) |
| Wake word | "Listen for Hey Jandal" on; Vosk STT; approved acoustic setup from #1403/#1410 (built-in speaker, side by side 25 cm–0.6 m, volume 9/15) |
| Notifications | Do Not Disturb on. Clear the shade before each shot |
| Status bar | One UI ignores SystemUI demo mode (see `docs/play-store/screenshots/README.md`). Keep the real status bar: it carries the airplane icon that proves the offline claim. Set the device clock to automatic and avoid shots where the time reads oddly |
| Seed data | A "shopping list" with milk and bread (the app files "grocery list" under "shopping list", see `NativeIntentHandler`). Seed it by typing "Add milk and bread to my shopping list" before recording. No other lists, notes or memories at the start. Calendar seeded with one invented event ("Netball practice", Thursday 5pm) |

## Intro video (target 45 s, hard cap 60 s)

Only the first ~30 s autoplay on Play, so the hook and the offline claim land before 0:30.

| Shot | Time | Feature | Input (exact) | Expected on screen | Mode | Network |
|---|---|---|---|---|---|---|
| S01 | 0:00–0:05 | Hey Jandal | Spoken by the S21 acoustic source: "Hey Jandal" then "what time is it" (existing `natural_wake.wav` + `qwen_command.wav` fixtures) | Listening indicator, then the Actions card `what time is it` → "It's …" | Deterministic | Offline |
| S02 | 0:05–0:12 | Quick actions | Typed: "Set a timer for 10 minutes", then "Set an alarm for 7am tomorrow" | Both confirmations; timer visibly counting down. Check the alarm wording first: an earlier build repeated the time ("…at 6:30am — 6:30am."); if it still does, file it and pick another shot | Deterministic | Offline |
| S03 | 0:12–0:18 | Lists, Kiwi phrasing | Typed: "Chuck bananas on the grocery list", then open the shopping list | Confirmation, then the list shows milk, bread, bananas | Deterministic | Offline |
| S04 | 0:18–0:30 | Memory and recall | Typed: "Remember that my favourite coffee is a flat white". New chat, typed: "What's my favourite coffee?" | Save confirmation; in the new chat the reply names a flat white | Model (compressed) | Offline |
| S05 | 0:30–0:40 | Persona chat | Typed, with **Full Jandal**: "Give me three ideas for a rainy Sunday" | A complete streamed reply with Kiwi tone | Model (compressed) | Offline |
| S06 | 0:40–0:45 | End card | — | App icon, "Jandal AI", "A Kiwi AI assistant that runs on your phone", "Free on Google Play · Open source" | Title card | — |

Captions (burned in, one line each, shown for the shot's length):

- S01: "Hey Jandal — hands-free, on your phone"
- S02: "Timers and alarms, instantly"
- S03: "Speaks Kiwi"
- S04: "Remembers what matters — stored on your device"
- S05: "Chat with a local AI. No cloud AI." (plus the ⏩ marker over compressed waits)
- An "✈ Airplane mode" corner badge from S01 to S05.

Voiceover (optional; the cut must work muted, because Play preview autoplays without sound):

> "This is Jandal. Say 'Hey Jandal', and it answers — right on your phone. Timers, alarms, lists… even in Kiwi. It remembers what matters, and it chats with a local AI that keeps working in airplane mode. Jandal. Your Kiwi assistant, on your phone."

## Feature catalogue (mini videos, follow-up issue)

These reuse the same runner. They are listed so the pipeline is built for them now; they are not in the intro cut.

| Shot | Feature | Input (exact) | Expected on screen | Network / caveat |
|---|---|---|---|---|
| M01 | Push-to-talk | Hold the mic, play a command fixture: "Set a timer for 5 minutes" | Transcript, then the timer confirmation | Offline. Needs an approved PTT fixture; the J8 PTT check is still open on #1329 |
| M02 | Flashlight | Typed: "Turn on the flashlight", then "Turn off the flashlight" | Both confirmations | Offline. The torch itself is not visible in a screen recording; caption it, or add a second-camera insert later |
| M03 | Calendar | Typed: "Add dentist appointment next Tuesday" | Jandal hands off to the device calendar's new-event screen, prefilled for next Tuesday (`ACTION_INSERT`); the runner taps Save | Offline. Shows the OEM calendar app; check its UI carries no real account name |
| M04 | Weather | Typed: "What's the weather like in Wellington?" | Weather card for Wellington | **Online** (Open-Meteo). Caption must not say offline |
| M05 | Nextcloud sync | On S23U, typed: "Add bread, eggs, and cheese to my grocery list". Then show the same list on the S21 (or the Nextcloud web view) | The items appear on the second client | **Online.** Test Nextcloud account only; crop or blur the server URL and username. Check which device is already logged in before setting up the other |
| M06 | Notes | Typed: "Write down that Sam needs library books tomorrow", then "Show my notes" | Note saved, then listed | Offline |
| M07 | Meal planning | Typed: "Plan vegetarian dinners for next week" | Meal plan generated | Offline, model (compressed) |
| M08 | Conversions | Typed: "Convert 1 cup of flour to grams" | Instant answer | Offline |
| M09 | Offline proof | Swipe down the shade to show airplane mode on, then run S04's recall prompt | Reply arrives with airplane mode on | Offline, model (compressed) |
| M10 | Persona levels | Same prompt under Full Jandal, Half a Jandal and Boring AI Mode | Three replies with visibly different tone | Offline, model (compressed) |

Excluded on purpose: navigation and "near me" searches (they reveal device location), calls, texts and email (they need contacts), and media hand-offs to third-party apps (their branding and content are not ours to show).

## Audio and licences

| Item | Proposal | Status |
|---|---|---|
| Voiceover voice | Jandal's shipped release-visible voice, Piper `en_GB-cori-high` (repository metadata MIT; LibriVox public-domain dataset, per `docs/VOICE_MODEL_ATTRIBUTION.md`) rendered by the app's own TTS path | **Needs Nick.** Exact package provenance is still open under #1474. Inflect Micro has no recorded licence for promotional use yet, so it is not proposed |
| Music | None in v1 of the video | — |
| Device audio | Not recorded (`scrcpy --no-audio`). scrcpy can only capture device playback, not the room, so the S01 acoustic source would be missing anyway; the caption carries "Hey Jandal" | Known limitation |

## Output formats

- Master render: 1080 × 1920 portrait, H.264, 30 fps, AAC audio, from the scrcpy clips without upscaling.
- Before the final render, check Play's current preview-video guidance for orientation and length; add a 1920 × 1080 landscape variant (portrait footage centred on a branded background) only if Play's guidance favours landscape.
- Video files are never committed. Raw clips and renders live outside the repo; the manifest (shot id, take, RC commit, APK SHA-256, start/end timestamps, compression spans) is the checked-in record.

## Running the pipeline

Prerequisites on the capture host: `adb`, `scrcpy` 2.2 or newer, `ffmpeg` with `drawtext` (libfreetype), and DejaVu Sans Bold. Offline shots need USB adb, because airplane mode drops wireless adb. Claim the device lane first.

1. Install the RC build, apply the device setup above, turn on airplane mode, then check the plan:
   `python3 scripts/demo_capture.py --dry-run --shots intro`
2. Record (one MP4 per shot plus `manifest.json`; `--out` must be outside the repo):
   `python3 scripts/demo_capture.py --serial RFCW11265XH --package <rc package> --commit <rc sha> --out ~/jandal-demo/<rc> --shots intro`
   S01 also needs `DEMO_WAKE_CMD`, the host command that plays the wake and command fixtures from the S21 (the #1329 J10 path). Re-take a model shot with `--shots S04 --take 2` (at most 3).
3. Render:
   `python3 scripts/demo_edit.py --clips ~/jandal-demo/<rc> --out ~/jandal-demo/<rc>/intro.mp4 [--pick S04=2] [--voiceover vo.wav] [--icon icon.png]`
   This also writes `intro.edl.json`: every cut, every speed-up and its factor, and the build provenance. Check it with the video.

The runner drives the UI by visible labels (`Actions`, `Chats`, `Tools`, `New conversation`, `Send`, `Cancel response`) through `uiautomator`, so it works on release builds, where the debug harness intents are disabled. If a label changes, fix `shots.json`, not the code.

## Decisions for Nick

1. Approve or change the intro shot list and order (S01–S06).
2. Approve the caption and voiceover wording, especially "No cloud AI" and "runs on your phone" against the #1262 listing copy.
3. Voiceover voice: Cori (pending #1474 provenance), a separately licensed TTS voice, or captions only.
4. Take rule: up to 3 takes for model replies, recorded in the manifest. Acceptable?
