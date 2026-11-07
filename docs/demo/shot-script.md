# Jandal showcase video — shot script (#1736)

**Status: intro order, captions and end card approved by Nick (4 Nov 2026).** Record from the final v0.1 RC build.

This script is the source of truth for the shot list. `docs/demo/shots.json` is its machine-readable copy, used by the capture runner (`scripts/demo_capture.py`) and the edit pipeline (`scripts/demo_edit.py`); keep the two in sync. Each shot has a stable id, so a clip, its caption and its manifest entry can be re-shot for every release without hand editing.

## Rules for every shot

1. **Real behaviour only.** Every frame comes from the release-candidate build running on a test device. No mock screens, composited replies, or edited reply text.
2. **Takes.** A model-generated reply may be re-taken up to 3 times; the manifest records the take count. Deterministic shots (timer, alarm, list, conversion) take 1 run unless the harness itself fails.
3. **Time compression is visible.** Waiting for the model (or a sync), and the typing in S03, may be cut or sped up, but a visible "▶▶ sped up N×" marker sits on screen for the whole compressed span. App-visible time to first token is long today (#1618), so replies must never look instant.
4. **No real personal data.** Seeded demo data only. Names in shots are invented (Aroha, Sam). No real contacts, accounts, messages, location or photos. Location-revealing hand-offs (Maps, "near me") are excluded from the intro cut.
5. **Offline is literal.** Shots marked *Offline* run with airplane mode on and Wi-Fi off, and the airplane icon is visible in the status bar. Shots that need the network (weather, Nextcloud) are never shown with an offline caption or under the airplane badge.
6. **Shipped v0.1 features only.** No roadmap features, no debug screens, no developer-only labels where a user-facing alternative exists.

## Device and app setup

| Item | Value |
|---|---|
| Primary device | S23U `RFCW11265XH`, Gemma 4 E4B, FLAGSHIP, GPU |
| Second device | S21 `R5CR605B71K`, only as the acoustic source for S01 (`scripts/demo_wake.py`) |
| Build | Final v0.1 RC build. Record commit, versionName/versionCode and APK SHA-256 in the manifest |
| Persona | Settings → Persona: **Full Jandal** for S05; default **Half a Jandal** for everything else |
| Theme | Dark (matches the S23U screenshot set in `docs/play-store/screenshots/`) |
| Wake word | "Listen for Hey Jandal" on; Vosk STT; approved acoustic setup from #1403/#1410 (built-in speaker, side by side 25 cm–0.6 m, volume 9/15) |
| Notifications | The runner turns Do Not Disturb (alarms only) on for the run and restores the previous setting. Close the shade before recording |
| Actions history | Clear it (trash icon on the Actions tab) before recording, so earlier test cards don't appear behind the new ones and the result checks start from an empty list |
| Nextcloud | A dedicated test user on Lokhor's own Nextcloud, signed in on the S23U only: Jandal via its browser login (Login Flow v2 ends its browser session after granting access), and separately Chrome on the S23U, signed in to the Nextcloud web UI with "Remember me" for S07B; the seeded "shopping list" synced. The server needs the Tasks app (Jandal lists sync as CalDAV task lists). Real accounts never appear on screen |
| Status bar | One UI ignores SystemUI demo mode (see `docs/play-store/screenshots/README.md`). Keep the real status bar: it carries the airplane icon that proves the offline claim. Set the device clock to automatic and avoid shots where the time reads oddly |
| Seed data | A "shopping list" with milk and bread (the app files "grocery list" under "shopping list", see `NativeIntentHandler`). Seed it by typing "Add milk to my shopping list" and then "Add bread to my shopping list" before recording (one request with "and" makes a single item "milk and bread"; multi-item adds split on commas only). Remove leftover test items first. Also seed one important date with "Save my wife's birthday as 14 March" (an invented date; S04 asks "When is my wife's birthday?"). No other lists, notes or memories at the start. Calendar seeded with one invented event ("Netball practice", Thursday 5pm) |

## Intro video (about 72 s, hard cap 75 s)

Only the first ~30 s autoplay on Play. The offline block comes first so the hook and the offline claim land inside it; the online shots follow. Framing: the assistant and its AI run entirely on the phone, and Jandal can also reach online services when you ask, with no account or login (weather, Wikipedia, currency rates). Lists are a headline feature, so they come second and come back in S07 with optional, self-hosted Nextcloud sync.

Each shot is a separate clip, so the runner turns airplane mode off between S05 and S06 over USB adb.

| Shot | Time | Feature | Input (exact) | Expected on screen | Mode | Network |
|---|---|---|---|---|---|---|
| S01 | 0:00–0:05 | Hey Jandal | Spoken by the S21 acoustic source: "Hey Jandal" then "what time is it" (existing `natural_wake.wav` + `qwen_command.wav` fixtures) | Listening indicator, then the Actions card `what time is it` → "It's …" | Deterministic | Offline |
| S02 | 0:05–0:14 | Lists, Kiwi phrasing | Typed: "Chuck bananas on the grocery list", then open the shopping list | Confirmation, then the list shows milk, bread, bananas | Deterministic | Offline |
| S03 | 0:14–0:18 | Timer and alarm | Typed: "Set a timer for 10 minutes", then "Set an alarm for 7am tomorrow" | Both confirmations; timer visibly counting down. The typing of each command is sped up to under a second, with the sped-up marker, so the shot fits its slot. Check the alarm wording first: an earlier build repeated the time ("…at 6:30am — 6:30am."); if it still does, file it and pick another shot | Deterministic | Offline |
| S04 | 0:18–0:28 | Memory and recall | Typed: "Remember that my favourite coffee is a flat white". New chat, typed: "What's my favourite coffee?", then "When is my wife's birthday?" | Save confirmation; the reply names a flat white; a countdown card to the seeded birthday ("127 days — Until …") | Model (compressed) | Offline |
| S05 | 0:28–0:36 | Persona chat | Typed, with **Full Jandal**: "Give me three ideas for a rainy Sunday" | A complete streamed reply with Kiwi tone | Model (compressed) | Offline |
| S06 | 0:36–0:41 | Weather | Typed: "What's the weather like in Wellington?" | Weather card for Wellington (Open-Meteo) | Deterministic | **Online** |
| S07 | 0:41–0:49 | Nextcloud sync | S07A on the S23U, typed: "Add cheese to my shopping list". S07B on the S23U: Chrome opens the shopping list directly on the user's Nextcloud Tasks page (`DEMO_NC_TASKS_PATH`, so the sidebar with other accounts' shared lists never shows) | The new items are on the user's own server. Server address, username and display name blurred | Deterministic (sync wait compressed) | **Online** |
| S08 | 0:49–0:53 | End card (4 s) | — | App icon, "Jandal AI", "A Kiwi AI assistant that runs on your phone", then the variant line (below) | Title card | — |

