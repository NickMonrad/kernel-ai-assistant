#!/usr/bin/env python3
"""Generate a PR comment Markdown summary from one or more normalised test
evidence JSON files produced by summarise_test_report.py.

Usage:

    python3 scripts/generate_test_pr_summary.py \
      --input scripts/test-reports/normalised/pr-1116/*_evidence.json \
      --pr 1116 \
      --out-md scripts/test-reports/normalised/pr-1116/pr_summary.md

    python3 scripts/generate_test_pr_summary.py \
      --input /tmp/evidence-s23/*_evidence.json /tmp/evidence-ci/*_evidence.json \
      --pr 1116 \
      --out-md /tmp/pr_comment.md
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import defaultdict
from pathlib import Path
from typing import Any


# ── Helpers ──────────────────────────────────────────────────────────────────
import glob as glob_mod


def load_reports(patterns: list[str]) -> list[dict]:
    """Load and validate all normalised evidence JSON files matching patterns."""
    seen: set[str] = set()
    reports: list[dict] = []
    for pat in patterns:
        for p in sorted(glob_mod.glob(pat, recursive=True)):
            resolved = Path(p).resolve()
            if resolved in seen:
                continue
            seen.add(resolved)
            try:
                with open(p) as f:
                    data = json.load(f)
            except (json.JSONDecodeError, OSError) as e:
                print(f"Warning: skipping {p}: {e}", file=sys.stderr)
                continue
            # Basic validation — it must look like a normalised report
            if not isinstance(data, dict) or "source" not in data:
                print(f"Warning: {p} is not a normalised evidence file", file=sys.stderr)
                continue
            reports.append(data)
    if not reports:
        print("Error: no valid normalised evidence files found", file=sys.stderr)
        sys.exit(1)
    return reports


def _fmt(val: Any) -> str:
    """Format a value for table cells — None/empty → —."""
    if val is None or val == "" or val == []:
        return "—"
    return str(val)


def _tier_label(tier: str) -> str:
    """Human-readable tier interpretation."""
    labels = {
        "reference": "reference ⚠️",
        "tracked": "tracked 📊",
        "experimental": "experimental ℹ️",
        "ci": "ci",
    }
    return labels.get(tier, tier)


def _tier_note(tier: str) -> str:
    notes = {
        "reference": "Potentially blocking — release signal.",
        "tracked": "Warning — reliability signal.",
        "experimental": "Informational — not considered for release decisions.",
        "ci": "CI environment — not a physical device.",
    }
    return notes.get(tier, "")


def _tier_emoji(tier: str) -> str:
    return {"reference": "⚠️", "tracked": "📊", "experimental": "ℹ️", "ci": "🤖"}.get(tier, "")


# ── Grouping ─────────────────────────────────────────────────────────────────


def group_reports(reports: list[dict]) -> dict[str, list[dict]]:
    """Group reports by source (ci / on_device)."""
    groups: dict[str, list[dict]] = defaultdict(list)
    for r in reports:
        groups[r["source"]].append(r)
    return dict(groups)


# ── Failure breakdown ────────────────────────────────────────────────────────


def failure_breakdown(reports: list[dict]) -> dict[str, int]:
    """Aggregate failure_category counts across reports."""
    counts: dict[str, int] = defaultdict(int)
    for r in reports:
        for c in r.get("cases", []):
            cat = c.get("failure_category")
            if cat:
                counts[cat] += 1
    return dict(counts)


# ── Device table ─────────────────────────────────────────────────────────────


def device_table(reports: list[dict]) -> list[str]:
    """Render a per-device result table for a list of reports.

    Reports with the same source, suite, device, commit, and run_id are
    deduped to avoid repeating identical runs loaded twice.
    """
    rows: list[tuple[Any, ...]] = []
    seen: set[tuple[str, str, str, str, str]] = set()
    for r in reports:
        dev = r.get("device", {})
        did = dev.get("id", "?")
        commit = r.get("commit", "?")
        key = (
            r.get("source", "?"),
            r.get("suite", "?"),
            did,
            commit,
            str(r.get("run_id", "") or ""),
        )
        if key in seen:
            continue
        seen.add(key)
        s = r.get("summary", {})
        total = s.get("total", 0)
        passed = s.get("passed", 0)
        failed = s.get("failed", 0)
        tier = dev.get("tier", "?")
        soc = dev.get("soc", "?")
        api = dev.get("android_api")
        api_str = str(api) if api is not None else "—"
        mod = r.get("model", {})
        model_name = mod.get("name") or "—"
        runtime = mod.get("runtime") or "—"
        backend = mod.get("backend") or "—"
        rows.append(
            (
                _tier_emoji(tier),
                did,
                dev.get("label", "?"),
                tier,
                soc,
                api_str,
                model_name,
                runtime,
                backend,
                commit[:10],
                total,
                passed,
                failed,
            )
        )

    # Sort: reference first, then tracked, then experimental, then ci; then by device label
    tier_order = {"reference": 0, "tracked": 1, "experimental": 2, "ci": 3}
    rows.sort(key=lambda r: (tier_order.get(r[3], 99), r[2]))

    lines = [
        "|   | Device | Label | Tier | SoC | API | Model | Runtime | Backend | Commit | Total | ✅ | ❌ |",
        "|---|---|---|---|---|---|---|---|---|---|---|---|---|",
    ]
    for r in rows:
        lines.append(
            f"| {r[0]} | {r[1]} | {r[2]} | {r[3]} | {r[4]} | {r[5]} "
            f"| {r[6]} | {r[7]} | {r[8]} | `{r[9]}` | {r[10]} | {r[11]} | {r[12]} |"
        )
    return lines


# ── Failure category table ───────────────────────────────────────────────────


def failure_table(breakdown: dict[str, int]) -> list[str]:
    """Render a failure-category breakdown table."""
    if not breakdown:
        return ["No failures recorded."]
    items = sorted(breakdown.items(), key=lambda x: -x[1])
    lines = [
        "| Failure Category | Count |",
        "|---|---|",
    ]
    for cat, count in items:
        lines.append(f"| {cat} | {count} |")
    return lines


# ── Main ─────────────────────────────────────────────────────────────────────


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Generate a PR comment Markdown summary from normalised "
                    "test evidence JSON files."
    )
    parser.add_argument(
        "--input", required=True, nargs="+",
        help="One or more normalised evidence JSON files or glob patterns",
    )
    parser.add_argument("--pr", required=True, type=int, help="PR number")
    parser.add_argument(
        "--out-md", required=True,
        help="Output path for generated PR comment Markdown",
    )
    args = parser.parse_args()

    reports = load_reports(args.input)
    groups = group_reports(reports)

    # ── Collect unique suites ────────────────────────────────────────────────
    suites: list[str] = sorted({r.get("suite", "?") for r in reports})

    # ── Build output ─────────────────────────────────────────────────────────
    lines: list[str] = []
    lines.append("<!-- test-evidence-summary -->")
    lines.append("")
    lines.append(f"## Test Evidence — PR #{args.pr}")
    lines.append("")
    lines.append(
        f"**Suites:** {', '.join(suites)}"
    )
    lines.append(
        f"**Reports:** {len(reports)} normalised evidence file(s)"
    )
    lines.append("")

    # ── CI results section ───────────────────────────────────────────────────
    ci_reports = groups.get("ci", [])
    ondev_reports = groups.get("on_device", [])

    if ci_reports:
        lines.append("### CI results")
        lines.append("")
        lines.append("*CI environment — not physical-device evidence.*")
        lines.append("")
        lines.extend(device_table(ci_reports))
        lines.append("")
        fb = failure_breakdown(ci_reports)
        if fb:
            lines.append("**Failure breakdown:**")
            lines.append("")
            lines.extend(failure_table(fb))
            lines.append("")
    else:
        lines.append("### CI results")
        lines.append("")
        lines.append("No CI result summaries were included.")
        lines.append("")

    if ondev_reports:
        lines.append("### On-device results")
        lines.append("")
        lines.append("*Physical-device test evidence.*")
        lines.append("")
        lines.extend(device_table(ondev_reports))
        lines.append("")
        fb = failure_breakdown(ondev_reports)
        if fb:
            lines.append("**Failure breakdown:**")
            lines.append("")
            lines.extend(failure_table(fb))
            lines.append("")
    else:
        lines.append("### On-device results")
        lines.append("")
        lines.append("No physical-device results were included.")
        lines.append("")

    # ── Tier interpretation ─────────────────────────────────────────────────
    tiers_present = set()
    for r in reports:
        t = r.get("device", {}).get("tier")
        if t:
            tiers_present.add(t)

    if tiers_present:
        lines.append("### Interpretation")
        lines.append("")
        for t in ["reference", "tracked", "experimental", "ci"]:
            if t in tiers_present:
                lines.append(f"- **{_tier_label(t)}:** {_tier_note(t)}")
        lines.append("")
        lines.append(
            "> **Note:** This summary is informational. "
            "No merge gates are enforced at this stage."
        )

    # ── Write output ─────────────────────────────────────────────────────────
    out = Path(args.out_md)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(lines) + "\n")
    print(f"PR summary written to {out}")



if __name__ == "__main__":
    main()
