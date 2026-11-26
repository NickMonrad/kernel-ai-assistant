#!/usr/bin/env python3
"""#2093 pre-registration v4.1: reported rule S5, SILENT start-boundary diagnostic, bounds and the v4 report.

Frozen inputs: oracle.py (beta 5, margin 1.0, boundary on) and suggest_v4.decide (rule S4, unchanged).
S5 is a separately reported arm. It differs from S4 only where S4 gives NONE on a margin or boundary failure.
Dumps are scored with the frozen oracle's full scan (no pruning). Aggregates only on stdout and in --out;
per-clip rows only in the private --private directory. Pre-registration: HELDOUT-PREREG-v4.1.md.

  python3 suggest_v41.py --model M=DIR --model L=DIR --places JSON --set NAME=DUMPDIR:LABELS:CLASSMAP[:CARRIERS]
                         [--slots hint,full,trigger] --private DIR --out JSON
"""
from __future__ import annotations

import argparse
import json
import math
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np

import lexicon
import oracle
import suggest_v4

BETA = 5.0
MARGIN = 1.0
Z = 1.96
S5_REASONS = ("margin", "boundary")
ARMS = ("s4", "s5")


def wilson(k: int, n: int) -> tuple[float, float]:
    """Wilson 95% interval for k successes in n (score interval, z = 1.96)."""
    if n == 0:
        return (0.0, 1.0)
    p, z2 = k / n, Z * Z
    denom = 1 + z2 / n
    centre = p + z2 / (2 * n)
    half = Z * math.sqrt(p * (1 - p) / n + z2 / (4 * n * n))
    return (max(0.0, (centre - half) / denom), min(1.0, (centre + half) / denom))


def upper(k: int, n: int) -> float:
    return wilson(k, n)[1]


def decide_s5(ev: dict) -> dict:
    """Rule S5 (reported arm): S4, plus PROMPT on a margin or boundary failure when the top is rewritable.

    Identity (the heard text already is the top place) gives NONE here: nothing to confirm, and no silent output.
    """
    if ev["outcome"] == "suggest" and ev["reason"] in S5_REASONS and ev["top_kind"] == "nz":
        if lexicon.fold(ev["top_name"]) == ev["match"]:
            return {"kind": "none", "name": None, "reason": f"identity_{ev['reason']}"}
        return {"kind": "prompt", "name": ev["top_name"], "reason": ev["reason"]}
    return suggest_v4.decide(ev)


def start_boundary_fail(clip, a: int, b: int) -> bool:
    """Mirror of the end check at the start: the first matched character sits inside a word.

    Fails when the character before the span is a letter and the first matched character is not a space.
    A span with no greedy characters fails.
    """
    idx = clip.chars_in(a, b)
    if not idx:
        return True
    first = idx[0]
    prev = clip.text[first - 1] if first > 0 else " "
    return clip.text[first] != " " and prev != " "


def top_span(entries: oracle.Entries, raw: np.ndarray, ra: np.ndarray, rb: np.ndarray, name: str) -> tuple[int, int]:
    """Frames of the top entry's best row: same row as oracle.best_per_entry (lowest index among ties)."""
    e = entries.names.index(name)
    rows = np.flatnonzero(entries.row_entry == e)
    score = raw[rows] + BETA * entries.row_n[rows]
    r = int(rows[int(np.argmax(score))])
    return int(ra[r]), int(rb[r])


def record(ev: dict, clip, entries: oracle.Entries, raw: np.ndarray, ra: np.ndarray, rb: np.ndarray) -> dict:
    """S4 and S5 decisions for one clip-slot, plus the SILENT start-boundary flag (None when not SILENT)."""
    s4 = suggest_v4.decide(ev)
    fail = None
    if s4["kind"] == "silent":
        a, b = top_span(entries, raw, ra, rb, ev["top_name"])
        fail = start_boundary_fail(clip, a, b)
    return {"s4": s4, "s5": decide_s5(ev), "silent_start_fail": fail}


def _cell() -> dict:
    return {"n": 0, "right_prompt": 0, "wrong_prompt": 0, "silent_right": 0, "silent_wrong": 0, "none": 0,
            "false_prompt": 0, "silent_nongold": 0, "silent_start_fail": 0}


def _add(c: dict, gold: str | None, dec: dict, fail: bool | None) -> None:
    c["n"] += 1
    if gold is not None:  # gold line
        if dec["kind"] == "prompt":
            c["right_prompt" if lexicon.fold(dec["name"]) == gold else "wrong_prompt"] += 1
        elif dec["kind"] == "silent":
            c["silent_right" if lexicon.fold(dec["name"]) == gold else "silent_wrong"] += 1
        else:
            c["none"] += 1
    else:  # non-gold line
        if dec["kind"] == "prompt":
            c["false_prompt"] += 1
        elif dec["kind"] == "silent":
            c["silent_nongold"] += 1
            c["silent_start_fail"] += bool(fail)
        else:
            c["none"] += 1


