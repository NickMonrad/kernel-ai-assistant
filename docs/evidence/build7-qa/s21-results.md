# Build 7 QA: S21 results (tracking #1944)

## Header

- Device: S21, `R5CR605B71K`, SM-G991B, Android 15. Debug build `ai.jandal.assistant.debug` from `origin/main` `8173f2dd`, APK sha256 `cfb3ec80…f6b7`, `lastUpdateTime=2026-11-16 21:28:57`. The #1968-fix agent reinstalled it at 22:12:34; see the build note.
- Model: Gemma-4 E-2B on GPU (app default on this phone).
- Sessions under the lock protocol:
  - first QA agent (`b7qa-s21-agent`), 21:28–21:44 AEST: smoke checks, then out of budget;
  - parent agent (`b7qa-parent`), 21:44–22:10:46 AEST (released): everything else, cleanup and restore.
  - parent agent again, 22:25–22:28:57 AEST (released): #1928 widget crop, #1931 setup step 4, and cleanup. Nothing audible; no TTS.
- Silenced for the first two sessions (recorded before, restored after; see Cleanup).
- **Build note:** at 22:12:34, between my sessions, another agent working on the #1968 fix (lock owner `fix-1968-lexicon`) reinstalled the S21 debug app with its own build, `6910c1a7`, the tip of `origin/fix/lexicon-regex-android-1968`.
  - So the #1928 and #1931 rows ran on that build.
  - Its merge-base with `8173f2dd` is `8173f2dd`, and `git diff --stat 8173f2dd 6910c1a7` touches only `PronunciationLexicon.kt`, the new `PronunciationLexiconDeviceTest.kt`, `core/voice/build.gradle.kts` and `gradle/libs.versions.toml`. No widget or setup code changed.
  - Every other S21 row ran on `8173f2dd`, including `s21-1932-voice-hey-jandal-subtitle.png` (captured about 21:56, before the reinstall), which shows "Adds a 117 MB download" on `8173f2dd` itself.

## Results

| Issue | Check | Verdict | Evidence | Notes |
|---|---|---|---|---|
| Smoke | App start reaches the ready UI | PASS (warm) | – | `am force-stop` is undone within ~50 ms because the system restarts `JandalVoiceInteractionService` (Jandal is the default assistant here). So `am start -W` is WARM: 631–1151 ms. A true cold start was not measurable on this phone. |
| Smoke | First chat reply | PASS | `s21-1920-rebuilds.txt` | Harness chat reached `Generation complete`. Typed fallthrough commands answered in 6.6–8.3 s. |
| Smoke | Timer 5 s | PASS | – | First agent's run. Needed a temporary `POST_NOTIFICATIONS` grant (the debug app's notifications are user-blocked on this phone), restored afterwards. It fired at 21:34:07 and auto-stopped; the app then showed "No timers running". |
| Smoke | Weather quick intent | PASS | – | "what's the weather in Auckland": the weather card, no LLM generation. |
| Smoke | List add and remove | PASS | – | "add qa test item to my shopping list", then removed. The list had to be created first (none existed). |
| Smoke | Chat voice reply with Cori (Sherpa) | **FAIL** → #1968 | `s21-tts-crash-patternsyntax.log` | The app crashes at the first spoken chunk: `PatternSyntaxException (?U:\s+)` in `PronunciationLexicon.<clinit>`. 3 crashes in this session, plus 1 from Actions Read aloud. |
| Known | "what lists do I have" via the Actions fallthrough | FAIL, already tracked | – | `Input token ids are too long … 3382 >= 3072` on E-2B. This is #1961 (Build 8), not new. |
| #1927 | Home stops a chat voice reply | PASS (Android TTS) | `s21-1927-home.log` | Cori crashes (#1968), so voice output was switched to Android TTS for this check and restored afterwards. Home 2 s into speech: `AudioTrack stop` + `abandonAudioFocus` from the Jandal TTS controller within 0.4 s, and no later `speaking_started` in 25 s. The reply was a multi-sentence story; the same prompt in the screen-off run produced 3 more `speaking_started` lines in the next 20 s, so more utterances were due. |
| #1927 | Screen off keeps speaking | PASS (Android TTS) | `s21-1927-screenoff.log` | `KEYCODE_SLEEP` → `Going to sleep due to sleep_button`. Three more `speaking_started` followed with the screen off (21:59:50, 22:00:01, 22:00:09), until the wake key at 22:00:10. |
| #1927 | Home stops 'Speak message' read-aloud | PASS (Android TTS) | `s21-1927-readaloud-home.log` | Read-aloud on the story reply, Home at 22:01:02.65, stop at 22:01:02.96, no further speech in 20 s. |
| #1927 | Same, with Cori | NOT RUN (blocked by #1968) | – | Re-check after the #1968 fix. |
| #1909 | Prompt path wording | PASS | `s21-1909-prompt-path-reply-and-system-prompt.png` | Chat "What is on my calendar on 2099-01-15". Permission flags were reset to the never-asked state. Reply: "Jandal needs calendar access to read your events. Allow it when prompted and Jandal will answer. If Android doesn't ask, turn on Calendar in App info, then ask again." Then the system prompt appeared and was denied. (The Actions route first shows an in-app "Allow calendar lookup?" dialog; its card says "Jandal needs calendar access to read your events.") |
| #1909 | App info path wording | PASS | `s21-1909-app-info-opened.png`, `s21-1909-app-info-path-reply.png` | After the second denial (`USER_FIXED`), "…2099-01-16" opened App info for Jandal AI (debug), with no system prompt. The reply shows the same text, whose second sentence describes this path. #1954 deliberately uses one sentence covering both paths, not two different texts. |
| #1942 | Card: one row per event, Show more | PASS | `s21-1942-card-collapsed.png`, `s21-1942-card-expanded.png` | Test calendar `JandalTest` (LOCAL) with 5 made-up events, debug filter set before any read. The UI dump showed only `Test …` rows. 3 rows, then "Show more (2)". A fixed time column (`7:30 – 7:35 AM`). The calendar name shows as secondary text. |
| #1942 | Spoken readout: gaps, A-M, Jandle, intro pause, Home stop | **FAIL** (blocked by #1968) | `s21-1942-read-aloud-crash.log` | The card's Read aloud crashes the app (same `PatternSyntaxException`). Nothing can be measured until #1968 is fixed. |
| #1928 | Widget mic contrast + content description | PASS (see build note) | `s21-1928-widget-mic-button.png` | The debug quick-actions widget (id 36, already on the S21 home screen from #1945's evidence) was cropped to the mic button only. Glyph `#FFFFFF` on `#4F7942`: **5.07:1** (minimum 3:1), light mode. The mic node has content-desc "Voice input". This replaces the S23U placement, which failed in the launcher. |
| #1931 | Setup guide Hey Jandal card names the download | PASS (see build note) | `s21-1931-setup-step4-hey-jandal-size.png` | Step 4: "Adds a 117 MB download. Uses a little battery." Settings › Voice says the same. Hey Jandal was not turned on, so whether step 3 lists the download when it's on was not checked. |
| #1920 | One rebuild per command | PASS | `s21-1920-rebuilds.txt` | 3 measured chat-path commands after a warm-up: `CONV_REBUILD` = 1, 1, 1 (`reason=updateSystemPrompt`). t0 → `generate()` was 3843 / 3893 / 3806 ms. Prefill was 2511 tokens. Typed fallthrough (`quick_action_is_voice=false`), because the voice route crashes at speech (#1968); the rebuild path (`submitInitialQueryIfNeeded`, independent command) is the same. |
| #1930 | Overlay states that can be shown without sound | PARTIAL | `s21-1930-overlay-listening-widget-entry.png` | The widget-entry overlay (`VoiceCommandActivity`, which the wake path now reuses): mic icon, "Listening…", "Your words are being captured.", X. Back dismissed it, and nothing was routed. The image is cropped to the card with the outside corners masked; the card is translucent, so three home-screen app icons show faintly through it (no text, no wallpaper photo). The wake path itself (Listening → partials → Processing, X cancel, turnScreenOn) needs the owner's spoken check. |
| #1932 | Voice settings Hey Jandal subtitle | PASS | `s21-1932-voice-hey-jandal-subtitle.png` | "Say "Hey Jandal" to start talking, hands-free. Adds a 117 MB download." The old "Tap Home-press…" text is gone. This also shows the #1931 size. |
| #1943 | 'Set up Jandal again' is the first Settings row | PASS | – | Seen in the UI dump (first row, "How Jandal works and download options"). |

