#!/usr/bin/env python3
"""
Train the Hey Jandal wake word classifier (Stage 3 of the openWakeWord pipeline).

This script replaces the interactive Jupyter notebook step. It:
  1. Downloads the shared Stage 1/2 backbone models (melspectrogram.onnx,
     embedding_model.onnx) from openWakeWord releases if not already present.
  2. Computes embeddings for all positive clips in --positive_dir using the
     backbone, then trains a small FCN classifier on those embeddings + the
     openWakeWord background negative corpus.
  3. Exports the trained classifier to ONNX as hey_jandal.onnx.
  4. Optionally quantises to INT8.

Usage:
    python training/train.py \
        --positive_dir training/wakeword/data/positives \
        --output_dir training/wakeword/output \
        --epochs 200 \
        --false_positive_weight 5.0

Output:
    training/wakeword/output/
        hey_jandal.onnx         — Stage 3 classifier (float32)
        hey_jandal_int8.onnx    — INT8 quantised variant (optional)
        melspectrogram.onnx     — Stage 1 backbone (downloaded)
        embedding_model.onnx    — Stage 2 backbone (downloaded)

Deploy: copy all three ONNX files to app/src/main/assets/models/wakeword/
"""

import argparse
import os
import sys
import shutil
from pathlib import Path

import numpy as np


# ─── Backbone model download ──────────────────────────────────────────────────

BACKBONE_URLS = {
    "melspectrogram.onnx": (
        "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/melspectrogram.onnx"
    ),
    "embedding_model.onnx": (
        "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/embedding_model.onnx"
    ),
}


def download_backbones(output_dir: Path) -> tuple[Path, Path]:
    """Download Stage 1/2 backbone models if not already present."""
    import urllib.request

    mel_path = output_dir / "melspectrogram.onnx"
    emb_path = output_dir / "embedding_model.onnx"

    for fname, url in BACKBONE_URLS.items():
        dest = output_dir / fname
        if dest.exists():
            print(f"  {fname} already present, skipping download")
            continue
        print(f"  Downloading {fname} from openWakeWord releases...")
        try:
            urllib.request.urlretrieve(url, dest)
            print(f"  Downloaded {fname} ({dest.stat().st_size // 1024}KB)")
        except Exception as e:
            print(f"ERROR: failed to download {fname}: {e}", file=sys.stderr)
            print(
                f"  Manual download: {url}",
                file=sys.stderr,
            )
            sys.exit(1)

    return mel_path, emb_path


# ─── Embedding computation ────────────────────────────────────────────────────

