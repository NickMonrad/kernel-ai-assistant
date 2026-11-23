"""PC reference driver mirroring RNE kokoroTextToSpeech.ts (dynamic XNNPACK variant), ExecuTorch 1.5.1 Python runtime."""
import json, math, re, time, wave
import numpy as np, torch
from executorch.runtime import Runtime

HF = "/home/lokhor/.cache/kokoro-et-1557/hf"
TS = "/home/lokhor/.cache/kokoro-et-1557/rne-src/packages/react-native-executorch/src/extensions/speech/utils/kokoroUtils.ts"
SR, TICKS, VREF, VHALF, DFEAT, MIN_DUR = 24000, 600, 256, 128, 640, 16
MAX_TOK, MAX_DUR = 128, 296  # probed: token axis [2,128]; duration-tick sum [1,296] (error 0x10 beyond)

def _unesc(s): return re.sub(r"\\u([0-9a-fA-F]{4})", lambda m: chr(int(m.group(1), 16)), s)
_src = open(TS, encoding="utf8").read()
_vocab_block = _src[_src.index("const VOCAB"): _src.index("};", _src.index("const VOCAB"))]
VOCAB = {_unesc(k): int(v) for k, v in re.findall(r"'((?:\\u[0-9a-fA-F]{4})|[^'\\])'\s*:\s*(\d+)", _vocab_block)}
_pause_block = _src[_src.index("KOKORO_PAUSE_MS"): ]
_pause_block = _pause_block[: _pause_block.index("};")]
PAUSE_MS = {_unesc(k): int(v) for k, v in re.findall(r"'((?:\\u[0-9a-fA-F]{4})|[^'\\])'\s*:\s*(\d+)", _pause_block)}
LETTER = re.compile(r"\p{L}") if False else None

rt = Runtime.get()
DUR = rt.load_program(f"{HF}/xnnpack/standard/duration_predictor_std_xnnpack_fp32.pte").load_method("forward")
SYN = rt.load_program(f"{HF}/xnnpack/standard/synthesizer_std_xnnpack_fp32.pte").load_method("forward")

def load_voice(name):
    raw = np.fromfile(f"{HF}/voices/{name}.bin", dtype="<f4")
    return raw.reshape(-1, VREF)

def is_letter(ch):
    return ch.isalpha()

def synth_chunk(phonemes: str, voice: np.ndarray, speed: float = 1.0):
    ph = list(phonemes.strip())
    n = min(max(len(ph) + 2, 2), MAX_TOK)
    ids = [0] * n
    idx_map = [-1] * n
    nxt = 1
    for i in range(min(n - 2, len(ph))):
        t = VOCAB.get(ph[i])
        if t is None: continue
        idx_map[nxt] = i; ids[nxt] = t; nxt += 1
    tokens = torch.tensor([ids], dtype=torch.int64)
    mask = torch.zeros((1, n), dtype=torch.bool)
    mask[0, : min(len(ph) + 2, n)] = True
    rows = voice.shape[0]
    vrow = max(0, min(len(ph) - 1, n - 1, rows - 1))
    off = vrow * VREF
    vref = torch.from_numpy(voice.reshape(-1)[off: off + VREF].copy()).reshape(1, VREF)
    vhalf = vref[:, VHALF:].contiguous()
    vref_h = vref[:, :VHALF].contiguous()
    spd = torch.tensor([speed], dtype=torch.float32)
    t0 = time.perf_counter()
    pred, feats = DUR.execute([tokens, mask, vhalf, spd])
    t_dur = time.perf_counter() - t0
    durs = pred.to(torch.int64).numpy().astype(np.int64)
    total = int(durs.sum())
    clamped = min(max(total, MIN_DUR), MAX_DUR)
    if clamped != total and total > 0:
        durs = scale_durations(durs, clamped)
    if durs.sum() == 0:
        return np.zeros(0, np.float32), 0.0, t_dur, 0.0
    indices = torch.from_numpy(np.repeat(np.arange(n, dtype=np.int64), durs))
    t1 = time.perf_counter()
    (audio,) = SYN.execute([tokens, mask, indices, feats, vref_h if False else vref])
    t_syn = time.perf_counter() - t1
    audio = audio.reshape(-1).numpy()
    # post-processing (mirrors TS steps 4)
    pad = n
    for i in range(1, n):
        if ids[i] == 0: pad = i; break
    eff = int(durs[: min(pad, n - 1) + 1].sum())
    audio = audio[: min(eff * TICKS, len(audio))]
    last = ph[-1] if ph else ""
    if n > 2:
        last_idx = n - 2 if is_letter(last) else n - 3
        last_ts = int(durs[: last_idx + 1].sum())
        audio = audio[: min(last_ts * TICKS, len(audio))]
    stripped_head = 0
    unstripped = audio
    audio = strip_audio(audio, 50 * SR // 1000)
    pause = PAUSE_MS.get(last, 0) * SR // 1000
    out = np.concatenate([audio, np.zeros(pause, np.float32)]).astype(np.float32)
    return out, len(out) / SR, t_dur, t_syn

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
    if L == 0: return audio
    norm = np.maximum(0.0, np.abs(audio.astype(np.float64)) - threshold)
    cs = np.concatenate([[0.0], np.cumsum(norm)])
    fwd = L - 1
    for p in range(steps, L + 1):           # window [p-steps, p-1]
        if (cs[p] - cs[p - steps]) / steps >= threshold:
            fwd = p - 1; break
    rev = 0
    for p in range(steps, L + 1):           # window [L-p, L-p+steps-1]
        lo = L - p
        if (cs[lo + steps] - cs[lo]) / steps >= threshold:
            rev = lo; break
    s = max(0, fwd - margin)
    e = min(L - 1, rev + margin)
    return audio[s: e + 1] if e >= s else audio[:0]

def synth(phonemes: str, voice_name="af_heart", speed=1.0):
    return synth_chunk(phonemes, load_voice(voice_name), speed)

def write_wav(path, audio):
    with wave.open(path, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((np.clip(audio, -1, 1) * 32767).astype("<i2").tobytes())