def aggregate(rows: list[dict]) -> dict:
    """rows: {size, slot, clip, cls, carrier, gold (display name or None), s4, s5, silent_start_fail}.

    Pass criteria (v4, applied to each arm separately): P1 gold wrong prompts = 0 (pooled and per class),
    P2 gold SILENT with another name = 0, P3 non-gold prompts = 0 (pooled and per class). Primary slot: hint.
    """
    cells: dict = defaultdict(_cell)
    by_class: dict = defaultdict(_cell)
    by_carrier: dict = defaultdict(_cell)
    towns: dict = defaultdict(lambda: {"n": 0, "wrong": False, "right": False})
    for r in rows:
        gold = lexicon.fold(r["gold"]) if r["gold"] else None
        for arm in ARMS:
            dec = r[arm]
            fail = r["silent_start_fail"]
            key = (arm, r["size"], r["slot"])
            _add(cells[key], gold, dec, fail)
            _add(by_class[(arm, r["size"], r["slot"], r["cls"])], gold, dec, fail)
            if r["carrier"]:
                _add(by_carrier[(arm, r["size"], r["slot"], r["carrier"], "gold" if gold else "nongold")],
                     gold, dec, fail)
            if gold:
                t = towns[(arm, r["size"], r["slot"], gold)]
                t["n"] += 1
                t["wrong"] |= dec["kind"] == "prompt" and lexicon.fold(dec["name"]) != gold
    out: dict = {}
    for (arm, size, slot), c in sorted(cells.items()):
        tag = f"{arm}|{size}|{slot}"
        gold_n = sum(v["n"] for (a, s, sl, cls), v in by_class.items() if (a, s, sl) == (arm, size, slot)
                     and cls.startswith("gold_"))
        gold_cls = {cls: v for (a, s, sl, cls), v in by_class.items() if (a, s, sl) == (arm, size, slot)
                    and cls.startswith("gold_")}
        non_cls = {cls: v for (a, s, sl, cls), v in by_class.items() if (a, s, sl) == (arm, size, slot)
                   and not cls.startswith("gold_")}
        tw = [t for (a, s, sl, g), t in towns.items() if (a, s, sl) == (arm, size, slot)]
        n_towns, n_wrong_towns = len(tw), sum(t["wrong"] for t in tw)
        offered = c["right_prompt"] + c["silent_right"]
        gold_n = c["n"] - sum(v["n"] for v in non_cls.values())
        out[tag] = {
            "gold": {"n": gold_n, "offered_right": offered, "offered_right_wilson": wilson(offered, gold_n),
                     "wrong_prompt": c["wrong_prompt"], "wrong_prompt_upper": upper(c["wrong_prompt"], gold_n),
                     "silent_wrong": c["silent_wrong"], "none": c["none"]},
            "gold_by_class": {k: {"n": v["n"], "offered_right": v["right_prompt"] + v["silent_right"],
                                  "wrong_prompt": v["wrong_prompt"], "wrong_prompt_upper": upper(v["wrong_prompt"], v["n"]),
                                  "silent_wrong": v["silent_wrong"]} for k, v in sorted(gold_cls.items())},
            "gold_towns": {"n": n_towns, "with_wrong_prompt": n_wrong_towns,
                           "with_wrong_prompt_upper": upper(n_wrong_towns, n_towns)},
            "nongold": {"n": c["n"] - gold_n, "false_prompt": c["false_prompt"],
                        "false_prompt_upper": upper(c["false_prompt"], c["n"] - gold_n),
                        "silent": c["silent_nongold"], "silent_start_fail": c["silent_start_fail"]},
            "nongold_by_class": {k: {"n": v["n"], "false_prompt": v["false_prompt"],
                                     "false_prompt_upper": upper(v["false_prompt"], v["n"]),
                                     "silent": v["silent_nongold"], "silent_start_fail": v["silent_start_fail"]}
                                 for k, v in sorted(non_cls.items())},
        }
        out[tag]["by_carrier"] = {
            f"{car}|{kind}": {"n": v["n"], "offered_right": v["right_prompt"] + v["silent_right"],
                              "wrong_prompt": v["wrong_prompt"], "false_prompt": v["false_prompt"],
                              "silent_nongold": v["silent_nongold"], "silent_start_fail": v["silent_start_fail"],
                              "wrong_prompt_upper": upper(v["wrong_prompt"], v["n"]) if kind == "gold"
                              else None,
                              "false_prompt_upper": upper(v["false_prompt"], v["n"]) if kind == "nongold"
                              else None}
            for (a, s, sl, car, kind), v in sorted(by_carrier.items()) if (a, s, sl) == (arm, size, slot)}
        out[tag]["pass"] = {
            "P1": c["wrong_prompt"] == 0 and all(v["wrong_prompt"] == 0 for v in gold_cls.values()),
            "P2": c["silent_wrong"] == 0,
            "P3": c["false_prompt"] == 0 and all(v["false_prompt"] == 0 for v in non_cls.values()),
        }
        out[tag]["pass"]["all"] = all(out[tag]["pass"].values())
        out[tag]["precondition_offered_right_ge_50pct"] = offered * 2 >= gold_n
    return out


