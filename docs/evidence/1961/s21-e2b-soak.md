# S21 E-2B memory soak at 7000 (#1961, re-review item 2)

Device: S21 (`R5CR605B71K`), package `ai.jandal.assistant.debug`, E-2B on GPU, MID_RANGE, `maxTokens` 7000. Stayon was set to `true` for the run and restored afterwards.
Result: FAIL. One LMKD kill of the app process at 7000 during a normal background step. The soak was stopped there. The value was NOT changed.

## Turns (one conversation, process pid 23189, 01:52:37 to 02:02:07)

1. Load_skill probe (see `s21-e2b-load-skill-review2.md`).
2. Ordinary explainer, about 250 words (lithium battery).
3. "Should I take an umbrella to Apia this afternoon?" Routed to the non-tool path (`current_prompt_excluding_rag_tokens=168`). Not a tool-path weather check.
4. Ordinary explainer, about 250 words (water cycle).
5. Ordinary explainer, about 250 words (geothermal plant).
6. Load_skill probe, repeated. Success, `serialized_result_tokens_estimate=1507`.
7. Ordinary explainer, about 250 words (bread).
8. Ordinary explainer, about 300 words (printing press).
9. Ordinary explainer, about 300 words (bicycle).

That is 9 turns. The target was 10 or more, with the 75% proactive reset crossed.

## KernelOOM budget lines

- `tokens_in_use` 0 (01:53:58), 2458 (01:56:09), 3446 (01:58:47), logged on some generations only.
- Threshold 5250, `proactive_reset=false` on every logged line.
- The 75% reset was NOT crossed in this run, so the proactive-reset path was NOT exercised.
- Last `max_tool_result_tokens` reading before the kill: 1819 (turn 8).

## Background step and LMKD kill

- 02:02:04 `am start -a android.media.action.STILL_IMAGE_CAMERA` (camera in foreground). The app's activity stopped at 02:02:05.
- 02:02:07.533 lmkd: `Reclaim 'ai.jandal.assistant.debug' (23189), uid 10300, oom_score_adj 100, state 2 to free 2468000kB rss, 779472kB swap; reason: low watermark is breached and swap is low (1600208kB < 1048572kB)`.
- 02:02:08 `ActivityManager: Process ai.jandal.assistant.debug (pid 23189) has died: vis BFGS(2425,458)`.
- Classification: the app was NOT the foreground process at the kill (camera was). oom_score_adj 100 is the visible (not foreground) band. Under the review's rule this is a background kill, not a foreground kill, so the STOP rule does not strictly trigger. It is still a reproduced LMKD kill of the app at 7000 during a normal user step, and the same mechanism as #684 (low watermark, swap low).
- 02:02:11 `JandalVoiceInteractionService` restart on `mem-pressure-event`. New pid 26649. 02:02:20 `MainActivity` relaunched into the model-loading screen. Engine re-initialised at 02:02:23, `Engine ready — backend: GPU, maxTokens: 7000` at 02:02:38.
- `am_proc_died` for another process (`ai.jandal.assistant`, pid 20570, the voice app) at 02:02:07.8. Not the debug app.

## Memory

- Sampler: `MemAvailable` and `MemFree` every 5 s from 01:55:52 to 02:02:58 (85 samples), file kept under `/tmp` only.
  - Minimum MemAvailable 83,908 kB at 02:02:06 (the kill second).
  - Minimum MemFree 35,680 kB at 02:02:27 (after the restart).
- `am_pss` logged for pid 23189 during the run: 4.4 to 5.1 GB as logged (raw bytes, `am_pss`). No `dumpsys meminfo` was captured for pid 23189 before the kill, so the pre-kill `dumpsys` numbers are missing.
- Post-restart `dumpsys meminfo ai.jandal.assistant.debug` for pid 26649 (after `Engine ready` at 7000):
  - TOTAL PSS 2,826,965 kB, TOTAL RSS 2,904,330 kB, TOTAL SWAP PSS 6,071 kB
  - Graphics 1,869,494 kB
  - Native Heap PSS 765,912 kB
  - Java Heap PSS 18,212 kB
  - Code 60,836 kB
- Earlier captures (`s21-e2b-memory-7000-vs-4000.md`) were TOTAL PSS 3,301,187 kB (7000) and 3,178,479 kB (4000), Graphics 2,145,662 kB and 1,972,730 kB.

## Not done

- The 10th turn and the 75% proactive-reset check.
- The 4000 control with the same camera step. Without it, the kill cannot be attributed to 7000 alone rather than to this device under this camera-launch pressure.

## Decision needed

The soak does not show 7000 is safe on S21 under this background step. Options:
1. Run the same camera-background step at 4000 to see whether the kill is specific to 7000.
2. Hold the 7000 default until a 7000 run survives the step.
3. Reduce the MID_RANGE window and split the load_skill budget another way.

No value change was made in this PR.
