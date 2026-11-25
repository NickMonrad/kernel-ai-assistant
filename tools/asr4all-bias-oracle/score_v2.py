"""#2093 pre-registration v2 scoring: identity-aware rewrites, output-text correctness, pass criteria.

Input: the oracle's per_clip.<slot>.v2.json (run with the v2 code: each beta row carries "match") and a private
class map JSON {"<clip>": "<class>"} whose gold classes start with "gold_". Output: aggregates only.

Definitions (beta is fixed per run; primary beta 5.0). "match" is the folded heard place text the rewrite would
replace: the matched frames of the top candidate, or the exact slot on an exact hit.
  - rewrite: outcome "accept" and the folded top name differs from "match".
  - identity: outcome "accept" and the folded top name equals "match" (ignoring case and macrons). Not a rewrite.
  - output place text: the top name if accept, otherwise "match".
  - gold correct: folded output place text equals the folded gold name.
  - gold substitution: a rewrite whose folded top name is not the folded gold name.
"""
from __future__ import annotations

import argparse
import json
import math
import sys
from collections import defaultdict
from pathlib import Path

import lexicon


def wilson(k: int, n: int, z: float = 1.959964) -> tuple[float, float]:
    if n == 0:
        return 0.0, 1.0
    p = k / n
    d = 1 + z * z / n
    c = (p + z * z / (2 * n)) / d
    h = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / d
    return max(0.0, c - h), min(1.0, c + h)


def fmt(k: int, n: int) -> str:
    lo, hi = wilson(k, n)
    return f"{k}/{n} ({100 * lo:.1f}-{100 * hi:.1f})"


def decide(row: dict, beta: str) -> dict:
    d = row[beta]
    match = d["match"]
    top = lexicon.fold(d["top_name"])
    accept = d["outcome"] == "accept"
    rewrite = accept and top != match
    out = top if accept else match
    gold = lexicon.fold(row["gold"][0]) if row["gold"] else ""
    return {"outcome": d["outcome"], "rewrite": rewrite, "identity": accept and not rewrite,
            "correct": bool(gold) and out == gold, "gold_sub": rewrite and top != gold}


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--per-clip", type=Path, required=True)
    ap.add_argument("--classes", type=Path, required=True, help="private JSON {clip: class}")
    ap.add_argument("--beta", default="b5", help="b3, b4 or b5 (primary b5)")
    args = ap.parse_args(argv)
    rows = json.loads(args.per_clip.read_text(encoding="utf-8"))
    classes = json.loads(args.classes.read_text(encoding="utf-8"))
    groups: dict[tuple[str, str], list[dict]] = defaultdict(list)
    for r in rows:
        if r["clip"] not in classes or args.beta not in r or "match" not in r[args.beta]:
            continue
        groups[(r["size"], classes[r["clip"]])].append(decide(r, args.beta))
    for (size, cls), items in sorted(groups.items()):
        n = len(items)
        if cls.startswith("gold_"):
            print(f"size {size} {cls}: n={n} correct-output {fmt(sum(i['correct'] for i in items), n)} "
                  f"substitution {fmt(sum(i['gold_sub'] for i in items), n)} "
                  f"suggest {sum(i['outcome'] == 'suggest' for i in items)} "
                  f"abstain {sum(i['outcome'] == 'abstain_competitor' for i in items)} "
                  f"veto {sum(i['outcome'] == 'veto' for i in items)} identity {sum(i['identity'] for i in items)}")
        else:
            print(f"size {size} {cls}: n={n} rewrites {fmt(sum(i['rewrite'] for i in items), n)} "
                  f"identity {sum(i['identity'] for i in items)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
