# Build 6 pre-alpha QA — S21 (Exynos)

- **Build:** `main` @ `c3bc27f8` (`fix(#1918)…`), `:app:assembleDebug`, APK sha256 `e3b89339f64805667ffa3f02ac8eabd908ff71ba4e91c77644a77b5d4c84dacd`.
- **Install:** `adb install -r` (no `pm clear`/uninstall). `dumpsys package ai.jandal.assistant.debug` → `lastUpdateTime=2026-11-15 17:56:29`.
- **Device:** S21 SM-G991B. Lock `/tmp/jandal-s21.lock` (mkdir) held from 17:55 to 18:43, then released.
- **Not covered:** #1915 (chat-voice chunking) is not merged into this build and needs its own re-check. The S23U was locked by another agent and was not used.

## How speech input was produced

No one spoke during the run. Voice input came from the debug-only acoustic stimulus receiver (`docs/testing/acoustic-stimulus-source.md`), which played fixtures through the S21's own speaker:

- **Wake word:** `natural_wake`, the owner's private "Hey Jandal" recording from `~/private-acoustic-fixtures`. It was installed in the app's private files only for the run and has been deleted from the device and the host. It is not committed.
- **Commands:** generated on the host with a sherpa-onnx VITS voice (piper `en_US-lessac-medium`). The phrases were "add milk to my shopping list", "what time is it", "tell me a short story about a dog" and "what's the weather in Brisbane". espeak-ng and Cori renderings were misrecognised by the device STT ("at milk to my shopping layst"), so they were not used.
- **Volume:** media volume stayed at **1** except while a fixture was playing. During playback the receiver set media volume to 5–9 for 1.4–3.6 s and then restored it, confirmed each time by `cleanup_completed … exact_restoration_verified:true`. Jandal's replies play on `USAGE_ASSISTANT` (stream 11, index 11/15), so media volume 1 does not lower them (see O4).

All screenshots are crops of Jandal UI or system media controls. Wallpaper, lock-screen photos, notifications and home-screen widgets are excluded. Remaining light wallpaper pixels behind translucent widgets were flattened.

## Results

