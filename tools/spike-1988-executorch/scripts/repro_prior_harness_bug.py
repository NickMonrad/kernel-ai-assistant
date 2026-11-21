"""Minimal reproduction of the earlier #1988 "Android divergence" on host, no device needed.

Runs FUTO's official ExecuTorch driver, imported unmodified at run time from the pinned asr4all-s snapshot,
for exactly the first two `stream_c16r4` steps on a public clip. The logits each stream-method call returns are
observed through a thin proxy (as in `frame_agreement.py`) and decoded two ways:

  reference  : the driver's own frame ids (argmax over all 257 columns, row stride 257 = vocab + blank)
  prior bug  : row stride len(metadata["vocab"]) = 256, argmax over columns 0..255 only (blank unreachable)

Both id sequences are collapsed to text by the driver's own CTC collapse (`Engine._text`); no driver logic is
re-implemented here.

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

warnings.filterwarnings("ignore")
logging.disable(logging.WARNING)

STEPS = 2

bundle = Path(sys.argv[1])
spec = importlib.util.spec_from_file_location("asr4all_executorch", bundle.parent / "asr4all_executorch.py")
ref = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ref)

engine = ref.load_engine(str(bundle))
engine.set_tier("stream_c16r4")
tier = engine.tiers[engine.idx]
vocab, blank = engine.meta["vocab"], engine.meta["blank_id"]
print(f"len(metadata.vocab)={len(vocab)} blank_id={blank}")

recorded = []


class MethodProxy:
    def __init__(self, inner, name):
        self.inner, self.name = inner, name

    def execute(self, inputs):
        outs = self.inner.execute(inputs)
        if self.name.startswith("stream_"):
            recorded.append(outs[0][0].float().numpy().copy())
        return outs


class ProgramProxy:
    def __init__(self, inner):
        self.inner = inner

    def load_method(self, name):
        return MethodProxy(self.inner.load_method(name), name)

    def __getattr__(self, item):
        return getattr(self.inner, item)


engine.model = ProgramProxy(engine.model)

# Enough audio for the second step's window and not the third, so the driver runs exactly STEPS steps.
need = (STEPS - 1) * tier["commit"] * engine.spf + tier["window"]
clip = ref.load_wav(sys.argv[2])[:need]
clip = np.pad(clip, (0, need - len(clip)))
engine.reset()
engine.decode_stream(clip)
assert len(recorded) == STEPS, len(recorded)
reference_ids = list(engine.ids)


def collapse(ids):
    """Text for `ids` through the driver's own CTC collapse."""
    saved, engine.ids = engine.ids, ids
    try:
        return engine._text()
    finally:
        engine.ids = saved


bad = []
for step, logits in enumerate(recorded):  # [commit, 257]
    print(f"step {step}: logits shape {list(logits.shape)}")
    flat, stride = logits.reshape(-1), len(vocab)
    bad += [int(np.argmax(flat[f * stride : f * stride + stride])) for f in range(tier["commit"])]
    print(f"  reference : {collapse(reference_ids[: (step + 1) * tier['commit']])!r}")
    print(f"  prior bug : {collapse(bad)!r}")
