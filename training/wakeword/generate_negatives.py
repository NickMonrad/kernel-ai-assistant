#!/usr/bin/env python3
"""
Generate a deterministic synthetic negative corpus for Hey Jandal classifier
training (issue #1444).

The original training used the openWakeWord ACAV pre-computed background
features (5.6M windows of real audio), which are not available on this
machine.  This generator produces the negative corpus synthetically,
mirroring the committed negative fixture semantics in
`core/voice/src/test/resources/wake-embeddings/generate_wake_embeddings.py`
(silence, white noise, Voss-McCartney pink noise, 4 Hz speech-shaped noise,
formant-synthesised vowels — deterministic seed 42, ~4 s clips, RMS ~2000
int16 ≈ 0.06 full scale) and extends them with brown noise, low-passed
room-tone, tonal hums and mixed classes at a spread of levels including the
measured #1432 capture idle range (RMS ≈ 300–500 int16).

Usage:
    python training/wakeword/generate_negatives.py \
        --output_dir training/wakeword/data/negatives \
        --count 1000 \
        --seed 42
"""

import argparse
from pathlib import Path

import numpy as np
import soundfile as sf

SAMPLE_RATE = 16_000
CLIP_SECONDS = 4.0


def voss_mccartney_pink(rng: np.random.Generator, n: int, n_rows: int = 16) -> np.ndarray:
    """Voss-McCartney 1/f noise (same construction as the committed fixtures)."""
    pink = np.zeros(n)
    rows = [rng.normal(0, 1, n) for _ in range(n_rows)]
    for i in range(n):
        pink[i] = sum(rows[r][i] for r in range(n_rows))
        if i & (i + 1) == 0:
            rows[(i + 1).bit_length() - 1] = rng.normal(0, 1, n)
    return pink


def lowpass(x: np.ndarray, cutoff_hz: float, sr: int = SAMPLE_RATE) -> np.ndarray:
    """First-order Butterworth-ish lowpass via simple one-pole filter."""
    dt = 1.0 / sr
    rc = 1.0 / (2.0 * np.pi * cutoff_hz)
    alpha = dt / (rc + dt)
    y = np.empty_like(x)
    acc = 0.0
    for i in range(len(x)):
        acc += alpha * (x[i] - acc)
        y[i] = acc
    return y


def formant_vowels(rng: np.random.Generator, n: int, f0: float, order: int) -> np.ndarray:
    """Formant-synthesised vowel sequence (3 segments), like the committed fixture."""
    vowels = [(730.0, 1090.0, 2440.0), (270.0, 2290.0, 3010.0), (300.0, 870.0, 2240.0)]
    if order == 1:
        vowels = vowels[::-1]
    elif order == 2:
        vowels = [vowels[1], vowels[2], vowels[0]]
    speech = np.zeros(n)
    seg = n // 3
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
    return speech / (np.std(speech) + 1e-9) * 2000


def make_clips(count: int, seed: int) -> list[tuple[str, np.ndarray]]:
    rng = np.random.default_rng(seed)
    n = int(SAMPLE_RATE * CLIP_SECONDS)
    clips: list[tuple[str, np.ndarray]] = []

    def add(name: str, audio: np.ndarray) -> None:
        # Keep float64 until the int16 conversion (float32 rounding can nudge
        # values past the truncation boundary and corrupt 1 LSB on ~5e-5 of
        # samples — the seed-42 realisations must stay bit-identical to the
        # committed fixture streams).
        clips.append((name, audio))

    # Class mix (deterministic order, one rng stream)
    classes = [
        ("silence", lambda: np.zeros(n)),
        ("white_rms2000", lambda: rng.normal(0, 1, n) * 2000),
        ("white_rms400", lambda: rng.normal(0, 1, n) * 400),
        ("pink_rms2000", lambda: (lambda p: p / np.std(p) * 2000)(voss_mccartney_pink(rng, n))),
        ("brown_rms2000", lambda: np.cumsum(rng.normal(0, 1, n)) / np.sqrt(n) * 9000),
        ("speechshape_rms2400", lambda: rng.normal(0, 1, n) * (0.5 + 0.5 * np.sin(2 * np.pi * 4 * np.arange(n) / SAMPLE_RATE)) * 2400),
        ("speechshape_rms600", lambda: rng.normal(0, 1, n) * (0.5 + 0.5 * np.sin(2 * np.pi * 4 * np.arange(n) / SAMPLE_RATE)) * 600),
        ("formant_f0_90", lambda: formant_vowels(rng, n, 90.0, 0)),
        ("formant_f0_110", lambda: formant_vowels(rng, n, 110.0, 0)),
        ("formant_f0_130", lambda: formant_vowels(rng, n, 130.0, 1)),
        ("formant_f0_110_rev", lambda: formant_vowels(rng, n, 110.0, 2)),
        ("roomtone_pink_lp", lambda: lowpass(voss_mccartney_pink(rng, n), 800.0) * 0.35),
        ("hum_50hz", lambda: sum(np.sin(2 * np.pi * 50 * h * np.arange(n) / SAMPLE_RATE) / h for h in range(1, 5)) * 250),
        ("mixed_speech_formant", lambda: (
            rng.normal(0, 1, n) * (0.5 + 0.5 * np.sin(2 * np.pi * 4 * np.arange(n) / SAMPLE_RATE)) * 1200
            + formant_vowels(rng, n, 110.0, 0) * 0.5
        )),
        ("mixed_white_formant", lambda: (
            rng.normal(0, 1, n) * 800 + formant_vowels(rng, n, 100.0, 1) * 0.7
        )),
    ]

    per_class = max(1, count // len(classes))
    for name, fn in classes:
        for _ in range(per_class):
            add(name, fn())
    return clips


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate deterministic synthetic negative clips")
    parser.add_argument("--output_dir", default="training/wakeword/data/negatives")
    parser.add_argument("--count", type=int, default=1000)
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    out_dir = Path(args.output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    clips = make_clips(args.count, args.seed)
    for i, (name, audio) in enumerate(clips):
        # Write 16-bit PCM like the committed fixture generator
        # (generate_wake_embeddings.py) — TRUNCATION (astype) not rounding, so
        # the seed-42 realisations are bit-identical to the committed test
        # streams; soundfile then returns float data normalised to [-1, 1]
        # (levels comparable to the committed test fixtures, RMS ~2000 int16
        # ≈ 0.06 full scale).
        pcm16 = np.clip(audio, -32768, 32767).astype(np.int16)
        sf.write(str(out_dir / f"neg_{i:04d}_{name}.wav"), pcm16, SAMPLE_RATE)
    print(f"Wrote {len(clips)} negative clips to {out_dir}/")
    print(f"Seed {args.seed}, {CLIP_SECONDS}s each, classes: "
          + ", ".join(sorted({n for n, _ in clips})))


if __name__ == "__main__":
    main()
