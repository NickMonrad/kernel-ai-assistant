# #1988 retest: ExecuTorch/XNNPACK INT8 vs ONNX Runtime FP32 asr4all

Benchmark-only spike. No production code, model selection, Auto default or #1987 change. Harness, pins and
procedure: [`tools/spike-1988-executorch/README.md`](../../../tools/spike-1988-executorch/README.md). Owner audio
and transcripts stayed in app-private storage (removed after each run) and a private host directory; only
aggregates appear here.

## 1. Root cause of the earlier Android "divergence"

**A harness indexing bug, not an ExecuTorch runtime defect.** The earlier decoder (`ExecuTorchAsr4allDecoder.step`
on `spike/build8-1988-executorch` / `test/build8-1988-xnnpack-aar-control`) read logits with
`offset = frame * vocab.size` and argmaxed `for (token in vocab.indices)`. `metadata.json` `vocab` has 256 entries;
the logits tensor is `[1, commit, 257]` (vocab + blank id 256). Each row was therefore read shifted by `frame`
floats and blank was unselectable.

Minimal host reproduction (`scripts/repro_prior_harness_bug.py`, no device): the official FUTO driver's correct
ExecuTorch 1.5.1 logits for the public clip, decoded with the old indexing, give exactly the strings the earlier
S23U runs reported:

| Step | Reference (stride 257) | Old harness indexing (stride 256) | Reported on device earlier |
|---|---|---|---|
| 0 | `he` | `s'abcdefghijkigsen` | `s'abcdefghijkigsen` |
| 1 | `he hoped there would` | `s'abcdefghijkigsen hndard hadosing andfgereuljk has hern` | identical |

The earlier hash/min/max/mean comparisons covered only the first `16 × 256` of `16 × 257` floats, so they were
never comparable with the Python hash; the identical Maven/S3 hash only shows both 1.5.1 builds compute the same.

Ruled out on host:
- official `asr4all_executorch.py` (same SHA-256 `0918a4dd…` in S/M/L) with `executorch==1.5.1` + `torch==2.14.0`:
  sensible raw and PCEC text on public clips (x86_64);
- the same `.pte` through `extension::Module` (the Android JNI call path): bit-identical logits;
- the `aarch64` 1.5.1 wheel under qemu-user `cortex-a710`: same argmax and text (ARM XNNPACK kernels);
- v1.5.1 built from source with the Android preset's `EXECUTORCH_XNNPACK_ENABLE_WEIGHT_CACHE=ON` and
  `EXECUTORCH_XNNPACK_SHARED_WORKSPACE=ON`, toggled at load (`host/`): no change.

On the S23U the Maven `executorch-android:1.5.1` AAR gives the reference argmax for the first two steps with
defaults, with weight cache and workspace sharing off, and with one thread (`StepProbeTest`).

## 2. Device parity (S23U, public clips)

Public clips: Narsil/asr_dummy `1.flac`–`3.flac` (LibriSpeech) and `mlk.flac` at dataset revision
`8d141c84e3f84c54cd7bbaa851d24edd0f559734`, converted with ffmpeg to 16 kHz mono PCM16; both tiers.

| Size | CTC frames agreeing with host reference | Max top-2 margin at a disagreeing frame | Flushed PCEC words differing |
|---|---|---|---|
| S | 1649/1662 (99.22%) | 0.09 | 5/151 |
| M | 1655/1662 (99.58%) | 0.26 | 0/152 |
| L | 1654/1662 (99.52%) | 0.09 | 0/152 |

Disagreements are confined to near-ties (top-1 minus top-2 log-prob ≤ 0.26 on at least one side). For scale:
x86 vs S23U step-0 logits differ by up to 0.47, and the official script on x86 vs aarch64 (qemu) itself changes
"brothels" to "brahels". PCEC runs and changes output (casing, punctuation, "creream" → "cream"); where raw tokens
agree, PCEC text matches the reference. Control: Jandal's ONNX engine (#1987 `591e829`) gives identical text to
FUTO's official `asr4all_onnx.py` on the S/c16r4 public clips.

## 3. Measurements

