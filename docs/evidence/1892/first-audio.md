# #1892 first-audio measurement

Device: S21 (`R5CR605B71K`, Android, Sherpa-ONNX TTS backend). Media volume 1 during runs (restored to 8).
Question: "What is the capital of Samoa? Answer in two short sentences." via `quick_action_input` (voice), routed to chat with `speakResponse`.
Metric: `SpeakingStarted epochMs` minus host-issued `am start` time, both device clock. Warm-up run excluded.

| Build | Warm-up (ms) | Run 1 (ms) | Run 2 (ms) | Run 3 (ms) |
|---|---|---|---|---|
| Before (d0b06533) | 31385 | 27709 | 22317 | 24018 |
| After (ba50af7d) | 48750 | 40422 | 41332 | 39978 |

Result: the candidate is SLOWER on this reply (~+16–18 s median). Not an acceptable outcome; unresolved.
Likely cause [INFERENCE, not verified against logs]: first chunk requires 40 chars, so the first short sentence
(~29 chars) waits for the second sentence; sentence-only streaming removes the earlier comma cut the baseline used.
Script: `measure-first-audio.sh` (token read on-device via `run-as`; no secrets committed).

## Re-measurement attempt (streamed first chunk = 20 chars) — INVALID, not used
Alternating MAIN (origin/main 10fd382f) / BRANCH, 3 cycles, 60 s idle after each install.
Not valid: the S21 was not in the earlier device state. The chat model was still loading (reply was
"Still loading the AI model…"), the Arctic Embed model was not downloaded, and the Cori Sherpa voice pack was
unavailable, so speech fell back to android_tts. The only SpeakingStarted lines were that fallback message, which
ActionsViewModel ignored. No valid first-audio numbers were produced. Re-run once the S21 has its models and voice pack.
