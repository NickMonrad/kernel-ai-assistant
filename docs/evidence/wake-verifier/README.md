# Hey Jandal low-confidence verifier: which engine? (#1045, input to #1982)

Benchmark-only spike. It changes no production code, model, threshold or download. Owner and voice-cloned
audio, transcripts and per-ring verdicts stayed in a private directory outside Git and in app-private or
`/data/local/tmp` storage that the device script deletes. This directory holds the harness and aggregates only.

- Harness: [`harness/`](harness/) (see [Reproduce](#reproduce)).
- Aggregates: [`results/host-aggregates.json`](results/host-aggregates.json) and the device files in [`results/`](results/).

Status: host comparison complete for all six options; device confirmation on the S21 (Whisper, asr4all M in both
tiers, KWS; all 1,118 rings) and a partial Whisper run on the S23U (826 rings, stopped when the phone was found
PIN-locked). Device verdicts agree with the host verdicts on 98.8–99.6% of rings. Both sides replay the same
digital rings, so this shows runtime and ARM/x86 agreement, not acoustic performance.

## Answer

### The bigger finding: the fast path

The production loop accepts any frame ≥ 0.65 without verification; the verifier only sees the 0.50–0.65 band.
On this corpus 12.9% of near-miss phrases (90/700) are accepted through that fast path, and no verifier can
stop them unless every candidate is verified. Verifying every candidate ≥ 0.50 removes them all (KWS 0/700;
asr4all M `prod+forms` 1.0%), but it also cuts genuine acceptance from 71–72% to 37–55%. That is a policy
change for its own issue (follow-up 2 below).

### Verifier verdicts

| | Verdict |
|---|---|
| **Candidate to validate** | **sherpa-onnx open-vocabulary keyword spotter** (`sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01`, int8, keyword `HEY JANDAL`). A candidate to validate on acoustic device tests, not the verifier to adopt now. Its engine-level advantage is clear: genuine acceptance 58.2% (default settings) vs Whisper 38.4% on the host; 61.6% vs 45.6% on the S21 over the same rings; 58.5% vs 38.5% on Piper voices; owner natural 5/5 vs 2/5; owner-voice clones 8/20 vs 7/20 (first crossing). The gain over the production detector alone is smaller: +8 (default) or +10 (tuned `t0.08`) of the 12 genuine low-band items, vs +3 for Whisper, i.e. 1.3–1.6 points vs 0.5 points out of 625. Its near-miss false accepts are almost all `Okay Jandal` (6–7 of 700, the same order as Whisper's 6). The recommendation rests on the engine-level figures plus the #1439 premise: real phone candidates land at 0.59–0.63, where the verifier does decide. This corpus has no acoustic low-band candidates, so the production gain is not measured. |
| **Interim verifier (#1982)** | **Whisper tiny.en, as #1982 already says.** Already wired and proven on device. Its `prod` forms (`hi` prefix, `jando`) came from #1439 and are already in production. Remove Zipformer (0% acceptance here). |
| **asr4all (M or L, either tier)** | **Not as a verifier.** It does not know "Jandal": with the production matcher it accepts 7–8% of genuine rings on the host and 9–10% on the S21. Reaching ~50–60% needs name spellings learnt from this same corpus (`jandle`, `gendal`, `janel`, `jand`, …). Those spellings also let the verifier accept 1–3 near-miss items that the fast path would not (near-miss gain column above). The c16r4 and c32r8 tiers are within noise; L is not better than M on the owner's recording. On the S21 an M check takes ~384–418 ms (Whisper ~507 ms), so it is not materially cheaper. |
| **No STT verification, one tuned threshold** | **Not viable.** Near-miss phrases (`Hey Handle`, `Hey Jungle`, `Hey Kendall`, …) reach detector scores of 0.93, as high as genuine ones. At 0.90 the detector still passes 6.9% of near-misses while genuine acceptance falls to 55.5%. |

### What #1982 should become

Keep #1982's Zipformer removal and its Whisper-only verifier (items 1–5) unchanged. This spike gives no reason to
make asr4all the verifier, so #1982 should not wait for, or depend on, the asr4all work for wake verification.
Add two follow-ups instead:

1. **New issue: validate sherpa-onnx KWS as the replacement verifier, then drop Whisper.** Scope: a
   `KeywordSpotter`-based `transcribeBlocking` replacement (or a dedicated `verifyWakeWindow` path) with the
   pinned 3.3M int8 model (hashes in [`harness/wake_eval.py`](harness/wake_eval.py) `KWS_PIN`), the keyword
   `▁HE Y ▁JA ND AL @HEY_JANDAL` ([`harness/device-kws/keywords.txt`](harness/device-kws/keywords.txt), no
   `bpe.model` needed at run time), and a threshold chosen from acoustic device runs rather than this digital
   corpus. Dropping `SHERPA_WHISPER_TINY_EN_*` from the Hey Jandal download (98.3 MB saved: 103.6 MB minus the
   5.3 MB KWS set; #1931) is conditional on that validation passing. Acceptance: the existing S21/S23U acoustic
   wake matrix on natural owner audio, no worse false-accept rate, and the licence items below resolved.
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
| (e) dedicated keyword verifier | sherpa-onnx open-vocabulary KWS, gigaspeech 3.3M int8 (archive sha256 `f170013b…`), keyword `HEY JANDAL`; threshold/boost 0.25/1.0 (default), 0.15/1.5, 0.08/2.0 | 5.3 MB (encoder 4.8 + decoder 0.3 + joiner 0.2 MB + tokens) | keyword hit; no transcript matching |
| (f) no STT verification | production openWakeWord detector only, one threshold | 0 | — |

Other dedicated verifiers looked at, not run (they need training or enrolment): openWakeWord's
[custom verifier models](https://github.com/dscripka/openWakeWord/blob/main/docs/custom_verifier_models.md)
(a per-speaker logistic regression on the same embeddings; needs ≥3 enrolment clips per user and a retrain per
environment), a second compact KWS model such as microWakeWord (#1401; needs a training project), and speaker
verification (answers *who* spoke, not *what*). The sherpa-onnx KWS needs neither training nor enrolment.

### KWS model pinning and licence

- Files, SHA-256 (checked by `wake_eval.py` on every run; a mismatch aborts):
  - `encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx` `1e721676…` (4,807,159 bytes)
  - `decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx` `e40ff432…` (277,985 bytes)
  - `joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx` `eae9da0c…` (163,380 bytes)
  - `tokens.txt` `fd2ded40…` (5,006 bytes); `bpe.model` `c8a2a012…` (244,837 bytes, used only to build the keyword)
  - archive `sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2` `f170013b…`
  - full hashes in `KWS_PIN` in `harness/wake_eval.py`.
- Upstream: `k2-fsa/sherpa-onnx` release `kws-models`, asset `sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2`.
- Keyword line: generated from `bpe.model` with `sp.encode("HEY JANDAL", out_type=str)` joined by spaces plus
  ` @HEY_JANDAL`. `wake_eval.py` regenerates it and aborts unless it equals the committed
  `harness/device-kws/keywords.txt`.
- Licence sources: the archive's `README.md` (SHA-256 `74e42d37…`) declares `license: Apache License 2.0` and
  trains on GigaSpeech XL (10,000 h). The ModelScope repository `pkufool/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01`
  reports the same licence (`License: Apache License 2.0`, read via its API).
- Open question, not resolved here: the GigaSpeech dataset card says the audio is for non-commercial research
  under its access terms, and that models trained on it "may be eligible for commercial license, provided they
  abide to the 'Fair Use' terms… it is your responsibility to verify". The dataset's metadata also says
  `apache-2.0`. Before shipping, resolve this with written upstream confirmation or a legal review, and add
  the notice asset alongside the model (follow-up 1).
- Correction: an earlier draft cited `k2-fsa/sherpa-onnx#3802` for this model. That thread concerns
  `sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20`, not the gigaspeech 3.3M model. Its licence change is for the
  zh-en model, and the commercial question in that thread is still open. It does not confirm this model's terms.

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
clones (20 rings in the tables); the owner-voice clone generator (Qwen3-TTS) could not be run on this host this
time, so the near-miss set is Piper voices only. Piper voices pronounce "Jandal" in US/UK English, which hurts
every engine; the owner rows are the closest to real use. Digital mixing is not the phone's acoustic path: on
these clean streams the detector usually scores ≥ 0.65, whereas on device (#1439) genuine candidates often land
at 0.59–0.63. KWS thresholds and asr4all's extra name forms were chosen on this same corpus, so the tuned
figures are optimistic.

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
| `kws-t0.25-s1.0` (default) | 58.2% (364/625) | 60.2% (376/625) | **5/5 / 5/5** | 8/20 / 8/20 | 58.5% (351/600) | 0.9% (6/700) | **0.0% (0/750)** | 0/33 |
| `kws-t0.15-s1.5` (tuned) | 64.2% (401/625) | 65.9% (412/625) | **5/5 / 5/5** | 10/20 / 11/20 | 64.3% (386/600) | 1.0% (7/700) | **0.0% (0/750)** | 0/33 |
| `kws-t0.08-s2.0` (tuned) | 67.7% (423/625) | 68.5% (428/625) | **5/5 / 5/5** | 11/20 / 14/20 | 67.8% (407/600) | 1.0% (7/700) | **0.0% (0/750)** | 0/33 |

The `t0.15` and `t0.08` settings were chosen on this corpus; their figures are optimistic.

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
Each low-band frame is verified synchronously in the detector's chunk loop (`OnnxWakeWordDetector.kt`, the
`verifyWindow` call on a `confidence >= lowThreshold` frame), so a candidate that is rejected many times blocks
that loop once per check.

"Gain over detector-only" is the number of items accepted only because the verifier said yes. An item the
detector alone accepts (any frame ≥ 0.65) is not gain whatever the verifier does. Of the 12 genuine items that
never reach 0.65, the verifier can add at most 12; of the 11 near-miss low-band items, at most 11.

| Option | Genuine accepted | Gain over detector-only (of 12) | Near-miss accepted | Near-miss gain (of 11) | Background accepted |
|---|---|---|---|---|---|
| `zipformer:prod` | 70.6% (441/625) | 0 | 12.9% (90/700) | 0 | 0/118 |
| `whisper-tiny-en:prod` | 71.0% (444/625) | 3 | 12.9% (90/700) | 0 | 0/118 |
| `asr4all-m-stream_c16r4:prod` | 70.6% (441/625) | 0 | 13.1% (92/700) | 2 | 0/118 |
| `asr4all-m-stream_c16r4:prod+forms` | 71.2% (445/625) | 4 | 13.1% (92/700) | 2 | 0/118 |
| `asr4all-m-stream_c32r8:prod+forms` | 71.2% (445/625) | 4 | 13.1% (92/700) | 2 | 0/118 |
| `asr4all-l-stream_c16r4:prod+forms` | 71.8% (449/625) | 8 | 13.3% (93/700) | 3 | 0/118 |
| `asr4all-l-stream_c32r8:prod+forms` | 71.4% (446/625) | 5 | 13.0% (91/700) | 1 | 0/118 |
| `kws-t0.25-s1.0` (default) | 71.8% (449/625) | 8 | 12.9% (90/700) | 0 | 0/118 |
| `kws-t0.08-s2.0` (tuned) | 72.2% (451/625) | 10 | 12.9% (90/700) | 0 | 0/118 |

The asr4all rows are the only ones where the verifier adds false accepts (1–3 near-miss items). For Whisper and
KWS every near-miss accept comes through the fast path, so the verifier adds none. The `prod+forms` lanes include
spellings learnt from the corpus (in-sample).

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

Quiet pass only (one engine at a time, 200 full-length rings). RSS columns come from the same pass: "after
load" is the growth at the end of load, "peak" the highest RSS growth seen during the pass. The full
accuracy pass ran engines in parallel and gives different RSS figures (for example asr4all reads 0 MiB after
load there, which is consistent with memory-mapped weights already resident; [INFERENCE], not measured).

| Engine | Threads | Load ms | First call ms | Warm p50 ms | Warm p95 ms | RSS Δ after load MiB | Peak RSS Δ MiB |
|---|---|---|---|---|---|---|---|
| Whisper tiny.en | 2 | 527 | 209 | 149 | 185 | 135 | 244 |
| Zipformer | 2 | 1536 | 148 | 129 | 140 | 170 | 175 |
| asr4all M c16r4 (ET) | 4 | 164 | 166 | 100 | 107 | 123 | 242 |
| asr4all M c32r8 (ET) | 4 | 101 | 140 | 95 | 118 | 123 | 247 |
| asr4all L c16r4 (ET) | 4 | 194 | 462 | 174 | 237 | 174 | 354 |
| asr4all L c32r8 (ET) | 4 | 154 | 220 | 160 | 187 | 174 | 360 |
| KWS 3.3M int8 (t0.25) | 2 | 473 | 47 | 38 | 39 | 40 | 45 |

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

The asr4all `prod+forms` lanes reuse the spellings learnt on the host (in-sample, from the same corpus), so
their device figures are not an independent test of those forms.

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

| Lane | Genuine TA | Owner natural | Near-miss FA | Background FA | Non-speech FA | Agrees with host verdict | Verify p50 / p95 / max ms | Load / cold | Memory (cross-process, see caveats) |
|---|---|---|---|---|---|---|---|---|---|
| Whisper tiny.en, `prod` (production app path) | 45.6% (171/375) | 1/3 | 0.7% (3/420) | 0.7% (2/290) | 0/33 | 99.1% (1108/1118) | 507 / 713 / 1006 (warm) | first verify after process start ≈ 1156 ms | app PSS +143 MiB |
| asr4all M c16r4, `prod` (ET probe) | 9.9% (37/375) | 1/3 | 2.9% (12/420) | 0.3% (1/290) | 0/33 | 99.4% (1111/1118) | 418 / 473 / 551 | load 767 ms | PSS +119 MiB after load, +170 MiB peak |
| asr4all M c16r4, `prod+forms` | 58.7% (220/375) | 3/3 | 3.6% (15/420) | 0.3% (1/290) | 0/33 | 98.9% (1106/1118) | same | same | same |
| asr4all M c32r8, `prod` (ET probe) | 9.3% (35/375) | 0/3 | 1.9% (8/420) | 0.3% (1/290) | 0/33 | 99.4% (1111/1118) | 384 / 462 / 563 | load 502 ms | PSS +123 MiB after load, +179 MiB peak |
| asr4all M c32r8, `prod+forms` | 59.7% (224/375) | 1/3 | 2.9% (12/420) | 0.3% (1/290) | 0/33 | 99.3% (1110/1118) | same | same | same |
| KWS t0.25 / s1.0 (default) | 61.6% (231/375) | **3/3** | 1.0% (4/420) | **0/290** | 0/33 | 99.0% (1107/1118) | **212 / 248 / 316** | load 3290 ms (incl. first ORT load) | PSS +50 MiB after load, +81 MiB peak |
| KWS t0.08 / s2.0 (tuned) | **70.1% (263/375)** | **3/3** | 1.2% (5/420) | **0/290** | 0/33 | 99.6% (1114/1118) | 217 / 252 / 445 | load 3503 ms (incl. first ORT load) | PSS +50 MiB after load, +80 MiB peak |

"Agrees with host verdict" compares each lane's accept/reject with the host verdict on the same digital ring.
It shows runtime and ARM/x86 agreement, not acoustic performance.

Reverb is the hardest condition on the phone as on the host (Whisper 28.0%, asr4all M + forms 44.0–46.4%, KWS
39.2–48.0%). asr4all's PCEC text matched the host's exactly on 88.5% (c16r4) and 89.1% (c32r8) of rings,
consistent with the ARM vs x86 kernel drift #1988 measured; it changed a verdict on ≤ 1.1% of rings. Whisper's two
runs per ring were identical on all 1,118.

Latency and memory are cross-process estimates, not like-for-like:

- Whisper's verify time is the app-side logcat gap inside the production path (goAsync broadcast, mutex,
  reflection, logging included). KWS's is a `nanoTime` around the decode inside `KwsProbe` under `app_process`:
  no app process, no mutex. The 507 vs 212 ms gap on the phone is therefore not a like-for-like 2.4×. The host
  quiet pass, which times all engines the same way, gives 149 vs 38 ms (≈4×).
- Memory: Whisper's figure is the app's PSS growth while its verifier is resident. asr4all's and KWS's are the
  growth of their own process from start, which for KWS includes loading onnxruntime itself. The app already
  has onnxruntime loaded for the wake detector, so KWS's load time and memory inside the app would be lower
  ([INFERENCE]; not measured in the app).
- asr4all ran with 4 threads, Whisper and KWS with 2.

Device state: on both phones the script installed the `main@00025895` debug APK with `install -r -d` (data
kept), pushed the three catalogue Whisper files only where missing, and staged rings in app-private storage and
`/data/local/tmp`. Cleanup reinstalled the exact previous debug APK (S21 `26bb36fa…`, S23U `8e0ec402…`),
removed every pushed and staged file, uninstalled the isolated #1988 probe packages it had installed, restored
the recorded `stay_on_while_plugged_in` value and released the lock. Nothing was uninstalled or cleared in
`ai.jandal.assistant*` and no Gradle connected task ran. The first S23U attempt sent one `KEYCODE_WAKEUP`
before the phone was reported PIN-locked; the script no longer touches the screen.

## Reproduce

Private inputs (not in Git): `~/private-acoustic-fixtures` (natural recording, #1984 breadth set, owner decision
set, #1439 probes, catalogue verifier models, KWS model directory), `~/jandal-voice` (#1736 clones, music), the
FUTO S/M/L snapshots in the Hugging Face cache, and the Piper voices from the sherpa-onnx `tts-models` release.
The KWS model is the archive from the sherpa-onnx `kws-models` release, unpacked into the private directory and
checked against the hashes in `KWS_PIN`.

```sh
H=docs/evidence/wake-verifier/harness
python $H/gen_piper.py --voices <piper-voice-dirs> --out <private>/wake-verifier-spike/fixtures/piper
python $H/wake_eval.py prepare --private <private> --work <work> --assets app/src/main/assets/models/wakeword
$H/run_host.sh <python> <private> <work> <work>/results          # all engines, then quiet timing passes
python $H/rederive_gain.py <work>/results/host-aggregates.json   # only for results written before the gain fields
python $H/wake_eval.py export --work <work> --out <rings>           # device subset (1,118 rings)
$H/device-kws/build.sh <kws-dir>                                    # KWS device lane (app_process); copies keywords.txt
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