Setup: isolated probe, one fresh instrumentation process per lane (load, PSS and VmHWM are per lane; model
files were already in page cache, so load is warm-cache, not first-ever cold load). Both runtimes run the same
FUTO streaming algorithm with PCEC on, at their as-shipped default threading (ExecuTorch JNI: cores/2 = 4 threads;
ONNX Runtime via the unmodified #1987 `Asr4allEngine`: ORT default). 100 ms (1,600-sample) chunks.

- **Owner set**: the 20 #1984 decision recordings (146 reference words, phrase list SHA-256 `ea7d480c…`), paced
  real-time feed, then EOF flush; **WER = flushed PCEC-corrected final** (primary), raw CTC final as diagnostic.
- **Breadth**: the 100 #1984 voice-cloned clips (638 words, phrase list `72790822…`), compute-only (no pacing).
  Diagnostic only; not used for the decision.
- **67 s stream**: public clips 1, 2, 3, MLK concatenated twice, paced; sustained-backlog check.
- **Public set**: clips 1–4 + MLK, paced (first corrected partial; owner clips are too short to emit one).

Tokenisation matches #1978 (NFC, lowercase, apostrophes removed, other punctuation separates tokens, macrons kept).
The ONNX lanes reproduce #1978's corrected S23U/S21 owner WER exactly (S 32.88/27.40, M 26.03/26.03,
L 20.55/19.86), which cross-checks this harness against the previous ONNX measurement. Every WER is identical on
S23U and S21 (both runtimes are deterministic across the two SoCs on these inputs).

### Summary (ET = ExecuTorch 1.5.1 XNNPACK INT8, ONNX = ONNX Runtime 1.24.3 FP32)

| Size/tier | Owner WER PCEC % ET / ONNX | Breadth WER PCEC % ET / ONNX | S23U load ms ET / ONNX | S21 load ms ET / ONNX | Peak PSS Δ MiB ET / ONNX (S23U) | S23U owner RTFx ET / ONNX | S21 owner RTFx ET / ONNX | S21 67 s stream lag p95 ms ET / ONNX | S21 67 s max queued chunks ET / ONNX |
|---|---|---|---|---|---|---|---|---|---|
| S c16r4 | 32.88 / 32.88 | 11.13 / 11.60 | 177 / 660 | 398 / 1987 | 108 / 182 | 11.7 / 7.5 | 6.9 / 3.7 | 85 / 154 | 1 / 2 |
| S c32r8 | 29.45 / 27.40 | 11.29 / 10.82 | 190 / 551 | 383 / 1752 | 112 / 186 | 18.1 / 10.9 | 9.0 / 5.0 | 109 / 179 | 1 / 2 |
| M c16r4 | 25.34 / 26.03 | 7.37 / 7.21 | 269 / 645 | 791 / 1976 | 161 / 309 | 10.0 / 5.8 | 4.8 / 2.3 | 124 / 252 | 2 / 3 |
| M c32r8 | 26.03 / 26.03 | 6.58 / 6.74 | 211 / 622 | 677 / 1783 | 162 / 319 | 15.4 / 7.4 | 6.2 / 3.3 | 165 / 305 | 2 / 4 |
| L c16r4 | 21.23 / 20.55 | 7.68 / 7.84 | 472 / 661 | 1020 / 2202 | 221 / 491 | 8.6 / 4.0 | 3.6 / 1.4 | 174 / 449 | 2 / 6 |
| L c32r8 | 19.18 / 19.86 | 6.74 / 6.58 | 304 / 676 | 808 / 2289 | 230 / 529 | 11.8 / 4.8 | 4.7 / 1.9 | 232 / 527 | 3 / 7 |

### Full tables

`Max queued chunks` = floor(max lag / 100 ms): later 100 ms chunks waiting behind a decode. First partial times are
wall time from stream start, so they include the tier's window (c16r4 ≈ 1.4 s, c32r8 ≈ 2.7 s). First corrected
(PCEC) partials appear only once 16 tokens plus PCEC lookahead exist, so none occurred on owner clips for either
runtime; the public-set column is shown instead.

