# Kernel AI — Model Training

This directory contains local ML training pipelines for Jandal's on-device models.
All pipelines are self-contained Python projects that run on the local workstation
(AMD Ryzen 7 5700X3D + RX 9070 XT, ROCm 7.2, Linux).

## Structure

```
training/
  wakeword/          # openWakeWord — custom "Hey Jandal" wake word model
  tts-voice/         # Piper TTS voice fine-tune (NZ accent, issue #756)
  shared/            # Shared assets: reference voice audio, augmentation utils
  README.md          # This file
```

## Shared environment

A single Python 3.12 virtual environment covers both pipelines:

```bash
# From repo root — create once
uv venv training/venv --python 3.12

# Install PyTorch ROCm 6.4 (gfx1201 / RX 9070 XT)
BASE=https://repo.radeon.com/rocm/manylinux/rocm-rel-6.4
uv pip install --python training/venv/bin/python \
    "${BASE}/pytorch_triton_rocm-3.2.0%2Brocm6.4.0.git6da9e660-cp312-cp312-linux_x86_64.whl" \
    "${BASE}/torch-2.6.0%2Brocm6.4.0.git2fb0ac2b-cp312-cp312-linux_x86_64.whl" \
    "${BASE}/torchaudio-2.6.0%2Brocm6.4.0.gitd8831425-cp312-cp312-linux_x86_64.whl"

# Install shared ML deps
uv pip install --python training/venv/bin/python -r training/requirements.txt
```

## Reference voice

`shared/reference_voice_16k.wav` — 48kHz mono WAV of the target speaker.
Used as the cloning reference for both wake word data generation (wakeword/) and
Piper TTS training data generation (tts-voice/).

The file is gitignored (personal audio). Re-record or re-download from your secure
storage and place it at `training/shared/reference_voice_16k.wav`.

Transcript of the reference clip:

> When the sunlight strikes raindrops in the air, they act like a prism and form a
> rainbow. The rainbow is a division of white light into many beautiful colors.
> These take the shape of a long round arch, with its path high above and its 2 ends
> apparently beyond the horizon.
>
> Are you ready to capture every sound? A good voice model needs absolute variety.
> It requires crisp consonance, smooth vowels and natural rhythm.
>
> The quick brown fox jumps gracefully over the lazy dog while 5 quiet zebras watch
> from the zoo. In 1234 days, technology will have advanced even further. Just
> remember to speak clearly, measure your pitch and let the software handle the
> complex audio process.

## Pipeline overview

| Pipeline | Issue | Input | Output | Status |
|---|---|---|---|---|
| Wake word | #984 | `shared/reference_voice_16k.wav` | `hey_jandal_int8.onnx` | In progress |
| Piper TTS voice | #756 | `shared/reference_voice_16k.wav` | Piper `.onnx` voice model | Planned |

## Hardware notes

- **GPU**: AMD RX 9070 XT (gfx1201, RDNA 4, 16GB VRAM)
- **ROCm**: 7.2.3 installed system-wide
- **PyTorch**: Must use AMD's ROCm 6.4 build — the PyPI `+rocm6.2` wheel does **not**
  include gfx1201 kernels and will crash with `invalid device function`
- **CPU fallback**: Both pipelines work on CPU but are 10–20× slower
