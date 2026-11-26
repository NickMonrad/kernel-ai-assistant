"""Scoring-time check for the frozen rule (#2093 criterion 6, PC p95 reporting item).

Times the per-clip scoring path of oracle.py at β 5 (greedy decode, slot, span scores, decision) on
dump logits already in memory. Setup (candidate BPE segmentation, dump loading) is outside the timer.
This is a PC figure. It is not the S21 ≤ 50 ms condition, which is a separate step-2 condition.

Two candidate sets: the full set (rewritable + competitors + impostors) and rewritable + impostors only.
The second is a timing probe; its decisions are not used for any accuracy claim.

Aggregates only: nothing per clip is printed or written.

  python3 time_scoring.py --dumps DIR --model M=DIR --model L=DIR --labels JSON --places JSON [--out JSON]
"""
from __future__ import annotations

import argparse
import json
import math
import statistics
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import lexicon  # noqa: E402
import oracle  # noqa: E402

BETA = 5.0
REPS = 3  # timed repetitions per dump; the per-dump value is the median


def nearest_rank(values: list[float], q: float) -> float:
    ordered = sorted(values)
    return ordered[math.ceil(q * len(ordered)) - 1]


def time_dumps(model: oracle.Model, places: list[dict], impostors: list[str],
               dumps: list[tuple[oracle.Dump, list[str]]]) -> tuple[dict, list[float]]:
    """Returns (set statistics, per-dump median milliseconds)."""
    entries = oracle.Entries.build(model, places, impostors)
    exact = {lexicon.fold(n): i for i, n in enumerate(entries.names)}
    per_dump_ms: list[float] = []
    for dump, gold in dumps:
        samples = []
        for _ in range(REPS):
            t0 = time.perf_counter()
            toks = oracle.greedy_tokens(dump.logp, model.blank)
            text, char_tok = oracle.greedy_text(model, toks)
            span = oracle.slot_for("hint", "v2", toks, dump.logp.shape[0], text, char_tok)
            clip = oracle.make_clip(text, char_tok, toks, span)
            raw, ra, rb = oracle.raw_scores(dump, entries, span)
            oracle.evaluate(raw, ra, rb, entries, BETA, gold, clip, exact, margin_min=oracle.MARGIN)
            samples.append((time.perf_counter() - t0) * 1000.0)
        per_dump_ms.append(statistics.median(samples))
    stats = {
        "names": len(entries.names),
        "rewritable": sum(k == "nz" for k in entries.kinds),
        "competitors": sum(k == "comp" for k in entries.kinds),
        "impostors": sum(k == "imp" for k in entries.kinds),
        "variants": len(entries.row_ext),
    }
    return stats, per_dump_ms


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dumps", type=Path, required=True, help="owner dump_logits_dir (private)")
    ap.add_argument("--model", action="append", required=True, help="SIZE=model dir (repeatable)")
    ap.add_argument("--labels", type=Path, required=True, help="private labels JSON")
    ap.add_argument("--places", type=Path, required=True, help="nz_geonames_places.json (private)")
    ap.add_argument("--out", type=Path, help="aggregate JSON output (optional)")
    args = ap.parse_args(argv)

    labels = json.loads(args.labels.read_text())
    all_places = oracle.load_places(args.places, lexicon.NZ_PLACES)
    rw_only = [p for p in all_places if p["role"] == "rewritable"]
    impostors = oracle.load_names(None, lexicon.IMPOSTORS)

    sets = {"full": all_places, "rewritable_impostors": rw_only}
    pooled: dict[str, list[float]] = {name: [] for name in sets}
    stats: dict[str, dict] = {}
    dumps_per_size: dict[str, int] = {}
    for spec in args.model:
        size, _, d = spec.partition("=")
        model = oracle.Model.load(size, Path(d))
        dumps = []
        for json_path in sorted(args.dumps.glob(f"*.{size}.*.json")):
            dump = oracle.load_dump(json_path)
            lab = labels.get(dump.clip)
            if lab is None:
                continue
            dumps.append((dump, lab["gold"]))
        dumps_per_size[size] = len(dumps)
        for name, places in sets.items():
            set_stats, per_dump = time_dumps(model, places, impostors, dumps)
            stats.setdefault(name, set_stats)
            pooled[name].extend(per_dump)

    summary = {
        "beta": BETA,
        "dumps_per_size": dumps_per_size,
        "dumps_pooled": len(pooled["full"]),
        "candidate_sets": {
            name: {
                **stats[name],
                "p50_ms": round(nearest_rank(pooled[name], 0.50), 1),
                "p95_ms": round(nearest_rank(pooled[name], 0.95), 1),
                "max_ms": round(max(pooled[name]), 1),
            }
            for name in sets
        },
    }
    out = json.dumps(summary, ensure_ascii=False, indent=1)
    print(out)
    if args.out:
        args.out.write_text(out + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