## FAIL details

- **#1968 (filed, release blocker):** every Sherpa (Cori/Piper/Kokoro) utterance crashes the app. `PronunciationLexicon.kt:104` `"(?U:\\s+)"` is rejected by Android's ICU regex (added in `ccf857ae`, #1958). Repro: Cori selected; harness `quick_action_input 'tell me a short story about a dog' --ez quick_action_is_voice true`, or Read aloud on any Actions card. Expected: speech. Actual: `FATAL EXCEPTION` → `Process … has died`.
- E-2B Actions fallthrough token overflow: already #1961 (Build 8).

## Cleanup and restore

| Item | Before | After (verified) |
|---|---|---|
| ringer mode | 2 | 2 |
| zen_mode | 0 | 0 (`set_dnd off`) |
| media / alarm / ring / notification | 2 / 11 / 11 / 11 | 2 / 11 / 11 / 11 |
| font_scale, night mode | 1.15, no | not changed |
| `stay_on_while_plugged_in` | 0 | 0 (`svc power stayon false`) |
| READ/WRITE_CALENDAR | granted=false, `USER_SET` | granted=false, `USER_SET` (flags reset for #1909, restored) |
| POST_NOTIFICATIONS | granted=false, `USER_SET` | granted=false, `USER_SET` |
| Voice output engine | Piper (Cori) | Piper (Cori); Android TTS used temporarily |
| Test calendar `JandalTest` (id 11) + 5 events | created | deleted; provider queries return no rows |
| Debug test-calendar filter | set | cleared (`<map />`) |
| Harness token | written | removed |
| Actions cards created tonight (20) | created | deleted in the UI; the first older card (6:19 pm) was left |
| Chats created tonight (14) | created | deleted in the UI |
| Test list `shopping` (created by the first agent; none existed before) | created | deleted |
| 2 chats + 2 Actions cards "tell me a short story about a dog" (22:13, 22:18) | created by the `fix-1968-lexicon` agent's runs, not by QA | deleted by me at 22:27 while cleaning up, before I knew whose they were; its harness token file was also removed (its run had finished and released the lock) |
| Installed debug build | `8173f2dd` (21:28:57) | **not restored:** the S21 still has the #1968-fix agent's build `6910c1a7` (`lastUpdateTime=2026-11-16 22:12:34`). A clean `origin/main` debug build will be installed after the post-#1969 re-run. |