def compute_embeddings(
    wav_dir: Path,
    mel_path: Path,
    emb_path: Path,
) -> np.ndarray:
    """
    Run all WAV files through openWakeWord's AudioFeatures pipeline and return
    embedding windows, shape (N, 16, 96).

    Uses AudioFeatures._streaming_features() + get_features() — the same internal
    path that produced the ACAV pre-computed negatives. This guarantees that
    positive embeddings and ACAV negatives share an identical feature distribution.

    AudioFeatures internally:
      - accumulates a 76-row mel ring with an 8-row stride
      - applies the mel normalisation (value / 10 + 2)
      - calls embedding_model.onnx on the full [1,76,32,1] window
    so none of those steps need to be replicated here.
    """
    try:
        from openwakeword.utils import AudioFeatures
    except ImportError:
        print(
            "ERROR: openwakeword not installed.\n"
            "  pip install git+https://github.com/dscripka/openWakeWord.git",
            file=sys.stderr,
        )
        sys.exit(1)

    try:
        import soundfile as sf
    except ImportError:
        import scipy.io.wavfile as _wav
        def _load_wav(path: str) -> np.ndarray:
            sr, data = _wav.read(path)
            if data.dtype != np.float32:
                data = data.astype(np.float32) / np.iinfo(data.dtype).max
            if data.ndim > 1:
                data = data.mean(axis=1)
            return data
    else:
        def _load_wav(path: str) -> np.ndarray:
            data, sr = sf.read(path, dtype='float32', always_2d=False)
            if data.ndim > 1:
                data = data.mean(axis=1)
            return data

    CONTEXT_FRAMES = 16  # matches ACAV shape (N,16,96) and runtime EMBEDDING_FRAMES=16
    # Minimum audio length: enough for the mel ring to fill (76 rows × 160 samples stride
    # + initial context) plus CONTEXT_FRAMES embedding steps. 3s is generous.
    MIN_AUDIO_SAMPLES = 16000 * 3

    wav_files = sorted(wav_dir.glob("*.wav"))
    if not wav_files:
        print(f"ERROR: no WAV files found in {wav_dir}", file=sys.stderr)
        sys.exit(1)

    print(f"  Computing embeddings for {len(wav_files)} clips via AudioFeatures...")
    all_windows: list[np.ndarray] = []

    # Create one AudioFeatures instance and reset() between clips — avoids
    # re-loading ONNX sessions 954 times (each init takes ~200ms).
    af = AudioFeatures(
        melspec_model_path=str(mel_path),
        embedding_model_path=str(emb_path),
    )

    for wav_path in wav_files:
        try:
            audio = _load_wav(str(wav_path))
        except Exception as e:
            print(f"    SKIP {wav_path.name}: {e}", file=sys.stderr)
            continue

        # Pad short clips so the mel ring fills and we get at least CONTEXT_FRAMES embeddings
        if len(audio) < MIN_AUDIO_SAMPLES:
            audio = np.pad(audio, (0, MIN_AUDIO_SAMPLES - len(audio)))

        # Reset internal state so previous clip doesn't bleed into this one
        af.reset()
        af._streaming_features(audio)

        # get_features returns (1, N, 96); squeeze batch dim → (N, 96)
        all_emb = af.get_features(n_feature_frames=len(af.feature_buffer), start_ndx=0)
        all_emb = np.array(all_emb).squeeze(0)  # (N, 96)

        if len(all_emb) < CONTEXT_FRAMES:
            print(f"    SKIP {wav_path.name}: only {len(all_emb)} embedding frames (need {CONTEXT_FRAMES})")
            continue

        # Slide a 16-frame window across the embedding sequence
        for i in range(len(all_emb) - CONTEXT_FRAMES + 1):
            all_windows.append(all_emb[i : i + CONTEXT_FRAMES])  # (16, 96)

    if not all_windows:
        print("ERROR: no embedding windows extracted — check positive clips", file=sys.stderr)
        sys.exit(1)

    result = np.stack(all_windows)  # (N, 16, 96)
    print(f"  {len(result)} embedding windows extracted")
    return result


# ─── Classifier training ──────────────────────────────────────────────────────

def train_classifier(
    positive_embeddings: np.ndarray,
    negative_embeddings: np.ndarray,
    epochs: int,
    false_positive_weight: float,
) -> "torch.nn.Module":
    """
    Train a small FCN classifier on pre-computed embedding windows.

    Architecture matches the official openWakeWord classifier:
    flatten(16×96=1536) → Linear(1536,128) → ReLU → Linear(128,1) → Sigmoid
    """
    import torch
    import torch.nn as nn

    class WakeWordClassifier(nn.Module):
        def __init__(self):
            super().__init__()
            self.net = nn.Sequential(
                nn.Flatten(),
                nn.Linear(16 * 96, 128),
                nn.ReLU(),
                nn.Linear(128, 1),
                nn.Sigmoid(),
            )

        def forward(self, x):
            return self.net(x)

    pos = torch.tensor(positive_embeddings, dtype=torch.float32)
    neg = torch.tensor(negative_embeddings, dtype=torch.float32)

    pos_labels = torch.ones(len(pos), 1)
    neg_labels = torch.zeros(len(neg), 1)

    X = torch.cat([pos, neg])
    y = torch.cat([pos_labels, neg_labels])

    # false_positive_weight penalises the NEGATIVE class (false positives are negatives
    # classified as positive). Higher weight → stronger push away from FPs.
    # Positives get weight 1.0; negatives get false_positive_weight.
    sample_weights = torch.cat([
        torch.ones(len(pos)),
        torch.full((len(neg),), false_positive_weight),
    ])

    dataset = torch.utils.data.TensorDataset(X, y, sample_weights)
    loader = torch.utils.data.DataLoader(dataset, batch_size=64, shuffle=True)

    model = WakeWordClassifier()
    optimiser = torch.optim.Adam(model.parameters(), lr=1e-3)
    criterion = nn.BCELoss(reduction="none")

    print(f"  Training classifier: {len(pos)} pos / {len(neg)} neg, {epochs} epochs")

    for epoch in range(epochs):
        model.train()
        total_loss = 0.0
        for xb, yb, wb in loader:
            optimiser.zero_grad()
            pred = model(xb)
            loss = (criterion(pred, yb) * wb.unsqueeze(1)).mean()
            loss.backward()
            optimiser.step()
            total_loss += loss.item()
        if (epoch + 1) % 20 == 0 or epoch == 0:
            print(f"    Epoch {epoch+1:3d}/{epochs}  loss={total_loss/len(loader):.4f}")

    return model


