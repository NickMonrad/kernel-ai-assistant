#!/usr/bin/env python3
"""Rewrite the stored `system` counters in host-aggregates.json to the corrected definitions.

The first scoring pass put an item under `via_verifier` whenever its first accepting frame was low-band,
even when a later frame reached >= HIGH and the detector alone would have accepted it. `wake_eval.py score`
now writes `accepted_without_verifier` (detector-only accepts) and `gain_over_detector_only` (accepted only
because the verifier said yes). Both follow exactly from the stored numbers, because the production loop
accepts every item the detector accepts (a later >= HIGH frame is reached unless a verifier accepts first):

    accepted = detector_only(>= HIGH) + gain   and   accepted >= detector_only(>= HIGH)

This script checks those identities, then rewrites the entries. It reads and writes only the results JSON.
Run once per results file; a file already in the new form is left alone.

usage: rederive_gain.py results/host-aggregates.json
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

HIGH = "0.65"


def main(path: Path) -> None:
    raw = path.read_text(encoding="utf-8")
    d = json.loads(raw)
    if "gain_over_detector_only" in next(iter(d["system"].values()))["genuine"]:
        print("already rederived")
        return
    for label, per_class in d["system"].items():
        for c, r in per_class.items():
            assert r["via_high"] + r["via_verifier"] == r["n"], (label, c, "old counters do not sum to accepted")
            detector = d["detector_only"][c][HIGH]["n"]
            assert r["n"] >= detector, (label, c, "accepted below detector-only: identity fails")
            r["accepted_without_verifier"] = detector
            r["gain_over_detector_only"] = r["n"] - detector
            del r["via_high"], r["via_verifier"]
    # The results files are written with indent=1 and no trailing newline.
    path.write_text(json.dumps(d, indent=1, ensure_ascii=False), encoding="utf-8")
    print(f"rewrote {len(d['system'])} system entries in {path}")


if __name__ == "__main__":
    main(Path(sys.argv[1]))
