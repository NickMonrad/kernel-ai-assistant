#!/usr/bin/env python3
"""Pre-registered tuning sweep for #2093 amendment 5 (tuning set only; never the owner real set).

Each clip is decoded once per size and slot (oracle.py), then every config in the fixed grid is applied to the
cached score rows. Config = (beta, margin, boundary, competitor_margin). The grid, the objective and the tie-break
are those of HELDOUT-PREREG-v3-amendment-5.md; they are constants here so the sweep cannot drift from the record.

Per config, over all sizes, slots and clips (clean + noisy, after the intelligibility exclusion):
  - gold substitutions: an accept whose folded top name is not the gold name (must be 0);
  - false-boost rewrites: non-identity accepts on non-gold clips (must be 0);
  - gold-correct accepts: the objective (maximise, among feasible configs).
Tie-break (smallest change from the current rule): fewest changed parameters, then |beta - 5|, then |margin - 1.0|,
then boundary on before off, then competitor counts before ignored.

Input: dump directory, the size models, private label and class maps. Output: aggregates JSON (no clip ids) and a
private per-clip directory (clip ids; never committed).
"""
from __future__ import annotations

import argparse
import itertools
import json
import sys
from collections import Counter, defaultdict
from pathlib import Path

import oracle
import score_v2

BETAS = (3.0, 4.0, 5.0, 6.0, 7.0)
MARGINS = (0.5, 1.0, 1.5, 2.0)
BOUNDARY = (True, False)
COMP_COUNTS = (True, False)
CURRENT = (5.0, 1.0, True, True)
SLOTS = ("full", "hint", "trigger")
GATE = "v2"


def grid() -> list[tuple[float, float, bool, bool]]:
    return list(itertools.product(BETAS, MARGINS, BOUNDARY, COMP_COUNTS))


def distance(cfg: tuple) -> int:
    return sum(a != b for a, b in zip(cfg, CURRENT))


def tie_key(cfg: tuple) -> tuple:
    beta, margin, boundary, comp = cfg
    return (distance(cfg), abs(beta - 5.0), abs(margin - 1.0), not boundary, not comp)


def choose(stats: dict) -> tuple[tuple, bool]:
    """stats: cfg -> totals dict. Returns (chosen config, feasible-found). Current rule when none is feasible."""
    feasible = [c for c, s in stats.items() if s["gold_sub"] == 0 and s["fa_rewrite"] == 0]
    if not feasible:
        return CURRENT, False
    best = max(stats[c]["gold_correct"] for c in feasible)
    tied = [c for c in feasible if stats[c]["gold_correct"] == best]
    return min(tied, key=tie_key), True


def cfg_name(cfg: tuple) -> str:
    beta, margin, boundary, comp = cfg
    return f"b{beta:g}_m{margin:g}_{'bd' if boundary else 'nobd'}_{'compM' if comp else 'compIgn'}"


def new_tally() -> dict:
    return {"gold_n": 0, "gold_correct": 0, "gold_sub": 0, "fa_n": 0, "fa_rewrite": 0, "miss": Counter()}


