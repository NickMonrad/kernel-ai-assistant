"""Host oracle for #1988: run FUTO's official driver, imported unmodified from the pinned Hugging Face
snapshot, over public clips and print raw + PCEC text per tier as JSON. For an ExecuTorch bundle
(`executorch/xnnpack_int8`) the driver is `executorch/asr4all_executorch.py`; for an ONNX release
directory containing `asr4all_onnx.py`, that script is used instead.

usage: python reference_outputs.py <bundle_dir> <tiers,comma> <wav>...
"""

import importlib.util
import json
import logging
import sys
import warnings
from pathlib import Path

warnings.filterwarnings("ignore")
logging.disable(logging.WARNING)

bundle = Path(sys.argv[1])
driver = bundle / "asr4all_onnx.py"
if not driver.exists():
    driver = bundle.parent / "asr4all_executorch.py"
spec = importlib.util.spec_from_file_location("asr4all_driver", driver)
ref = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ref)

engine = ref.load_engine(str(bundle))
rows = []
for tier in sys.argv[2].split(","):
    engine.set_tier(tier)
    for wav in sys.argv[3:]:
        clip = ref.load_wav(wav)
        raw = engine.decode_offline(clip, apply_pcec=False)
        pcec = engine.decode_offline(clip, apply_pcec=True)
        rows.append({"tier": tier, "clip": Path(wav).name, "raw": raw, "pcec": pcec})
print(json.dumps(rows, indent=1))
