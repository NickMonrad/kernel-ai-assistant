# #1892 first-audio measurement on S23U (alternating main / branch)

Device: S23U (`RFCW11265XH`, SM-S918B), unlocked, screen kept on. Media volume 1 during runs, restored to 11.
Voice: Settings > Voice shows "Cori · Ready" and "Cori is selected" before the run. Download was not tapped.
Builds:
- MAIN: `origin/main` 6e0c34cd (exported with `git archive`, debug APK built from that export).
- BRANCH: `fix/chat-voice-chunking-1892` 6f6567c2 (debug APK built from the worktree).

Prompt: "What is the capital of Samoa? Answer in two short sentences." via `quick_action_input` (voice).
Protocol: install (`install -r`), 60 s idle, one unmeasured warm-up run, then 3 measured runs per build, alternating MAIN, BRANCH, MAIN, BRANCH, MAIN, BRANCH.
Metrics:
- `send→SpeakingStarted`: device-clock `am start` time to the `SpeakingStarted epochMs` log line.
- `decode tok/s`: last `decode=… tokens @ … tokens/s` line from `LiteRtInferenceEngine` logged after each run.
- `first-chunk-queued→SpeakingStarted`: NOT measured. The app has no log line at the queue point, and main has no
  equivalent, so the metric cannot be compared between builds without adding instrumentation to both.

| Run | send→SpeakingStarted (ms) | decode tok/s |
|---|---|---|
| MAIN warm-up (excluded) | 59267 | 16.08 (21 tok) |
| MAIN 1 | 31254 | 14.11 (183 tok) |
| MAIN 2 | 46917 | 16.75 (204 tok) |
| MAIN 3 | 38344 | 21.57 (36 tok) |
| BRANCH warm-up (excluded) | 69357 | 15.34 (142 tok) |
| BRANCH 1 | 36610 | 21.84 (96 tok) |
| BRANCH 2 | 52122 | 17.67 (259 tok) |
| BRANCH 3 | 47364 | 16.48 (165 tok) |

Summary (3 measured runs each):
- MAIN send→SpeakingStarted: median 38344 ms, mean 38838 ms.
- BRANCH send→SpeakingStarted: median 47364 ms, mean 45365 ms.
- MAIN decode: mean 17.48 tok/s. BRANCH decode: mean 18.66 tok/s. Decode token counts vary widely (36 to 259).

Result: on the S23U this sample does NOT show the branch as faster. The branch is slower on both median (+9 s) and mean
(+6.5 s). Three runs per build with this variance is inconclusive. The S21 runs (`first-audio-v2.md`) showed the
branch faster. The two devices disagree, so "first audio is faster" is not established by either measurement.

Caveats:
- `am start` logged "intent has been delivered to currently running top-most instance" on every run. The app process
  stayed alive between runs, so the runs are not cold starts. Decode counts differ per run because the model answers differently.
- Decode tok/s is read from the last log line in logcat, which may not be the same generation as the SpeakingStarted line
  when several generations are logged. Treat it as indicative.
- Engine per run was not captured (logcat was cleared per run and the `engine=` line was not extracted).
- No personal data is in these logs; the harness token was created on-device and removed afterwards.

Script: `measure-first-audio.sh` (token read on-device via `run-as`; no secrets committed).
