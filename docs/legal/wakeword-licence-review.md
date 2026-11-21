# Wake-word model licence review (#2017)

Checked 2026-11-21. Engineering record, not legal advice. Scope: the three wake-word ONNX files in `app/src/main/assets/models/wakeword/`, the Stage 3 training provenance in `training/wakeword/README.md`, and the Whisper ONNX export used by the STT path. Docs only: no code, asset, NOTICE or release change follows from this review.

Pinned upstream: openWakeWord tag `v0.5.1` = commit [`1eec2158c5c54150ac5f4c15065adacb1003b1e7`](https://github.com/dscripka/openWakeWord/commit/1eec2158c5c54150ac5f4c15065adacb1003b1e7) (2023-09-02).

## Result

| File | Licence position | Verdict |
|---|---|---|
| `melspectrogram.onnx` (Stage 1) | No file-level licence. Upstream README's model-licence sentence (CC BY-NC-SA 4.0) literally covers "all of the included pre-trained models". | **unclear** (literal reading: NC-SA, blocks release) |
| `embedding_model.onnx` (Stage 2) | Upstream says the backbone is Google's `speech_embedding` under Apache-2.0, but also says it was "manually re-implemented". Weight origin is not stated. The same NC-SA sentence applies literally. | **unclear** |
| `hey_jandal.onnx` (Stage 3) | Jandal-trained. Depends on the frozen Stage 1/2 backbone and on Piper-voice audio whose lineage is non-releasable per `docs/legal/piper-voice-review.md`. Negative-corpus source is not recorded in the README. | **unclear** (blocking in practice) |
| Whisper `tiny.en` encoder/decoder/tokens (`csukuangfj/sherpa-onnx-whisper-tiny.en`) | Export repo has no licence. OpenAI's code is MIT; OpenAI's HF card for the weights declares Apache-2.0. | **unclear** (likely release-OK with notice once lineage is confirmed) |

Consistent with the current signoff: all three wake-word assets stay excluded from release (`docs/RELEASE_ATTRIBUTION_SIGNOFF.md` line 45 and line 78). No verdict here changes that.

## Repo files and hashes

Hashes were computed from the working-tree files and match the upstream `v0.5.1` release assets byte-for-byte (downloaded from `https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/<name>`).

| Repo file | Bytes | SHA-256 | Upstream v0.5.1 asset SHA-256 |
|---|---|---|---|
| `melspectrogram.onnx` | 1087958 | `ba2b0e0f8b7b875369a2c89cb13360ff53bac436f2895cced9f479fa65eb176f` | same |
| `embedding_model.onnx` | 1326578 | `70d164290c1d095d1d4ee149bc5e00543250a7316b59f31d056cff7bd3075c1f` | same |
| `hey_jandal.onnx` | 205430 | `11bcdb0d800b3a93449197122bd9fb484c4b8db887364c629f6c975e3e38c206` | n/a (Jandal-trained) |

The GitHub release API reports `digest: null` for these assets; the hashes above come from downloading them. Upstream `training/train.py` at `v0.5.1` fetches the same two URLs ([train.py, lines 43–47 at the pinned commit](https://github.com/dscripka/openWakeWord/blob/1eec2158c5c54150ac5f4c15065adacb1003b1e7/training/train.py)). Repo `parity_reference.json` records the same two hashes (`model_sha256`).

## Per-file evidence

### 1. `melspectrogram.onnx` — Stage 1

- Upstream README, [line 141 at the pinned commit](https://github.com/dscripka/openWakeWord/blob/1eec2158c5c54150ac5f4c15065adacb1003b1e7/README.md#L141): "an ONNX implementation of Torch's melspectrogram function with fixed parameters is used". No licence is stated for this file.
- Upstream README, [line 242](https://github.com/dscripka/openWakeWord/blob/1eec2158c5c54150ac5f4c15065adacb1003b1e7/README.md#L242), under `# License`, quoted in full:
  > "All of the code in this repository is licensed under the **Apache 2.0** license. All of the included pre-trained models are licensed under the [Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International](https://creativecommons.org/licenses/by-nc-sa/4.0/) license due to the inclusion of datasets with unknown or restrictive licensing as part of the training data. If you are interested in pre-trained models with more permissive licensing, please raise an issue and we will try to add them to a future release."
- Upstream ships it as a release asset next to the wake-word heads (`v0.5.1` release, [assets list](https://github.com/dscripka/openWakeWord/releases/tag/v0.5.1)). [INFERENCE] The file is "included" in the literal sense.
- [INFERENCE] The stated reason for NC-SA ("datasets with unknown or restrictive licensing as part of the training data") does not obviously apply to a fixed-parameter DSP filterbank export that has no trained weights. The maintainer has not said so.

Verdict: **unclear**. Literal reading of line 242 is CC BY-NC-SA 4.0, which would block a commercial release. Resolution:
- Written file-level statement from the upstream maintainer (dscripka) on the licence of `melspectrogram.onnx`, citing the v0.5.1 hash above; or
- Replace with a fixed filterbank export whose spec and licence are documented upstream of any NC data, with a parity test against the current model output (the repo already has `parity_reference.json` and `generate_parity_reference.py` for this path). Replacement needs the same output to within the existing parity tolerance.

### 2. `embedding_model.onnx` — Stage 2

- Upstream README, [line 143 at the pinned commit](https://github.com/dscripka/openWakeWord/blob/1eec2158c5c54150ac5f4c15065adacb1003b1e7/README.md#L143), quoted in part:
  > "This [model](https://arxiv.org/abs/2002.01322) is provided by [Google](https://tfhub.dev/google/speech_embedding/1) as a TFHub module under an [Apache-2.0](https://opensource.org/licenses/Apache-2.0) license. For openWakeWord, this model was manually re-implemented to separate out different functionality and allow for more control of architecture modifications compared to a TFHub module."
- The line 143 claim is about Google's module. It does not say whether openWakeWord's `embedding_model.onnx` carries Google's weights (ported) or weights retrained by openWakeWord. That is the decisive fact and it is not stated.
- Google primary source: `https://tfhub.dev/google/speech_embedding/1` returns 404 and redirects to Kaggle, [`https://www.kaggle.com/models/google/speech-embedding`](https://www.kaggle.com/models/google/speech-embedding). The Kaggle page is JavaScript-rendered and the licence field could not be read with the tools available here, so the Apache-2.0 claim is **not verified from Google's page**. The only support found is the openWakeWord README and the PyPI description (`https://pypi.org/project/openwakeword/`), both secondary.
- Line 242 (quoted above) applies literally to this file as well.

Verdict: **unclear**. Resolution:
- Capture the licence field from the Kaggle (or TF Hub) model page for `google/speech-embedding` at a recorded date, using a browser or authenticated Kaggle API. Record the URL and the licence text.
- Written statement from dscripka on whether `embedding_model.onnx` weights are ported from Google's module or retrained, and on what data. If ported, Google's Apache-2.0 terms (with NOTICE obligations) plausibly apply to the weights. [INFERENCE] Line 242 would still need a file-level answer, because the upstream README says the pre-trained models are NC-SA as a whole.

### 3. `hey_jandal.onnx` — Stage 3

- Training provenance, [`training/wakeword/README.md`](../../training/wakeword/README.md) (tracked, blob `9b08710`):
  - Lines 3–17: Stage 3 classifier over frozen Stage 1/2 outputs; "Stages 1 and 2 are pre-built shared models downloaded automatically by `train.py`".
  - Lines 34–36: 954 positive clips: 174 from real recordings (29 originals, augmented), and 780 "from Piper TTS across 11 voices and 13 phonetic variants". The 11 voices are not named.
  - Lines 83–86: outputs `hey_jandal.onnx`, `melspectrogram.onnx`, `embedding_model.onnx`.
- `training/train.py` (tracked), [lines 293–330](../../training/train.py):
  - Negatives: first choice is `openwakeword_features_ACAV100M_2000_hrs_16bit.npy`, read from a path in the working directory or from a hard-coded maintainer path (`/home/lokhor/Documents/...`). This file is not tracked. Fallback is `openwakeword.utils.get_negative_clips`. The README does not name the negative corpus.
  - Positives and negatives are embedded through the same Stage 2 backbone (`compute_embeddings`).
- `training/wakeword/generate_hey_jandal.py` (tracked), lines 1–12: voice-clones the maintainer's reference clip with Qwen3-TTS-12Hz-1.7B-Base. The README does not say whether its outputs are in the 954-clip set. [INFERENCE] They probably are not, but this is not recorded. The Qwen3-TTS model card on Hugging Face declares `apache-2.0` (`https://huggingface.co/api/models/Qwen/Qwen3-TTS-12Hz-1.7B-Base`, sha `fd4b2543…`).
- Real-recording consent is stated in `app/src/main/assets/legal/NOTICE.txt` lines 72–73 ("consented maintainer/spouse recordings and Piper-generated synthetic clips").
- Piper voices: `docs/legal/piper-voice-review.md` finds that the Sherpa-packaged Piper voices are Lessac- or Ryan-derived and not releasable as downloaded. The 11 voices used for the 780 clips are not recorded, so their lineage cannot be checked.

Verdict: **unclear** (blocking in practice). Reasons, each [INFERENCE] where marked:
1. Audio from unidentified Piper voices, some of which may be Lessac/Ryan-derived, is in the positive set.
2. The classifier is trained on embeddings from the frozen Stage 2 backbone (item 2 above). [INFERENCE] Whether the upstream NC-SA sentence reaches a classifier trained on top of it is a legal question not answered upstream.
3. The negative corpus source (ACAV100M features or `get_negative_clips`) is not recorded and its licence was not checked.

Resolution:
- Name the 11 Piper voices and the negative corpus in `training/wakeword/README.md`, with per-voice licence records from `docs/legal/piper-voice-review.md`.
- Record whether any Qwen3-TTS clips are in the training set.
- Resolve item 2 with the upstream maintainer's answer on file 1 and 2, or retrain Stage 3 only on releasable audio over a releasable backbone.

### 4. Whisper `tiny.en` ONNX export (STT path)

- `csukuangfj/sherpa-onnx-whisper-tiny.en` at `main` = `d026532c022fa99fd789d6b32446a1df7b6bfc43` (HF API). Files: `tiny.en-encoder.int8.onnx`, `tiny.en-decoder.int8.onnx`, `tiny.en-tokens.txt`, plus float variants and `test_wavs/`. No `README.md`, no `LICENSE`. HF API `cardData.license: null`. Source: `https://huggingface.co/api/models/csukuangfj/sherpa-onnx-whisper-tiny.en`.
- Repo `docs/RELEASE_ATTRIBUTION_SIGNOFF.md` line 74 already records that this export "identifies no licence for the exact exported files".
- OpenAI code: [`openai/whisper` LICENSE](https://github.com/openai/whisper/blob/main/LICENSE), MIT, "Copyright (c) 2022 OpenAI" (GitHub licence API `spdx_id: MIT`).
- OpenAI weights: HF card for [`openai/whisper-tiny.en`](https://huggingface.co/openai/whisper-tiny.en), revision `87c7102498dcde7456f24cfd30239ca606ed9063`, front matter line 45: `license: apache-2.0`. **The MIT licence covers the code; the published weight card says Apache-2.0.** The issue's premise that Whisper is MIT is correct for code only.
- Export lineage: [sherpa-onnx Whisper export recipe](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/whisper/export-onnx.html), as cited in the signoff. sherpa-onnx code is Apache-2.0 (GitHub licence API `spdx_id: Apache-2.0`). This establishes lineage, not an express grant for the exported files.

Verdict: **unclear**. [INFERENCE] The upstream weights are declared Apache-2.0 at source, so an ONNX conversion of them is likely release-OK with notice. That needs the export's lineage to be stated for these exact files. Resolution:
- A written statement from csukuangfj (or a LICENSE/notice added to the export repo) that the exported files are derived from `openai/whisper-tiny.en` under Apache-2.0; or
- Re-export locally from `openai/whisper-tiny.en` at the pinned revision with the recorded export recipe, and ship the Apache-2.0 notice (plus OpenAI's attribution) in `NOTICE`.

## Evidence index

- openWakeWord README at `v0.5.1`: https://github.com/dscripka/openWakeWord/blob/1eec2158c5c54150ac5f4c15065adacb1003b1e7/README.md (lines 141, 143, 242).
- openWakeWord LICENSE at `v0.5.1` (Apache-2.0 text): https://github.com/dscripka/openWakeWord/blob/1eec2158c5c54150ac5f4c15065adacb1003b1e7/LICENSE.
- openWakeWord release `v0.5.1` assets: https://github.com/dscripka/openWakeWord/releases/tag/v0.5.1.
- openWakeWord `training/train.py` at `v0.5.1`: https://github.com/dscripka/openWakeWord/blob/1eec2158c5c54150ac5f4c15065adacb1003b1e7/training/train.py.
- Google speech_embedding: https://tfhub.dev/google/speech_embedding/1 (404, redirects to Kaggle); https://www.kaggle.com/models/google/speech-embedding (licence field not readable).
- csukuangfj Whisper export: https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny.en/tree/d026532c022fa99fd789d6b32446a1df7b6bfc43.
- OpenAI Whisper code: https://github.com/openai/whisper/blob/main/LICENSE. OpenAI weights card: https://huggingface.co/openai/whisper-tiny.en (rev `87c7102498dcde7456f24cfd30239ca606ed9063`).
- Repo: `training/wakeword/README.md`, `training/train.py`, `training/wakeword/generate_hey_jandal.py`, `docs/RELEASE_ATTRIBUTION_SIGNOFF.md`, `docs/legal/piper-voice-review.md`, `app/src/main/assets/legal/NOTICE.txt`.

## Not checked

- Google's licence text for `speech_embedding`: the Kaggle page did not expose it to static fetches. Needs a browser or authenticated Kaggle API.
- The ACAV100M feature file's licence. It is not in the repo and was not located upstream in this review.
- Piper voice identities for the 780 Stage 3 positives.
