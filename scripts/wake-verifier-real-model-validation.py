#!/usr/bin/env python3
"""
Wake-verifier real-model validation for #1439.

Runs the EXACT on-device verifier configuration against a wake-phrase WAV and
prints the baseline (Zipformer) and corrected (Whisper tiny.en) transcripts.

Models are resolved from a local directory (the exact files downloaded through
the app's KernelModel catalogue — pull them from a device's
`Android/data/com.kernel.ai.debug/files/models/` or download the catalogue
URLs). Every file is hash-pinned: the script FAILS CLOSED on any missing or
mismatched file. No model binaries or private audio are committed to the repo.

Requirements:
    pip install sherpa-onnx==1.13.0 soundfile numpy

Usage:
    python scripts/wake-verifier-real-model-validation.py \
        --models-dir /path/to/models \
        --fixture /path/to/natural_wake.wav

The fixture should be the fixed natural wake phrase (48 kHz or 16 kHz mono
int16 WAV). The script builds the production detector-equivalent window
(3 s ring, phrase ending ~0.15 s before the end, digital-silence lead/tail)
plus the clean fixture, and prints the verifier verdicts.

Exit code: 0 when the Whisper verifier accepts the production window and the
Zipformer baseline rejects it; 1 otherwise.
"""
from __future__ import annotations

import argparse
import hashlib
import sys
import time
from pathlib import Path

import numpy as np
import sherpa_onnx
import soundfile as sf

# SHA-256 of the exact verifier files pulled from the physical test devices
# (S21 R5CR605B71K / S23U, 2026-08-03) — identical on both devices.
MODEL_HASHES: dict[str, dict[str, str]] = {
    "zipformer": {
        "sherpa-stt-encoder.int8.onnx": "4b7edfb7783e94a66fead1470a066ccc2eceb14e2630d8aa1914e25f8ff35027",
        "sherpa-stt-decoder.int8.onnx": "279fb5e6ee22f0efb3be4dc62fe745864979970d158192851f307744d27a72c3",
        "sherpa-stt-joiner.int8.onnx": "480747e5f00fdbd68e0c93b09edfcd6698853526ccfe0f859ad624108ce7dda2",
        "sherpa-stt-tokens.txt": "49e3c2646595fd907228b3c6787069658f67b17377c60aeb8619c4551b2316fb",
    },
    "whisper": {
        "sherpa-whisper-tiny.en-encoder.int8.onnx": "0ce578b827c94a961aacb8fa14b02f096504b337e5c94be37c36238cbe3e8bc6",
        "sherpa-whisper-tiny.en-decoder.int8.onnx": "06c0e6ff6348d427e51839219d1c886c18cfdf411e629e33f5e1679bff9c1527",
        "sherpa-whisper-tiny.en-tokens.txt": "306cd27f03c1a714eca7108e03d66b7dc042abe8c258b44c199a7ed9838dd930",
    },
}

# Supported Hey Jandal forms (matches WakeWordUtils.containsWakePhrase, #1439).
ACCEPTED_PREFIXES = ("hey", "hi", "a")
ACCEPTED_NAMES = ("jandal", "jandel", "handel", "handal", "hando", "jando")


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def verify_models(models_dir: Path, kind: str) -> dict[str, Path]:
    files = {name: models_dir / name for name in MODEL_HASHES[kind]}
    for name, path in files.items():
        if not path.exists():
            raise SystemExit(f"missing model file: {path}")
        actual = sha256(path)
        if actual != MODEL_HASHES[kind][name]:
            raise SystemExit(
                f"SHA-256 mismatch for {path}: expected {MODEL_HASHES[kind][name]} got {actual}"
            )
    return files


def resample_linear(x: np.ndarray, src_rate: int, dst_rate: int) -> np.ndarray:
    n = int(len(x) * dst_rate / src_rate)
    idx = np.linspace(0, len(x) - 1, n)
    return np.interp(idx, np.arange(len(x)), x.astype(np.float64)).astype(np.int16)


def accepted(text: str) -> bool:
    lower = text.strip().rstrip("?!.,:;").lower()
    for prefix in ACCEPTED_PREFIXES:
        for name in ACCEPTED_NAMES:
            for sep in ("", ",", "."):
                candidate = f"{prefix}{sep} {name}"
                if f" {candidate} " in f" {lower} ":
                    return True
    return False


def build_zipformer(files: dict[str, Path]) -> sherpa_onnx.OnlineRecognizer:
    # Mirrors SherpaOnnxVoiceInputController.initWakeOnlineRecognizer exactly.
    return sherpa_onnx.OnlineRecognizer.from_transducer(
        tokens=str(files["sherpa-stt-tokens.txt"]),
        encoder=str(files["sherpa-stt-encoder.int8.onnx"]),
        decoder=str(files["sherpa-stt-decoder.int8.onnx"]),
        joiner=str(files["sherpa-stt-joiner.int8.onnx"]),
        num_threads=2,
        sample_rate=16000,
        feature_dim=80,
        enable_endpoint_detection=True,
        decoding_method="greedy_search",
        max_active_paths=4,
        normalize_samples=True,
    )


