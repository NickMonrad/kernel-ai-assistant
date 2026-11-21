# Wake-word alternatives for Jandal: commercial-safety survey (#2021 part 2)

Status: research only. No code, model, or asset is added or released by this document.
Survey date: 2026-11-21. Scope: custom "Hey Jandal" wake word on Android (min SDK 35).
Companion: the openWakeWord asset review is in PR #2020 (`docs/legal/wakeword-licence-review.md`, pinned upstream `v0.5.1` = `1eec2158c5c54150ac5f4c15065adacb1003b1e7`). Its verdict stands: all three openWakeWord-derived wake-word assets are excluded from release.

Legend: **[V]** verified from a primary source in this survey. **[I]** `[INFERENCE]`. **[NC]** not checked.

## Summary

- No option surveyed is both commercially cleared end-to-end and ready to ship today.
- Porcupine is the fastest path but is a paid, proprietary commercial licence with no published price. Commercial use requires a paid agreement.
- sherpa-onnx KWS has an open-source code licence, but its GigaSpeech-trained weights sit on GigaSpeech audio whose access terms are non-commercial. The publisher's model README says Apache-2.0. That claim conflicts with the data terms and has not been confirmed by a maintainer.
- microWakeWord (Apache-2.0 code and weights) is the most practical route to a custom "Hey Jandal" model. Its pretrained models and default training recipe depend on data with unclear or non-commercial lineage. A custom model trained only on clean, releasable data is the commercial-safe option.
- Recommendation: train our own microWakeWord-architecture streaming model on releasable data only. Effort is in section 6 and is `[I]`.

## 1. Comparison table

