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

## 2. Device parity (S23U and S21, public clips)

Public clips: Narsil/asr_dummy `1.flac`–`4.flac` and `mlk.flac` at dataset revision
`8d141c84e3f84c54cd7bbaa851d24edd0f559734`, converted with ffmpeg to 16 kHz mono PCM16; both tiers. `1`–`3` are
LibriSpeech English, `mlk` is English, **`4` is Spanish** (out of domain for an English-only model). Every row is
reported with and without clip 4. S23U and S21 produce bit-identical CTC frame ids and texts on all 30 rows
(including clip 4), so one set of figures covers both phones.

Two host oracles, both FUTO's unmodified `asr4all_executorch.py` with ExecuTorch 1.5.1: the x86_64 wheel and the
aarch64 wheel under qemu-user (`-cpu cortex-a710`, ARM XNNPACK kernels like the phones).

| Size | Frames agreeing, x86 oracle, without clip 4 | x86 oracle, all 5 clips | **aarch64 oracle, all 5 clips** | Flushed PCEC words differing (x86, without / with clip 4) | **aarch64: PCEC rows identical / words differing** |
|---|---|---|---|---|---|
| S | 1649/1662 (99.22%) | 1972/1990 (99.10%) | 1987/1990 (99.85%) | 5/151 / 10/167 | 7/10 rows, 3/169 words |
| M | 1655/1662 (99.58%) | 1974/1990 (99.20%) | 1986/1990 (99.80%) | 0/152 / 7/176 | 10/10 rows, 0 words |
| L | 1654/1662 (99.52%) | 1980/1990 (99.50%) | 1988/1990 (99.90%) | 0/152 / 2/181 | 10/10 rows, 0 words |

- **Near-ties, corrected claim.** Against the x86 oracle, disagreements are not all near-ties: on M/c16r4 clip 4
  two frames disagree with top-2 margins of 0.66/0.69 and 0.63/1.19 (host/device). That is within the cross-ISA
  logit drift (x86 vs S23U step-1 logits differ by up to 1.16). Against the **aarch64** oracle, M clip 4 agrees on
  every frame, and the largest min(host, device) margin at any disagreeing frame is 0.12 (S), 0.03 (M), 0.04 (L).
  The x86 oracle itself also differs from the aarch64 oracle (e.g. "brothels" → "brahels" on S/c16r4 clip 3).
- **PCEC text.** Against the aarch64 oracle M and L final texts are identical on all 10 rows including clip 4.
  S differs on 3 rows by one word each: clip 3 c16r4 (three acoustic frames differ, min margin ≤ 0.12), and
  clip 4 / MLK c32r8, where every acoustic frame agrees but the PCEC pass produces a different word (PCEC argmax
  margins were not recorded; e.g. device `cream.`, aarch64 host `creream.`, both from raw `cream`). So that is a
  host/device PCEC numerics difference, not PCEC "fixing" a word as previously stated. Without clip 4 and against
  the x86 oracle, M and L are identical, as previously reported; with clip 4 they are not (M 7/176, L 2/181
  words): x86 and ARM kernels diverge most on that out-of-domain clip.