def build_whisper(files: dict[str, Path]) -> sherpa_onnx.OfflineRecognizer:
    # Mirrors SherpaOnnxVoiceInputController.initWakeWhisperRecognizer exactly.
    return sherpa_onnx.OfflineRecognizer.from_whisper(
        encoder=str(files["sherpa-whisper-tiny.en-encoder.int8.onnx"]),
        decoder=str(files["sherpa-whisper-tiny.en-decoder.int8.onnx"]),
        tokens=str(files["sherpa-whisper-tiny.en-tokens.txt"]),
        language="en",
        task="transcribe",
        num_threads=2,
        decoding_method="greedy_search",
        provider="cpu",
    )


def transcribe_zipformer(rec: sherpa_onnx.OnlineRecognizer, pcm16: np.ndarray) -> str:
    # Java JNI maps an empty hotword string to CreateStream() (no context graph);
    # the Python binding does not, so call the no-arg form to match the app.
    stream = rec.create_stream()
    stream.accept_waveform(16000, pcm16.astype(np.float32) / 32768.0)
    stream.input_finished()
    iters = 0
    while rec.is_ready(stream):
        rec.decode_stream(stream)
        iters += 1
        if iters > 500:
            break
    result = rec.get_result(stream)
    return (result.text if hasattr(result, "text") else str(result)).strip().rstrip("?!.,:;").lower()


def transcribe_whisper(rec: sherpa_onnx.OfflineRecognizer, pcm16: np.ndarray) -> str:
    stream = rec.create_stream()
    stream.accept_waveform(16000, pcm16.astype(np.float32) / 32768.0)
    rec.decode_stream(stream)
    result = stream.result if hasattr(stream, "result") else rec.get_result(stream)
    return (result.text if hasattr(result, "text") else str(result)).strip().rstrip("?!.,:;").lower()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--models-dir", type=Path, required=True, help="dir containing the catalogue model files")
    ap.add_argument("--fixture", type=Path, required=True, help="natural wake phrase WAV (48k or 16k mono int16)")
    args = ap.parse_args()

    zip_files = verify_models(args.models_dir, "zipformer")
    whisper_files = verify_models(args.models_dir, "whisper")
    zip_rec = build_zipformer(zip_files)
    whisper_rec = build_whisper(whisper_files)

    x, sr = sf.read(args.fixture, dtype="int16")
    if x.ndim > 1:
        x = x[:, 0]
    if sr != 16000:
        x16 = resample_linear(x, sr, 16000)
    else:
        x16 = x

    # Production detector-equivalent window: 3 s ring ending ~0.15 s after the
    # phrase end (pass-trial journal timing).
    lead = np.zeros(int((3.0 - len(x16) / 16000 - 0.15) * 16000), dtype=np.int16)
    tail = np.zeros(int(0.15 * 16000), dtype=np.int16)
    ring = np.concatenate([lead, x16, tail])
    if len(ring) < 48000:
        ring = np.concatenate([ring, np.zeros(48000 - len(ring), dtype=np.int16)])

    print(f"fixture: {args.fixture} ({len(x16)} samples @16 kHz)")
    print(f"{'verifier':<10} {'input':<14} {'transcript':<22} {'verdict':<10} {'time_s'}")
    print("-" * 74)

    verdicts = {}
    for label, pcm in (("clean", x16), ("ring", ring)):
        t0 = time.perf_counter()
        zt = transcribe_zipformer(zip_rec, pcm)
        dt = time.perf_counter() - t0
        print(f"{'zipformer':<10} {label:<14} {zt!r:<22} {'ACCEPT' if accepted(zt) else 'reject':<10} {dt:.2f}")
        verdicts[f"zipformer_{label}"] = accepted(zt)

        t0 = time.perf_counter()
        wt = transcribe_whisper(whisper_rec, pcm)
        dt = time.perf_counter() - t0
        print(f"{'whisper':<10} {label:<14} {wt!r:<22} {'ACCEPT' if accepted(wt) else 'reject':<10} {dt:.2f}")
        verdicts[f"whisper_{label}"] = accepted(wt)

    # Acceptance: the Whisper verifier must accept the production ring window and
    # the Zipformer baseline must reject it (the #1439 defect).
    ok = verdicts["whisper_ring"] and not verdicts["zipformer_ring"]
    print("-" * 74)
    print("RESULT:", "PASS" if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
