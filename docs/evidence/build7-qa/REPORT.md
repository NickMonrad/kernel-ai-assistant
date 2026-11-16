# Build 7 device QA pass

Tracking: #1944 (milestone "Build 7 — setup polish & tester follow-ups").

**Result:**
- The first pass on `8173f2dd` found one release blocker, filed as #1968: every spoken reply with a Sherpa voice (Cori, the default) crashed the app. `PronunciationLexicon` compiled `(?U:\s+)`, which Android's ICU regex rejects; it came in with `ccf857ae` (#1958).
- #1969 fixed it (merged as `e227d09d`). [The re-run on that build](#re-run-after-1969-e227d09d) passes on both phones:
  - a plain Cori reply ends `speak_end … reason=COMPLETED` with no crash;
  - #1927: Home stops a Cori voice reply (S23U), and screen-off keeps it speaking (S21);
  - #1942: the spoken calendar readout passes (gaps, A-M, Jandle, the intro pause, and the `speak_end` reasons).
- Everything else checked on `8173f2dd` passes.

## Build and devices

| Item | Value |
|---|---|
| Source | First pass: `origin/main` `8173f2dd6e9300f5c7179dd683bbc1f80897290c`. Re-run: `origin/main` `e227d09d9f10d1a935053f76d0c8c526201286a6` (#1969 merged) |
| APK | `./gradlew assembleDebug`, package `ai.jandal.assistant.debug`. `8173f2dd`: sha256 `cfb3ec8049ade805cbfe96bd207c51aecbb11d803eee57ae13f6d8ed08e1f6b7`. `e227d09d`: sha256 `65a2386bc2cc05c67cf076cbe97c97d20bea26877f2ce8072f1f6e17e1f4eea3` |
| S23U | `RFCW11265XH`, SM-S918B, Android 16, Gemma-4 E-4B GPU. Installed 21:28:46. Details: [s23u-results.md](s23u-results.md) |
| S21 | `R5CR605B71K`, SM-G991B, Android 15, Gemma-4 E-2B GPU. Installed 21:28:57; the #1968-fix agent reinstalled it at 22:12:34 (see below). Details: [s21-results.md](s21-results.md) |
| Honor | Not touched |
| When | 2026-11-16, first pass 21:24–22:29 AEST; re-run 22:51–23:12 AEST. Each phone was silenced before anything audible: ringer silent, media/notification 0, DND on, alarm `Muted: true`. Speech was judged from logs. Recorded values were restored and re-read (see the results files; one S23U DND exception in [Re-run](#re-run-after-1969-e227d09d)). |

Locks: `/tmp/jandal-s23u.lock` and `/tmp/jandal-s21.lock` were held with owner files for every device session and released by their owners.

At 22:12:34, between QA sessions, another agent working on the #1968 fix (`fix-1968-lexicon`) reinstalled the S21 debug app with its own build. Only two S21 rows ran after that (#1928, #1931); both are marked S21* below.

That build is `6910c1a7`, the tip of `origin/fix/lexicon-regex-android-1968`, branched directly from `8173f2dd` (merge-base `8173f2dd`). `git diff --stat 8173f2dd 6910c1a7` touches only:
- `PronunciationLexicon.kt` (+5/−2)
- the new `PronunciationLexiconDeviceTest.kt` (+29)
- `core/voice/build.gradle.kts` (+2)
- `gradle/libs.versions.toml` (+2)

No widget or setup code changed, so the two S21* rows hold for `8173f2dd`.

## Results

PASS / FAIL / NOT RUN / OWNER CHECK. "S21*" means the S21 had the #1968-fix agent's build installed at the time.

| Issue | What was checked | S23U | S21 | Evidence |
|---|---|---|---|---|
| Smoke | App start to ready UI | PASS (cold, 1141 ms) | PASS (warm only, 631–1151 ms; the assistant service restarts the process at once) | results files |
| Smoke | First chat reply | PASS | PASS | [s21-1920-rebuilds.txt](s21-1920-rebuilds.txt) |
| Smoke | Timer 5 s (silenced) | PASS (alert text observation) | PASS | results files |
| Smoke | Weather quick intent | PASS. The S23U run hit a real Open-Meteo stall: the 5 s budget expired with no exception, unlike the marker path, and the marker had been removed before the run | PASS | [s23u-smoke-weather-auckland.log](s23u-smoke-weather-auckland.log) |
| Smoke | List add / remove | PASS | PASS | results files |
| **#1968 (new)** | **Any Cori/Sherpa speech** | **FAIL: crash** on `8173f2dd` → **PASS on `e227d09d`**: Cori reply `reason=COMPLETED`, no crash | **FAIL: crash** on `8173f2dd` → **PASS on `e227d09d`**: same | [s21-tts-crash-patternsyntax.log](s21-tts-crash-patternsyntax.log), [s21-1942-read-aloud-crash.log](s21-1942-read-aloud-crash.log); re-run: [s23u-rerun-chat-cori.log](s23u-rerun-chat-cori.log), [s21-rerun-chat-cori.log](s21-rerun-chat-cori.log) |
| #1941 | Typed "ad milk to my shopping lest" → `add_to_list`, no LLM | PASS: Actions card routed `add_to_list`, "• milk" (other cards read `llm_fallthrough`) | – | [s23u-1941-actions-ui-dump.txt](s23u-1941-actions-ui-dump.txt) |
| #1941 | Chat `run_intent(bulk_add_to_list)` | PASS (agent-observed, no artefact): QA agent 1 saw `success=true` in logcat. The log was not kept, and the chat was deleted in cleanup | – | s23u-results.md |
| #1940 | Weather mishearings | OWNER CHECK | OWNER CHECK | `TranscriptNormaliser` runs only inside the STT controllers; the harness route skips it, so typed tests prove nothing. Unit tests: `TranscriptNormaliserTest`, `WeatherSttRoutingTest`. |
| #1927 | Home stops a chat voice reply | **PASS with Cori** (`e227d09d`): `INTERRUPTED_BACKGROUND` 0.45 s after Home, the next chunk discarded | PASS (Android TTS, `8173f2dd`) | [s23u-rerun-1927-home.log](s23u-rerun-1927-home.log), [s21-1927-home.log](s21-1927-home.log) |
| #1927 | Home stops 'Speak message' read-aloud | NOT RUN with Cori (read-aloud and Home share the stop path checked above) | PASS (Android TTS, `8173f2dd`) | [s21-1927-readaloud-home.log](s21-1927-readaloud-home.log) |
| #1927 | Screen off keeps speaking | n/a (S21 only) | **PASS with Cori** (`e227d09d`): chunks 1–3 played with the screen off (gaps 7, 5, 283 ms); also PASS with Android TTS on `8173f2dd` | [s21-rerun-1927-screenoff.log](s21-rerun-1927-screenoff.log), [s21-1927-screenoff.log](s21-1927-screenoff.log) |
| #1928 | Widget mic contrast + content description | NOT RUN (launcher placement failed) | PASS* 5.07:1, "Voice input" | [s21-1928-widget-mic-button.png](s21-1928-widget-mic-button.png) |
| #1932 | Hey Jandal subtitle copy | PASS on S21 (the new text, Jandal is default assistant). S23U shows the not-default-assistant text ("Set Jandal as default assistant first…"), which only proves the old "Tap Home-press" text is gone | PASS | [s21-1932-voice-hey-jandal-subtitle.png](s21-1932-voice-hey-jandal-subtitle.png) (`8173f2dd`, before the S21 reinstall), [s23u-dark-font1.15-voice-top.png](s23u-dark-font1.15-voice-top.png) |
| #1932 | List chooser casing `shopping list` | PASS | – | [s23u-1932-list-chooser-casing.png](s23u-1932-list-chooser-casing.png) |
| #1932 | Widget picker labels | PASS for this build ("Jandal AI (debug)"); the other rows are older installed packages | – | s23u-results.md |
| #1952 | No chat composer frame on the widget → Actions handoff | PASS (screenrecord, 10 fps, dark) | – | [s23u-1952-frames.txt](s23u-1952-frames.txt), [1](s23u-1952-1-chat-before-handoff.png) [2](s23u-1952-2-opaque-cover.png) [3](s23u-1952-3-actions-first-frame.png) [4](s23u-1952-4-actions-loaded.png) |
| #1909 | Permission reply wording, prompt path (deny) | – | PASS | [s21-1909-prompt-path-reply-and-system-prompt.png](s21-1909-prompt-path-reply-and-system-prompt.png) |
| #1909 | App info path (permanently denied) | – | PASS (App info opens; the reply's second sentence covers it) | [s21-1909-app-info-opened.png](s21-1909-app-info-opened.png), [s21-1909-app-info-path-reply.png](s21-1909-app-info-path-reply.png) |
| #1912 | Acceptance modules compile | PASS (host) | – | [1912-compile-tasks.txt](1912-compile-tasks.txt) |
| #1943 | 'Set up Jandal again' is the first Settings row | PASS | PASS | [s23u-1943-settings-top.png](s23u-1943-settings-top.png) |
| #1943 | Mobile-data switch only in Model Management › Downloads; same value as setup step 3 | PASS | – | [s23u-1943-model-management-downloads.png](s23u-1943-model-management-downloads.png) |
| #1942 | Card: rows, time column, Show more (test calendar only) | – | PASS | [s21-1942-card-collapsed.png](s21-1942-card-collapsed.png), [s21-1942-card-expanded.png](s21-1942-card-expanded.png) |
| #1942 | Spoken readout: gaps, A-M, Jandle, intro pause, `speak_end` reasons, Home stop | **PASS** (`e227d09d`): pause 400 ms, gaps 2/0/0 ms, `COMPLETED`; Home → `INTERRUPTED_BACKGROUND` | **PASS** (`e227d09d`): "A-M", "Test Jandle sync", pause 400 ms, gaps 577/2/2 ms, `COMPLETED`; Home → `INTERRUPTED_BACKGROUND` 0.65 s | [Re-run](#re-run-after-1969-e227d09d); crash on `8173f2dd`: [s21-1942-read-aloud-crash.log](s21-1942-read-aloud-crash.log) |
| #1920 | Conversation rebuilds per command | – | PASS: 1, 1, 1 (t0 → `generate()` 3.8–3.9 s) | [s21-1920-rebuilds.txt](s21-1920-rebuilds.txt) |
| #1938 | Jandal colours by default; the Appearance switch applies without restart | PASS (restored to off) | – | [dark](s23u-1938-dark-system-colours-on.png), [light](s23u-1938-light-system-colours-on.png) |
| #1936 | Setup steps 1–4: header, cards, chips, footer cue | PASS (4 combinations) | – | `s23u-{dark,light}-font{1.15,2.0}-step*.png` |
| #1929 | Each step opens at the top | PASS | – | `s23u-*-step*-top.png` |
| #1931 | Hey Jandal card names its download | n/a (models already on the phone) | PASS* "Adds a 117 MB download" | [s21-1931-setup-step4-hey-jandal-size.png](s21-1931-setup-step4-hey-jandal-size.png) |
| #1937 | Online switch inside the selected engine card | PASS at 1.15; NOT RUN at 2.0 | – | [s23u-light-font1.15-voice-top.png](s23u-light-font1.15-voice-top.png), [s23u-dark-font1.15-step2-top.png](s23u-dark-font1.15-step2-top.png) |
| #1930 | Hey Jandal overlay (what can be shown silently) | – | PARTIAL: only the overlay's "Listening…" state from the widget entry, which the wake path reuses. The wake path itself is an owner check. The card is translucent, so three home-screen app icons (phone, Brave, Firefox) show faintly through it; no text, wallpaper photo or personal data | [s21-1930-overlay-listening-widget-entry.png](s21-1930-overlay-listening-widget-entry.png); wake-path items are on the owner's checklist |
| #1922 | MET Norway fallback, debug marker, uncached city | PASS: Oamaru answered by MET Norway 2.0 s after the forced failure; card credits MET; marker removed | – | [s23u-1922-met-fallback.log](s23u-1922-met-fallback.log), [s23u-1922-met-fallback-card.png](s23u-1922-met-fallback-card.png) |

Font: the phones' default font scale is 1.15, so "default" in the setup images means 1.15.

## Failures and issues

| Finding | Issue | Severity |
|---|---|---|
| Sherpa speech crashes the app (`PronunciationLexicon` `(?U:\s+)`, ICU) | **#1968** (new, Build 7 milestone) | Release blocker. Fixed by #1969 (`e227d09d`) and verified on both phones in the re-run. |
| E-2B Actions fallthrough "what lists do I have": `Input token ids are too long … 3382 >= 3072` (S21) | #1961 (existing, Build 8) | Known; not re-filed. |

Observations (not filed):
- #1941, chat route: "Please add eggs, bread and butter to my qa test list" was quick-routed to `get_list_items` with a wrong list name. Another multi-item phrasing created a stray list. On the Actions route, the same sentence adds one item "eggs, bread and butter" rather than three. Outside #1941's Done-when.
- S23U timer alert text read "I didn't catch anything — please try again" while silenced. Not investigated.
- #1952: Actions shows its empty state for about 0.2 s before history loads. This is not a chat frame.
- Re-run, S23U, seen once: the first readout after the app came back from the background ended `speak_end … reason=STOPPED` 157 ms after `speak_start`, with chunk 0 discarded. It happened while the engine was re-initialising after an eviction. It did not reproduce after a 90 s background idle, nor after a forced eviction (`am send-trim-memory … BACKGROUND`); both readouts ended `COMPLETED`. Evidence: [s23u-rerun-1942-readout-stopped-once.log](s23u-rerun-1942-readout-stopped-once.log).
- Re-run, S23U, seen once: on the first Home-mid-readout run, One UI delivered the Actions `ON_STOP` 3.07 s after the Home key. So one more chunk started before speech stopped. The repeat stopped 0.42 s after Home. Evidence: [s23u-rerun-1942-readout-home-run1.log](s23u-rerun-1942-readout-home-run1.log).
- Re-run, both phones: after a Home stop, the chunk already being synthesised keeps running in the background and is then discarded. On the S23U it took 15–33 s at rtf 1.9–2.5. No audio plays (`discard … reason=stale`).
- Re-run, S21 spoken text: AM is respelled "A-M", but PM is spoken as "P M" ("one forty five P M"). Whether eSpeak says "P M" correctly is a by-ear check, added to the owner checklist.

## Method notes

- Harness: a fresh token is written with `run-as` and sent with `chat_input`, `quick_action_input`, or `quick_action_input_encoded`; `quick_action_is_voice` is false wherever speech would trigger #1968. #1927 used Android TTS for the run, and the voice engine was restored to Piper/Cori afterwards.
- #1942 used a LOCAL test calendar `JandalTest` with 5 made-up events, behind the debug test-calendar filter set before any read. The UI dump showed only `Test …` rows before any screenshot. Calendar, events and filter were removed, and permissions restored.
- #1909 used deny paths only, with a far-future date (2099). Permission flags were restored to `granted=false, USER_SET`.
- Screenshots are cropped to app content. No conversation list, contacts or real events were captured. Home-screen content appears in two places:
  - `s21-1928-widget-mic-button.png` is cropped inside the widget's mic button;
  - the translucent #1930 overlay card lets three app icons show faintly through it, with no text or wallpaper photo.

## Cleanup

All QA test data was removed on both phones:
- lists and items, the test calendar, 8 S23U and 16 S21 chats, 13 S23U and 22 S21 Actions cards;
- harness tokens, marker files and dump files.

A DB read-back on each phone shows no conversation or Actions row created after 21:20. On the S21, two chats and two cards made by the #1968-fix agent's runs (22:13, 22:18) were removed in the same sweep. Device settings and permissions were restored to their recorded values (tables in the results files).

**Installed build at the end:** both phones have the clean `origin/main` `e227d09d` debug build (sha256 `65a2386b…eea3`): the S23U since 22:54:04, the S21 since 22:54:17. The #1968-fix agent's build `6910c1a7` is gone from the S21.

## Re-run after #1969 (`e227d09d`)

Both phones were locked (`b7qa-parent`; S23U 22:53–23:12:37, S21 22:54–23:11:43) and silenced first; speech was judged from logs. The clean `e227d09d` debug APK was installed on both.

Logs:
- logcat `VoiceTiming`: `playStart`, with `gapMs` = silence the pipeline added, the inserted pause excluded; plus `pause`, `discard` and `synth`;
- `KernelAI_TTS speaking_started … text=` for the spoken text, S21 only; the S23U doesn't print this tag;
- the in-app event log `files/jandal_events.log` for `speak_start`/`speak_end` and reasons.

| Check | S23U | S21 | Evidence |
|---|---|---|---|
| Plain Cori chat reply ("Say hello to me in one short sentence", voice → chat) | PASS: `speak_end … spoken=true reason=COMPLETED`; no crash | PASS: same ("Keeorah. Hello."); no crash | [s23u-rerun-chat-cori.log](s23u-rerun-chat-cori.log), [s21-rerun-chat-cori.log](s21-rerun-chat-cori.log) |
| #1927 Home stops a Cori voice reply (story, voice → chat) | PASS: Home 23:03:26.95 → `speak_end … reason=INTERRUPTED_BACKGROUND` 23:03:27.40; no later `playStart` in 25 s; next chunk `discard … stale` | – | [s23u-rerun-1927-home.log](s23u-rerun-1927-home.log) |
| #1927 screen off keeps a Cori reply speaking | – | PASS: `Going to sleep` 22:56:35.3, then `playStart` chunks 1, 2, 3 at 38.58, 43.84, 52.36 (gaps 7, 5, 283 ms) until the wake key at 22:57:00.5 | [s21-rerun-1927-screenoff.log](s21-rerun-1927-screenoff.log) |
| #1942 readout, test calendar `JandalTest` only (filter set before any read; the card dump showed only `Test …` rows) | PASS: "You have five events today", `pause ms=400`, gap after the pause 2 ms, later gaps 0 and 0 ms; `COMPLETED` | PASS: same intro, `pause ms=400`, gap after the pause 577 ms, later gaps 2 and 2 ms; `COMPLETED` | [s23u-rerun-1942-readout.log](s23u-rerun-1942-readout.log), [s21-rerun-1942-readout.log](s21-rerun-1942-readout.log) |
| #1942 spoken text | not logged on this phone | PASS: "seven thirty to seven thirty five **A-M**, Test standup.", "ten o'clock to ten fifteen A-M, Test **Jandle** sync. …", no calendar name spoken | [s21-rerun-1942-readout.log](s21-rerun-1942-readout.log) |
| #1942 Home mid-readout | PASS: `INTERRUPTED_BACKGROUND` 0.42 s after Home; no `mCbf … abnormal` line (first run: 3.07 s, see Observations) | PASS: `INTERRUPTED_BACKGROUND` 0.65 s after Home, chunk 2 `discard … stale`; no `mCbf` line | [s23u-rerun-1942-readout-home.log](s23u-rerun-1942-readout-home.log), [s21-rerun-1942-readout-home.log](s21-rerun-1942-readout-home.log) |

No `FATAL EXCEPTION` from the debug app and no `mCbf call back interval is abnormal` line in any re-run log on either phone. Crackle is still a by-ear check on the owner's list.

Cleanup:
- `JandalTest` calendars and events deleted; provider queries return no rows.
- Filter cleared (`<map />`).
- `READ_CALENDAR` revoked to its recorded state (S23U `granted=false`, no USER_SET; S21 `granted=false, USER_SET`).
- Re-run chats (S23U 3, S21 2) and Actions cards (S23U 8, S21 4) deleted. The DB read-back shows 0 rows since the re-run started. Only rows from 22:53 on were deleted; the #1968-fix agent's earlier S21 rows were left.
- Harness tokens removed.
- S21 audio restored exactly (ringer 2, zen 0, media 2, notification 11, stay-on 0).
- S23U restored: ringer 0, media 11, alarm 1, ring 0, notification 0, font 1.15, night yes. Exception: at 22:53 its `zen_mode` was 1 from the active "Sleeping" schedule. `set_dnd on`/`off` leaves that rule overridden (`OVERRIDE_DEACTIVATE`), so `zen_mode` now reads 0 until the rule ends and re-arms the next night. I didn't set a manual priority DND in its place, because a manual rule would not end in the morning. Ringer silent and notification volume 0 are unchanged, so the phone stays silent tonight.

## Owner internal-testing smoke checklist

Run this on the internal-testing build, which needs #1969 (`e227d09d` or later).

1. **Spoken weather mishearings (#1940):** say "What's the weather in Auckland", "What's the weather tomorrow", and a fast "what's the weather like today". Each should route to the weather quick intent (fast reply, no "Sending to Jandal for processing…").
2. **Hey Jandal on a locked phone (#1930):** with the screen locked:
   - say "Hey Jandal". The overlay shows **Listening**, then your words live, then **Processing**, then the result.
   - start again and tap **X** mid-capture. It closes, nothing runs, and Hey Jandal still works next time.
   - with the screen **off**, say "Hey Jandal". The screen turns on and the overlay shows.
3. **Calendar readout by ear (#1942, Cori):** "what's on my calendar today":
   - "Jandle" (not "jun-DALL"), and AM said as "A-M" (not "am"). PM is sent to the voice as "P M": check it sounds like "P-M";
   - a short pause after "You have N events today";
   - no long gaps between events;
   - press **Home** mid-readout: it stops cleanly with **no crackle**.
4. **Chat voice replies (#1927, Cori):** a long voice reply. Home stops it; screen off keeps it speaking.
5. **Cori 'Kee-ora' sample in setup (#1936):** Settings › Set up Jandal again › step 2 › Cori › Hear a sample. Listen for "Kia ora" said as "Kee-ora".
6. **Setup guide on the Honor (#1936/#1929/#1931):**
   - steps 1–4 at your usual and largest font;
   - each step opens at the top;
   - nothing hidden behind the footer;
   - Hey Jandal shows its download size;
   - the optional-settings card shows the amber **Needs access** state for Honor Usage Access.