| # | Check | Result | Evidence |
|---|---|---|---|
| 1a | Add Jandal widget and Lists widget with the One UI widget picker over adb | **PASS** | Picker search for "Jandal" lists 4 identical "Jandal AI" entries, one per installed variant (`ai.jandal.assistant[.debug]`, `com.kernel.ai[.debug]`). The 4th entry is the debug app. `dumpsys appwidget`: id 36 `KernelWidgetReceiver`, id 37 `ListsWidgetReceiver`. Screenshots: `T1-01-jandal-widget-added.png`, `T1-04-lists-widget-config.png` |
| 1b | Widget text box sends to Jandal | **PASS** | `T1-textbox.log` (`WidgetTextInputActivity: submitting text=…`, `trust=DIRECT`, `add_to_list`), `T1-03-widget-textbox-sent-actions.png` |
| 1c | Widget mic opens `VoiceCommandActivity` with Starting, Listening (ring + hint) and Processing | **PASS** | `T1-06-overlay-starting.png`, `T1-07-overlay-listening-ring-hint.png`, `T1-08-overlay-partial-transcript.png`, `T1-11-overlay-processing-add-milk.png`. The hint appeared on the early uses and was gone on later ones (`MicHintPolicy` cap). See O2 for how Processing looks. |
| 1d | Start cue plays at Listening | **PASS** | `T1-cue-timing-run5.log`: capture `startInput` 18:10:07.771 → `ToneGenerator startTone` 07.786 (+15 ms). Run 1: 19.850 → 19.867. |
| 1e | Spoken reply plays | **PASS** | `T1-cue-timing-run5.log`: transcript "add milk to my shopping list" → `add_to_list` → `speaking_started … "Added milk to your shopping list."` → `startOutput stream 11`. Screenshot: `T1-12-actions-add-milk-result.png` |
| 1f | Lists widget refreshes after "add milk to my shopping list" | **PASS** | `T1-05-lists-widget-before.png` (eggs) → `T1-10-lists-widget-after-add-milk.png` (eggs, milk), confirmed in the UI tree |
| 2a | Hey Jandal on the lock screen, triggered by voice | **PASS** | Swipe keyguard enabled for this test only, `isKeyguardShowing=true`. Real detection: `confidence=0.90`, `VERIFIED_ACTIVATION`, `COMMAND_ROUTING_RESULT handed_off`, `VoiceCommandActivity` START (`T2-lockscreen-wake.log`, `T2-wake-journal.json`). Overlay over the keyguard: `T2-01-lockscreen-overlay-processing.png`. Reply "It's six fifteen P M." played while still locked. Lock-screen chip: `T2-02-lockscreen-jandal-speaking-chip.png` |
| 2b | Overlay phases on the lock screen | **PASS** (finding F5) | On the wake path, capture happens inside `WakeWordService` with no UI, and the overlay appears only after the final transcript, for about 0.5 s in the Processing state. When the overlay is launched the way the assistant/side-key path does (`am start … VoiceCommandActivity -f 0x10000000`), Starting, Listening (ring) and Processing all render over the keyguard: `T2-03`, `T2-04`, `T2-05` `*.png`, `T2b-lockscreen-overlay-launch.log` |
| 3a | #1913 volume-down while speaking in the app | **PASS** | `T3a-inapp-voldown-stop.log`. Key sent at 18:27:14.38, `AudioTrack stop` at 14.96, 2.0 s into a 3.4 s chunk. Volumes before and after: media 1, assistant 11. No volume panel, and no further speech. |
| 3b | #1913 volume-down during a reply started from the overlay (widget mic → reply in the foreground app) | **PASS** | `T3c-overlay-initiated-voldown-stop.log`. Stopped 2.5 s into a 91-character chunk. Volumes unchanged. Generation finished afterwards with no further speech. |
| 3c | Volume-down while speaking on the **lock screen** (Hey Jandal reply) | **FAIL** (F1) | `T3b-lockscreen-voldown-and-chip-stop.log`: `dispatchVolumeKeyEvent` → `Local MediaSessionRecord can handle volume key` → `adjustStreamVolume() stream=11 dir=-1` → assistant volume 11 → 10, `ACTION_PANEL_SHOW`. Speech went on (next chunk `speaking_started` 18:20:06). |
| 3d | Stop on the media card in the shade | **PASS** | `T3-02-shade-media-card-speaking.png`, `T3d-shade-media-card-stop.log`: custom action at 40.323 → `AudioTrack stop` at 40.493, card removed, no further speech |
| 3e | Stop on the lock-screen "Jandal is speaking" chip (extra) | **PASS** | `T3b-lockscreen-voldown-and-chip-stop.log`: custom action at 18:20:42.065 → stop at 42.139, no speech afterwards. The #1913 notes said the lock-screen card was not seen on the S21. On this build it renders (`T2-02`). |
| 4a | #1918 screen off mid-reply keeps speaking | **PASS** | `T4a-screen-off-keeps-speaking.log`. After `KEYCODE_SLEEP` the device was `Dozing`, the current chunk played to the end (74252 frames), and the next chunks `speaking_started` at 18:31:31 and 18:31:40 played with the screen off. |
| 4b | Home stops speech (Actions reply) | **PASS** | `T4c-actions-home-stops-speech.log`. `ON_STOP keepSpeech=false` → `AudioTrack stop` 0.7 s into a 3 s reply. |
| 4c | Home stops speech (voice-originated **chat** reply, LLM fallthrough) | **FAIL** (F2) | `T4d-chat-voice-reply-home.log`. Home at 18:36:15.69, but the chunks at 18:36:24 and 18:36:40 still played in the background. The chat "Speak message" read-aloud also kept going after Home (`T4b-chat-speak-message-home.log`: Home at 18:31:53, next chunk at 18:32:08). |
| 4d | No mic session after (`dumpsys audio`) | **PASS** | `T4e-no-mic-session-after.txt`. `RecordActivityMonitor` has no active recording. The last `rec stop` is at 18:35:24 (end of the overlay capture), with nothing after Home or screen off. Hey Jandal was off for this test. |
| 5 | #1850 pre-roll handoff with Hey Jandal on | **PASS** (owner check) | `T5-wake-preroll-handoff.log`, `T5b-wake-then-command-no-pause.log`, `T5*-wake-journal.json`. Detector `stopInput`/`releaseInput` at 18:15:11.346–.392, then Sherpa capture `startInput` at 11.474. That leaves a 128 ms gap in which no capture is open. The command in the fixture starts at 11.693, after capture opens. The cue fires at capture open (`STT_READY` 11.466, `startTone` 11.467). Final transcript sha256 is `cbe649f8…`, which is exactly "what time is it", so the cue does not land in the transcript. No `Pre-roll trimmed` warning. **Owner check:** a human saying the command with no pause after the wake word. |
| 6 | Setup wizard re-run (Settings → "Set up Jandal again") at font 1.0 and 2.0; Back exits without changing settings | **PASS** (finding F4) | All 4 steps render at 1.0 (`T6-f100-step01..04b.png`) and 2.0 (`T6-f200-step0*.png`). Back steps 4 → 3 → 2 → 1 → Settings. `T6-wizard-prefs-compare.txt`: every user-settings datastore and shared_prefs file is byte-identical. Only the wizard's own resume marker (`step`) changed; `completed` and `decided` stayed true. |
| 7 | Weather with Open-Meteo stalling: stale or "isn't answering" reply in ≤ ~10 s | **PASS** ("isn't answering"); stale branch is an **owner check** | Voice: Running action at video t=7.0 s → "The weather service isn't answering right now…" at t=17.0 s, i.e. 10.0 s (`T7-01`, `T7-02` `*.png`). Log: `Live weather fetch exceeded the 10000 ms budget` (`T7a-weather-voice-stall.log`). Text "weather in Brisbane": 10.2 s (`T7b-weather-text-brisbane.log`, `T7-03`). Both runs logged `Weather cache miss after API_UNAVAILABLE`, so the stale-cache reply could not be reached from this network. The spoken reply starts about 4.6 s after the text (TTS synthesis). |

