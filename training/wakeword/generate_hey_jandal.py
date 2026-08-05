#!/usr/bin/env python3
"""
Generate synthetic "Hey Jandal" audio samples for wake word training.

Uses Qwen3-TTS-12Hz-1.7B-Base to clone the voice from a reference audio clip,
then generates hundreds of variations of "Hey Jandal" with pitch/speed/noise
augmentation to produce a training-ready dataset.

Usage:
    python scripts/generate_hey_jandal.py \
        --ref_audio /path/to/your_voice.wav \
        --ref_text "exact transcript of what you say in the clip" \
        --output_dir data/hey_jandal_positives \
        --n 600

Output:
    data/hey_jandal_positives/
        hey_jandal_0000.wav  ...  (16kHz, mono, ~1s each)
"""

import argparse
import random
import sys
from pathlib import Path

import numpy as np
import soundfile as sf
import torch
import torchaudio
import torchaudio.transforms as T

# ---------------------------------------------------------------------------
# Phrase variants — phonetic diversity matters more than text diversity.
# All produce the same /heɪ ˈdʒændl/ sound; varied punctuation/capitalisation
# gives the LM slightly different prosody without changing the phonetics.
# ---------------------------------------------------------------------------
PHRASES = [
    "Hey Jandal",
    "Hey, Jandal",
    "Hey Jandal.",
    "Hey Jandal!",
    "hey jandal",
    "hey, jandal",
]

# Reference transcript (from user — verbatim, matches the audio clip)
DEFAULT_REF_TEXT = (
    "When the sunlight strikes raindrops in the air, they act like a prism and form a rainbow. "
    "The rainbow is a division of white light into many beautiful colors. "
    "These take the shape of a long round arch, with its path high above and its 2 ends apparently beyond the horizon. "
    "Are you ready to capture every sound? A good voice model needs absolute variety. "
    "It requires crisp consonance, smooth vowels and natural rhythm. "
    "The quick brown fox jumps gracefully over the lazy dog while 5 quiet zebras watch from the zoo. "
    "In 1234 days, technology will have advanced even further. "
    "Just remember to speak clearly, measure your pitch and let the software handle the complex audio process."
)


def resample_to_16k(wav: np.ndarray, src_sr: int) -> np.ndarray:
    """Resample numpy audio array to 16kHz mono."""
    tensor = torch.from_numpy(wav.copy())
    if tensor.ndim == 1:
        tensor = tensor.unsqueeze(0)
    elif tensor.ndim == 2 and tensor.shape[0] > tensor.shape[1]:
        tensor = tensor.T  # (samples, channels) → (channels, samples)
    # mix to mono
    if tensor.shape[0] > 1:
        tensor = tensor.mean(dim=0, keepdim=True)
    if src_sr != 16000:
        resampler = T.Resample(src_sr, 16000)
        tensor = resampler(tensor)
    return tensor.squeeze(0).numpy()


def augment(wav: np.ndarray, sr: int, rng: random.Random) -> np.ndarray:
    """
    Apply random augmentation to a 16kHz mono clip:
      - Speed perturbation ±15%
      - Pitch shift ±2 semitones
      - Additive Gaussian noise (SNR 10–30 dB)
      - Random DC offset removal + normalisation
    """
    tensor = torch.from_numpy(wav.copy()).unsqueeze(0)  # (1, T)

    # Speed perturbation (stretches/compresses without pitch change)
    speed_factor = rng.uniform(0.85, 1.15)
    effects = [["speed", str(speed_factor)], ["rate", str(sr)]]
    try:
        tensor, _ = torchaudio.sox_effects.apply_effects_tensor(tensor, sr, effects)
    except Exception:
        # sox_effects may not be available; fall back to resample-based speed change
        new_sr = int(sr * speed_factor)
        resampler = T.Resample(new_sr, sr)
        tensor = resampler(tensor)

    # Pitch shift ±2 semitones via torchaudio (if available)
    semitones = rng.uniform(-2.0, 2.0)
    try:
        ps = T.PitchShift(sample_rate=sr, n_steps=semitones)
        tensor = ps(tensor)
    except Exception:
        pass  # pitch shift optional

    wav_aug = tensor.squeeze(0).numpy()

    # Additive white Gaussian noise
    snr_db = rng.uniform(10, 30)
    signal_power = np.mean(wav_aug ** 2) + 1e-9
    noise_power = signal_power / (10 ** (snr_db / 10))
    noise = np.random.normal(0, np.sqrt(noise_power), wav_aug.shape).astype(np.float32)
    wav_aug = wav_aug + noise

    # Sub-frame phase jitter (issue #1444): shift the clip by a random
    # 0..(frame-1) sample offset so the phrase crosses the 1280-sample mel
    # chunk grid at every sub-frame phase (see generate_tts_clips.py).
    phase = rng.randrange(0, 1280)
    if phase:
        wav_aug = np.concatenate([np.zeros(phase, dtype=np.float32), wav_aug[:-phase]])

    # Moderate amplitude variation spanning the measured S21 capture range
    # (issue #1444): peaks 0.03–0.51 of full scale in the #1432 captures;
    # previously normalised to a fixed 0.9 peak.
    peak = np.abs(wav_aug).max()
    if peak > 0:
        wav_aug = wav_aug / peak * rng.uniform(0.05, 0.95)

    return wav_aug.astype(np.float32)


