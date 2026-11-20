#!/usr/bin/env python3
"""Host-side Hey Jandal wake-verifier comparison (#1045 / #1982 spike). Benchmark-only.

Three steps, all writing to a private work directory outside Git:

  prepare  build the corpus, run the production openWakeWord detector on every stream
           and cut the exact 3 s verifier rings the detector would hand over
  engine   run one verifier engine (one process per engine, for load time and memory)
           over every ring; keeps transcripts private
  score    match transcripts, simulate the production dual-threshold loop, and write
           the publishable aggregates (no audio, no transcripts)

asr4all is driven by FUTO's published reference driver `executorch/asr4all_executorch.py`,
imported at run time from the local Hugging Face snapshot. Nothing from it is copied here.

usage:
  wake_eval.py prepare --private ~/private-acoustic-fixtures --work <dir> --assets app/src/main/assets/models/wakeword
  wake_eval.py engine  <name> --private ... --work <dir>
  wake_eval.py score   --work <dir> --out docs/evidence/wake-verifier/results
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
import platform
import re
import subprocess
import sys
import time
import zlib
from pathlib import Path

import numpy as np

SR = 16_000
FRAME = 1_280          # WAKE_WORD_FRAME_SAMPLES
MEL_LEAD = 480         # MEL_LEAD_SAMPLES
MEL_ROWS = 8           # MEL_ROWS_PER_CHUNK
MEL_RING = 76          # MEL_RING_SIZE
EMB_FRAMES = 16        # EMBEDDING_FRAMES
RING = 3 * SR          # WAKE_WORD_VERIFY_WINDOW_S * SAMPLE_RATE
LOW, HIGH = 0.50, 0.65  # WAKE_WORD_DEFAULT_LOW_THRESHOLD / WAKE_WORD_DEFAULT_THRESHOLD
MAX_LOWBAND_RINGS = 20  # per item, for the system simulation

# Pinned inputs. Detector assets are the committed app assets; model files are the
# exact catalogue/published files.
DETECTOR_ASSETS = ("melspectrogram.onnx", "embedding_model.onnx", "hey_jandal.onnx")
WHISPER = {
    "tiny.en-encoder.int8.onnx": "0ce578b827c94a961aacb8fa14b02f096504b337e5c94be37c36238cbe3e8bc6",
    "tiny.en-decoder.int8.onnx": "06c0e6ff6348d427e51839219d1c886c18cfdf411e629e33f5e1679bff9c1527",
    "tiny.en-tokens.txt": "306cd27f03c1a714eca7108e03d66b7dc042abe8c258b44c199a7ed9838dd930",
}
ZIPFORMER = {
    "sherpa-stt-encoder.int8.onnx": "4b7edfb7783e94a66fead1470a066ccc2eceb14e2630d8aa1914e25f8ff35027",
    "sherpa-stt-decoder.int8.onnx": "279fb5e6ee22f0efb3be4dc62fe745864979970d158192851f307744d27a72c3",
    "sherpa-stt-joiner.int8.onnx": "480747e5f00fdbd68e0c93b09edfcd6698853526ccfe0f859ad624108ce7dda2",
    "sherpa-stt-tokens.txt": "49e3c2646595fd907228b3c6787069658f67b17377c60aeb8619c4551b2316fb",
}
ASR4ALL_REV = {
    "m": "7d6e9145109a5bece4f05831d046e93d27e38a99",
    "l": "79d1c23bc8f1174cee576b40bbaa0118bd95abed",
}
ASR4ALL_PTE = {  # executorch/xnnpack_int8/asr_encoder.pte
    "m": "d08648b9",
    "l": "2670068a",
}
KWS_DIR = "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"
KWS_FILES = {
    "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
    "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
    "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
    "tokens.txt",
}
KWS_PHRASES = ("HEY JANDAL",)


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def pinned(directory: Path, table: dict[str, str]) -> dict[str, Path]:
    out = {}
    for name, want in table.items():
        p = directory / name
        got = sha256(p)
        if got != want:
            raise SystemExit(f"hash mismatch {p}: {got}")
        out[name] = p
    return out


# ── audio ────────────────────────────────────────────────────────────────────

def read16k(path: Path) -> np.ndarray:
    import soundfile as sf
    import soxr

    x, sr = sf.read(str(path), dtype="float32", always_2d=True)
    x = x[:, 0]
    if sr != SR:
        x = soxr.resample(x, sr, SR)
    return np.clip(np.round(x * 32767), -32768, 32767).astype(np.int16)


def decode_mp3_16k(path: Path) -> np.ndarray:
    raw = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", str(path), "-ac", "1", "-ar", str(SR), "-f", "s16le", "-"],
        check=True, capture_output=True,
    ).stdout
    return np.frombuffer(raw, dtype=np.int16).copy()


def active_rms(x: np.ndarray) -> float:
    f = x.astype(np.float64)
    n = len(f) // 320
    if n == 0:
        return float(np.sqrt(np.mean(f ** 2)) + 1e-9)
    e = np.sqrt(np.mean(f[: n * 320].reshape(n, 320) ** 2, axis=1))
    keep = e[e > e.max() * 10 ** (-40 / 20)]
    return float(np.sqrt(np.mean(keep ** 2)) + 1e-9)


def mix(stream: np.ndarray, noise: np.ndarray, speech_rms: float, snr_db: float) -> np.ndarray:
    reps = int(np.ceil(len(stream) / len(noise)))
    n = np.tile(noise, reps)[: len(stream)].astype(np.float64)
    n *= speech_rms / (np.sqrt(np.mean(n ** 2)) + 1e-9) / 10 ** (snr_db / 20)
    return np.clip(stream.astype(np.float64) + n, -32768, 32767).astype(np.int16)


CONDITIONS = ("clean", "att12", "reverb", "music10", "babble10")


def reverberate(x: np.ndarray, seed: int, rt60: float = 0.5) -> np.ndarray:
    """Synthetic room: direct path plus an exponentially decaying noise tail at 0 dB DRR,
    renormalised to the dry clip's active level."""
    rng = np.random.default_rng(seed)
    n = int(rt60 * SR)
    tail = rng.standard_normal(n) * np.exp(-6.9 * np.arange(n) / (rt60 * SR))
    h = tail / np.sqrt(np.sum(tail ** 2))
    h[0] += 1.0
    m = len(x) + n - 1
    y = np.fft.irfft(np.fft.rfft(x.astype(np.float64), m) * np.fft.rfft(h, m), m)[: len(x) + n // 2]
    y *= active_rms(x) / active_rms(y)
    return np.clip(y, -32768, 32767).astype(np.int16)


def seed_of(text: str) -> int:
    return zlib.crc32(text.encode())


# ── production detector replica ──────────────────────────────────────────────

class Detector:
    """openWakeWord three-stage pipeline exactly as OnnxWakeWordDetector feeds it, with Stage 2/3
    run on every frame (no silence gate: every evaluated stream contains audio throughout)."""

    def __init__(self, assets: Path):
        import onnxruntime as ort

        opts = ort.SessionOptions()
        opts.intra_op_num_threads = 1
        mk = lambda n: ort.InferenceSession(str(assets / n), opts, providers=["CPUExecutionProvider"])
        self.mel, self.emb, self.cls = (mk(n) for n in DETECTOR_ASSETS)
        self.names = [(s.get_inputs()[0].name) for s in (self.mel, self.emb, self.cls)]

    def scores(self, pcm: np.ndarray) -> list[float | None]:
        tail = None
        mel_rows: list[np.ndarray] = []
        embs: list[np.ndarray] = []
        out: list[float | None] = []
        for i in range(len(pcm) // FRAME):
            chunk = pcm[i * FRAME:(i + 1) * FRAME].astype(np.float32)
            x = chunk if tail is None else np.concatenate([tail, chunk])
            rows = self.mel.run(None, {self.names[0]: x[None, :]})[0].reshape(-1, 32)[:MEL_ROWS]
            tail = chunk[-MEL_LEAD:]
            mel_rows.extend(rows / 10.0 + 2.0)
            mel_rows = mel_rows[-MEL_RING:]
            if len(mel_rows) < MEL_RING:
                out.append(None)
                continue
            e = self.emb.run(None, {self.names[1]: np.asarray(mel_rows, np.float32)[None, :, :, None]})[0]
            embs.append(e.reshape(-1))
            embs = embs[-EMB_FRAMES:]
            if len(embs) < EMB_FRAMES:
                out.append(None)
                continue
            c = self.cls.run(None, {self.names[2]: np.asarray(embs, np.float32)[None]})[0]
            out.append(float(np.asarray(c).reshape(-1)[0]))
        return out


def ring_at(stream: np.ndarray, frame: int) -> np.ndarray:
    end = (frame + 1) * FRAME
    return stream[max(0, end - RING):end]


# ── corpus ───────────────────────────────────────────────────────────────────

def build_items(private: Path) -> list[dict]:
    """Every evaluated stream: genuine and near-miss phrases under four conditions, background
    speech and non-speech. Streams carry real room tone before and after the phrase."""
    # Room tone: the first 0.8 s of the #1439 probe recording (before its wake phrase), looped.
    room = np.tile(read16k(private / "probe-inputs/probe_roomtone.wav")[: 4 * SR // 5], 7)
    music = decode_mp3_16k(Path.home() / "jandal-voice/music/air-light.mp3")
    breadth_dir = private / "stt-eval-breadth/16k"
    breadth = sorted(breadth_dir.glob("*.wav"))
    babble_src = [read16k(p) for p in breadth[-3:]]
    babble = sum(np.pad(b.astype(np.int32), (0, max(len(x) for x in babble_src) - len(b))) for b in babble_src)
    babble = np.clip(babble, -32768, 32767).astype(np.int16)

    phrases: list[tuple[str, str, str, str, np.ndarray]] = []  # id, class, subset, phrase_id, pcm
    phrases.append(("owner-natural", "genuine", "owner-natural", "owner", read16k(private / "natural_wake.wav")))
    for t in range(1, 5):
        p = Path.home() / f"jandal-voice/inputs/s01_wake_take{t}.wav"
        phrases.append((f"cloned1736-take{t}", "genuine", "cloned-1736", "G-1736", read16k(p)))
    for subset, sub in (("piper-multi", "piper"),):
        d = private / "wake-verifier-spike/fixtures" / sub
        if not (d / "manifest-private.json").exists():
            print(f"skipping missing fixture set {d}", file=sys.stderr)
            continue
        for row in json.loads((d / "manifest-private.json").read_text())["fixtures"]:
            p = d / row["file_name"]
            assert sha256(p) == row["sha256"], p
            phrases.append((row["fixture_id"], row["class"], subset, row["phrase_id"], read16k(p)))

    items = []
    lead, tail = room[: 2 * SR], room[-SR * 3 // 2:]
    for fid, klass, subset, pid, x in phrases:
        rms = active_rms(x)
        for cond in CONDITIONS:
            clip = x
            if cond == "att12":
                clip = (x.astype(np.float64) * 10 ** (-12 / 20)).astype(np.int16)
            elif cond == "reverb":
                clip = reverberate(x, seed_of(fid))
            s = np.concatenate([lead, clip, tail])
            if cond == "music10":
                off = seed_of(fid) % (len(music) - len(s))
                s = mix(s, music[off: off + len(s)], rms, 10)
            elif cond == "babble10":
                s = mix(s, babble, rms, 10)
            items.append({"id": f"{fid}/{cond}", "class": klass, "subset": subset, "phrase_id": pid,
                          "condition": cond, "pcm": s, "phrase_end": len(lead) + len(clip)})

    lead1, tail1 = room[:SR], room[-SR:]
    bg = [(p.stem, "owner-decision", read16k(p)) for p in sorted((private / "stt-eval-2026-11/owner-decision/16k").glob("*.wav"))]
    bg += [(p.stem, "breadth-cloned", read16k(p)) for p in breadth[:-3]]  # last 3 form the babble
    bg.append(("probe-nonwake", "probe", read16k(private / "probe-inputs/probe_nonwake.wav")))
    for fid, subset, x in bg:
        s = np.concatenate([lead1, x, tail1])
        items.append({"id": f"{fid}/clean", "class": "background_speech", "subset": subset, "phrase_id": subset,
                      "condition": "clean", "pcm": s, "phrase_end": len(s)})
    for k in range(len(music) // (5 * SR)):
        items.append({"id": f"music-{k:02d}", "class": "non_speech", "subset": "music", "phrase_id": "music",
                      "condition": "clean", "pcm": music[k * 5 * SR:(k + 1) * 5 * SR], "phrase_end": 5 * SR})
    items.append({"id": "roomtone", "class": "non_speech", "subset": "roomtone", "phrase_id": "roomtone",
                  "condition": "clean", "pcm": np.tile(room, 2), "phrase_end": 2 * len(room)})
    items.append({"id": "silence", "class": "non_speech", "subset": "silence", "phrase_id": "silence",
                  "condition": "clean", "pcm": np.zeros(5 * SR, np.int16), "phrase_end": 5 * SR})
    return items


def cmd_prepare(a) -> None:
    work = Path(a.work)
    work.mkdir(parents=True, exist_ok=True, mode=0o700)
    det = Detector(Path(a.assets))
    asset_hashes = {n: sha256(Path(a.assets) / n) for n in DETECTOR_ASSETS}
    items = build_items(Path(a.private).expanduser())
    rings: dict[str, np.ndarray] = {}
    meta = []
    for it in items:
        s = it.pop("pcm")
        sc = det.scores(s)
        valid = [(i, v) for i, v in enumerate(sc) if v is not None]
        mx_i, mx = max(valid, key=lambda t: t[1])
        low = [i for i, v in valid if v >= LOW]
        cand = low[0] if low else mx_i
        it.update({
            "scores": [None if v is None else round(v, 5) for v in sc],
            "max_score": round(mx, 5), "max_frame": mx_i, "candidate_frame": cand,
            "lowband_frames": [i for i, v in valid if LOW <= v < HIGH][:MAX_LOWBAND_RINGS],
            "above_low_frames": low[:MAX_LOWBAND_RINGS],
        })
        keys = {f"{it['id']}@{f}" for f in it["above_low_frames"] + [cand]}
        if it["class"] == "background_speech":
            # Engine-level false-accept exposure: a ring every 0.5 s across the whole utterance.
            frames = range((3 * SR // 2) // FRAME, len(s) // FRAME, (SR // 2) // FRAME)
            it["window_frames"] = list(frames)
            keys |= {f"{it['id']}@{f}" for f in frames}
        for k in keys:
            rings[k] = ring_at(s, int(k.rsplit("@", 1)[1]))
        meta.append(it)
        print(f"{it['id']:40s} max={mx:.3f} low={len(it['lowband_frames'])}", flush=True)
    np.savez(work / "rings.npz", **rings)
    (work / "items.json").write_text(json.dumps({"detector_assets": asset_hashes, "items": meta}))
    print(f"{len(meta)} items, {len(rings)} rings")


# ── engines ──────────────────────────────────────────────────────────────────

def app_text(t: str) -> str:
    """SherpaOnnxVoiceInputController.resultTextFromWakeWhisper normalisation."""
    return t.strip().rstrip("?.!,:;").lower()


class Whisper:
    threads = 2

    def __init__(self, private: Path):
        import sherpa_onnx

        f = pinned(private / "verifier-models/whisper", WHISPER)
        # initWakeWhisperRecognizer: en/transcribe, numThreads=2, cpu, greedy; Kotlin tailPaddings=1000.
        self.rec = sherpa_onnx.OfflineRecognizer.from_whisper(
            encoder=str(f["tiny.en-encoder.int8.onnx"]), decoder=str(f["tiny.en-decoder.int8.onnx"]),
            tokens=str(f["tiny.en-tokens.txt"]), language="en", task="transcribe", num_threads=2,
            decoding_method="greedy_search", tail_paddings=1000)

    def __call__(self, pcm: np.ndarray) -> dict:
        s = self.rec.create_stream()
        s.accept_waveform(SR, pcm.astype(np.float32) / 32768.0)
        self.rec.decode_stream(s)
        return {"text": app_text(s.result.text)}


class Zipformer:
    threads = 2

    def __init__(self, private: Path):
        import sherpa_onnx

        f = pinned(private / "verifier-models", ZIPFORMER)
        # initWakeOnlineRecognizer: 16 kHz/80, numThreads=2, endpoint on, greedy, maxActivePaths=4.
        self.rec = sherpa_onnx.OnlineRecognizer.from_transducer(
            tokens=str(f["sherpa-stt-tokens.txt"]), encoder=str(f["sherpa-stt-encoder.int8.onnx"]),
            decoder=str(f["sherpa-stt-decoder.int8.onnx"]), joiner=str(f["sherpa-stt-joiner.int8.onnx"]),
            num_threads=2, sample_rate=SR, feature_dim=80, enable_endpoint_detection=True,
            decoding_method="greedy_search", max_active_paths=4)

    def __call__(self, pcm: np.ndarray) -> dict:
        s = self.rec.create_stream()  # Java "" hotwords => no context graph
        s.accept_waveform(SR, pcm.astype(np.float32) / 32768.0)
        s.input_finished()
        n = 0
        while self.rec.is_ready(s) and n <= 500:
            self.rec.decode_stream(s)
            n += 1
        r = self.rec.get_result(s)
        return {"text": app_text(r.text if hasattr(r, "text") else str(r))}


def futo_driver(size: str):
    snap = Path.home() / f".cache/huggingface/hub/models--futo-org--asr4all-{size}/snapshots/{ASR4ALL_REV[size]}"
    path = snap / "executorch/asr4all_executorch.py"
    spec = importlib.util.spec_from_file_location(f"asr4all_executorch_{size}", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    mod._runtime()  # import ExecuTorch (and torch) before the memory baseline
    return mod, snap / "executorch/xnnpack_int8"


class Asr4all:
    threads = 4  # ExecuTorch's Android default is cores/2 = 4 on both test phones

    def __init__(self, size: str, tier: str, driver):
        from executorch.extension.pybindings import portable_lib

        portable_lib._unsafe_reset_threadpool(self.threads)
        mod, bundle = driver
        pte = bundle / "asr_encoder.pte"
        if not sha256(pte).startswith(ASR4ALL_PTE[size]):
            raise SystemExit(f"pte hash mismatch {pte}")
        self.e = mod.Engine(str(bundle))
        self.e.set_tier(tier)

    def __call__(self, pcm: np.ndarray) -> dict:
        e = self.e
        e.reset()
        e.decode_stream(pcm.astype(np.float32) / 32768.0)
        e.finish()
        raw = e._text()  # CTC collapse of the acoustic frames (reference driver)
        pcec = e.decode_pcec(decode_tail=True)
        return {"text": app_text(pcec), "raw": app_text(raw)}


class Kws:
    threads = 2

    def __init__(self, private: Path, threshold: float, score: float):
        import sentencepiece as spm
        import sherpa_onnx

        d = private / "wake-verifier-spike/models" / KWS_DIR
        sp = spm.SentencePieceProcessor(model_file=str(d / "bpe.model"))
        kw = Path(os.environ.get("TMPDIR", "/tmp")) / f"kws-{os.getpid()}.txt"
        kw.write_text("".join(" ".join(sp.encode(p, out_type=str)) + f" @{p.replace(' ', '_')}\n" for p in KWS_PHRASES))
        self.kws = sherpa_onnx.KeywordSpotter(
            tokens=str(d / "tokens.txt"),
            encoder=str(d / "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"),
            decoder=str(d / "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"),
            joiner=str(d / "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"),
            keywords_file=str(kw), num_threads=2, max_active_paths=4,
            keywords_score=score, keywords_threshold=threshold, num_trailing_blanks=1)
        kw.unlink()

    def __call__(self, pcm: np.ndarray) -> dict:
        s = self.kws.create_stream()
        s.accept_waveform(SR, pcm.astype(np.float32) / 32768.0)
        s.accept_waveform(SR, np.zeros(SR // 2, np.float32))  # flush the chunked encoder
        s.input_finished()
        hit = ""
        while self.kws.is_ready(s):
            self.kws.decode_stream(s)
            r = self.kws.get_result(s)
            if r:
                hit = r
                self.kws.reset_stream(s)
        return {"text": hit}


def make_engine(name: str, private: Path):
    if name == "whisper-tiny-en":
        return Whisper(private)
    if name == "zipformer":
        return Zipformer(private)
    m = re.fullmatch(r"asr4all-([ml])-(stream_c16r4|stream_c32r8)", name)
    if m:
        drv = futo_driver(m.group(1))
        return lambda: Asr4all(m.group(1), m.group(2), drv)
    m = re.fullmatch(r"kws-t([0-9.]+)-s([0-9.]+)", name)
    if m:
        return Kws(private, float(m.group(1)), float(m.group(2)))
    raise SystemExit(f"unknown engine {name}")


def proc_status(field: str) -> int:
    for line in Path("/proc/self/status").read_text().splitlines():
        if line.startswith(field + ":"):
            return int(line.split()[1])
    return -1


def cmd_engine(a) -> None:
    import sherpa_onnx  # noqa: F401  (import cost outside the memory delta)

    work = Path(a.work)
    rings = dict(np.load(work / "rings.npz"))
    keys = sorted(rings)
    if a.latency:
        # Quiet timing pass: N evenly spaced full-length rings, run alone on an idle host.
        full = [k for k in keys if len(rings[k]) == RING]
        keys = [full[j] for j in np.linspace(0, len(full) - 1, a.latency).astype(int)]
    private = Path(a.private).expanduser()
    lazy = None
    if a.name.startswith("asr4all"):
        lazy = make_engine(a.name, private)  # imports the driver + runtime only
    base_rss = proc_status("VmRSS")
    t0 = time.perf_counter()
    eng = lazy() if lazy else make_engine(a.name, private)
    load_ms = (time.perf_counter() - t0) * 1e3
    rss_loaded = proc_status("VmRSS")
    lat = []
    rows = []
    for k in keys:
        t = time.perf_counter()
        r = eng(rings[k])
        ms = (time.perf_counter() - t) * 1e3
        lat.append(ms)
        rows.append(json.dumps({"key": k, "ms": round(ms, 2), **r}) + "\n")
    if not a.latency:
        out = work / f"engine-{a.name}.jsonl"
        out.write_text("".join(rows))
        out.chmod(0o600)
    hwm = proc_status("VmHWM")
    ring_lat = [l for k, l in zip(keys, lat) if len(rings[k]) == RING]
    meta = {
        "engine": a.name, "threads": eng.threads, "rings": len(keys), "load_ms": round(load_ms, 1),
        "first_call_ms": round(lat[0], 1),
        "warm_p50_ms": round(float(np.percentile(ring_lat[1:], 50)), 1),
        "warm_p95_ms": round(float(np.percentile(ring_lat[1:], 95)), 1),
        "rss_after_load_delta_mib": round((rss_loaded - base_rss) / 1024, 1),
        "peak_rss_delta_mib": round((hwm - base_rss) / 1024, 1),
        "host": f"{platform.processor() or platform.machine()} {os.cpu_count()} cpus, Python {platform.python_version()}",
    }
    suffix = "latency" if a.latency else "meta"
    (work / f"engine-{a.name}-{suffix}.json").write_text(json.dumps(meta, indent=1))
    print(json.dumps(meta))


# ── scoring ──────────────────────────────────────────────────────────────────

PROD = re.compile(r"\b(?:hey|hi|a)[,.]?\s*(?:jandal|jandel|handel|handal|hando|jando)\b")  # WakeWordUtils.kt
STRICT = re.compile(r"\bhey[,.]?\s+jandal\b")
JOINED = re.compile(r"(?:hey|hi|a)(?:jandal|jandel|handel|handal|hando|jando)")


def lev(a: str, b: str) -> int:
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


PREFIXES = {"hey", "hi", "a", "hay", "hei"}


def name_candidates(t: str):
    """The word after a wake prefix, alone and joined with the next word ("jan dal")."""
    w = re.findall(r"[a-z]+", t)
    for i, word in enumerate(w[:-1]):
        if word in PREFIXES:
            yield w[i + 1]
            if i + 2 < len(w):
                yield w[i + 1] + w[i + 2]


def fuzzy(t: str) -> bool:
    """Edit distance <= 2 from "jandal" after a wake prefix: the loosest matcher a weak engine would need."""
    return any(lev(c, "jandal") <= 2 for c in name_candidates(t))


MATCHERS = {
    "strict": lambda t: bool(STRICT.search(t.lower())),
    "prod": lambda t: bool(PROD.search(t.lower())),
    "joined": lambda t: bool(JOINED.search(re.sub(r"[^a-z]", "", t.lower()))),
    "fuzzy": lambda t: fuzzy(t.lower()),
}


def miss_class(t: str) -> str:
    """Why a genuine transcript fails the production matcher (for the matching-requirement table)."""
    t = t.lower()
    if PROD.search(t):
        return "prod_match"
    if JOINED.search(re.sub(r"[^a-z]", "", t)):
        return "split_or_spacing"
    c = [lev(x, "jandal") for x in name_candidates(t)]
    if c and min(c) <= 2:
        return f"name_ed{min(c)}"
    if not t.strip():
        return "empty"
    return "no_wake_name"


def rate(n, d) -> dict:
    n, d = int(n), int(d)
    return {"n": n, "of": d, "pct": round(100 * n / d, 1) if d else None}


def cmd_score(a) -> None:
    work = Path(a.work)
    data = json.loads((work / "items.json").read_text())
    items = data["items"]
    engines = {}
    for p in sorted(work.glob("engine-*-meta.json")):
        meta = json.loads(p.read_text())
        lp = work / f"engine-{meta['engine']}-latency.json"
        if lp.exists():
            meta["quiet_timing"] = json.loads(lp.read_text())
        rows = {}
        for line in open(work / f"engine-{meta['engine']}.jsonl"):
            r = json.loads(line)
            rows[r["key"]] = r
        engines[meta["engine"]] = (meta, rows)

    genuine_items = [i for i in items if i["class"] == "genuine"]
    matching = {}  # per engine/field: why genuine candidate transcripts miss, and the extra name forms needed
    extra_forms = {}
    for name, (meta, rows) in engines.items():
        if name.startswith("kws"):
            continue
        for field in ["text"] + (["raw"] if name.startswith("asr4all") else []):
            key = f"{name}{'/raw' if field == 'raw' else ''}"
            hist, forms = {}, {}
            for i in genuine_items:
                t = rows[f"{i['id']}@{i['candidate_frame']}"].get(field, "").lower()
                mc = miss_class(t)
                hist[mc] = hist.get(mc, 0) + 1
                if mc.startswith("name_ed"):
                    for c in name_candidates(t):
                        if lev(c, "jandal") <= 2:
                            forms[c] = forms.get(c, 0) + 1
                            break
            # In-sample: name spellings (of the public wake word) seen on genuine misses at least twice.
            extra_forms[key] = sorted(f for f, n in forms.items() if n >= 2)
            matching[key] = {"genuine_miss_classes": hist, "extra_name_forms_seen_twice": extra_forms[key]}

    def extended(forms):
        rx = re.compile(r"\b(?:hey|hi|a)[,.]?\s*(?:jandal|jandel|handel|handal|hando|jando"
                        + "".join("|" + re.escape(f) for f in forms) + r")\b")
        return lambda t: bool(rx.search(t.lower()))

    variants = []  # (label, engine, field, matcher)
    for name, (meta, rows) in engines.items():
        fields = ["text"] + (["raw"] if name.startswith("asr4all") else [])
        for field in fields:
            for mname in (["hit"] if name.startswith("kws") else [*MATCHERS, "prod+forms"]):
                label = f"{name}{'/raw' if field == 'raw' else ''}{'' if mname == 'hit' else ':' + mname}"
                variants.append((label, name, field, mname))

    ext = {k: extended(v) for k, v in extra_forms.items()}

    def verdict(name, field, mname, key):
        r = engines[name][1][key]
        t = r.get(field, "")
        if mname == "hit":
            return bool(t)
        if mname == "prod+forms":
            return ext[f"{name}{'/raw' if field == 'raw' else ''}"](t)
        return MATCHERS[mname](t)

    classes = ["genuine", "near_miss", "background_speech", "non_speech"]
    out = {"schema": 1, "detector_assets": data["detector_assets"], "thresholds": {"low": LOW, "high": HIGH},
           "corpus": {}, "engines": {n: m for n, (m, _) in engines.items()}, "matching": matching,
           "engine_level": {}, "system": {}, "detector_only": {}}
    for c in classes:
        sub = [i for i in items if i["class"] == c]
        out["corpus"][c] = {
            "items": len(sub),
            "subsets": {s: sum(1 for i in sub if i["subset"] == s) for s in sorted({i["subset"] for i in sub})},
            "unique_utterances": len({i["id"].split("/")[0] for i in sub}),
        }
        if c == "background_speech":
            out["corpus"][c]["windows"] = sum(len(i["window_frames"]) for i in sub)

    for label, name, field, mname in variants:
        el = {}
        for c in ("genuine", "near_miss", "non_speech"):
            sub = [i for i in items if i["class"] == c]
            acc = [verdict(name, field, mname, f"{i['id']}@{i['candidate_frame']}") for i in sub]
            el[c] = rate(sum(acc), len(acc))
            if c == "genuine":
                el["genuine_by_subset"] = {s: rate(sum(v for i, v in zip(sub, acc) if i["subset"] == s),
                                                   sum(1 for i in sub if i["subset"] == s))
                                           for s in sorted({i["subset"] for i in sub})}
                el["genuine_by_condition"] = {cd: rate(sum(v for i, v in zip(sub, acc) if i["condition"] == cd),
                                                       sum(1 for i in sub if i["condition"] == cd))
                                              for cd in CONDITIONS}
                # Same items, ring cut at the detector's peak frame (phrase fully inside the ring).
                peak = [verdict(name, field, mname, f"{i['id']}@"
                                f"{i['max_frame'] if i['max_frame'] in i['above_low_frames'] else i['candidate_frame']}")
                        for i in sub]
                el["genuine_at_peak"] = rate(sum(peak), len(peak))
                el["genuine_at_peak_by_subset"] = {
                    s: rate(sum(v for i, v in zip(sub, peak) if i["subset"] == s), sum(1 for i in sub if i["subset"] == s))
                    for s in sorted({i["subset"] for i in sub})}
            if c == "near_miss":
                el["near_miss_accepted_by_phrase"] = {}
                for i, v in zip(sub, acc):
                    if v:
                        d = el["near_miss_accepted_by_phrase"]
                        d[i["phrase_id"]] = d.get(i["phrase_id"], 0) + 1
        bg = [i for i in items if i["class"] == "background_speech"]
        wins = [(i, f) for i in bg for f in i["window_frames"]]
        wv = [verdict(name, field, mname, f"{i['id']}@{f}") for i, f in wins]
        el["background_windows"] = rate(sum(wv), len(wv))
        el["background_windows_by_subset"] = {s: rate(sum(v for (i, _), v in zip(wins, wv) if i["subset"] == s),
                                                      sum(1 for i, _ in wins if i["subset"] == s))
                                              for s in sorted({i["subset"] for i in bg})}
        out["engine_level"][label] = el

        # Production loop: >= HIGH accepts; LOW..HIGH verifies that frame's ring; reject continues.
        sysr = {}
        for c in classes:
            sub = [i for i in items if i["class"] == c]
            acc = high = ver = 0
            for i in sub:
                for f, v in enumerate(i["scores"]):
                    if v is None:
                        continue
                    if v >= HIGH:
                        acc += 1; high += 1
                        break
                    if v >= LOW and f in i["lowband_frames"] and verdict(name, field, mname, f"{i['id']}@{f}"):
                        acc += 1; ver += 1
                        break
            sysr[c] = {**rate(acc, len(sub)), "via_high": high, "via_verifier": ver}
        out["system"][label] = sysr

        # Alternative policy: verify every candidate >= LOW, including >= HIGH (no unverified fast path).
        va = {}
        for c in classes:
            sub = [i for i in items if i["class"] == c]
            acc = sum(any(verdict(name, field, mname, f"{i['id']}@{f}") for f in i["above_low_frames"]) for i in sub)
            va[c] = rate(acc, len(sub))
        out.setdefault("system_verify_all", {})[label] = va

    for c in classes:
        sub = [i for i in items if i["class"] == c]
        out["detector_only"][c] = {f"{t:.2f}": rate(sum(i["max_score"] >= t for i in sub), len(sub))
                                   for t in np.arange(0.30, 0.96, 0.05)}
        out["detector_only"][c + "_lowband_items"] = sum(1 for i in sub if i["lowband_frames"] and i["max_score"] < HIGH)
    gen = [i for i in items if i["class"] == "genuine"]
    out["detector_only"]["genuine_max_score_by_subset"] = {
        s: [round(float(np.percentile([i["max_score"] for i in gen if i["subset"] == s], q)), 3) for q in (10, 50, 90)]
        for s in sorted({i["subset"] for i in gen})}
    neg = [i for i in items if i["class"] in ("near_miss", "background_speech", "non_speech")]
    out["detector_only"]["max_negative_score"] = round(max(i["max_score"] for i in neg), 4)
    out["detector_only"]["max_negative_score_by_class"] = {
        c: round(max(i["max_score"] for i in items if i["class"] == c), 4)
        for c in ("near_miss", "background_speech", "non_speech")}
    Path(a.out).mkdir(parents=True, exist_ok=True)
    (Path(a.out) / "host-aggregates.json").write_text(json.dumps(out, indent=1))
    print(f"wrote {Path(a.out) / 'host-aggregates.json'}")


DEVICE_CONDITIONS = ("clean", "reverb", "music10")


def device_subset(items: list[dict]) -> list[tuple[dict, str, str]]:
    """Rings replayed on device: genuine, near-miss and non-speech candidate rings under the clean,
    reverb and music conditions, and every third background-speech window."""
    out = []
    for i in items:
        if i["class"] == "background_speech":
            out += [(i, f"{i['id']}@{f}", "window") for f in i["window_frames"][::3]]
        elif i["condition"] in DEVICE_CONDITIONS:
            out.append((i, f"{i['id']}@{i['candidate_frame']}", "candidate"))
    return out


def cmd_export(a) -> None:
    import soundfile as sf

    work, out = Path(a.work), Path(a.out)
    out.mkdir(parents=True, exist_ok=True, mode=0o700)
    items = json.loads((work / "items.json").read_text())["items"]
    rings = np.load(work / "rings.npz")
    index = {}
    for n, (i, key, role) in enumerate(device_subset(items)):
        name = f"wv_{n:04d}.wav"
        sf.write(out / name, rings[key], SR, subtype="PCM_16")
        index[name] = {"key": key, "class": i["class"], "subset": i["subset"], "condition": i["condition"],
                       "phrase_id": i["phrase_id"], "role": role}
    (out / "index-private.json").write_text(json.dumps(index, indent=1))
    print(f"{len(index)} rings -> {out}")


def pct(xs, q):
    return round(float(np.percentile(xs, q)), 1) if xs else None


def cmd_device_score(a) -> None:
    """Device verdicts/transcripts against the same rings: acceptance by class and host parity."""
    work, dev = Path(a.work), Path(a.device)
    index = json.loads((Path(a.rings) / "index-private.json").read_text())
    host = json.loads(Path(a.host).read_text())
    host_rows = {}
    for p in work.glob("engine-*.jsonl"):
        name = p.stem.removeprefix("engine-")
        host_rows[name] = {json.loads(l)["key"]: json.loads(l) for l in open(p)}
    classes = ("genuine", "near_miss", "background_speech", "non_speech")
    res = {"schema": 1, "device": a.label, "rings": len(index), "by_class_rings": {
        c: sum(1 for v in index.values() if v["class"] == c) for c in classes}, "lanes": {}}

    def summarise(verdicts: dict[str, bool], host_verdict) -> dict:
        s = {}
        for c in classes:
            vs = [verdicts[f] for f, v in index.items() if v["class"] == c and f in verdicts]
            s[c] = rate(sum(vs), len(vs))
        g = [(f, v) for f, v in index.items() if v["class"] == "genuine" and f in verdicts]
        s["genuine_by_condition"] = {cd: rate(sum(verdicts[f] for f, v in g if v["condition"] == cd),
                                              sum(1 for _, v in g if v["condition"] == cd))
                                     for cd in CONDITIONS}
        s["genuine_owner_natural"] = rate(sum(verdicts[f] for f, v in g if v["subset"] == "owner-natural"),
                                          sum(1 for _, v in g if v["subset"] == "owner-natural"))
        agree = [verdicts[f] == host_verdict(index[f]["key"]) for f in verdicts]
        s["host_verdict_agreement"] = rate(sum(agree), len(agree))
        return s

    # Whisper through the production debug probe: broadcast results + streamed probe log lines.
    bt = dev / "whisper-broadcasts.txt"
    if bt.exists():
        sent, verdicts, consistent = {}, {}, []
        errors = 0
        for line in open(bt):
            parts = line.split(maxsplit=2)
            if len(parts) < 3:
                continue
            f, t0, rest = parts
            m = re.search(r'data="([a-z,_]+)"', rest)
            runs = m.group(1).split(",") if m else []
            if not runs or runs[0] not in ("accept", "reject"):
                errors += 1  # probe error or a broadcast cut off when a run was stopped
                continue
            sent[f] = int(t0) / 1e3
            verdicts[f] = runs[0] == "accept"
            consistent.append(len(set(runs)) == 1)
        logt: dict[str, dict[int, float]] = {}
        for line in open(dev / "whisper-logcat.txt"):
            m = re.match(r"\s*(\d+\.\d+).*wake-verifier probe (\S+) run (\d)/\d ->", line)
            if m:
                logt.setdefault(m.group(2), {})[int(m.group(3))] = float(m.group(1))
        warm_by = {f: (v[2] - v[1]) * 1e3 for f, v in logt.items() if 1 in v and 2 in v}
        first_by = {f: (logt[f][1] - sent[f]) * 1e3 for f in sent if f in logt and 1 in logt[f]}
        warm = list(warm_by.values())
        order = sorted(sent, key=sent.get)
        # am broadcast start-up + dispatch, estimated on warm fixtures: (send -> run 1) - (run 1 -> run 2).
        oh = [first_by[f] - warm_by[f] for f in order[1:] if f in first_by and f in warm_by]
        overhead = float(np.median(oh)) if oh else None
        mp = dev / "whisper-memory.json"
        mem = json.loads(mp.read_text()) if mp.exists() else {}  # absent when a run was stopped early
        lane = summarise(verdicts, lambda k: MATCHERS["prod"](host_rows["whisper-tiny-en"][k]["text"]))
        lane.update({
            "runs_consistent": rate(sum(consistent), len(consistent)),
            "verify_warm_p50_ms": pct(warm, 50), "verify_warm_p95_ms": pct(warm, 95),
            "verify_warm_max_ms": pct(warm, 100),
            "broadcast_overhead_median_ms": round(overhead, 1) if overhead is not None else None,
            "cold_first_verify_est_ms": round(first_by[order[0]] - overhead, 1) if overhead is not None else None,
            "pss_delta_mib": round((mem["pss_after_kb"] - mem["pss_before_kb"]) / 1024, 1) if mem else None,
            "vm_hwm_mib": round(mem["vm_hwm_kb"] / 1024, 1) if mem.get("vm_hwm_kb") else None,
            "same_process": mem["pid_before"] == mem["pid_after"] if mem else None,
            "rings_completed": len(verdicts), "probe_errors": errors,
        })
        res["lanes"]["whisper-tiny-en:prod (app probe)"] = lane
    # asr4all through the #1988 ExecuTorch probe: BenchmarkTest compute-mode JSON.
    for p in sorted(dev.glob("wake-compute-et-*.json")):
        j = json.loads(p.read_text())
        hname = f"asr4all-{j['size']}-{j['tier']}"
        forms = host["matching"].get(hname, {}).get("extra_name_forms_seen_twice", [])
        rx = re.compile(r"\b(?:hey|hi|a)[,.]?\s*(?:jandal|jandel|handel|handal|hando|jando"
                        + "".join("|" + re.escape(f) for f in forms) + r")\b")
        texts = {c["id"] + ".wav": app_text(c["pcec_final"]) for c in j["clips"]}
        for mname, m in (("prod", MATCHERS["prod"]), ("prod+forms", lambda t: bool(rx.search(t)))):
            verdicts = {f: m(t) for f, t in texts.items()}
            lane = summarise(verdicts, lambda k: m(host_rows[hname][k]["text"]))
            same = [texts[f] == host_rows[hname][index[f]["key"]]["text"] for f in texts]
            ms = [c["compute_ms"] for c in j["clips"]]
            lane.update({
                "transcript_identical_to_host": rate(sum(same), len(same)),
                "load_ms": round(j["load_ms"], 1), "first_feed_ms": round(j["first_feed_ms"], 1),
                "verify_p50_ms": pct(ms, 50), "verify_p95_ms": pct(ms, 95), "verify_max_ms": pct(ms, 100),
                "pss_load_delta_mib": round((j["pss_after_load_kb"] - j["pss_baseline_kb"]) / 1024, 1),
                "pss_peak_delta_mib": round((j["pss_peak_sampled_kb"] - j["pss_baseline_kb"]) / 1024, 1),
                "vm_hwm_mib": round(j["vm_hwm_kb"] / 1024, 1), "lane": j["lane"],
                "extra_forms": forms if mname == "prod+forms" else [],
            })
            res["lanes"][f"{hname}:{mname} (ET probe)"] = lane
    # sherpa-onnx KWS lane (KwsProbe under app_process): TSV of file, verdict, ms, then a #meta line.
    for p in sorted(dev.glob("kws-t*-s*.tsv")):
        name = p.stem
        verdicts, ms, meta = {}, [], {}
        for line in open(p):
            parts = line.rstrip("\n").split("\t")
            if parts[0] == "#meta":
                meta = {k: float(v) for k, v in (x.split("=") for x in parts[1:])}
            elif len(parts) == 3:
                verdicts[parts[0]] = parts[1] == "accept"
                ms.append(float(parts[2]))
        if not meta:
            continue
        lane = summarise(verdicts, lambda k: bool(host_rows[name][k]["text"]))
        lane.update({
            "load_ms": round(meta["load_ms"], 1), "first_call_ms": round(ms[0], 1),
            "verify_p50_ms": pct(ms[1:], 50), "verify_p95_ms": pct(ms[1:], 95), "verify_max_ms": pct(ms[1:], 100),
            "pss_load_delta_mib": round((meta["pss_after_load_kb"] - meta["pss_before_kb"]) / 1024, 1),
            "pss_peak_delta_mib": round((meta["pss_peak_kb"] - meta["pss_before_kb"]) / 1024, 1),
            "vm_hwm_mib": round(meta["vm_hwm_kb"] / 1024, 1),
        })
        res["lanes"][f"{name} (KWS app_process)"] = lane
    Path(a.out).write_text(json.dumps(res, indent=1))
    print(f"wrote {a.out}")


def main() -> None:
    ap = argparse.ArgumentParser()
    sp = ap.add_subparsers(dest="cmd", required=True)
    p = sp.add_parser("prepare")
    p.add_argument("--private", required=True)
    p.add_argument("--work", required=True)
    p.add_argument("--assets", required=True)
    p = sp.add_parser("engine")
    p.add_argument("name")
    p.add_argument("--latency", type=int, default=0, help="timing-only pass over N full-length rings")
    p.add_argument("--private", required=True)
    p.add_argument("--work", required=True)
    p = sp.add_parser("score")
    p.add_argument("--work", required=True)
    p.add_argument("--out", required=True)
    p = sp.add_parser("export")
    p.add_argument("--work", required=True)
    p.add_argument("--out", required=True)
    p = sp.add_parser("device-score")
    p.add_argument("--work", required=True)
    p.add_argument("--rings", required=True)
    p.add_argument("--device", required=True)
    p.add_argument("--host", required=True)
    p.add_argument("--label", required=True)
    p.add_argument("--out", required=True)
    a = ap.parse_args()
    {"prepare": cmd_prepare, "engine": cmd_engine, "score": cmd_score, "export": cmd_export,
     "device-score": cmd_device_score}[a.cmd](a)


if __name__ == "__main__":
    main()
