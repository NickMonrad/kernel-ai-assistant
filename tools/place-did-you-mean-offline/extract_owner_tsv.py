#!/usr/bin/env python3
"""Build the private tuning TSV for the #2079 offline "Did you mean" sweep.

Inputs (all private, never committed):
  --labels      owner-labels.json   {"R01": {"gold": [...]}, ...}
  --classes     owner-class-map.json {"R01": "gold_navigate", ...}
  --dump-dir    directory with R##.{M,L}.stream_c16r4.json (field "final")

Output: TSV with columns id, size, class, gold, greedy_text (one row per clip).
Keep the output in a private directory; do not commit it.
"""
import argparse
import json
from pathlib import Path


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--labels", type=Path, required=True)
    p.add_argument("--classes", type=Path, required=True)
    p.add_argument("--dump-dir", type=Path, required=True)
    p.add_argument("--out", type=Path, required=True)
    args = p.parse_args()

    labels = json.loads(args.labels.read_text())
    classes = json.loads(args.classes.read_text())
    rows = []
    for rid in sorted(labels):
        gold = "|".join(labels[rid].get("gold", []))
        for size in ("M", "L"):
            dump = args.dump_dir / f"{rid}.{size}.stream_c16r4.json"
            text = json.loads(dump.read_text()).get("final", "")
            rows.append(f"{rid}\t{size}\t{classes.get(rid, 'unknown')}\t{gold}\t{text}")
    args.out.write_text("\n".join(rows) + "\n")


if __name__ == "__main__":
    main()
