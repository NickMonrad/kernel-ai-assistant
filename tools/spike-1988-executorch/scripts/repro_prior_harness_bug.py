"""Minimal reproduction of the earlier #1988 "Android divergence" on host, no device needed.

Runs the first two `stream_c16r4` steps of FUTO's official ExecuTorch driver (imported unmodified from the
pinned asr4all-s snapshot) on a public clip, then decodes the *same* logits two ways:

  reference  : row stride 257 (= logits.shape[-1], vocab + blank), argmax over all 257 columns
  prior bug  : row stride len(metadata["vocab"]) = 256, argmax over columns 0..255 only (blank unreachable)

The prior Android harness (`ExecuTorchAsr4allDecoder.step`, branches spike/build8-1988-executorch and
test/build8-1988-xnnpack-aar-control) indexed logits with `frame * vocab.size` and `for (token in vocab.indices)`.
Its reported device checkpoints were "s'abcdefghijkigsen" and
"s'abcdefghijkigsen hndard hadosing andfgereuljk has hern"; the second decode below prints exactly those.

usage: python repro_prior_harness_bug.py <.../executorch/xnnpack_int8> <16 kHz mono wav>
"""

import importlib.util
import logging
import sys
import warnings
from pathlib import Path

import numpy as np
import torch

warnings.filterwarnings("ignore")
logging.disable(logging.WARNING)

bundle = Path(sys.argv[1])
spec = importlib.util.spec_from_file_location("asr4all_executorch", bundle.parent / "asr4all_executorch.py")
ref = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ref)

engine = ref.load_engine(str(bundle))
engine.set_tier("stream_c16r4")
tier = engine.tiers[engine.idx]
clip = ref.load_wav(sys.argv[2])
vocab, blank = engine.meta["vocab"], engine.meta["blank_id"]
print(f"len(metadata.vocab)={len(vocab)} blank_id={blank}")


def ctc(ids):
    out, prev = [], -1
    for i in ids:
        if i != prev and i != blank and i < len(vocab):
            out.append(vocab[i])
        prev = i
    return " ".join("".join(out).split())


filt, attn = engine.filter_cache, engine.attn_cache
good, bad = [], []
for step in range(2):
    start = step * tier["commit"] * engine.spf
    win = np.zeros(tier["window"], dtype=np.float32)
    seg = clip[start : start + tier["window"]]
    win[: len(seg)] = seg
    outs = engine.model.load_method(tier["name"]).execute([torch.from_numpy(win)[None, :], filt, attn])
    filt, attn = outs[1].clone(), outs[2].clone()
    logits = outs[0][0].float().numpy()  # [commit, 257]
    print(f"step {step}: logits shape {list(logits.shape)}")
    good += logits.argmax(-1).tolist()
    flat, stride = logits.reshape(-1), len(vocab)
    bad += [int(np.argmax(flat[f * stride : f * stride + stride])) for f in range(tier["commit"])]
    print(f"  reference : {ctc(good)!r}")
    print(f"  prior bug : {ctc(bad)!r}")
