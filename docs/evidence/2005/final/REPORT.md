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