## Bugs (not filed)

**F1. Medium: volume-down on the lock screen changes the assistant volume instead of stopping speech (#1913 gap).**
Steps:
1. Turn Hey Jandal on.
2. Lock the phone (swipe keyguard) and wake the screen.
3. Say "Hey Jandal, tell me a short story about a dog".
4. While Jandal is speaking, press volume-down.

Actual: the system volume panel appears, the assistant stream goes from 11 to 10, and speech continues with the next sentences. Expected: speech stops and the volume stays the same, as it does in the app.

Cause: with the keyguard focused, no Jandal activity receives the key. `MediaSessionRecord` treats the local-playback `JandalSpeech` session as a volume target. The lock-screen chip's Stop button does work. Evidence: `T3b-lockscreen-voldown-and-chip-stop.log`.

**F2. Medium: Home does not stop chat speech.**
Steps:
1. Tap the widget mic and say "tell me a short story about a dog". It falls through to chat and the reply is spoken.
2. While it is speaking, press Home.

Actual: the reply keeps speaking sentence by sentence in the background. Tapping "Speak message" in chat and then pressing Home behaves the same way. Expected: Home stops speech, as on Actions (#1918 states "Home/navigation still stops it", but `pauseTransientVoiceUi` is only wired to `ActionsScreen`).

Shade/lock Stop and volume-down in the app still work. Re-check with #1915. Evidence: `T4d-chat-voice-reply-home.log`.

**F3. Medium (accessibility): the widget mic glyph is nearly invisible.**
`KernelWidget` draws `android.R.drawable.ic_btn_speak_now`, which renders grey (115,115,115), on FernGreen (79,121,66). Measured contrast is **1.07:1**, against the 3:1 minimum for UI components (`.omp/AGENTS.md`). Evidence: `T1-01-jandal-widget-added.png`.

**F4. Low–Medium (large font): the setup wizard keeps the scroll position between steps.**
Steps:
1. Set font scale to 2.0.
2. Open Settings → Set up Jandal again.
3. Scroll step 2, then tap Continue.

Actual: step 3 opens scrolled down with its title ("Download Jandal's models") off-screen. The same happens on steps 2 and 4 if the previous step was scrolled. Back to step 1 also keeps the scroll offset. If the previous step was not scrolled, the next one opens at the top. Evidence: `T6-f200-step02-no-prior-scroll.png` versus `T6-f200-step03-after-scrolling-step02.png` and `T6-f200-back-to-step01.png`.

**F5. Low (design gap): the Hey Jandal path shows no Starting or Listening overlay.**
`WakeWordService` captures the command with no UI, and the cue is the only listening signal. `VoiceCommandActivity` appears only after the final transcript, for about 0.5 s. On the lock screen the user gets no in-app indication that the mic is open, apart from the OS mic dot. The #1850 states appear only on the widget and assistant paths. Evidence: `T2-01` versus `T2-03`…`T2-05`, `T2-lockscreen-wake.log`.

## Observations

- **O1. Long silent gaps between spoken sentences** in chat and long replies: 3.5–12 s per sentence (`Sherpa streaming chunk: 10974ms for 119 chars`). Short replies take 2.2–4.6 s from intent to first audio (`T3b`, `T3d`, `T4a` logs). This overlaps #1915.
- **O2. The "Processing…" label never shows when partial transcripts arrive**, because the partial text replaces the label. Processing can only be told apart from Listening by the ring disappearing, the pulse stopping and the hint going away (`T1-11`). This matches the code (`partialText.ifEmpty { … }`).
- **O3. Enabling Hey Jandal downloads about 103 MB without a prompt**: the Whisper tiny.en verifier (`SHERPA_WHISPER_TINY_EN_*`, `AUTO_QUEUED`, files dated 18:13–18:14). Neither the toggle copy nor the wizard mentions it; the wizard's download step says "Nothing left to download".
- **O4. Replies and the wake cue ignore media volume.** TTS uses `USAGE_ASSISTANT` (stream 11, 11/15) and the wake cue uses `STREAM_ALARM` (11/15), so the "media volume at 1" condition did not lower either.
- **O5. The adb harness tip no longer produces voice replies.** Shell-delivered `quick_action_input` now arrives as `trust=PREFILL` (#1604, `callerUid=-1`), and tapping Send submits it as Text, so nothing is spoken. Voice turns in this run used the widget mic with the acoustic stimulus.
- **O6. Copy and cosmetic issues:**
  - The Voice settings subtitle reads "Always-on wake word detection. Tap Home-press to activate when recognised."
  - The list chooser shows "Shopping list" while the widget and card show "shopping list".
  - The widget picker shows 4 indistinguishable "Jandal AI" entries when several variants are installed.
- **O7. Brief chat-screen flash during overlay handoff.** In the widget-mic recording, the chat input bar ("Message Jandal…", PTT/Loop) was visible for about 100 ms before Actions rendered, after a previous turn had navigated to chat. This was seen in the screen recording only; the frame was not kept because the wallpaper shows behind it.

## Device restore

| Setting | Before | After |
|---|---|---|
| Media volume (stream 3) | 8 | 8 (1 during the run) |
| Assistant volume (stream 11) | 11 | 11 (restored after F1 lowered it to 10) |
| `stay_on_while_plugged_in` | 0 | 0 (`svc power stayon usb` during the run) |
| `locksettings get-disabled` | true | true (false for T2/T3b only) |
| `location_mode` | 3 | 3 (not changed) |
| `font_scale` | 1.15 | 1.15 (1.0 and 2.0 for T6 only) |
| Hey Jandal | off | off (`WakeWordService` not running) |
| Active mic recording | none | none |

Left on the device: the two widgets added for the test (allowed), the debug app's "shopping list" created by the test (eggs, milk; the Lists widget shows it), the test Actions and chat history, and the Whisper verifier models from O3.

Removed from the device: acoustic fixtures, stimulus result files and screen recordings under `/sdcard`. Removed from the host: temporary voice models and the venv.

Do not merge — wait for review.
