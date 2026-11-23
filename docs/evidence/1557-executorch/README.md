# #1557 Kokoro on ExecuTorch: spike evidence

Debug-only spike. CPU/XNNPACK, fp32, shipped software-mansion dynamic `.pte` pair. Not a release path. Compared with the Inflect Micro ONNX baseline (#2088, S23U, ORT 1.24.3).

## Scope decisions

- **Model.** Shipped dynamic `.pte` pair, used at exact token length (`n = phonemes + 2`) with an all-true BOOL mask. No re-export. The static, no-delegate re-export was dropped.
- **BOOL input.** The 1.5.1 AAR has no BOOL path in `Tensor.ones/zeros/fromBlob`. A UINT8 mask is rejected by the runtime (`Input 1 has unexpected scalar type: expected Bool but was Byte`). Chosen route: `BoolInputTensor extends Tensor` in Java, overriding `dtype()` and `getRawDataBuffer()`. No native code, no JNI library added. A Kotlin subclass does not compile (`Tensor(long[])` is `internal`).
- **Native-side acceptance.** Proven on device: the runtime accepts `BoolInputTensor` (instrumented smoke and the lane's `status: ok` runs).

## PC evidence

- Static no-delegate vs shipped dynamic (phrase 1): SNR 25.34 dB. Path dropped.
- XNNPACK static duration lowering hung on executorch 1.5.1 (>11 min) and 1.4.1 (1200 s timeout, `export/dur14_xnn.log`). Dropped.
- Padded (n=128, valid-prefix mask) vs exact-length all-ones mask: durations identical on phrases L=16, 51, 7. Audio SNR 42.20 dB (phrase 1), 31.76 dB (phrase 2, marginal), 39.37 dB (phrase 3).

## Device evidence (S23U, SM-S918B, Android 16)

- `BoolInputSmokeTest`: 3/3 pass (mixed mask `[1,0,3,0]`, all-true, UINT8 mask rejected pre-native). Gradle also ran it on the S21 (SM-G991B) without holding that device's lock; this is disclosed, not a lock-protocol run.
- Corpus: 8 samples, 3 repeats = 24 rows per run (sha256 `d3f0422e…`, matches #2088).
- Build: debug APK git_sha stamp `03edad83` (from the build metadata; treat as the stamp, not a verified commit).

### Kokoro alone (fresh process launch each run)

| Run | RTF median (min–max) | First-chunk median | Cancel return | VmHWM end |
|---|---|---|---|---|
| cold1 | 0.749 (0.625–0.935) | 2454 ms | 650 ms | 1.32 GB |
| cold2 | 0.849 (0.634–0.938) | 2807 ms | 644 ms | 1.31 GB |
| cold3 | 0.861 (0.742–0.940) | 3071 ms | 644 ms | 1.29 GB |

Recovery ok in all three. Runner init 17–25 ms.

### Kokoro with E4B resident

E4B loaded by one chat message to the app. The app process was kept, so these are warm-process runs, not cold-process runs (the label `with-e4b-cold*` is the script's name, kept for consistency; it is misleading).

| Run | RTF median (min–max) | First-chunk median | Cancel return | VmHWM end | RSS end |
|---|---|---|---|---|---|
| with-e4b 1 | 0.698 (0.651–0.827) | 2539 ms | 635 ms | 2.33 GB | 1.88 GB |
| with-e4b 2 | 0.939 (0.700–1.106) | 2833 ms | 789 ms | 2.33 GB | 1.41 GB |
| with-e4b 3 | 0.935 (0.697–1.120) | 2892 ms | 1108 ms | 2.33 GB | 1.41 GB |

[INFERENCE] The RTF rise and cancel latency in runs 2–3 come from E4B contention (the Kokoro lane shares CPU with a resident LLM). Not isolated.

### Inflect baseline (#2088, same device, ORT)

RTF median ~0.24, first-chunk ~2.5 s, cancel→throw ~5.9 s, VmHWM ~1.02–1.04 GB (chat model not resident).

## Comparison

- Kokoro alone is about 3× slower in RTF (0.75–0.86 vs 0.24), similar first-chunk latency (2.5–3.1 s vs 2.5 s), and about 6× faster on cancel (~0.65 s vs ~5.9 s). Peak RSS about 0.3 GB higher alone; about 1.3 GB higher with E4B resident.
- Cancel numbers are not like-for-like: Kokoro's cancel is checked between chunks; Inflect's is checked at stage boundaries.

## Known defects and gaps

- **Digit normalisation missing** in the phonemizer: "It happened in 1999." becomes `nˌIntˈin nˈIndinˈIn`. Not fixed.
- **Error-path hang.** A UINT8 mask hung the lane for ~15 min (no JSON) rather than aborting. The per-run 20 min deadline in `DebugTtsBaselineService` now turns a stuck native call into a failed JSON, but cannot interrupt it. `docs/architecture/executorch-runtime.md` says errors abort; the S23U hung. That doc note needs correcting.
- **Phrase 2 PC equivalence** marginal (31.76 dB).
- **Listening set** rendered on PC to `/home/lokhor/private-acoustic-fixtures/kokoro-executorch-1557/` (outside the repo, not uploaded). Not listened to, not ASR-checked. The WAVs are PC renders, not device output.
- **16 KB and APK size.** Not re-run. No native library was added, so the documented figures are assumed unchanged [INFERENCE]. `scripts/check_android_16kb_artifacts.py` needs an AAB.
- **Release phonemiser licence** is an open question (`~/.cache/kokoro-et-1557/phonemis-licences.md`).

## Reproduction

- Device runs: `run_cold.sh <label>` (force-stops and relaunches), `run_warm.sh <label>` (keeps the process, E4B resident). Results: `results/<label>.json`.
- PC: `verify_unpadded.py`, `snr_pad_vs_exact.py`, `listen_set.py`.
