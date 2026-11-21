# asr4all ExecuTorch per-device-tier measurement (S23U, S21, S21 low-end proxy)

Benchmark only, for the owner decision of 21 Nov (asr4all on ExecuTorch INT8; M default on capable devices, L as
a user option, S only if a device class exists where M can't keep up and S beats the alternatives). It answers
audit #2001 F3 (reproducibility) and F4 (decision-grade tier evidence, like-for-like baseline). No production
code, model selection or default is changed.

- Harness and procedure: [`tools/asr4all-tier-measurement/`](../../../tools/asr4all-tier-measurement/README.md).
- Data in this directory (transcript-free): `per-run-metrics.json` (one row per device × lane × set × repeat:
  timings, memory, coverage, WER, per-clip edit counts keyed by the public #1984 phrase IDs) and `summary.json`
  (mean / sd / min / max / n over repeats). Tables below are `scripts/tables.py summary.json` verbatim.
- **Licence:** FUTO's `asr4all_executorch.py` is unlicensed, so the Kotlin ExecuTorch driver is **kept in a
  private directory outside Git** and compiled in at build time; no port of it is published here (details in the
  harness README). It is the driver verified frame-for-frame against FUTO's script in #2002, plus runtime
  output-shape assertions.

## Setup

| Item | Value |
|---|---|
| Devices | S23U `RFCW11265XH` (SM8550, 12 GB → `HardwareTier.FLAGSHIP`); S21 `R5CR605B71K` (Exynos 2100, 8 GB → `MID_RANGE`) |
| Low-end proxy (for `LOW_POWER`) | S21 with every thread of the process pinned to CPUs 0–2 (three Cortex-A55 at ≤2.21 GHz) via `taskset -a -p 07`, re-applied before each clip; ExecuTorch at 2 threads, Zipformer at its shipping 2. All 42 proxy runs recorded `Cpus_allowed_list = 0-2` on every thread. **A proxy, not a device**: it keeps the S21's memory system and lacks the 2 mid/big cores most budget SoCs have. |
| ET threads | 4 (JNI default) on S23U and S21; 2 on the low-end proxy only. The LOW_POWER numbers are 2-thread results (see Recommendation). |
| ExecuTorch | Maven `executorch-android:1.5.1`, FUTO asr4all S/M/L `executorch/xnnpack_int8` PTEs (`7d567968…`, `d08648b9…`, `2670068a…`, pins as in #2002), JNI default threads (4) unless proxy |
| Zipformer | shipping `sherpa-onnx-streaming-zipformer-en-2023-02-21` int8 @`bfbb1195…`, the app's Sherpa AAR, shipping recogniser config |
| Android native | Google on-device recogniser (`AiAiSpeechRecognitionService`, `en-AU` device locale), shipping intent + `EXTRA_AUDIO_SOURCE` + `EXTRA_SEGMENTED_SESSION` (see caveats) |
| Normaliser | Jandal `TranscriptNormaliser` from `origin/main` `00025895`, compiled unmodified, applied on device after each engine's shipping pre-processing |
| Sets | owner: 20 #1984 decision recordings, 146 words, paced 100 ms feed; long: 66.7 s public stream (`long-public.wav` `342c3e48…`), paced; breadth: 100 #1984 voice-cloned clips, 638 words, compute-only, repeat 1 |
| Repeats | 3 sessions per phone (one lock hold each), lane order rotated per session; fresh process per lane per repeat |
| APKs | app `98e97a76…`, test `1bb5f280…` for all 6 sessions; isolated package `ai.jandal.spike.asr4alltier` |
| Private ExecuTorch driver | not in Git; SHA-256 `EtAsr4allDecoder.kt` `9b11efc5…`, `Asr4allEtGeometry.kt` `07d8ef37…` on the measurement host (see Caveats) |

WER: #1978 tokenisation (`score.py`), summed edits / summed words. "Normalised" is the primary column for the like-for-like comparison F4 asked for. The #1987 asr4all engine does not call the normaliser, so the asr4all normalised column is hypothetical. On the owner set the normaliser changed no final of any engine (normalised = as-emitted). On breadth it changed one Zipformer final (284 → 283 edits on both phones), so Zipformer breadth is 44.36% normalised (44.52% as emitted). "Repeats without recogniser error" drops the Android native repeats with a recogniser error (see Caveats); for all other lanes it equals the normalised column.

## Results

#### Accuracy (owner = 20 decision clips, 146 words; breadth = 100 voice-cloned clips, 638 words)

| Device | Lane | Owner WER % (normalised) | Owner WER %, normalised, repeats without recogniser error | Owner WER % (as emitted) | Owner raw WER % | Breadth WER % (normalised) | NZ/Māori terms (normalised) |
|---|---|---|---|---|---|---|---|
| S23U | ET S c16r4 | **32.88** | 32.88 (3/3) | 32.88 | 36.30 | 11.13 | 1/17 |
| S23U | ET S c32r8 | **29.45** | 29.45 (3/3) | 29.45 | 33.56 | 11.29 | 1/17 |
| S23U | ET M c16r4 | **25.34** | 25.34 (3/3) | 25.34 | 28.77 | 7.37 | 1/17 |
| S23U | ET M c32r8 | **26.03** | 26.03 (3/3) | 26.03 | 26.03 | 6.58 | 1/17 |
| S23U | ET L c16r4 | **21.23** | 21.23 (3/3) | 21.23 | 21.92 | 7.68 | 2/17 |
| S23U | ET L c32r8 | **19.18** | 19.18 (3/3) | 19.18 | 19.86 | 6.74 | 2/17 |
| S23U | Zipformer (shipping) | **47.26** | 47.26 (3/3) | 47.26 | 47.26 | 44.36 | 0/17 |
| S23U | Android native | **32.19 (30.82–34.93)** | 30.82 (2/3) | 32.19 (30.82–34.93) | 32.19 (30.82–34.93) | 9.56 | 0/17 |
| S21 | ET S c16r4 | **32.88** | 32.88 (3/3) | 32.88 | 36.30 | 11.13 | 1/17 |
| S21 | ET S c32r8 | **29.45** | 29.45 (3/3) | 29.45 | 33.56 | 11.29 | 1/17 |
| S21 | ET M c16r4 | **25.34** | 25.34 (3/3) | 25.34 | 28.77 | 7.37 | 1/17 |
| S21 | ET M c32r8 | **26.03** | 26.03 (3/3) | 26.03 | 26.03 | 6.58 | 1/17 |
| S21 | ET L c16r4 | **21.23** | 21.23 (3/3) | 21.23 | 21.92 | 7.68 | 2/17 |
| S21 | ET L c32r8 | **19.18** | 19.18 (3/3) | 19.18 | 19.86 | 6.74 | 2/17 |
| S21 | Zipformer (shipping) | **47.26** | 47.26 (3/3) | 47.26 | 47.26 | 44.36 | 0/17 |
| S21 | Android native | **30.59 (30.14–31.51)** | 30.14 (2/3) | 30.59 (30.14–31.51) | 30.59 (30.14–31.51) | 9.56 | 0/17 |
| S21 | ET S c16r4 [low-end proxy: cpus 0x07, t2] | **32.88** | 32.88 (3/3) | 32.88 | 36.30 | – | 1/17 |
| S21 | ET S c32r8 [low-end proxy: cpus 0x07, t2] | **29.45** | 29.45 (3/3) | 29.45 | 33.56 | – | 1/17 |
| S21 | ET M c16r4 [low-end proxy: cpus 0x07, t2] | **25.34** | 25.34 (3/3) | 25.34 | 28.77 | – | 1/17 |
| S21 | ET M c32r8 [low-end proxy: cpus 0x07, t2] | **26.03** | 26.03 (3/3) | 26.03 | 26.03 | – | 1/17 |
| S21 | ET L c16r4 [low-end proxy: cpus 0x07, t2] | **21.23** | 21.23 (3/3) | 21.23 | 21.92 | – | 2/17 |
| S21 | ET L c32r8 [low-end proxy: cpus 0x07, t2] | **19.18** | 19.18 (3/3) | 19.18 | 19.86 | – | 2/17 |
| S21 | Zipformer (shipping) [low-end proxy: cpus 0x07, default] | **47.26** | 47.26 (3/3) | 47.26 | 47.26 | – | 0/17 |

#### Real-time behaviour (paced 100 ms feed; mean ± sd over repeats, worst repeat for max columns)

| Device | Lane | n | Owner RTFx | Owner lag p95 ms | Owner max queued chunks (worst) | 67 s RTFx | 67 s lag p95 ms | 67 s max queued (worst) | 67 s mean lag first → last fifth ms | 67 s final after audio end ms |
|---|---|---|---|---|---|---|---|---|---|---|
| S23U | ET S c16r4 | 3 | 16.41 ± 4.58 | 40 ± 18 | 0 | 10.74 ± 3.88 | 49 ± 19 | 1 | 9 → 12 | 85 ± 28 |
| S23U | ET S c32r8 | 3 | 23.11 ± 4.94 | 2 ± 0 | 0 | 13.19 ± 5.04 | 55 ± 21 | 0 | 6 → 10 | 112 ± 29 |
| S23U | ET M c16r4 | 3 | 13.21 ± 2.52 | 49 ± 17 | 0 | 9.32 ± 2.94 | 57 ± 21 | 0 | 10 → 13 | 119 ± 18 |
| S23U | ET M c32r8 | 3 | 17.72 ± 2.75 | 2 ± 0 | 0 | 11.17 ± 3.59 | 71 ± 22 | 1 | 8 → 11 | 157 ± 22 |
| S23U | ET L c16r4 | 3 | 10.51 ± 1.53 | 61 ± 17 | 0 | 8.01 ± 2.07 | 69 ± 21 | 1 | 11 → 14 | 148 ± 16 |
| S23U | ET L c32r8 | 3 | 12.86 ± 1.17 | 2 ± 0 | 1 | 9.34 ± 2.22 | 90 ± 18 | 1 | 9 → 11 | 213 ± 16 |
| S23U | Zipformer (shipping) | 3 | 9.24 ± 1.56 | 46 ± 12 | 0 | 8.36 ± 0.98 | 43 ± 5 | 0 | 12 → 13 | 31 ± 1 |
| S21 | ET S c16r4 | 3 | 8.94 ± 2.37 | 62 ± 14 | 1 | 8.10 ± 1.71 | 68 ± 17 | 1 | 13 → 13 | 137 ± 62 |
| S21 | ET S c32r8 | 3 | 12.35 ± 3.12 | 4 ± 0 | 1 | 9.97 ± 1.89 | 83 ± 24 | 1 | 10 → 13 | 214 ± 62 |
| S21 | ET M c16r4 | 3 | 6.36 ± 2.06 | 95 ± 25 | 1 | 5.64 ± 1.62 | 105 ± 29 | 1 | 20 → 21 | 259 ± 60 |
| S21 | ET M c32r8 | 3 | 6.91 ± 0.63 | 4 ± 0 | 2 | 7.32 ± 1.96 | 131 ± 37 | 2 | 15 → 18 | 315 ± 97 |
| S21 | ET L c16r4 | 3 | 3.81 ± 0.19 | 159 ± 5 | 2 | 3.51 ± 0.22 | 171 ± 10 | 2 | 38 → 39 | 381 ± 43 |
| S21 | ET L c32r8 | 3 | 4.98 ± 0.65 | 4 ± 0 | 2 | 4.49 ± 0.06 | 230 ± 2 | 3 | 28 → 37 | 611 ± 43 |
| S21 | Zipformer (shipping) | 3 | 2.69 ± 0.16 | 174 ± 8 | 3 | 2.50 ± 0.18 | 172 ± 17 | 3 | 52 → 49 | 101 ± 9 |
| S21 | ET S c16r4 [low-end proxy: cpus 0x07, t2] | 3 | 2.73 ± 0.02 | 191 ± 4 | 2 | 2.65 ± 0.03 | 211 ± 1 | 2 | 50 → 55 | 618 ± 34 |
| S21 | ET S c32r8 [low-end proxy: cpus 0x07, t2] | 3 | 3.17 ± 0.08 | 110 ± 10 | 3 | 3.45 ± 0.19 | 283 ± 17 | 3 | 48 → 48 | 904 ± 66 |
| S21 | ET M c16r4 [low-end proxy: cpus 0x07, t2] | 3 | 1.58 ± 0.06 | 345 ± 12 | 4 | 1.65 ± 0.12 | 362 ± 22 | 4 | 122 → 141 | 1122 ± 54 |
| S21 | ET M c32r8 [low-end proxy: cpus 0x07, t2] | 3 | 1.86 ± 0.12 | 310 ± 50 | 5 | 2.13 ± 0.15 | 517 ± 39 | 6 | 109 → 133 | 1592 ± 138 |
| S21 | ET L c16r4 [low-end proxy: cpus 0x07, t2] | 3 | 1.10 ± 0.04 | 504 ± 28 | 6 | 1.10 ± 0.03 | 562 ± 20 | 6 | 254 → 303 | 1942 ± 83 |
| S21 | ET L c32r8 [low-end proxy: cpus 0x07, t2] | 3 | 1.18 ± 0.08 | 363 ± 36 | 9 | 1.32 ± 0.06 | 882 ± 42 | 10 | 277 → 371 | 3025 ± 168 |
| S21 | Zipformer (shipping) [low-end proxy: cpus 0x07, default] | 3 | 1.14 ± 0.02 | 343 ± 19 | 4 | 1.07 ± 0.02 | 330 ± 14 | 4 | 196 → 197 | 343 ± 31 |

#### Live partials (owner clips, paced)

| Device | Lane | First partial ms (median over clips) | Clips with a partial | Clips with a corrected partial | Share of clip time showing partial text | Partial updates / clip | Final after audio end ms (median) |
|---|---|---|---|---|---|---|---|
| S23U | ET S c16r4 | 1440 ± 16 | 19/20 | 0/20 | 0.43 ± 0.01 | 2.6 ± 0.0 | 67 ± 19 |
| S23U | ET S c32r8 | 2751 ± 18 | 11/20 | 0/20 | 0.12 ± 0.00 | 0.7 ± 0.0 | 82 ± 17 |
| S23U | ET M c16r4 | 1649 ± 17 | 18/20 | 0/20 | 0.36 ± 0.00 | 2.4 ± 0.0 | 99 ± 7 |
| S23U | ET M c32r8 | 3067 ± 19 | 11/20 | 0/20 | 0.07 ± 0.00 | 0.6 ± 0.0 | 112 ± 18 |
| S23U | ET L c16r4 | 1862 ± 17 | 16/20 | 0/20 | 0.30 ± 0.00 | 2.2 ± 0.0 | 131 ± 3 |
| S23U | ET L c32r8 | 3497 ± 16 | 6/20 | 0/20 | 0.03 ± 0.00 | 0.3 ± 0.0 | 175 ± 20 |
| S23U | Zipformer (shipping) | 1128 ± 1 | 20/20 | n/a | 0.59 ± 0.00 | 5.8 ± 0.0 | 2 ± 0 |
| S23U | Android native | 1101 ± 0 | 20/20 | n/a | 0.56 ± 0.02 | 7.0 ± 0.3 | 77 ± 35 |
| S21 | ET S c16r4 | 1466 ± 14 | 19/20 | 0/20 | 0.42 ± 0.00 | 2.6 ± 0.0 | 129 ± 36 |
| S21 | ET S c32r8 | 2785 ± 18 | 11/20 | 0/20 | 0.11 ± 0.00 | 0.7 ± 0.0 | 159 ± 42 |
| S21 | ET M c16r4 | 1697 ± 21 | 18/20 | 0/20 | 0.34 ± 0.01 | 2.4 ± 0.0 | 224 ± 77 |
| S21 | ET M c32r8 | 3157 ± 14 | 11/20 | 0/20 | 0.06 ± 0.00 | 0.6 ± 0.0 | 297 ± 36 |
| S21 | ET L c16r4 | 1958 ± 7 | 16/20 | 0/20 | 0.28 ± 0.00 | 2.2 ± 0.0 | 382 ± 37 |
| S21 | ET L c32r8 | 3631 ± 27 | 6/20 | 0/20 | 0.02 ± 0.00 | 0.3 ± 0.0 | 513 ± 81 |
| S21 | Zipformer (shipping) | 1182 ± 11 | 20/20 | n/a | 0.56 ± 0.01 | 5.8 ± 0.0 | 55 ± 9 |
| S21 | Android native | 1101 ± 0 | 20/20 | n/a | 0.57 ± 0.01 | 7.1 ± 0.1 | 110 ± 19 |
| S21 | ET S c16r4 [low-end proxy: cpus 0x07, t2] | 1589 ± 3 | 19/20 | 0/20 | 0.38 ± 0.00 | 2.6 ± 0.0 | 548 ± 11 |
| S21 | ET S c32r8 [low-end proxy: cpus 0x07, t2] | 2993 ± 13 | 11/20 | 0/20 | 0.08 ± 0.00 | 0.7 ± 0.0 | 667 ± 24 |
| S21 | ET M c16r4 [low-end proxy: cpus 0x07, t2] | 1944 ± 12 | 18/20 | 0/20 | 0.28 ± 0.00 | 2.4 ± 0.0 | 1065 ± 43 |
| S21 | ET M c32r8 [low-end proxy: cpus 0x07, t2] | 3529 ± 26 | 11/20 | 0/20 | 0.03 ± 0.00 | 0.6 ± 0.0 | 1245 ± 70 |
| S21 | ET L c16r4 [low-end proxy: cpus 0x07, t2] | 2318 ± 30 | 16/20 | 0/20 | 0.20 ± 0.00 | 2.2 ± 0.0 | 1631 ± 114 |
| S21 | ET L c32r8 [low-end proxy: cpus 0x07, t2] | 4251 ± 39 | 6/20 | 0/20 | 0.00 ± 0.00 | 0.3 ± 0.0 | 2365 ± 175 |
| S21 | Zipformer (shipping) [low-end proxy: cpus 0x07, default] | 1377 ± 4 | 20/20 | n/a | 0.49 ± 0.00 | 5.8 ± 0.0 | 235 ± 20 |

#### Load and memory (in-process engines; fresh process per lane and repeat)

| Device | Lane | Load ms | Peak PSS Δ MiB | VmHWM MiB |
|---|---|---|---|---|
| S23U | ET S c16r4 | 175 ± 16 | 110 ± 2 (max 130) | 215 ± 3 |
| S23U | ET S c32r8 | 159 ± 11 | 114 ± 0 (max 130) | 217 ± 1 |
| S23U | ET M c16r4 | 251 ± 86 | 158 ± 1 (max 181) | 262 ± 1 |
| S23U | ET M c32r8 | 211 ± 6 | 164 ± 1 (max 189) | 268 ± 1 |
| S23U | ET L c16r4 | 363 ± 126 | 221 ± 2 (max 246) | 325 ± 3 |
| S23U | ET L c32r8 | 301 ± 19 | 229 ± 0 (max 256) | 333 ± 1 |
| S23U | Zipformer (shipping) | 1722 ± 172 | 202 ± 2 (max 217) | 303 ± 2 |
| S21 | ET S c16r4 | 265 ± 161 | 114 ± 2 (max 134) | 233 ± 3 |
| S21 | ET S c32r8 | 258 ± 164 | 118 ± 2 (max 138) | 238 ± 2 |
| S21 | ET M c16r4 | 440 ± 281 | 162 ± 2 (max 187) | 281 ± 3 |
| S21 | ET M c32r8 | 415 ± 91 | 169 ± 2 (max 195) | 289 ± 2 |
| S21 | ET L c16r4 | 682 ± 136 | 227 ± 0 (max 255) | 347 ± 1 |
| S21 | ET L c32r8 | 720 ± 251 | 237 ± 1 (max 261) | 357 ± 1 |
| S21 | Zipformer (shipping) | 5661 ± 1111 | 207 ± 2 (max 222) | 327 ± 2 |
| S21 | ET S c16r4 [low-end proxy: cpus 0x07, t2] | 1256 ± 150 | 117 ± 0 (max 128) | 238 ± 0 |
| S21 | ET S c32r8 [low-end proxy: cpus 0x07, t2] | 1175 ± 52 | 122 ± 0 (max 134) | 243 ± 0 |
| S21 | ET M c16r4 [low-end proxy: cpus 0x07, t2] | 1847 ± 247 | 167 ± 0 (max 184) | 287 ± 1 |
| S21 | ET M c32r8 [low-end proxy: cpus 0x07, t2] | 1754 ± 15 | 173 ± 0 (max 192) | 294 ± 1 |
| S21 | ET L c16r4 [low-end proxy: cpus 0x07, t2] | 2407 ± 236 | 233 ± 0 (max 251) | 348 ± 12 |
| S21 | ET L c32r8 [low-end proxy: cpus 0x07, t2] | 2770 ± 251 | 243 ± 0 (max 260) | 364 ± 1 |
| S21 | Zipformer (shipping) [low-end proxy: cpus 0x07, default] | 19655 ± 783 | 214 ± 1 (max 215) | 334 ± 1 |

### Paired per-clip comparisons (normalised finals; exact sign test, ties dropped; identical on both phones for the in-process engines)

| A vs B | Owner edits A / B | Owner better / worse / tied (p) | Breadth edits A / B | Breadth better / worse / tied (p) |
|---|---|---|---|---|
| ET M c16r4 vs ET S c16r4 | 37 / 48 | 10 / 3 / 7 (0.09) | 47 / 71 | 20 / 4 / 76 (0.002) |
| ET M c16r4 vs ET S c32r8 | 37 / 43 | 7 / 3 / 10 (0.34) | 47 / 72 | 20 / 3 / 77 (0.0005) |
| ET M c16r4 vs ET M c32r8 | 37 / 38 | 2 / 1 / 17 (1.0) | 47 / 42 | 1 / 6 / 93 (0.13) |
| ET L c16r4 vs ET M c16r4 | 31 / 37 | 6 / 3 / 11 (0.51) | 49 / 47 | 9 / 9 / 82 (1.0) |
| ET L c32r8 vs ET M c16r4 | 28 / 37 | 7 / 1 / 12 (0.07) | 43 / 47 | 11 / 6 / 83 (0.33) |
| ET M c16r4 vs Android native (S21 r1) | 37 / 44 | 8 / 4 / 8 (0.39) | 47 / 61 | 20 / 10 / 70 (0.10) |
| ET S c16r4 vs Android native (S21 r1) | 48 / 44 | 8 / 8 / 4 (1.0) | 71 / 61 | 10 / 21 / 69 (0.07) |
| ET M c16r4 vs Zipformer | 37 / 69 | 15 / 2 / 3 (0.002) | 47 / 283 | 89 / 2 / 9 (3e-24) |

## What the evidence says

1. **Accuracy does not depend on device or threads.** Every in-process lane (ExecuTorch, Zipformer) produced
   byte-identical owner finals on S23U, S21 and the low-end proxy, in every repeat. Order on the owner set: L c32r8 19.2% < L c16r4 21.2% < M c16r4
   25.3% ≈ M c32r8 26.0% < S c32r8 29.5% < Android native 30.1–30.8% error-free (30.1–34.9% with two error repeats) < S c16r4 32.9% ≪
   Zipformer 47.3%. On 146 words only M/L vs Zipformer is significant; the breadth set (diagnostic, 638 words)
   separates M from S clearly (7.4% vs 11.1%, p = 0.002) and puts M ≈ L.
2. **Tier choice: c16r4 everywhere.** c32r8 buys nothing measurable in accuracy for M (37 vs 38 edits) and costs
   live text: first partial ~3.1 s vs ~1.7 s, and partials on 11/20 vs 18/20 owner clips. No tier produced a
   PCEC-corrected partial on any owner clip; users see raw lowercase partials, then the corrected final.
   Zipformer and Android native show the first partial ~0.5 s earlier than M c16r4 and on 20/20 clips.
3. **Real time.** On the S23U and S21 every ExecuTorch lane keeps up with margin (67 s stream RTFx ≥ 3.5,
   ≤ 3 queued 100 ms chunks, mean lag rising by ≤ 9 ms from first to last fifth); M c16r4 on the S21: RTFx
   5.6 ± 1.6, p95 lag 105 ms, final 0.26 s after audio end. ExecuTorch S/M load in 0.16–0.44 s (mean) vs
   Zipformer's 1.7 s (S23U) / 5.7 s (S21).
4. **Low-end proxy (2 ET threads).** M c16r4 still runs faster than real time (RTFx 1.65 ± 0.12 on the 67 s stream) with
   bounded but visible lag: p95 362 ms, up to 4 queued chunks, mean lag 122 → 141 ms from first to last fifth of
   the stream, final ~1.1 s after audio end, load 1.8 s. S c16r4 has 1.6× the headroom (RTFx 2.65, p95 211 ms,
   final 0.6 s). L c16r4 is marginal (RTFx 1.10, lag 254 → 303 ms and growing, final 1.9 s) and L c32r8 worse.
   **The shipping Zipformer on the same proxy is no better off than ET M**: RTFx 1.07, p95 330 ms, 4 queued
   chunks, 19.7 s load, at 47% WER.
5. **Memory** (peak PSS Δ, per process): S ~115 MiB, M ~160–170, L ~225–245, Zipformer ~205–215. M costs ~50 MiB
   more than S.

## Recommendation

| `HardwareTier` | Default | User option | Not offered |
|---|---|---|---|
| FLAGSHIP (S23U class) | **M c16r4** | **L c16r4** (fastest live text for L; L c32r8 gains 3 owner edits, not significant, and shows partials on 6/20 clips) | S |
| MID_RANGE (S21 class) | **M c16r4** | **L c16r4** (RTFx 3.5, ≤2 queued chunks, final ~0.4 s after audio end on the S21) | S |
| LOW_POWER (proxy only, **2 ET threads**) | **M c16r4**, then the fallback ladder below | none (L is at RTFx ~1.1 with growing lag) | S, see the argument below |

**Thread count is part of the LOW_POWER decision.** The LOW_POWER row is a 2-thread result on the proxy. Real budget SoCs may run the engine at 4 threads, and oversubscribing a 4-little-core SoC could push M below the 1.65× measured here [INFERENCE: not measured]. Before LOW_POWER ships, measure M c16r4 at 3 and 4 threads on the proxy (variants already defined in `scripts/lanes-s21-threads.txt`, not run in this PR), and record the thread count in the tier decision.

**LOW_POWER fallback ladder** (for the runtime guard):
1. **M c16r4** (default).
2. **M c32r8**, the same model and session with a longer window. On the proxy it runs at RTFx 2.13 ± 0.15 (vs 1.65 for c16r4) at 26.03% owner WER (vs 25.34%; 38 vs 37 edits, p = 1.0) and 6.58% breadth (vs 7.37%). The cost is live text: first partial ~3.1 s and partials on 11/20 clips, not 18/20.
3. **Android native**, out of process, with the error caveat above (error-free 30.1–30.8% owner WER).
4. S is not a rung: it is not measured as a fallback, and the case for it is argued below.

**Guard (runtime, LOW_POWER only).** Move down one rung when either trigger holds:
- RTFx < 1.2 over the last 20 s of audio. Measured proxy RTFx: L c16r4 1.07–1.10, L c32r8 1.24–1.32; M c16r4 lowest repeat 1.52, M c32r8 lowest 2.02.
- At least 8 queued 100 ms chunks for at least 10 s. Measured proxy worst-repeat queues: M c16r4 4, M c32r8 6, L c16r4 6, L c32r8 10.

The earlier guard (p95 lag above 0.5 s or 5 queued chunks) is withdrawn: it would trip on M c32r8 on the proxy (p95 517 ms, worst queue 6), which runs at RTFx 2.13. Both new thresholds are chosen from three repeats per configuration, not from a UX study, so they are provisional and must be checked on a real LOW_POWER device.

**Should S be offered at all? Argued both ways.**

*For S (on LOW_POWER):* M's headroom on the proxy is thin: RTFx 1.65, 0.36 s p95 lag and ~1.1 s from end of speech to final. A real budget phone can be slower than three A55 cores at 2.21 GHz with the S21's memory system (most use A55 at ≤2.0 GHz [INFERENCE]), and could push M below real time where S (RTFx 2.65, 0.6 s to final) would still keep up. S also uses ~50 MiB less on phones with <6 GB RAM, which is exactly the RAM-defined LOW_POWER tier, while the app's LLM may compete for memory.

*Against S:* the owner's condition is not met by this evidence. On the slowest configuration measured, M kept up (RTFx > 1.5 on every repeat, lag bounded), so there is no measured device class where M can't keep up. And S does not clearly beat the alternatives there. Its owner WER (32.9%) is worse than Android native's error-free 30.1–30.8% (48 vs 44–45 edits; the paired test on S21 r1 is 8/8 per-clip, p = 1.0), and it is clearly worse than M on breadth (11.1% vs 7.4%). Android native needs no download or local compute. A third model adds download, licence notices, test lanes and maintenance for a hypothetical device.

**Decision recommended: do not ship S now.** Ship M c16r4 as the default on every tier and L c16r4 as an option on FLAGSHIP/MID_RANGE. Add the LOW_POWER fallback ladder and guard above to the integration, with the thread count fixed in the tier decision. Revisit S only if a real LOW_POWER device, not this proxy, shows M below real time after the ladder.

## Caveats

- **Low-end is a proxy** (above). It bounds CPU throughput only; it does not model a budget phone's memory
  bandwidth, thermal throttling or a smaller RAM budget.
- **Thread count.** Every low-end-proxy ExecuTorch number is a 2-thread result. The shipping engine defaults to 4 threads (`Asr4AllEngine.DEFAULT_THREADS = 4` on `feat/2004-asr4all-executorch-cleanroom`). The 3- and 4-thread proxy variants are defined in `scripts/lanes-s21-threads.txt` but have not been run.
- **Android native** needed `EXTRA_SEGMENTED_SESSION` to return any result with a file audio source (both Google recognisers on these phones returned an empty `onResults` bundle otherwise). It therefore transcribes the whole clip rather than stopping at the first endpoint like the shipping controller, and runs out of process, so load, memory, RTFx and lag are not attributable. Two repeats hit a recogniser error: S23U r3 (A-08, 11 of 11 words deleted) and S21 r2 (A-02, 3 of 3 words deleted). The harness that produced these reports discarded segments already received when `onError` fired, so those two WER values reflect the harness, not the recogniser. The `NativeLane.kt` change (keep the segments on error) is in this PR, but **the reports here were not re-run with it**. The table therefore shows native both with all repeats and error-free. Error-free: S23U 30.82% (2 repeats, 45 edits each), S21 30.14% (2 repeats, 44 each). With the error repeats: S23U 30.82–34.93%, S21 30.14–31.51%. It runs offline here (on-device model) and its accuracy can change with Google app updates.
- **Zipformer** finals are taken at EOF for paired WER, like asr4all; the shipping loop would have stopped at an
  endpoint before EOF on 1 of 20 owner clips.
- **Speech-end (endpoint) latency was not measured**; "final after audio end" is EOF-flush time, not endpoint
  latency.
- **First partial** is wall time from stream start and includes the tier window (c16r4 1.4 s, c32r8 2.7 s).
- Repeats: timing sd is large on the S23U for S/M (thermal state differs between sessions); worst-repeat columns
  are reported for queueing.
- **Traceability.** The 148 runs in this directory predate the `app_apk_sha256`, `test_apk_sha256` and `private_et_driver_sha256` fields, which are therefore null in `per-run-metrics.json`. The harness now writes all three into every report (`BenchmarkTest.kt`, `probe/build.gradle.kts`). The SHA-256 values of the private driver files on the measurement host are `EtAsr4allDecoder.kt` `9b11efc5b62d4bad553631432cf317467a5aff587060391381577e5d5ccb21fc` and `Asr4allEtGeometry.kt` `07d8ef370961811af1d294591b5d5ac89a1a2bc84251e4e5a9f0b1a8f7392ebe`. These are the files as they are now; they are not proven to be the files compiled into the earlier sessions.
