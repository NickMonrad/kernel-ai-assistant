#!/usr/bin/env python3
"""#2093 competitor pre-filter: exact upper-bound pruning, checked against the frozen full scan (beta 5).

Replaces the K-trigram retrieval (removed; it changed decisions at every K tested). Pruning is exact for decisions:

  score_r = raw_r + BETA * n_r  (oracle.evaluate)
  pen[t, v] = logp[t, v] - max_v logp[t, v] <= 0
  UB_r = sum over label occurrences k of row r of max_t pen[t, label_k]  +  BETA * n_r  >=  score_r

Rounds: score the SEED rows with the largest UB; s* = max exact score so far; then score every unscored row with
UB >= s* - MARGIN - EPS; repeat until no unscored row qualifies. Each unscored row then has score < s_final - MARGIN,
so the top entry, its best row (ties: lowest row index, as oracle.best_per_entry), the runner-up and the decision are
the same as in the full scan. The reported margin can be larger than the full-scan margin only when the true margin
is >= MARGIN (no decision effect; decisions use margin < MARGIN only).

Modes:
  check  full scan vs pruned on the same clip-slots: decision fields (S4, S5, SILENT start flag, outcome, reason,
         top, match), raw/start/end equality on scored rows, bound validity (score <= UB on every row) and pruning
         soundness (every unscored row scores below s* - MARGIN). Exit 1 on any mismatch.
  time   owner set, full vs pruned, from slot choice to decision (greedy decode excluded). Warm-up + --reps repeats
         per clip-slot; per clip-slot median; p50/p95 across clip-slots. Pin with taskset; set OMP/OPENBLAS threads to 1.

Aggregates only on stdout and in --out. Per-clip rows only in --private.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path

import numpy as np

import lexicon
import oracle
import suggest_v41 as S41

SEED = 256
EPS = 1e-6
SLOTS = ["hint", "full", "trigger"]
BETA = S41.BETA
MARGIN = S41.MARGIN
NEG = oracle.NEG
DECISION_FIELDS = ("outcome", "reason", "top_name", "top_kind", "match")


class Groups:
    """Rows grouped by CTC-extension length, with the ext arrays cached (same grouping as oracle.raw_scores)."""

    def __init__(self, entries: oracle.Entries):
        self.R = len(entries.row_ext)
        self.groups = {}
        for L, idx in entries.by_length().items():
            ext = np.array([entries.row_ext[i] for i in idx], dtype=np.int64)
            self.groups[L] = (idx, ext)

    def raw(self, dump: oracle.Dump, span, keep: np.ndarray | None):
        a, b = span
        lp = dump.logp[a:b]
        pen = lp - lp.max(axis=1, keepdims=True)
        out = np.full(self.R, NEG)
        st = np.zeros(self.R, dtype=np.int64)
        en = np.zeros(self.R, dtype=np.int64)
        for idx, ext in self.groups.values():
            if keep is None:
                sub_idx, sub_ext = idx, ext
            else:
                m = keep[idx]
                if not m.any():
                    continue
                sub_idx, sub_ext = idx[m], ext[m]
            best, sa, sb = oracle.span_best(pen, sub_ext)
            out[sub_idx] = best
            st[sub_idx] = sa + a
            en[sub_idx] = sb + a
        return out, st, en

    def bound(self, mp: np.ndarray, row_n: np.ndarray) -> np.ndarray:
        """UB score per row: sum of the per-label maxima over label occurrences (odd ext indices) + BETA * n."""
        ub = np.empty(self.R)
        for idx, ext in self.groups.values():
            ub[idx] = mp[ext[:, 1::2]].sum(axis=1) + BETA * row_n[idx]
        return ub


def pruned_scores(groups: Groups, row_n: np.ndarray, dump: oracle.Dump, span):
    """Exact-decision pruned scan. Returns raw/start/end (NEG where unscored), scored mask, UB, rounds, s*."""
    a, b = span
    lp = dump.logp[a:b]
    pen = lp - lp.max(axis=1, keepdims=True)
    ub = groups.bound(pen.max(axis=0), row_n)
    scored = np.zeros(groups.R, dtype=bool)
    out = np.full(groups.R, NEG)
    st = np.zeros(groups.R, dtype=np.int64)
    en = np.zeros(groups.R, dtype=np.int64)
    k = min(SEED, groups.R)
    cand = np.zeros(groups.R, dtype=bool)
    cand[np.argpartition(-ub, k - 1)[:k]] = True
    rounds = 0
    s_star = -np.inf
    while True:
        new = cand & ~scored
        if not new.any():
            break
        rounds += 1
        raw_new, sn, en_new = groups.raw(dump, span, new)
        out[new] = raw_new[new]
        st[new] = sn[new]
        en[new] = en_new[new]
        scored |= new
        s_star = float(np.max(out[scored] + BETA * row_n[scored]))
        cand = ub >= s_star - MARGIN - EPS
    return out, st, en, scored, ub, rounds, s_star


def decide_fields(ev: dict, rec: dict) -> tuple:
    return tuple(ev[f] for f in DECISION_FIELDS) + (rec["s4"]["kind"], rec["s4"]["name"], rec["s5"]["kind"],
                                                  rec["s5"]["name"], rec["silent_start_fail"])


def pct(xs, q):
    return float(np.percentile(np.array(xs), q)) if len(xs) else float("nan")


def run_check(args, sets, places, impostors) -> int:
    checks = {"clip_slots": 0, "decision_mismatch": 0, "raw_mismatch": 0, "bound_violations": 0,
              "soundness_violations": 0}
    scored_rows: dict = {}
    rounds_list: dict = {}
    mismatches: list = []
    for size_spec in args.model:
        size, _, mdir = size_spec.partition("=")
        model = oracle.Model.load(size, Path(mdir))
        entries = oracle.Entries.build(model, places, impostors)
        exact = {lexicon.fold(n): i for i, n in enumerate(entries.names)}
        groups = Groups(entries)
        row_n = entries.row_n.astype(np.float64)
        for name, ddir, labels, cmap in sets:
            paths = sorted(ddir.glob(f"*.{size}.*.json"))
            if args.limit:
                paths = paths[:args.limit]
            for jp in paths:
                dump = oracle.load_dump(jp)
                lab = labels.get(dump.clip)
                if lab is None:
                    continue
                toks = oracle.greedy_tokens(dump.logp, model.blank)
                text, char_tok = oracle.greedy_text(model, toks)
                for slot in args.slots:
                    span = oracle.slot_for(slot, "v2", toks, dump.logp.shape[0], text, char_tok)
                    if span is None:
                        continue
                    clip = oracle.make_clip(text, char_tok, toks, span)
                    full, fst, fen = groups.raw(dump, span, None)
                    ev_f = oracle.evaluate(full, fst, fen, entries, BETA, lab["gold"], clip, exact, margin_min=MARGIN)
                    rec_f = S41.record(ev_f, clip, entries, full, fst, fen)
                    pr, pst, pen_, scored, ub, rounds, s_star = pruned_scores(groups, row_n, dump, span)
                    ev_p = oracle.evaluate(pr, pst, pen_, entries, BETA, lab["gold"], clip, exact, margin_min=MARGIN)
                    rec_p = S41.record(ev_p, clip, entries, pr, pst, pen_)
                    key = (size, slot)
                    checks["clip_slots"] += 1
                    scored_rows.setdefault(key, []).append(int(scored.sum()))
                    rounds_list.setdefault(key, []).append(rounds)
                    if decide_fields(ev_f, rec_f) != decide_fields(ev_p, rec_p):
                        checks["decision_mismatch"] += 1
                        mismatches.append((size, slot, name, dump.clip))
                    if not (np.array_equal(pr[scored], full[scored]) and np.array_equal(pst[scored], fst[scored])
                            and np.array_equal(pen_[scored], fen[scored])):
                        checks["raw_mismatch"] += 1
                    full_score = full + BETA * row_n
                    checks["bound_violations"] += int(np.sum(full_score > ub + 1e-6))
                    checks["soundness_violations"] += int(np.sum(full_score[~scored] >= s_star - MARGIN))
            print(f"[{size}] set {name} done", file=sys.stderr)
    per_slot = {f"{s}|{sl}": {"clip_slots": len(v), "rows_scored_p50": pct(v, 50), "rows_scored_p95": pct(v, 95),
                              "rounds_p50": pct(rounds_list[(s, sl)], 50), "rounds_p95": pct(rounds_list[(s, sl)], 95)}
                for (s, sl), v in sorted(scored_rows.items())}
    result = {"mode": "check", "beta": BETA, "margin": MARGIN, "seed": SEED, "checks": checks, "per_slot": per_slot}
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, ensure_ascii=False, indent=1), encoding="utf-8")
    (args.private / "mismatches.prefilter.json").write_text(json.dumps(mismatches), encoding="utf-8")
    print(json.dumps(result, indent=1))
    bad = checks["decision_mismatch"] or checks["raw_mismatch"] or checks["bound_violations"] or \
        checks["soundness_violations"]
    return 1 if bad else 0


def run_time(args, sets, places, impostors) -> int:
    load_before = os.getloadavg()
    med: dict = {}
    spans: dict = {}
    for size_spec in args.model:
        size, _, mdir = size_spec.partition("=")
        model = oracle.Model.load(size, Path(mdir))
        entries = oracle.Entries.build(model, places, impostors)
        exact = {lexicon.fold(n): i for i, n in enumerate(entries.names)}
        groups = Groups(entries)
        row_n = entries.row_n.astype(np.float64)
        for name, ddir, labels, cmap in sets:
            for jp in sorted(ddir.glob(f"*.{size}.*.json")):
                dump = oracle.load_dump(jp)
                lab = labels.get(dump.clip)
                if lab is None:
                    continue
                toks = oracle.greedy_tokens(dump.logp, model.blank)
                text, char_tok = oracle.greedy_text(model, toks)
                T = dump.logp.shape[0]

                def decide(kind: str, slot: str):
                    span = oracle.slot_for(slot, "v2", toks, T, text, char_tok)
                    if span is None:
                        return None
                    clip = oracle.make_clip(text, char_tok, toks, span)
                    if kind == "full":
                        raw, ra, rb = groups.raw(dump, span, None)
                    else:
                        raw, ra, rb, *_ = pruned_scores(groups, row_n, dump, span)
                    ev = oracle.evaluate(raw, ra, rb, entries, BETA, lab["gold"], clip, exact, margin_min=MARGIN)
                    S41.record(ev, clip, entries, raw, ra, rb)
                    return span

                for slot in args.slots:
                    if decide("full", slot) is None:
                        continue
                    decide("pruned", slot)  # warm-up
                    t_full, t_pr = [], []
                    for _ in range(args.reps):
                        t = time.perf_counter()
                        decide("full", slot)
                        t_full.append(time.perf_counter() - t)
                        t = time.perf_counter()
                        decide("pruned", slot)
                        t_pr.append(time.perf_counter() - t)
                    med.setdefault((size, slot, "full"), []).append(float(np.median(t_full)) * 1000.0)
                    med.setdefault((size, slot, "pruned"), []).append(float(np.median(t_pr)) * 1000.0)
                    span = oracle.slot_for(slot, "v2", toks, T, text, char_tok)
                    spans.setdefault((size, slot), []).append(span[1] - span[0])
            print(f"[{size}] set {name} timed", file=sys.stderr)
    load_after = os.getloadavg()
    result = {"mode": "time", "reps": args.reps, "warmup": 1, "pinned": os.environ.get("TIME_PIN", ""),
              "load_before": load_before, "load_after": load_after,
              "ms": {f"{s}|{sl}|{p}": {"clips": len(v), "p50": round(pct(v, 50), 2), "p95": round(pct(v, 95), 2)}
                     for (s, sl, p), v in sorted(med.items())},
              "slot_frames": {f"{s}|{sl}": {"p50": pct(v, 50), "p95": pct(v, 95)}
                              for (s, sl), v in sorted(spans.items())}}
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, ensure_ascii=False, indent=1), encoding="utf-8")
    print(json.dumps(result, indent=1))
    return 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("mode", choices=["check", "time"])
    ap.add_argument("--model", action="append", required=True, help="SIZE=model dir (repeatable)")
    ap.add_argument("--set", action="append", required=True, help="NAME=DUMPDIR:LABELS:CLASSMAP (repeatable)")
    ap.add_argument("--places", type=Path, required=True)
    ap.add_argument("--slots", default=",".join(SLOTS))
    ap.add_argument("--reps", type=int, default=5)
    ap.add_argument("--limit", type=int, default=0, help="smoke: max dumps per size and set (0 = all)")
    ap.add_argument("--private", type=Path, required=True, help="private output dir")
    ap.add_argument("--out", type=Path, required=True, help="aggregate JSON (git-safe)")
    args = ap.parse_args(argv)
    args.private.mkdir(parents=True, exist_ok=True)
    args.slots = args.slots.split(",")
    places = oracle.load_places(args.places, lexicon.NZ_PLACES)
    impostors = oracle.load_names(None, lexicon.IMPOSTORS)
    sets = []
    for spec in args.set:
        name, _, rest = spec.partition("=")
        d, labels, cmap = rest.split(":")
        sets.append((name, Path(d), json.loads(Path(labels).read_text(encoding="utf-8")),
                     json.loads(Path(cmap).read_text(encoding="utf-8"))))
    if args.mode == "check":
        return run_check(args, sets, places, impostors)
    return run_time(args, sets, places, impostors)


if __name__ == "__main__":
    sys.exit(main())