def load_model(device: str):
    from qwen_tts import Qwen3TTSModel  # type: ignore
    dtype = torch.bfloat16 if device.startswith("cuda") else torch.float32
    print(f"Loading Qwen3-TTS-12Hz-1.7B-Base on {device} ({dtype}) ...")
    model = Qwen3TTSModel.from_pretrained(
        "Qwen/Qwen3-TTS-12Hz-1.7B-Base",
        device_map=device,
        dtype=dtype,
    )
    print("Model loaded.")
    return model


def build_clone_prompt(model, ref_audio_path: str, ref_text: str):
    print(f"Building voice clone prompt from: {ref_audio_path}")
    # Trim reference to 10s — the tokenizer processes the full clip in one pass
    # and OOMs on long audio (>15s) even with 16GB VRAM.
    import torchaudio, torch
    wav, sr = torchaudio.load(ref_audio_path)
    max_samples = sr * 10
    if wav.shape[-1] > max_samples:
        wav = wav[..., :max_samples]
        print(f"  Trimmed ref audio to 10s ({max_samples} samples @ {sr}Hz)")
    ref_audio = (wav.squeeze(0).numpy(), sr)
    prompt = model.create_voice_clone_prompt(
        ref_audio=ref_audio,
        ref_text=ref_text,
        x_vector_only_mode=False,
    )
    return prompt


def generate_batch(model, prompt, phrases: list[str]) -> list[tuple]:
    """Generate a batch of cloned audio clips, return list of (wav_np, sr).
    Tries the full batch first; on OOM falls back to one-at-a-time."""
    try:
        wavs, sr = model.generate_voice_clone(
            text=phrases,
            language=["English"] * len(phrases),
            voice_clone_prompt=prompt,
        )
        return [(w, sr) for w in wavs]
    except torch.cuda.OutOfMemoryError:
        torch.cuda.empty_cache()
        print(f"  OOM on batch of {len(phrases)}, falling back to 1-at-a-time")
        results = []
        for phrase in phrases:
            wavs, sr = model.generate_voice_clone(
                text=phrase,
                language="English",
                voice_clone_prompt=prompt,
            )
            results.append((wavs[0], sr))
            torch.cuda.empty_cache()
        return results



def main():
    parser = argparse.ArgumentParser(description="Generate Hey Jandal training clips")
    parser.add_argument("--ref_audio", required=True, help="Path to reference voice WAV/MP3/FLAC (3–30s)")
    parser.add_argument("--ref_text", default=DEFAULT_REF_TEXT, help="Exact transcript of ref_audio")
    parser.add_argument("--output_dir", default="data/hey_jandal_positives", help="Output directory")
    parser.add_argument("--n", type=int, default=600, help="Number of clips to generate")
    parser.add_argument("--batch_size", type=int, default=8, help="Clips per model inference call")
    parser.add_argument("--augment_factor", type=int, default=3,
                        help="Augmented copies per raw generated clip (total = n * augment_factor)")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--device", default="cuda:0" if torch.cuda.is_available() else "cpu")
    args = parser.parse_args()

    random.seed(args.seed)
    np.random.seed(args.seed)
    rng = random.Random(args.seed)

    ref_path = Path(args.ref_audio)
    if not ref_path.exists():
        print(f"ERROR: ref_audio not found: {ref_path}", file=sys.stderr)
        sys.exit(1)

    out_dir = Path(args.output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    model = load_model(args.device)
    prompt = build_clone_prompt(model, str(ref_path), args.ref_text)

    total_raw = args.n
    total_out = total_raw * args.augment_factor
    print(f"Generating {total_raw} raw clips → {total_out} augmented clips in {out_dir}/")

    # Resume: count existing files to find idx and generated_raw
    existing = sorted(out_dir.glob("hey_jandal_*.wav"))
    idx = len(existing)
    # Estimate generated_raw from idx: each raw clip produces 1 + augment_factor files
    files_per_raw = 1 + args.augment_factor
    generated_raw = idx // files_per_raw
    if generated_raw:
        print(f"  Resuming: {generated_raw} raw clips already done ({idx} files exist)")

    while generated_raw < total_raw:
        remaining = total_raw - generated_raw
        batch_n = min(args.batch_size, remaining)

        # Pick phrases — cycle through variants so all are represented
        phrases = [PHRASES[(generated_raw + i) % len(PHRASES)] for i in range(batch_n)]

        try:
            results = generate_batch(model, prompt, phrases)
        except Exception as e:
            print(f"  Batch failed: {e}, skipping", file=sys.stderr)
            generated_raw += batch_n
            torch.cuda.empty_cache()
            continue

        for (wav_np, sr) in results:
            # Resample to 16kHz mono
            wav_16k = resample_to_16k(wav_np, sr)

            # Save raw clone
            raw_path = out_dir / f"hey_jandal_{idx:04d}.wav"
            sf.write(str(raw_path), wav_16k, 16000)
            idx += 1

            # Save augmented copies
            for _ in range(args.augment_factor):
                wav_aug = augment(wav_16k, 16000, rng)
                aug_path = out_dir / f"hey_jandal_{idx:04d}.wav"
                sf.write(str(aug_path), wav_aug, 16000)
                idx += 1

            generated_raw += 1
            if generated_raw % 10 == 0:
                print(f"  {generated_raw}/{total_raw} raw clips done → {idx} files written")

    print(f"\nDone. {idx} clips written to {out_dir}/")
    print("Next: run the openWakeWord training notebook with this directory as --positive_clips")


if __name__ == "__main__":
    main()
