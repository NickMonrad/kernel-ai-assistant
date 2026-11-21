# #2005 / PR #2029 — final on-device acceptance

PR head under test: `80bca11f14752e6c5294af72f1c273c51b91b3fb` (`feat/2005-asr4all-integration-9`, upstream `feat/2005-asr4all-integration`).

## Builds

| Device | Target | APK | sha256 | versionCode |
|---|---|---|---|---|
| S23U (RFCW11265XH) | PR head 80bca11f | debug `assembleDebug` | `9e22e6915c1a7c9a1fb5818a9fa02ddd356a603c10a3efc74baaff4ba1c158c4` | 4432 |
| S21 (R5CR605B71K) | Combined test-only: PR head + `origin/fix/1961-e2b-tool-prompt-length` (9302b80b), merge `a5ef8c16` | debug `assembleDebug` | `cda9ace9da90156490dcca6db20c18ac45a113de3bb78d9f5a888ea49117187e` | 4432 |

The combined merge `a5ef8c16` existed only as a local branch (`test/2005-final-combined-local`); it was not pushed. Part B ran on that S21 build, so Part B results cover the combined build.

DB state: S23U `kernel_db` user_version 59. S21 user_version 60 before install.

## Results

| # | Check | Result | Evidence |
|---|---|---|---|
| A1 | PTT punctuated final + latency (S23U, Asr4all M, source S21 `qwen_command`) | **PASS** | Final `What time is it?` (punctuated), route `get_time`, reply `It's 3:52 am`. Fixture 25.04–26.72 (S21 clock); `ADB_INTENT_TRACE submitMode=Voice` 27.39; capture audio focus abandoned 27.46. Final 0.67 s after speech ended, 3.8 s after tap. |
| A2 | Mid-dictation M→L switch (S23U) | **NOT RUN / FAIL for the mid-dictation AC** | Switch done between turns, not mid-capture. Chip L selected at 03:55:02; L reconciled as already on disk (`existingStates=[SUCCEEDED]`). After that, M files absent from disk (see Observations). PTT with L: capture 35.49 → final `ADB_INTENT_TRACE` 39.11, route `get_time`. Switch back to M not performed (M not on disk). |
| A3 | Live partials during PTT (S23U) | **NOT VERIFIED** | 10 `screencap` frames over the capture window, plus `uiautomator dump` attempts (dump ~2.3 s, too slow to sample a 1.5 s utterance). Frames show only `Listening, speak now`; no partial text in the composer or bubble. No partial log lines for chat PTT. |
| B1 | Hey Jandal hand-off (S21 target, S23U source) | **PASS** | `natural_wake` played 42.72–44.84 (S23U clock); S21 `WakeWordDetector: detected (high confidence=0.743)` 44.686; `WakeWordService: yielding mic to voice session` 44.802. `qwen_command` played 48.05–49.63; route `get_time`, reply `It's 3:58 am` shown in S21 Actions list. Detector re-armed 51.07. Hey Jandal restored to **false** (was false before the test). |
| B2 | Noisy-room endpointing (S21 target) | **PASS** (synthetic noise; see caveats) | Steady pink noise, SNR 10.07 dB, 4.0 s: tap 44.69, final 49.79 (0.8 s after playback end), route `get_time`. Babble, SNR 9.62 dB, 4.0 s: tap 16.27, final 21.25 (0.5 s after playback end), route `get_time`. Both well under 60 s. Transcripts routed correctly but lacked the trailing `?`. |
| B3 | Screen-off during Loop reply (S21), wake 224 | **PASS** | Loop capture abandoned 46.74. `SpeakingStarted` 49.059; `keyevent 26` at 49.149 (`mAwake=false`), playback ended 50.765. No capture after playback. `keyevent 224`: `mAwake=true`, MainActivity focused, pid 25146 unchanged. No FATAL/AndroidRuntime from `ai.jandal`. |
| W | Audio focus: each capture `requestAudioFocus` paired with `abandonAudioFocus` (`Asr4allVoiceInputController`, `NativeAndroidVoiceInputController`) | **PASS** (for the captures listed above) | `MediaFocusControl` lines in `logcat-sanitized.log`. |

## Observations (not fixed; no product code changed)

1. **PTT is tap-to-start.** `feature/chat/.../ChatScreen.kt` binds PTT to `Surface(onClick = onStartVoiceInput)`. A hold/swipe does not keep the mic open; capture begins at tap and ends on endpoint. A "release-to-final" measurement therefore is tap-to-final, not release-to-final. Confirm this matches the acceptance wording in #2005.
2. **M files disappeared from disk after L was selected.** On S23U, `files/models/asr4all/m/` no longer existed at 03:55:25; `asr4all/l/` is present. Log at 03:55:02–07 shows `Enqueuing asr4all L … existingStates=[SUCCEEDED]` and `Refreshed state for asr4all M …: NotDownloaded`. `Asr4AllInstall.deleteFiles` only deletes the named size, and no delete was logged. Cause not identified; not claimed as intended one-size-on-disk policy. Reproduce before filing.
3. **Partials not observable** in chat PTT for Asr4all (A3), see table.
4. **Noisy finals drop the trailing `?`.** B2 transcripts routed correctly but are unpunctuated. Not a correctness failure for routing; relevant to A1's punctuation AC under noise.
5. **Babble fixture is synthetic** (8-band AM noise, not recorded babble). Results apply only to that noise model.
6. Pre-existing conversation history on both devices. Not captured in evidence.

