# Issue #1756 — periodic GPU restart OFF/ON comparison

## Build and devices

- PR: [#1767](https://github.com/NickMonrad/kernel-ai-assistant/pull/1767).
- Device build commit: `ff64a6bfedbb7e329ab563c0ae0f7cb347bed0c2`.
- LiteRT-LM: `0.19.0`.
- Debug APK SHA-256 on both devices: `b8ded39a7022dd906e61fae9d7876f3b193d5f35b55f817aca1adad469411721` (installed with `adb install -r`; hashes matched local APK and each installed `base.apk`).
- S21: `R5CR605B71K`, Samsung `SM-G991B`, E2B/GPU.
- S23U: `RFCW11265XH`, Samsung `SM-S918B`, E4B/GPU.

## Method

- Four matched runs, 100 requests each, same APK and same request order: S21 OFF/ON and S23U OFF/ON. Each run used 75 Actions requests and 25 Chat requests; a new Chat conversation was started every fourth request.
- Final corpus: 18 repeatable, local, read-only QIR cases covering time/date, battery, system information, and list reads. No alarms, timers, reminders, calendar writes, list edits, messages, calls, emails, or system toggles. Weather was excluded from the final matched corpus to avoid network/permission-dependent variance; a preliminary S23U weather attempt showed the optional location prompt, and “Not now” was selected (no location permission granted).
- Before each run, a safe general-knowledge prompt warmed the local model while the restart flag was OFF; warmups are not included in the 100 measured requests. OFF = `Settings.Global pr1756_gpu_restart` unset. ON = value `1`. Release-build gating was not changed.
- `Pass` means the observed routed intent equals the expected intent. The exact result marker is also recorded separately; this distinguishes a route match from a tool-level failure. Latency includes UI navigation/new-chat reset through the observed route/result marker.
- The single repeated route miss in all four runs was `what do I need to get from the shops` → expected `get_list_items`, actual `llm_fallthrough`, at requests 14, 32, 50, 68, and 86. It is the same fixed false negative throughout both halves and both devices, not a time-dependent drift/NO_MATCH collapse. Five `failure_no_llm` markers for list reads are also present in every run; the observed intent was still `get_list_items`.

## Results

| Device/run | Intent pass rate | First 50 | Second 50 | Drift OFF? | CPU fallback | Restart markers | Median / p95 latency |
|---|---:|---:|---:|---|---|---|---:|
| S21 OFF | 95/100 (95%) | 47/50 | 48/50 | No — same fixed misses; no temporal collapse | 0 | 0 | 16.7s / 34.2s |
| S21 ON | 95/100 (95%) | 47/50 | 48/50 | No — same fixed misses; no temporal collapse | 1; CPU from request 16 through 100 (85/100 rows) | 1/1, 16 | 16.7s / 48.4s |
| S23U OFF | 95/100 (95%) | 47/50 | 48/50 | No — same fixed misses; no temporal collapse | 0 | 0 | 14.4s / 26.6s |
| S23U ON | 95/100 (95%) | 47/50 | 48/50 | No — same fixed misses; no temporal collapse | 0 | 5/5, 16,36,52,72,88 | 14.4s / 44.1s |

- No OFF drift was observed on either device. First-half vs second-half intent match rates were unchanged (47/50 → 48/50 in each run); the five list-query false negatives recur at fixed indices in all runs.
- S21 ON: one restart completed at request 16, then the backend changed GPU → CPU; CPU was observed for requests 16–100 (85 rows). No later GPU restart occurred after the backend switch. S21 OFF stayed GPU for all 100 rows.
- S23U ON: five restarts completed at requests 16, 36, 52, 72, and 88; GPU remained active for all 100 rows. S23U OFF had no restart markers and stayed GPU.
- `restart_started` and `restart_completed` counts matched for every observed restart.
- Run windows (UTC): S21 OFF `2026-11-09T13:36:37`–`14:09:27`; S23U OFF `14:11:45`–`14:40:48`; S21 ON `14:42:27`–`15:19:32`; S23U ON `15:21:24`–`15:52:18`.

## Device cleanup

- Restored `stay_on_while_plugged_in` to S21 `0` and S23U `15`; removed the debug comparison setting on both devices (now unset). Removed the harness token and temporary UI dump.
- S23U `IMPORTANT_DATE_REMINDER` remained present: 21 matching `dumpsys alarm` lines before and after. Shopping-list data was read only and not edited; network settings were not changed.
- Test Chat conversations were created as part of the requested Chat-surface run and left in place; no app data was cleared or bulk-deleted.

## Per-request evidence

`route_pass` is intent equality; `result_marker` retains the observed completion state. No assistant text or list contents are included.

| Run | # | UTC completed | Surface | Input | Expected | Observed | Route pass | Result marker | Backend | Restart start/complete | Latency | Attempts |
|---|---:|---|---|---|---|---|---|---|---|---:|---:|---:|
| S21 OFF | 1 | 2026-11-09T13:36:37+00:00 | actions | what time is it | get_time | get_time | YES | routed | GPU | — | 17.18s | 1 |
| S21 OFF | 2 | 2026-11-09T13:36:49+00:00 | actions | what's today's date | get_time | get_time | YES | routed | GPU | — | 12.27s | 1 |
| S21 OFF | 3 | 2026-11-09T13:37:02+00:00 | actions | what's my battery level | get_battery | get_battery | YES | routed | GPU | — | 12.37s | 1 |
| S21 OFF | 4 | 2026-11-09T13:37:36+00:00 | chat | how much battery do I have | get_battery | get_battery | YES | direct_reply | GPU | — | 34.41s | 1 |
| S21 OFF | 5 | 2026-11-09T13:37:53+00:00 | actions | battery | get_battery | get_battery | YES | routed | GPU | — | 16.92s | 1 |
| S21 OFF | 6 | 2026-11-09T13:38:05+00:00 | actions | am I running low on battery | get_battery | get_battery | YES | routed | GPU | — | 12.36s | 1 |
| S21 OFF | 7 | 2026-11-09T13:38:18+00:00 | actions | how much storage do I have left | get_system_info | get_system_info | YES | routed | GPU | — | 12.36s | 1 |
| S21 OFF | 8 | 2026-11-09T13:38:51+00:00 | chat | what's my RAM usage | get_system_info | get_system_info | YES | direct_reply | GPU | — | 33.38s | 1 |
| S21 OFF | 9 | 2026-11-09T13:39:08+00:00 | actions | how much space is left on my phone | get_system_info | get_system_info | YES | routed | GPU | — | 16.69s | 1 |
| S21 OFF | 10 | 2026-11-09T13:39:20+00:00 | actions | how many weeks until 31 October | get_date_diff | get_date_diff | YES | routed | GPU | — | 12.45s | 1 |
| S21 OFF | 11 | 2026-11-09T13:39:33+00:00 | actions | how many weeks until the 31 October | get_date_diff | get_date_diff | YES | routed | GPU | — | 12.39s | 1 |
| S21 OFF | 12 | 2026-11-09T13:40:06+00:00 | chat | show my todo list | get_list_items | get_list_items | YES | failure_no_llm | GPU | — | 33.21s | 1 |
| S21 OFF | 13 | 2026-11-09T13:40:23+00:00 | actions | what's on my shopping list | get_list_items | get_list_items | YES | routed | GPU | — | 16.78s | 1 |
| S21 OFF | 14 | 2026-11-09T13:40:57+00:00 | actions | what do I need to get from the shops | get_list_items | llm_fallthrough | NO | model_fallthrough_complete | GPU | — | 34.20s | 1 |
| S21 OFF | 15 | 2026-11-09T13:41:14+00:00 | actions | what's on my grocery list | get_list_items | get_list_items | YES | routed | GPU | — | 16.73s | 1 |
| S21 OFF | 16 | 2026-11-09T13:41:47+00:00 | chat | show me the shopping list | get_list_items | get_list_items | YES | direct_reply | GPU | — | 33.32s | 1 |
| S21 OFF | 17 | 2026-11-09T13:42:04+00:00 | actions | read out my holiday list | get_list_items | get_list_items | YES | routed | GPU | — | 16.71s | 1 |
| S21 OFF | 18 | 2026-11-09T13:42:16+00:00 | actions | read me my grocery list | get_list_items | get_list_items | YES | routed | GPU | — | 12.33s | 1 |
| S21 OFF | 19 | 2026-11-09T13:42:28+00:00 | actions | what time is it | get_time | get_time | YES | routed | GPU | — | 12.43s | 1 |
| S21 OFF | 20 | 2026-11-09T13:43:02+00:00 | chat | what's today's date | get_time | get_time | YES | direct_reply | GPU | — | 33.20s | 1 |
| S21 OFF | 21 | 2026-11-09T13:43:18+00:00 | actions | what's my battery level | get_battery | get_battery | YES | routed | GPU | — | 16.81s | 1 |
| S21 OFF | 22 | 2026-11-09T13:43:31+00:00 | actions | how much battery do I have | get_battery | get_battery | YES | routed | GPU | — | 12.50s | 1 |
| S21 OFF | 23 | 2026-11-09T13:43:43+00:00 | actions | battery | get_battery | get_battery | YES | routed | GPU | — | 12.46s | 1 |
| S21 OFF | 24 | 2026-11-09T13:44:17+00:00 | chat | am I running low on battery | get_battery | get_battery | YES | direct_reply | GPU | — | 33.77s | 1 |
| S21 OFF | 25 | 2026-11-09T13:44:34+00:00 | actions | how much storage do I have left | get_system_info | get_system_info | YES | routed | GPU | — | 16.77s | 1 |
| S21 OFF | 26 | 2026-11-09T13:44:46+00:00 | actions | what's my RAM usage | get_system_info | get_system_info | YES | routed | GPU | — | 12.35s | 1 |
| S21 OFF | 27 | 2026-11-09T13:44:59+00:00 | actions | how much space is left on my phone | get_system_info | get_system_info | YES | routed | GPU | — | 12.26s | 1 |
| S21 OFF | 28 | 2026-11-09T13:45:32+00:00 | chat | how many weeks until 31 October | get_date_diff | get_date_diff | YES | direct_reply | GPU | — | 33.79s | 1 |
| S21 OFF | 29 | 2026-11-09T13:45:49+00:00 | actions | how many weeks until the 31 October | get_date_diff | get_date_diff | YES | routed | GPU | — | 16.77s | 1 |
| S21 OFF | 30 | 2026-11-09T13:46:02+00:00 | actions | show my todo list | get_list_items | get_list_items | YES | routed | GPU | — | 12.51s | 1 |
| S21 OFF | 31 | 2026-11-09T13:46:14+00:00 | actions | what's on my shopping list | get_list_items | get_list_items | YES | routed | GPU | — | 12.54s | 1 |
| S21 OFF | 32 | 2026-11-09T13:46:57+00:00 | chat | what do I need to get from the shops | get_list_items | llm_fallthrough | NO | model_fallthrough_complete | GPU | — | 43.35s | 1 |
| S21 OFF | 33 | 2026-11-09T13:47:14+00:00 | actions | what's on my grocery list | get_list_items | get_list_items | YES | routed | GPU | — | 16.87s | 1 |
| S21 OFF | 34 | 2026-11-09T13:47:27+00:00 | actions | show me the shopping list | get_list_items | get_list_items | YES | routed | GPU | — | 12.54s | 1 |
| S21 OFF | 35 | 2026-11-09T13:47:39+00:00 | actions | read out my holiday list | get_list_items | get_list_items | YES | routed | GPU | — | 12.41s | 1 |
| S21 OFF | 36 | 2026-11-09T13:48:13+00:00 | chat | read me my grocery list | get_list_items | get_list_items | YES | failure_no_llm | GPU | — | 33.27s | 1 |
| S21 OFF | 37 | 2026-11-09T13:48:29+00:00 | actions | what time is it | get_time | get_time | YES | routed | GPU | — | 16.71s | 1 |
| S21 OFF | 38 | 2026-11-09T13:48:42+00:00 | actions | what's today's date | get_time | get_time | YES | routed | GPU | — | 12.38s | 1 |
| S21 OFF | 39 | 2026-11-09T13:48:54+00:00 | actions | what's my battery level | get_battery | get_battery | YES | routed | GPU | — | 12.39s | 1 |
| S21 OFF | 40 | 2026-11-09T13:49:28+00:00 | chat | how much battery do I have | get_battery | get_battery | YES | direct_reply | GPU | — | 34.11s | 1 |
| S21 OFF | 41 | 2026-11-09T13:49:45+00:00 | actions | battery | get_battery | get_battery | YES | routed | GPU | — | 16.69s | 1 |
| S21 OFF | 42 | 2026-11-09T13:49:57+00:00 | actions | am I running low on battery | get_battery | get_battery | YES | routed | GPU | — | 12.45s | 1 |
| S21 OFF | 43 | 2026-11-09T13:50:10+00:00 | actions | how much storage do I have left | get_system_info | get_system_info | YES | routed | GPU | — | 12.42s | 1 |
| S21 OFF | 44 | 2026-11-09T13:50:43+00:00 | chat | what's my RAM usage | get_system_info | get_system_info | YES | direct_reply | GPU | — | 33.26s | 1 |
| S21 OFF | 45 | 2026-11-09T13:51:00+00:00 | actions | how much space is left on my phone | get_system_info | get_system_info | YES | routed | GPU | — | 16.79s | 1 |
| S21 OFF | 46 | 2026-11-09T13:51:12+00:00 | actions | how many weeks until 31 October | get_date_diff | get_date_diff | YES | routed | GPU | — | 12.44s | 1 |
| S21 OFF | 47 | 2026-11-09T13:51:25+00:00 | actions | how many weeks until the 31 October | get_date_diff | get_date_diff | YES | routed | GPU | — | 12.36s | 1 |
| S21 OFF | 48 | 2026-11-09T13:51:58+00:00 | chat | show my todo list | get_list_items | get_list_items | YES | failure_no_llm | GPU | — | 33.29s | 1 |
| S21 OFF | 49 | 2026-11-09T13:52:14+00:00 | actions | what's on my shopping list | get_list_items | get_list_items | YES | routed | GPU | — | 16.59s | 1 |
| S21 OFF | 50 | 2026-11-09T13:52:52+00:00 | actions | what do I need to get from the shops | get_list_items | llm_fallthrough | NO | model_fallthrough_complete | GPU | — | 37.59s | 1 |
| S21 OFF | 51 | 2026-11-09T13:53:09+00:00 | actions | what's on my grocery list | get_list_items | get_list_items | YES | routed | GPU | — | 17.01s | 1 |
| S21 OFF | 52 | 2026-11-09T13:53:43+00:00 | chat | show me the shopping list | get_list_items | get_list_items | YES | direct_reply | GPU | — | 34.12s | 1 |
| S21 OFF | 53 | 2026-11-09T13:54:00+00:00 | actions | read out my holiday list | get_list_items | get_list_items | YES | routed | GPU | — | 16.90s | 1 |
| S21 OFF | 54 | 2026-11-09T13:54:13+00:00 | actions | read me my grocery list | get_list_items | get_list_items | YES | routed | GPU | — | 12.39s | 1 |
| S21 OFF | 55 | 2026-11-09T13:54:25+00:00 | actions | what time is it | get_time | get_time | YES | routed | GPU | — | 12.39s | 1 |
| S21 OFF | 56 | 2026-11-09T13:54:59+00:00 | chat | what's today's date | get_time | get_time | YES | direct_reply | GPU | — | 34.09s | 1 |
| S21 OFF | 57 | 2026-11-09T13:55:16+00:00 | actions | what's my battery level | get_battery | get_battery | YES | routed | GPU | — | 16.89s | 1 |
| S21 OFF | 58 | 2026-11-09T13:55:28+00:00 | actions | how much battery do I have | get_battery | get_battery | YES | routed | GPU | — | 12.59s | 1 |
| S21 OFF | 59 | 2026-11-09T13:55:41+00:00 | actions | battery | get_battery | get_battery | YES | routed | GPU | — | 12.43s | 1 |
| S21 OFF | 60 | 2026-11-09T13:56:15+00:00 | chat | am I running low on battery | get_battery | get_battery | YES | direct_reply | GPU | — | 34.04s | 1 |
| S21 OFF | 61 | 2026-11-09T13:56:32+00:00 | actions | how much storage do I have left | get_system_info | get_system_info | YES | routed | GPU | — | 16.80s | 1 |
| S21 OFF | 62 | 2026-11-09T13:56:44+00:00 | actions | what's my RAM usage | get_system_info | get_system_info | YES | routed | GPU | — | 12.38s | 1 |
| S21 OFF | 63 | 2026-11-09T13:56:57+00:00 | actions | how much space is left on my phone | get_system_info | get_system_info | YES | routed | GPU | — | 12.43s | 1 |
| S21 OFF | 64 | 2026-11-09T13:57:31+00:00 | chat | how many weeks until 31 October | get_date_diff | get_date_diff | YES | direct_reply | GPU | — | 34.01s | 1 |
| S21 OFF | 65 | 2026-11-09T13:57:47+00:00 | actions | how many weeks until the 31 October | get_date_diff | get_date_diff | YES | routed | GPU | — | 16.85s | 1 |
| S21 OFF | 66 | 2026-11-09T13:58:00+00:00 | actions | show my todo list | get_list_items | get_list_items | YES | routed | GPU | — | 12.35s | 1 |
| S21 OFF | 67 | 2026-11-09T13:58:12+00:00 | actions | what's on my shopping list | get_list_items | get_list_items | YES | routed | GPU | — | 12.51s | 1 |
| S21 OFF | 68 | 2026-11-09T13:58:56+00:00 | chat | what do I need to get from the shops | get_list_items | llm_fallthrough | NO | model_fallthrough_complete | GPU | — | 44.23s | 1 |
| S21 OFF | 69 | 2026-11-09T13:59:13+00:00 | actions | what's on my grocery list | get_list_items | get_list_items | YES | routed | GPU | — | 16.87s | 1 |
| S21 OFF | 70 | 2026-11-09T13:59:26+00:00 | actions | show me the shopping list | get_list_items | get_list_items | YES | routed | GPU | — | 12.34s | 1 |
| S21 OFF | 71 | 2026-11-09T13:59:38+00:00 | actions | read out my holiday list | get_list_items | get_list_items | YES | routed | GPU | — | 12.49s | 1 |
| S21 OFF | 72 | 2026-11-09T14:00:12+00:00 | chat | read me my grocery list | get_list_items | get_list_items | YES | failure_no_llm | GPU | — | 33.45s | 1 |
| S21 OFF | 73 | 2026-11-09T14:00:28+00:00 | actions | what time is it | get_time | get_time | YES | routed | GPU | — | 16.76s | 1 |
| S21 OFF | 74 | 2026-11-09T14:00:41+00:00 | actions | what's today's date | get_time | get_time | YES | routed | GPU | — | 12.42s | 1 |
| S21 OFF | 75 | 2026-11-09T14:00:53+00:00 | actions | what's my battery level | get_battery | get_battery | YES | routed | GPU | — | 12.37s | 1 |
| S21 OFF | 76 | 2026-11-09T14:01:27+00:00 | chat | how much battery do I have | get_battery | get_battery | YES | direct_reply | GPU | — | 33.95s | 1 |
| S21 OFF | 77 | 2026-11-09T14:01:44+00:00 | actions | battery | get_battery | get_battery | YES | routed | GPU | — | 16.73s | 1 |
| S21 OFF | 78 | 2026-11-09T14:01:56+00:00 | actions | am I running low on battery | get_battery | get_battery | YES | routed | GPU | — | 12.55s | 1 |
| S21 OFF | 79 | 2026-11-09T14:02:09+00:00 | actions | how much storage do I have left | get_system_info | get_system_info | YES | routed | GPU | — | 12.45s | 1 |
| S21 OFF | 80 | 2026-11-09T14:02:42+00:00 | chat | what's my RAM usage | get_system_info | get_system_info | YES | direct_reply | GPU | — | 33.19s | 1 |
| S21 OFF | 81 | 2026-11-09T14:02:59+00:00 | actions | how much space is left on my phone | get_system_info | get_system_info | YES | routed | GPU | — | 16.75s | 1 |
| S21 OFF | 82 | 2026-11-09T14:03:11+00:00 | actions | how many weeks until 31 October | get_date_diff | get_date_diff | YES | routed | GPU | — | 12.53s | 1 |
| S21 OFF | 83 | 2026-11-09T14:03:24+00:00 | actions | how many weeks until the 31 October | get_date_diff | get_date_diff | YES | routed | GPU | — | 12.52s | 1 |
| S21 OFF | 84 | 2026-11-09T14:03:57+00:00 | chat | show my todo list | get_list_items | get_list_items | YES | failure_no_llm | GPU | — | 33.56s | 1 |
| S21 OFF | 85 | 2026-11-09T14:04:14+00:00 | actions | what's on my shopping list | get_list_items | get_list_items | YES | routed | GPU | — | 16.77s | 1 |
| S21 OFF | 86 | 2026-11-09T14:04:48+00:00 | actions | what do I need to get from the shops | get_list_items | llm_fallthrough | NO | model_fallthrough_complete | GPU | — | 33.53s | 1 |
| S21 OFF | 87 | 2026-11-09T14:05:05+00:00 | actions | what's on my grocery list | get_list_items | get_list_items | YES | routed | GPU | — | 17.11s | 1 |
| S21 OFF | 88 | 2026-11-09T14:05:39+00:00 | chat | show me the shopping list | get_list_items | get_list_items | YES | direct_reply | GPU | — | 33.98s | 1 |
| S21 OFF | 89 | 2026-11-09T14:05:56+00:00 | actions | read out my holiday list | get_list_items | get_list_items | YES | routed | GPU | — | 16.70s | 1 |
| S21 OFF | 90 | 2026-11-09T14:06:08+00:00 | actions | read me my grocery list | get_list_items | get_list_items | YES | routed | GPU | — | 12.36s | 1 |
| S21 OFF | 91 | 2026-11-09T14:06:20+00:00 | actions | what time is it | get_time | get_time | YES | routed | GPU | — | 12.48s | 1 |
| S21 OFF | 92 | 2026-11-09T14:06:54+00:00 | chat | what's today's date | get_time | get_time | YES | direct_reply | GPU | — | 34.04s | 1 |
| S21 OFF | 93 | 2026-11-09T14:07:11+00:00 | actions | what's my battery level | get_battery | get_battery | YES | routed | GPU | — | 16.83s | 1 |
| S21 OFF | 94 | 2026-11-09T14:07:24+00:00 | actions | how much battery do I have | get_battery | get_battery | YES | routed | GPU | — | 12.43s | 1 |
| S21 OFF | 95 | 2026-11-09T14:07:36+00:00 | actions | battery | get_battery | get_battery | YES | routed | GPU | — | 12.41s | 1 |
| S21 OFF | 96 | 2026-11-09T14:08:11+00:00 | chat | am I running low on battery | get_battery | get_battery | YES | direct_reply | GPU | — | 34.91s | 1 |
| S21 OFF | 97 | 2026-11-09T14:08:28+00:00 | actions | how much storage do I have left | get_system_info | get_system_info | YES | routed | GPU | — | 16.72s | 1 |
| S21 OFF | 98 | 2026-11-09T14:08:40+00:00 | actions | what's my RAM usage | get_system_info | get_system_info | YES | routed | GPU | — | 12.40s | 1 |
| S21 OFF | 99 | 2026-11-09T14:08:53+00:00 | actions | how much space is left on my phone | get_system_info | get_system_info | YES | routed | GPU | — | 12.53s | 1 |
| S21 OFF | 100 | 2026-11-09T14:09:27+00:00 | chat | how many weeks until 31 October | get_date_diff | get_date_diff | YES | direct_reply | GPU | — | 33.89s | 1 |
| S21 ON | 1 | 2026-11-09T14:42:27+00:00 | actions | what time is it | get_time | get_time | YES | routed | GPU | — | 16.78s | 1 |
| S21 ON | 2 | 2026-11-09T14:42:40+00:00 | actions | what's today's date | get_time | get_time | YES | routed | GPU | — | 12.33s | 1 |
| S21 ON | 3 | 2026-11-09T14:42:52+00:00 | actions | what's my battery level | get_battery | get_battery | YES | routed | GPU | — | 12.37s | 1 |
| S21 ON | 4 | 2026-11-09T14:43:26+00:00 | chat | how much battery do I have | get_battery | get_battery | YES | direct_reply | GPU | — | 33.75s | 1 |
| S21 ON | 5 | 2026-11-09T14:43:43+00:00 | actions | battery | get_battery | get_battery | YES | routed | GPU | — | 16.68s | 1 |
| S21 ON | 6 | 2026-11-09T14:43:55+00:00 | actions | am I running low on battery | get_battery | get_battery | YES | routed | GPU | — | 12.28s | 1 |
| S21 ON | 7 | 2026-11-09T14:44:07+00:00 | actions | how much storage do I have left | get_system_info | get_system_info | YES | routed | GPU | — | 12.47s | 1 |
| S21 ON | 8 | 2026-11-09T14:44:40+00:00 | chat | what's my RAM usage | get_system_info | get_system_info | YES | direct_reply | GPU | — | 32.94s | 1 |
| S21 ON | 9 | 2026-11-09T14:44:57+00:00 | actions | how much space is left on my phone | get_system_info | get_system_info | YES | routed | GPU | — | 16.65s | 1 |
| S21 ON | 10 | 2026-11-09T14:45:09+00:00 | actions | how many weeks until 31 October | get_date_diff | get_date_diff | YES | routed | GPU | — | 12.29s | 1 |
| S21 ON | 11 | 2026-11-09T14:45:22+00:00 | actions | how many weeks until the 31 October | get_date_diff | get_date_diff | YES | routed | GPU | — | 12.38s | 1 |
| S21 ON | 12 | 2026-11-09T14:45:55+00:00 | chat | show my todo list | get_list_items | get_list_items | YES | failure_no_llm | GPU | — | 33.23s | 1 |
| S21 ON | 13 | 2026-11-09T14:46:11+00:00 | actions | what's on my shopping list | get_list_items | get_list_items | YES | routed | GPU | — | 16.71s | 1 |
| S21 ON | 14 | 2026-11-09T14:46:42+00:00 | actions | what do I need to get from the shops | get_list_items | llm_fallthrough | NO | model_fallthrough_complete | GPU | — | 30.86s | 1 |
| S21 ON | 15 | 2026-11-09T14:46:59+00:00 | actions | what's on my grocery list | get_list_items | get_list_items | YES | routed | GPU | — | 16.78s | 1 |
| S21 ON | 16 | 2026-11-09T14:47:39+00:00 | chat | show me the shopping list | get_list_items | get_list_items | YES | direct_reply | CPU | 1/1 | 40.01s | 1 |
| S21 ON | 17 | 2026-11-09T14:47:56+00:00 | actions | read out my holiday list | get_list_items | get_list_items | YES | routed | CPU | — | 16.66s | 1 |
| S21 ON | 18 | 2026-11-09T14:48:08+00:00 | actions | read me my grocery list | get_list_items | get_list_items | YES | routed | CPU | — | 12.40s | 1 |
| S21 ON | 19 | 2026-11-09T14:48:21+00:00 | actions | what time is it | get_time | get_time | YES | routed | CPU | — | 12.40s | 1 |
| S21 ON | 20 | 2026-11-09T14:48:54+00:00 | chat | what's today's date | get_time | get_time | YES | direct_reply | CPU | — | 33.81s | 1 |
| S21 ON | 21 | 2026-11-09T14:49:11+00:00 | actions | what's my battery level | get_battery | get_battery | YES | routed | CPU | — | 16.65s | 1 |
| S21 ON | 22 | 2026-11-09T14:49:24+00:00 | actions | how much battery do I have | get_battery | get_battery | YES | routed | CPU | — | 12.52s | 1 |
| S21 ON | 23 | 2026-11-09T14:49:36+00:00 | actions | battery | get_battery | get_battery | YES | routed | CPU | — | 12.42s | 1 |
| S21 ON | 24 | 2026-11-09T14:50:10+00:00 | chat | am I running low on battery | get_battery | get_battery | YES | direct_reply | CPU | — | 33.73s | 1 |
| S21 ON | 25 | 2026-11-09T14:50:26+00:00 | actions | how much storage do I have left | get_system_info | get_system_info | YES | routed | CPU | — | 16.67s | 1 |
| S21 ON | 26 | 2026-11-09T14:50:39+00:00 | actions | what's my RAM usage | get_system_info | get_system_info | YES | routed | CPU | — | 12.38s | 1 |
| S21 ON | 27 | 2026-11-09T14:50:51+00:00 | actions | how much space is left on my phone | get_system_info | get_system_info | YES | routed | CPU | — | 12.41s | 1 |
| S21 ON | 28 | 2026-11-09T14:51:25+00:00 | chat | how many weeks until 31 October | get_date_diff | get_date_diff | YES | direct_reply | CPU | — | 33.97s | 1 |
| S21 ON | 29 | 2026-11-09T14:51:42+00:00 | actions | how many weeks until the 31 October | get_date_diff | get_date_diff | YES | routed | CPU | — | 16.64s | 1 |
| S21 ON | 30 | 2026-11-09T14:51:54+00:00 | actions | show my todo list | get_list_items | get_list_items | YES | routed | CPU | — | 12.36s | 1 |
| S21 ON | 31 | 2026-11-09T14:52:06+00:00 | actions | what's on my shopping list | get_list_items | get_list_items | YES | routed | CPU | — | 12.29s | 1 |
| S21 ON | 32 | 2026-11-09T14:52:59+00:00 | chat | what do I need to get from the shops | get_list_items | llm_fallthrough | NO | model_fallthrough_complete | CPU | — | 52.56s | 1 |
| S21 ON | 33 | 2026-11-09T14:53:16+00:00 | actions | what's on my grocery list | get_list_items | get_list_items | YES | routed | CPU | — | 16.71s | 1 |
| S21 ON | 34 | 2026-11-09T14:53:28+00:00 | actions | show me the shopping list | get_list_items | get_list_items | YES | routed | CPU | — | 12.48s | 1 |
| S21 ON | 35 | 2026-11-09T14:53:41+00:00 | actions | read out my holiday list | get_list_items | get_list_items | YES | routed | CPU | — | 12.47s | 1 |
| S21 ON | 36 | 2026-11-09T14:54:14+00:00 | chat | read me my grocery list | get_list_items | get_list_items | YES | failure_no_llm | CPU | — | 33.33s | 1 |
| S21 ON | 37 | 2026-11-09T14:54:31+00:00 | actions | what time is it | get_time | get_time | YES | routed | CPU | — | 16.82s | 1 |
| S21 ON | 38 | 2026-11-09T14:54:43+00:00 | actions | what's today's date | get_time | get_time | YES | routed | CPU | — | 12.52s | 1 |
| S21 ON | 39 | 2026-11-09T14:54:56+00:00 | actions | what's my battery level | get_battery | get_battery | YES | routed | CPU | — | 12.47s | 1 |
| S21 ON | 40 | 2026-11-09T14:55:30+00:00 | chat | how much battery do I have | get_battery | get_battery | YES | direct_reply | CPU | — | 33.96s | 1 |
| S21 ON | 41 | 2026-11-09T14:55:46+00:00 | actions | battery | get_battery | get_battery | YES | routed | CPU | — | 16.67s | 1 |
| S21 ON | 42 | 2026-11-09T14:55:59+00:00 | actions | am I running low on battery | get_battery | get_battery | YES | routed | CPU | — | 12.38s | 1 |
| S21 ON | 43 | 2026-11-09T14:56:11+00:00 | actions | how much storage do I have left | get_system_info | get_system_info | YES | routed | CPU | — | 12.51s | 1 |
| S21 ON | 44 | 2026-11-09T14:56:45+00:00 | chat | what's my RAM usage | get_system_info | get_system_info | YES | direct_reply | CPU | — | 33.20s | 1 |
| S21 ON | 45 | 2026-11-09T14:57:01+00:00 | actions | how much space is left on my phone | get_system_info | get_system_info | YES | routed | CPU | — | 16.60s | 1 |
| S21 ON | 46 | 2026-11-09T14:57:14+00:00 | actions | how many weeks until 31 October | get_date_diff | get_date_diff | YES | routed | CPU | — | 12.41s | 1 |
| S21 ON | 47 | 2026-11-09T14:57:26+00:00 | actions | how many weeks until the 31 October | get_date_diff | get_date_diff | YES | routed | CPU | — | 12.29s | 1 |
| S21 ON | 48 | 2026-11-09T14:57:59+00:00 | chat | show my todo list | get_list_items | get_list_items | YES | failure_no_llm | CPU | — | 33.16s | 1 |
| S21 ON | 49 | 2026-11-09T14:58:16+00:00 | actions | what's on my shopping list | get_list_items | get_list_items | YES | routed | CPU | — | 16.74s | 1 |
| S21 ON | 50 | 2026-11-09T14:58:56+00:00 | actions | what do I need to get from the shops | get_list_items | llm_fallthrough | NO | model_fallthrough | CPU | — | 40.47s | 1 |
| S21 ON | 51 | 2026-11-09T14:59:13+00:00 | actions | what's on my grocery list | get_list_items | get_list_items | YES | routed | CPU | — | 16.88s | 1 |
| S21 ON | 52 | 2026-11-09T14:59:48+00:00 | chat | show me the shopping list | get_list_items | get_list_items | YES | direct_reply | CPU | — | 34.65s | 1 |
| S21 ON | 53 | 2026-11-09T15:00:05+00:00 | actions | read out my holiday list | get_list_items | get_list_items | YES | routed | CPU | — | 16.88s | 1 |
| S21 ON | 54 | 2026-11-09T15:00:18+00:00 | actions | read me my grocery list | get_list_items | get_list_items | YES | routed | CPU | — | 13.46s | 1 |
| S21 ON | 55 | 2026-11-09T15:00:32+00:00 | actions | what time is it | get_time | get_time | YES | routed | CPU | — | 13.82s | 1 |
| S21 ON | 56 | 2026-11-09T15:01:20+00:00 | chat | what's today's date | get_time | get_time | YES | direct_reply | CPU | — | 48.48s | 1 |
| S21 ON | 57 | 2026-11-09T15:01:39+00:00 | actions | what's my battery level | get_battery | get_battery | YES | routed | CPU | — | 18.40s | 1 |
| S21 ON | 58 | 2026-11-09T15:01:52+00:00 | actions | how much battery do I have | get_battery | get_battery | YES | routed | CPU | — | 13.58s | 1 |
| S21 ON | 59 | 2026-11-09T15:02:06+00:00 | actions | battery | get_battery | get_battery | YES | routed | CPU | — | 13.64s | 1 |
| S21 ON | 60 | 2026-11-09T15:02:55+00:00 | chat | am I running low on battery | get_battery | get_battery | YES | direct_reply | CPU | — | 49.51s | 1 |
| S21 ON | 61 | 2026-11-09T15:03:14+00:00 | actions | how much storage do I have left | get_system_info | get_system_info | YES | routed | CPU | — | 18.59s | 1 |
| S21 ON | 62 | 2026-11-09T15:03:28+00:00 | actions | what's my RAM usage | get_system_info | get_system_info | YES | routed | CPU | — | 13.82s | 1 |
| S21 ON | 63 | 2026-11-09T15:03:42+00:00 | actions | how much space is left on my phone | get_system_info | get_system_info | YES | routed | CPU | — | 13.68s | 1 |
| S21 ON | 64 | 2026-11-09T15:04:30+00:00 | chat | how many weeks until 31 October | get_date_diff | get_date_diff | YES | direct_reply | CPU | — | 48.42s | 1 |
| S21 ON | 65 | 2026-11-09T15:04:48+00:00 | actions | how many weeks until the 31 October | get_date_diff | get_date_diff | YES | routed | CPU | — | 18.38s | 1 |
| S21 ON | 66 | 2026-11-09T15:05:02+00:00 | actions | show my todo list | get_list_items | get_list_items | YES | routed | CPU | — | 13.50s | 1 |
| S21 ON | 67 | 2026-11-09T15:05:16+00:00 | actions | what's on my shopping list | get_list_items | get_list_items | YES | routed | CPU | — | 13.75s | 1 |
| S21 ON | 68 | 2026-11-09T15:06:33+00:00 | chat | what do I need to get from the shops | get_list_items | llm_fallthrough | NO | model_fallthrough_complete | CPU | — | 77.75s | 1 |
| S21 ON | 69 | 2026-11-09T15:06:52+00:00 | actions | what's on my grocery list | get_list_items | get_list_items | YES | routed | CPU | — | 18.27s | 1 |
| S21 ON | 70 | 2026-11-09T15:07:05+00:00 | actions | show me the shopping list | get_list_items | get_list_items | YES | routed | CPU | — | 13.59s | 1 |
| S21 ON | 71 | 2026-11-09T15:07:19+00:00 | actions | read out my holiday list | get_list_items | get_list_items | YES | routed | CPU | — | 13.48s | 1 |
| S21 ON | 72 | 2026-11-09T15:08:05+00:00 | chat | read me my grocery list | get_list_items | get_list_items | YES | failure_no_llm | CPU | — | 46.24s | 1 |
| S21 ON | 73 | 2026-11-09T15:08:23+00:00 | actions | what time is it | get_time | get_time | YES | routed | CPU | — | 18.32s | 1 |
| S21 ON | 74 | 2026-11-09T15:08:37+00:00 | actions | what's today's date | get_time | get_time | YES | routed | CPU | — | 13.67s | 1 |
| S21 ON | 75 | 2026-11-09T15:08:51+00:00 | actions | what's my battery level | get_battery | get_battery | YES | routed | CPU | — | 13.75s | 1 |
| S21 ON | 76 | 2026-11-09T15:09:40+00:00 | chat | how much battery do I have | get_battery | get_battery | YES | direct_reply | CPU | — | 49.48s | 1 |
| S21 ON | 77 | 2026-11-09T15:09:59+00:00 | actions | battery | get_battery | get_battery | YES | routed | CPU | — | 18.47s | 1 |
| S21 ON | 78 | 2026-11-09T15:10:12+00:00 | actions | am I running low on battery | get_battery | get_battery | YES | routed | CPU | — | 13.73s | 1 |
| S21 ON | 79 | 2026-11-09T15:10:26+00:00 | actions | how much storage do I have left | get_system_info | get_system_info | YES | routed | CPU | — | 13.83s | 1 |
| S21 ON | 80 | 2026-11-09T15:11:12+00:00 | chat | what's my RAM usage | get_system_info | get_system_info | YES | direct_reply | CPU | — | 46.25s | 1 |
| S21 ON | 81 | 2026-11-09T15:11:31+00:00 | actions | how much space is left on my phone | get_system_info | get_system_info | YES | routed | CPU | — | 18.41s | 1 |
| S21 ON | 82 | 2026-11-09T15:11:44+00:00 | actions | how many weeks until 31 October | get_date_diff | get_date_diff | YES | routed | CPU | — | 13.52s | 1 |
| S21 ON | 83 | 2026-11-09T15:11:58+00:00 | actions | how many weeks until the 31 October | get_date_diff | get_date_diff | YES | routed | CPU | — | 13.88s | 1 |
| S21 ON | 84 | 2026-11-09T15:12:44+00:00 | chat | show my todo list | get_list_items | get_list_items | YES | failure_no_llm | CPU | — | 46.21s | 1 |
| S21 ON | 85 | 2026-11-09T15:13:03+00:00 | actions | what's on my shopping list | get_list_items | get_list_items | YES | routed | CPU | — | 18.48s | 1 |
| S21 ON | 86 | 2026-11-09T15:13:45+00:00 | actions | what do I need to get from the shops | get_list_items | llm_fallthrough | NO | model_fallthrough | CPU | — | 41.55s | 1 |
| S21 ON | 87 | 2026-11-09T15:14:04+00:00 | actions | what's on my grocery list | get_list_items | get_list_items | YES | routed | CPU | — | 19.65s | 1 |
| S21 ON | 88 | 2026-11-09T15:14:52+00:00 | chat | show me the shopping list | get_list_items | get_list_items | YES | direct_reply | CPU | — | 47.66s | 1 |
| S21 ON | 89 | 2026-11-09T15:15:10+00:00 | actions | read out my holiday list | get_list_items | get_list_items | YES | routed | CPU | — | 18.51s | 1 |
| S21 ON | 90 | 2026-11-09T15:15:24+00:00 | actions | read me my grocery list | get_list_items | get_list_items | YES | routed | CPU | — | 13.69s | 1 |
| S21 ON | 91 | 2026-11-09T15:15:38+00:00 | actions | what time is it | get_time | get_time | YES | routed | CPU | — | 13.64s | 1 |
| S21 ON | 92 | 2026-11-09T15:16:25+00:00 | chat | what's today's date | get_time | get_time | YES | direct_reply | CPU | — | 47.43s | 1 |
| S21 ON | 93 | 2026-11-09T15:16:43+00:00 | actions | what's my battery level | get_battery | get_battery | YES | routed | CPU | — | 18.37s | 1 |
| S21 ON | 94 | 2026-11-09T15:16:57+00:00 | actions | how much battery do I have | get_battery | get_battery | YES | routed | CPU | — | 13.74s | 1 |
| S21 ON | 95 | 2026-11-09T15:17:11+00:00 | actions | battery | get_battery | get_battery | YES | routed | CPU | — | 13.79s | 1 |
| S21 ON | 96 | 2026-11-09T15:17:59+00:00 | chat | am I running low on battery | get_battery | get_battery | YES | direct_reply | CPU | — | 48.26s | 1 |
| S21 ON | 97 | 2026-11-09T15:18:18+00:00 | actions | how much storage do I have left | get_system_info | get_system_info | YES | routed | CPU | — | 18.34s | 1 |
| S21 ON | 98 | 2026-11-09T15:18:31+00:00 | actions | what's my RAM usage | get_system_info | get_system_info | YES | routed | CPU | — | 13.69s | 1 |
| S21 ON | 99 | 2026-11-09T15:18:45+00:00 | actions | how much space is left on my phone | get_system_info | get_system_info | YES | routed | CPU | — | 13.72s | 1 |
| S21 ON | 100 | 2026-11-09T15:19:32+00:00 | chat | how many weeks until 31 October | get_date_diff | get_date_diff | YES | direct_reply | CPU | — | 47.42s | 1 |
| S23U OFF | 1 | 2026-11-09T14:11:45+00:00 | actions | what time is it | get_time | get_time | YES | routed | GPU | — | 14.85s | 1 |
| S23U OFF | 2 | 2026-11-09T14:11:56+00:00 | actions | what's today's date | get_time | get_time | YES | routed | GPU | — | 10.29s | 1 |
| S23U OFF | 3 | 2026-11-09T14:12:06+00:00 | actions | what's my battery level | get_battery | get_battery | YES | routed | GPU | — | 10.44s | 1 |
| S23U OFF | 4 | 2026-11-09T14:12:32+00:00 | chat | how much battery do I have | get_battery | get_battery | YES | direct_reply | GPU | — | 26.01s | 1 |
| S23U OFF | 5 | 2026-11-09T14:12:47+00:00 | actions | battery | get_battery | get_battery | YES | routed | GPU | — | 14.55s | 1 |
| S23U OFF | 6 | 2026-11-09T14:13:00+00:00 | actions | am I running low on battery | get_battery | get_battery | YES | routed | GPU | — | 12.76s | 1 |
| S23U OFF | 7 | 2026-11-09T14:13:12+00:00 | actions | how much storage do I have left | get_system_info | get_system_info | YES | routed | GPU | — | 12.72s | 1 |
| S23U OFF | 8 | 2026-11-09T14:13:38+00:00 | chat | what's my RAM usage | get_system_info | get_system_info | YES | direct_reply | GPU | — | 26.26s | 1 |
| S23U OFF | 9 | 2026-11-09T14:13:55+00:00 | actions | how much space is left on my phone | get_system_info | get_system_info | YES | routed | GPU | — | 16.84s | 1 |
| S23U OFF | 10 | 2026-11-09T14:14:05+00:00 | actions | how many weeks until 31 October | get_date_diff | get_date_diff | YES | routed | GPU | — | 10.13s | 1 |
| S23U OFF | 11 | 2026-11-09T14:14:16+00:00 | actions | how many weeks until the 31 October | get_date_diff | get_date_diff | YES | routed | GPU | — | 10.11s | 1 |
| S23U OFF | 12 | 2026-11-09T14:14:42+00:00 | chat | show my todo list | get_list_items | get_list_items | YES | failure_no_llm | GPU | — | 26.31s | 1 |
| S23U OFF | 13 | 2026-11-09T14:14:56+00:00 | actions | what's on my shopping list | get_list_items | get_list_items | YES | routed | GPU | — | 14.60s | 1 |
| S23U OFF | 14 | 2026-11-09T14:15:34+00:00 | actions | what do I need to get from the shops | get_list_items | llm_fallthrough | NO | model_fallthrough_complete | GPU | — | 37.96s | 1 |
| S23U OFF | 15 | 2026-11-09T14:15:49+00:00 | actions | what's on my grocery list | get_list_items | get_list_items | YES | routed | GPU | — | 14.63s | 1 |
| S23U OFF | 16 | 2026-11-09T14:16:15+00:00 | chat | show me the shopping list | get_list_items | get_list_items | YES | direct_reply | GPU | — | 26.39s | 1 |
| S23U OFF | 17 | 2026-11-09T14:16:30+00:00 | actions | read out my holiday list | get_list_items | get_list_items | YES | routed | GPU | — | 14.57s | 1 |
| S23U OFF | 18 | 2026-11-09T14:16:40+00:00 | actions | read me my grocery list | get_list_items | get_list_items | YES | routed | GPU | — | 10.31s | 1 |
| S23U OFF | 19 | 2026-11-09T14:16:51+00:00 | actions | what time is it | get_time | get_time | YES | routed | GPU | — | 10.19s | 1 |
| S23U OFF | 20 | 2026-11-09T14:17:17+00:00 | chat | what's today's date | get_time | get_time | YES | direct_reply | GPU | — | 26.45s | 1 |
| S23U OFF | 21 | 2026-11-09T14:17:31+00:00 | actions | what's my battery level | get_battery | get_battery | YES | routed | GPU | — | 14.48s | 1 |
| S23U OFF | 22 | 2026-11-09T14:17:42+00:00 | actions | how much battery do I have | get_battery | get_battery | YES | routed | GPU | — | 10.30s | 1 |
| S23U OFF | 23 | 2026-11-09T14:17:52+00:00 | actions | battery | get_battery | get_battery | YES | routed | GPU | — | 10.31s | 1 |
| S23U OFF | 24 | 2026-11-09T14:18:19+00:00 | chat | am I running low on battery | get_battery | get_battery | YES | direct_reply | GPU | — | 26.46s | 1 |
| S23U OFF | 25 | 2026-11-09T14:18:35+00:00 | actions | how much storage do I have left | get_system_info | get_system_info | YES | routed | GPU | — | 16.89s | 1 |
| S23U OFF | 26 | 2026-11-09T14:18:48+00:00 | actions | what's my RAM usage | get_system_info | get_system_info | YES | routed | GPU | — | 12.66s | 1 |
| S23U OFF | 27 | 2026-11-09T14:19:01+00:00 | actions | how much space is left on my phone | get_system_info | get_system_info | YES | routed | GPU | — | 12.65s | 1 |
| S23U OFF | 28 | 2026-11-09T14:19:27+00:00 | chat | how many weeks until 31 October | get_date_diff | get_date_diff | YES | direct_reply | GPU | — | 26.27s | 1 |
| S23U OFF | 29 | 2026-11-09T14:19:41+00:00 | actions | how many weeks until the 31 October | get_date_diff | get_date_diff | YES | routed | GPU | — | 14.36s | 1 |
| S23U OFF | 30 | 2026-11-09T14:19:52+00:00 | actions | show my todo list | get_list_items | get_list_items | YES | routed | GPU | — | 10.26s | 1 |
| S23U OFF | 31 | 2026-11-09T14:20:02+00:00 | actions | what's on my shopping list | get_list_items | get_list_items | YES | routed | GPU | — | 10.32s | 1 |
| S23U OFF | 32 | 2026-11-09T14:21:07+00:00 | chat | what do I need to get from the shops | get_list_items | llm_fallthrough | NO | model_fallthrough_complete | GPU | — | 64.83s | 1 |
| S23U OFF | 33 | 2026-11-09T14:21:21+00:00 | actions | what's on my grocery list | get_list_items | get_list_items | YES | routed | GPU | — | 14.61s | 1 |
| S23U OFF | 34 | 2026-11-09T14:21:32+00:00 | actions | show me the shopping list | get_list_items | get_list_items | YES | routed | GPU | — | 10.20s | 1 |
| S23U OFF | 35 | 2026-11-09T14:21:42+00:00 | actions | read out my holiday list | get_list_items | get_list_items | YES | routed | GPU | — | 10.25s | 1 |
| S23U OFF | 36 | 2026-11-09T14:22:08+00:00 | chat | read me my grocery list | get_list_items | get_list_items | YES | failure_no_llm | GPU | — | 26.49s | 1 |
| S23U OFF | 37 | 2026-11-09T14:22:23+00:00 | actions | what time is it | get_time | get_time | YES | routed | GPU | — | 14.41s | 1 |
| S23U OFF | 38 | 2026-11-09T14:22:33+00:00 | actions | what's today's date | get_time | get_time | YES | routed | GPU | — | 10.14s | 1 |
| S23U OFF | 39 | 2026-11-09T14:22:43+00:00 | actions | what's my battery level | get_battery | get_battery | YES | routed | GPU | — | 10.32s | 1 |
| S23U OFF | 40 | 2026-11-09T14:23:10+00:00 | chat | how much battery do I have | get_battery | get_battery | YES | direct_reply | GPU | — | 26.35s | 1 |
| S23U OFF | 41 | 2026-11-09T14:23:24+00:00 | actions | battery | get_battery | get_battery | YES | routed | GPU | — | 14.57s | 1 |
| S23U OFF | 42 | 2026-11-09T14:23:34+00:00 | actions | am I running low on battery | get_battery | get_battery | YES | routed | GPU | — | 10.27s | 1 |
| S23U OFF | 43 | 2026-11-09T14:23:47+00:00 | actions | how much storage do I have left | get_system_info | get_system_info | YES | routed | GPU | — | 12.66s | 1 |
| S23U OFF | 44 | 2026-11-09T14:24:13+00:00 | chat | what's my RAM usage | get_system_info | get_system_info | YES | direct_reply | GPU | — | 26.33s | 1 |
| S23U OFF | 45 | 2026-11-09T14:24:30+00:00 | actions | how much space is left on my phone | get_system_info | get_system_info | YES | routed | GPU | — | 16.40s | 1 |
| S23U OFF | 46 | 2026-11-09T14:24:40+00:00 | actions | how many weeks until 31 October | get_date_diff | get_date_diff | YES | routed | GPU | — | 9.86s | 1 |
| S23U OFF | 47 | 2026-11-09T14:24:50+00:00 | actions | how many weeks until the 31 October | get_date_diff | get_date_diff | YES | routed | GPU | — | 9.98s | 1 |
| S23U OFF | 48 | 2026-11-09T14:25:16+00:00 | chat | show my todo list | get_list_items | get_list_items | YES | failure_no_llm | GPU | — | 26.03s | 1 |
| S23U OFF | 49 | 2026-11-09T14:25:30+00:00 | actions | what's on my shopping list | get_list_items | get_list_items | YES | routed | GPU | — | 14.05s | 1 |
| S23U OFF | 50 | 2026-11-09T14:26:10+00:00 | actions | what do I need to get from the shops | get_list_items | llm_fallthrough | NO | model_fallthrough | GPU | — | 40.37s | 1 |
| S23U OFF | 51 | 2026-11-09T14:26:28+00:00 | actions | what's on my grocery list | get_list_items | get_list_items | YES | routed | GPU | — | 17.94s | 1 |
| S23U OFF | 52 | 2026-11-09T14:26:54+00:00 | chat | show me the shopping list | get_list_items | get_list_items | YES | direct_reply | GPU | — | 26.22s | 1 |
| S23U OFF | 53 | 2026-11-09T14:27:09+00:00 | actions | read out my holiday list | get_list_items | get_list_items | YES | routed | GPU | — | 14.45s | 1 |
| S23U OFF | 54 | 2026-11-09T14:27:19+00:00 | actions | read me my grocery list | get_list_items | get_list_items | YES | routed | GPU | — | 10.38s | 1 |
| S23U OFF | 55 | 2026-11-09T14:27:29+00:00 | actions | what time is it | get_time | get_time | YES | routed | GPU | — | 10.30s | 1 |
| S23U OFF | 56 | 2026-11-09T14:27:56+00:00 | chat | what's today's date | get_time | get_time | YES | direct_reply | GPU | — | 26.40s | 1 |
| S23U OFF | 57 | 2026-11-09T14:28:10+00:00 | actions | what's my battery level | get_battery | get_battery | YES | routed | GPU | — | 14.50s | 1 |
| S23U OFF | 58 | 2026-11-09T14:28:21+00:00 | actions | how much battery do I have | get_battery | get_battery | YES | routed | GPU | — | 10.26s | 1 |
| S23U OFF | 59 | 2026-11-09T14:28:33+00:00 | actions | battery | get_battery | get_battery | YES | routed | GPU | — | 12.48s | 1 |
| S23U OFF | 60 | 2026-11-09T14:29:00+00:00 | chat | am I running low on battery | get_battery | get_battery | YES | direct_reply | GPU | — | 26.64s | 1 |
| S23U OFF | 61 | 2026-11-09T14:29:17+00:00 | actions | how much storage do I have left | get_system_info | get_system_info | YES | routed | GPU | — | 16.90s | 1 |
| S23U OFF | 62 | 2026-11-09T14:29:29+00:00 | actions | what's my RAM usage | get_system_info | get_system_info | YES | routed | GPU | — | 12.65s | 1 |
| S23U OFF | 63 | 2026-11-09T14:29:42+00:00 | actions | how much space is left on my phone | get_system_info | get_system_info | YES | routed | GPU | — | 12.74s | 1 |
| S23U OFF | 64 | 2026-11-09T14:30:08+00:00 | chat | how many weeks until 31 October | get_date_diff | get_date_diff | YES | direct_reply | GPU | — | 26.21s | 1 |
| S23U OFF | 65 | 2026-11-09T14:30:22+00:00 | actions | how many weeks until the 31 October | get_date_diff | get_date_diff | YES | routed | GPU | — | 13.98s | 1 |
| S23U OFF | 66 | 2026-11-09T14:30:32+00:00 | actions | show my todo list | get_list_items | get_list_items | YES | routed | GPU | — | 9.95s | 1 |
| S23U OFF | 67 | 2026-11-09T14:30:42+00:00 | actions | what's on my shopping list | get_list_items | get_list_items | YES | routed | GPU | — | 9.92s | 1 |
| S23U OFF | 68 | 2026-11-09T14:31:43+00:00 | chat | what do I need to get from the shops | get_list_items | llm_fallthrough | NO | model_fallthrough_complete | GPU | — | 60.95s | 1 |
| S23U OFF | 69 | 2026-11-09T14:31:58+00:00 | actions | what's on my grocery list | get_list_items | get_list_items | YES | routed | GPU | — | 14.72s | 1 |
| S23U OFF | 70 | 2026-11-09T14:32:08+00:00 | actions | show me the shopping list | get_list_items | get_list_items | YES | routed | GPU | — | 10.11s | 1 |
| S23U OFF | 71 | 2026-11-09T14:32:18+00:00 | actions | read out my holiday list | get_list_items | get_list_items | YES | routed | GPU | — | 10.26s | 1 |
| S23U OFF | 72 | 2026-11-09T14:32:44+00:00 | chat | read me my grocery list | get_list_items | get_list_items | YES | failure_no_llm | GPU | — | 26.34s | 1 |
| S23U OFF | 73 | 2026-11-09T14:32:59+00:00 | actions | what time is it | get_time | get_time | YES | routed | GPU | — | 14.43s | 1 |
| S23U OFF | 74 | 2026-11-09T14:33:09+00:00 | actions | what's today's date | get_time | get_time | YES | routed | GPU | — | 10.21s | 1 |
| S23U OFF | 75 | 2026-11-09T14:33:19+00:00 | actions | what's my battery level | get_battery | get_battery | YES | routed | GPU | — | 10.30s | 1 |
| S23U OFF | 76 | 2026-11-09T14:33:46+00:00 | chat | how much battery do I have | get_battery | get_battery | YES | direct_reply | GPU | — | 26.42s | 1 |
| S23U OFF | 77 | 2026-11-09T14:34:00+00:00 | actions | battery | get_battery | get_battery | YES | routed | GPU | — | 14.69s | 1 |
| S23U OFF | 78 | 2026-11-09T14:34:11+00:00 | actions | am I running low on battery | get_battery | get_battery | YES | routed | GPU | — | 10.25s | 1 |
| S23U OFF | 79 | 2026-11-09T14:34:23+00:00 | actions | how much storage do I have left | get_system_info | get_system_info | YES | routed | GPU | — | 12.57s | 1 |
| S23U OFF | 80 | 2026-11-09T14:34:49+00:00 | chat | what's my RAM usage | get_system_info | get_system_info | YES | direct_reply | GPU | — | 25.94s | 1 |
| S23U OFF | 81 | 2026-11-09T14:35:06+00:00 | actions | how much space is left on my phone | get_system_info | get_system_info | YES | routed | GPU | — | 16.75s | 1 |
| S23U OFF | 82 | 2026-11-09T14:35:16+00:00 | actions | how many weeks until 31 October | get_date_diff | get_date_diff | YES | routed | GPU | — | 10.15s | 1 |
| S23U OFF | 83 | 2026-11-09T14:35:26+00:00 | actions | how many weeks until the 31 October | get_date_diff | get_date_diff | YES | routed | GPU | — | 10.23s | 1 |
| S23U OFF | 84 | 2026-11-09T14:35:53+00:00 | chat | show my todo list | get_list_items | get_list_items | YES | failure_no_llm | GPU | — | 26.58s | 1 |
| S23U OFF | 85 | 2026-11-09T14:36:07+00:00 | actions | what's on my shopping list | get_list_items | get_list_items | YES | routed | GPU | — | 14.45s | 1 |
| S23U OFF | 86 | 2026-11-09T14:36:48+00:00 | actions | what do I need to get from the shops | get_list_items | llm_fallthrough | NO | model_fallthrough | GPU | — | 40.67s | 1 |
| S23U OFF | 87 | 2026-11-09T14:37:10+00:00 | actions | what's on my grocery list | get_list_items | get_list_items | YES | routed | GPU | — | 22.42s | 1 |
| S23U OFF | 88 | 2026-11-09T14:37:37+00:00 | chat | show me the shopping list | get_list_items | get_list_items | YES | direct_reply | GPU | — | 26.37s | 1 |
| S23U OFF | 89 | 2026-11-09T14:37:51+00:00 | actions | read out my holiday list | get_list_items | get_list_items | YES | routed | GPU | — | 14.51s | 1 |
| S23U OFF | 90 | 2026-11-09T14:38:02+00:00 | actions | read me my grocery list | get_list_items | get_list_items | YES | routed | GPU | — | 10.19s | 1 |
| S23U OFF | 91 | 2026-11-09T14:38:12+00:00 | actions | what time is it | get_time | get_time | YES | routed | GPU | — | 10.36s | 1 |
| S23U OFF | 92 | 2026-11-09T14:38:38+00:00 | chat | what's today's date | get_time | get_time | YES | direct_reply | GPU | — | 26.21s | 1 |
| S23U OFF | 93 | 2026-11-09T14:38:53+00:00 | actions | what's my battery level | get_battery | get_battery | YES | routed | GPU | — | 14.61s | 1 |
| S23U OFF | 94 | 2026-11-09T14:39:03+00:00 | actions | how much battery do I have | get_battery | get_battery | YES | routed | GPU | — | 10.26s | 1 |
| S23U OFF | 95 | 2026-11-09T14:39:13+00:00 | actions | battery | get_battery | get_battery | YES | routed | GPU | — | 10.36s | 1 |
| S23U OFF | 96 | 2026-11-09T14:39:40+00:00 | chat | am I running low on battery | get_battery | get_battery | YES | direct_reply | GPU | — | 26.22s | 1 |
| S23U OFF | 97 | 2026-11-09T14:39:56+00:00 | actions | how much storage do I have left | get_system_info | get_system_info | YES | routed | GPU | — | 16.83s | 1 |
| S23U OFF | 98 | 2026-11-09T14:40:09+00:00 | actions | what's my RAM usage | get_system_info | get_system_info | YES | routed | GPU | — | 12.64s | 1 |
| S23U OFF | 99 | 2026-11-09T14:40:22+00:00 | actions | how much space is left on my phone | get_system_info | get_system_info | YES | routed | GPU | — | 12.51s | 1 |
| S23U OFF | 100 | 2026-11-09T14:40:48+00:00 | chat | how many weeks until 31 October | get_date_diff | get_date_diff | YES | direct_reply | GPU | — | 26.13s | 1 |
| S23U ON | 1 | 2026-11-09T15:21:24+00:00 | actions | what time is it | get_time | get_time | YES | routed | GPU | — | 14.74s | 1 |
| S23U ON | 2 | 2026-11-09T15:21:34+00:00 | actions | what's today's date | get_time | get_time | YES | routed | GPU | — | 10.19s | 1 |
| S23U ON | 3 | 2026-11-09T15:21:44+00:00 | actions | what's my battery level | get_battery | get_battery | YES | routed | GPU | — | 10.42s | 1 |
| S23U ON | 4 | 2026-11-09T15:22:11+00:00 | chat | how much battery do I have | get_battery | get_battery | YES | direct_reply | GPU | — | 26.47s | 1 |
| S23U ON | 5 | 2026-11-09T15:22:25+00:00 | actions | battery | get_battery | get_battery | YES | routed | GPU | — | 14.44s | 1 |
| S23U ON | 6 | 2026-11-09T15:22:36+00:00 | actions | am I running low on battery | get_battery | get_battery | YES | routed | GPU | — | 10.36s | 1 |
| S23U ON | 7 | 2026-11-09T15:22:48+00:00 | actions | how much storage do I have left | get_system_info | get_system_info | YES | routed | GPU | — | 12.50s | 1 |
| S23U ON | 8 | 2026-11-09T15:23:14+00:00 | chat | what's my RAM usage | get_system_info | get_system_info | YES | direct_reply | GPU | — | 26.15s | 1 |
| S23U ON | 9 | 2026-11-09T15:23:31+00:00 | actions | how much space is left on my phone | get_system_info | get_system_info | YES | routed | GPU | — | 16.95s | 1 |
| S23U ON | 10 | 2026-11-09T15:23:42+00:00 | actions | how many weeks until 31 October | get_date_diff | get_date_diff | YES | routed | GPU | — | 10.24s | 1 |
| S23U ON | 11 | 2026-11-09T15:23:52+00:00 | actions | how many weeks until the 31 October | get_date_diff | get_date_diff | YES | routed | GPU | — | 10.33s | 1 |
| S23U ON | 12 | 2026-11-09T15:24:18+00:00 | chat | show my todo list | get_list_items | get_list_items | YES | failure_no_llm | GPU | — | 26.28s | 1 |
| S23U ON | 13 | 2026-11-09T15:24:32+00:00 | actions | what's on my shopping list | get_list_items | get_list_items | YES | routed | GPU | — | 14.34s | 1 |
| S23U ON | 14 | 2026-11-09T15:25:13+00:00 | actions | what do I need to get from the shops | get_list_items | llm_fallthrough | NO | model_fallthrough | GPU | — | 40.97s | 1 |
| S23U ON | 15 | 2026-11-09T15:25:43+00:00 | actions | what's on my grocery list | get_list_items | get_list_items | YES | routed | GPU | — | 30.04s | 1 |
| S23U ON | 16 | 2026-11-09T15:26:28+00:00 | chat | show me the shopping list | get_list_items | get_list_items | YES | direct_reply | GPU | 1/1 | 44.21s | 1 |
| S23U ON | 17 | 2026-11-09T15:26:42+00:00 | actions | read out my holiday list | get_list_items | get_list_items | YES | routed | GPU | — | 14.60s | 1 |
| S23U ON | 18 | 2026-11-09T15:26:53+00:00 | actions | read me my grocery list | get_list_items | get_list_items | YES | routed | GPU | — | 10.24s | 1 |
| S23U ON | 19 | 2026-11-09T15:27:03+00:00 | actions | what time is it | get_time | get_time | YES | routed | GPU | — | 10.21s | 1 |
| S23U ON | 20 | 2026-11-09T15:27:29+00:00 | chat | what's today's date | get_time | get_time | YES | direct_reply | GPU | — | 26.31s | 1 |
| S23U ON | 21 | 2026-11-09T15:27:44+00:00 | actions | what's my battery level | get_battery | get_battery | YES | routed | GPU | — | 14.48s | 1 |
| S23U ON | 22 | 2026-11-09T15:27:54+00:00 | actions | how much battery do I have | get_battery | get_battery | YES | routed | GPU | — | 10.31s | 1 |
| S23U ON | 23 | 2026-11-09T15:28:04+00:00 | actions | battery | get_battery | get_battery | YES | routed | GPU | — | 10.21s | 1 |
| S23U ON | 24 | 2026-11-09T15:28:30+00:00 | chat | am I running low on battery | get_battery | get_battery | YES | direct_reply | GPU | — | 26.13s | 1 |
| S23U ON | 25 | 2026-11-09T15:28:47+00:00 | actions | how much storage do I have left | get_system_info | get_system_info | YES | routed | GPU | — | 16.44s | 1 |
| S23U ON | 26 | 2026-11-09T15:28:59+00:00 | actions | what's my RAM usage | get_system_info | get_system_info | YES | routed | GPU | — | 12.16s | 1 |
| S23U ON | 27 | 2026-11-09T15:29:11+00:00 | actions | how much space is left on my phone | get_system_info | get_system_info | YES | routed | GPU | — | 12.26s | 1 |
| S23U ON | 28 | 2026-11-09T15:29:37+00:00 | chat | how many weeks until 31 October | get_date_diff | get_date_diff | YES | direct_reply | GPU | — | 25.45s | 1 |
| S23U ON | 29 | 2026-11-09T15:29:51+00:00 | actions | how many weeks until the 31 October | get_date_diff | get_date_diff | YES | routed | GPU | — | 14.02s | 1 |
| S23U ON | 30 | 2026-11-09T15:30:01+00:00 | actions | show my todo list | get_list_items | get_list_items | YES | routed | GPU | — | 10.02s | 1 |
| S23U ON | 31 | 2026-11-09T15:30:11+00:00 | actions | what's on my shopping list | get_list_items | get_list_items | YES | routed | GPU | — | 10.34s | 1 |
| S23U ON | 32 | 2026-11-09T15:31:15+00:00 | chat | what do I need to get from the shops | get_list_items | llm_fallthrough | NO | model_fallthrough_complete | GPU | — | 64.09s | 1 |
| S23U ON | 33 | 2026-11-09T15:31:30+00:00 | actions | what's on my grocery list | get_list_items | get_list_items | YES | routed | GPU | — | 15.02s | 1 |
| S23U ON | 34 | 2026-11-09T15:31:40+00:00 | actions | show me the shopping list | get_list_items | get_list_items | YES | routed | GPU | — | 10.41s | 1 |
| S23U ON | 35 | 2026-11-09T15:31:51+00:00 | actions | read out my holiday list | get_list_items | get_list_items | YES | routed | GPU | — | 10.19s | 1 |
| S23U ON | 36 | 2026-11-09T15:32:34+00:00 | chat | read me my grocery list | get_list_items | get_list_items | YES | failure_no_llm | GPU | 1/1 | 43.36s | 1 |
| S23U ON | 37 | 2026-11-09T15:32:48+00:00 | actions | what time is it | get_time | get_time | YES | routed | GPU | — | 14.46s | 1 |
| S23U ON | 38 | 2026-11-09T15:32:59+00:00 | actions | what's today's date | get_time | get_time | YES | routed | GPU | — | 10.22s | 1 |
| S23U ON | 39 | 2026-11-09T15:33:09+00:00 | actions | what's my battery level | get_battery | get_battery | YES | routed | GPU | — | 10.27s | 1 |
| S23U ON | 40 | 2026-11-09T15:33:35+00:00 | chat | how much battery do I have | get_battery | get_battery | YES | direct_reply | GPU | — | 26.28s | 1 |
| S23U ON | 41 | 2026-11-09T15:33:50+00:00 | actions | battery | get_battery | get_battery | YES | routed | GPU | — | 14.42s | 1 |
| S23U ON | 42 | 2026-11-09T15:34:02+00:00 | actions | am I running low on battery | get_battery | get_battery | YES | routed | GPU | — | 12.58s | 1 |
| S23U ON | 43 | 2026-11-09T15:34:15+00:00 | actions | how much storage do I have left | get_system_info | get_system_info | YES | routed | GPU | — | 12.75s | 1 |
| S23U ON | 44 | 2026-11-09T15:34:41+00:00 | chat | what's my RAM usage | get_system_info | get_system_info | YES | direct_reply | GPU | — | 26.00s | 1 |
| S23U ON | 45 | 2026-11-09T15:34:58+00:00 | actions | how much space is left on my phone | get_system_info | get_system_info | YES | routed | GPU | — | 16.82s | 1 |
| S23U ON | 46 | 2026-11-09T15:35:08+00:00 | actions | how many weeks until 31 October | get_date_diff | get_date_diff | YES | routed | GPU | — | 10.17s | 1 |
| S23U ON | 47 | 2026-11-09T15:35:18+00:00 | actions | how many weeks until the 31 October | get_date_diff | get_date_diff | YES | routed | GPU | — | 10.24s | 1 |
| S23U ON | 48 | 2026-11-09T15:35:44+00:00 | chat | show my todo list | get_list_items | get_list_items | YES | failure_no_llm | GPU | — | 26.22s | 1 |
| S23U ON | 49 | 2026-11-09T15:35:59+00:00 | actions | what's on my shopping list | get_list_items | get_list_items | YES | routed | GPU | — | 14.46s | 1 |
| S23U ON | 50 | 2026-11-09T15:36:40+00:00 | actions | what do I need to get from the shops | get_list_items | llm_fallthrough | NO | model_fallthrough | GPU | — | 40.91s | 1 |
| S23U ON | 51 | 2026-11-09T15:36:58+00:00 | actions | what's on my grocery list | get_list_items | get_list_items | YES | routed | GPU | — | 18.19s | 1 |
| S23U ON | 52 | 2026-11-09T15:37:43+00:00 | chat | show me the shopping list | get_list_items | get_list_items | YES | direct_reply | GPU | 1/1 | 45.03s | 1 |
| S23U ON | 53 | 2026-11-09T15:37:58+00:00 | actions | read out my holiday list | get_list_items | get_list_items | YES | routed | GPU | — | 14.64s | 1 |
| S23U ON | 54 | 2026-11-09T15:38:08+00:00 | actions | read me my grocery list | get_list_items | get_list_items | YES | routed | GPU | — | 10.23s | 1 |
| S23U ON | 55 | 2026-11-09T15:38:18+00:00 | actions | what time is it | get_time | get_time | YES | routed | GPU | — | 10.11s | 1 |
| S23U ON | 56 | 2026-11-09T15:38:44+00:00 | chat | what's today's date | get_time | get_time | YES | direct_reply | GPU | — | 26.17s | 1 |
| S23U ON | 57 | 2026-11-09T15:38:58+00:00 | actions | what's my battery level | get_battery | get_battery | YES | routed | GPU | — | 14.30s | 1 |
| S23U ON | 58 | 2026-11-09T15:39:09+00:00 | actions | how much battery do I have | get_battery | get_battery | YES | routed | GPU | — | 10.11s | 1 |
| S23U ON | 59 | 2026-11-09T15:39:19+00:00 | actions | battery | get_battery | get_battery | YES | routed | GPU | — | 10.31s | 1 |
| S23U ON | 60 | 2026-11-09T15:39:45+00:00 | chat | am I running low on battery | get_battery | get_battery | YES | direct_reply | GPU | — | 26.33s | 1 |
| S23U ON | 61 | 2026-11-09T15:40:02+00:00 | actions | how much storage do I have left | get_system_info | get_system_info | YES | routed | GPU | — | 16.72s | 1 |
| S23U ON | 62 | 2026-11-09T15:40:15+00:00 | actions | what's my RAM usage | get_system_info | get_system_info | YES | routed | GPU | — | 12.60s | 1 |
| S23U ON | 63 | 2026-11-09T15:40:27+00:00 | actions | how much space is left on my phone | get_system_info | get_system_info | YES | routed | GPU | — | 12.35s | 1 |
| S23U ON | 64 | 2026-11-09T15:40:53+00:00 | chat | how many weeks until 31 October | get_date_diff | get_date_diff | YES | direct_reply | GPU | — | 26.13s | 1 |
| S23U ON | 65 | 2026-11-09T15:41:07+00:00 | actions | how many weeks until the 31 October | get_date_diff | get_date_diff | YES | routed | GPU | — | 14.06s | 1 |
| S23U ON | 66 | 2026-11-09T15:41:17+00:00 | actions | show my todo list | get_list_items | get_list_items | YES | routed | GPU | — | 9.87s | 1 |
| S23U ON | 67 | 2026-11-09T15:41:27+00:00 | actions | what's on my shopping list | get_list_items | get_list_items | YES | routed | GPU | — | 9.90s | 1 |
| S23U ON | 68 | 2026-11-09T15:42:40+00:00 | chat | what do I need to get from the shops | get_list_items | llm_fallthrough | NO | model_fallthrough_complete | GPU | — | 72.96s | 1 |
| S23U ON | 69 | 2026-11-09T15:42:55+00:00 | actions | what's on my grocery list | get_list_items | get_list_items | YES | routed | GPU | — | 14.69s | 1 |
| S23U ON | 70 | 2026-11-09T15:43:05+00:00 | actions | show me the shopping list | get_list_items | get_list_items | YES | routed | GPU | — | 10.13s | 1 |
| S23U ON | 71 | 2026-11-09T15:43:15+00:00 | actions | read out my holiday list | get_list_items | get_list_items | YES | routed | GPU | — | 10.23s | 1 |
| S23U ON | 72 | 2026-11-09T15:44:00+00:00 | chat | read me my grocery list | get_list_items | get_list_items | YES | failure_no_llm | GPU | 1/1 | 44.67s | 1 |
| S23U ON | 73 | 2026-11-09T15:44:14+00:00 | actions | what time is it | get_time | get_time | YES | routed | GPU | — | 14.52s | 1 |
| S23U ON | 74 | 2026-11-09T15:44:24+00:00 | actions | what's today's date | get_time | get_time | YES | routed | GPU | — | 10.17s | 1 |
| S23U ON | 75 | 2026-11-09T15:44:35+00:00 | actions | what's my battery level | get_battery | get_battery | YES | routed | GPU | — | 10.28s | 1 |
| S23U ON | 76 | 2026-11-09T15:45:00+00:00 | chat | how much battery do I have | get_battery | get_battery | YES | direct_reply | GPU | — | 25.89s | 1 |
| S23U ON | 77 | 2026-11-09T15:45:15+00:00 | actions | battery | get_battery | get_battery | YES | routed | GPU | — | 14.22s | 1 |
| S23U ON | 78 | 2026-11-09T15:45:27+00:00 | actions | am I running low on battery | get_battery | get_battery | YES | routed | GPU | — | 12.58s | 1 |
| S23U ON | 79 | 2026-11-09T15:45:40+00:00 | actions | how much storage do I have left | get_system_info | get_system_info | YES | routed | GPU | — | 12.48s | 1 |
| S23U ON | 80 | 2026-11-09T15:46:06+00:00 | chat | what's my RAM usage | get_system_info | get_system_info | YES | direct_reply | GPU | — | 26.07s | 1 |
| S23U ON | 81 | 2026-11-09T15:46:22+00:00 | actions | how much space is left on my phone | get_system_info | get_system_info | YES | routed | GPU | — | 16.68s | 1 |
| S23U ON | 82 | 2026-11-09T15:46:33+00:00 | actions | how many weeks until 31 October | get_date_diff | get_date_diff | YES | routed | GPU | — | 10.14s | 1 |
| S23U ON | 83 | 2026-11-09T15:46:43+00:00 | actions | how many weeks until the 31 October | get_date_diff | get_date_diff | YES | routed | GPU | — | 10.22s | 1 |
| S23U ON | 84 | 2026-11-09T15:47:09+00:00 | chat | show my todo list | get_list_items | get_list_items | YES | failure_no_llm | GPU | — | 26.06s | 1 |
| S23U ON | 85 | 2026-11-09T15:47:23+00:00 | actions | what's on my shopping list | get_list_items | get_list_items | YES | routed | GPU | — | 14.34s | 1 |
| S23U ON | 86 | 2026-11-09T15:48:04+00:00 | actions | what do I need to get from the shops | get_list_items | llm_fallthrough | NO | model_fallthrough | GPU | — | 40.69s | 1 |
| S23U ON | 87 | 2026-11-09T15:48:25+00:00 | actions | what's on my grocery list | get_list_items | get_list_items | YES | routed | GPU | — | 20.59s | 1 |
| S23U ON | 88 | 2026-11-09T15:49:09+00:00 | chat | show me the shopping list | get_list_items | get_list_items | YES | direct_reply | GPU | 1/1 | 44.13s | 1 |
| S23U ON | 89 | 2026-11-09T15:49:23+00:00 | actions | read out my holiday list | get_list_items | get_list_items | YES | routed | GPU | — | 14.40s | 1 |
| S23U ON | 90 | 2026-11-09T15:49:33+00:00 | actions | read me my grocery list | get_list_items | get_list_items | YES | routed | GPU | — | 10.24s | 1 |
| S23U ON | 91 | 2026-11-09T15:49:43+00:00 | actions | what time is it | get_time | get_time | YES | routed | GPU | — | 10.19s | 1 |
| S23U ON | 92 | 2026-11-09T15:50:09+00:00 | chat | what's today's date | get_time | get_time | YES | direct_reply | GPU | — | 25.87s | 1 |
| S23U ON | 93 | 2026-11-09T15:50:24+00:00 | actions | what's my battery level | get_battery | get_battery | YES | routed | GPU | — | 14.43s | 1 |
| S23U ON | 94 | 2026-11-09T15:50:34+00:00 | actions | how much battery do I have | get_battery | get_battery | YES | routed | GPU | — | 10.21s | 1 |
| S23U ON | 95 | 2026-11-09T15:50:44+00:00 | actions | battery | get_battery | get_battery | YES | routed | GPU | — | 10.22s | 1 |
| S23U ON | 96 | 2026-11-09T15:51:10+00:00 | chat | am I running low on battery | get_battery | get_battery | YES | direct_reply | GPU | — | 26.11s | 1 |
| S23U ON | 97 | 2026-11-09T15:51:27+00:00 | actions | how much storage do I have left | get_system_info | get_system_info | YES | routed | GPU | — | 16.81s | 1 |
| S23U ON | 98 | 2026-11-09T15:51:40+00:00 | actions | what's my RAM usage | get_system_info | get_system_info | YES | routed | GPU | — | 12.56s | 1 |
| S23U ON | 99 | 2026-11-09T15:51:52+00:00 | actions | how much space is left on my phone | get_system_info | get_system_info | YES | routed | GPU | — | 12.56s | 1 |
| S23U ON | 100 | 2026-11-09T15:52:18+00:00 | chat | how many weeks until 31 October | get_date_diff | get_date_diff | YES | direct_reply | GPU | — | 26.04s | 1 |
