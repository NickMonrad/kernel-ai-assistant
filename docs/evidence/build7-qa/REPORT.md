# Build 7 device QA pass

Tracking: #1944 (milestone "Build 7 — setup polish & tester follow-ups").

**Result: one release blocker found, filed as #1968.** Every spoken reply with a Sherpa voice (Cori, the default) crashes the app. Cause: `PronunciationLexicon` compiles `(?U:\s+)`, which Android's ICU regex rejects. It came in with `ccf857ae` (#1958). Everything else checked passes, except the speech checks that the crash blocks (#1927 with Cori, #1942 readout audio). Those need a re-run after the #1968 fix.

## Build and devices

| Item | Value |
|---|---|
| Source | `origin/main` `8173f2dd6e9300f5c7179dd683bbc1f80897290c` |
| APK | `./gradlew assembleDebug`, sha256 `cfb3ec8049ade805cbfe96bd207c51aecbb11d803eee57ae13f6d8ed08e1f6b7`, package `ai.jandal.assistant.debug` |
| S23U | `RFCW11265XH`, SM-S918B, Android 16, Gemma-4 E-4B GPU. Installed 21:28:46. Details: [s23u-results.md](s23u-results.md) |
| S21 | `R5CR605B71K`, SM-G991B, Android 15, Gemma-4 E-2B GPU. Installed 21:28:57. Details: [s21-results.md](s21-results.md) |
| Honor | Not touched |
| When | 2026-11-16, 21:24–22:29 AEST. Each phone was silenced before anything audible: ringer silent, media/notification 0, DND on, alarm `Muted: true`. Speech was judged from logs. All recorded values were restored and re-read (see the results files). |

Locks: `/tmp/jandal-s23u.lock` and `/tmp/jandal-s21.lock` were held with owner files for every device session and released by their owners.

At 22:12:34, between QA sessions, another agent working on the #1968 fix (`fix-1968-lexicon`) reinstalled the S21 debug app with its own build. Only two S21 rows ran after that (#1928, #1931); both are marked below.

## Results

PASS / FAIL / NOT RUN / OWNER CHECK. "S21*" means the S21 had the #1968-fix agent's build installed at the time.