# ─── ONNX export ─────────────────────────────────────────────────────────────

def export_onnx(model: "torch.nn.Module", output_path: Path) -> None:
    import torch

    model.eval()
    dummy = torch.zeros(1, 16, 96)
    torch.onnx.export(
        model,
        dummy,
        str(output_path),
        input_names=["input"],
        output_names=["output"],
        dynamic_axes={"input": {0: "batch"}, "output": {0: "batch"}},
        opset_version=12,
    )
    print(f"  Exported {output_path} ({output_path.stat().st_size // 1024}KB)")


def quantise(src: Path, dst: Path) -> None:
    from onnxruntime.quantization import quantize_dynamic, QuantType

    quantize_dynamic(str(src), str(dst), weight_type=QuantType.QInt8)
    print(f"  Quantised → {dst} ({dst.stat().st_size // 1024}KB)")


# ─── Negative corpus ─────────────────────────────────────────────────────────

def get_negative_embeddings(
    mel_path: Path,
    emb_path: Path,
    n_target: int,
) -> np.ndarray:
    """
    Load background negatives, preferring the ACAV pre-computed features
    (openwakeword_features_ACAV100M_2000_hrs_16bit.npy, shape (5625000,16,96))
    which are already in the correct [N,16,96] format and match what the official
    OWW training notebook uses. Falls back to downloading OWW background clips,
    then to synthetic silence/noise if neither is available.
    """
    # 1. Check for ACAV pre-computed features (preferred)
    acav_candidates = [
        Path("openwakeword_features_ACAV100M_2000_hrs_16bit.npy"),
        Path("/home/lokhor/Documents/development/openWakeWord/openwakeword_features_ACAV100M_2000_hrs_16bit.npy"),
    ]
    for acav_path in acav_candidates:
        if acav_path.exists():
            print(f"  Loading ACAV pre-computed features from {acav_path}")
            acav = np.load(str(acav_path), mmap_mode="r")
            # shape is (5625000, 16, 96) — sample n_target rows randomly
            idx = np.random.default_rng(42).choice(len(acav), size=min(n_target, len(acav)), replace=False)
            result = acav[idx].astype(np.float32)
            print(f"  Sampled {len(result)} ACAV windows (shape {result.shape})")
            return result

    # 2. Fall back to downloading OWW background clips
    try:
        from openwakeword.utils import get_negative_clips

        neg_dir = Path("/tmp/oww_negatives")
        neg_dir.mkdir(exist_ok=True)
        clips = get_negative_clips(output_dir=str(neg_dir), n_clips=n_target)
        print(f"  Downloaded {len(clips)} background negative clips")
        return compute_embeddings(neg_dir, mel_path, emb_path)
    except Exception as e:
        print(f"  Warning: could not fetch openWakeWord negatives ({e})", file=sys.stderr)
        print("  Falling back to synthetic negatives (silence + Gaussian noise)")

    # 3. Synthetic silence/noise fallback
    rng = np.random.default_rng(42)
    import onnxruntime as ort

    FRAME_SAMPLES = 1280
    CONTEXT_FRAMES = 16

    mel_session = ort.InferenceSession(str(mel_path))
    emb_session = ort.InferenceSession(str(emb_path))
    mel_input = mel_session.get_inputs()[0].name
    emb_input = emb_session.get_inputs()[0].name

    windows = []
    for _ in range(n_target):
        if rng.random() < 0.5:
            audio = rng.standard_normal(CONTEXT_FRAMES * FRAME_SAMPLES).astype(np.float32) * 0.01
        else:
            audio = np.zeros(CONTEXT_FRAMES * FRAME_SAMPLES, dtype=np.float32)
        frames = []
        for start in range(0, len(audio), FRAME_SAMPLES):
            chunk = audio[start : start + FRAME_SAMPLES].reshape(1, FRAME_SAMPLES)
            mel = mel_session.run(None, {mel_input: chunk})[0]
            mel = mel / 10.0 + 2.0
            emb = emb_session.run(None, {emb_input: mel})[0]
            frames.append(emb.reshape(96))
        if len(frames) >= CONTEXT_FRAMES:
            windows.append(np.stack(frames[:CONTEXT_FRAMES]))

    return np.stack(windows)