## Device state at end

- S23U: Asr4all `L` selected and Downloaded; M files removed (see observation 2). Not restored. `svc power stayon false`. Keyguard off at end. Acoustic fixtures removed from `ai.jandal.assistant.debug/files/acoustic-fixtures`.
- S21: Asr4all selected, M on disk. Hey Jandal restored to false. Acoustic fixtures removed. `svc power stayon false`. Idle, MainActivity.
- Logcat captures cancelled after tests. Sanitized log: `logcat-sanitized.log` (filtered to app pids 3598/25146 and related tags; placeholder titles redacted).

## Not done

- A2 mid-dictation switch and switch-back to M.
- A3 partials verification.
- Hold-to-talk release measurement (PTT is tap-to-start; see observation 1).

## Re-run r2: A2 mid-dictation M→L switch and A3 live partials (S23U)

Scope: PR head `35484706` (evidence commit on top of `80bca11f`); APKs unchanged from the table above. Source: S21 (`R5CR605B71K`) acoustic receiver. Fixtures built from the private `qwen_command` fixture by `/tmp/jandal-long/make.py` (host-only, not committed): `a2long_1..7` and `a3long_1..4`, each = qwen_command + 300 ms gap + qwen_command + 300 ms gap (3593 ms). Chunks are chained broadcasts with no pause between them. Every chunk is the same recorded utterance, so the speech is repetitive, not a natural long dictation.

Test-induced change, restored: a stray tap at (400,1373) on S23U Voice settings selected `Android native` (prefs `AndroidNative`). Restored by tapping the asr4all streaming card; prefs back to `Asr4all` before any capture in this re-run.

| # | Check | Result | Evidence |
|---|---|---|---|
| A2 | Mid-dictation M→L switch | **PASS** (retry; first attempt see below) | Retry 04:12: L tap 15.399; S23U `Asr4allVoiceInputController` requestAudioFocus 17.866; S21 chunk-1 playback starts ≈17.95 (S21 wall 738.308); `ModelDownloadManager: Download succeeded … asr4all/l/…/asr_encoder.pte` 18.451, i.e. ~0.5 s into first speech chunk, so the L download completed during the live capture. Final: `ADB_INTENT_TRACE` 45.811, `ADB_ROUTE_DECISION` `get_time` 45.844, `ADB_TOOL_COMPLETE` 46.280 (transcript delivered, not cancelled). Audio focus abandoned 45.949. `Refreshed state for asr4all M encoder: NotDownloaded` 45.987; M dir gone at poll 46.185 (`/l` only). Next PTT on L (trial `a2-next-l`, qwen_command, S21 start 777.234 ≈ host 56.9): `ADB_INTENT_TRACE` 59.604, route `get_time` 59.641, `ADB_TOOL_COMPLETE` 04:13:00.076. No `asr4all engine failed to open` in logcat. |
| A3 | Live partials during PTT | **PASS** (engine L, after A2 retry) | `screenrecord --time-limit 15` on S23U, PTT tap 1.2 s after start, chain a3long_1..4 from S21 (4 broadcasts). Frames at 4 fps, cropped to bottom 900 px (`a3-frames/sheet1..3.png`, 16 frames per sheet, 0–12 s). In the `Listening, speak now` banner the partial grows: `What?` → `What time is?` → `What time is it? What` → `What time is it? What time is it?` → `… What time is?` → `… What time is it? What time is` (≈1.5–12 s). Composer stays `Message Jandal…` during capture. Final came after the recording had ended (`ADB_INTENT_TRACE` 32.476, device clock), so the frames show partials before final, not the final bubble. Sheet 4 (12–15 s) not reviewed. Partials on engine M not re-measured in this run. |

First attempt (04:09), not counted as PASS: L encoder finished 27.672, 24 ms after capture focus 27.648 and before speech onset (~28.0), so the download did not overlap speech. Capture ended with `ADB_INTENT_TRACE` 55.667 `get_time`; abandon 55.786; M NotDownloaded 55.813; M dir gone by 56.245 (M deleted after capture end, transcript delivered). Retry (04:12) used a shorter navigation (L tap → PTT ≈ 2.5 s) so speech began before the download finished.

Observation 2 (M disappearing from disk after L selection): re-run shows the M removal is the designed one-size-on-disk commit, and it happened only after the capture's audio focus was abandoned (45.949 → M gone by 46.185; 56.245 in the first attempt). Whether the commit waits for capture idle is inferred from this ordering, not from a direct log line. Earlier "cause not identified" note is superseded.

End state (S23U): asr4all engine selected (prefs `selected_voice_input_engine` = `Asr4all`, `asr4all_model_size` key present), model M installed, `files/models/asr4all/m` only, L removed. Set `svc power stayon false`. S21: stayon false; acoustic fixtures removed from `files/acoustic-fixtures`; `/data/local/tmp` a2long/a3long/qwen wav and manifest removed (52 unrelated items left in `/data/local/tmp`, not touched). Logcat jobs cancelled. Sanitized log: `logcat-r2-sanitized.log` (pids 3598 S23U, 25146 S21).

Open question (not decided by me): the first run recorded the original engine setting as `Automatic (Recommended)`. The coordinator's end state is M, while the "restore original if different" clause would require Automatic. I left M per the explicit end state. Confirm which is wanted; switching to Automatic is one tap on S23U.

Partial-frame evidence `a3-frames/` shows only the chat test conversation and the Jandal banner/composer; status bar and conversation list were not captured.
