# Piper voice licence and provenance review (#1263)

Checked 2026-11-21. This is an engineering record, not legal advice. It supersedes the 2026-11-12 note that used to live at `docs/release-audit/PIPER_VOICE_LICENCE_REVIEW.md`. That note pinned `rhasspy/piper-voices` at `5041a7a`, so it missed the upstream retrains described below. It also could not confirm what the Sherpa-ONNX archives contain.

Scope: every `SherpaPiperVoice` entry except `CoriHigh`, which #1268 had made release-visible. Since #1793 (merged as `46539142`), no Piper voice, Cori included, is release-visible in v0.1: the Sherpa/Piper/eSpeak runtime is excluded from release. Kokoro and Inflect are out of scope. So is the eSpeak-ng GPL posture: the owner accepted it under #1474, which is now closed, although source-to-binary provenance for the packs' `espeak-ng-data` is still not established.

## Result

**No voice is release-OK as Jandal downloads it today.** Every reviewed voice stays `releaseVisible = false`. No code, test, NOTICE or `app/release-attribution-metadata.json` change follows from this review.

Six voices (Jenny, Alba, Aru, VCTK, Joe, Amy) were retrained upstream between 9 and 20 Nov 2026 on a base that does not descend from Lessac. Jandal does not download those retrained weights. It downloads Sherpa-ONNX archives published between June 2025 and April 2026, and every one of those archives contains the older pre-retrain weights (verified below). Ten were fine-tuned from Lessac or Ryan. Lessac and Ryan are themselves trained from scratch on those restricted datasets. Jenny, Alba, Aru, VCTK and Joe could become release-OK with conditions (see "Path to release"). Amy cannot, whatever it is trained on.

## What the app downloads

`SherpaPiperVoice.downloadUrl` is `https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/<assetDirectoryName>.tar.bz2`. The download key is the Piper voice id. Each archive contains `<id>.onnx`, `<id>.onnx.json`, `MODEL_CARD`, `tokens.txt` and `espeak-ng-data/`.

How each archive was verified:

1. Every archive was downloaded and its SHA-256 matched the `digest` that the GitHub release API reports for that asset.
2. The upstream `.onnx` was downloaded from `rhasspy/piper-voices` at revision `5041a7a49ae5b3a0f30e6837769eb03fdbb231bd` ("pre-retrain"). For retrained voices it was also downloaded at revision `e929968c3761f1c281875787685a745863db838d` ("current", 2026-11-20). Every download matched the Hugging Face LFS oid.
3. The ONNX initializer tensors were compared by value. Sherpa adds `metadata_props` (`model_type`, `comment`, `language`, `voice`, `has_espeak`, `n_speakers`, `sample_rate`), so whole-file hashes differ by design.

| Voice id | Sherpa asset (uploaded, sha256) | Weights equal pre-retrain upstream | Weights equal current upstream | `MODEL_CARD` inside archive: Training |
|---|---|---|---|---|
| en_GB-jenny_dioco-medium | 2025-06-30, `a0888024…` | yes | **no** | Finetuned from lessac medium |
| en_GB-southern_english_female-low | 2025-06-30, `448e7723…` | yes | not retrained | Finetuned from Ryan low |
| en_GB-northern_english_male-medium | 2025-06-30, `2bb2c1e7…` | yes | not retrained | Finetuned from lessac medium |
| en_GB-alan-medium | 2025-06-30, `a48d4017…` | yes | not retrained | Finetuned from lessac medium |
| en_GB-alba-medium | 2025-06-30, `fcd45962…` | yes | **no** | Finetuned from lessac medium |
| en_GB-aru-medium | 2025-06-30, `15b6e7a5…` | yes | **no** | Finetuned from lessac medium |
| en_GB-semaine-medium | 2025-06-30, `b2a1e390…` | yes | not retrained | Finetuned from lessac medium |
| en_GB-vctk-medium | 2025-06-30, `abafd35b…` | yes | **no** | Finetuned from lessac medium |
| en_US-amy-medium | 2026-04-22, `9a5d1fc4…` | yes | **no** | Finetuned from lessac medium |
| en_US-joe-medium | 2025-12-02, `261b903a…` | yes (1) | **no** | Finetuned from lessac medium |
| en_US-lessac-high | 2025-06-30, `8619d204…` | yes | not retrained | Trained from scratch (Blizzard 2013 Lessac) |
| en_US-ryan-high | 2025-06-30, `6a71edf4…` | yes | not retrained | Trained from scratch (RyanSpeech) |

