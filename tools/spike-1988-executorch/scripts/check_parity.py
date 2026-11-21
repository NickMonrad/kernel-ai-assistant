"""Compare device parity JSON (ParityTest) with host reference JSON (reference_outputs.py).

usage: python check_parity.py <reference.json> <device.json>   -> exit 1 on any raw/PCEC text mismatch
"""

import json
import sys

ref = {(r["tier"], r["clip"]): r for r in json.load(open(sys.argv[1]))}
dev = json.load(open(sys.argv[2]))
bad = 0
for d in dev:
    r = ref.get((d["tier"], d["clip"]))
    if r is None:
        print(f"MISSING reference for {d['tier']} {d['clip']}")
        bad += 1
        continue
    for field in ("raw", "pcec"):
        ok = r[field] == d[field]
        bad += not ok
        print(f"{'OK  ' if ok else 'DIFF'} {d['variant']:>16} {d['tier']} {d['clip']:>8} {field}"
              + ("" if ok else f"\n   ref: {r[field]}\n   dev: {d[field]}"))
print(f"{len(dev) * 2 - bad}/{len(dev) * 2} fields match")
sys.exit(1 if bad else 0)