End card variants, chosen at render time (`--end-card`):

- `beta` (pre-launch, for beta recruitment): "Join the beta · Open source" and the repository address `github.com/NickMonrad/kernel-ai-assistant`. Swap the address for the beta sign-up link in `shots.json` once there is one.
- `launch` (after the Play listing is public): "Free on Google Play · Open source".

Captions (burned in, one line or two, shown for the shot's length):

- S01: `Optional "Hey Jandal" wake word` (Hey Jandal is opt-in; #1262 says not to overclaim wake-word or hands-free behaviour)
- S02: "Lists that understand Kiwi"
- S03: "Timers and alarms, instantly"
- S04: "Remembers what matters — stored on your device"
- S05: "Chat with your local AI. No cloud required." (plus the "▶▶ sped up N×" marker over compressed waits)
- S06: "Online when you ask. No account needed." (shortened from "Weather, Wikipedia, currency: online when you ask, no account needed" so it fits at full caption size)
- S07: "Optional sync with your own Nextcloud" (on both S07A and S07B)
- An "✈ Airplane mode" corner badge on S01–S05 only. The weather and Nextcloud captions never sit under it.

Voiceover: **none in the first cut** (captions only; the cut must work muted anyway, because Play preview autoplays without sound). Add the Cori voiceover once #1474 clears its provenance. Draft wording for then, to be re-timed to the order above:

> "This is Jandal. Turn on 'Hey Jandal', and it answers — right on your phone. Lists, even in Kiwi. Timers and alarms. It remembers what matters, and it chats with a local AI that keeps working in airplane mode. Need the weather? Jandal goes online when you ask, no account needed, and your lists can sync with your own Nextcloud. Jandal. Your Kiwi assistant, on your phone."

## Feature catalogue (mini videos, follow-up issue)

These reuse the same runner. They are listed so the pipeline is built for them now; they are not in the intro cut.

| Shot | Feature | Input (exact) | Expected on screen | Network / caveat |
|---|---|---|---|---|
| M01 | Push-to-talk | Hold the mic, play a command fixture: "Set a timer for 5 minutes" | Transcript, then the timer confirmation | Offline. Needs an approved PTT fixture; the J8 PTT check is still open on #1329 |
| M02 | Flashlight | Typed: "Turn on the flashlight", then "Turn off the flashlight" | Both confirmations | Offline. The torch itself is not visible in a screen recording; caption it, or add a second-camera insert later |
| M03 | Calendar | Typed: "Add dentist appointment next Tuesday" | Jandal hands off to the device calendar's new-event screen, prefilled for next Tuesday (`ACTION_INSERT`); the runner taps Save | Offline. Shows the OEM calendar app; check its UI carries no real account name |
| M04 | Weather | Now intro shot S06 | — | **Online** (Open-Meteo). Caption must not say offline |
| M05 | Nextcloud sync | Now intro shot S07 (S07A + S07B) | — | **Online.** Test Nextcloud user only; server address, username and display name blurred |
| M06 | Notes | Typed: "Write down that Sam needs library books tomorrow", then "Show my notes" | Note saved, then listed | Offline |
| M07 | Meal planning | Typed: "Plan vegetarian dinners for next week" | Meal plan generated | Offline, model (compressed) |
| M08 | Conversions | Typed: "Convert 1 cup of flour to grams" | Instant answer | Offline |
| M09 | Offline proof | Swipe down the shade to show airplane mode on, then run S04's recall prompt | Reply arrives with airplane mode on | Offline, model (compressed) |
| M10 | Persona levels | Same prompt under Full Jandal, Half a Jandal and Boring AI Mode | Three replies with visibly different tone | Offline, model (compressed) |

Excluded on purpose: navigation and "near me" searches (they reveal device location), calls, texts and email (they need contacts), and media hand-offs to third-party apps (their branding and content are not ours to show).

## Audio and licences

| Item | Proposal | Status |
|---|---|---|
| Voiceover voice | Jandal's shipped release-visible voice, Piper `en_GB-cori-high` (repository metadata MIT; LibriVox public-domain dataset, per `docs/VOICE_MODEL_ATTRIBUTION.md`) rendered by the app's own TTS path | **Decided: captions only for the first cut.** Add Cori once its package provenance clears under #1474. Inflect Micro has no recorded licence for promotional use yet, so it is not proposed |
| Music | None in v1 of the video | — |
| Device audio | Not recorded (`scrcpy --no-audio`). scrcpy can only capture device playback, not the room, so the S01 acoustic source would be missing anyway; the caption carries "Hey Jandal" | Known limitation |

## Output formats

- Master render: 1080 × 1920 portrait, H.264, 30 fps, AAC audio, from the scrcpy clips without upscaling.
- Before the final render, check Play's current preview-video guidance for orientation and length; add a 1920 × 1080 landscape variant (portrait footage centred on a branded background) only if Play's guidance favours landscape.
- Video files are never committed. Raw clips and renders live outside the repo; the manifest (shot id, take, RC commit, APK SHA-256, start/end timestamps, compression spans) is the checked-in record.

## Running the pipeline

Prerequisites on the capture host: `adb`, `scrcpy` 3.0 or newer (tested with 5.0.1), `ffmpeg` and `ffprobe` with `drawtext` (libfreetype), and DejaVu Sans Bold. Both devices awake and unlocked, Do Not Disturb on. The S23U needs USB adb: the runner turns airplane mode (and Wi-Fi) on before each offline shot and off before each online shot, and airplane mode drops wireless adb. Claim the device lane first.

1. Install the RC build on both devices, apply the device setup above, then check the plan:
   `python3 scripts/demo_capture.py --dry-run --shots intro`
2. Put the private values in `~/.config/jandal-demo/demo.env` (outside the repo; the runner loads it, and anything already set in the environment wins):

   ```sh
   mkdir -p ~/.config/jandal-demo && chmod 700 ~/.config/jandal-demo
   cat > ~/.config/jandal-demo/demo.env <<'EOF'
   DEMO_NC_SERVER=https://<test nextcloud host>
   DEMO_NC_USER=<test nextcloud username>
   DEMO_NC_TASKS_PATH=/apps/tasks/calendars/<shopping list id>   # from Chrome's address bar on the list
   DEMO_UNLOCK_PIN_FILE=<file holding the device PIN>              # only if the S23U has a PIN lock
   DEMO_UNLOCK_PIN_VAR=<variable name in that file>
   DEMO_WAKE_CMD="python3 scripts/demo_wake.py --source R5CR605B71K"
   EOF
   chmod 600 ~/.config/jandal-demo/demo.env
   ```

   `scripts/demo_wake.py` plays the approved `natural_wake` then `qwen_command` fixtures from the S21's debug app at volume 9/15 (`docs/testing/acoustic-stimulus-source.md`). For S01 the S21 sits beside the S23U as in #1403/#1410, with "Listen for Hey Jandal" on.
3. Record (one MP4 per shot plus `manifest.json`; `--out` must be outside the repo):
   `python3 scripts/demo_capture.py --serial RFCW11265XH --package <rc package> --commit <rc sha> --out ~/jandal-demo/<rc> --shots intro`
   S01 needs `DEMO_WAKE_CMD`, and S07A/S07B need `DEMO_NC_SERVER` and `DEMO_NC_USER`, from the env file above. Add `DEMO_NC_DISPLAY_NAME` too if the test user's display name differs from the username: the Nextcloud web page shows it. S07A/S07B refuse to record without `DEMO_NC_SERVER` and `DEMO_NC_USER`; the runner records the screen bounds of every node showing either value, and the edit step blurs them. Re-take a model shot with `--shots S04 --take 2` (at most 3).
4. Render, choosing the end card:
   `python3 scripts/demo_edit.py --clips ~/jandal-demo/<rc> --out ~/jandal-demo/<rc>/intro.mp4 --end-card beta [--pick S04=2] [--icon icon.png]`
   Use `--end-card launch` once the Play listing is public. This also writes `intro.edl.json`: every cut, every speed-up and its factor, every blurred region, the end-card variant and the build provenance. Check it with the video, and check S07 frame by frame for anything the blur missed.

The runner drives the UI by visible labels (`Actions`, `Chats`, `Tools`, `New conversation`, `Send`, `Cancel response`) through `uiautomator`, so it works on release builds, where the debug harness intents are disabled. If a label changes, fix `shots.json`, not the code.

How the runner keeps the cut honest and in sync:

- **Clock sync.** Before each shot the device is left on the home screen, so the first big screen change in the clip is the app appearing. `am start -W` returns when that frame is drawn, and the runner stores the measured offset as `lead_in_s` (S23U: 1.1–1.6 s; a fixed guess was out by ~0.6 s). A take with no launch frame is marked failed.
- **Fresh screen reads.** `uiautomator dump` can exit 0 without writing a new file; the runner deletes the old dump first, checks the output and retries, so it never acts on a stale screen. Each read takes ~2.5–3 s on the S23U, which is why taps and typing are slow in real time.
- **Result checks.** Quick-action shots wait for a *new* confirmation (`"new": true`): Actions history keeps earlier takes' results, so a plain text wait would pass on an old one. A missing result fails the take, and the edit step never picks a failed take.
- **Blur.** For S07A/S07B every screen read (including inside waits and holds) is scanned for the Nextcloud server and username, with and without `https://`. Boxes are stored in screen pixels with the screen and clip sizes; the edit step scales them, or refuses if the clip is rotated.
- **Length.** `demo_edit.py` refuses a full cut over 75 s (`--allow-over-cap` to override) and lists the longest shots. `--shots S03,S06` renders a preview of some shots without the end card.

## Decisions for Nick

Recorded from Nick's review (4 Nov 2026):

1. **Intro order:** offline block first (S01 Hey Jandal, S02 lists, S03 timer and alarm, S04 memory, S05 persona chat), then online (S06 weather, S07 Nextcloud sync), then the S08 end card. Target ~55 s, cap 60 s (raised to 70 s, decision 12).
2. **Weather goes in the intro.** Framing: the assistant and AI run entirely on the phone; Jandal can also reach online services when you ask, with no account or login needed (weather, Wikipedia, currency rates).
3. **Lists are a headline feature:** more time early in the cut, plus Nextcloud sync framed as optional and self-hosted.
4. **Captions only** for the first cut; add the Cori voiceover once #1474 clears provenance.
5. **Take rule approved:** up to 3 takes for model replies, recorded in the manifest.
6. **End card** is a render-time choice: pre-launch "Join the beta", launch "Free on Google Play".
7. **S01 caption** says `Optional "Hey Jandal" wake word`; no "hands-free" claim.

Later decisions (Nick, 4 Nov 2026):

8. **S03** keeps both the timer and the alarm; the typing is sped up, with the marker, to fit.
9. **S06 caption** shortened to "Online when you ask. No account needed." so it renders at full size.
10. **S07's second view** was first the Jandal app on the S21; changed (7 Nov 2026) to the user's own Nextcloud Tasks web page in the S23U browser. One sign-in instead of two, and it shows the item landing on the user's own server.
11. **Typing is sped up in every shot** (to about 1 s, with the marker), as are the weather and sync waits, to shorten the cut. Measured on the S23U, real-time typing alone took ~8 s per request.
12. **Cap raised to 70 s.** With typing sped up, the measured shots put the full cut at about 70 s (each screen read takes ~2.5 s on the S23U). The offline block now runs to about 0:48, but the airplane-mode badge and the S01-S03 hook are on screen within the first 30 s that autoplay on Play. The Time column in the table above is the original plan; measured shot lengths (S23U, Nov 2026) are about S01 8 s, S02 11 s, S03 8 s, S04 12 s, S05 9 s, S06 6 s, S07 11 s, end card 5 s.
13. **Cap raised to 75 s** (7 Nov 2026). The full rehearsal measured 72.5 s once the end holds were no longer being dropped; Lokhor will watch a full cut and decide whether to trim further.