(1) The Joe archive's graph was re-exported: it has 396 initializers against 401 upstream, or 360 against 365 excluding anonymous `onnx::*` constants. Every tensor value still matches the pre-retrain weights and covers 100% of the parameters. It shares 0% of its parameters with the retrained weights.

## Per-voice verdicts

| Voice (enum) | Model card dataset (current card) | Dataset terms (primary source) | Lineage of the downloaded weights | Verdict |
|---|---|---|---|---|
| Jenny Dioco (`JennyDioco`) | [dioco-group/jenny-tts-dataset](https://github.com/dioco-group/jenny-tts-dataset/blob/ad714a8d15fa010b4ccc024df678822695238070/README.md), licence "See URL" | Custom README terms, no SPDX licence. Commercial use is permitted. Attribution is required in software that generates audio on user action: the voice must be called "Jenny", and "Jenny (Dioco)" where practical. You must not claim the dataset as your own. The speaker (Jenny, Irish) recorded it for the dataset and the README offers further recordings. There is no separate consent document. | Lessac medium, which is research-only | **Not releasable** as downloaded. Can be release-OK with conditions using the retrained weights. |
| Southern English Female (`SouthernEnglishFemale`) | [OpenSLR 83](https://www.openslr.org/83/), CC BY-SA 4.0 ([LICENSE](https://www.openslr.org/resources/83/LICENSE)) | CC BY-SA 4.0, © Google. Commercial use is allowed. Share-alike may reach the weights. | Ryan low, whose [card](https://huggingface.co/rhasspy/piper-voices/blob/5041a7a49ae5b3a0f30e6837769eb03fdbb231bd/en/en_US/ryan/low/MODEL_CARD) records CC BY-NC-SA 4.0. Not retrained upstream. | **Not releasable**: a non-commercial parent. |
| Northern English Male (`NorthernEnglishMale`) | OpenSLR 83, CC BY-SA 4.0 | As above | Lessac medium. Not retrained upstream. | **Not releasable**: Lessac lineage. Upstream [discussion #113](https://huggingface.co/rhasspy/piper-voices/discussions/113) asks about commercial use of this voice and is unanswered. |
| Alan (`AlanMedium`) | [mimic3-voices `en_UK/apope_low`](https://github.com/MycroftAI/mimic3-voices/tree/b239a9084e21fbaa7ac78ea6e31f5de1c31c8f42/voices/en_UK/apope_low), licence "See URL" | The per-voice [LICENSE](https://github.com/MycroftAI/mimic3-voices/blob/b239a9084e21fbaa7ac78ea6e31f5de1c31c8f42/voices/en_UK/apope_low/LICENSE) reads "Copyright 2022 Mycroft AI / All Rights Reserved". That overrides the repository's CC BY-SA 4.0. The voice is Alan Pope's, and no grant from him or Mycroft was found. | Lessac medium. Not retrained upstream. | **Not releasable**: all rights reserved, plus Lessac lineage. Upstream [discussion #119](https://huggingface.co/rhasspy/piper-voices/discussions/119) asks about this voice's terms and is unanswered. |
| Alba (`AlbaMedium`) | [Alba speech corpus, Edinburgh DataShare 10283/3270](https://datashare.ed.ac.uk/handle/10283/3270) | `dc.rights`: CC BY 4.0. "The Creative Commons licence does not affect the moral rights of the voice talent … Any use derogatory to the voice talent is prohibited." Citation: Valentini-Botinhao, C.; Yamagishi, J. (2019), https://doi.org/10.7488/ds/2506 | Lessac medium | **Not releasable** as downloaded. Can be release-OK with conditions using the retrained weights. |
| Aru (`AruMedium`, 12 speakers) | The card URL (liverpool.ac.uk/…/speech-corpus/) returns HTTP 404. The dataset is [ARU speech corpus, DataCat 681](https://datacat.liverpool.ac.uk/681/). | The full archive is **CC BY 3.0**. Only the README PDF is CC BY 4.0, which is what the card claims for the whole. DataCat: "Ethics clearance was gained from all participants to make these speech recordings available to the public." Citation: Hopkins, C.; Graetzer, S.; Seiffert, G. (2019), https://doi.org/10.17638/datacat.liverpool.ac.uk/681 | Lessac medium | **Not releasable** as downloaded. Can be release-OK with conditions using the retrained weights. |
| Semaine (`SemaineMedium`, 4 speakers) | [marytts/dfki-semaine-data](https://github.com/marytts/dfki-semaine-data/blob/cbeb97b9bb0deecf4355220fcfba280a7b30983a/README.md) | "Copyright 2009 DFKI GmbH", CC BY-NC-SA 4.0 | Lessac medium. Not retrained upstream. | **Not releasable**: the dataset itself is non-commercial, so retraining cannot fix it. The #1268 decision stands. |
| VCTK (`VctkMedium`, 109 speakers) | [CSTR VCTK Corpus 0.92, DataShare 10283/3443](https://datashare.ed.ac.uk/handle/10283/3443) | `dc.rights`: "Creative Commons Attribution 4.0 International Public License". Citation: Yamagishi, J.; Veaux, C.; MacDonald, K. (2019), https://doi.org/10.7488/ds/2645 | Lessac medium | **Not releasable** as downloaded. Can be release-OK with conditions using the retrained weights. Upstream [discussion #105](https://huggingface.co/rhasspy/piper-voices/discussions/105) names this voice as Lessac-derived. |
| Amy (`AmyMedium`) | [MycroftAI/mimic3-voices](https://github.com/MycroftAI/mimic3-voices/tree/b239a9084e21fbaa7ac78ea6e31f5de1c31c8f42), licence "See URL" | That repository has no Amy voice or dataset. Its voices are `en_US/{cmu-arctic,hifi-tts,ljspeech,m-ailabs,vctk}_low` and `en_UK/apope_low`. No licence and no speaker identity is documented. | Lessac medium | **Not releasable**: provenance can't be traced. Upstream [COMMERCIAL_USE.md](https://github.com/OHF-Voice/piper1-gpl/blob/dbb48a47031d80d96a0ef6837255986bb7d1b9f7/docs/COMMERCIAL_USE.md) says Amy's "recordings have no documented license, so it is not listed", even after the retrain. |
| Joe (`JoeMedium`) | [OHF-Voice/voice-datasets](https://github.com/OHF-Voice/voice-datasets/blob/1692a7c509e35eceb5d3bd6a7581a104629ded0f/README.md), CC0 | "These datasets are licensed under CC0 (public domain). If you use them, please cite this repository!" The data was crowdsourced by Nabu Casa for Home Assistant's Year of Voice. | Lessac medium | **Not releasable** as downloaded. Can be release-OK with conditions using the retrained weights. |
| Lessac (`LessacHigh`) | [Blizzard 2013 Lessac](https://www.cstr.ed.ac.uk/projects/blizzard/2013/lessac_blizzard2013/) | The [licence](https://www.cstr.ed.ac.uk/projects/blizzard/2013/lessac_blizzard2013/license.html) is from Voice Factory International and Lessac Technologies. Clause 3.2 grants a "non-exclusive and non-transferable licence, without the right to sub-licence, to use the Materials exclusively for Research Purposes only". Research Purposes excludes commercial voice-synthesis products. | Trained from scratch on that data | **Not releasable**: research-only. |
| Ryan (`RyanHigh`) | [RyanSpeech on Kaggle](https://www.kaggle.com/datasets/roholazandie/ryanspeech). The Kaggle page returns HTTP 404 and the Kaggle API returns 403 to an anonymous client. | The card says CC BY-NC-SA 4.0. The author's [ryan-tts README](https://github.com/roholazandie/ryan-tts/blob/64f085a5abf26362f64eb7b23885e973336c65b9/README.md) carries a CC BY-NC 4.0 badge and gates the dataset behind a request form. Both are non-commercial. | Trained from scratch | **Not releasable**: non-commercial dataset. |

## Upstream licensing position and disputes

- The Hugging Face repository carries the metadata tag `license: mit`. Upstream [LICENSING.md](https://github.com/OHF-Voice/piper1-gpl/blob/31c3011967fe18501621032937acdcd6bd358438/docs/LICENSING.md) says the Piper project "makes **no license grant of its own over voice models**". A card's `License` line covers "that voice's own dataset only — not the datasets of any voice it was fine-tuned from". The project "holds no permission from Lessac Technologies or CSTR". Upstream asks redistributors to ship each voice's `MODEL_CARD`. **The MIT tag is therefore not a basis for clearing any voice, Cori included.**
- In [discussion #105](https://huggingface.co/rhasspy/piper-voices/discussions/105) the maintainer (synesthesiam) answered a commercial on-device redistributor. On 2026-09-10 he wrote that whether weights are derivative works "is left open to the user's discretion". On 2026-09-17 he wrote that "users would need to enter a separate agreement with them about redistribution rights". In the same thread the requester points out that the Sherpa-ONNX packaged copies predate the retraining.
- In [discussion #94](https://huggingface.co/rhasspy/piper-voices/discussions/94) the maintainer said on 2026-08-06: "This is something that I don't think has been tested yet legally." That thread cites the earlier [rhasspy/piper discussion #271](https://github.com/rhasspy/piper/discussions/271) (2023-11-14). There the maintainer said the project "does not impose any additional licenses on the checkpoints or voice models" and that it is "the responsibility of the end user to make the ultimate judgement". The discussion reached no conclusion.
- Upstream [COMMERCIAL_USE.md](https://github.com/OHF-Voice/piper1-gpl/blob/dbb48a47031d80d96a0ef6837255986bb7d1b9f7/docs/COMMERCIAL_USE.md) lists voices whose whole chain allows commercial use. It warns: "A copy downloaded before the retrain is the old version and **does not qualify**, even though it has the same file name." As of 2026-11-18 it lists Jenny, Alba (hifi_tts_92 base) and Joe (rdh base). Aru and VCTK were retrained later, on [2026-11-20](https://huggingface.co/rhasspy/piper-voices/commit/e929968c3761f1c281875787685a745863db838d) and [2026-11-19](https://huggingface.co/rhasspy/piper-voices/commit/42301ec547bc2939386cf0ae9f9050ca05b1d3af), and are not on the list yet. Their current cards name hifi_tts_92 as the base.
- Retrain commit: [`cf910fb`](https://huggingface.co/rhasspy/piper-voices/commit/cf910fb59f1d79ab3b625a63993a108751c6c25d), "Replaces lessac-derived voices with ones finetuned from bases trained from scratch".
- New bases:
  - `en_US-hifi_tts_92-medium` is trained from scratch on Hi-Fi TTS reader 92 (Cori Samuel), [OpenSLR 109](https://www.openslr.org/109/), **CC BY 4.0**, a dataset built from LibriVox audiobooks.
  - `nl_BE-rdh-medium` is trained from scratch on [r-dh/dutch-vl-tts](https://github.com/r-dh/dutch-vl-tts), CC0.

## Path to release (Jenny, Alba, Aru, VCTK, Joe)

All of these conditions must hold before `releaseVisible = true`:

1. **Change the weights source.** Download the retrained weights at an exact `rhasspy/piper-voices` revision (`e929968c3761f1c281875787685a745863db838d` or a later commit chosen at promotion time). Record that revision and the per-voice `.onnx` LFS oid in this doc and in `app/release-attribution-metadata.json`. Upstream retrains in place under the same file names, so a revision range or file name alone does not pin the weights. Alternatively, use a Sherpa asset whose initializers are shown, by the method above, to equal the pinned weights. The current Sherpa archives are not acceptable under any reading.
2. **Repackage for Sherpa-ONNX.** The retrained `.onnx` has no Sherpa metadata. Its `.onnx.json` declares `piper_version` 1.5.0 and adds `vowel_clusters`, `phoneme_counts`, `hop_length` and `default_speaker_id`. Its `phoneme_id_map` has 166 entries where the old one had 154 (Jenny was checked); existing ids are unchanged. Graph inputs are unchanged (`input`, `input_lengths`, `scales`). So `tokens.txt` must be regenerated and `add_meta_data.py` must be applied. [INFERENCE] Sherpa-ONNX 1.13.0 probably loads the graph. Whether the eSpeak frontend matches Piper 1.5's `vowel_clusters` handling is untested, so pronunciation needs checking on a device.
3. **Hosting and notices.** If Jandal hosts the repackaged archive, that is redistribution. Ship the upstream `MODEL_CARD` inside each pack and add the NOTICE entry below. The pack's `espeak-ng-data` falls under the eSpeak-ng GPL posture accepted in #1474 (closed). Its source-to-binary provenance must be recorded before any release.
4. **#1793 exclusion.** Since #1793, no Piper voice is release-visible in v0.1 and the Piper/eSpeak runtime is kept out of release artefacts. These conditions apply only once that exclusion is lifted. Any voice made release-visible must then also be recorded in `app/release-attribution-metadata.json`, which `:app:verifyReleaseAttributionMetadata` checks.

NOTICE text to add once a voice meets the conditions. The Hi-Fi TTS line is shared by the hifi_tts_92 group.

```
Piper voice "<name>" (rhasspy/piper-voices <id>, revision <sha>)
  Base model: en_US-hifi_tts_92-medium, trained on the Hi-Fi Multi-Speaker English TTS
  Dataset (reader 92), E. Bakhturina, V. Lavrukhin, B. Ginsburg, Y. Zhang, NVIDIA,
  https://www.openslr.org/109/, licensed CC BY 4.0
  (https://creativecommons.org/licenses/by/4.0/). Model weights are derived from it.
```

Add one of these per voice:

- **Jenny** (the UI name must be "Jenny", and "Jenny (Dioco)" where practical; the current display name "Jenny Dioco" should become "Jenny (Dioco)"):
  `Voice: Jenny (Dioco). Trained on the Jenny TTS dataset by Dioco, https://github.com/dioco-group/jenny-tts-dataset.`
- **Alba**: `Trained on the Alba speech corpus, Valentini-Botinhao, Cassia; Yamagishi, Junichi (2019), University of Edinburgh, https://doi.org/10.7488/ds/2506, licensed CC BY 4.0. Model weights are derived from the dataset. The moral rights of the voice talent are not licensed; use derogatory to the voice talent is prohibited.`
- **Aru**: `Trained on the ARU speech corpus, Hopkins, Carl; Graetzer, Simone; Seiffert, Gary (2019), University of Liverpool, https://doi.org/10.17638/datacat.liverpool.ac.uk/681, licensed CC BY 3.0 (https://creativecommons.org/licenses/by/3.0/). Model weights are derived from the dataset.`
- **VCTK**: `Trained on the CSTR VCTK Corpus v0.92, Yamagishi, Junichi; Veaux, Christophe; MacDonald, Kirsten (2019), University of Edinburgh, https://doi.org/10.7488/ds/2645, licensed CC BY 4.0. Model weights are derived from the dataset.`
- **Joe** (rdh base, not hifi_tts_92, so it uses this text instead of the Hi-Fi TTS line): `Piper voice "Joe" (rhasspy/piper-voices en_US-joe-medium, revision <sha>). Trained on the Nabu Casa voice-datasets "joe" recordings (CC0), https://github.com/OHF-Voice/voice-datasets; base model nl_BE-rdh-medium (CC0, https://github.com/r-dh/dutch-vl-tts).` Attribution is a courtesy here, not an obligation.

## Cori (reference only, unchanged)

`en_GB-cori-high` is unchanged upstream: the `.onnx` LFS oid `470b4dd6…` has been the same since 2024-03-12, and the card is identical at both revisions. It says "Trained from scratch" on LibriVox (public domain). The training notes link to [brycebeattie.com/files/tts](https://brycebeattie.com/files/tts/), where the trainer lists Cori (high) as "License: public domain" and adds "Feel free to use these for any legal and ethical purpose". COMMERCIAL_USE.md lists it among from-scratch voices. Because upstream says the repository's MIT tag is not a grant, any future release basis for Cori is the trainer's public-domain statement plus the public-domain dataset, not "MIT repository". Cori is currently excluded from v0.1 by #1793. The Sherpa Cori archive (2025-06-30, sha256 `42922f07…`) was weight-compared by the same method: 460 initializers on each side, matching 100% of parameters by value against the upstream `.onnx` (LFS oid `470b4dd6…`). Its `MODEL_CARD` points to Bryce Beattie's training notes.

## Candidate English voices (not added)

These voices from `rhasspy/piper-voices` have a clean dataset chain and an existing Sherpa-ONNX asset. Each would need the same archive weight check before use.

| Voice | Accent / speaker | Dataset and chain | Sherpa asset | Notes |
|---|---|---|---|---|
| `en_GB-cori-medium` | British (RP) female | The Sherpa asset (2025-06-30) predates the retrain, so it matches the [pre-retrain card](https://huggingface.co/rhasspy/piper-voices/blob/5041a7a49ae5b3a0f30e6837769eb03fdbb231bd/en/en_GB/cori/medium/MODEL_CARD): trained from scratch on LibriVox, public domain. The [current card](https://huggingface.co/rhasspy/piper-voices/blob/e929968c3761f1c281875787685a745863db838d/en/en_GB/cori/medium/MODEL_CARD) is a hifi_tts_92 fine-tune, CC BY 4.0. | `vits-piper-en_GB-cori-medium.tar.bz2`, ~67 MB | Best fit for NZ/UK. Same voice as the catalogue's Cori (high) at about 58% of the download. |
| `en_US-ljspeech-high` | US female | [Card](https://huggingface.co/rhasspy/piper-voices/blob/e929968c3761f1c281875787685a745863db838d/en/en_US/ljspeech/high/MODEL_CARD): LJ Speech, public domain, trained from scratch by Bryce Beattie | `vits-piper-en_US-ljspeech-high.tar.bz2`, ~116 MB | Well-known clean voice. |
| `en_US-norman-medium` | US male | [Card](https://huggingface.co/rhasspy/piper-voices/blob/e929968c3761f1c281875787685a745863db838d/en/en_US/norman/medium/MODEL_CARD): LibriVox, public domain, trained from scratch | `vits-piper-en_US-norman-medium.tar.bz2`, ~67 MB | Clean male option. |
| `en_US-kristin-medium` | US female | [Card](https://huggingface.co/rhasspy/piper-voices/blob/e929968c3761f1c281875787685a745863db838d/en/en_US/kristin/medium/MODEL_CARD): LibriVox, public domain, trained from scratch | `vits-piper-en_US-kristin-medium.tar.bz2`, ~67 MB | |
| `en_US-john-medium` | US male | [Card](https://huggingface.co/rhasspy/piper-voices/blob/e929968c3761f1c281875787685a745863db838d/en/en_US/john/medium/MODEL_CARD): LibriVox, public domain, fine-tuned from Kristin (public domain) | `vits-piper-en_US-john-medium.tar.bz2`, ~67 MB | |

Not chosen:

- `en_US-kathleen-medium` and `en_US-mike-medium` have clean CC0 chains but no Sherpa asset. `kathleen-low` descends from Ryan.
- `en_US-libritts_r-medium`'s card still says it was fine-tuned from Lessac.
- `en_US-bryce-medium` has an undocumented base.

Apart from Cori, no clean voice in `rhasspy/piper-voices` has a native NZ or UK accent. For UK accents, the retrained Alba, Jenny, Aru and VCTK are the route.

## Sources

- App code: `core/voice/src/main/java/com/kernel/ai/core/voice/SherpaPiperVoice.kt` (`downloadUrl`, `downloadKey`, `releaseVisible`).
- Sherpa-ONNX `tts-models` release (id 130612623): https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models
- rhasspy/piper-voices:
  - pre-retrain revision: https://huggingface.co/rhasspy/piper-voices/tree/5041a7a49ae5b3a0f30e6837769eb03fdbb231bd
  - current revision: https://huggingface.co/rhasspy/piper-voices/tree/e929968c3761f1c281875787685a745863db838d
  - model card URL pattern: `https://huggingface.co/rhasspy/piper-voices/blob/<rev>/en/<locale>/<voice>/<quality>/MODEL_CARD`
- Upstream policy: [LICENSING.md @31c3011](https://github.com/OHF-Voice/piper1-gpl/blob/31c3011967fe18501621032937acdcd6bd358438/docs/LICENSING.md), [COMMERCIAL_USE.md @dbb48a4](https://github.com/OHF-Voice/piper1-gpl/blob/dbb48a47031d80d96a0ef6837255986bb7d1b9f7/docs/COMMERCIAL_USE.md)
- Upstream discussions:
  - [#105, Lessac-derived licensing](https://huggingface.co/rhasspy/piper-voices/discussions/105)
  - [#94, Lessac-derived Polish voices](https://huggingface.co/rhasspy/piper-voices/discussions/94)
  - [#113, Northern English Male](https://huggingface.co/rhasspy/piper-voices/discussions/113)
  - [#119, Alan](https://huggingface.co/rhasspy/piper-voices/discussions/119)
- Dataset sources are linked in the verdict table. Also:
  - Hi-Fi TTS: https://www.openslr.org/109/
  - rdh: https://github.com/r-dh/dutch-vl-tts
  - Bryce Beattie's voices: https://brycebeattie.com/files/tts/

### Unreachable or inconsistent sources

- The ARU card URL returns 404. DataCat 681 was used instead, and its CC BY 3.0 conflicts with the card's CC BY 4.0.
- The RyanSpeech Kaggle page returns 404 and the Kaggle API returns 403. The non-commercial status rests on the card and the author's GitHub README.
- The Amy card's URL does not contain the claimed dataset.
