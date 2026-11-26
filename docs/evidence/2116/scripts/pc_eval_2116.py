"""PC synthesis and metrics for #2116: shipped fp32 vs reproduced fp32 vs XNNPACK int8 Kokoro .pte pairs.

Same chunk logic as docs/evidence/1557-executorch/scripts/pc_kokoro.py (mirrors RNE kokoroTextToSpeech.ts), but the
model pair is an argument instead of a hard-coded path, and nothing is loaded at import time.

Subcommands:
  synth    --models NAME=DIR[,DIR] ... --voices V ... --out OUT   render every chunk; writes WAV + .npy + synth.json
  metrics  --out OUT                                              SNR/length/duration agreement vs the reference model
Phonemes come from the phonemis runner built for #1557 (run from ~/.cache/kokoro-et-1557, the same cwd as listen_set.py).
"""
import argparse
import json
import re
import subprocess
import time
import wave
from pathlib import Path

import numpy as np
import torch
from executorch.runtime import Runtime

CACHE = Path.home() / ".cache" / "kokoro-et-1557"
TS = CACHE / "rne-src/packages/react-native-executorch/src/extensions/speech/utils/kokoroUtils.ts"
PHONEMIS_CWD = CACHE
SR, TICKS, VREF, VHALF, MIN_DUR = 24000, 600, 256, 128, 16
MAX_TOK, MAX_DUR = 128, 296
REFERENCE = "shipped"

# Listening/eval text: the six sentences of the Qwen3 ICL owner listening set (listening-icl/render_listening.py).
# Digits are spelled out: the lane has no digit normalisation (known gap in #1557), so digits would test the gap,
# not the model. Disclosed in docs/evidence/2116/README.md.
SENTENCES = [
    "Sure, the kitchen lights are off now.",
    "Aroha is meeting Mere at the Rotorua library, and then they will walk down to Lake Tarawera.",
    "It is eighteen degrees with a thirty percent chance of rain, and the total comes to forty-two dollars and fifty cents.",
    "The clinic appointment is on the third of March at quarter past nine in the morning, about twenty kilometres from Wellington Station.",
    "Wellington is windy today. Take a jacket, because it may feel colder near the coast. Tomorrow should be clearer.",
    "The bus to Lower Hutt leaves from platform four every fifteen minutes during the morning peak. "
    "The last weekday service departs at a quarter past eleven at night. "
    "If you miss it, a taxi from the station costs about thirty dollars.",
]

SHIPPED_DIR = CACHE / "hf/xnnpack/standard"
VOICE_DIR = CACHE / "hf/voices"


def _unesc(s):
    return re.sub(r"\\u([0-9a-fA-F]{4})", lambda m: chr(int(m.group(1), 16)), s)


def _load_tables():
    src = TS.read_text(encoding="utf8")
    block = src[src.index("const VOCAB"): src.index("};", src.index("const VOCAB"))]
    vocab = {_unesc(k): int(v) for k, v in re.findall(r"'((?:\\u[0-9a-fA-F]{4})|[^'\\])'\s*:\s*(\d+)", block)}
    pb = src[src.index("KOKORO_PAUSE_MS"):]
    pb = pb[: pb.index("};")]
    pause = {_unesc(k): int(v) for k, v in re.findall(r"'((?:\\u[0-9a-fA-F]{4})|[^'\\])'\s*:\s*(\d+)", pb)}
    return vocab, pause


VOCAB, PAUSE_MS = _load_tables()


def phonemise(text: str, lang: str = "en-us") -> str:
    base = CACHE / "hf/phonemizer" / lang
    out = subprocess.run(
        [str(CACHE / "phonemis-src/build/phonemis_runner"), "--lang", lang, "--lexicon", str(base / "lexicon.json"),
         "--tagger", str(base / "tags.json"), text],
        capture_output=True, text=True, cwd=PHONEMIS_CWD, check=True,
    ).stdout
    out = re.sub(r"\x1b\[[0-9;]*m", "", out)
    return re.search(r"Output: (.*)", out).group(1).strip()


def chunk_text(text: str, limit: int = 110) -> list[str]:
    """Sentence chunks; a sentence over `limit` characters is split at the comma nearest its middle."""
    out = []
    for sent in re.split(r"(?<=[.?!])\s+", text.strip()):
        if len(sent) <= limit or "," not in sent:
            out.append(sent)
            continue
        mid = len(sent) // 2
        commas = [m.start() for m in re.finditer(",", sent)]
        cut = min(commas, key=lambda c: abs(c - mid))
        out.append(sent[: cut + 1])
        out.append(sent[cut + 1:].strip())
    return out


