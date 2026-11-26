#!/usr/bin/env python3
"""#2093 pre-registration v4: suggest-first decision, scored from the frozen oracle's beta-5 rows.

No audio and no decoding. Input: the oracle's per_clip.<slot>.<gate>.json (oracle.py, frozen sha256 in
HELDOUT-PREREG-v4.md). Output per clip: PROMPT(name) | SILENT(name) | NONE. Aggregates only.

Rule S4 (suggest-first; never a silent place change):
  - top entry must be a rewritable (NZ) place. Competitor, impostor, veto, below_threshold -> NONE.
  - the oracle's margin must pass (reason "margin" -> NONE).
  - the matched span must pass the word-boundary check (reason "boundary" -> NONE). The word-count check is
    NOT applied to prompts (reason "word_count" still prompts).
  - if the heard text already is the top place (identity, folded), the output is the canonical spelling:
    SILENT. This changes macrons and capitals only, never the place.
  - otherwise PROMPT(top): "Did you mean <top>?" The text is not changed unless the user confirms.
"""
from __future__ import annotations

import argparse
import json
import sys
from collections import defaultdict
from pathlib import Path

import lexicon
import score_v2

BETA = "b5"


def decide(d: dict) -> dict:
    """One oracle decision row (the beta block of a per_clip row) -> S4 outcome."""
    outcome, reason = d["outcome"], d.get("reason")
    if outcome == "veto":
        return {"kind": "none", "name": None, "reason": "veto"}
    if d["top_kind"] != "nz":
        return {"kind": "none", "name": None, "reason": f"top_{d['top_kind']}"}
    if not (outcome == "accept" or (outcome == "suggest" and reason == "word_count")):
        return {"kind": "none", "name": None, "reason": reason or outcome}  # margin/boundary/threshold/competitor
    name = d["top_name"]
    if lexicon.fold(name) == d["match"]:
        return {"kind": "silent", "name": name, "reason": "identity"}
    return {"kind": "prompt", "name": name, "reason": reason or "accept"}


def _cell() -> dict:
    return {"n": 0, "right_prompt": 0, "wrong_prompt": 0, "silent_right": 0, "silent_wrong": 0,
            "none": 0, "false_prompt": 0}


def score(rows: list[dict], classes: dict[str, str], beta: str = BETA) -> dict:
    """Aggregates per (size, class). Gold classes start with gold_; everything else is non-gold."""
    out: dict[tuple[str, str], dict] = defaultdict(_cell)
    for r in rows:
        cls = classes.get(r["clip"])
        if cls is None or beta not in r or "match" not in r[beta]:
            continue
        dec = decide(r[beta])
        gold = lexicon.fold(r["gold"][0]) if r["gold"] else ""
        cell = out[(r["size"], cls)]
        cell["n"] += 1
        if cls.startswith("gold_"):
            if dec["kind"] == "prompt":
                cell["right_prompt" if lexicon.fold(dec["name"]) == gold else "wrong_prompt"] += 1
            elif dec["kind"] == "silent":
                cell["silent_right" if lexicon.fold(dec["name"]) == gold else "silent_wrong"] += 1
            else:
                cell["none"] += 1
        else:
            cell["false_prompt"] += dec["kind"] == "prompt"
    return {f"{size} {cls}": dict(c) for (size, cls), c in sorted(out.items())}


def _fmt(k: int, n: int) -> str:
    return score_v2.fmt(k, n)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--per-clip", type=Path, required=True, action="append",
                    help="oracle per_clip JSON (repeat for several sets; rows are pooled)")
    ap.add_argument("--classes", type=Path, required=True, help="private JSON {clip: class}")
    ap.add_argument("--beta", default=BETA)
    args = ap.parse_args(argv)
    rows: list[dict] = []
    for p in args.per_clip:
        rows += json.loads(p.read_text(encoding="utf-8"))
    classes = json.loads(args.classes.read_text(encoding="utf-8"))
    for key, c in score(rows, classes, args.beta).items():
        n = c["n"]
        if key.split(" ", 1)[1].startswith("gold_"):
            offered = c["right_prompt"] + c["silent_right"]
            print(f"{key}: n={n} offered-right {_fmt(offered, n)} wrong-prompt {_fmt(c['wrong_prompt'], n)} "
                  f"silent-wrong {c['silent_wrong']} none {c['none']}")
        else:
            print(f"{key}: n={n} false-prompt {_fmt(c['false_prompt'], n)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
