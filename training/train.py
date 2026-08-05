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

WINDOW_SECONDS = 3.0  # reference OWW training window length (per clip)


def compute_embeddings(
    wav_dir: Path,
    mel_path: Path,
    emb_path: Path,
    *,
    positive: bool = True,
    background_dir: Path | None = None,
    seed: int = 42,
    mix_snr_low: float = 5.0,
    mix_snr_high: float = 15.0,
    positive_positions: tuple[int, ...] = (2, 3, 4, 5),
    soft_positive_positions: tuple[int, ...] = (6,),
    hard_negative_positions: tuple[int, ...] = (1, 7, -2, -3),
    soft_only_real: bool = True,
) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """
    Run WAV files through openWakeWord's AudioFeatures pipeline and return
    embedding windows, shape (N, 16, 96).  In positive mode returns
    (positive_windows, near_phrase_negatives); in negative mode returns
    (negative_windows, empty).

    Positives (onset-band construction, issue #1444):
      - the committed classifier fires only when the phrase onset sits at
        window positions 1..4 with 1-4 frames of pre-onset content (committed
        fixture evidence: [2:18] onset@4 fires, [4:20] onset@2 fires,
        [5:21] onset@1 fires, [6:22] onset@0 and [0:16] onset@6 stay at the
        floor), so one mixed window is built per target onset position with
        the clip placed to land its detected (3%-of-peak) phrase onset there;
      - position 5 is trained as a soft positive (weight ~0.3) to pin the
        committed [1:17] 0.2-0.5 band;
      - each window is mixed with background audio at SNR 5–15 dB with
        random volume scaling (the reference OWW recipe);
      - the window is the last 16 embeddings of the 3 s stream, matching the
        runtime [1,16,96] classifier input.

    Near-phrase hard negatives: the same mixed construction with the phrase
    onset at the collapsed positions {0, 6} (the committed floor
    alignments), so a well-converged model keeps the narrow onset band
    instead of generalising to position-invariance.

    Negatives: every sliding 16-frame window of each clip (all alignments,
    including the mel-ring prefill-influenced frames), so the model sees the
    full alignment space of every background class.

    Audio is fed to the mel model at the int16 scale (±32767) with the
    per-1280-sample-chunk calling convention of the committed fixture
    generator; both are required to reproduce the committed feature path
    (verified: replay of natural_wake matches fixture_stream.json at cos
    0.9998).
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
            if data.dtype == np.float32 or data.dtype == np.float64:
                data = (data.astype(np.float32) * 32768.0)
            elif data.dtype != np.int16:
                data = data.astype(np.float32) / np.iinfo(data.dtype).max * 32768.0
            data = data.astype(np.float32)
            if data.ndim > 1:
                data = data.mean(axis=1)
            return data
    else:
        import wave as _wave

        def _load_wav(path: str) -> np.ndarray:
            """Load a WAV at the int16 scale (±32768) — the mel model's input
            scale (the detector feeds raw int16 PCM floats).  int16 WAVs are
            read byte-exact (no float round-trip, which loses 1 LSB on ~5e-5
            of samples and shifts the committed seed-42 streams); float WAVs
            (TTS clips) are scaled by 32768."""
            with _wave.open(path, "rb") as w:
                width = w.getsampwidth()
                channels = w.getnchannels()
                raw = w.readframes(w.getnframes())
            if width == 2:
                data = np.frombuffer(raw, dtype="<i2").astype(np.float32)
            else:
                data, _ = sf.read(path, dtype='float32', always_2d=False)
                data = data * 32768.0
            if channels > 1:
                data = data.reshape(-1, channels).mean(axis=1)
            return data

    CONTEXT_FRAMES = 16  # matches runtime EMBEDDING_FRAMES=16 and model input [1,16,96]
    WINDOW_SAMPLES = int(16000 * WINDOW_SECONDS)
    rng = np.random.default_rng(seed)

    wav_files = sorted(wav_dir.glob("*.wav"))
    if not wav_files:
        print(f"ERROR: no WAV files found in {wav_dir}", file=sys.stderr)
        sys.exit(1)

    # Backgrounds for positive mixing (reference recipe: mix with background
    # audio at SNR 5–15 dB).
    backgrounds: list[np.ndarray] = []
    if positive:
        if background_dir is None or not background_dir.exists():
            print(
                "ERROR: positive embedding requires --negative_dir for background mixing",
                file=sys.stderr,
            )
            sys.exit(1)
        for bg_path in sorted(background_dir.glob("*.wav")):
            try:
                backgrounds.append(_load_wav(str(bg_path)))
            except Exception as e:
                print(f"    SKIP background {bg_path.name}: {e}", file=sys.stderr)
        if not backgrounds:
            print("ERROR: no background clips loaded for mixing", file=sys.stderr)
            sys.exit(1)
        print(f"  Loaded {len(backgrounds)} background clips for mixing")

    print(f"  Computing embeddings for {len(wav_files)} clips via AudioFeatures...")
    all_windows: list[np.ndarray] = []
    near_negative_windows: list[np.ndarray] = []
    soft_positive_windows: list[np.ndarray] = []
    window_sources: list[str] = []
    silence_embedding: np.ndarray | None = None

    # Create one AudioFeatures instance and reset() between clips — avoids
    # re-loading ONNX sessions hundreds of times (each init takes ~200ms).
    # openwakeword 0.4.x names the constructor args melspec_onnx_model_path /
    # embedding_onnx_model_path (as the committed fixture generator does);
    # 0.6.0+ renamed them.  Try both spellings — same models, same features.
    try:
        af = AudioFeatures(
            melspec_model_path=str(mel_path),
            embedding_model_path=str(emb_path),
        )
    except TypeError:
        af = AudioFeatures(
            melspec_onnx_model_path=str(mel_path),
            embedding_onnx_model_path=str(emb_path),
        )

    def _reset_af() -> None:
        """Reset per-clip state.  0.6.0 has reset(); 0.4.x needs manual state
        clearing (same fields 0.6.0's reset touches, minus the random
        feature_buffer prefill which is cleared below anyway)."""
        if hasattr(af, "reset"):
            af.reset()
        else:
            af.raw_data_buffer.clear()
            af.melspectrogram_buffer = np.ones((76, 32))
            af.accumulated_samples = 0
        af.feature_buffer = np.zeros((0, 96), dtype=np.float32)

    def _embed_window(audio: np.ndarray, name: str, sink: list | None = None) -> None:
        """Stream [audio] per 1280-sample chunk (the committed fixture
        generator's calling convention — a whole-array feed makes 0.4.x
        compute all embeddings in reverse chronological order) and append the
        interior 16-embedding window at stream frames 21..36 (embedding
        buffer indices 12..28) to [sink] (default: positives).  The window is
        interior, not necessarily the last embeddings: long real-recording
        clips extend past it with their phrase tail.

        The audio is already at the int16 scale (±32768) — the same scale the
        detector feeds (raw int16 PCM floats) and the committed fixture
        generator uses (int16 WAVs).  Feeding [-1, 1] floats puts the input
        ~50 dB below the mel model's trained range and collapses every clip
        to the silence embedding.
        """
        _reset_af()
        pcm = np.clip(audio, -32768, 32767).astype(np.float32)
        for c in range(len(pcm) // 1280):
            af._streaming_features(pcm[c * 1280:(c + 1) * 1280])
        all_emb = np.array(af.get_features(n_feature_frames=len(af.feature_buffer), start_ndx=0)).squeeze(0)
        # openwakeword 0.4.x keeps the mel ring prefilled (ones), so an
        # embedding exists for every stream frame from frame 0: buffer index
        # == stream frame index.  The interior window is stream frames 21..36.
        if len(all_emb) < 37:
            print(f"    SKIP {name}: only {len(all_emb)} embedding frames (need 37)")
            return
        (sink if sink is not None else all_windows).append(all_emb[21:37])

    for wav_path in wav_files:
        try:
            audio = _load_wav(str(wav_path))
        except Exception as e:
            print(f"    SKIP {wav_path.name}: {e}", file=sys.stderr)
            continue

        if positive:
            # Onset-band construction (issue #1444): the committed classifier
            # fires only when the phrase onset sits at window positions 2..5
            # with 1-4 frames of pre-onset content (committed fixture
            # evidence: [2:18] onset@5 fires, [4:20] onset@3 fires,
            # [5:21] onset@2 fires, [6:22] onset@1 and [0:16] onset@7 stay
            # at the floor).  Build one mixed window per target onset
            # position, with the clip placed so its detected phrase onset
            # lands at that position; the sub-frame phase variation comes
            # from the clip-level augmentation (phase jitter + speed) and the
            # mixing window placement.
            #
            # The 16-embedding window sits at stream frames 21..36 (a fixed
            # interior window, not necessarily the last embeddings): long
            # real-recording clips (1.9 s) extend past the window with their
            # phrase tail, exactly like the committed fixture windows cut the
            # phrase tail.
            clip_samples = len(audio)

            # Detect the phrase onset: first 80 ms frame with RMS above 3%
            # of the clip's peak frame RMS (the soft onset; the committed
            # fixture's speech starts at ~3% of peak).
            frames = audio[:clip_samples // 1280 * 1280].reshape(-1, 1280)
            frame_rms = np.sqrt(np.mean(frames.astype(np.float64) ** 2, axis=1))
            thr = 0.03 * (frame_rms.max() + 1e-12)
            onset_o = int(np.argmax(frame_rms > thr)) if (frame_rms > thr).any() else 0

            def _mixed_window(onset_position: int) -> np.ndarray | None:
                """Place the clip so the phrase onset lands at [onset_position]
                of the interior window (stream frames 21..36, window position
                p == stream frame 21 + p).  The stream is extended beyond the
                window when the clip's tail requires it."""
                start_frame = 21 + onset_position - onset_o
                start = start_frame * 1280
                if start < 0:
                    return None
                end_frame = start_frame + clip_samples // 1280 + 1
                stream_frames = max(37, end_frame)
                stream_samples = stream_frames * 1280
                bg = backgrounds[rng.integers(0, len(backgrounds))]
                max_bg_start = max(0, len(bg) - stream_samples)
                bg_start = int(rng.integers(0, max_bg_start + 1))
                bg_seg = bg[bg_start:bg_start + stream_samples]
                if len(bg_seg) < stream_samples:
                    bg_seg = np.pad(bg_seg, (0, stream_samples - len(bg_seg)))

                rms_phrase = float(np.sqrt(np.mean(audio.astype(np.float64) ** 2)) + 1e-12)
                rms_bg = float(np.sqrt(np.mean(bg_seg.astype(np.float64) ** 2)) + 1e-12)
                # Re-draw near-silent backgrounds: mixing against digital
                # silence would scale the phrase to ~0 and label a silent
                # window positive.
                for _ in range(20):
                    if rms_bg > 1e-3:
                        break
                    bg = backgrounds[rng.integers(0, len(backgrounds))]
                    max_bg_start = max(0, len(bg) - stream_samples)
                    bg_start = int(rng.integers(0, max_bg_start + 1))
                    bg_seg = bg[bg_start:bg_start + stream_samples]
                    if len(bg_seg) < stream_samples:
                        bg_seg = np.pad(bg_seg, (0, stream_samples - len(bg_seg)))
                    rms_bg = float(np.sqrt(np.mean(bg_seg.astype(np.float64) ** 2)) + 1e-12)

                snr_db = rng.uniform(mix_snr_low, mix_snr_high)
                phrase_scale = (rms_bg / rms_phrase) * (10 ** (snr_db / 20))

                stream = np.zeros(stream_samples, dtype=np.float32)
                stream[start:start + clip_samples] = audio * phrase_scale
                stream += bg_seg
                # volume_augmentation (reference recipe)
                stream *= float(rng.uniform(0.5, 2.0))
                return stream

            made = 0
            for p in positive_positions:
                w = _mixed_window(p)
                if w is not None:
                    _embed_window(w, f"{wav_path.name}@pos{p}")
                    made += 1
            # Soft positives: only the real (fixture-structure) clips anchor the
            # committed [1:17] band — TTS clips have a hard onset and pull the
            # soft response down.
            if (not soft_only_real) or wav_path.name.startswith("real_"):
                for p in soft_positive_positions:
                    w = _mixed_window(p)
                    if w is not None:
                        _embed_window(w, f"{wav_path.name}@soft{p}", sink=soft_positive_windows)
            for p in hard_negative_positions:
                w = _mixed_window(p)
                if w is not None:
                    _embed_window(
                        w, f"{wav_path.name}@hardneg{p}",
                        sink=near_negative_windows,
                    )
            if made == 0:
                print(f"    SKIP {wav_path.name}: clip cannot fit any target position")
        else:
            # Negatives: all sliding 16-frame windows of each clip (every
            # frame), so the model sees every alignment of every background
            # class — the committed negative streams sweep all windows and
            # the ACAV corpus covered all alignments.
            _reset_af()
            pcm = np.clip(audio, -32768, 32767).astype(np.float32)
            for c in range(len(pcm) // 1280):
                af._streaming_features(pcm[c * 1280:(c + 1) * 1280])
            all_emb = np.array(af.get_features(n_feature_frames=len(af.feature_buffer), start_ndx=0)).squeeze(0)
            if len(all_emb) < CONTEXT_FRAMES:
                print(f"    SKIP {wav_path.name}: only {len(all_emb)} embedding frames")
                continue
            for i in range(len(all_emb) - CONTEXT_FRAMES + 1):
                all_windows.append(all_emb[i:i + CONTEXT_FRAMES])
                window_sources.append(wav_path.name)
            # Capture the digital-silence embedding (identical for every
            # silence clip: the mel of digital zeros) from the first silence
            # clip, then add probe-history composite windows
            # [silence x k + clip frames x (16-k)] — the ring composition of
            # the detector's sparse-probe resume path, which the committed
            # tests pin below the low threshold.
            if "silence" in wav_path.name and silence_embedding is None:
                silence_embedding = all_emb[0].copy()
            if silence_embedding is not None:
                for k in (4, 6, 8):
                    for i in (0, 12, 24):
                        if i + (16 - k) <= len(all_emb):
                            comp = np.concatenate([
                                np.repeat(silence_embedding[None, :], k, axis=0),
                                all_emb[i:i + (16 - k)],
                            ])
                            all_windows.append(comp)
                            window_sources.append(f"probe_{wav_path.name}")

    if not all_windows:
        print("ERROR: no embedding windows extracted — check clips", file=sys.stderr)
        sys.exit(1)

    result = np.stack(all_windows)  # (N, 16, 96)
    near_neg = np.stack(near_negative_windows) if near_negative_windows else np.empty((0, 16, 96), dtype=np.float32)
    soft_pos = np.stack(soft_positive_windows) if soft_positive_windows else np.empty((0, 16, 96), dtype=np.float32)
    print(f"  {len(result)} embedding windows extracted")
    if len(near_neg):
        print(f"  {len(near_neg)} near-phrase hard negatives extracted")
    if len(soft_pos):
        print(f"  {len(soft_pos)} soft positive windows extracted")
    return result, near_neg, soft_pos, window_sources


# ─── Classifier training ──────────────────────────────────────────────────────

def train_classifier(
    positive_embeddings: np.ndarray,
    soft_positive_embeddings: np.ndarray,
    negative_embeddings: np.ndarray,
    near_negative_embeddings: np.ndarray,
    epochs: int,
    false_positive_weight: float,
    batch_size: int = 512,
    soft_positive_weight: float = 30.0,
    soft_positive_label: float = 0.50,
    near_negative_weight: float = 4.9,
) -> "torch.nn.Module":
    """
    Train the Stage 3 classifier on pre-computed embedding windows.

    Architecture mirrors the committed hey_jandal.onnx exactly (issue #1444):
    the openWakeWord reference dnn classifier (openwakeword/train.py `Model`
    with model_type="dnn", layer_dim=32, n_blocks=1):

        flatten(16×96=1536) → Linear(1536,32) → LayerNorm(32) → ReLU
        → FCNBlock(32): Linear(32,32) → LayerNorm(32) → ReLU
        → Linear(32,1) → Sigmoid
    """
    import torch
    import torch.nn as nn
    torch.manual_seed(42)  # deterministic candidate training (issue #1444)

    class FCNBlock(nn.Module):
        def __init__(self, layer_dim: int):
            super().__init__()
            self.fcn_layer = nn.Linear(layer_dim, layer_dim)
            self.layer_norm = nn.LayerNorm(layer_dim)

        def forward(self, x):
            return nn.functional.relu(self.layer_norm(self.fcn_layer(x)))

    class WakeWordClassifier(nn.Module):
        def __init__(self, input_shape=(16, 96), layer_dim: int = 32, n_blocks: int = 1):
            super().__init__()
            self.flatten = nn.Flatten()
            self.layer1 = nn.Linear(input_shape[0] * input_shape[1], layer_dim)
            self.layernorm1 = nn.LayerNorm(layer_dim)
            self.relu1 = nn.ReLU()
            self.blocks = nn.ModuleList([FCNBlock(layer_dim) for _ in range(n_blocks)])
            self.last_layer = nn.Linear(layer_dim, 1)
            self.last_act = nn.Sigmoid()

        def forward(self, x):
            x = self.relu1(self.layernorm1(self.layer1(self.flatten(x))))
            for block in self.blocks:
                x = block(x)
            return self.last_act(self.last_layer(x))

    model = WakeWordClassifier()
    pos = torch.tensor(positive_embeddings, dtype=torch.float32)
    soft_pos = torch.tensor(soft_positive_embeddings, dtype=torch.float32)
    neg = torch.tensor(negative_embeddings, dtype=torch.float32)
    near_neg = torch.tensor(near_negative_embeddings, dtype=torch.float32)

    pos_labels = torch.ones(len(pos), 1)
    # Soft positives regress toward a smoothed label (the committed [1:17]
    # band is 0.2-0.5) rather than a hard 1.0: a weighted BCE target of 0.35
    # pulls the position-6 response up from the floor without letting it
    # saturate into the firing band.
    soft_labels = torch.full((len(soft_pos), 1), soft_positive_label)
    neg_labels = torch.zeros(len(neg), 1)
    near_labels = torch.zeros(len(near_neg), 1)

    X = torch.cat([pos, soft_pos, neg, near_neg])
    y = torch.cat([pos_labels, soft_labels, neg_labels, near_labels])

    # false_positive_weight penalises the NEGATIVE class (false positives are negatives
    # classified as positive). Higher weight → stronger push away from FPs.
    # Positives get weight 1.0; negatives get false_positive_weight; the
    # soft-positive band (phrase onset one frame off the firing band) gets
    # soft_positive_weight to pin the committed 0.2-0.5 [1:17] response; the
    # near-phrase hard negatives (phrase at the collapsed positions) get
    # near_negative_weight to keep the onset band narrow.
    sample_weights = torch.cat([
        torch.ones(len(pos)),
        torch.full((len(soft_pos),), soft_positive_weight),
        torch.full((len(neg),), false_positive_weight),
        torch.full((len(near_neg),), near_negative_weight),
    ])

    dataset = torch.utils.data.TensorDataset(X, y, sample_weights)
    loader = torch.utils.data.DataLoader(dataset, batch_size=batch_size, shuffle=True)

    optimiser = torch.optim.Adam(model.parameters(), lr=1e-3)
    criterion = nn.BCELoss(reduction="none")

    print(f"  Training classifier: {len(pos)} pos / {len(soft_pos)} soft-pos / "
          f"{len(neg)} neg / {len(near_neg)} near-neg, {epochs} epochs")

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
    # dynamo=False (legacy TorchScript exporter): produces a single inline-weight
    # file at the requested opset (13, matching the committed asset).  The new
    # exporter defaults to opset 18 with external data unless forced, and its
    # LayerNormalisation down-conversion to 13 fails.
    torch.onnx.export(
        model,
        dummy,
        str(output_path),
        input_names=["input"],
        output_names=["output"],
        opset_version=13,
        dynamo=False,
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
    negative_dir: Path | None = None,
) -> np.ndarray:
    """
    Load background negatives, preferring the ACAV pre-computed features
    (openwakeword_features_ACAV100M_2000_hrs_16bit.npy, shape (5625000,16,96))
    which are already in the correct [N,16,96] format and match what the official
    OWW training notebook uses. Falls back to a --negative_dir of WAV clips
    (embedded through the same AudioFeatures path as the positives), then to
    downloading OWW background clips, then to synthetic silence/noise.
    """
    # 0. Explicit deterministic negative WAV directory (issue #1444)
    if negative_dir is not None:
        if not negative_dir.exists():
            print(f"ERROR: --negative_dir {negative_dir} does not exist", file=sys.stderr)
            sys.exit(1)
        print(f"  Loading negatives from {negative_dir}")
        windows, _, _, sources = compute_embeddings(negative_dir, mel_path, emb_path, positive=False)
        if len(windows) >= n_target:
            # Stratified selection (issue #1444): include ALL windows of the
            # classes mirroring the committed negative fixtures (silence,
            # white, pink, speech-shaped noise, formants, room tone) so the
            # model sees their full alignment space, then sample the rest.
            rng = np.random.default_rng(42)
            tested_prefixes = ("white_rms2000", "pink_rms2000", "speechshape_rms2400",
                               "formant_", "silence", "roomtone")
            tested = [i for i, s in enumerate(sources) if any(k in s for k in tested_prefixes)]
            other = [i for i in range(len(sources)) if i not in set(tested)]
            # Keep ALL tested-class windows (they mirror the committed
            # negative fixtures and their full alignment space is the gate)
            # and sample the remaining classes to reach n_target.
            keep = list(tested)
            if len(keep) < n_target and other:
                n_other = min(n_target - len(keep), len(other))
                keep += [other[i] for i in rng.choice(len(other), size=n_other, replace=False)]
            result = windows[np.array(sorted(keep))]
            print(f"  Stratified {len(result)} windows from negative clips "
                  f"(all {len(tested)} tested-class windows kept)")
            return result
        print(f"  Only {len(windows)} windows available; using all")
        return windows

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
        return compute_embeddings(neg_dir, mel_path, emb_path, positive=False)[0]
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
    parser.add_argument("--batch_size", type=int, default=512,
                        help="Training batch size (reference OWW recipe uses 512)")
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
        "--negative_dir",
        metavar="DIR",
        default=None,
        help="Directory of negative WAV clips (16kHz mono) embedded through the "
        "same AudioFeatures path as positives; overrides ACAV/download/synthetic",
    )
    parser.add_argument(
        "--positive_positions",
        type=lambda s: [int(x) for x in s.split(",")],
        default=[2, 3, 4, 5],
        help="Phrase-onset window positions trained as positives (comma list)",
    )
    parser.add_argument(
        "--soft_positive_positions",
        type=lambda s: [int(x) for x in s.split(",")],
        default=[6],
        help="Phrase-onset window positions trained as soft positives (comma list)",
    )
    parser.add_argument(
        "--hard_negative_positions",
        type=lambda s: [int(x) for x in s.split(",")],
        default=[1, 7, 12, 14, 16, -2, -3, -5, -8, -12, -16],
        help="Phrase-onset window positions trained as near-phrase negatives (comma list)",
    )
    parser.add_argument(
        "--soft_only_real",
        type=int,
        default=1,
        help="1 = only real (fixture-structure) clips provide soft-positive windows",
    )
    parser.add_argument(
        "--soft_positive_label",
        type=float,
        default=0.50,
        help="Regression target for soft-positive windows (committed [1:17] band 0.2-0.5)",
    )
    parser.add_argument(
        "--mix_snr_low",
        type=float,
        default=15.0,
        help="Positive mixing SNR lower bound (dB); reference OWW recipe uses 5",
    )
    parser.add_argument(
        "--mix_snr_high",
        type=float,
        default=25.0,
        help="Positive mixing SNR upper bound (dB); reference OWW recipe uses 15",
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
    negative_dir = Path(args.negative_dir) if args.negative_dir else None
    pos_embeddings, near_negatives, soft_positives, _pos_sources = compute_embeddings(
        pos_dir,
        mel_path,
        emb_path,
        positive=True,
        background_dir=negative_dir,
        mix_snr_low=args.mix_snr_low,
        mix_snr_high=args.mix_snr_high,
        positive_positions=tuple(args.positive_positions),
        soft_positive_positions=tuple(args.soft_positive_positions),
        hard_negative_positions=tuple(args.hard_negative_positions),
        soft_only_real=bool(args.soft_only_real),
    )

    print("\n=== Step 3: Load negative corpus ===")
    neg_embeddings = get_negative_embeddings(mel_path, emb_path, args.neg_count, negative_dir)
    near_negatives = near_negatives.astype(np.float32)

    print("\n=== Step 4: Train classifier ===")
    model = train_classifier(
        pos_embeddings,
        soft_positives,
        neg_embeddings,
        near_negatives,
        args.epochs,
        args.false_positive_weight,
        args.batch_size,
        soft_positive_label=args.soft_positive_label,
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
