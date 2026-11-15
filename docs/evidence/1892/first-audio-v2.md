# #1892 first-audio re-measurement (S21, alternating main / branch)

Device: S21 `R5CR605B71K`, `ai.jandal.assistant.debug`, media volume 1, stay-on 3 during run (restored to 8 / 0).
Builds: MAIN = origin/main `10fd382f` (APK `/tmp/apk-main-1892.apk`); BRANCH = `8609c4de` (20-char streamed first chunk).
Method: 3 cycles; each build installed with `install -r`, device idle 60 s, one unmeasured warm-up question, then one measured question. Question: "What is the capital of Samoa? Answer in two short sentences."
Measured: time from `am start` to first `SpeakingStarted` in `KernelAI` logcat.
Engine: logs show `engine=sherpa_stream`. The Settings > Voice screen showed "Cori · Download failed" and "Piper is selected, but its voice is not ready yet", so the exact voice file used was not confirmed for every run.

| Cycle | MAIN (ms) | BRANCH (ms) |
|---|---|---|
| 1 | 27818 | 36904 |
| 2 | 46016 | 36859 |
| 3 | 47784 | 36893 |
| median | 46016 | 36893 |

Warm-up runs (not counted): MAIN 84495, 55012, 55384; BRANCH 48217, 45798, 44323 ms.

Result: no measurable improvement in this run. BRANCH is steadier (36.9 s x3) and MAIN varies 27.8–47.8 s. The sample is small and the device load varied, so this does not confirm the 20-char rule's effect. A pass with the logged voice pinned (or with Cori Ready) is still needed.
