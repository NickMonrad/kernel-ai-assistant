# #1942 review round 1: evidence

S21 (`R5CR605B71K`, Exynos 2100), lock `/tmp/jandal-s21.lock` held 15:17–15:35 AEST. Debug build
`ai.jandal.assistant.debug`, voice Cori high. Made-up text files and local test calendars only. The S21
also has 6 real events today (counted by event id, no titles read), so every calendar read used the
debug-only test-calendar filter and only `JandalTest` calendars.

## 6. Piper threads: 2 vs 4 on the S21

Same probe texts as the S23U runs ([speech-chunk-timings.md](speech-chunk-timings.md)): `new` = this PR's
wording without the pause marker, `old` = main's wording, `chat` = a 3-sentence reply. One utterance at a
time. rtf = synthesis ms / audio ms (below 1 is faster than real time). Gap = silence the pipeline added
at the first chunk boundary (later boundaries were 0–3 ms in every run).

| Threads | Run | rtf per chunk | Gap after chunk 0 |
|---|---|---|---|
| 2 | foreground, new | 0.94, 1.04, 1.01 | **2445 ms** |
| 2 | foreground, old | 0.85, 0.99, 0.94, 1.16 | **3033 ms** |
| 2 | foreground, chat | 0.80, 0.90, 0.92 | 1201 ms |
| 2 | after Home, old | 0.78, 0.97, 1.03, 1.24 | 3356 ms |
| 4 | foreground, new | 0.56, 0.62, 0.74 | 3 ms |
| 4 | foreground, old | 0.69, 0.75, 0.73, 0.75 | 1444 ms |
| 4 | foreground, chat | 0.70, 0.75, 0.71 | 79 ms |
| 4 | after Home, old | 0.87, 0.86, 0.78, 0.79 | 2414 ms |
| 4 | **final build, real readout** (pause, test calendars) | 0.63, 0.60, 0.61 | 7 ms after the pause; 739 ms at the next boundary |

4 threads is faster than 2 on the S21 in every run, foreground and after Home, so 4 is kept.

After Home the S21 was **not** slowed (no `onTrimMemory`, focus granted). Jandal is the S21's assistant app
(`JandalVoiceInteractionService` is bound by the system), which keeps the process out of the background
CPU group. On the S23U, where it is not, the same step gave rtf ≈ 3 ([speech-chunk-timings.md](speech-chunk-timings.md)).
So the screen-off slowdown (#1959) depends on whether Jandal is the default assistant [INFERENCE: from
the bound service; not tested with the role toggled].

The S21 is slower than the S23U (rtf 0.56–0.75 vs 0.40–0.56 with 4 threads). Its one boundary over 0.7 s
in the final readout is a one-event chunk followed by a two-event chunk (739 ms, under the 1 s target).
Contention with Gemma while a chat reply streams was not measured.

## 7. JNI callback in an R8 build

The release R8 configuration keeps `SherpaContinueCallback.invoke(float[])` with the exact signature
the JNI looks up:

- `-keep` rule in `core/voice/consumer-rules.pro` (class and method).
- Unsigned release build with the CI's R8 settings (`assembleRelease
  -PreleaseSigningPropertiesFile=/nonexistent/...`, so the upload key is never read or used):
  `seeds.txt` lists `com.kernel.ai.core.voice.SherpaContinueCallback: java.lang.Integer invoke(float[])`;
  `dexdump` of `app-release-unsigned.apk` shows `invoke` with type `([F)Ljava/lang/Integer;`.
- **Run on a device:** a local-only Gradle init script (not committed) made the debug build type release-like:
  R8 on with the release proguard files, `debuggable false`, debug signing, `.debug` id. LeakCanary needs
  `leak_canary_allow_in_non_debuggable_build` in such a build. Its `seeds.txt` and `dexdump` show the same
  kept method. Installed on the S21 over the debug app (`flags=[ HAS_CODE … ]`, no `DEBUGGABLE`):
  - a streamed Cori reply (main's calendar text through `SpeechProbeReceiver`), then a second one 4 s later,
    which stops the first through the callback;
  - one process (pid 19450) throughout: `SpeakingStarted` for both, system `AudioTrack … state:started` for
    each chunk, and **no** `Fatal signal`, `JNI DETECTED ERROR`, `NoSuchMethodError` or `FATAL EXCEPTION`.
  - The normal debuggable debug build was reinstalled afterwards.

## 1. Duplicates: what the calendar provider returns

Two LOCAL test calendars, both named `JandalTest`, queried directly through the provider
(`content://com.android.calendar/instances/when/…`, restricted to the test calendar ids):

| Case | Provider rows for today |
|---|---|
| One invite in both calendars (same `uid2445`, same start) | **2 rows**: event ids 4945 and 4946, both `uid2445=jandal-1942-shared@test.invalid`, same begin/end |
| Daily recurring event (3 days) plus an exception for today's instance (renamed, moved 15 min) | **1 row**: the exception (event 4949, `original_id=4948`, same `uid2445`); today's original instance is suppressed |

So a recurring event plus an exception does not duplicate. The same meeting in two calendars does, and
the old dedupe (event id + start) could not merge it, because the ids differ. The owner's duplicate
matches this case [INFERENCE: the owner's calendars were not inspected]. Dedupe now uses the iCalendar
UID + start when a UID is present (`uid2445` is readable on Instances on the S21), and event id + start
otherwise.

App readout on the final build with the filter on: the card shows `Test planning` once,
`Test daily check-in (moved)` once and `Test standup`. Checked with a UI dump only; no S21 screenshot was
taken (the S21 is not used for committed screen captures).

Cleanup: both test calendars and their events deleted (query returns none), filter cleared,
`READ_CALENDAR` revoked to its pre-run state (`granted=false`, `USER_SET` kept), probe files removed,
`svc power stayon` restored to its recorded `0`; media volume was 2 and was not changed.