# ─── Main ─────────────────────────────────────────────────────────────────────

def main() -> None:
    parser = argparse.ArgumentParser(description="Train Hey Jandal wake word classifier")
    parser.add_argument(
        "--positive_dir",
        default="training/wakeword/data/positives",
        help="Directory of positive WAV clips (16kHz mono)",
    )
    parser.add_argument(
        "--output_dir",
        default="training/wakeword/output",
        help="Directory to write hey_jandal.onnx and backbone models",
    )
    parser.add_argument("--epochs", type=int, default=200)
    parser.add_argument(
        "--false_positive_weight",
        type=float,
        default=5.0,
        help="Training loss weight applied to negative examples (higher = fewer FPs)",
    )
    parser.add_argument(
        "--neg_count",
        type=int,
        default=1000,
        help="Number of background negative clips to use",
    )
    parser.add_argument(
        "--quantise",
        action="store_true",
        help="Also export an INT8 quantised variant",
    )
    parser.add_argument(
        "--deploy",
        metavar="ASSETS_DIR",
        default=None,
        help="Copy all three ONNX files to the given assets directory after training",
    )
    args = parser.parse_args()

    out_dir = Path(args.output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    pos_dir = Path(args.positive_dir)

    print("=== Step 1: Download backbone models ===")
    mel_path, emb_path = download_backbones(out_dir)

    print("\n=== Step 2: Compute positive embeddings ===")
    pos_embeddings = compute_embeddings(pos_dir, mel_path, emb_path)

    print("\n=== Step 3: Load negative corpus ===")
    neg_embeddings = get_negative_embeddings(mel_path, emb_path, args.neg_count)

    print("\n=== Step 4: Train classifier ===")
    model = train_classifier(
        pos_embeddings,
        neg_embeddings,
        args.epochs,
        args.false_positive_weight,
    )

    print("\n=== Step 5: Export ONNX ===")
    onnx_path = out_dir / "hey_jandal.onnx"
    export_onnx(model, onnx_path)

    if args.quantise:
        quantise(onnx_path, out_dir / "hey_jandal_int8.onnx")

    print("\n=== Step 6: Deploy ===")
    if args.deploy:
        deploy_dir = Path(args.deploy)
        deploy_dir.mkdir(parents=True, exist_ok=True)
        for fname in ("melspectrogram.onnx", "embedding_model.onnx", "hey_jandal.onnx"):
            src = out_dir / fname
            if src.exists():
                shutil.copy2(src, deploy_dir / fname)
                print(f"  Copied {fname} → {deploy_dir / fname}")
        print(f"\nDeploy complete. Rebuild and install the APK to activate Hey Jandal.")
    else:
        print(f"  Skipping deploy. To deploy manually:")
        print(f"    cp {out_dir}/{{melspectrogram,embedding_model,hey_jandal}}.onnx \\")
        print(f"       app/src/main/assets/models/wakeword/")

    print(f"\nDone. Model: {onnx_path}")


if __name__ == "__main__":
    main()
