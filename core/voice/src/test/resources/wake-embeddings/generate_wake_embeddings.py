#!/usr/bin/env python3
"""
Generate privacy-safe Stage-2 embedding streams for the real-model wake
classifier tests (WakeWordClassifierModelTest, issue #1432 review finding 2).

Feature extraction uses openWakeWord's `AudioFeatures` class — the exact
feature path used by training/train.py for the positive and negative corpora
(see training/train.py compute_embeddings()): 76-row mel ring at 8-row
strides, mel normalisation value/10+2, embedding_model.onnx [1,76,32,1] ->
96-dim. The committed classifier fires on this distribution (verified:
natural fixture windows score 0.83-0.93 CPU).

Inputs:
  --model-dir <dir>  : dir with melspectrogram.onnx + embedding_model.onnx
  --out <dir>        : committed test-resource output dir
  --wake-wav <path>  : wake phrase WAV (48/16 kHz mono). Only its embedding
                       stream is committed; the WAV itself is NOT committed
                       (private household recording).

Synthetic streams (committed, deterministic seed 42, ~4 s each):
  silence            : digital zeros
  noise_white        : gaussian white noise (RMS ~0.06 full scale)
  noise_pink         : Voss-McCartney 1/f noise
  noise_speech       : speech-shaped noise (4 Hz syllabic envelope)
  speech_formant     : formant-synthesised vowel sequence /a/-/i/-/u/
                       (speech-like, not the wake phrase)

Output: one JSON file per stream — a list of 96-dim float arrays, one
embedding per 80 ms of audio (8 mel rows per embedding). No audio content is
written to the output directory.
"""
from __future__ import annotations

import argparse
import json
import wave
from pathlib import Path

import numpy as np
from openwakeword.utils import AudioFeatures

SAMPLE_RATE = 16_000
TRAILING_SILENCE_FRAMES = 25  # 2 s appended after the wake WAV


def audio_features(model_dir: Path) -> AudioFeatures:
    af = AudioFeatures(
        melspec_onnx_model_path=str(model_dir / "melspectrogram.onnx"),
        embedding_onnx_model_path=str(model_dir / "embedding_model.onnx"),
    )
    # Drop the silence pre-fill so the stream contains exactly the input audio.
    af.feature_buffer = np.zeros((0, 96), dtype=np.float32)
    return af


def embedding_stream(af: AudioFeatures, pcm16: np.ndarray) -> list[list[float]]:
    for c in range(len(pcm16) // 1280):
        af._streaming_features(pcm16[c * 1280:(c + 1) * 1280])
    return af.feature_buffer.astype(float).tolist()


def resample_to_16k(wav_path: Path) -> np.ndarray:
    with wave.open(str(wav_path), "rb") as w:
        rate = w.getframerate()
        channels = w.getnchannels()
        raw = w.readframes(w.getnframes())
    pcm = np.frombuffer(raw, dtype=np.int16)
    if channels > 1:
        pcm = pcm[::channels]
    if rate != SAMPLE_RATE:
        n_out = int(len(pcm) * SAMPLE_RATE / rate)
        x_old = np.linspace(0, len(pcm) - 1, len(pcm))
        x_new = np.linspace(0, len(pcm) - 1, n_out)
        pcm = np.interp(x_new, x_old, pcm.astype(float)).astype(np.int16)
    return pcm


def synthetic_streams() -> dict[str, np.ndarray]:
    rng = np.random.default_rng(42)
    n = SAMPLE_RATE * 4  # ~4 s -> ~32 embeddings
    out: dict[str, np.ndarray] = {}
    out["silence"] = np.zeros(n, dtype=np.int16)
    out["noise_white"] = (rng.normal(0, 1, n) * 2000).astype(np.int16)
    # pink noise via Voss-McCartney (16 generators)
    pink = np.zeros(n)
    rows = [rng.normal(0, 1, n) for _ in range(16)]
    for i in range(n):
        pink[i] = sum(rows[r][i] for r in range(16))
        if i & (i + 1) == 0:
            rows[(i + 1).bit_length() - 1] = rng.normal(0, 1, n)
    pink = pink / np.std(pink) * 2000
    out["noise_pink"] = pink.astype(np.int16)
    # speech-shaped noise: 4 Hz syllabic envelope
    t = np.arange(n) / SAMPLE_RATE
    env = 0.5 + 0.5 * np.sin(2 * np.pi * 4 * t)
    out["noise_speech"] = (rng.normal(0, 1, n) * env * 2400).astype(np.int16)
    # formant vowel sequence /a/-/i/-/u/
    vowels = [(730.0, 1090.0, 2440.0), (270.0, 2290.0, 3010.0), (300.0, 870.0, 2240.0)]
    speech = np.zeros(n)
    seg = n // 3
    f0 = 110.0
    for v, (f1, f2, f3) in enumerate(vowels):
        for k in range(seg):
            idx = v * seg + k
            t_k = k / SAMPLE_RATE
            glottal = (np.sin(2 * np.pi * f0 * t_k) > -0.95).astype(float)
            speech[idx] = (
                np.sin(2 * np.pi * f1 * t_k)
                + 0.6 * np.sin(2 * np.pi * f2 * t_k)
                + 0.4 * np.sin(2 * np.pi * f3 * t_k)
            ) * glottal
    speech = speech / np.max(np.abs(speech)) * 18000
    out["speech_formant"] = speech.astype(np.int16)
    return out


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-dir", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--wake-wav", help="wake phrase WAV (embedding stream committed, WAV not)")
    args = parser.parse_args()

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    if args.wake_wav:
        af = audio_features(Path(args.model_dir))
        pcm = resample_to_16k(Path(args.wake_wav))
        trailing = np.zeros(TRAILING_SILENCE_FRAMES * 1280, dtype=np.int16)
        stream = embedding_stream(af, np.concatenate([pcm, trailing]))
        (out / "fixture_stream.json").write_text(json.dumps(stream))
        print(f"fixture_stream: {len(stream)} embeddings ({Path(args.wake_wav).name} + 2 s silence)")

    for name, pcm in synthetic_streams().items():
        af = audio_features(Path(args.model_dir))
        stream = embedding_stream(af, pcm)
        (out / f"{name}_stream.json").write_text(json.dumps(stream))
        print(f"{name}_stream: {len(stream)} embeddings")


if __name__ == "__main__":
    main()
