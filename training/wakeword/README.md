# Hey Jandal Wake Word Training

Produces `hey_jandal.onnx` — the Stage 3 classifier for the openWakeWord pipeline used in Kernel AI.

## Architecture

The runtime uses a fixed 3-stage pipeline in `OnnxWakeWordDetector`:

```
16kHz mono PCM (80ms = 1280 samples)
  → melspectrogram.onnx      Stage 1: raw PCM → mel-spectrogram patch
  → embedding_model.onnx     Stage 2: mel → 96-dim embedding (one per 80ms frame)
  → ring buffer (28 frames ≈ 2.24s context)
  → hey_jandal.onnx          Stage 3: 28×96 window → confidence [0,1]
```

You only train Stage 3. Stages 1 and 2 are pre-built shared models downloaded automatically by `train.py`.

All three ONNX files must be placed in `app/src/main/assets/models/wakeword/` for the toggle to activate.

## Setup

```bash
cd <repo root>
python -m venv training/venv
source training/venv/bin/activate
pip install -r training/requirements.txt
# openWakeWord from source (PyPI release may lag)
pip install git+https://github.com/dscripka/openWakeWord.git
```

## Step 1 — Generate positive clips (already done)

954 positive clips exist in `training/wakeword/data/positives/` (local only, gitignored):
- 174 from real recordings in `training/wakeword/data/real_recordings/` (29 originals × augmented)
- 780 from Piper TTS across 11 voices and 13 phonetic variants

To regenerate or add more clips:

```bash
# TTS clips (Piper, multi-voice)
python training/wakeword/generate_tts_clips.py \
    --voices_dir training/wakeword/piper_voices \
    --real_dir training/wakeword/data/real_recordings \
    --output_dir training/wakeword/data/positives \
    --augment_factor 5
```

Piper voices are downloaded separately (~60MB each, gitignored):
```bash
# Example: download en_AU-karen-medium
python -c "
from piper.download import get_voices, ensure_voice_exists
voices = get_voices(update_voices=True)
ensure_voice_exists('en_AU-karen', voices, 'training/wakeword/piper_voices')
"
```

## Step 2 — Add real recordings

Place your own "Hey Jandal" recordings as 16kHz mono WAV files in `training/wakeword/data/real_recordings/`. Convert M4A files:

```bash
for f in training/wakeword/data/real_recordings/raw_m4a/*.m4a; do
    ffmpeg -i "$f" -ar 16000 -ac 1 \
        "training/wakeword/data/real_recordings/$(basename "${f%.m4a}").wav"
done
```

## Step 3 — Train

```bash
source training/venv/bin/activate

python training/train.py \
    --positive_dir training/wakeword/data/positives \
    --output_dir   training/wakeword/output \
    --epochs       200 \
    --false_positive_weight 5.0 \
    --neg_count    1000
```

Output in `training/wakeword/output/`:
- `hey_jandal.onnx` — trained Stage 3 classifier
- `melspectrogram.onnx` — downloaded Stage 1 backbone
- `embedding_model.onnx` — downloaded Stage 2 backbone

Training on CPU (5700X3D) typically takes 5–15 minutes with 954 positive clips.

## Step 4 — Deploy

```bash
python training/train.py \
    --positive_dir training/wakeword/data/positives \
    --output_dir   training/wakeword/output \
    --deploy       app/src/main/assets/models/wakeword

# Then build and install
./gradlew installDebug
```

Or manually copy:
```bash
cp training/wakeword/output/{melspectrogram,embedding_model,hey_jandal}.onnx \
   app/src/main/assets/models/wakeword/
./gradlew installDebug
```

## Step 5 — Validate

After installing:
1. Open Settings → Voice → Hey Jandal and enable the toggle (should no longer show "unavailable")
2. Say "Hey Jandal" — voice input should trigger within ~2.5s
3. Run 10 minutes of continuous NZ speech — target <1 false activation/hour

If FP rate is too high, retrain with a higher `--false_positive_weight` (try 10.0, 20.0). Tracking in issue #986.

## Phonetic notes

"Jandal" = /ˈdʒændl/ — short front /a/ as in "jam". The training set includes phonetic variants (`Jandel`, `Handal`, `Handel`) to help the model generalise across the NZ vowel. Voice diversity across 11 Piper voices matters more than clip count.

## What's gitignored

| Path | Reason |
|------|--------|
| `training/venv/` | Python venv |
| `training/wakeword/data/` | Large audio dataset (~200MB) |
| `training/wakeword/piper_voices/` | Piper ONNX voices (~600MB) |
| `training/wakeword/output/` | Training outputs (deployed to assets/) |
| `training/shared/reference_voice*.wav` | Personal audio |

Source scripts (`generate_tts_clips.py`, `generate_hey_jandal.py`, `train.py`) and this README are tracked.
