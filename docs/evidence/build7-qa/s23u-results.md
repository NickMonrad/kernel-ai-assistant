# Build 7 QA: S23U results (tracking #1944)

## Header

- Device: S23U, `RFCW11265XH`, SM-S918B, Android 16. Debug build `ai.jandal.assistant.debug` from `origin/main` `8173f2dd`, APK sha256 `cfb3ec80…f6b7`, `lastUpdateTime=2026-11-16 21:28:46` (unchanged at the end of the run). Gemma-4 E-4B on GPU.
- Sessions under the lock protocol:
  - QA agent 1 (`b7qa-s23u-agent`), 21:28–21:46 AEST: #1941, the #1922 natural fallback, the timer, the TTS crash, then out of budget;
  - QA agent 2 (`b7qa-s23u-agent2`), 21:48–22:15: UI checks;
  - parent (`b7qa-parent`), 22:17–22:24:28 and 22:24 (one file removed): #1952 re-run, #1922 card, #1932 chooser, and cleanup.
- Recorded originals (22:17): ringer 0 (silent), zen_mode 0, media 11, alarm 1, ring 0, notification 0, font_scale 1.15, night mode yes, Appearance "Use your phone's colours" off.
  - Silenced for each session: media 0, DND on (alarm `Muted: true`).
  - Restored and verified: zen 0 (`mZenMode=ZEN_MODE_OFF`), ringer 0, media 11, alarm 1, ring 0, notification 0, font 1.15, night yes, Appearance off.
