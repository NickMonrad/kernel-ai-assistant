# #1982 S21 Whisper low-band verification

Device check for AC2: the Whisper tiny.en verifier still works after the Zipformer removal. Aggregates only;
per-ring verdicts, transcripts and the ring set stay in a private directory outside Git.

- Device: S21, SM-G991B, serial `R5CR605B71K`, Android 15 (`o1s:15`, build `AP3A.240905.015.A2`). The S23U and Honor were not touched.
- Build: debug APK from the tree of `feat/1982-remove-zipformer` at `6864d26d` (`app/build/outputs/apk/debug/app-debug.apk`, SHA-256 `ded17683…`). Installed for the run only.
- Path: production `verifyWakeWindow` → `transcribeBlocking` → Whisper tiny.en → `containsWakePhrase`, driven by the debug `WakeVerifierProbeReceiver` (`harness/device_run.sh s21 … whisper`). Two runs per ring; run 2 minus run 1 is the warm verification time.
- Rings: 1,118 low-band candidate rings from the #1045 spike set (genuine 375, near-miss 420, background speech 290, non-speech 33; the 3 s window the detector hands over at the first frame ≥ 0.50). Ring set `rings.tar` SHA-256 prefix `8faf448f`.
- Lock: `/tmp/jandal-s21.lock` held by `omp-1982` for the run and released afterwards. No other device was used.

## Results

Full aggregate: [`s21-whisper-aggregate.json`](s21-whisper-aggregate.json).

| Class | Rings | Accepted (run 1) | Accepted (run 2) | Both runs agree | Warm verify |
|---|---|---|---|---|---|
| Genuine "Hey Jandal" | 375 | 45.6% (171) | 45.6% (171) | 100% | |
| Near-miss | 420 | 0.7% (3) | 0.7% (3) | 100% | |
| Background speech | 290 | 0.7% (2) | 0.7% (2) | 100% | |
| Non-speech | 33 | 0% (0) | 0% (0) | 100% | |
| Owner natural (subset of genuine) | 3 | 1/3 | | | |

Warm verification latency over 1,118 rings (run 2 minus run 1, from logcat): p50 492 ms, p95 708 ms, max 1,028 ms, mean 510 ms. Cold first-verify latency was not re-measured in this run.

Regression check: the verdicts were compared with the #1045 S21 Whisper run on the same rings, from the main-based build before this PR. All 2,236 run verdicts are identical (2236/2236). The genuine, near-miss, background and owner figures match the published #1045 S21 Whisper row, and the warm p50/p95/max (507/713/1006 ms) are within run-to-run noise.

## Run incidents and cleanup

- The harness's EXIT cleanup did not run after the Whisper phase finished (no `cleanup done` line in `run.log`). Cleanup was done by hand, in the harness's order: the 1,118 `wv_*.wav` fixtures removed from the app's `files/acoustic-fixtures`, `/data/local/tmp/wake-verifier` removed, the original debug APK reinstalled with `install -r -d` (pre-run pull, SHA-256 `1dbd856a…`), and the lock removed.
- `stay_on_while_plugged_in` was set to `usb` by the harness and has been set back to `0`. The pre-run value was not recorded by the harness, so `0` (the Android default) is an assumption, not a verified original.
- The Whisper model files were already on the device (`pushed=no`) and were left in place.