| Device | Lane | Owner WER PCEC % | Owner WER raw % | Breadth WER PCEC % | Load ms | Peak PSS Δ MiB | VmHWM MiB | Owner RTFx | Breadth RTFx | First raw partial ms (n) | First corrected partial ms, public (n) | Owner lag p95 / max ms | Max queued chunks | NZ/Māori terms exact (macron-insensitive) / total | Section A edits / words |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| S23U | et l c16r4 | **21.23** | 21.92 | 7.68 | 472 | 221 | 322 | 8.6 | 9.3 | 1881 (16/20) | 5690 (3/5) | 80 / 90 | 0 | 2 (3) / 17 | 25/85 |
| S23U | et l c32r8 | **19.18** | 19.86 | 6.74 | 304 | 230 | 329 | 11.8 | 10.5 | 3508 (6/20) | 6652 (2/5) | 2 / 117 | 1 | 2 (3) / 17 | 24/85 |
| S23U | et m c16r4 | **25.34** | 28.77 | 7.37 | 269 | 161 | 262 | 10.0 | 14.7 | 1668 (18/20) | 5465 (3/5) | 68 / 86 | 0 | 1 (2) / 17 | 31/85 |
| S23U | et m c32r8 | **26.03** | 26.03 | 6.58 | 211 | 162 | 263 | 15.4 | 14.5 | 3089 (11/20) | 6993 (1/5) | 2 / 100 | 0 | 1 (2) / 17 | 31/85 |
| S23U | et s c16r4 | **32.88** | 36.30 | 11.13 | 177 | 108 | 209 | 11.7 | 31.8 | 1457 (19/20) | 5942 (3/5) | 56 / 68 | 0 | 1 (1) / 17 | 37/85 |
| S23U | et s c32r8 | **29.45** | 33.56 | 11.29 | 190 | 112 | 213 | 18.1 | 27.2 | 2769 (11/20) | 6683 (2/5) | 3 / 78 | 0 | 1 (1) / 17 | 36/85 |
| S23U | onnx l c16r4 | **20.55** | 21.92 | 7.84 | 661 | 491 | 601 | 4.0 | 3.3 | 1968 (16/20) | 5728 (3/5) | 153 / 184 | 1 | 2 (3) / 17 | 25/85 |
| S23U | onnx l c32r8 | **19.86** | 19.86 | 6.58 | 676 | 529 | 628 | 4.8 | 3.5 | 3626 (6/20) | 6786 (2/5) | 3 / 249 | 2 | 2 (3) / 17 | 24/85 |
| S23U | onnx m c16r4 | **26.03** | 28.77 | 7.21 | 645 | 309 | 419 | 5.8 | 5.7 | 1719 (18/20) | 5535 (3/5) | 109 / 142 | 1 | 1 (2) / 17 | 32/85 |
| S23U | onnx m c32r8 | **26.03** | 26.71 | 6.74 | 622 | 319 | 432 | 7.4 | 6.1 | 3155 (11/20) | 7051 (1/5) | 3 / 166 | 1 | 1 (2) / 17 | 31/85 |
| S23U | onnx s c16r4 | **32.88** | 36.30 | 11.60 | 660 | 182 | 289 | 7.5 | 13.8 | 1489 (19/20) | 6001 (3/5) | 86 / 106 | 1 | 0 (0) / 17 | 37/85 |
| S23U | onnx s c32r8 | **27.40** | 32.19 | 10.82 | 551 | 186 | 295 | 10.9 | 13.2 | 2801 (11/20) | 6721 (3/5) | 4 / 111 | 1 | 0 (0) / 17 | 35/85 |
| S21 | et l c16r4 | **21.23** | 21.92 | 7.68 | 1020 | 225 | 338 | 3.6 | 4.2 | 1961 (16/20) | 5805 (3/5) | 163 / 206 | 2 | 2 (3) / 17 | 25/85 |
| S21 | et l c32r8 | **19.18** | 19.86 | 6.74 | 808 | 236 | 348 | 4.7 | 4.8 | 3621 (6/20) | 6782 (2/5) | 4 / 249 | 2 | 2 (3) / 17 | 24/85 |
| S21 | et m c16r4 | **25.34** | 28.77 | 7.37 | 791 | 158 | 271 | 4.8 | 6.3 | 1725 (18/20) | 5515 (3/5) | 117 / 164 | 1 | 1 (2) / 17 | 31/85 |
| S21 | et m c32r8 | **26.03** | 26.03 | 6.58 | 677 | 167 | 280 | 6.2 | 6.7 | 3170 (11/20) | 7092 (1/5) | 4 / 196 | 1 | 1 (2) / 17 | 31/85 |
| S21 | et s c16r4 | **32.88** | 36.30 | 11.13 | 398 | 114 | 226 | 6.9 | 9.5 | 1482 (19/20) | 5970 (3/5) | 76 / 131 | 1 | 1 (1) / 17 | 37/85 |
| S21 | et s c32r8 | **29.45** | 33.56 | 11.29 | 383 | 115 | 227 | 9.0 | 10.5 | 2810 (11/20) | 6728 (2/5) | 5 / 147 | 1 | 1 (1) / 17 | 36/85 |
| S21 | onnx l c16r4 | **20.55** | 21.92 | 7.84 | 2202 | 498 | 611 | 1.4 | 1.7 | 2259 (16/20) | 6004 (3/5) | 407 / 610 | 6 | 2 (3) / 17 | 25/85 |
| S21 | onnx l c32r8 | **19.86** | 19.86 | 6.58 | 2289 | 510 | 632 | 1.9 | 2.0 | 3963 (6/20) | 7047 (2/5) | 73 / 638 | 6 | 2 (3) / 17 | 24/85 |
| S21 | onnx m c16r4 | **26.03** | 28.77 | 7.21 | 1976 | 329 | 449 | 2.3 | 3.5 | 1858 (18/20) | 5649 (3/5) | 240 / 346 | 3 | 1 (2) / 17 | 32/85 |
| S21 | onnx m c32r8 | **26.03** | 26.71 | 6.74 | 1783 | 344 | 462 | 3.3 | 3.9 | 3338 (11/20) | 7332 (1/5) | 101 / 443 | 4 | 1 (2) / 17 | 31/85 |
| S21 | onnx s c16r4 | **32.88** | 36.30 | 11.60 | 1987 | 196 | 312 | 3.7 | 5.3 | 1555 (19/20) | 6066 (3/5) | 145 / 227 | 2 | 0 (0) / 17 | 37/85 |
| S21 | onnx s c32r8 | **27.40** | 32.19 | 10.82 | 1752 | 200 | 317 | 5.0 | 6.3 | 2893 (11/20) | 6806 (3/5) | 30 / 281 | 2 | 0 (0) / 17 | 35/85 |

