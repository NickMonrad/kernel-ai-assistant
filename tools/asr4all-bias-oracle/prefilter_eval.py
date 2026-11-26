#!/usr/bin/env python3
"""#2093 competitor pre-filter: measure decision changes and scoring time against the frozen oracle (beta 5).

The frozen oracle.py is not edited. Stage 1 (new): a char-trigram retrieval index over every candidate row
(canonical + respellings), scored per slot word by cosine; the top-K rows plus every row of an exact-match
entry are kept. Stage 2: oracle.span_best on the kept rows only; unkept rows stay NEG, so oracle.evaluate()
runs unchanged. The filter is measured here; it is NOT part of the held-out pass (see HELDOUT-PREREG-v4.md).

Output: aggregates to stdout and a JSON file. Per-clip rows go to the private directory given with --private.
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

import numpy as np

import lexicon
import oracle
import suggest_v4

KS = [50, 100, 200, 500, 1000, 2000]
SLOTS = ["hint", "full", "trigger"]
BETA = 5.0
MARGIN = 1.0
NEG = oracle.NEG


def trigrams(word: str) -> set[str]:
    p = "#" + word + "#"
    return {p[i:i + 3] for i in range(len(p) - 2)}


class TriIndex:
    """Inverted index: trigram id -> sorted row ids. Built once per model size."""

    def __init__(self, entries: oracle.Entries):
        self.entries = entries
        R = len(entries.row_entry)
        vocab: dict[str, int] = {}
        postings: list[list[int]] = []
        self.rnorm = np.ones(R)
        for r in range(R):
            name = entries.names[int(entries.row_entry[r])]
            text = lexicon.fold(lexicon.respellings(name)[int(entries.row_variant[r])])
            tris: set[str] = set()
            for w in text.split():
                tris |= trigrams(w)
            self.rnorm[r] = max(len(tris), 1)
            for t in tris:
                tid = vocab.setdefault(t, len(vocab))
                if tid == len(postings):
                    postings.append([])
                postings[tid].append(r)
        self.vocab = vocab
        self.ptr = np.zeros(len(postings) + 1, dtype=np.int64)
        self.ptr[1:] = np.cumsum([len(p) for p in postings])
        self.post = np.fromiter((r for p in postings for r in p), dtype=np.int64, count=int(self.ptr[-1]))
        self.R = R
        order = np.argsort(entries.row_entry, kind="stable")
        self.rows_by_entry_sorted = order
        self.entry_bounds = np.searchsorted(entries.row_entry[order], np.arange(len(entries.names) + 1))

    def select(self, slot_text: str, K: int, exact_entry: int | None) -> np.ndarray:
        best = np.zeros(self.R)
        for w in slot_text.split():
            Q = trigrams(w)
            ids = [self.vocab[t] for t in Q if t in self.vocab]
            if not ids:
                continue
            cat = np.concatenate([self.post[self.ptr[i]:self.ptr[i + 1]] for i in ids])
            cnt = np.bincount(cat, minlength=self.R)
            np.maximum(best, cnt / np.sqrt(len(Q) * self.rnorm), out=best)
        keep = np.zeros(self.R, dtype=bool)
        if K >= self.R:
            keep[:] = True
        else:
            keep[np.argpartition(-best, K)[:K]] = True
        if exact_entry is not None:
            keep[self.rows_by_entry_sorted[self.entry_bounds[exact_entry]:self.entry_bounds[exact_entry + 1]]] = True
        return keep


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


def pct(xs, q):
    return float(np.percentile(np.array(xs), q)) * 1000.0 if xs else float("nan")


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--model", action="append", required=True, help="SIZE=model dir (repeatable)")
    ap.add_argument("--set", action="append", required=True,
                    help="NAME=DUMPDIR:LABELS:CLASSMAP (repeatable)")
    ap.add_argument("--places", type=Path, required=True)
    ap.add_argument("--slots", default=",".join(SLOTS))
    ap.add_argument("--limit", type=int, default=0, help="smoke: max dumps per size and set (0 = all)")
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
        d, labels, cmap = rest.split(":")
        sets.append((name, Path(d), json.loads(Path(labels).read_text(encoding="utf-8")),
                     json.loads(Path(cmap).read_text(encoding="utf-8"))))

    agg: dict = {}          # (size, slot, K) -> counts
    times: dict = {}        # (size, slot, K|'full') -> [seconds]
    eval_times: list = []
    checks = {"raw_equal": 0, "raw_checked": 0, "exact_missing": 0}
    per_clip: list = []

    for size_spec in args.model:
        size, _, mdir = size_spec.partition("=")
        model = oracle.Model.load(size, Path(mdir))
        entries = oracle.Entries.build(model, places, impostors)
        exact = {lexicon.fold(n): i for i, n in enumerate(entries.names)}
        t0 = time.perf_counter()
        index = TriIndex(entries)
        groups = Groups(entries)
        print(f"[{size}] rows={len(entries.row_ext)} index_s={time.perf_counter() - t0:.1f}", file=sys.stderr)
        for name, ddir, labels, cmap in sets:
            paths = sorted(ddir.glob(f"*.{size}.*.json"))
            if args.limit:
                paths = paths[:args.limit]
            for jp in paths:
                dump = oracle.load_dump(jp)
                lab = labels.get(dump.clip)
                if lab is None:
                    continue
                gold = bool(lab["gold"])
                cls = cmap.get(dump.clip, "?")
                toks = oracle.greedy_tokens(dump.logp, model.blank)
                text, char_tok = oracle.greedy_text(model, toks)
                for slot in slots:
                    span = oracle.slot_for(slot, "v2", toks, dump.logp.shape[0], text, char_tok)
                    if span is None:
                        continue
                    clip = oracle.make_clip(text, char_tok, toks, span)
                    exact_entry = exact.get(clip.slot_text) if clip.slot_text else None

                    t = time.perf_counter()
                    full, fst, fen = groups.raw(dump, span, None)
                    times.setdefault((size, slot, "full"), []).append(time.perf_counter() - t)
                    t = time.perf_counter()
                    ev_full = oracle.evaluate(full, fst, fen, entries, BETA, lab["gold"],
                                              clip, exact, margin_min=MARGIN)
                    eval_times.append(time.perf_counter() - t)
                    dec_full = suggest_v4.decide(ev_full)

                    for K in KS:
                        t = time.perf_counter()
                        keep = index.select(clip.slot_text if clip.slot_text else "", K, exact_entry)
                        raw_k, ra, rb = groups.raw(dump, span, keep)
                        times.setdefault((size, slot, K), []).append(time.perf_counter() - t)
                        checks["raw_checked"] += 1
                        checks["raw_equal"] += int(np.array_equal(raw_k[keep], full[keep]))
                        if exact_entry is not None and not keep[np.flatnonzero(entries.row_entry == exact_entry)].all():
                            checks["exact_missing"] += 1
                        t = time.perf_counter()
                        ev_k = oracle.evaluate(raw_k, ra, rb, entries, BETA, lab["gold"], clip, exact,
                                               margin_min=MARGIN)
                        eval_times.append(time.perf_counter() - t)
                        dec_k = suggest_v4.decide(ev_k)
                        c = agg.setdefault((size, slot, K), {"n": 0, "gold_n": 0, "outcome": 0, "gold_outcome": 0,
                                                             "top_name": 0, "gold_top_name": 0,
                                                             "s4": 0, "gold_s4": 0, "cls": {}})
                        c["n"] += 1
                        c["gold_n"] += gold
                        ch_out = ev_full["outcome"] != ev_k["outcome"]
                        ch_top = ev_full["top_name"] != ev_k["top_name"]
                        ch_s4 = (dec_full["kind"], dec_full["name"]) != (dec_k["kind"], dec_k["name"])
                        c["outcome"] += ch_out
                        c["gold_outcome"] += ch_out and gold
                        c["top_name"] += ch_top
                        c["gold_top_name"] += ch_top and gold
                        c["s4"] += ch_s4
                        c["gold_s4"] += ch_s4 and gold
                        cc = c["cls"].setdefault(cls, {"n": 0, "s4": 0})
                        cc["n"] += 1
                        cc["s4"] += ch_s4
                        per_clip.append({"size": size, "set": name, "slot": slot, "K": K, "clip": dump.clip,
                                         "gold": gold, "outcome_full": ev_full["outcome"], "outcome_k": ev_k["outcome"],
                                         "top_full": ev_full["top_name"], "top_k": ev_k["top_name"],
                                         "s4_full": dec_full, "s4_k": dec_k})
            print(f"[{size}] set {name} done", file=sys.stderr)

    result = {"ks": KS, "beta": BETA, "margin": MARGIN, "checks": checks, "decisions": {}, "timing_ms": {}}
    for (size, slot, K), c in sorted(agg.items(), key=str):
        result["decisions"][f"{size}|{slot}|{K}"] = {k: v for k, v in c.items() if k != "cls"} | {
            "classes": c["cls"]}
    for key, xs in sorted(times.items(), key=str):
        result["timing_ms"][f"{key[0]}|{key[1]}|{key[2]}"] = {"n": len(xs), "p50": round(pct(xs, 50), 2),
                                                             "p95": round(pct(xs, 95), 2)}
    result["evaluate_ms"] = {"n": len(eval_times), "p50": round(pct(eval_times, 50), 2),
                             "p95": round(pct(eval_times, 95), 2)}
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, ensure_ascii=False, indent=1), encoding="utf-8")
    (args.private / "per_clip.prefilter.json").write_text(json.dumps(per_clip, ensure_ascii=False),
                                                          encoding="utf-8")
    print(json.dumps({"checks": checks, "evaluate_ms": result["evaluate_ms"]}, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
