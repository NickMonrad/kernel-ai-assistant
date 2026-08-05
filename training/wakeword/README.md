# Hey Jandal Wake Word Training

Produces `hey_jandal.onnx` — the Stage 3 classifier for the openWakeWord pipeline used in Kernel AI.

## Architecture

The runtime uses a fixed 3-stage pipeline in `OnnxWakeWordDetector`:

```
16kHz mono PCM (80ms = 1280 samples)
  → melspectrogram.onnx      Stage 1: raw PCM → mel-spectrogram patch
  → embedding_model.onnx     Stage 2: mel → 96-dim embedding (one per 80ms frame)
  → ring buffer (16 frames ≈ 1.28s context)
  → hey_jandal.onnx          Stage 3: 16×96 window → confidence [0,1]
```

You only train Stage 3. Stages 1 and 2 are pre-built shared models downloaded automatically by `train.py`.

Stage 3 is the openWakeWord reference dnn classifier (same topology as the
committed `hey_jandal.onnx`, issue #1444):

```
flatten(16×96=1536) → Linear(1536,32) → LayerNorm(32) → ReLU
→ Linear(32,32) → LayerNorm(32) → ReLU → Linear(32,1) → Sigmoid
```

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

1428 positive clips exist in `training/wakeword/data/positives_c1/` (local only, gitignored) for the #1444 recipe:
- 1248 from Piper TTS across 12 medium voices (en_GB alan/alba/aru/cori/jenny_dioco/northern_english_male/semaine, en_US amy/bryce/hfc_female/lessac/joe) and 13 phonetic variants
- 180 from the natural_wake fixture recording (30 copies × 1 raw + 5 augmented)

To regenerate:

```bash
# TTS clips (Piper, multi-voice) + real recordings
python training/wakeword/generate_tts_clips.py \
    --voices_dir training/wakeword/piper_voices \
    --real_dir training/wakeword/data/real_recordings \
    --output_dir training/wakeword/data/positives_c1 \
    --augment_factor 5
```

Augmentation (issue #1444, evidence-backed from the #1432 S21 captures):
- speed perturbation ±15%;
- AWGN at SNR 10–30 dB;
- **sub-frame phase jitter** — each clip is shifted by a random 0–1279-sample
  offset so the phrase crosses the 1280-sample mel chunk grid at every
  sub-frame phase (the measured S21 capture failure c120-06 fires when its
  PCM is shifted 384 samples, i.e. its phase was the discriminator);
- **peak normalisation uniform(0.05, 0.95)** — the measured S21 capture
  phrase peaks are 0.03–0.51 of full scale; the original recipe only covered
  0.7–0.95.

Disable/change via `--aug_phase_jitter 0`, `--aug_peak_min`, `--aug_peak_max`.

Piper voices are downloaded separately (~60MB each, gitignored) from
`https://huggingface.co/rhasspy/piper-voices/resolve/main/en/<locale>/<name>/medium/<voice>.onnx`.

## Step 1b — Generate negative clips

The original ACAV pre-computed negatives (5.6M windows) are not available on
the training machine, so a deterministic synthetic corpus is used instead,
mirroring the committed negative fixture semantics
(`core/voice/src/test/resources/wake-embeddings/generate_wake_embeddings.py`):

```bash
python training/wakeword/generate_negatives.py \
    --output_dir training/wakeword/data/negatives --count 990
```

Classes: digital silence, white noise (RMS 2000/400), pink (Voss-McCartney),
brown, speech-shaped noise (4 Hz syllabic envelope), formant vowels, low-passed
room tone, 50 Hz hum, and mixed classes.  The int16 conversion truncates
(`astype`) exactly like the committed generator, so the seed-42 realisations
are bit-identical to the committed `*_stream.json` fixtures.

## Step 2 — Add real recordings

Place your own "Hey Jandal" recordings as 16kHz mono WAV files in `training/wakeword/data/real_recordings/`. Convert M4A files:

```bash
for f in training/wakeword/data/real_recordings/raw_m4a/*.m4a; do
    ffmpeg -i "$f" -ar 16000 -ac 1 \
        "training/wakeword/data/real_recordings/$(basename "${f%.m4a}").wav"
done
```

The #1444 recipe copies the private natural_wake fixture 30× into this
directory (the original 29 real recordings are not recoverable).

## Step 3 — Train

The #1444 recipe uses the reference OWW window construction: each positive
window is a 3 s stream mixed with background audio at SNR 15–25 dB with the
phrase onset placed at window positions 2–5 (the committed firing band
[2:18]–[5:21]), position 6 trained as a soft positive (label 0.50) to pin the
committed [1:17] 0.2–0.5 band, and positions {1,7,12,14,16,−2,−3,−5,−8,−12,−16}
trained as near-phrase hard negatives.  Negatives are all sliding windows of
the synthetic corpus plus probe-history composites, stratified to include the
full tested-class alignment space.

```bash
source training/venv/bin/activate

python training/train.py \
    --positive_dir training/wakeword/data/positives_c1 \
    --negative_dir training/wakeword/data/negatives \
    --output_dir   training/wakeword/output/c2 \
    --epochs       100 \
    --false_positive_weight 4.5 \
    --batch_size   512 \
    --neg_count    30000 \
    --mix_snr_low  15 \
    --mix_snr_high 25 \
    --soft_positive_label 0.50
```

Output in `training/wakeword/output/c2/`:
- `hey_jandal.onnx` — trained Stage 3 classifier (opset 13, ~200 KB)
- `melspectrogram.onnx` — downloaded Stage 1 backbone
- `embedding_model.onnx` — downloaded Stage 2 backbone
- `training-manifest.json` — full provenance (data, augmentation, environment, hashes)

Training on CPU (5700X3D) takes ~20 minutes with 1428 positive clips.

## Step 4 — Deploy

```bash
python training/train.py \
    --positive_dir training/wakeword/data/positives_c1 \
    --negative_dir training/wakeword/data/negatives \
    --output_dir   training/wakeword/output/c2 \
    --deploy       app/src/main/assets/models/wakeword

# Then build and install
./gradlew installDebug
```

Or manually copy:
```bash
cp training/wakeword/output/c2/{melspectrogram,embedding_model,hey_jandal}.onnx \
   app/src/main/assets/models/wakeword/
./gradlew installDebug
```

After deploying a retrained classifier, regenerate the committed parity
reference and update the pinned hashes:

```bash
python3 core/voice/src/test/resources/wake-embeddings/generate_parity_reference.py \
    --model-dir app/src/main/assets/models/wakeword \
    --out core/voice/src/test/resources/wake-embeddings
# then update the two pinned hashes in WakeWordClassifierModelTest.kt and
# WakeWordFeaturePipelineParityTest.kt
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
| `scripts/private-acoustic-runs/` | Private capture evidence (evaluation only) |

Source scripts (`generate_tts_clips.py`, `generate_hey_jandal.py`,
`generate_negatives.py`, `train.py`) and this README are tracked.