| Device | Lane | RTFx | Lag p95 / max ms | Mean lag first → last fifth ms | Max queued chunks (chunks ≥100 ms) | Final after audio end ms | Peak PSS Δ MiB |
|---|---|---|---|---|---|---|---|
| S23U | et l c16r4 | 5.9 | 96 / 107 | 14 → 19 | 1 (10/667) | 138 | 243 |
| S23U | et l c32r8 | 6.7 | 109 / 133 | 11 → 17 | 1 (47/667) | 212 | 251 |
| S23U | et m c16r4 | 6.1 | 81 / 91 | 14 → 17 | 0 (0/667) | 144 | 180 |
| S23U | et m c32r8 | 7.0 | 98 / 123 | 10 → 16 | 1 (28/667) | 172 | 186 |
| S23U | et s c16r4 | 6.9 | 67 / 81 | 13 → 12 | 0 (0/667) | 105 | 125 |
| S23U | et s c32r8 | 7.5 | 78 / 95 | 9 → 14 | 0 (0/667) | 143 | 130 |
| S23U | onnx l c16r4 | 3.7 | 157 / 196 | 30 → 37 | 1 (102/667) | 381 | 512 |
| S23U | onnx l c32r8 | 4.0 | 227 / 269 | 28 → 38 | 2 (100/667) | 606 | 505 |
| S23U | onnx m c16r4 | 4.9 | 117 / 141 | 19 → 24 | 1 (69/667) | 234 | 316 |
| S23U | onnx m c32r8 | 5.2 | 149 / 197 | 19 → 25 | 1 (50/667) | 340 | 326 |
| S23U | onnx s c16r4 | 5.5 | 96 / 121 | 16 → 20 | 1 (19/667) | 166 | 189 |
| S23U | onnx s c32r8 | 6.0 | 108 / 131 | 13 → 19 | 1 (44/667) | 241 | 197 |
| S21 | et l c16r4 | 3.3 | 174 / 292 | 40 → 41 | 2 (113/667) | 480 | 255 |
| S21 | et l c32r8 | 4.3 | 232 / 364 | 29 → 38 | 3 (109/667) | 601 | 260 |
| S21 | et m c16r4 | 4.5 | 124 / 217 | 25 → 25 | 2 (99/667) | 373 | 183 |
| S21 | et m c32r8 | 5.7 | 165 / 256 | 18 → 25 | 2 (60/667) | 475 | 190 |
| S21 | et s c16r4 | 6.3 | 85 / 114 | 15 → 16 | 1 (8/667) | 187 | 128 |
| S21 | et s c32r8 | 7.8 | 109 / 171 | 13 → 15 | 1 (42/667) | 249 | 134 |
| S21 | onnx l c16r4 | 1.4 | 449 / 643 | 154 → 196 | 6 (389/667) | 1249 | 522 |
| S21 | onnx l c32r8 | 2.1 | 527 / 730 | 134 → 142 | 7 (255/667) | 1444 | 534 |
| S21 | onnx m c16r4 | 2.4 | 252 / 366 | 68 → 70 | 3 (206/667) | 683 | 347 |
| S21 | onnx m c32r8 | 3.4 | 305 / 433 | 52 → 51 | 4 (141/667) | 805 | 354 |
| S21 | onnx s c16r4 | 3.8 | 154 / 231 | 32 → 32 | 2 (101/667) | 326 | 206 |
| S21 | onnx s c32r8 | 5.1 | 179 / 276 | 27 → 28 | 2 (73/667) | 546 | 204 |