| Issue | What was checked | S23U | S21 | Evidence |
|---|---|---|---|---|
| Smoke | App start to ready UI | PASS (cold, 1141 ms) | PASS (warm only, 631–1151 ms; the assistant service restarts the process at once) | results files |
| Smoke | First chat reply | PASS | PASS | [s21-1920-rebuilds.txt](s21-1920-rebuilds.txt) |
| Smoke | Timer 5 s (silenced) | PASS (alert text observation) | PASS | results files |
| Smoke | Weather quick intent | PASS | PASS | [s23u-smoke-weather-auckland.log](s23u-smoke-weather-auckland.log) |
| Smoke | List add / remove | PASS | PASS | results files |
| **#1968 (new)** | **Any Cori/Sherpa speech** | **FAIL: crash** | **FAIL: crash** | [s21-tts-crash-patternsyntax.log](s21-tts-crash-patternsyntax.log), [s21-1942-read-aloud-crash.log](s21-1942-read-aloud-crash.log) |
| #1941 | Typed "ad milk to my shopping lest" → `add_to_list`, no LLM | PASS | – | s23u-results.md |
| #1941 | Chat `run_intent(bulk_add_to_list)` | PASS (`success=true`) | – | s23u-results.md |
| #1940 | Weather mishearings | OWNER CHECK | OWNER CHECK | `TranscriptNormaliser` runs only inside the STT controllers; the harness route skips it, so typed tests prove nothing. Unit tests: `TranscriptNormaliserTest`, `WeatherSttRoutingTest`. |
| #1927 | Home stops a chat voice reply | NOT RUN with Cori (#1968) | PASS (Android TTS) | [s21-1927-home.log](s21-1927-home.log) |
| #1927 | Home stops 'Speak message' read-aloud | NOT RUN with Cori (#1968) | PASS (Android TTS) | [s21-1927-readaloud-home.log](s21-1927-readaloud-home.log) |
| #1927 | Screen off keeps speaking | n/a (S21 only) | PASS (Android TTS) | [s21-1927-screenoff.log](s21-1927-screenoff.log) |
| #1928 | Widget mic contrast + content description | NOT RUN (launcher placement failed) | PASS* 5.07:1, "Voice input" | [s21-1928-widget-mic-button.png](s21-1928-widget-mic-button.png) |
| #1932 | Hey Jandal subtitle copy | PASS | PASS | [s21-1932-voice-hey-jandal-subtitle.png](s21-1932-voice-hey-jandal-subtitle.png), [s23u-dark-font1.15-voice-top.png](s23u-dark-font1.15-voice-top.png) |
| #1932 | List chooser casing `shopping list` | PASS | – | [s23u-1932-list-chooser-casing.png](s23u-1932-list-chooser-casing.png) |
| #1932 | Widget picker labels | PASS for this build ("Jandal AI (debug)"); the other rows are older installed packages | – | s23u-results.md |
| #1952 | No chat composer frame on the widget → Actions handoff | PASS (screenrecord, 10 fps, dark) | – | [s23u-1952-frames.txt](s23u-1952-frames.txt), [1](s23u-1952-1-chat-before-handoff.png) [2](s23u-1952-2-opaque-cover.png) [3](s23u-1952-3-actions-first-frame.png) [4](s23u-1952-4-actions-loaded.png) |
| #1909 | Permission reply wording, prompt path (deny) | – | PASS | [s21-1909-prompt-path-reply-and-system-prompt.png](s21-1909-prompt-path-reply-and-system-prompt.png) |
| #1909 | App info path (permanently denied) | – | PASS (App info opens; the reply's second sentence covers it) | [s21-1909-app-info-opened.png](s21-1909-app-info-opened.png), [s21-1909-app-info-path-reply.png](s21-1909-app-info-path-reply.png) |
| #1912 | Acceptance modules compile | PASS (host) | | [1912-compile-tasks.txt](1912-compile-tasks.txt) |
| #1943 | 'Set up Jandal again' is the first Settings row | PASS | PASS | [s23u-1943-settings-top.png](s23u-1943-settings-top.png) |
| #1943 | Mobile-data switch only in Model Management › Downloads; same value as setup step 3 | PASS | – | [s23u-1943-model-management-downloads.png](s23u-1943-model-management-downloads.png) |
| #1942 | Card: rows, time column, Show more (test calendar only) | – | PASS | [s21-1942-card-collapsed.png](s21-1942-card-collapsed.png), [s21-1942-card-expanded.png](s21-1942-card-expanded.png) |
| #1942 | Spoken readout: gaps, A-M, Jandle, intro pause, Home stop | – | **FAIL: blocked by #1968** (Read aloud crashes) | [s21-1942-read-aloud-crash.log](s21-1942-read-aloud-crash.log) |
| #1920 | Conversation rebuilds per command | – | PASS: 1, 1, 1 (t0 → `generate()` 3.8–3.9 s) | [s21-1920-rebuilds.txt](s21-1920-rebuilds.txt) |
| #1938 | Jandal colours by default; the Appearance switch applies without restart | PASS (restored to off) | – | [dark](s23u-1938-dark-system-colours-on.png), [light](s23u-1938-light-system-colours-on.png) |
| #1936 | Setup steps 1–4: header, cards, chips, footer cue | PASS (4 combinations) | – | `s23u-{dark,light}-font{1.15,2.0}-step*.png` |
| #1929 | Each step opens at the top | PASS | – | `s23u-*-step*-top.png` |
| #1931 | Hey Jandal card names its download | n/a (models already on the phone) | PASS* "Adds a 117 MB download" | [s21-1931-setup-step4-hey-jandal-size.png](s21-1931-setup-step4-hey-jandal-size.png) |
| #1937 | Online switch inside the selected engine card | PASS at 1.15; NOT RUN at 2.0 | – | [s23u-light-font1.15-voice-top.png](s23u-light-font1.15-voice-top.png), [s23u-dark-font1.15-step2-top.png](s23u-dark-font1.15-step2-top.png) |
| #1930 | Hey Jandal overlay (what can be shown silently) | – | PASS partial: the overlay "Listening…" state from the widget entry, which the wake path reuses | [s21-1930-overlay-listening-widget-entry.png](s21-1930-overlay-listening-widget-entry.png); wake-path items are on the owner's checklist |
| #1922 | MET Norway fallback, debug marker, uncached city | PASS: Oamaru answered by MET Norway 2.0 s after the forced failure; card credits MET; marker removed | – | [s23u-1922-met-fallback.log](s23u-1922-met-fallback.log), [s23u-1922-met-fallback-card.png](s23u-1922-met-fallback-card.png) |

