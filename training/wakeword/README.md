# Wake Word Training — "Hey Jandal"

Produces `hey_jandal_int8.onnx` for the `WakeWordService` in `:core:voice`.
Related issue: #984.

## How it works

openWakeWord uses a 3-stage pipeline — only stage 3 (the binary classifier) is
trained here. Stages 1 and 2 are pre-built shared models from the openWakeWord
releases.

```
Mic audio (16kHz)
    │
    ▼
Stage 1: melspectrogram.onnx     — raw audio → mel spectrogram frames (80ms windows)
    │
    ▼
Stage 2: embedding_model.onnx    — frames → 96-dim embeddings (28-frame ring buffer)
    │
    ▼
Stage 3: hey_jandal.onnx         — embeddings → confidence score [0, 1]  ← trained here
```

## Step 1 — Generate positive training clips

Requires Qwen3-TTS-12Hz-1.7B-Base (auto-downloaded from HuggingFace on first run, ~4GB).

```bash
# From repo root
training/venv/bin/python training/wakeword/generate_hey_jandal.py \
    --ref_audio  training/shared/reference_voice_16k.wav \
    --output_dir training/wakeword/data/positives \
    --n 200 \
    --augment_factor 3
```

Outputs ~800 WAV files (16kHz mono, ~1s each) in `data/positives/`.
All files are gitignored.

## Step 2 — Download pre-built stage 1 & 2 models

```bash
training/venv/bin/python - <<'EOF'
import openwakeword
openwakeword.utils.download_models()
# copies melspectrogram.onnx and embedding_model.onnx to ~/.cache/openwakeword/
EOF

mkdir -p training/wakeword/data/models
cp ~/.cache/openwakeword/melspectrogram.onnx training/wakeword/data/models/
cp ~/.cache/openwakeword/embedding_model.onnx training/wakeword/data/models/
```

## Step 3 — Train the classifier

Open and run the openWakeWord training notebook:

```bash
training/venv/bin/jupyter notebook \
    ~/Documents/development/openWakeWord/notebooks/training_models.ipynb
```

Key parameters to set in the notebook:
- `POSITIVE_CLIPS_DIR` → `training/wakeword/data/positives`
- `MODEL_NAME` → `hey_jandal`
- `FALSE_POSITIVE_WEIGHT` → start at `5.0`, increase if FP rate is high

## Step 4 — Quantise and bundle

```bash
training/venv/bin/python - <<'EOF'
from onnxruntime.quantization import quantize_dynamic, QuantType
quantize_dynamic(
    "training/wakeword/data/models/hey_jandal.onnx",
    "training/wakeword/data/models/hey_jandal_int8.onnx",
    weight_type=QuantType.QInt8,
)
print("Done")
EOF

# Bundle into the app
cp training/wakeword/data/models/hey_jandal_int8.onnx \
   app/src/main/assets/models/wakeword/hey_jandal.onnx
```

## Validation targets

| Metric | Target |
|---|---|
| True positive rate (50 manual recordings) | >95% |
| False positive rate (10 min NZ speech) | <1 activation/hour |
| Model size | <1MB |
| Inference latency (Adreno 740, NNAPI) | <5ms per 80ms chunk |

## Phonetics note

"Jandal" = /ˈdʒændl/ — short front /a/ as in "jam", NZ/Māori influenced.
This is the critical differentiator from similar-sounding phrases ("hey Daniel",
"hey handle", "hey candle"). Training data quality depends on preserving this
pronunciation in the Qwen3-TTS cloned voice.
