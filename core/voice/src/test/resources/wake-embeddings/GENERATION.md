# Wake embedding test fixtures — provenance

These JSON files are privacy-safe Stage-2 embedding streams used by
`WakeWordClassifierModelTest` to execute the real committed Stage 3
classifier (`hey_jandal.onnx`) on the #1432 resume-path window compositions.

No private audio is committed. The private household `natural_wake` WAV is
processed locally and only its 96-dim embedding stream is committed.

## Generation

`generate_wake_embeddings.py` (this directory) with:

- openWakeWord `AudioFeatures` (the exact feature path used by
  `training/train.py` — 76-row mel ring at 8-row strides, mel normalisation
  `value/10 + 2`, `embedding_model.onnx` input `[1,76,32,1]`).
- Seed 42 (numpy default_rng) for all synthetic audio.
- `--wake-wav`: the frozen private natural fixture (48 kHz mono int16),
  resampled to 16 kHz by linear interpolation, plus 2 s of appended digital
  silence (25 frames). Only the resulting embedding stream is committed.

## Model hashes (SHA-256)

| Model | SHA-256 |
|---|---|
| melspectrogram.onnx | `ba2b0e0f8b7b875369a2c89cb13360ff53bac436f2895cced9f479fa65eb176f` |
| embedding_model.onnx | `70d164290c1d095d1d4ee149bc5e00543250a7316b59f31d056cff7bd3075c1f` |
| hey_jandal.onnx (committed classifier) | `11bcdb0d800b3a93449197122bd9fb484c4b8db887364c629f6c975e3e38c206` |

The committed `models/wakeword/hey_jandal.onnx` test-resource copy is
byte-identical to `app/src/main/assets/models/wakeword/hey_jandal.onnx`
(verified at generation time).

## Tensor format

Each `*_stream.json` is a JSON array of float arrays: one 96-dim embedding
per 80 ms of audio (one embedding per 1280-sample chunk, 8 mel rows per
embedding). Embeddings are in chronological capture order.

| File | Frames | Semantic class |
|---|---|---|
| `fixture_stream.json` | 49 | natural wake phrase + 2 s trailing silence; phrase occupies frames ≈2–16 (windows `[2:18]`–`[5:21]` are the positive band) |
| `silence_stream.json` | 50 | digital zeros |
| `noise_white_stream.json` | 50 | gaussian white noise |
| `noise_pink_stream.json` | 50 | Voss-McCartney 1/f noise |
| `noise_speech_stream.json` | 50 | speech-shaped noise (4 Hz syllabic envelope) |
| `speech_formant_stream.json` | 50 | formant-synthesised /a/-/i/-/u/ (speech-like, non-wake) |

Synthetic streams are ~4 s; the committed classifier scores every 16-frame
window of all non-positive streams below the configured low threshold
(0.50), and the fixture's phrase windows at 0.84–0.93 (above the configured
high threshold 0.65) — CPU inference, no NNAPI involved.

## Window compositions used by the tests

| Test case | Composition | Expected band |
|---|---|---|
| positive consecutive | `fixture[2:18]`, `fixture[4:20]` | ≥ high (0.65) |
| silence | `silence[0:16]` | < low (0.50) |
| noise (white/pink/speech) | first 16 frames | < low |
| near phrase (formant) | `speech_formant[0:16]` | < low |
| preserved sparse-probe positive | ring: `silence[0]`×8 then `fixture[0:24]`; sliding windows | max ≥ high |
| preserved sparse-probe hard negative | ring: `silence[0]`×8 then `noise_white[0:24]`; all windows | < low |
| preserved immediate post-exit | ring: `silence[0]`×15 then `fixture[2]` | < low |
| old full-reset first evaluation | `fixture[1:17]` | < low (0.21 measured) |

## Regenerating

```sh
python3 core/voice/src/test/resources/wake-embeddings/generate_wake_embeddings.py \
  --model-dir app/src/main/assets/models/wakeword \
  --out core/voice/src/test/resources/wake-embeddings \
  --wake-wav /path/to/natural_wake.wav
```

Requires `pip install openwakeword` (and `soundfile`/`scipy` for WAV IO).
