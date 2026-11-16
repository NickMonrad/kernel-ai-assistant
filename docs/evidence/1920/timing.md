# #1920 device evidence: chat voice command, S23U, debug build, E-4B on GPU

Device: Samsung S23 Ultra (`RFCW11265XH`), debug package `ai.jandal.assistant.debug`. Device idle, screen awake.
Command: `Tell me a short story about a dog.` via the harness (`quick_action_is_voice=true`).
Metric: `TTFT_STAGE stage=first_token t_ms` (ms from `accepted` in `ChatViewModel` to first answer token).
Raw logs: `s23u-ttft-runs.txt` (BEFORE/AFTER timings, 3 install cycles, alternating builds), `diag-after-triggers.txt` (AFTER trigger diagnostic).

BEFORE = `origin` base HEAD `f07a327d`. AFTER = this branch.

## Time to first token (ms), measured runs

| Build | Run 1 | Run 2 | Run 3 | Median |
|---|---|---|---|---|
| BEFORE cycle 1 | 19542 | 23994 | 15659 | 19542 |
| BEFORE cycle 2 | 49793 | 23333 | 32780 | 32780 |
| BEFORE cycle 3 | 51790 | 25516 | 29044 | 29044 |
| AFTER cycle 1 | 37202 | 17095 | 16954 | 17095 |
| AFTER cycle 2 | 47958 | 47527 | 19749 | 47527 |
| AFTER cycle 3 | 38064 | 63058 | 18011 | 38064 |

Pooled (9 runs each): BEFORE median 25516 ms, AFTER median 37202 ms. Spread is 15–63 s on both builds. Engine-only TTFT shows the same spread. **No measurable TTFT improvement on this debug build.** The AFTER median is not a regression signal; the variance is larger than any effect.

## Rebuild trigger diagnostic (AFTER build, 3 runs)

Per run, rebuild lines were captured from logcat cleared before `am start`.

- A rebuild (`CONV_REBUILD reason=updateSystemPrompt`) appears about 1.7 s **before** `accepted`. It is the launch-time prompt install, outside the TTFT window.
- After `accepted`, the only trigger is `CONV_REBUILD_TRIGGER reason=independentCommandReset`, and no `CONV_REBUILD` line follows it. The reset is a no-op because the conversation is clean.
- Result: zero rebuilds inside the voice command window in all 3 runs.

BEFORE builds have no rebuild counter, so BEFORE rebuild counts are not recorded. Under HEAD, `resetConversation` always rebuilds, so each BEFORE command pays one rebuild inside its TTFT window.

## Scope of this evidence

- Debug builds leave `debugForceGpuConversationReset` off, so the per-turn GPU history replay (the owner's release-path ~4-rebuild case, #684/#1089 workaround) does not run in this harness. The fold-into-replay path is covered by unit tests only.
- The release-path 41 s chat-to-speech timing from the issue is **not** reproduced by this evidence. A release-build measurement is still needed to show that user-visible gain.