### Not measured / limits

- **Speech-end (endpoint) latency**: not measured. This harness has no endpoint-triggered-final trial; all finals
  are EOF flushes, which #1988 excludes from latency. ONNX endpoint coverage in #1978 was 1 qualifying clip.
- Memory is per-process PSS/VmHWM, including the ~37 MiB test-process baseline in VmHWM; PSS Δ excludes it.
- Thread counts were not equalised; this compares each runtime as it would ship.
- S PCEC text differs from the x86 host oracle on 5/151 public words (near-tie frames; identical on M/L). On the
  owner set ET and ONNX S/c32r8 differ by 3 edits (43 vs 40); other lanes differ by ≤1 edit or not at all.
- Test APK SHA-256 `757209c6…` for the S23U owner/breadth/public lanes; `493a9163…` for S23U 67 s and all S21
  lanes (only `StepProbeTest` changed between them). App APK `5bd1bcd8…` throughout.

## 4. Recommendation

**Recommendation: consider replacing ONNX Runtime with ExecuTorch for asr4all, via a separate integration
issue; if adopted, prefer M/c16r4.** Do not change #1987, the Auto default or #1980 in this spike.

Why:
- **Accuracy is equivalent.** INT8 vs FP32 owner WER differ by at most 3 edits on 146 words in either direction
  (ET better on M/c16r4 and L/c32r8, worse on S/c32r8), and ≤0.5 points on breadth. Nothing here says INT8 costs
  accuracy.
- **ExecuTorch is cheaper on every resource measured.** Peak PSS 40–57% lower; load 1.4–3.7× faster on S23U and
  2.2–5× faster on S21; compute 1.6–2.6× faster. Download is much smaller: one PTE of 32 / 60 / 101 MB (S/M/L)
  vs 108 / 214 / 374 MB of ONNX weights.
- **It changes which size is affordable on the S21.** S21 ONNX M/L build 3–7 queued chunks on the 67 s stream
  (L c32r8 p95 lag 527 ms, final 1.4 s after audio end); ET M stays at ≤2 chunks (p95 124–165 ms) with less
  memory than ONNX S. ET M/c16r4 gives owner WER 25.34% (vs 32.88% for S/c16r4) and breadth 7.4% (vs 11.1%),
  with the earliest raw partial among M lanes (~1.7 s).
- **L**: best owner WER (19.18% ET c32r8) but ~1 s load, ~230 MiB and 2–3 queued chunks on S21; keep
  benchmark-only unless accuracy on Māori place names becomes the priority (L hits 2/17 exact vs 1/17 for M;
  all sizes miss most of them, which is #1983's job).

Trade-offs and costs for the integration issue:
- A second native runtime in the APK: `libexecutorch.so` (9.1 MB arm64, 16 KB-aligned LOAD segments) +
  `libfbjni.so`; it needs a `libc++_shared.so` pickFirst. ONNX Runtime stays for openWakeWord, so this adds a
  runtime rather than removing one; the asr4all ONNX path would be deleted, not kept alongside.
- New model packaging (single `.pte` + `metadata.json`), new download pins and licence notices (ExecuTorch
  BSD-3-Clause; weights unchanged under the FUTO restricted-use licence).
- A production Kotlin port of `asr4all_executorch.py` with the 257-column logits handling made explicit; the earlier
  failure shows that this is the one easy place to get wrong.
- Endpoint latency and live-mic acceptance must be measured in that issue; they were not measured here.

If the owner prefers not to take on a second native runtime, ONNX S/c32r8 (the current #1980 proposal) remains
valid; ExecuTorch does not change accuracy, only cost and which size fits the S21.
