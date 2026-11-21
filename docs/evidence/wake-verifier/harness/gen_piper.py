#!/usr/bin/env python3
"""Multi-speaker synthetic wake-verifier fixtures from public Piper voices (sherpa-onnx VITS).

Speaks the authored genuine and near-miss prompts in phrases.py with five non-owner voices.
Benchmark-only; audio stays in a private directory outside Git.

usage: gen_piper.py --voices <dir with vits-piper-* model dirs> --out <private-dir>
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import numpy as np
import sherpa_onnx
import soundfile as sf
import soxr

from phrases import GENUINE, NEAR_MISS

VOICES = (
    "vits-piper-en_GB-cori-high",
    "vits-piper-en_GB-alan-medium",
    "vits-piper-en_GB-jenny_dioco-medium",
    "vits-piper-en_US-lessac-medium",
    "vits-piper-en_US-ryan-medium",
)
GENUINE_SPEEDS = (1.0, 0.85)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--voices", type=Path, nargs="+", required=True)
    ap.add_argument("--out", type=Path, required=True)
    a = ap.parse_args()
    a.out.mkdir(parents=True, exist_ok=True, mode=0o700)
    dirs = {p.name: p for d in a.voices for p in d.glob("vits-piper-*") if p.is_dir()}
    rows = []
    for v in VOICES:
        d = dirs[v]
        model = next(d.glob("*.onnx"))
        tts = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
            vits=sherpa_onnx.OfflineTtsVitsModelConfig(
                model=str(model), tokens=str(d / "tokens.txt"), data_dir=str(d / "espeak-ng-data")),
            num_threads=4)))
        jobs = [("genuine", pid, text, s) for pid, text in GENUINE.items() for s in GENUINE_SPEEDS]
        jobs += [("near_miss", pid, text, 1.0) for pid, text in NEAR_MISS.items()]
        for klass, pid, text, speed in jobs:
            audio = tts.generate(text, sid=0, speed=speed)
            x = soxr.resample(np.asarray(audio.samples, np.float32), audio.sample_rate, 16000)
            x = np.clip(x / max(1e-6, float(np.abs(x).max())) * 0.7, -1, 1)
            fid = f"{pid}-{v.removeprefix('vits-piper-')}-x{int(speed * 100)}"
            path = a.out / f"{fid}.wav"
            sf.write(path, x, 16000, subtype="PCM_16")
            path.chmod(0o600)
            rows.append({"fixture_id": fid, "class": klass, "phrase_id": pid, "voice": v, "speed": speed,
                         "source": "piper", "file_name": path.name, "duration_ms": round(len(x) / 16),
                         "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
        print(v, len(rows), flush=True)
    (a.out / "manifest-private.json").write_text(json.dumps({"schema_version": 1, "fixtures": rows}, indent=1))


if __name__ == "__main__":
    main()