- **ONNX control.** The device ONNX lane (#1987 `591e829`, compiled unmodified from Git) gives raw and PCEC finals
  identical to FUTO's `asr4all_onnx.py` on host for **all 6 size/tier lanes × 5 public clips on both phones**
  (120/120 strings).

## 3. Measurements

Setup: isolated probe, one fresh instrumentation process per lane (load, PSS and VmHWM are per lane; model
files were already in page cache, so load is warm-cache, not first-ever cold load). Both runtimes run the same
FUTO streaming algorithm with PCEC on, 100 ms (1,600-sample) chunks. **Each timing figure is a single run per
lane** (no repeats, no variance estimate); WER is deterministic and reproduced on both phones.

**Configuration confound (disclosed).** The main tables compare each runtime *as configured for shipping*, which
is not like-for-like:
- **ExecuTorch**: Java `Module.load` default, i.e. the JNI's `cores/2` = **4 threads** (`jni_layer.cpp:329`),
  XNNPACK INT8 kernels; XNNPACK weight cache and shared workspace ON per the v1.5.1 Android build preset.
- **ONNX (#1987 `Asr4allEngine.openSession`)**: `setCPUArenaAllocator(false)` and
  `session.disable_prepacking=1` (memory savings: weights stay views of the mmapped `.onnx.data`), and intra-op
  threads left at the ORT default ([INFERENCE] ORT's default is one thread per physical core, i.e. likely all 8
  on these SoCs, little cores included). FP32 kernels.

So the main-table speed/memory ratios mix the runtime, INT8 vs FP32, thread count and #1987's memory-saving
settings. The **ORT control** in §3.1 re-runs ONNX with 4 intra-op threads and arena + prepacking enabled to
separate those.

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

**Ratio ranges across all sets** (main tables, ET vs ONNX as configured by #1987; single run per lane):

| Set | Peak PSS reduction | Compute RTFx ratio ET/ONNX | Load ratio ONNX/ET |
|---|---|---|---|
| Owner (paced) | S23U 39.5–56.6%, S21 41.9–54.7% | S23U 1.55–2.47×, S21 1.80–2.49× | S23U 1.40–3.72×, S21 2.16–4.99× |
| Breadth (compute-only) | S23U 37.1–52.5%, S21 39.5–51.3% | S23U 2.06–2.99×, S21 1.66–2.54× | S23U 1.80–3.18×, S21 1.81–3.95× |
| 67 s stream (paced) | S23U 33.6–52.5%, S21 34.1–51.2% | S23U 1.23–1.66×, S21 1.53–2.28× | S23U 1.47–2.82×, S21 2.65–3.61× |
| Public (paced) | S23U 41.2–57.2%, S21 45.2–55.2% | S23U 1.39–2.10×, S21 1.78–2.27× | S23U 1.81–3.29×, S21 2.66–4.15× |

All sets: PSS 33.6–57.2% lower, compute 1.23–2.99× faster, load 1.40–4.99× faster. The smallest speed-ups are on
the S23U 67 s stream (S and M 1.23–1.35×), where both runtimes mostly wait on the 100 ms pacing.

### 3.1 ORT control lane (configuration confound)

Same isolated APK, same session, three runtimes interleaved per lane: ET (as above), ONNX with #1987's settings,
and **ONNX 4 thr + arena/prepack** = the identical #1987 engine source compiled into a second package whose only
change is `openSession`: `setIntraOpNumThreads(4)`, CPU arena and weight prepacking left at ORT defaults (enabled)
(`probe/build.gradle.kts` generates it; the build fails if the two replaced `openSession` lines are not found
verbatim, and the #1987 commit is pinned by default). Lanes: M/c16r4 and
S/c32r8 (the #1980 proposal), on both phones. Single run per lane; the ET rows here are a second session and differ
from the main tables by run-to-run variance (e.g. S23U ET M breadth RTFx 19.8 vs 14.7 earlier), which is the
practical noise floor for these timings.

| Device | Size/tier | Runtime | Owner WER PCEC % | Breadth WER PCEC % | Load ms | Peak PSS Δ MiB (max of 3 sets) | Owner RTFx | Breadth RTFx | 67 s RTFx | 67 s lag p95 / max ms | 67 s max queued chunks | 67 s final after end ms |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| S23U | M/C16R4 | ET INT8 (4 thr) | 25.34 | 7.37 | 338 | 198 | 10.3 | 19.8 | 6.1 | 80 / 97 | 0 | 155 |
| S23U | M/C16R4 | ONNX #1987 config | 26.03 | 7.21 | 717 | 331 | 5.7 | 7.4 | 4.7 | 121 / 147 | 1 | 258 |
| S23U | M/C16R4 | ONNX 4 thr + arena/prepack | 26.03 | 7.21 | 778 | 559 | 6.7 | 8.6 | 5.0 | 109 / 138 | 1 | 232 |
| S23U | S/C32R8 | ET INT8 (4 thr) | 29.45 | 11.29 | 270 | 129 | 17.9 | 36.9 | 7.6 | 76 / 88 | 0 | 101 |
| S23U | S/C32R8 | ONNX #1987 config | 27.40 | 10.82 | 540 | 235 | 11.0 | 16.5 | 6.0 | 110 / 135 | 1 | 232 |
| S23U | S/C32R8 | ONNX 4 thr + arena/prepack | 27.40 | 10.82 | 672 | 371 | 12.3 | 17.7 | 6.3 | 99 / 127 | 1 | 213 |
| S21 | M/C16R4 | ET INT8 (4 thr) | 25.34 | 7.37 | 405 | 185 | 6.0 | 7.2 | 4.5 | 127 / 170 | 1 | 336 |
| S21 | M/C16R4 | ONNX #1987 config | 26.03 | 7.21 | 2012 | 347 | 2.3 | 3.2 | 2.4 | 257 / 395 | 3 | 663 |
| S21 | M/C16R4 | ONNX 4 thr + arena/prepack | 26.03 | 7.21 | 2860 | 576 | 2.9 | 4.1 | 2.9 | 203 / 330 | 3 | 601 |
| S21 | S/C32R8 | ET INT8 (4 thr) | 29.45 | 11.29 | 547 | 135 | 9.2 | 11.2 | 7.6 | 110 / 171 | 1 | 259 |
| S21 | S/C32R8 | ONNX #1987 config | 27.40 | 10.82 | 1774 | 210 | 5.0 | 6.1 | 5.2 | 176 / 277 | 2 | 506 |
| S21 | S/C32R8 | ONNX 4 thr + arena/prepack | 27.40 | 10.82 | 1985 | 335 | 6.0 | 7.3 | 5.9 | 144 / 232 | 2 | 420 |

**Does the ExecuTorch speed advantage survive? Yes, reduced.** Against ORT at 4 threads with arena and prepacking:
- compute RTFx ET/ORT-tuned: S23U owner 1.53× (M) / 1.46× (S), breadth 2.29× / 2.09×, 67 s stream 1.23× / 1.21×;
  S21 owner 2.03× / 1.52×, breadth 1.76× / 1.55×, 67 s 1.57× / 1.29×. (Against #1987's config the same lanes were
  1.26–2.67×.) Tuning ORT recovers only 1.04–1.27× of throughput.
- tuned ORT costs memory: peak PSS 1.58–1.80× of #1987's config and ET at 29–40% of tuned ORT's PSS, both computed
  **per set** (owner, breadth, 67 s) from each set's peak PSS Δ; on the table's max-of-3-sets column the same
  ratios are 1.58–1.69× and 32–40% (M/c16r4 559–576 MiB vs 331–347). Tuned loads are slower in all 12 cases
  (1.09–1.44×; prepacking copies weights).
- on the S21 67 s stream, tuned ORT M/c16r4 still queues up to 3 chunks (p95 203 ms, final 601 ms after audio end)
  vs ET M/c16r4 1 chunk (p95 127 ms, 336 ms).
- WER is unchanged by the ORT settings (identical edits).

So most of the gap survives equalised threads and ORT's arena/prepacking: it belongs to the published ExecuTorch
INT8/XNNPACK export vs the ORT FP32 export as a whole. This spike cannot separate INT8 kernels from runtime
overhead (ORT INT8 would need custom quantisation, out of scope). #1987's memory-saving settings make ORT only
4–21% slower (tuned/#1987 throughput 1.04–1.27×) while saving 37–44% of its PSS.

### Full tables

`Max queued chunks` = floor(max lag / 100 ms): later 100 ms chunks waiting behind a decode. First partial times are
wall time from stream start, so they include the tier's window (c16r4 ≈ 1.4 s, c32r8 ≈ 2.7 s). First corrected
(PCEC) partials appear only once 16 tokens plus PCEC lookahead exist, so none occurred on owner clips for either
runtime; the public-set column is shown instead. **Section A** = owner clips `A-01`–`A-12` (#1984 section A: quiet
room, NZ and Māori place names; 85 reference words); the column is summed PCEC word edits over those 12 clips.

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
- **Single run per lane** for every timing and memory figure (load, RTFx, lag, queued chunks, PSS); no repeats or
  variance. The ORT-control session re-ran ET and #1987-config ONNX on 4 lanes per phone: owner and 67 s RTFx moved
  by ≤6% except S21 ET M owner (4.8 → 6.0, +25%); breadth RTFx rose 24–36% on the S23U for both runtimes alike and
  up to +13% on the S21 (ET M 6.3 → 7.2); load moved by up to −55% (S23U ET M breadth 463 → 207 ms; S21 ET M
  owner 791 → 405 ms); PSS by ≤10% except S23U ONNX S breadth (+22%). Treat speed ratios below ~1.3× and load
  differences below ~2× as within noise; ratios within one session are more reliable.
- Thread counts and ORT session options differ in the main tables (see "Configuration confound"); §3.1 controls
  for them on two lanes only.
- Parity figures in §2 exclude Spanish clip 4 in the first and fourth columns; all-clip figures are alongside.
- On the owner set ET and ONNX S/c32r8 differ by 3 edits (43 vs 40); other lanes differ by ≤1 edit or not at all.
- One S21 control lane (#1987-config ONNX M/c16r4, owner) aborted at start inside `android.os.Debug.getPss()`
  (platform binder assertion, SIGABRT, 0.7 s after launch, before any model load); it was re-run alone in a new
  locked session and completed.
- Test APK SHA-256 `757209c6…` for the S23U owner/breadth/public lanes; `493a9163…` for S23U 67 s and all S21
  lanes (only `StepProbeTest` changed between them). App APK `5bd1bcd8…` for those. The ORT-control runs (§3.1)
  used app APK `66f80c1f…` (adds the `onnxt4` lane) with the unchanged test APK `493a9163…`.

## 4. Recommendation

**Recommendation: consider replacing ONNX Runtime with ExecuTorch for asr4all, via a separate integration
issue; if adopted, prefer M/c16r4.** Do not change #1987, the Auto default or #1980 in this spike.

Why:
- **Accuracy is equivalent.** INT8 vs FP32 owner WER differ by at most 3 edits on 146 words in either direction
  (ET better on M/c16r4 and L/c32r8, worse on S/c32r8), and ≤0.5 points on breadth. Nothing here says INT8 costs
  accuracy.
- **ExecuTorch is cheaper on every resource measured**, across all sets: peak PSS 33.6–57.2% lower, load
  1.40–4.99× faster, compute 1.23–2.99× faster vs #1987's ORT config (PSS ranges are per lane and set, from each
  set's peak PSS Δ). Against ORT tuned to 4 threads with arena and prepacking (§3.1) it is still 1.21–2.29× faster
  at 29–40% of the PSS (per set; 32–40% on the max-of-3-sets column). Download: one PTE of 32.5 / 60.0 /
  101.0 MB (S/M/L) vs 109 / 215 / 375 MB for the ONNX tier sets.
- **Against the incumbent #1980 proposal (ONNX S/c32r8), ET M/c16r4 is not an accuracy win on the decision set**:
  37 vs 40 owner edits on 146 words, which is noise. The case for M rests on:
  - resources: S23U/S21 peak PSS 161/158 vs 186/200 MiB, load 269/791 vs 551/1752 ms, similar owner RTFx
    (10.0/4.8 vs 10.9/5.0);
  - latency: first raw partial ~1.7 s on 18/20 owner clips vs ~2.8–2.9 s on 11/20. **This comes from the c16r4
    tier, not the runtime**: ONNX M/c16r4 already gives 1719 ms on 18/20 (S23U) at 38 edits. S21 67 s stream:
    p95 lag 124 vs 179 ms, final 373 vs 546 ms after audio end; both ≤2 queued chunks;
  - breadth (diagnostic only, not a decision input per #1984): 7.37% vs 10.82% WER on 638 voice-cloned words.
- **The runtime-specific win is that M/c16r4 fits the S21 on ExecuTorch.** On the S21 67 s stream ET M/c16r4
  queues at most 2 chunks (p95 124 ms; 1 chunk / 127 ms in the control session), while ONNX M/c16r4 queues 3
  (p95 252 ms) and tuned ORT M/c16r4 also 3 (p95 203 ms); ONNX L queues 6–7 (c32r8 p95 527 ms, final 1.4 s after
  audio end). ET M does this with less memory than ONNX S. So choosing M/c16r4 (c16r4's early partials with M's
  accuracy) is only comfortable on the S21 with ExecuTorch.
- **L**: best owner WER (19.18% ET c32r8) but ~1 s load, ~230 MiB and 2–3 queued chunks on S21; keep
  benchmark-only unless accuracy on Māori place names becomes the priority (L hits 2/17 exact vs 1/17 for M;
  all sizes miss most of them, which is #1983's job).

Trade-offs and costs for the integration issue:
- A second native runtime in the APK. arm64: `libexecutorch.so` 9.06 MB + `libfbjni.so` 0.18 MB (fbjni 0.7.0);
  its `libc++_shared.so` (1.29 MB) adds nothing because `core:memory` already ships `c++_shared`, but the two
  copies must be resolved with a `pickFirst` and ABI-checked. `app/build.gradle.kts` has no `abiFilters`, so
  universal/preview APKs would also carry x86_64 `libexecutorch.so` 12.05 MB + `libfbjni.so` 0.17 MB. All LOAD
  segments are 16 KB-aligned. ONNX Runtime stays for openWakeWord, so this adds a runtime rather than removing
  one; the asr4all ONNX path would be deleted, not kept alongside.
- New model packaging (single `.pte` + `metadata.json`), new download pins and licence notices (ExecuTorch
  BSD-3-Clause; weights unchanged under the FUTO restricted-use licence).
- A production Kotlin port of `asr4all_executorch.py`. **It must assert output shapes at runtime**: this probe
  hard-codes the method geometry (`Asr4allEtGeometry`, `vocab = 257`) and never checks `outs[i].toTensor().shape()`;
  the 1.5.1 Java `MethodMetadata` exposes only name and backends, not tensor metadata. The port should check
  logits `[1, commit, len(vocab) + 1]`, hidden `[1, commit, dim]`, cache and PCEC shapes on the first call and
  fail fast. A stride mismatch on exactly this tensor caused the earlier false "divergence".
- Endpoint latency and live-mic acceptance must be measured in that issue; they were not measured here.

If the owner prefers not to take on a second native runtime, ONNX S/c32r8 (the current #1980 proposal) remains
valid; ExecuTorch does not change accuracy, only cost and whether M/c16r4 fits the S21. The earlier first partial
of M/c16r4 is a tier choice that ONNX can also make, at higher S21 lag.

## Licence note

This evidence was first published in PR #2002, which also contained a Kotlin port of FUTO's
`executorch/asr4all_executorch.py`. FUTO's reference drivers (`asr4all_executorch.py`, `onnx/asr4all_onnx.py`)
carry no software licence: the asr4all repos' `LICENSE` (FUTO Model Weights License (Restricted Use) 1.0) covers
only the weights and says inference code is governed separately. This replacement therefore publishes no port,
translation or close paraphrase of either script. Excluded relative to #2002:

| File in #2002 | Why excluded |
|---|---|
| `probe/src/main/.../EtAsr4allDecoder.kt` | Kotlin port of `asr4all_executorch.py` (stream loop, CTC, PCEC pooling/rendering) |
| `probe/src/main/.../Asr4allEtGeometry.kt` | The driver's per-tier method geometry table; used only by the driver and `StepProbeTest` |
| `probe/src/androidTest/.../StepProbeTest.kt` | Its step loop re-expresses the driver's `_step` window/cache handling line for line |
| `host/xnnpack_options_runner.cpp`, `host/CMakeLists.txt` | The same `_step` window/cache loop in C++ (and its build file) |

These files are kept in a private directory outside Git and compiled in with `-PprivateEtDecoderDir` (see
[`tools/spike-1988-executorch/README.md`](../../../tools/spike-1988-executorch/README.md)); the probe build fails
if the driver is missing. Host oracles import FUTO's script unmodified at run time from the pinned snapshot.
`scripts/repro_prior_harness_bug.py` was rewritten to observe the driver's own steps and use its own CTC collapse
instead of re-implementing them; on host (x86_64, `executorch==1.5.1`, `torch==2.14.0`) it prints exactly the
strings in §1.

**Provenance.** Every device result in this document (§1 `StepProbeTest`, §2 parity, §3 benchmarks and ORT
control) and the §1 `extension::Module` host check was produced with the excluded files exactly as committed in
#2002 at commit `53afb5a8`, identified by SHA-256:

| File (at `53afb5a8`) | SHA-256 |
|---|---|
| `EtAsr4allDecoder.kt` | `c71769d6e9c20d4c54a0180b90758c7951d548593a049d1deb1634220f78c634` |
| `Asr4allEtGeometry.kt` | `8bf1a1f1e8dfff2b3df69f5496944a77b828b4c81a7a27a20f47bedd3294c864` |
| `StepProbeTest.kt` | `b6647612e3ca0a8d457f169239e9b767544ca8a389b8b101f259959d99de0c39` |
| `host/xnnpack_options_runner.cpp` | `b441d4936f10d482ef8009c3d77e59fb30b74cd0a8dbac8f6d4874136dcb83f1` |
| `host/CMakeLists.txt` | `5e1f2a9ee6d95aea4d333f329b53a7803652af43f7af68241689507b91ba7689` |

The ONNX lanes compile Jandal's #1987 engine straight out of Git at `591e8291` (PR #1987 head) at build time;
nothing from it is copied into this PR. That engine's own `Asr4allPcec.kt` describes itself as a port of the
reference driver's `_Pcec`, so its licence status belongs to #1987 and is unchanged by this PR.

Numbers and conclusions above are unchanged from #2002. The §4 item "a production Kotlin port of
`asr4all_executorch.py`" would, for the same reason, need a licence from FUTO or an implementation that does not
derive from that script.