Font: the phones' default font scale is 1.15, so "default" in the setup images means 1.15.

## Failures and issues

| Finding | Issue | Severity |
|---|---|---|
| Sherpa speech crashes the app (`PronunciationLexicon` `(?U:\s+)`, ICU) | **#1968** (new, Build 7 milestone) | Release blocker. Re-run #1927 with Cori and the #1942 readout after the fix. |
| E-2B Actions fallthrough "what lists do I have": `Input token ids are too long … 3382 >= 3072` (S21) | #1961 (existing, Build 8) | Known; not re-filed. |

Observations (not filed):
- #1941, chat route: "Please add eggs, bread and butter to my qa test list" was quick-routed to `get_list_items` with a wrong list name. Another multi-item phrasing created a stray list. On the Actions route, the same sentence adds one item "eggs, bread and butter" rather than three. Outside #1941's Done-when.
- S23U timer alert text read "I didn't catch anything — please try again" while silenced. Not investigated.
- #1952: Actions shows its empty state for about 0.2 s before history loads. This is not a chat frame.

## Method notes

- Harness: a fresh token is written with `run-as` and sent with `chat_input`, `quick_action_input`, or `quick_action_input_encoded`; `quick_action_is_voice` is false wherever speech would trigger #1968. #1927 used Android TTS for the run, and the voice engine was restored to Piper/Cori afterwards.
- #1942 used a LOCAL test calendar `JandalTest` with 5 made-up events, behind the debug test-calendar filter set before any read. The UI dump showed only `Test …` rows before any screenshot. Calendar, events and filter were removed, and permissions restored.
- #1909 used deny paths only, with a far-future date (2099). Permission flags were restored to `granted=false, USER_SET`.
- Screenshots are cropped to app content. Neither phone's home screen, conversation list, contacts nor real events were captured.

## Cleanup

All QA test data was removed on both phones: lists and items, test calendar, 8 S23U and 16 S21 chats, 13 S23U and 22 S21 Actions cards, harness tokens, marker files and dump files. A DB read-back on each phone shows no conversation or Actions row created after 21:20. On the S21, two chats and two cards made by the #1968-fix agent's runs (22:13, 22:18) were removed in the same sweep. Device settings and permissions were restored to their recorded values (tables in the results files).

## Owner internal-testing smoke checklist

Run this on the internal-testing build after #1968 is fixed.

1. **Spoken weather mishearings (#1940):** say "What's the weather in Auckland", "What's the weather tomorrow", and a fast "what's the weather like today". Each should route to the weather quick intent (fast reply, no "Sending to Jandal for processing…").
2. **Hey Jandal on a locked phone (#1930):** with the screen locked:
   - say "Hey Jandal". The overlay shows **Listening**, then your words live, then **Processing**, then the result.
   - start again and tap **X** mid-capture. It closes, nothing runs, and Hey Jandal still works next time.
   - with the screen **off**, say "Hey Jandal". The screen turns on and the overlay shows.
3. **Calendar readout by ear (#1942, Cori):** "what's on my calendar today":
   - "Jandle" (not "jun-DALL") and "A-M"/"P-M" (not "am");
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
