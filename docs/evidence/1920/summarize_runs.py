#!/usr/bin/env python3
"""Issue #1920: medians and spread per build and query kind from runs.txt (parse_run.py lines).

usage: summarize_runs.py <runs.txt>   Warm-up runs and runs without a first token are excluded.
"""
import statistics
import sys
from collections import defaultdict

METRICS = [
    "rebuilds", "t0_to_accepted", "accepted_to_gen", "t0_to_gen", "litert_ttft_ms",
    "t0_to_first_output", "thinking_chars", "engine_ttft", "t0_to_first_token",
]


def main() -> None:
    groups = defaultdict(list)
    for line in open(sys.argv[1]):
        if "label=" not in line:
            continue
        row = dict(kv.split("=", 1) for kv in line.split() if "=" in kv)
        if row["label"].endswith("warmup") or "error" in row:
            continue
        groups[(row["kind"], row["build"])].append(row)
    print("| query | build | n | metric | median | min | max | IQR |")
    print("|---|---|---|---|---|---|---|---|")
    for (kind, build), rows in sorted(groups.items()):
        for metric in METRICS:
            values = sorted(float(r[metric]) for r in rows if r.get(metric, "NA") != "NA")
            if not values:
                continue
            q = statistics.quantiles(values, n=4) if len(values) >= 2 else [values[0]] * 3
            print(
                f"| {kind} | {build} | {len(values)} | {metric} | {statistics.median(values):.0f} "
                f"| {values[0]:.0f} | {values[-1]:.0f} | {q[0]:.0f}–{q[2]:.0f} |"
            )


if __name__ == "__main__":
    main()
