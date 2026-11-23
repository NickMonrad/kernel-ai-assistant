"""Shipped software-mansion .pte: padded-to-128 (valid-prefix bool mask) vs exact-length all-ones bool mask.

Comparison is on real tokens only (first L = phonemes + 2): durations for those tokens, and audio truncated
to the real-token duration in ticks (RNE cuts at the effective duration). Pad-position durations are excluded.
"""
import numpy as np, torch
import pc_kokoro as ref

PHRASES = ["həlˈoʊ wˈɜːld.", "ðɪs ɪz ɐ tˈɛst ʌv ðə spˈiːtʃ sˈɪstəm, wɪð ɐ pˈɔz.", "wˈʌn?"]
TICKS = 600


def encode(ph_str, n, voice):
    ph = list(ph_str.strip())
    ids = [0] * n
    nxt = 1
    for i in range(min(n - 2, len(ph))):
        t = ref.VOCAB.get(ph[i])
        if t is None:
            continue
        ids[nxt] = t
        nxt += 1
    valid = min(len(ph) + 2, n)
    vrow = max(0, min(len(ph) - 1, n - 1, voice.shape[0] - 1))
    vref = torch.from_numpy(voice.reshape(-1)[vrow * ref.VREF:(vrow + 1) * ref.VREF].copy()).reshape(1, ref.VREF)
    return torch.tensor([ids], dtype=torch.int64), valid, vref


def run(ph_str, voice, n, exact):
    tokens, L, vref = encode(ph_str, n, voice)
    if exact:
        tokens = tokens[:, :L]
        mask = torch.ones((1, L), dtype=torch.bool)
    else:
        mask = torch.zeros((1, n), dtype=torch.bool)
        mask[0, :L] = True
    vhalf = vref[:, ref.VHALF:].contiguous()
    spd = torch.tensor([1.0], dtype=torch.float32)
    pred, feats = ref.DUR.execute([tokens, mask, vhalf, spd])
    real = pred.to(torch.int64).numpy().astype(np.int64)[:L].copy()
    total = int(real.sum())
    clamped = min(max(total, ref.MIN_DUR), ref.MAX_DUR)
    if clamped != total and total > 0:
        real = ref.scale_durations(real, clamped)
    idx = np.repeat(np.arange(L, dtype=np.int64), real)
    (audio,) = ref.SYN.execute([tokens, mask, torch.from_numpy(idx), feats, vref])
    a = audio.reshape(-1).numpy()
    return real, a[: int(real.sum()) * TICKS], L


def main():
    voice = ref.load_voice("af_heart")
    for p in PHRASES:
        d_pad, a_pad, L = run(p, voice, 128, exact=False)
        d_ex, a_ex, _ = run(p, voice, 128, exact=True)
        dur_eq = np.array_equal(d_pad, d_ex)
        same_len = a_pad.shape == a_ex.shape
        diff = float(np.abs(a_pad - a_ex).max()) if same_len else float("nan")
        print(f"[{p[:24]!r}] L={L} durations_equal={dur_eq} sum={int(d_ex.sum())} "
              f"audio_len_pad={a_pad.shape[0]} audio_len_exact={a_ex.shape[0]} audio_max_abs={diff:.3e}")


if __name__ == "__main__":
    main()