def tally(stats: dict, key, gold: list[str], dec: dict) -> None:
    s = stats[key]
    row = {"gold": gold, "d": dec}
    d = score_v2.decide(row, "d")
    if gold:
        s["gold_n"] += 1
        s["gold_correct"] += d["correct"]
        s["gold_sub"] += d["gold_sub"]
        if d["correct"]:
            cause = "correct"
        elif d["gold_sub"]:
            cause = "substitution"
        elif dec["outcome"] == "suggest":
            cause = f"suggest_{dec['reason']}"
        else:
            cause = dec["outcome"]
        s["miss"][cause] += 1
    else:
        s["fa_n"] += 1
        s["fa_rewrite"] += d["rewrite"]


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dumps", type=Path, required=True)
    ap.add_argument("--model", action="append", required=True, help="SIZE=model dir (repeatable)")
    ap.add_argument("--labels", type=Path, required=True)
    ap.add_argument("--classes", type=Path, required=True)
    ap.add_argument("--places", type=Path)
    ap.add_argument("--impostors", type=Path)
    ap.add_argument("--out", type=Path, required=True, help="private output dir")
    args = ap.parse_args(argv)

    labels = json.loads(args.labels.read_text(encoding="utf-8"))
    classes = json.loads(args.classes.read_text(encoding="utf-8"))
    places = oracle.load_places(args.places, oracle.lexicon.NZ_PLACES)
    impostors = oracle.load_names(args.impostors, oracle.lexicon.IMPOSTORS)
    args.out.mkdir(parents=True, exist_ok=True)

    cfgs = grid()
    stats = {c: defaultdict(new_tally) for c in cfgs}
    per_clip: list[dict] = []
    for spec in args.model:
        size, _, d = spec.partition("=")
        model = oracle.Model.load(size, Path(d))
        entries = oracle.Entries.build(model, places, impostors)
        exact = {oracle.lexicon.fold(n): i for i, n in enumerate(entries.names)}
        for slot in SLOTS:
            for json_path in sorted(args.dumps.glob(f"*.{size}.*.json")):
                dump = oracle.load_dump(json_path)
                lab = labels.get(dump.clip)
                if lab is None:
                    continue
                toks = oracle.greedy_tokens(dump.logp, model.blank)
                text, char_tok = oracle.greedy_text(model, toks)
                span = oracle.slot_for(slot, GATE, toks, dump.logp.shape[0], text, char_tok)
                if span is None:
                    continue
                clip = oracle.make_clip(text, char_tok, toks, span)
                raw, ra, rb = oracle.raw_scores(dump, entries, span)
                cls = classes[dump.clip]
                rec = {"clip": dump.clip, "size": size, "slot": slot, "class": cls, "cfgs": {}}
                for c in cfgs:
                    beta, margin, boundary, comp = c
                    dec = oracle.evaluate(raw, ra, rb, entries, beta, lab["gold"], clip, exact,
                                          margin_min=margin, boundary=boundary, competitor_margin=comp)
                    tally(stats[c], (size, slot), lab["gold"], dec)
                    rec["cfgs"][cfg_name(c)] = {"outcome": dec["outcome"], "reason": dec["reason"],
                                                "top": dec["top_name"], "match": dec["match"]}
                per_clip.append(rec)
                print(f"[{size} {slot}] {dump.clip}", file=sys.stderr)

    # aggregates over all cells (size x slot) per config
    totals = {}
    for c in cfgs:
        t = new_tally()
        for (size, slot), s in stats[c].items():
            for k in ("gold_n", "gold_correct", "gold_sub", "fa_n", "fa_rewrite"):
                t[k] += s[k]
            t["miss"].update(s["miss"])
        totals[c] = t
    chosen, feasible_found = choose(totals)
    summary = {
        "configs": {cfg_name(c): {"gold_n": t["gold_n"], "gold_correct": t["gold_correct"],
                                  "gold_sub": t["gold_sub"], "fa_n": t["fa_n"], "fa_rewrite": t["fa_rewrite"],
                                  "miss": dict(t["miss"])} for c, t in totals.items()},
        "current": cfg_name(CURRENT),
        "chosen": cfg_name(chosen),
        "feasible_found": feasible_found,
        "cells": {cfg_name(c): {f"{s}.{sl}": {k: v for k, v in tl.items() if k != "miss"} | {"miss": dict(tl["miss"])}
                                for (s, sl), tl in stats[c].items()} for c in (CURRENT, chosen)},
    }
    (args.out / "sweep.json").write_text(json.dumps(summary, ensure_ascii=False, indent=1), encoding="utf-8")
    (args.out / "per_clip.sweep.json").write_text(json.dumps(per_clip, ensure_ascii=False), encoding="utf-8")
    print(json.dumps({"current": summary["current"], "chosen": summary["chosen"],
                      "feasible_found": feasible_found}, indent=1))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
