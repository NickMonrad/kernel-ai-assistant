"""Paired per-clip comparison of two lanes on the owner set (normalised finals), from per-run-metrics.json.

usage: python paired.py <per-run-metrics.json> <device> "<lane A>" "<lane B>" [set]
Prints summed edits, per-clip better/worse/tied counts and an exact two-sided sign-test p-value (ties dropped).
Uses repeat 1; WER of the in-process engines is identical across repeats.
"""

import json
import math
import sys

runs = json.load(open(sys.argv[1]))
device, a, b = sys.argv[2:5]
set_ = sys.argv[5] if len(sys.argv) > 5 else "owner"


def pick(lane):
    rs = [r for r in runs if r["device"] == device and r["lane"] == lane and r["set"] == set_]
    if not rs:
        sys.exit(f"no runs for {device} / {lane} / {set_}")
    return sorted(rs, key=lambda r: r["repeat"])[0]["per_clip_edits_norm"]


ea, eb = pick(a), pick(b)
better = sum(ea[k] < eb[k] for k in ea)
worse = sum(ea[k] > eb[k] for k in ea)
n = better + worse
k = min(better, worse)
p = min(1.0, 2 * sum(math.comb(n, i) for i in range(k + 1)) / 2 ** n) if n else 1.0
print(f"{a} vs {b} ({device}, {set_}): edits {sum(ea.values())} vs {sum(eb.values())}; "
      f"A better on {better}, worse on {worse}, tied {len(ea) - n}; sign test p = {p:.3g}")