def load_voice(name: str) -> np.ndarray:
    return np.fromfile(VOICE_DIR / f"{name}.bin", dtype="<f4").reshape(-1, VREF)


class Pair:
    def __init__(self, dp_path: Path, syn_path: Path, pad_to: int | None = None):
        rt = Runtime.get()
        self.dp = rt.load_program(str(dp_path)).load_method("forward")
        self.syn = rt.load_program(str(syn_path)).load_method("forward")
        self.pad_to = pad_to  # fixed-token-length exports (--static-tokens) take inputs padded to this length


def is_letter(ch):
    return ch.isalpha()


def scale_durations(durs, target):
    total = durs.sum()
    scale = target / total
    shrinking = scale < 1
    scaled = scale * durs
    rounded = np.ceil(scaled) if shrinking else np.floor(scaled)
    rounded = rounded.astype(np.int64)
    diff = abs(target - int(rounded.sum()))
    rem = np.abs(rounded - scaled)
    order = np.argsort(-rem, kind="stable")
    for k in range(min(diff, len(order))):
        rounded[order[k]] += -1 if shrinking else 1
    return rounded


def strip_audio(audio, margin, steps=10, threshold=0.005):
    L = len(audio)
    if L == 0:
        return audio
    norm = np.maximum(0.0, np.abs(audio.astype(np.float64)) - threshold)
    cs = np.concatenate([[0.0], np.cumsum(norm)])
    fwd = L - 1
    for p in range(steps, L + 1):
        if (cs[p] - cs[p - steps]) / steps >= threshold:
            fwd = p - 1
            break
    rev = 0
    for p in range(steps, L + 1):
        lo = L - p
        if (cs[lo + steps] - cs[lo]) / steps >= threshold:
            rev = lo
            break
    s = max(0, fwd - margin)
    e = min(L - 1, rev + margin)
    return audio[s: e + 1] if e >= s else audio[:0]


