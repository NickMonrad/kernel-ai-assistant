#!/usr/bin/env python3
"""
Generate the authoritative Python/openWakeWord feature-pipeline reference for
the #1432 production-vs-training parity test (WakeWordFeaturePipelineParityTest).

This script executes the EXACT reference feature path used to generate the
committed fixture streams and to train the committed classifier:

  openwakeword 0.4.0 `AudioFeatures` streaming (`_streaming_features`) with
  the committed backbone models (melspectrogram.onnx + embedding_model.onnx,
  pinned hashes below), and the committed classifier hey_jandal.onnx for the
  Stage-3 window scores.

It records, for every 1280-sample chunk:

  1. the mel-model input length (1280 for the first chunk, 1760 afterwards —
     the streaming path always computes mel over the last `n + 480` buffered
     samples, i.e. the current chunk plus the 480-sample tail of the previous
     chunk);
  2. the raw Stage-1 output rows (after the openWakeWord transform x/10 + 2);
  3. the complete rolling mel context (the last-76-row window that Stage 2
     sees);
  4. the 96-dim Stage-2 embedding;
  5. the classifier confidence over the last-16-embedding window once the
     feature buffer holds >= 16 embeddings.

The reference `AudioFeatures` initial state is exactly the one used by
generate_wake_embeddings.py: `melspectrogram_buffer` pre-filled with 76 rows
of ones, `feature_buffer` cleared to zero rows (the package's pre-filled
embeddings are discarded).  The `raw_data_buffer` starts empty, so the first
chunk's mel input is only 1280 samples and yields 5 rows; every following
chunk yields 8 rows (3 boundary rows over the previous chunk tail + the 5
rows over the current chunk).

Outputs (committed, privacy-safe):

  parity_pcm.bin          — deterministic int16 LE mono 16 kHz PCM (seeded
                            noise + fixed tones/transients + silence; no
                            speech content, does not approximate any private
                            recording)
  parity_reference.json   — the per-chunk checkpoint records above

Usage:
  python3 core/voice/src/test/resources/wake-embeddings/generate_parity_reference.py \
      --model-dir app/src/main/assets/models/wakeword \
      --out core/voice/src/test/resources/wake-embeddings

Requires the reference venv with openwakeword 0.4.0 (the version the
committed fixture streams were generated with) and onnxruntime.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import struct
from pathlib import Path

import numpy as np
import onnxruntime as ort
from openwakeword.utils import AudioFeatures

SAMPLE_RATE = 16_000
FRAME_SAMPLES = 1_280
N_CHUNKS = 50  # 4.0 s

MODEL_SHA256 = {
    "melspectrogram.onnx": "ba2b0e0f8b7b875369a2c89cb13360ff53bac436f2895cced9f479fa65eb176f",
    "embedding_model.onnx": "70d164290c1d095d1d4ee149bc5e00543250a7316b59f31d056cff7bd3075c1f",
    "hey_jandal.onnx": "3a920e291662d4b58e10432b5c7f686f00073c45972763d55552200b97f9c4a8",
}


def verify_models(model_dir: Path) -> None:
    for name, expected in MODEL_SHA256.items():
        p = model_dir / name
        digest = hashlib.sha256(p.read_bytes()).hexdigest()
        if digest != expected:
            raise SystemExit(f"model hash mismatch for {name}: {digest} != {expected}")
        print(f"  {name} ok ({digest[:12]}…)")


def synthesize_pcm() -> np.ndarray:
    """Deterministic, privacy-safe 4 s int16 stream: silence, LCG noise,
    fixed sine tones, a chirp transient, a noise burst, trailing silence."""
    n = SAMPLE_RATE * 4
    out = np.zeros(n, dtype=np.int16)

    def xorshift32(seed: int, count: int) -> np.ndarray:
        state = seed
        values = np.empty(count, dtype=np.int64)
        for i in range(count):
            state ^= (state << 13) & 0xFFFFFFFF
            state ^= state >> 17
            state ^= (state << 5) & 0xFFFFFFFF
            state &= 0xFFFFFFFF
            values[i] = state
        return values

    # 0.4 s silence (chunks 0-4)
    start = int(0.4 * SAMPLE_RATE)
    end = int(1.6 * SAMPLE_RATE)
    rng = xorshift32(0x9E3779B9, end - start)
    out[start:end] = ((rng & 0xFFFF) - 0x8000).astype(np.float64) * (2000.0 / 32768.0)

    # 1.6-2.8 s: fixed tones (440 + 880 + 1320 Hz) with a 2 kHz chirp burst
    start = int(1.6 * SAMPLE_RATE)
    end = int(2.8 * SAMPLE_RATE)
    t = np.arange(end - start) / SAMPLE_RATE
    tones = 6000.0 * (np.sin(2 * np.pi * 440.0 * t) + 0.5 * np.sin(2 * np.pi * 880.0 * t)
                      + 0.25 * np.sin(2 * np.pi * 1320.0 * t)) / 1.75
    burst_start = int(2.0 * SAMPLE_RATE) - start
    burst_end = int(2.1 * SAMPLE_RATE) - start
    if burst_end > burst_start:
        tb = np.arange(burst_end - burst_start) / SAMPLE_RATE
        tones[burst_start:burst_end] += 9000.0 * np.sin(2 * np.pi * 2000.0 * tb)
    out[start:end] = tones

    # 2.8-3.2 s: noise with a loud transient burst
    start = int(2.8 * SAMPLE_RATE)
    end = int(3.2 * SAMPLE_RATE)
    rng = xorshift32(0x243F6A88, end - start)
    noise = ((rng & 0xFFFF) - 0x8000).astype(np.float64) * (2500.0 / 32768.0)
    bstart = int(2.85 * SAMPLE_RATE) - start
    bend = int(2.95 * SAMPLE_RATE) - start
    noise[bstart:bend] += 9000.0
    out[start:end] = noise

    # 3.2-4.0 s: silence
    return out.astype(np.int16)


def run_reference(model_dir: Path, pcm: np.ndarray) -> list[dict]:
    af = AudioFeatures(
        melspec_onnx_model_path=str(model_dir / "melspectrogram.onnx"),
        embedding_onnx_model_path=str(model_dir / "embedding_model.onnx"),
    )
    # Same initial state as generate_wake_embeddings.py: keep the 76-row ones
    # mel pre-fill, discard the package's pre-filled feature embeddings.
    af.feature_buffer = np.zeros((0, 96), dtype=np.float32)

    classifier = ort.InferenceSession(
        str(model_dir / "hey_jandal.onnx"), providers=["CPUExecutionProvider"]
    )
    class_input = classifier.get_inputs()[0].name

    records = []
    for c in range(N_CHUNKS):
        chunk = pcm[c * FRAME_SAMPLES:(c + 1) * FRAME_SAMPLES]
        assert len(chunk) == FRAME_SAMPLES
        buffered = len(af.raw_data_buffer)
        mel_input_len = min(buffered + FRAME_SAMPLES, FRAME_SAMPLES + 480)
        buffer_before = af.melspectrogram_buffer.shape[0]
        af._streaming_features(chunk)
        rows_appended = af.melspectrogram_buffer.shape[0] - buffer_before
        # The embedding window Stage 2 saw: the last 76 rows of the buffer.
        window = af.melspectrogram_buffer[-76:, :].copy()
        # Embedding appended this chunk (one per chunk in the reference).
        embedding = af.feature_buffer[-1, :].copy() if af.feature_buffer.shape[0] > 0 else None
        confidence = None
        if af.feature_buffer.shape[0] >= 16:
            win = af.feature_buffer[-16:, :].astype(np.float32)[None, :, :]
            confidence = float(classifier.run(None, {class_input: win})[0][0, 0])
        records.append({
            "chunk": c + 1,
            "mel_input_samples": mel_input_len,
            "mel_rows_appended": int(rows_appended),
            "mel_rows": af.melspectrogram_buffer[-rows_appended:, :].astype(float).tolist()
                        if rows_appended > 0 else [],
            "mel_buffer_rows": int(af.melspectrogram_buffer.shape[0]),
            "window_rows": window.astype(float).tolist(),
            "embedding": None if embedding is None else embedding.astype(float).tolist(),
            "classifier_confidence": confidence,
        })
    return records


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-dir", required=True)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    model_dir = Path(args.model_dir)
    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    print("Verifying committed model hashes…")
    verify_models(model_dir)

    print("Synthesizing deterministic PCM…")
    pcm = synthesize_pcm()
    pcm_path = out_dir / "parity_pcm.bin"
    pcm_path.write_bytes(pcm.tobytes())
    pcm_sha = hashlib.sha256(pcm.tobytes()).hexdigest()
    print(f"  {pcm_path.name}: {len(pcm)} samples, sha256 {pcm_sha}")

    print("Running openwakeword AudioFeatures streaming reference…")
    records = run_reference(model_dir, pcm)

    import openwakeword
    import onnxruntime
    reference = {
        "pcm": pcm_path.name,
        "pcm_sha256": pcm_sha,
        "pcm_samples": int(len(pcm)),
        "sample_rate": SAMPLE_RATE,
        "frame_samples": FRAME_SAMPLES,
        "chunks": N_CHUNKS,
        "openwakeword_version": "0.4.0",
        "onnxruntime_version": onnxruntime.__version__,
        "openwakeword_path": str(Path(openwakeword.__file__).parent),
        "model_sha256": MODEL_SHA256,
        "chunk_records": records,
    }
    ref_path = out_dir / "parity_reference.json"
    ref_path.write_text(json.dumps(reference))
    print(f"Wrote {ref_path.name} ({ref_path.stat().st_size // 1024} KB)")


if __name__ == "__main__":
    main()
