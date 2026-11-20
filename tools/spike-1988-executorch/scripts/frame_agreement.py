"""Frame-level parity between the device decoder (ParityTest JSON) and FUTO's official driver on host.

The official engine is imported unmodified; its stream-method calls are observed through a thin proxy
(no reference logic is re-implemented). For every committed CTC frame we compare argmax ids and report
the host top-1/top-2 log-prob margin at each disagreement: differences confined to near-ties are
kernel-numerics noise, not harness logic.

usage: python frame_agreement.py <.../executorch/xnnpack_int8> <device parity json> <public wav dir>
"""

import importlib.util
import json
import logging
import sys
import warnings
from pathlib import Path

import numpy as np

warnings.filterwarnings("ignore")
logging.disable(logging.WARNING)

bundle = Path(sys.argv[1])
spec = importlib.util.spec_from_file_location("asr4all_executorch", bundle.parent / "asr4all_executorch.py")
ref = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ref)
engine = ref.load_engine(str(bundle))
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
total = agree = 0
worst = 0.0
for row in json.load(open(sys.argv[2])):
    engine.set_tier(row["tier"])
    recorded.clear()
    clip = ref.load_wav(str(Path(sys.argv[3]) / row["clip"]))
    engine.decode_offline(clip, apply_pcec=True)
    host_ids = list(engine.ids)
    margins = []
    for lp in recorded:
        top2 = np.sort(lp, axis=-1)[:, -2:]
        margins += (top2[:, 1] - top2[:, 0]).tolist()
    margins = margins[: len(host_ids)]
    dev_ids = row["frame_ids"]
    assert len(dev_ids) == len(host_ids), (row["tier"], row["clip"], len(dev_ids), len(host_ids))
    diff = [i for i, (a, b) in enumerate(zip(dev_ids, host_ids)) if a != b]
    total += len(host_ids)
    agree += len(host_ids) - len(diff)
    dm = [margins[i] for i in diff]
    dev_m = [row["frame_margins"][i] for i in diff]
    worst = max([worst] + [min(a, b) for a, b in zip(dm, dev_m)])
    print(f"{row['tier']} {row['clip']:>8}: {len(host_ids)} frames, {len(diff)} differ; "
          f"host margins at diffs {np.round(dm, 3).tolist()} device margins {np.round(dev_m, 3).tolist()}")
print(f"TOTAL {agree}/{total} frames agree ({100 * agree / total:.2f}%); "
      f"largest min(host, device) top-2 margin at a disagreeing frame: {worst:.3f}")