def load_meta(labels_path: Path, cmap_path: Path, carriers_path: Path | None) -> dict:
    labels = json.loads(labels_path.read_text(encoding="utf-8"))
    cmap = json.loads(cmap_path.read_text(encoding="utf-8"))
    carriers = json.loads(carriers_path.read_text(encoding="utf-8")) if carriers_path else {}
    meta = {}
    for clip, lab in labels.items():
        cls = cmap.get(clip, "?")
        car = carriers.get(clip) or ({"gold_bare": "bare", "gold_navigate": "trigger",
                                      "gold_weather": "trigger"}.get(cls))
        meta[clip] = {"gold": lab["gold"], "cls": cls, "carrier": car}
    return meta


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--model", action="append", required=True, help="SIZE=model dir (repeatable)")
    ap.add_argument("--places", type=Path, required=True)
    ap.add_argument("--set", action="append", required=True, help="NAME=DUMPDIR:LABELS:CLASSMAP[:CARRIERS]")
    ap.add_argument("--slots", default="hint,full,trigger")
    ap.add_argument("--private", type=Path, required=True, help="private output dir (per-clip rows)")
    ap.add_argument("--out", type=Path, required=True, help="aggregate JSON (git-safe)")
    args = ap.parse_args(argv)
    args.private.mkdir(parents=True, exist_ok=True)
    slots = args.slots.split(",")
    places = oracle.load_places(args.places, lexicon.NZ_PLACES)
    impostors = oracle.load_names(None, lexicon.IMPOSTORS)
    sets = []
    for spec in args.set:
        name, _, rest = spec.partition("=")
        parts = rest.split(":")
        ddir, labels, cmap = Path(parts[0]), Path(parts[1]), Path(parts[2])
        carriers = Path(parts[3]) if len(parts) > 3 else None
        sets.append((name, ddir, load_meta(labels, cmap, carriers)))
    rows: list[dict] = []
    per_clip: list[dict] = []
    for size_spec in args.model:
        size, _, mdir = size_spec.partition("=")
        model = oracle.Model.load(size, Path(mdir))
        entries = oracle.Entries.build(model, places, impostors)
        exact = {lexicon.fold(n): i for i, n in enumerate(entries.names)}
        for name, ddir, meta in sets:
            for jp in sorted(ddir.glob(f"*.{size}.*.json")):
                dump = oracle.load_dump(jp)
                m = meta.get(dump.clip)
                if m is None:
                    continue
                toks = oracle.greedy_tokens(dump.logp, model.blank)
                text, char_tok = oracle.greedy_text(model, toks)
                for slot in slots:
                    span = oracle.slot_for(slot, "v2", toks, dump.logp.shape[0], text, char_tok)
                    if span is None:
                        continue
                    clip = oracle.make_clip(text, char_tok, toks, span)
                    raw, ra, rb = oracle.raw_scores(dump, entries, span)
                    ev = oracle.evaluate(raw, ra, rb, entries, BETA, m["gold"], clip, exact, margin_min=MARGIN)
                    rec = record(ev, clip, entries, raw, ra, rb)
                    row = {"size": size, "slot": slot, "clip": dump.clip, "set": name, "cls": m["cls"],
                           "carrier": m["carrier"], "gold": m["gold"][0] if m["gold"] else None, **rec}
                    rows.append(row)
                    per_clip.append(row | {"ev": ev})
            print(f"[{size}] set {name} done", file=sys.stderr)
    (args.private / "per_clip.v41.json").write_text(json.dumps(per_clip, ensure_ascii=False), encoding="utf-8")
    agg = aggregate(rows)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(agg, ensure_ascii=False, indent=1), encoding="utf-8")
    for tag, v in agg.items():
        g, p = v["gold"], v["pass"]
        print(f"{tag}: gold n={g['n']} offered-right {g['offered_right']} wrong-prompt {g['wrong_prompt']} "
              f"silent-wrong {g['silent_wrong']} | non-gold false-prompt {v['nongold']['false_prompt']} "
              f"silent {v['nongold']['silent']} (start-fail {v['nongold']['silent_start_fail']}) | "
              f"P1 {p['P1']} P2 {p['P2']} P3 {p['P3']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
