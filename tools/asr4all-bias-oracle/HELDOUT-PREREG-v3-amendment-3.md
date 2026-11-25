# #2093 pre-registration v3, amendment 3: synthetic gap-fill set

Scope: a SYNTHETIC supplementary set for the 14 owner script lines not recorded (R17–R30), plus noisy copies of R21–R30. It is scored with the same frozen v3 code and asset, β 5 primary. It is reported separately from the owner's real recordings and is never pooled into the real-recording pass/fail. The real-recording verdict (amendment 2 and README) stands on its own. A combined table may appear only if labelled "real + synthetic, not pre-registered for go/no-go".

## Timing (honest record)

- This amendment was written and committed while `synth_gap.py` was being prepared, before the full 42-clip synthesis was run and before any gap clip was scored.
- Disclosure: during the one-line method check I ran one ASR pass (faster-whisper medium.en) on the R17 check takes (take 1 result read; takes 2–3 not confirmed). This was an intelligibility check of the synthesis method, not scoring. No oracle output or dump exists for gap clips. The check result is recorded below.


## Differences from v1 (not a new speaker)

v1 synthetic set used the same reference file (`clone_24k.wav`, 52 s) and the same Qwen3-TTS 1.7B Base model, but x-vector-only mode (no transcript). This set uses the same file in full voice-clone (ICL) mode with its transcript. Same voice, different clone method. It tests unrecorded lines and the ICL method, not a new speaker, and not the owner's real microphone or room.

## Pipeline (frozen)

- Model: `Qwen/Qwen3-TTS-12Hz-1.7B-Base`, bf16, sdpa. Script: `synth_gap.py` (sha256 `516af379f386eb5cfeaa7fc18295fb27e41842b39e959093bf1ff74d7d0a687d`).
- Reference audio: `~/jandal-voice/reference/clone_24k.wav`, sha256 `daee1f65d402a0a9c60857a5d6a3afc048d092fa9e8526e5e4a305b185694466`, 51.95 s, 24 kHz mono.
- Reference transcript: `~/jandal-voice/reference/clone_24k.txt`, sha256 `0463bf5f771ff3371cea5e6ba48fcdc187ee689a3cdf92ac6c8acc1edf7cfdc1`. Produced by faster-whisper medium.en (CPU int8, 16 kHz input). Cross-checked with small.en: 118 words each, word-sequence similarity 0.932. Read by the operator for coherence. The transcript is private and not in git.
- Clone mode: ICL, `create_voice_clone_prompt(ref_audio, ref_text=<transcript>, x_vector_only_mode=False)`.
- Fallback not used: `ref_rainbow.wav` (19 s) was not needed, because the 52 s clip produced intelligible output in the check below.
- Takes: 3 per line, seed `8100 + 100·take + index` (index = line position in `gap_lines.json`). Output: 16 kHz mono PCM_16, duration ≤ 5000 ms.
- Line list: `gap_lines.json` (sha256 `2b9a5c8880547c92d31c829c2068f1c4db07c5685469bcaeacf3672613c71b9c`), derived from the owner's 50-row line list (`b9af729b…696`). Only ids R17–R30 are used. Text stays private.
- Gold: the place name for each line (display forms as in the asset; `Oamaru` per the asset spelling). Classes: gold_navigate 5 lines, gold_weather 6 lines, gold_bare 3 lines (R21, R25, R27).

## Noisy copies (R21–R30)

- Source: each R21–R30 clean take (30 takes). Noisy copies at 10 dB and 5 dB SNR, 60 files. Script: `make_noisy.py` (sha256 `8e8fc9263a3adb6802340feef3b6ae582834b008034d656af6f39cba8eaf3ee1`).
- Noise source: generated locally by the script. Type "babble-like": pink noise with a slow 0.5–4 Hz random envelope. It approximates a TV or kitchen background. It is NOT a recording of either. No third-party audio is used, so there is no licence to record. There is no fan-type noise in this set.
- SNR defined on whole-clip RMS: 10·log10(P_speech / P_noise). Peak-normalised only if the mix would clip.
- Noisy clips are classed `noisy_<gold class>` and reported separately. They are not scored as a condition pooled with the clean takes.

## Harness and scoring (frozen)

- Labels and class map: built by `make_gap_harness.py` (sha256 `fb406adc6acd00a0ef60b61ab0d3336b1729e6a8e20dc9ca454be93ed669f2c2`) from the private line list. Private, not in git.
- Dump: host harness, sizes M and L, same bridge and config as the v1 held-out set.
- Oracle: `oracle.py` sha256 `397329a5…` (v3), asset `nz_geonames_places.json` sha256 `fd1b0904…`, gate v2, β 5 primary, β 3 and 4 as sensitivity, all three slots.
- Scoring: `score_v2.py` sha256 `684896e9…`. Wilson 95% intervals.
- Criteria: the v3 + amendment-1 criteria 1–6 apply to the clean takes as a synthetic supplementary check. Criterion 6 is not evaluated. The noisy copies are scored for criterion 1 (gold substitutions = 0) only, reported separately.

## Owner listening set (not scored)

Six sentences rendered in the same ICL mode (one take each, seed `9000 + i`), 24 kHz mono MP3 at 64 kbps, kept privately in `~/private-acoustic-fixtures/listening-icl/`. These are not in the oracle, not scored, not part of any criterion. They exist for the owner to listen to the clone quality.

## Known limits

- Synthetic clone audio; not S23U→S21 or the owner's real microphone or room.
- Same voice reference as v1; different clone method. Not a new speaker.
- Noise is a generated approximation, not a real TV or kitchen recording.
- N per class is small (gold 5/6/3; noisy 3 takes × 2 SNR per line). Zero-count upper bounds are wide.
- R17 check: intelligibility of take 1 by ASR word similarity 0.769 against the line text. Takes 2–3 not confirmed. The full run will be checked by the same ASR before any scoring; a poorly intelligible take is recorded, not dropped.
