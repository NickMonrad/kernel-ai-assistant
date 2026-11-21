# #1993 device check: S23U (RFCW11265XH), Cori TTS, `mahi` -> `ma-hee` (PENDING)

Status: PENDING. Not run on device. The S23U is PIN-locked (`isKeyguardShowing=true`, `mDreamingLockscreen=true`). The owner must unlock it in person. An earlier attempt on this device crashed the debug app (see below). This evidence is incomplete until the device check is redone.

## What ran

- Host unit tests: `./gradlew :core:voice:testDebugUnitTest` for `PronunciationLexiconTest` (21 tests) and `SherpaSpeechTextTest` (61 tests) passed. Includes the new `mahi` cases.
- Debug build: `./gradlew :app:assembleDebug` succeeded on this branch (Room v59, `KernelDatabase` `version = 59`).
- `settings global stay_on_while_plugged_in`: set to 15, then restored to 0 (previous value). The restore to 0 is the setting that left the device locked. For any future use, set it to 2 and leave it at 2 afterwards.
- `SampleClipReceiver` broadcasts (text `Ka pai tō mahi.`, then `Ka pai tō ma-hee.`) started the process, and the app crashed both times:

```
IllegalStateException: A migration from 60 to 59 was required but not found.
  at androidx.room.RoomConnectionManager$SupportOpenHelperCallback.onDowngrade(...)
```

## Why

- The S23U debug database `databases/kernel_db` already had `user_version = 60` before this install. The header was read with `run-as` and `head -c 100` (read-only).
- This branch is Room v59, so the v59 build cannot open a v60 database. The brief said the S23U is v59, which the device did not match.
- Recovery needs either a v60 build (excluded by the brief) or clearing the debug app data (destructive). Neither was done.

## Device state after this run

- `settings global stay_on_while_plugged_in`: set to 15 for the test, restored to 0 (previous value).
- S23U lock `/tmp/jandal-s23u.lock`: released.
- Debug app `ai.jandal.assistant.debug`: crash-loops on launch with this build. Needs the owner's decision (reinstall the v60 build that was there, or clear app data).
- Screen was not turned off, no power or lock key pressed, no PIN entered.

## Not measured

- Clip durations for the raw `mahi` and respelled `ma-hee` renders on the S23U. Host reference (from `/tmp/1993-options/manifest.json`, option opt0 and opt6): sentence `Ka pai tō mahi.` ~1.35 s; `Ka pai tō ma-hee.` ~1.40 and ~1.47 s.
- Whether `ma-hee` has an audible pause at the hyphen on device.
- The live speech path does not log the prepared text (only its length, and only with verbose logging). `SampleClipReceiver` also bypasses the lexicon, so on-device confirmation of the spoken text is not possible with current logging. The lexicon-to-spoken mapping is covered by `PronunciationLexiconTest` (`SherpaSpeechText.prepare("Ka pai tō mahi.") == "Ka pai tō ma-hee."`).
