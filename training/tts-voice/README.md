# Piper TTS Voice Training — NZ Accent (issue #756)

Produces a custom Piper TTS `.onnx` voice model trained on a NZ-accented speaker,
for use as Jandal's local voice output engine.

## Strategy

Piper requires ~1 hour of clean, labelled speech audio for a good single-speaker
model. Rather than recording 1 hour manually, we generate the training corpus
using Qwen3-TTS to clone the reference voice across ~2000 diverse sentences,
then train Piper on the synthetic data.

```
Reference voice clip (training/shared/reference_voice_16k.wav)
    │
    ▼
Qwen3-TTS voice clone → 2000 × sentence WAVs (16kHz mono)
    │
    ▼
Piper fine-tune (piper-train) → model.onnx + model.onnx.json
    │
    ▼
core/voice/src/main/assets/tts/jandal_nz.onnx
```

## Status

Planned — depends on wake word work (#984, #985) shipping first.
Tracked in issue #756.

## Planned steps

1. **Generate sentence corpus** — adapt `generate_hey_jandal.py` to synthesise
   ~2000 diverse English sentences (from a corpus like LJSpeech prompts or
   custom NZ-flavoured text) in the cloned voice
2. **Prepare Piper dataset** — label each WAV with its transcript in Piper's
   `metadata.csv` format
3. **Fine-tune** — run `piper-train` on the labelled corpus
4. **Export** — convert to `.onnx` + `.onnx.json` config
5. **Bundle** — place in `core/voice/src/main/assets/tts/`

## Sentence corpus generation script

To be written in `training/tts-voice/generate_corpus.py` — will share the
Qwen3-TTS cloning logic from `training/wakeword/generate_hey_jandal.py`.