- No `svc power`, sleep or power keys, and no PIN entry. Typed harness routes only after the TTS crash was found (#1968).

## Results

| Issue | Check | Verdict | Evidence | Notes |
|---|---|---|---|---|
| Smoke | Cold start reaches the ready UI | PASS | – | `am force-stop` + `am start -W`: TotalTime 1141 ms; UI dump of the ready screen. |
| Smoke | First chat reply | PASS | – | Agent 1: the harness chat "In one sentence, why is the sky blue?" generated a reply. |
| Smoke | Weather quick intent | PASS | `s23u-smoke-weather-auckland.log` | Typed "what's the weather in Auckland" → `get_weather` card, no inference-engine lines. MET Norway answered because Open-Meteo really was failing, not because of the debug marker. The marker was removed by the #1922 script after 22:10:54.5 and before this run (first command 22:11:11.7). In this run the 5 s `OPEN_METEO_BUDGET_MS` expired (22:11:19.5 → 24.77) with no `Weather API failed` or `debug: forced Open-Meteo failure` line; the marker path logs those at once. |
| Smoke | Timer 5 s | PASS (with observation) | – | Agent 1: needed a temporary `POST_NOTIFICATIONS` grant (restored to off). It fired. The alert's text read "I didn't catch anything — please try again" [INFERENCE: the alert-time listening found no command, with the phone silenced; not investigated]. |
| Smoke | Voice reply (Cori) | **FAIL** → #1968 | – | Agent 1's first voice reply crashed the app: `PatternSyntaxException` in `PronunciationLexicon.<clinit>`. The same crash is on the S21, see `s21-tts-crash-patternsyntax.log`. |
| #1941 | Typed "ad milk to my shopping lest" → add_to_list | PASS | `s23u-1941-actions-ui-dump.txt` | Agent 1's run. The Actions UI dump (22:10, before cleanup) shows the card routed `add_to_list` with "• milk"; the cards that went to the model read `llm_fallthrough`. |
| #1941 | run_intent bulk_add_to_list via chat | PASS (agent-observed, no artefact) | – | Agent 1: "Use run_intent with bulk_add_to_list…" → Gemma called `run_intent(bulk_add_to_list)`, `success=true`, and the 'qa test' card listed eggs, bread and butter. The logcat was not kept, and the chat was deleted in cleanup. |
| #1941 | Natural multi-item phrasing (observation) | Observation | – | Chat route, agent 1: "Please add eggs, bread and butter to my qa test list" was quick-routed to `get_list_items` (list "and butter to my qa test list"). Another phrasing created a stray list "the three ingredients eggs, flour and sugar to the qa test" (its DELETED tombstone is in the DB). On the Actions route (parent, 22:22), the same sentence added one item "eggs, bread and butter" to the right list. Outside #1941's Done-when; not filed. |
| #1922 | Forced Open-Meteo failure → MET Norway, uncached city | PASS | `s23u-1922-met-fallback.log`, `s23u-1922-met-fallback-card.png` | Marker on, typed "what's the weather in Oamaru": forced failure 22:10:45.774 → MET answered 22:10:47.808. The card shows Oamaru, NZ, 9 °C, Overcast, "Weather data: MET Norway (CC BY 4.0)". Marker removed and confirmed gone. |
| #1927 | Home stop with Cori | NOT RUN (blocked by #1968) | – | Verified on the S21 with Android TTS instead. |
| #1928 | Widget mic contrast on the S23U home screen | NOT RUN | – | Agent 2's 3 launcher drag attempts bound nothing. The home screen blocks uiautomator (`could not get idle state`), so the parent did not retry blind. Measured on the S21 instead: 5.07:1. |
| #1932 | Voice settings Hey Jandal subtitle | PASS (old text gone only) | `s23u-dark-font1.15-voice-top.png` | Reads "Set Jandal as default assistant first for reliable background mic access.", the text shown when Jandal is not the default assistant. That proves only that the old "Tap Home-press" text is gone; the new default-assistant text is verified on the S21. |
| #1932 | List chooser casing | PASS | `s23u-1932-list-chooser-casing.png` | `ListsWidgetConfigureActivity` ("Choose a list") shows `shopping list`, like the card (`s23u-1952-4-actions-loaded.png`). Closed with Back; nothing selected. |
| #1932 | Widget picker labels | PASS for this build | – | Agent 2 saw one "Jandal AI (debug)" row plus four "Jandal AI" rows. Five packages expose widget receivers: this debug build, `ai.jandal.assistant`, `com.kernel.ai`, `com.kernel.ai.debug` and `com.kernel.ai.debug.llamaprobe`. The four "Jandal AI" rows are those other packages, which don't have #1946's debug label [INFERENCE: the rows were not mapped one by one]. |
| #1952 | No chat composer frame on the chat → Actions handoff | PASS | `s23u-1952-frames.txt`, `s23u-1952-1…4-*.png` | Parent re-run (agent 2's run sent only "Add", so it never reached Actions). f020 chat → f021 opaque cover → f022–23 Actions empty state → f024 Actions loaded; no composer frame after f020. Dark mode only. |
| #1943 | 'Set up Jandal again' is the first Settings row | PASS | `s23u-1943-settings-top.png` | |
| #1943 | 'Download on mobile data' only in Model Management › Downloads | PASS | `s23u-1943-settings-top.png`, `s23u-1943-model-management-downloads.png` | Not in the top-level list (scrolled, text absent). In Model Management: "Models download on Wi-Fi only", switch off. |
| #1943 | Setup guide and Model Management agree | PASS | `s23u-1943-model-management-downloads.png`, `s23u-dark-font1.15-step3-top.png` | Both show Wi-Fi only. Toggling was not exercised. |
| #1938 | Jandal colours by default; Appearance switch | PASS | `s23u-1938-dark-system-colours-on.png`, `s23u-1938-light-system-colours-on.png`, setup images (Jandal green) | Switch off by default (Jandal fern green throughout the setup images). Turned on, it applied in place with no restart (teal accents in both captures), then restored to off. |
| #1936 | Setup guide steps 1–4: step header, cards, chips, footer | PASS | `s23u-{dark,light}-font{1.15,2.0}-step*.png` | All four combinations reviewed: "Step N of 4" and a segmented bar; option cards; status chips with icon and text (`✓ On`, `○ Off`, `Downloaded`, `Recommended`); a "More below" cue above a divided footer; the step 4 end at 2.0 clears Finish. |
| #1929 | Each step opens scrolled to the top | PASS | `*-step*-top.png` | Captured after Continue at both font sizes. |
| #1931 | Hey Jandal download size | PASS on S21 / n/a on S23U | – | The S23U already has the Hey Jandal models (Whisper tiny.en "Downloaded"), so its card correctly shows no extra size. The S21 shows "Adds a 117 MB download". |
| #1937 | Online-speech switch inside the selected option | PASS at 1.15 / NOT RUN at 2.0 | `s23u-{dark,light}-font1.15-voice-top.png`, `s23u-dark-font1.15-step2-top.png` | Inside the Automatic card, in both Settings › Voice and setup step 2. The 2.0 captures did not include the Automatic card's switch. |

## Cleanup

- Agent 1: removed the stray list "the three ingredients…".
- Agent 2 removed:
  - 'milk' from 'shopping list' (empty before) and the 'qa test' list;
  - the chats "In one sentence, why is the sky blue?", "Use run_intent with bulk_add_to_list…", "I am planning a baking session…", "Please add eggs, bread and butter…", "Remind me about the team lunch" and its "Add…" thread, and "tell me a short story about a dog…" (agent 1's crashed voice test; agent 2 flagged it as outside its list, but it was QA test data);
  - the widget picker it had left open.
- Parent removed:
  - 'qa flash test' and 'eggs, bread and butter' from 'shopping list' (now empty, as before) and the re-created 'qa test' list;
  - the "Remind me about the team lunch" chat from the #1952 re-run;
  - 13 Actions cards dated 21:30–22:23 (deletion stopped at the first older card, 6:20 pm);
  - the harness token and `/sdcard/b7.xml`.
- DB check after cleanup: 0 conversations and 0 Actions rows created or updated after 21:20.

## Re-run after #1969 (`origin/main` `e227d09d`, APK sha256 `65a2386b…eea3`)

- Lock `b7qa-parent`, 22:53–23:12:37 AEST.
- Installed 22:54:04 and left in place: the S23U now has the clean `e227d09d` debug build.
- Recorded at 22:53:55: ringer 0, zen 1 (the "Sleeping" schedule), stay-on 2, media 11, alarm 1, ring 0, notification 0, font 1.15, night yes.
- Silenced with media 0 and DND on. No `svc power`, sleep or power keys.
- Restored: media 11; the rest was untouched.
- **zen_mode now reads 0, not 1.** `set_dnd off` left the active Sleeping rule `OVERRIDE_DEACTIVATE` until it next ends and re-arms. A manual priority DND was not set in its place, because it wouldn't end in the morning. Ringer silent and notification volume 0 keep the phone silent.
- Voice: Cori.

| Check | Verdict | Evidence | Notes |
|---|---|---|---|
| Plain Cori chat reply | PASS | `s23u-rerun-chat-cori.log` | `speak_end … spoken=true reason=COMPLETED`; no crash. |
| #1927 Home stops a Cori voice reply | PASS | `s23u-rerun-1927-home.log` | Home 23:03:26.95 → `INTERRUPTED_BACKGROUND` 23:03:27.40, then `playEnd`. No later `playStart` in 25 s; chunk 1 `discard … stale`. (Attempt 1 waited for a log tag this phone doesn't print, so Home came after the story had finished; it isn't counted.) |
| #1942 Cori readout (`JandalTest` only, filter first) | PASS | `s23u-rerun-1942-readout.log` | `pause ms=400`, gap after the pause 2 ms, later gaps 0 ms and 0 ms, `COMPLETED`. Card rows showed only `Test …`. The spoken text isn't logged on this phone (no `KernelAI_TTS` lines); checked on the S21. |
| #1942 Home mid-readout | PASS | `s23u-rerun-1942-readout-home.log` (+ `-run1.log`) | `INTERRUPTED_BACKGROUND` 0.42 s after Home; no `mCbf` line. Run 1: One UI delivered `ON_STOP` 3.07 s after Home, so one more chunk started before the stop. |
| Observation | – | `s23u-rerun-1942-readout-stopped-once.log` | One readout, just after the app returned from the background while the engine re-initialised after an eviction, ended `STOPPED` after 157 ms. Not reproduced: a 90 s background idle and a forced eviction (`am send-trim-memory … BACKGROUND`) both ended `COMPLETED`. |

Cleanup:
- Calendar id 13 and its events deleted; the filter cleared.
- `READ_CALENDAR` back to `granted=false` (no USER_SET, as recorded).
- Chats (3) and Actions cards (8) from 22:53 on deleted; DB read-back 0.
- Token and `/sdcard/b7.xml` removed.
