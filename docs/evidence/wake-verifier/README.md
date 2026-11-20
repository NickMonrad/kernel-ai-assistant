# Hey Jandal low-confidence verifier: which engine? (#1045, input to #1982)

Benchmark-only spike. It changes no production code, model, threshold or download. Owner and voice-cloned
audio, transcripts and per-ring verdicts stayed in a private directory outside Git and in app-private or
`/data/local/tmp` storage that the device script deletes. This directory holds the harness and aggregates only.

- Harness: [`harness/`](harness/) (see [Reproduce](#reproduce)).
- Aggregates: [`results/host-aggregates.json`](results/host-aggregates.json) and the device files in [`results/`](results/).

Status: host comparison complete for all six options; device confirmation on the S21 (Whisper, asr4all M in both
tiers, KWS; all 1,118 rings) and a partial Whisper run on the S23U (826 rings, stopped when the phone was found
PIN-locked). Device verdicts agree with the host on 98.8–99.6% of rings.

## Answer

| | Verdict |
|---|---|
| **Verifier to use** | **sherpa-onnx open-vocabulary keyword spotter** (`sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01`, int8, Apache-2.0) with the single keyword `HEY JANDAL`. Highest genuine acceptance of every option on the host and on the S21 (S21: 61.6% at the default threshold vs Whisper 45.6% on the same rings), every owner natural ring accepted, no background-speech false accepts, ~5 MB download instead of ~104 MB, and on the S21 ~212 ms per check vs Whisper's ~507 ms with about half the memory. It runs on the sherpa-onnx 1.13.0 runtime the app already ships (`KeywordSpotter` is in the pinned AAR). Its one weakness here: it accepts `Okay Jandal` (1.0–1.2% near-miss FA vs Whisper's 0.7%). It needs its own implementation issue, with acoustic device validation, before it replaces Whisper. |
| **Interim verifier (#1982)** | **Whisper tiny.en, as #1982 already says.** The best option that needs no new matcher forms and no tuning on this corpus; already wired and proven on device. Remove Zipformer (0% acceptance here). |
| **asr4all (M or L, either tier)** | **Not as a verifier.** It does not know the word "Jandal": with the production matcher it accepts 6–8% of genuine rings on the host and 9–10% on the S21. Reaching ~50–60% needs extra name spellings learnt from this same corpus (`jandle`, `gendal`, `janel`, `jand`, …), which also raises near-miss false accepts to 2–4%. The c16r4 and c32r8 tiers are within noise of each other; L is not better than M on the owner's recording. On the S21 an M check takes ~384–418 ms (Whisper ~507 ms) with ~170–179 MiB peak PSS in its own process (Whisper adds ~143 MiB to the app), so it is not materially cheaper. Its only real advantage, no extra download when M is the STT model, does not outweigh the recognition gap. |
| **No STT verification, one tuned threshold** | **Not viable.** Near-miss phrases (`Hey Handle`, `Hey Jungle`, `Hey Kendall`, …) reach detector scores of 0.93, as high as genuine ones. No single threshold separates them: at 0.90 the detector still passes 6.9% of near-misses while dropping genuine acceptance to 55.5%. |
| **Bigger finding** | The verifier only sees the 0.50–0.65 band. Every near-miss false accept on this corpus (12.9% of near-miss items) came through the ≥ 0.65 fast path, which no verifier touches. Verifying every candidate ≥ 0.50 removes them all on this corpus (KWS: 0/700) but also costs genuine acceptance (49.6–55.4% vs 71–72%). That is a policy change for its own issue, and only a cheap verifier such as KWS makes it affordable. |

### What #1982 should become

Keep #1982's Zipformer removal and its Whisper-only verifier (items 1–5) unchanged. This spike gives no reason to
make asr4all the verifier, so #1982 should not wait for, or depend on, the asr4all work for wake verification.
Add two follow-ups instead:

1. **New issue: replace the Whisper verifier with sherpa-onnx KWS.** Scope: a `KeywordSpotter`-based
   `transcribeBlocking` replacement (or a dedicated `verifyWakeWindow` path) with the pinned 3.3M int8 model,
   pre-tokenised `▁HE Y ▁JA ND AL` keyword (no `bpe.model` needed at run time), threshold chosen from acoustic
   device runs rather than this digital corpus, then drop `SHERPA_WHISPER_TINY_EN_*` from the Hey Jandal
   download (saves ~99 MB; #1931). Acceptance: the existing S21/S23U acoustic wake matrix, natural owner audio,
   no worse false-accept rate.
2. **New issue (decision): verify every candidate, not only the low band**, and review the production
   matcher's `handel`/`handal` forms (they let `Hey Handle` through every STT engine) and the bare `a` prefix
   (`I lost a jandal` passes Whisper). Both pre-date this spike (#1439 flagged `handel`).

## Options and corpus

| Option | Model / config | Extra download for Hey Jandal | Matching |
|---|---|---|---|
| (a) Whisper tiny.en (current) | catalogue `SHERPA_WHISPER_TINY_EN_*`, sherpa-onnx 1.13.0, app config (2 threads, greedy, en/transcribe) | 103.6 MB (12.9 + 89.9 + 0.8 MB) | transcript → `containsWakePhrase` |
| (b) Zipformer (current fallback) | catalogue `SHERPA_STT_*` 2023-02-21, app online config | 127.8 MB, or 0 while it is still the queued STT model | same |
| (c) asr4all M via ExecuTorch | FUTO `asr4all-m@7d6e9145`, `executorch/xnnpack_int8/asr_encoder.pte` (`d08648b9…`), driven by FUTO's `asr4all_executorch.py` (`0918a4dd…`) imported at run time | 0 when M is the STT model (60.0 MB otherwise) | PCEC output (raw CTC also scored) → same |
| (d) asr4all tiers | M and L × `stream_c16r4` / `stream_c32r8` | L: 0 when L is the STT model (101.0 MB otherwise) | same |
| (e) dedicated keyword verifier | sherpa-onnx open-vocabulary KWS, gigaspeech 3.3M int8 (archive `f170013b…`), keyword `HEY JANDAL`; threshold/boost 0.25/1.0 (default), 0.15/1.5, 0.08/2.0 | 5.3 MB (encoder 4.8 + decoder 0.3 + joiner 0.2 MB + tokens) | keyword hit; no transcript matching |
| (f) no STT verification | production openWakeWord detector only, one threshold | 0 | — |

Other dedicated verifiers looked at, not run (they need training or enrolment): openWakeWord's
[custom verifier models](https://github.com/dscripka/openWakeWord/blob/main/docs/custom_verifier_models.md)
(a per-speaker logistic regression on the same embeddings; needs ≥3 enrolment clips per user and a retrain per
environment), a second compact KWS model such as microWakeWord (#1401; needs a training project), and speaker
verification (answers *who* spoke, not *what*). The sherpa-onnx KWS needs neither training nor enrolment.

Corpus (all 16 kHz mono; each ring is the exact 3 s window the detector would hand over):

| Class | Items | Unique utterances | Sources |
|---|---|---|---|
| Genuine "Hey Jandal" | 625 | 125 | owner's natural recording (1), owner-voice Qwen3-TTS clones from #1736 (4), five public Piper voices × 12 prompts × 2 speeds (120) |
| Near-miss | 700 | 140 | five Piper voices × 28 prompts: rhymes (`Hey Sandal`, `Hey Handle`, `Hey Kendall`, …), other wake words, and NZ speech that contains "jandal" (`I lost a jandal at the beach.`, `Okay Jandal`, `Jandal.`) |
| Background speech | 118 (750 rings) | 118 | owner decision set (20 real owner commands, several addressing "Jandal"), #1984 voice-cloned breadth set (97), #1439 non-wake probe; a ring every 0.5 s |
| Non-speech | 33 | 33 | music, room tone, silence |

Every genuine and near-miss utterance is evaluated under five conditions: clean, −12 dB, synthetic room reverb
(RT60 0.5 s, 0 dB DRR), music at 10 dB SNR and three-talker babble at 10 dB SNR. Each stream has room tone
before and after the phrase. Prompts are in [`harness/phrases.py`](harness/phrases.py).

How rings are cut: the production detector (the app's `melspectrogram.onnx`, `embedding_model.onnx`,
`hey_jandal.onnx`, fed exactly as `OnnxWakeWordDetector` does) runs over each stream. The **candidate ring**
is the 3 s ring at the first frame ≥ 0.50 (or at the peak frame if none), as `extractPcmSnapshot` would
produce. The first crossing often comes before the phrase has finished, so the tables also give acceptance
on the **peak ring** (ring at the highest-scoring frame).

Corpus limits, stated plainly: there is one natural owner utterance (five conditions) and four owner-voice
clones; the owner-voice clone generator (Qwen3-TTS) could not be run on this host this time, so the near-miss
set is Piper voices only. Piper voices pronounce "Jandal" in US/UK English, which hurts every engine; the
owner rows are the closest to real use. Digital mixing is not the phone's acoustic path: on these clean
streams the detector usually scores ≥ 0.65, whereas on device (#1439) genuine candidates often land at
0.59–0.63. KWS thresholds and asr4all's extra name forms were chosen on this same corpus, so their figures are
optimistic.

## Host results

Host: x86_64, 16 CPUs, Python 3.12, sherpa-onnx 1.13.0, ExecuTorch 1.5.1 + torch 2.14.0 (CPU), onnxruntime
1.32 for the detector. Accuracy passes ran in parallel; timings come from a separate quiet pass over 200
full-length rings per engine. The host was shared with other jobs, so the timings are indicative; the device
tables are the ones to quote.

### Accuracy per verifier (every candidate ring sent to the verifier)

Matchers: `strict` = `hey jandal`; `prod` = production `containsWakePhrase`; `joined` = `prod` with spaces
ignored (`hey jan dal`); `fuzzy` = any word within edit distance 2 of `jandal` after a wake prefix;
`prod+forms` = `prod` plus the name spellings that engine produced on at least two genuine rings (in-sample).

| Option | Genuine TA (first crossing) | Genuine TA (peak ring) | Owner natural (first / peak) | Owner-voice clone #1736 (first / peak) | Piper voices (first) | Near-miss FA | Background-speech rings FA | Non-speech FA |
|---|---|---|---|---|---|---|---|---|
| `whisper-tiny-en:strict` | 32.5% (203/625) | 33.1% (207/625) | 1/5 / 1/5 | 1/20 / 1/20 | 33.5% (201/600) | 0.0% (0/700) | 0.0% (0/750) | 0/33 |
| `whisper-tiny-en:prod` | 38.4% (240/625) | 39.7% (248/625) | 2/5 / 4/5 | 7/20 / 8/20 | 38.5% (231/600) | 0.9% (6/700) | 0.8% (6/750) | 0/33 |
| `whisper-tiny-en:joined` | 40.2% (251/625) | 41.9% (262/625) | 2/5 / 4/5 | 7/20 / 8/20 | 40.3% (242/600) | 0.9% (6/700) | 1.2% (9/750) | 0/33 |
| `whisper-tiny-en:fuzzy` | 43.8% (274/625) | 44.8% (280/625) | 2/5 / 4/5 | 7/20 / 8/20 | 44.2% (265/600) | **13.9% (97/700)** | 0.8% (6/750) | 0/33 |
| `zipformer:prod` | 0.0% (0/625) | 0.2% (1/625) | 0/5 / 0/5 | 0/20 / 0/20 | 0.0% (0/600) | 0.1% (1/700) | 0.0% (0/750) | 0/33 |
| `zipformer:fuzzy` | 5.1% (32/625) | 15.5% (97/625) | 0/5 / 0/5 | 0/20 / 1/20 | 5.3% (32/600) | 2.9% (20/700) | 0.4% (3/750) | 0/33 |
| `asr4all-m-stream_c16r4:prod` | 7.8% (49/625) | 6.1% (38/625) | 1/5 / 0/5 | 7/20 / 7/20 | 6.8% (41/600) | 2.3% (16/700) | 0.4% (3/750) | 0/33 |
| `asr4all-m-stream_c16r4:prod+forms` | 50.1% (313/625) | 49.4% (309/625) | 4/5 / 2/5 | 7/20 / 10/20 | 50.3% (302/600) | 2.9% (20/700) | 0.5% (4/750) | 0/33 |
| `asr4all-m-stream_c16r4:fuzzy` | 50.9% (318/625) | 50.2% (314/625) | 4/5 / 2/5 | 7/20 / 10/20 | 51.2% (307/600) | **18.3% (128/700)** | 0.5% (4/750) | 0/33 |
| `asr4all-m-stream_c32r8:prod` | 6.7% (42/625) | 5.1% (32/625) | 0/5 / 0/5 | 7/20 / 7/20 | 5.8% (35/600) | 1.3% (9/700) | 0.1% (1/750) | 0/33 |
| `asr4all-m-stream_c32r8:prod+forms` | 50.9% (318/625) | 49.3% (308/625) | 2/5 / 2/5 | 7/20 / 8/20 | 51.5% (309/600) | 2.1% (15/700) | 0.3% (2/750) | 0/33 |
| `asr4all-l-stream_c16r4:prod+forms` | 55.5% (347/625) | 55.2% (345/625) | 0/5 / 0/5 | 7/20 / 8/20 | 56.7% (340/600) | 3.1% (22/700) | 0.5% (4/750) | 0/33 |
| `asr4all-l-stream_c32r8:prod+forms` | 54.2% (339/625) | 54.2% (339/625) | 0/5 / 2/5 | 6/20 / 6/20 | 55.5% (333/600) | 2.3% (16/700) | 0.7% (5/750) | 0/33 |
| `kws-t0.25-s1.0` | 58.2% (364/625) | 60.2% (376/625) | **5/5 / 5/5** | 8/20 / 8/20 | 58.5% (351/600) | 0.9% (6/700) | **0.0% (0/750)** | 0/33 |
| `kws-t0.15-s1.5` | 64.2% (401/625) | 65.9% (412/625) | **5/5 / 5/5** | 10/20 / 11/20 | 64.3% (386/600) | 1.0% (7/700) | **0.0% (0/750)** | 0/33 |
| `kws-t0.08-s2.0` | 67.7% (423/625) | 68.5% (428/625) | **5/5 / 5/5** | 11/20 / 14/20 | 67.8% (407/600) | 1.0% (7/700) | **0.0% (0/750)** | 0/33 |

Near-miss false accepts by prompt: Whisper `prod` — `I lost a jandal` 6 (the bare `a` prefix); asr4all M
`prod` — `Hey Handle` 16 or 9 (via the existing `handel` form); asr4all `prod+forms` adds `I lost a jandal`
and, for L, `Hey Janelle`/`Hey Vandal`; KWS — `Okay Jandal` 6–7 and nothing else. Whisper's own in-sample
spellings would include `randall`, which shows why growing the form list from data is unsafe. asr4all's raw
CTC text scores within 3 points of its PCEC text (`m-c16r4/raw:prod+forms` 52.6%, `m-c32r8/raw` 53.1%).

Genuine acceptance by condition:

| Option | clean | −12 dB | reverb | music 10 dB | babble 10 dB |
|---|---|---|---|---|---|
| `whisper-tiny-en:prod` | 54.4% | 51.2% | 29.6% | 56.0% | 0.8% |
| `zipformer:prod` | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% |
| `asr4all-m-stream_c16r4:prod` | 8.8% | 8.8% | 6.4% | 15.2% | 0.0% |
| `asr4all-m-stream_c16r4:prod+forms` | 64.8% | 66.4% | 44.0% | 67.2% | 8.0% |
| `asr4all-m-stream_c32r8:prod+forms` | 65.6% | 66.4% | 45.6% | 68.0% | 8.8% |
| `asr4all-l-stream_c16r4:prod+forms` | 69.6% | 70.4% | 57.6% | 72.0% | 8.0% |
| `kws-t0.25-s1.0` | 72.8% | 72.0% | 38.4% | 72.8% | 35.2% |
| `kws-t0.08-s2.0` | 84.0% | 80.8% | 48.0% | 80.0% | 45.6% |

(125 items per cell. Whole table for every matcher: `engine_level.*.genuine_by_condition` in the JSON.)

### Partial or fuzzy matching, per engine

Why each engine's genuine candidate transcripts miss the production matcher (625 rings each):

| Engine output | Matches `prod` | Split (`jan dal`) | Name 1 edit away | Name 2 edits away | No wake name | Empty | Extra spellings seen ≥ 2× |
|---|---|---|---|---|---|---|---|
| `whisper-tiny-en` | 240 | 11 | 10 | 18 | 346 | 0 | chandal, janal, jandle, janzel, jondal, randall |
| `zipformer` | 0 | 0 | 2 | 30 | 520 | 73 | jand |
| `asr4all-m-stream_c16r4` | 49 | 0 | 0 | 269 | 304 | 3 | gendal, jand, jandle, janel, jendel |
| `asr4all-m-stream_c32r8` | 42 | 0 | 6 | 276 | 299 | 2 | gandel, gendal, jand, jandle, janel, jendal |
| `asr4all-l-stream_c16r4` | 37 | 2 | 5 | 308 | 261 | 12 | dandal, jandle, janel |
| `asr4all-l-stream_c32r8` | 31 | 6 | 4 | 308 | 261 | 15 | dandal, jand, jandle, janel |

- **Whisper**: the existing `prod` forms (`hi` prefix, `jando`, from #1439) are already needed and enough;
  spacing tolerance (`joined`) adds 1.8 points; fuzzy matching adds 5 points but multiplies near-miss false
  accepts by 15. Recommendation: keep `prod`, add nothing.
- **Zipformer**: nothing short of fuzzy matching helps, and fuzzy still accepts only 5%.
- **asr4all**: unusable without new name spellings (43% of its genuine output is 2 edits from `jandal`).
  Fuzzy matching and the in-sample list give the same acceptance; the list keeps near-miss FA at 2–3%, fuzzy
  matching takes it to 18%. FUTO's `set_dictionary` contextual biasing would be the proper fix, but the
  `word_spotter.py` module it needs is not part of the release.
- **KWS**: no transcript matching at all; the keyword graph replaces it.

### System level: the production dual-threshold loop

≥ 0.65 accepts without verification; 0.50–0.65 verifies that frame's ring; a rejection lets later frames try.

| Option | Genuine accepted | Near-miss accepted | Background accepted | Genuine accepted via the verifier |
|---|---|---|---|---|
| `zipformer:prod` | 70.6% (441/625) | 12.9% (90/700) | 0/118 | 0 |
| `whisper-tiny-en:prod` | 71.0% (444/625) | 12.9% (90/700) | 0/118 | 28 |
| `asr4all-m-stream_c16r4:prod` | 70.6% (441/625) | 13.1% (92/700) | 0/118 | 4 |
| `asr4all-m-stream_c16r4:prod+forms` | 71.2% (445/625) | 13.1% (92/700) | 0/118 | 35 |
| `asr4all-l-stream_c16r4:prod+forms` | 71.8% (449/625) | 13.3% (93/700) | 0/118 | 39 |
| `kws-t0.25-s1.0` | 71.8% (449/625) | 12.9% (90/700) | 0/118 | 44 |
| `kws-t0.08-s2.0` | 72.2% (451/625) | 12.9% (90/700) | 0/118 | 48 |

Alternative policy, verify every candidate ≥ 0.50 (no unverified fast path):

| Option | Genuine accepted | Near-miss accepted | Background accepted |
|---|---|---|---|
| `whisper-tiny-en:prod` | 37.1% (232/625) | 0.0% (0/700) | 0/118 |
| `asr4all-m-stream_c16r4:prod+forms` | 44.8% (280/625) | 1.0% (7/700) | 0/118 |
| `asr4all-l-stream_c32r8:prod+forms` | 47.4% (296/625) | 0.7% (5/700) | 0/118 |
| `kws-t0.25-s1.0` | 49.6% (310/625) | 0.0% (0/700) | 0/118 |
| `kws-t0.08-s2.0` | 55.4% (346/625) | 0.0% (0/700) | 0/118 |

(f) Detector only, one threshold on the maximum score per item (%):

| Threshold | 0.30 | 0.40 | 0.50 | 0.60 | 0.65 | 0.70 | 0.80 | 0.85 | 0.90 |
|---|---|---|---|---|---|---|---|---|---|
| Genuine | 76.0 | 74.1 | 72.5 | 70.9 | 70.6 | 69.1 | 65.9 | 62.6 | 55.5 |
| Near-miss | 16.6 | 15.6 | 14.4 | 13.3 | 12.9 | 12.0 | 10.6 | 8.4 | 6.9 |
| Background speech | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |

Highest score on any negative: 0.933 (near-miss); background speech and non-speech never exceeded 0.0014.

### Cost on the host

| Engine | Threads | Load ms | First call ms | Warm p50 ms | Warm p95 ms | RSS Δ after load MiB | Peak RSS Δ MiB |
|---|---|---|---|---|---|---|---|
| Whisper tiny.en | 2 | 527 | 209 | 149 | 185 | 136 | 234 |
| Zipformer | 2 | 1536 | 148 | 129 | 140 | 173 | 176 |
| asr4all M c16r4 (ET) | 4 | 164 | 166 | 100 | 107 | 0 (mmap) | 49 |
| asr4all M c32r8 (ET) | 4 | 101 | 140 | 95 | 118 | 0 (mmap) | 69 |
| asr4all L c16r4 (ET) | 4 | 194 | 462 | 174 | 237 | 51 | 153 |
| asr4all L c32r8 (ET) | 4 | 154 | 220 | 160 | 187 | 52 | 154 |
| KWS 3.3M int8 | 2 | 473 | 47 | 38 | 39 | 40 | 44 |

Per 3 s ring. ExecuTorch threads are 4, its Android default (cores/2) on both phones; sherpa-onnx uses the
app's 2. asr4all would add no memory only if the verifier shared the STT engine's loaded instance; a separate
verifier instance costs the figure shown.

## Device confirmation

Device lanes replay the same candidate rings (1,118: genuine, near-miss and non-speech under clean, reverb
and music, plus every third background-speech ring) into each verifier. Whisper runs through the production
path (the debug `WakeVerifierProbeReceiver`: `verifyWakeWindow` → `transcribeBlocking` → Whisper →
`containsWakePhrase`) on the debug APK built from `main@00025895`; two runs per ring, the gap between them
being the warm verification time. asr4all runs in the isolated #1988 ExecuTorch probe; KWS runs as
`KwsProbe` under `app_process` with the app's own sherpa-onnx 1.13.0 AAR and onnxruntime-android 1.24.3.
No lane uses the microphone or the screen.

### S23U (`RFCW11265XH`), Whisper only, partial

The run was stopped at 826 of 1,118 rings when the operator reported the phone PIN-locked; the asr4all and KWS
lanes did not run there. Cleanup ran in full: original debug APK restored (SHA-256 `8e0ec402…`), the pushed
Whisper files, staged rings and `/data/local/tmp` files removed, stay-awake setting restored, lock released.

| Lane | Rings | Genuine TA | Near-miss FA | Background FA | Agrees with host verdict | Runs identical | Warm verify p50 / p95 / max ms | Cold first verify ≈ ms |
|---|---|---|---|---|---|---|---|---|
| Whisper tiny.en, `prod` | 826 | 45.6% (171/375) | 0.7% (3/420) | 1/31 | 98.8% (816/826) | 100% | 218 / 294 / 478 | 777 |

### S21 (`R5CR605B71K`), all lanes

Rings: 375 genuine, 420 near-miss, 290 background-speech, 33 non-speech. Owner natural = the 3 owner rings in
the device subset (clean, reverb, music).

| Lane | Genuine TA | Owner natural | Near-miss FA | Background FA | Non-speech FA | Agrees with host verdict | Verify p50 / p95 / max ms | Load / cold | Memory |
|---|---|---|---|---|---|---|---|---|---|
| Whisper tiny.en, `prod` (production app path) | 45.6% (171/375) | 1/3 | 0.7% (3/420) | 0.7% (2/290) | 0/33 | 99.1% (1108/1118) | 507 / 713 / 1006 (warm) | first verify after process start ≈ 1156 ms | app PSS +143 MiB |
| asr4all M c16r4, `prod` (ET probe) | 9.9% (37/375) | 1/3 | 2.9% (12/420) | 0.3% (1/290) | 0/33 | 99.4% (1111/1118) | 418 / 473 / 551 | load 767 ms | PSS +119 MiB after load, +170 MiB peak |
| asr4all M c16r4, `prod+forms` | 58.7% (220/375) | 3/3 | 3.6% (15/420) | 0.3% (1/290) | 0/33 | 98.9% (1106/1118) | same | same | same |
| asr4all M c32r8, `prod` (ET probe) | 9.3% (35/375) | 0/3 | 1.9% (8/420) | 0.3% (1/290) | 0/33 | 99.4% (1111/1118) | 384 / 462 / 563 | load 502 ms | PSS +123 MiB after load, +179 MiB peak |
| asr4all M c32r8, `prod+forms` | 59.7% (224/375) | 1/3 | 2.9% (12/420) | 0.3% (1/290) | 0/33 | 99.3% (1110/1118) | same | same | same |
| KWS t0.25 / s1.0 (default) | 61.6% (231/375) | **3/3** | 1.0% (4/420) | **0/290** | 0/33 | 99.0% (1107/1118) | **212 / 248 / 316** | load 3290 ms (incl. first ORT load) | PSS +50 MiB after load, +81 MiB peak |
| KWS t0.08 / s2.0 | **70.1% (263/375)** | **3/3** | 1.2% (5/420) | **0/290** | 0/33 | 99.6% (1114/1118) | 217 / 252 / 445 | load 3503 ms (incl. first ORT load) | PSS +50 MiB after load, +80 MiB peak |

Reverb is the hardest condition on the phone as on the host (Whisper 28.0%, asr4all M + forms 44.0–46.4%, KWS
39.2–48.0%). asr4all's PCEC text matched the host's exactly on 88.5% (c16r4) and 89.1% (c32r8) of rings,
consistent with the ARM vs x86 kernel drift #1988 measured; it changed a verdict on ≤ 1.1% of rings. Whisper's two
runs per ring were identical on all 1,118.

Caveats: the lanes run in different processes, so memory is not like-for-like. Whisper's figure is the app's
PSS growth while its verifier is resident; asr4all's and KWS's are the growth of their own process from start,
which for KWS includes loading onnxruntime itself (the app already has it loaded for the wake detector, so
KWS's load time and memory in the app would be lower). asr4all ran with 4 threads, Whisper and KWS with 2.

Device state: on both phones the script installed the `main@00025895` debug APK with `install -r -d` (data
kept), pushed the three catalogue Whisper files only where missing, and staged rings in app-private storage and
`/data/local/tmp`. Cleanup reinstalled the exact previous debug APK (S21 `26bb36fa…`, S23U `8e0ec402…`),
removed every pushed and staged file, uninstalled the isolated #1988 probe packages it had installed, restored
the recorded `stay_on_while_plugged_in` value and released the lock. Nothing was uninstalled or cleared in
`ai.jandal.assistant*` and no Gradle connected task ran. The first S23U attempt sent one `KEYCODE_WAKEUP`
before the phone was reported PIN-locked; the script no longer touches the screen.

## Reproduce

Private inputs (not in Git): `~/private-acoustic-fixtures` (natural recording, #1984 breadth set, owner decision
set, #1439 probes, catalogue verifier models), `~/jandal-voice` (#1736 clones, music), the FUTO S/M/L snapshots in
the Hugging Face cache, and the KWS model and Piper voices from the sherpa-onnx `kws-models` / `tts-models`
releases.

```sh
H=docs/evidence/wake-verifier/harness
python $H/gen_piper.py --voices <piper-voice-dirs> --out <private>/wake-verifier-spike/fixtures/piper
python $H/wake_eval.py prepare --private <private> --work <work> --assets app/src/main/assets/models/wakeword
$H/run_host.sh <python> <private> <work> <work>/results          # all engines, then quiet timing passes
python $H/wake_eval.py export --work <work> --out <rings>           # device subset (1,118 rings)
$H/device-kws/build.sh <kws-dir>                                    # KWS device lane (app_process)
APK=app/build/outputs/apk/debug/app-debug.apk PROBE_APK_DIR=<#1988 probe outputs/apk> KWS_DIR=<kws-dir> \
  KWS_MODEL=<kws model dir> $H/device_run.sh s21 <agent-id> <rings> <private-out> whisper,et,kws
python $H/wake_eval.py device-score --work <work> --rings <rings> --device <private-out> \
  --host <work>/results/host-aggregates.json --label <device> --out <json>
python $H/render.py <host-aggregates.json> [device json,...] [labels...]
```

Python 3.12 venv: `executorch==1.5.1 torch==2.14.0 sherpa-onnx==1.13.0 onnxruntime soundfile soxr sentencepiece
numpy psutil`. asr4all runs through FUTO's `executorch/asr4all_executorch.py`, loaded from the Hugging Face
snapshot at run time; nothing from it is copied into this repository. The ExecuTorch device lane is the isolated
#1988 probe (`spike/1988-executorch-retest`), installed only for the run and uninstalled afterwards.
