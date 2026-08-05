#!/usr/bin/env python3
"""
Generate synthetic "Hey Jandal" clips using piper-tts with multiple voices.

Produces:
  output_dir/tts_NNN.wav   — 16kHz mono, one per (voice, phrase, speaker_id) combo
  Each raw clip is then augmented augment_factor times.
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
from piper import PiperVoice

# Phonetic variants covering NZ pronunciation /hændl/:
#   "Jandal"  → standard spelling, works for some voices
#   "Jandel"  → closer to NZ vowel for non-NZ voices (per user testing with Piper Semaine/Spike)
#   "Handal"  → approximates NZ /h/ onset (J sounds like H in NZ English)
#   "Handel"  → combines both corrections
PHRASES = [
    "Hey Jandal",
    "Hey, Jandal",
    "Hey Jandal!",
    "Hey Jandel",
    "Hey, Jandel",
    "Hey Jandel!",
    "Hey Handal",
    "Hey Handel",
    "hey jandal",
    "hey jandel",
    "hey handal",
    "Jandal",
    "Jandel",
]


def load_voices(voices_dir: Path) -> list[tuple[str, PiperVoice]]:
    voices = []
    for onnx in sorted(voices_dir.glob("*.onnx")):
        json_path = onnx.with_suffix(".onnx.json")
        if not json_path.exists():
            print(f"  Skipping {onnx.name} — no .json sidecar", file=sys.stderr)
            continue
        try:
            v = PiperVoice.load(str(onnx), config_path=str(json_path), use_cuda=False)
            voices.append((onnx.stem, v))
            print(f"  Loaded {onnx.stem}")
        except Exception as e:
            print(f"  Failed to load {onnx.name}: {e}", file=sys.stderr)
    return voices


def synthesise(voice: PiperVoice, text: str, speaker_id: int | None = None) -> tuple[np.ndarray, int]:
    """Synthesise text with piper, return (float32 numpy array, sample_rate)."""
    from piper import SynthesisConfig
    syn_config = SynthesisConfig(speaker_id=speaker_id) if speaker_id is not None else None
    chunks = list(voice.synthesize(text, syn_config=syn_config))
    if not chunks:
        raise ValueError("piper returned no audio chunks")
    audio = np.concatenate([c.audio_float_array for c in chunks])
    sr = chunks[0].sample_rate
    return audio, sr



def resample_to_16k(wav: np.ndarray, src_sr: int) -> np.ndarray:
    tensor = torch.from_numpy(wav.copy()).unsqueeze(0)
    if src_sr != 16000:
        tensor = T.Resample(src_sr, 16000)(tensor)
    return tensor.squeeze(0).detach().numpy()


def augment(
    wav: np.ndarray,
    sr: int,
    rng: random.Random,
    phase_jitter: bool = True,
    peak_band: tuple[float, float] = (0.05, 0.95),
) -> np.ndarray:
    """Random augmentation for a 16kHz mono wake-word clip.

    phase_jitter (issue #1444): shift the clip by a random 0..1279-sample
    offset so the phrase crosses the 1280-sample mel chunk grid at every
    sub-frame phase.  Physical S21 captures land at arbitrary
    playback-vs-capture clock phases; without this the classifier only fires
    inside a narrow sub-frame phase band (#1432 c120-06 never fired at any
    window offset, while a 384-sample shift of the same PCM scores 0.67).

    peak_band: amplitude range for peak normalisation.  The measured #1432
    S21 capture phrase peaks are 0.03–0.51 of full scale; the original
    training only covered 0.7–0.95.
    """
    tensor = torch.from_numpy(wav.copy()).unsqueeze(0)

    # Speed perturbation ±15% via resample trick (avoids sox segfault on ROCm)
    speed = rng.uniform(0.85, 1.15)
    fake_sr = int(sr * speed)
    tensor = T.Resample(fake_sr, sr)(tensor)

    # Pitch shift skipped — PitchShift is CPU-slow; speed perturbation covers sufficient
    # perceptual variation for a wake word binary classifier.

    # AWGN noise SNR 10–30 dB
    snr_db = rng.uniform(10, 30)
    signal_power = tensor.pow(2).mean().clamp(min=1e-9)
    noise_power = signal_power / (10 ** (snr_db / 10))
    tensor = tensor + torch.randn_like(tensor) * noise_power.sqrt()

    # Sub-frame phase jitter (issue #1444)
    if phase_jitter:
        phase = rng.randrange(0, 1280)
        if phase:
            n = tensor.shape[-1]
            tensor = torch.nn.functional.pad(tensor, (phase, 0))[..., :n]

    # Moderate amplitude variation spanning the measured S21 capture range
    # (issue #1444)
    peak_scale = rng.uniform(*peak_band)

    # Normalise
    peak = tensor.abs().max().clamp(min=1e-9)
    tensor = tensor / peak * peak_scale

    return tensor.squeeze(0).detach().numpy()


def augment_real(
    wav_path: Path,
    sr_expected: int,
    n: int,
    rng: random.Random,
    phase_jitter: bool = True,
    peak_band: tuple[float, float] = (0.05, 0.95),
) -> list[np.ndarray]:
    """Load a real recording and produce n augmented variants."""
    wav, sr = sf.read(str(wav_path), dtype="float32")
    if wav.ndim > 1:
        wav = wav.mean(axis=1)
    if sr != sr_expected:
        wav = resample_to_16k(wav, sr)
    return [augment(wav, sr_expected, rng, phase_jitter, peak_band) for _ in range(n)]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--voices_dir", default="training/wakeword/piper_voices")
    parser.add_argument("--real_dir", default=None,
                        help="Directory of real recordings to augment alongside TTS")
    parser.add_argument("--output_dir", default="training/wakeword/data/positives")
    parser.add_argument("--augment_factor", type=int, default=5,
                        help="Augmented copies per raw clip")
    parser.add_argument("--aug_phase_jitter", type=int, default=1,
                        help="0 = disable sub-frame phase jitter (original recipe)")
    parser.add_argument("--aug_peak_min", type=float, default=0.05,
                        help="Peak normalisation lower bound (measured S21 range)")
    parser.add_argument("--aug_peak_max", type=float, default=0.95,
                        help="Peak normalisation upper bound")
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    rng = random.Random(args.seed)
    phase_jitter = bool(args.aug_phase_jitter)
    peak_band = (args.aug_peak_min, args.aug_peak_max)
    out_dir = Path(args.output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    # Count existing files to resume
    existing = len(list(out_dir.glob("*.wav")))
    idx = existing
    if existing:
        print(f"Resuming — {existing} files already in {out_dir}")

    voices_dir = Path(args.voices_dir)
    print(f"Loading voices from {voices_dir}...")
    voices = load_voices(voices_dir)
    if not voices:
        print("ERROR: no voices loaded", file=sys.stderr)
        sys.exit(1)
    print(f"{len(voices)} voices loaded\n")

    # --- TTS clips ---
    print("Generating TTS clips...")
    tts_raw = 0
    for voice_name, voice in voices:
        # Check how many speaker IDs this voice has
        n_speakers = voice.config.num_speakers or 1
        speaker_ids = list(range(min(n_speakers, 3)))  # cap at 3 speaker IDs per voice

        for spk in speaker_ids:
            spk_arg = spk if n_speakers > 1 else None
            for phrase in PHRASES:
                try:
                    wav, sr = synthesise(voice, phrase, speaker_id=spk_arg)
                    wav16 = resample_to_16k(wav, sr)

                    raw_path = out_dir / f"tts_{idx:04d}.wav"
                    sf.write(str(raw_path), wav16, 16000)
                    idx += 1
                    tts_raw += 1

                    for _ in range(args.augment_factor):
                        wav_aug = augment(wav16, 16000, rng, phase_jitter, peak_band)
                        sf.write(str(out_dir / f"tts_{idx:04d}.wav"), wav_aug, 16000)
                        idx += 1
                except Exception as e:
                    print(f"  SKIP {voice_name} spk={spk_arg} '{phrase}': {e}", file=sys.stderr)

        print(f"  {voice_name}: done ({n_speakers} speaker(s))")

    print(f"\nTTS: {tts_raw} raw → {tts_raw * args.augment_factor} augmented clips")

    # --- Real recording augmentation ---
    if args.real_dir:
        real_dir = Path(args.real_dir)
        real_wavs = sorted(real_dir.glob("*.wav"))
        print(f"\nAugmenting {len(real_wavs)} real recordings × {args.augment_factor}...")
        real_aug = 0
        for wav_path in real_wavs:
            # Copy original
            wav, sr = sf.read(str(wav_path), dtype="float32")
            if wav.ndim > 1:
                wav = wav.mean(axis=1)
            if sr != 16000:
                wav = resample_to_16k(wav, sr)
            sf.write(str(out_dir / f"real_{idx:04d}.wav"), wav, 16000)
            idx += 1

            for aug_wav in augment_real(wav_path, 16000, args.augment_factor, rng, phase_jitter, peak_band):
                sf.write(str(out_dir / f"real_{idx:04d}.wav"), aug_wav, 16000)
                idx += 1
            real_aug += 1

        print(f"Real recordings: {real_aug} originals + {real_aug * args.augment_factor} augmented")

    print(f"\nDone. {idx} total clips in {out_dir}/")


if __name__ == "__main__":
    main()
