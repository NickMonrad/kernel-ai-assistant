# #1998 gate check on S21 (R5CR605B71K), PR #2045 head 1b79a18e

## Build under test

- Built from PR #2045 head `1b79a18e` plus a normal merge of `origin/main` (`7f25f784`, which includes #2032 / Room v60). Merge commit: `8a10c02a`. This is a real merge on the PR branch, not a test-only merge.
- Debug APK, installed with `adb install -r -d` as `ai.jandal.assistant.debug` (versionName 0.1.0, lastUpdateTime 2026-11-22 09:05:36).
- Device: Samsung SM-G991B (S21), serial R5CR605B71K, no PIN. The S23U and the Honor were not touched.
- Terms state: `TERMS_VERSION` 2026-11-22. Only `files/datastore/terms_acceptance_prefs.preferences_pb` was removed with `run-as` once (between the accept step and check 2). No app data was cleared.
- asr4all M files were downloaded by the app during this run. No files were pushed from a host.

## Results

| Check | Result | Evidence |
|---|---|---|
| 1. Terms not accepted: asr4all download blocked | PASS, with a caveat | `screenshots/check1-*`, `logs/jandal_events_voice_download.log` |
| 2. asr4all selected with files present, Terms not accepted: no engine open, fallback, no loop | PASS, with a caveat | `screenshots/check2-*`, `logs/jandal_events_voice_download.log` (`stt_route`, `route=ASR4ALL`, `online_fallback=false`) |
| 3. Accept Terms via card/banner: download allowed, cancellable, asr4all opens when files present | PASS | `screenshots/check3-*`, `logs/*` |
| 4. Gemma/HF download row shows the Terms link; sheet opens and closes to the same screen | PARTIAL | `screenshots/check4-*` |

### Check 1
- Terms unticked on the asr4all card: the Download button is rendered disabled. Tapping it does nothing.
- No `ModelDownloadWorker`/`ModelDownloadManager` events and no download state changes in the logcat/event window (`logs/logcat_download_excerpt.log`, `logs/jandal_events_voice_download.log` before timestamp 1795302520283).
- The "Accept the Terms of Use to download this speech model." refusal (`ModelDownloadManager`/`ModelDownloadWorker`) was not shown, because the disabled button is never reached. The refusal path itself was not exercised from the UI.
- Caveat: before the tap I checked only `files/` (private) for asr4all, not the external `Android/data/.../files/models/asr4all` directory. The no-download evidence is the empty event/log window, not a directory check.

### Check 2
- Terms unaccepted (preference file removed via `run-as`), M files present, asr4all selected, PTT tapped.
- Route logged: `voice|stt_route|route=ASR4ALL|online_fallback=false`. No online fallback. Screen shows "Offline speech model is not downloaded. Download it in Settings → Voice." This is the missing-model message; the Terms-gated state is surfaced as a missing model.
- No crash (`AndroidRuntime` FATAL: none). One automatic re-arm after `stt_error|category=NO_SPEECH`, then idle: events at 1795302858433 (route), 869054 (NO_SPEECH), 869121 (route), 879312 (NO_SPEECH), then no further voice events for more than 15 s. Bounded, not a loop.
- Caveat: the `Auto` option was not used; the asr4all radio was selected directly.

### Check 3
- Accept via the asr4all card checkbox: M download began and completed (ModelDownloadManager: `config.json`, `release_geom.json`, `preprocessor_config.json`, `metadata.json`, `asr_encoder.pte`, ~60 MB total), about 12 s.
- Accept via the Chats banner ("Please review Jandal's Terms of Use" → "I agree"): banner cleared, datastore written, asr4all card shows checkbox checked.
- L size (about 101 MB) download after banner acceptance: began ("Downloading", Cancel visible). Encoder reached about 78.7 MB, then cancelled (`ASR4ALL_L_ENCODER phase=CANCELLED`). Cancelled directory removed; state NOT_DOWNLOADED.
- PTT with Terms accepted and M files present: route `ASR4ALL`, screen "Listening, speak now". No open failure.
- Deviation: the M download finished before a cancel could be sent, so cancel was exercised on L instead.

### Check 4
- Model Management shows "Read the Terms of Use" in the Still-needed card. Tapping it opens the Terms sheet ("Jandal AI Terms of Use"). Back dismisses it and returns to Model Management (`screenshots/check4-back-to-model-management.png`).
- Not met as written: the Gemma 4 E-4B download row (Not needed on this phone, Download button) shows no Terms link (`screenshots/check4-gemma-e4b-download-row-no-link.png`). The link is only in the Still-needed card. Per-row links were not observed on any Gemma/HF row in this build.
- Sheet open/close behaviour verified on the Still-needed link only.

## Other observations (not gating)
- A LeakCanary notification (JandalVoiceInteractionService leak) appeared on the S21 during the run. Debug build tooling; excluded from screenshots.
- Logcat shows no `KernelAI`-tagged route lines for the PTT tap; the route was taken from `files/jandal_events.log`.

## Files
- `screenshots/`: cropped to the relevant UI; no conversation titles, notifications, or personal data.
- `logs/jandal_events_voice_download.log`: `files/jandal_events.log` lines for voice and download/lifecycle.
- `logs/logcat_download_excerpt.log`: logcat excerpt for ModelDownload and asr4all WorkManager lines.

Do not merge. Gate check only.