| Option | Code licence | Weights licence | Training data / lineage | Custom "Hey Jandal" commercial route | Android runtime fit | Size | Latency / accuracy | Verdict |
|---|---|---|---|---|---|---|---|---|
| **openWakeWord** (current, `dscripka/openWakeWord`) | Apache-2.0 [V] | README: included pretrained models are CC BY-NC-SA 4.0 [V] | Feature models: Google `speech_embedding` (licence per Kaggle, [NC] on primary page); `hey_jandal.onnx` depends on Piper voices of unknown lineage [PR #2020] | Blocked by NC weights and unclear TTS lineage | ONNX Runtime; app already uses it | `hey_jandal.onnx` ~205 KB [PR #2020] | [NC] | Excluded (PR #2020). Reuse only if feature models are retrained on clean data. |
| **microWakeWord** (`kahrendt/microWakeWord`, main `4665173cd35f1cff9a61e06fc427f124766c488e`, pushed 2026-07-09) | Apache-2.0 [V] | Via `esphome/micro-wake-word-models` (pushed 2026-10-01): README and NOTICE say Apache-2.0 covers `.tflite` and `.json` [V] | README data sources: FSD50K (various CC), FMA (CC BY 4.0), WHAM! (**CC BY-NC 4.0**, augmentation), BIRD RIR, VOICES (CC BY 4.0), Common Voice (CC), DiPCo (CDLA-P 1.0, validation). Positives generated with `piper-sample-generator` default `en_US-libritts_r-medium` [V]. LibriTTS-R dataset CC BY 4.0 [V]. Piper voice lineage conflicts (see section 3) | Train own model with a clean data set. The pipeline is Apache-2.0 [V]. Default pretrained models are not releasable as-is [I] | TFLite (streaming, quantized). Android via TFLite/LiteRT, not verified in this survey [I] | `alexa` 55,856 B; `hey_jarvis` 52,272 B; `okay_nabu` 60,264 B; `hey_mycroft` 57,248 B; `vad` 34,328 B [V, esphome v2] | No latency or accuracy figures in repo; ROC plots only [V] | **Best base for custom model, after clean retrain** |
| **sherpa-onnx open-vocabulary KWS** (`k2-fsa/sherpa-onnx` master `8a667ab114e8a007f671678ddb031729dd81dce4`, pushed 2026-11-21; release `kws-models`) | Apache-2.0 [V] | Publisher README (`sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01`, tarball sha256 `f170013b4716e41b62b9bfd809687c207cef798ef9bc6534d524e17af9b6561a`): front matter `license: Apache License 2.0`, trained on GigaSpeech XL 10000 h [V]. sherpa docs and issue #4235 say check model licence separately [V]. No maintainer confirmation found | GigaSpeech audio: HF gated access terms say "non-commercial research and educational purposes" [V]. HF discussion `speechcolab/gigaspeech#13` (Apr 2025, open) questions the Apache-2.0 metadata [V] | Blocked until the GigaSpeech data question and weights licence are resolved in writing | sherpa-onnx already in app; KWS Android APK demo exists [V, docs] | gigaspeech-3.3M: int8 encoder 4.81 MB, decoder 0.28 MB, joiner 0.16 MB; fp32 encoder 12.17 MB [V]. zh-en-3M: int8 encoder 4.4 MB [V, docs] | Chunk 16 = 320 ms, chunk 8 = 160 ms (docs, zh-en-3M) [V]; no accuracy figures published [NC] | Not cleared. Revisit only with written licence |
| **sherpa-onnx `zh-en-3M-2025-12-20`** | Apache-2.0 [V] | **No LICENSE or README in the tarball** (sha256 `68447f4fbc67e70eee3a93961f36e81e98f47aef73ce7e7ca00885c6cd3616a6`, archive listed in full) [V]. Issue #3802 reports ModelScope front matter says Apache 2.0 [reported, NC] | Not stated in the docs page [V] | Blocked: no licence file, no training-data statement | As above; supports `en.phone` lexicon for custom keywords [V] | fp32 encoder ~12 MB, int8 4.4 MB [V] | Chunk 16 = 320 ms, chunk 8 = 160 ms [V] | Not cleared |
| **sherpa-onnx WenetSpeech 3.3M** | Apache-2.0 (code) [V] | [NC] | WenetSpeech L 10000 h, Chinese only [V, docs] | Out of scope for an English wake word | As above | ~4.6 MB int8 encoder [V, docs] | [NC] | Out of scope |
| **Picovoice Porcupine** (`Picovoice/porcupine` master `4890e6f3ec6f6976e2dae5a4bdffb0a95e3a5228`, pushed 2026-11-21) | Apache-2.0 for the repository's SDK source and wrappers [V] | Engine and models are under the Picovoice Terms, not Apache-2.0 [V] | Not disclosed; models trained by Picovoice Console [V] [I on data chain] | Custom keywords via Picovoice Console (paid commercial use) [V]. Terms §6.3 grant is "provided full compliance with Terms and payment of applicable fees" [V]. §7.1(4) forbids modifying or deriving the Software; §7.1(9) forbids training ML models without written consent [V] | Android SDK `ai.picovoice:porcupine-android` 4.0.2 (licence field Apache-2.0 on the wrapper POM) [V]. AccessKey required [V]. Activation/licence-check runtime behaviour [NC] | Vendor FAQ: ~1 MB memory; <4% single core on Pi 3 [V vendor claim] | Vendor FAQ: 97%+ detection, <1 false alarm per 10 h [V vendor claim, not independent] | **Not recommended** for commercial use until a written quote and terms are reviewed |
| **Mycroft Precise** (`MycroftAI/mycroft-precise`) | Apache-2.0 [V] | [NC]. `MycroftAI/precise-data` licence field is null [V] | [NC] | [NC] | TensorFlow/Keras; older stack | [NC] | [NC] | Stale (last push 2023-11-25 [V]); not recommended |
| **wekws** (`wenet-e2e/wekws`, pushed 2026-07-23) | Apache-2.0 [V] | [NC] | [NC] | Recipes exist; training data per recipe [NC] | PyTorch training; export path [NC] | [NC] | [NC] | Candidate for research only; requires own data check |
| **pocketsphinx** (`cmusphinx/pocketsphinx`) | NOASSERTION per GitHub API [V] | - | - | - | - | - | - | Excluded: licence not confirmed |
| **Own feature extractor + own training data** | Apache-2.0 for TensorFlow / TFLite Micro [V]. The micro_speech frontend used by microWakeWord is in this family [I] | Our own | Only clean data (section 4) | Yes, if data chain is clean [I] | Same as microWakeWord | ~50 KB class model [I] | To be measured | **Recommended** |

## 2. Dataset licences checked

| Dataset | Licence | Status |
|---|---|---|
| LibriSpeech (`openslr/librispeech_asr`) | cc-by-4.0 [V, HF card] | Usable for negatives/speech with attribution [I] |
| Google Speech Commands (`google/speech_commands`) | cc-by-4.0 [V, HF API] | Usable; contains no "jandal" [V, word list not re-checked] |
| LibriTTS-R (OpenSLR 141) | CC BY 4.0 [V, Piper model card] | Dataset is clean; the Piper model trained on it is not (section 3) |
| GigaSpeech | Apache-2.0 HF metadata; gated terms say non-commercial research and educational use [V] | **Not commercially cleared** |
| Common Voice | Mozilla Data Collective; HF card redirects there [V] | Licence not re-verified in this survey; CC0 per upstream description [NC] |
| WHAM! noise | CC BY-NC 4.0 per microWakeWord docs [V, doc] and HF mirror `philgzl/wham` [V] | **Non-commercial**; do not use for release weights |
| `kahrendt/microwakeword` HF pre-generated features | No licence field in HF API [V] | Do not use until licence stated |
| MLCommons Multilingual Spoken Words | HF page returned 401 [V] | [NC] |

## 3. Piper and TTS lineage (blocks the default microWakeWord positives)

- `piper-sample-generator` (MIT [V], pushed 2026-03-12) generates positives with `en_US-libritts_r-medium.pt`, exported from the `rhasspy/piper-checkpoints` `libritts_r/medium` checkpoint [V, readme].
- The two upstream model cards for the same voice disagree:
  - `rhasspy/piper-voices` `en/en_US/libritts_r/medium/MODEL_CARD`: "Fine-tuned from English lessac medium on train-clean-360" [V].
  - `rhasspy/piper-checkpoints` `en/en_US/libritts_r/medium/MODEL_CARD`: "Trained from scatch. Requires Piper 1.5 or higher." [V]
- Lessac lineage is research-only/NC per the Blizzard terms summarised in `docs/legal/piper-voice-review.md` on this branch [V].
- Conclusion: the default microWakeWord positive generator has contested lineage. Do not ship weights trained on it [I]. Use a voice whose full chain is clean (section 4).

## 4. Own feature extractor and training on clean data

Route: keep the microWakeWord/TFLite streaming architecture (Apache-2.0 code [V]) and the TF micro frontend (Apache-2.0 [V]); train from scratch on releasable data.

- Positives: consented recordings of "Hey Jandal" from several speakers, plus TTS only from voices with a documented clean chain. [I] Candidate voices from `docs/legal/piper-voice-review.md`: cori-medium (public domain per trainer statement, [V] in that doc), ljspeech-high, norman, kristin, john. Each still needs its own chain check before use.
- Negatives: LibriSpeech (CC BY 4.0), Speech Commands (CC BY 4.0), Common Voice (licence to re-confirm at MDC), and own recordings. Do not use WHAM!, GigaSpeech, or unlicensed FSD50K/FMA clips without per-clip checks [I].
- Noise augmentation: generated noise or own recordings only [I].
- Risk: small positive count from our own recordings raises false-reject and false-accept risk; needs evaluation set [I].

## 5. Open questions

1. openWakeWord written licence for pretrained models: owner-gated. Not posted upstream in this task (per user instruction). A reply from the openWakeWord owner is needed before any reuse.
2. sherpa-onnx gigaspeech-3.3M weights: publisher says Apache-2.0; GigaSpeech audio says non-commercial. No maintainer answer. Issue #4235 (2026-11-18, closed 2026-11-19) received only an automated bot answer [V].
3. Porcupine commercial price: not published. Free-tier figures online are third-party and unverified [NC].
4. Piper `libritts_r` lineage: two conflicting model cards (section 3). Ask Piper maintainers or drop the voice.
5. Android runtime for microWakeWord TFLite models under LiteRT: not tested here [NC].

## 6. Recommendation and rough effort

**Recommended path: own streaming KWS on clean data (microWakeWord-architecture, Apache-2.0 code).**

1. Data collection: consented "Hey Jandal" recordings, many speakers, plus clean negatives (section 4). [I] 1–2 weeks.
2. Training with the microWakeWord pipeline on the clean set; export int8 TFLite (~50 KB class). [I] 3–5 days.
3. Android integration via TFLite/LiteRT with the same streaming frontend; measure latency and false-accept/false-reject on device. [I] 1–2 weeks.
4. Licence file, data manifest, and model card recording every data source and its licence. [I] 2–3 days.

Total: ~3–5 engineer-weeks [I]. Highest uncertainty: data volume needed for accuracy, not licence.

**Fallback (fast but commercial-gated): Porcupine paid licence.** Effort is days plus sales. Blocked on price, terms review (§7.1(4), §7.1(9)), and activation/runtime review.

**Not recommended now:** sherpa-onnx GigaSpeech weights (until written licence and GigaSpeech terms resolved); sherpa zh-en-3M (no licence file); default microWakeWord/ESPHome pretrained models (their chain includes WHAM! NC augmentation and the contested libritts_r generator [I]).

## 7. Not checked

- Exact default microWakeWord training voices beyond `piper-sample-generator` README.
- Latency and accuracy measurements on device.
- LiteRT compatibility of microWakeWord streaming ops.
- wekws, Mycroft Precise, and sherpa WenetSpeech licences beyond the repo metadata above.
- FSD50K and FMA per-clip licences.
- Common Voice and MLCommons licences at the primary source.

## 8. Sources (retrieved 2026-11-21)

- openWakeWord: `https://github.com/dscripka/openWakeWord` (pushed 2025-12-30); assets in PR #2020.
- microWakeWord: `https://github.com/kahrendt/microWakeWord` main `4665173cd35f1cff9a61e06fc427f124766c488e`; `documentation/data_sources.md`.
- ESPHome models: `https://github.com/esphome/micro-wake-word-models` (pushed 2026-10-01); NOTICE and LICENSE.
- piper-sample-generator: `https://github.com/rhasspy/piper-sample-generator` (MIT).
- LibriTTS-R model cards: `https://huggingface.co/rhasspy/piper-voices` and `https://huggingface.co/datasets/rhasspy/piper-checkpoints`.
- sherpa-onnx: `https://github.com/k2-fsa/sherpa-onnx` master `8a667ab114e8a007f671678ddb031729dd81dce4`; KWS docs `https://k2-fsa.github.io/sherpa/onnx/kws/pretrained_models/index.html`; Android KWS note `https://k2-fsa.github.io/sherpa/onnx/kws/apk.html`; release `kws-models`.
- sherpa issues: #4235, #3802, #3917, #3878, #3760, #3658 (duplicates of #3802 closed).
- GigaSpeech: `https://github.com/SpeechColab/GigaSpeech`; HF `https://huggingface.co/datasets/speechcolab/gigaspeech`; discussion #13 (opened 2025-04-23).
- Porcupine: `https://github.com/Picovoice/porcupine` master `4890e6f3ec6f6976e2dae5a4bdffb0a95e3a5228`; Terms `https://picovoice.ai/docs/terms-of-use/` (updated 2026-03-18, effective 2026-03-30); FAQ `https://picovoice.ai/docs/faq/general/` and `https://picovoice.ai/docs/faq/porcupine/`; pricing `https://picovoice.ai/pricing/`.
- Maven POM: `https://repo1.maven.org/maven2/ai/picovoice/porcupine-android/4.0.2/porcupine-android-4.0.2.pom`.
- Mycroft Precise: `https://github.com/MycroftAI/mycroft-precise`.
- Datasets: LibriSpeech HF card `https://huggingface.co/datasets/openslr/librispeech_asr`; Speech Commands `https://huggingface.co/datasets/google/speech_commands`; WHAM mirror `https://huggingface.co/datasets/philgzl/wham`.
- TensorFlow and TFLite Micro: `https://github.com/tensorflow/tensorflow` and `https://github.com/tensorflow/tflite-micro` (Apache-2.0).
- Third-party only, not relied on: pyai.com pricing blog, outspoken.cloud Porcupine price claim, Dosu bot replies in sherpa issues.
