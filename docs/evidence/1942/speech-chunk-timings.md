# #1942 speech chunk timings (S23U, Cori high, made-up text)

Method: `SpeechProbeReceiver` (debug build only, explicit component) speaks a file through
`speakInSentenceChunks` -> `VoiceOutputController`. Logs from tag `KernelAI`. No real calendar data.

| Run | Threads | Text | Chunk | Chars | Synth ms | Notes |
|-----|---------|------|-------|-------|----------|-------|
| 1 (after install) | 2 | old format, 290 chars | 1 | 63 | 9923 | Init ~7 s before. Only one chunk synthesised before the next run started. |
| 2 (warm, clean) | 2 | old format | 1 | 63 | 11076 | Next chunk (104 chars) synth 23181 ms. Playback of chunk 1 ended before chunk 2 was ready. |
| 3 (4 threads, clean) | 4 | old format | 1 | 63 | 8470 | About 25% faster than 2 threads on chunk 1 only. |
| 4 (overlapped) | 4 | old format | 2 | 104 | 69290 | Invalid: a previous aborted native synth still held the ONNX session. |
| 5 (overlapped) | 4 | new format | 1 | 63 | 127632 | Invalid: same overlap. |
| 6 (after app restart) | 2 | new format, 200 chars | 1 | 57 | no completion in 2+ min | Hang: `producer got chunk` logged, no synth end, process idle. |

Root cause evidence so far:
- Synthesis runs at about 2.4 to 3 times real time on this device. Playback of a chunk therefore ends long before the next chunk is synthesised, which is the audible gap.
- The pipeline does overlap synthesis with playback (`audioQueue` capacity 2). The gap is synthesis speed, not a missing lookahead. Not yet confirmed by a clean multi-chunk run.
- Runs 4 to 6 are not valid timing data. Native synth calls cannot be cancelled, and overlapping sessions queue behind them.
- Run 6 hangs with near-zero CPU, with no native error output after model creation. Not reproduced on the S21 (its lock was held by another agent). Cause unknown.

Not done: Home-stop test, `speak_end reason=stopped`, card screenshot, S21 check, stale-synth generation counter.
