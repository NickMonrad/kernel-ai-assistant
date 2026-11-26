"""Rewrite-or-suggest figure for the frozen β 5 rule on the owner recordings (#2093, information only).

Reads the per-clip outputs of oracle.py (one directory per slot, e.g. owner-full, owner-hint, owner-trigger;
each holds per_clip.<slot>.v2.json) and counts, at β 5 only:
  - gold cell-lines (scored): the rewrite (accept) names the gold place, or a "Did you mean <place>?"
    suggestion names it; wrong-place rewrites and wrong-place suggestions are counted separately.
  - non-gold cell-lines (scored): rewrites (split into identity, i.e. the greedy text already equals the name
    after the ASCII fold, and non-identity) and suggestions.
A cell-line is one clip in one size and one slot. Recall (accept only) is the first number; the rewrite-or-suggest
figure is the second. No threshold was pre-registered for either: they are information only.

These recordings were already used to choose β 5 (amendment 5), so the figure is not a blind result.

Aggregates only: nothing per clip is printed.

  python3 suggest_figure.py --run DIR [--run DIR ...] [--beta 5] [--out JSON]
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import lexicon  # noqa: E402


def load_rows(run_dir: Path) -> list[dict]:
    files = sorted(run_dir.glob("per_clip.*.v2.json"))
    if len(files) != 1:
        raise SystemExit(f"expected one per_clip.*.v2.json in {run_dir}, found {len(files)}")
    return json.loads(files[0].read_text(encoding="utf-8"))


def tally(run_dir: Path, beta_key: str) -> dict:
    slot = run_dir.name.split("-", 1)[-1]  # owner-hint -> hint
    out: dict[str, dict] = {}
    for size in ("M", "L"):
        c = {"gold_cell_lines": 0, "accepted_correct": 0, "rewritten_wrong": 0,
             "suggested_correct": 0, "suggested_wrong": 0, "gold_no_action": 0,
             "nongold_cell_lines": 0, "nongold_rewritten": 0, "nongold_identity": 0, "nongold_suggested": 0}
        for row in load_rows(run_dir):
            if row["size"] != size or not row["scored"] or beta_key not in row:
                continue
            d = row[beta_key]
            if row["gold"]:
                c["gold_cell_lines"] += 1
                right = d["top_name"] in row["gold"]
                if d["accept"]:
                    c["accepted_correct" if right else "rewritten_wrong"] += 1
                elif d["outcome"] == "suggest":
                    c["suggested_correct" if right else "suggested_wrong"] += 1
                else:
                    c["gold_no_action"] += 1
            else:
                c["nongold_cell_lines"] += 1
                if d["accept"]:
                    c["nongold_rewritten"] += 1
                    if lexicon.fold(d["match"]) == lexicon.fold(d["top_name"]):
                        c["nongold_identity"] += 1
                c["nongold_suggested"] += int(d["outcome"] == "suggest")
        out[f"{size}"] = {"slot": slot, **c}
    return out


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--run", action="append", type=Path, required=True, help="oracle run dir (repeatable)")
    ap.add_argument("--beta", default="5", help="β value to read (default 5, the frozen rule)")
    ap.add_argument("--out", type=Path, help="aggregate JSON output (optional)")
    args = ap.parse_args(argv)

    key = f"b{float(args.beta):g}"
    cells: dict[str, dict] = {}
    for run in args.run:
        for size, c in tally(run, key).items():
            cells[f"{c['slot']}-{size}"] = c

    keys = ["gold_cell_lines", "accepted_correct", "rewritten_wrong", "suggested_correct", "suggested_wrong",
            "gold_no_action", "nongold_cell_lines", "nongold_rewritten", "nongold_identity", "nongold_suggested"]
    total = {k: sum(c[k] for c in cells.values()) for k in keys}
    total["nongold_non_identity_rewrites"] = total["nongold_rewritten"] - total["nongold_identity"]
    total["rewrite_or_suggest_correct"] = total["accepted_correct"] + total["suggested_correct"]
    summary = {"beta": float(args.beta), "cells": cells, "total": total, "blind": False}
    out = json.dumps(summary, ensure_ascii=False, indent=1)
    print(out)
    if args.out:
        args.out.write_text(out + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