def synth_chunk(pair: Pair, phonemes: str, voice: np.ndarray, speed: float = 1.0):
    """Returns (audio incl. trailing pause, durations sum, synth seconds for duration+synth)."""
    ph = list(phonemes.strip())
    n = min(max(len(ph) + 2, 2), MAX_TOK)
    ids = [0] * n
    nxt = 1
    for i in range(min(n - 2, len(ph))):
        t = VOCAB.get(ph[i])
        if t is None:
            continue
        ids[nxt] = t
        nxt += 1
    L = pair.pad_to or n
    tokens = torch.tensor([ids + [0] * (L - n)], dtype=torch.int64)
    mask = torch.zeros((1, L), dtype=torch.bool)
    mask[0, : min(len(ph) + 2, n)] = True
    rows = voice.shape[0]
    vrow = max(0, min(len(ph) - 1, n - 1, rows - 1))
    off = vrow * VREF
    vref = torch.from_numpy(voice.reshape(-1)[off: off + VREF].copy()).reshape(1, VREF)
    vhalf = vref[:, VHALF:].contiguous()
    spd = torch.tensor([speed], dtype=torch.float32)
    t0 = time.perf_counter()
    pred, feats = pair.dp.execute([tokens, mask, vhalf, spd])
    durs = pred.to(torch.int64).numpy().astype(np.int64).reshape(-1)[:n]
    total = int(durs.sum())
    clamped = min(max(total, MIN_DUR), MAX_DUR)
    if clamped != total and total > 0:
        durs = scale_durations(durs, clamped)
    if durs.sum() == 0:
        return np.zeros(0, np.float32), 0, time.perf_counter() - t0
    indices = torch.from_numpy(np.repeat(np.arange(n, dtype=np.int64), durs))
    (audio,) = pair.syn.execute([tokens, mask, indices, feats, vref])
    elapsed = time.perf_counter() - t0
    audio = audio.reshape(-1).numpy()
    pad = n
    for i in range(1, n):
        if ids[i] == 0:
            pad = i
            break
    eff = int(durs[: min(pad, n - 1) + 1].sum())
    audio = audio[: min(eff * TICKS, len(audio))]
    last = ph[-1] if ph else ""
    if n > 2:
        last_idx = n - 2 if is_letter(last) else n - 3
        last_ts = int(durs[: last_idx + 1].sum())
        audio = audio[: min(last_ts * TICKS, len(audio))]
    audio = strip_audio(audio, 50 * SR // 1000)
    pause = PAUSE_MS.get(last, 0) * SR // 1000
    out = np.concatenate([audio, np.zeros(pause, np.float32)]).astype(np.float32)
    return out, total, elapsed


def write_wav(path: Path, audio: np.ndarray):
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes((np.clip(audio, -1, 1) * 32767).astype("<i2").tobytes())


def sentence_chunks():
    """[(sentence_index, chunk_index, text)] for the listening/eval set."""
    rows = []
    for si, sent in enumerate(SENTENCES, start=1):
        for ci, c in enumerate(chunk_text(sent), start=1):
            rows.append((si, ci, c))
    return rows


def cmd_synth(args):
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    chunks = sentence_chunks()
    phon = {}
    for si, ci, text in chunks:
        phon[f"{si}-{ci}"] = phonemise(text)
    (out / "phonemes.json").write_text(json.dumps({"chunks": [
        {"sentence": si, "chunk": ci, "text": t, "phonemes": phon[f"{si}-{ci}"]} for si, ci, t in chunks]},
        ensure_ascii=False, indent=2), encoding="utf-8")
    results = {}
    for spec in args.models:
        name, paths = spec.split("=", 1)
        pad_to = None
        if "@" in paths:
            paths, pad = paths.rsplit("@", 1)
            pad_to = int(pad)
        dp_path, syn_path = [Path(p) for p in paths.split(",")] if "," in paths else (
            Path(paths) / "duration_predictor_std_xnnpack_fp32.pte", Path(paths) / "synthesizer_std_xnnpack_fp32.pte")
        pair = Pair(dp_path, syn_path, pad_to)
        for voice_name in args.voices:
            voice = load_voice(voice_name)
            for si, ci, text in chunks:
                audio, dur_total, secs = synth_chunk(pair, phon[f"{si}-{ci}"], voice)
                key = f"{name}/{voice_name}/s{si:02d}-c{ci:02d}"
                np.save(out / f"{key}.npy", audio)
                write_wav(out / f"{key}.wav", audio)
                results[key] = {"audio_s": round(len(audio) / SR, 3), "dur_ticks": dur_total, "synth_s": round(secs, 4)}
                print(key, results[key], flush=True)
        del pair
    (out / "synth.json").write_text(json.dumps(results, indent=1))


def _snr(ref: np.ndarray, test: np.ndarray):
    n = min(len(ref), len(test))
    if n == 0:
        return float("nan")
    r, t = ref[:n].astype(np.float64), test[:n].astype(np.float64)
    err = np.sum((r - t) ** 2)
    if err == 0:
        return float("inf")
    return float(10 * np.log10(np.sum(r ** 2) / err))


def cmd_metrics(args):
    out = Path(args.out)
    meta = json.loads((out / "synth.json").read_text())
    names = sorted({k.split("/")[0] for k in meta})
    voices = sorted({k.split("/")[1] for k in meta})
    ref = args.reference
    rows = []
    for voice in voices:
        for key in sorted(k for k in meta if k.startswith(f"{ref}/{voice}/")):
            suffix = key.split("/", 2)[2]
            a_ref = np.load(out / f"{key}.npy")
            for name in names:
                if name == ref:
                    continue
                k2 = f"{name}/{voice}/{suffix}"
                if k2 not in meta:
                    continue
                a = np.load(out / f"{k2}.npy")
                rows.append({
                    "voice": voice, "chunk": suffix, "vs": name, "ref": ref,
                    "snr_db": _snr(a_ref, a),
                    "len_ratio": (len(a) / len(a_ref)) if len(a_ref) else float("nan"),
                    "dur_equal": meta[key]["dur_ticks"] == meta[k2]["dur_ticks"],
                    "audio_s_ref": meta[key]["audio_s"], "audio_s_test": meta[k2]["audio_s"],
                })
    (out / "metrics.json").write_text(json.dumps(rows, indent=1))
    for vs in sorted({r["vs"] for r in rows}):
        sel = [r for r in rows if r["vs"] == vs]
        finite = [r["snr_db"] for r in sel if np.isfinite(r["snr_db"])]
        print(f"{vs} vs {ref}: chunks={len(sel)} SNR median={np.median(finite):.2f} min={np.min(finite):.2f} "
              f"dur_equal={sum(r['dur_equal'] for r in sel)}/{len(sel)}")


def main():
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("synth")
    s.add_argument("--models", nargs="+", required=True, help="NAME=DIR (uses fp32 file names) or NAME=DP.pte,SYN.pte")
    s.add_argument("--voices", nargs="+", required=True)
    s.add_argument("--out", required=True)
    s.set_defaults(fn=cmd_synth)
    m = sub.add_parser("metrics")
    m.add_argument("--out", required=True)
    m.add_argument("--reference", default=REFERENCE)
    m.set_defaults(fn=cmd_metrics)
    args = p.parse_args()
    args.fn(args)


if __name__ == "__main__":
    main()
