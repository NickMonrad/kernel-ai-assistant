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
  - Native Heap PSS 766,007 kB (Private Dirty 765,912 kB; the earlier draft quoted the Private Dirty column)
  - Java Heap PSS 18,212 kB
  - Code 60,836 kB
- Earlier captures (`s21-e2b-memory-7000-vs-4000.md`) were TOTAL PSS 3,301,187 kB (7000) and 3,178,479 kB (4000), Graphics 2,145,662 kB and 1,972,730 kB.

## Control runs (same procedure, 3072 / 7000 / 4000)

Procedure, identical for all four runs (the 3072 run is the clean rerun, see notes): `force-stop`, `logcat -c`, launch `MainActivity`, new chat, wait for `Engine ready`. Full mode: probe (turn 1), lithium (2), umbrella (3), water cycle (4), geothermal (5), probe (6), bread (7), printing press (8), bicycle (9). At 3072 the probes are withheld (7 ordinary turns, lithium included). Then `dumpsys meminfo` (pre-camera), `STILL_IMAGE_CAMERA` for 15 s, `MainActivity` for 40 s. `MemAvailable`/`MemFree` sampled every 5 s from launch. Stayon `true` during each run.

| Run | maxTokens | Turns complete / failed | Debug pid (start → after camera) | lmkd kill | oom_score_adj | min MemAvailable kB | min MemFree kB | Graphics kB | Native Heap PSS kB | TOTAL PSS kB | swap PSS kB | Proactive resets | Max tokens_in_use | load_skill calls / aborts |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 3072 | 3072 | 6 / 1 (lithium prefill failed) | 5037 → 5037 | no | - | 189,456 | 48,788 | 1,930,802 | 531,607 | 3,311,559 | 488,820 | 6 | 3,284 | 0 / 0 (probes withheld) |
| 7000 A | 7000 | 9 / 0 | 7881 → 9873 | **yes** | 100 | 1,291,536 | 66,148 | 2,144,798 | 493,345 | 3,382,674 | 504,373 | 0 | 3,175 | 2 / 0 |
| 7000 B | 7000 | 9 / 0 | 11321 → 11321 | no | - | 177,096 | 48,292 | 2,145,834 | 475,755 | 3,670,582 | 666,667 | 0 | 2,942 | 2 / 0 |
| 4000 | 4000 | 9 / 0 | 15487 → 15487 | no | - | 143,620 | 69,696 | 1,980,102 | 512,134 | 3,312,574 | 467,863 | 3 | 3,785 | 0 / 0 (see note) |

Original 7000 soak (pid 23189, different start but same camera step): killed, oom_score_adj 100, min MemAvailable 83,908 at the kill, min MemFree 35,680 after the restart. Its pre-camera `dumpsys` was not captured.

Notes:
- 3072: lithium fallthrough failed at prefill, `Input token ids are too long ... 3401 >= 3072`, with `current_prompt_excluding_rag_tokens=1638`. This is the original bug, reproduced on the ordinary chat path. The other six turns completed. The 3072 workload therefore differs from 7000 by one failed generation.
- 3072: the proactive reset (threshold 2304) fired on 6 generations, `tokens_in_use` 2987 to 3284.
- 7000 A: lmkd `Reclaim 'ai.jandal.assistant.debug' (7881)` at 02:52:00.901, `oom_score_adj 100`, `state 2`, freed 2,443,524 kB RSS and 816,616 kB swap, reason `low watermark is breached and swap is low (1948724kB < 1048572kB)`. The camera was launched at 02:51:57 and the kill came about 3 s later. The app restarted as pid 9873.
- 7000 B: survived with the same pid. Its turn 9 took 175 s (`total=175447ms`), against 65 to 82 s for the same turn in the other runs.
- 4000: survived. Both probes called `run_intent` (`system_check_stopwatch_status`), not `load_skill`. Attempt-1 `max_tool_result_tokens=0`, so the load_skill abort path was not reached. Proactive reset fired on 3 generations.
- `am_pss` field 7 is 5.2e9 to 5.6e9 in all four runs, including 3072, and it does not track `dumpsys` TOTAL PSS (3.3 to 3.7 GB). Units are not verified, so the peak is not used in the comparison. Raw lines are in `/tmp` only.
- Invalid first 3072 attempt: the runner counted its own adb log echo as generation results, so turns overlapped. Discarded. The table uses the clean rerun.
- Sample size: 7000 killed in 2 of 3 runs (original soak, run A); run B survived. 3072 and 4000 are one run each, both survived. [INFERENCE] The kill is intermittent at 7000 on this S21. The data does not show 4000 is safer, since 4000 had minimum MemFree of the same order (70 MB) and no kill in one run. A rate needs more runs per window.

Restore: DB row `gemma_4_e2b` verified at 7000 on the host copy of the device DB; engine init at 03:15:07 `maxTokens: 7000`. Stayon set to `false`. Device lock released.

## Decision needed (owner)

The soak and control do not settle whether 7000 is safe on the S21 under this camera step. The default is NOT changed in this PR. Options:
1. Keep 7000 and document the intermittent LMKD kill risk on S21 MID_RANGE, backed by these runs.
2. Run more repeats per window (for example 3 each at 3072, 4000 and 7000) before deciding.
3. Choose a lower MID_RANGE window (4000 cannot serve the load_skill budget).

No value change was made in this PR.
