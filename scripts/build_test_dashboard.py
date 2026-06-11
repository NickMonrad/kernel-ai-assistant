#!/usr/bin/env python3
"""Static test-evidence dashboard builder.

Scans a checkout of the ``test-results`` branch, aggregates evidence, and
generates a static HTML dashboard + JSON data files.

Usage::

    git clone --branch test-results git@github.com:NickMonrad/kernel-ai-assistant.git /tmp/test-results
    ./scripts/build_test_dashboard.py --results-dir /tmp/test-results/results --out-dir _site
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

HERE = Path(__file__).resolve().parent

REQUIRED_EVIDENCE_FIELDS = frozenset({
    "schema_version", "source", "suite", "timestamp", "repo",
    "branch", "commit", "pr", "release", "run_id",
    "device", "model", "summary", "cases",
})

CI_LABEL = "CI / static runner"
ON_DEVICE_LABEL = "On-device / physical"

SOURCE_LABELS = {"ci": CI_LABEL, "on_device": ON_DEVICE_LABEL}

DEVICE_REFERENCE = HERE.parent / "scripts" / "testdata" / "devices.yaml"

# Passthrough fields preserved in data exports
PASSTHROUGH = {"source", "suite", "timestamp", "commit", "pr", "release", "run_id"}


# ---------------------------------------------------------------------------
# Evidence loading
# ---------------------------------------------------------------------------

def _load_evidence(path: Path) -> dict | None:
    """Load and validate a single evidence JSON file.  Returns ``None`` on
    any structural or required-field violation (warning printed to stderr)."""
    try:
        raw = json.loads(path.read_text())
    except (json.JSONDecodeError, OSError) as exc:
        print(f"[WARN] {path}: {exc}", file=sys.stderr)
        return None

    if not isinstance(raw, dict):
        print(f"[WARN] {path}: not a JSON object", file=sys.stderr)
        return None

    missing = REQUIRED_EVIDENCE_FIELDS - set(raw)
    if missing:
        print(
            f"[WARN] {path}: missing required fields: {', '.join(sorted(missing))}",
            file=sys.stderr,
        )
        return None

    src = raw.get("source")
    if src not in SOURCE_LABELS:
        print(f"[WARN] {path}: unknown source {src!r}", file=sys.stderr)
        return None

    return raw


def _discover_results(results_dir: Path) -> list[dict]:
    """Walk ``results_dir`` and return a list of parsed evidence dicts."""
    evidence: list[dict] = []
    for json_path in sorted(results_dir.rglob("*.json")):
        rec = _load_evidence(json_path)
        if rec is not None:
            rec["_source_relpath"] = str(json_path.relative_to(results_dir))
            evidence.append(rec)
    return evidence


# ---------------------------------------------------------------------------
# Aggregation helpers
# ---------------------------------------------------------------------------

def _safe_int(val, default: int = 0) -> int:
    if isinstance(val, int):
        return val
    if isinstance(val, float):
        return int(val)
    return default


def _safe_float(val, default: float = 0.0) -> float:
    if isinstance(val, (int, float)):
        return float(val)
    return default


def _pr_label(pr_val: int | None) -> str:
    return f"PR #{pr_val}" if pr_val is not None else "—"


def _device_id(rec: dict) -> str:
    """Extract device id, falling back to a safe default."""
    device = rec.get("device")
    if isinstance(device, dict):
        return str(device.get("id", "unknown"))
    return "unknown"


def _suite_name(rec: dict) -> str:
    return str(rec.get("suite", "unknown"))


def _source_label(source: str) -> str:
    return SOURCE_LABELS.get(source, source)


def _truncate_sha(sha: str, length: int = 12) -> str:
    return sha[:length] if sha and len(sha) >= length else sha


def _iso_short(ts: str) -> str:
    """Short friendly date from ISO 8601."""
    if not ts:
        return "—"
    # Keep date + hour
    m = re.match(r"(\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2})", ts)
    return m.group(1) if m else ts[:16]


# ---------------------------------------------------------------------------
# Rendering helpers
# ---------------------------------------------------------------------------

def _status_badge(pass_rate: float, total: int) -> str:
    """Render a badge for a known evidence run.

    ``total=0`` returns a neutral badge (no evidence).
    """
    if total == 0:
        return '<span class="badge neutral">No evidence</span>'
    if pass_rate >= 1.0:
        return '<span class="badge pass">PASS</span>'
    if pass_rate >= 0.8:
        return '<span class="badge warn">WARN</span>'
    return '<span class="badge fail">FAIL</span>'


def _result_cell(summary: dict) -> str:
    """Render a result table cell from an evidence summary.

    Handles empty (no evidence), full pass, and fail states.
    """
    total = _safe_int(summary.get("total"))
    passed = _safe_int(summary.get("passed"))
    if total == 0:
        return '<span class="badge neutral">No evidence</span>'
    badge = _status_badge(summary.get("pass_rate", 0.0), total)
    return f"{badge} {passed}/{total}"


def _scope_label(rec: dict) -> str:
    """Return a human-readable scope label for a latest-result card.

    Priority order: PR number, release/baseline name, 'Unscoped evidence'.
    """
    pr = rec.get("pr")
    if isinstance(pr, int):
        return _pr_label(pr)
    release = rec.get("release")
    if isinstance(release, str) and release:
        return release
    return "Unscoped evidence"


def _evidence_scope(rec: dict) -> str:
    """Return a short scope tag for display on cards and headers.

    Returns one of: 'PR #N', the release name, 'baseline', or 'unscoped'.
    """
    pr = rec.get("pr")
    if isinstance(pr, int):
        return _pr_label(pr)
    release = rec.get("release")
    if isinstance(release, str) and release:
        return release
    return "baseline"
def _scope_info(rec: dict) -> tuple[str, int | str | None, str]:
    """Return normalized scope metadata for grouping and display."""
    pr = rec.get("pr")
    if isinstance(pr, int):
        return ("pr", pr, _pr_label(pr))
    release = rec.get("release")
    if isinstance(release, str) and release:
        return ("release", release, f"Release {release}")
    return ("unscoped", None, "Unscoped evidence")


def _scope_sort_key(scope_type: str, scope_value: int | str | None, scope_label: str) -> tuple[int, int, str]:
    """Sort PR scopes first, then releases, then unscoped."""
    if scope_type == "pr":
        return (0, -scope_value if isinstance(scope_value, int) else 0, scope_label)
    if scope_type == "release":
        return (1, 0, scope_label)
    return (2, 0, scope_label)
def _source_links(results_url_base: str, latest_relpath: str, all_relpaths: list[str], record_count: int) -> str:
    """Render source evidence file links for a suite/scope row.

    Single-record rows get a direct link. Multi-record rows get a ``<details>``
    element listing all contributing files.
    """
    if not results_url_base or not latest_relpath:
        return ""
    latest_url = f"{results_url_base}/{latest_relpath}"
    if record_count <= 1:
        return f'<a href="{latest_url}" target="_blank" rel="noopener" class="source-link">View JSON</a>'
    # Multi-record: show all source files
    files_html = "".join(
        f'<li><a href="{results_url_base}/{p}" target="_blank" rel="noopener" class="source-link">{p}</a></li>'
        for p in all_relpaths
    )
    return (
        f'<details class="source-details">'
        f'<summary class="source-summary">View JSON · {record_count} source files</summary>'
        f'<ol class="source-file-list">{files_html}</ol>'
        f'</details>'
    )


# ---------------------------------------------------------------------------
# Data aggregation
# ---------------------------------------------------------------------------

def _build_aggregates(evidence: list[dict]) -> dict:
    """Build aggregated data structures from flat evidence list."""
    # Latest per source
    latest_by_source: dict[str, dict | None] = {"ci": None, "on_device": None}

    # Group by PR, release, device
    pr_map: dict[int, list[dict]] = {}
    release_map: dict[str, list[dict]] = {}
    device_map: dict[str, list[dict]] = {}

    for rec in evidence:
        src = rec.get("source", "unknown")
        ts = rec.get("timestamp", "")

        # Latest per source
        cur = latest_by_source.get(src)
        if cur is None or ts > cur.get("timestamp", ""):
            latest_by_source[src] = rec

        # PR
        pr = rec.get("pr")
        if isinstance(pr, int):
            pr_map.setdefault(pr, []).append(rec)

        # Release
        rel = rec.get("release")
        if isinstance(rel, str) and rel:
            release_map.setdefault(rel, []).append(rec)

        # Device
        did = _device_id(rec)
        device_map.setdefault(did, []).append(rec)

    # Sort PRs descending
    sorted_prs = sorted(pr_map.items(), key=lambda x: x[0], reverse=True)
    sorted_releases = sorted(release_map.items(), key=lambda x: x[0], reverse=True)

    prs_data: list[dict] = []
    for pr_num, recs in sorted_prs:
        ci_recs = [r for r in recs if r.get("source") == "ci"]
        od_recs = [r for r in recs if r.get("source") == "on_device"]

        # Latest evidence record per source (by timestamp)
        ci_latest = max(ci_recs, key=lambda r: r.get("timestamp", "")) if ci_recs else None
        od_latest = max(od_recs, key=lambda r: r.get("timestamp", "")) if od_recs else None

        # Use the latest record's summary for the main result (not merged across all records)
        ci_latest_summary = ci_latest.get("summary", {}) if ci_latest else {}
        od_latest_summary = od_latest.get("summary", {}) if od_latest else {}

        ci_commit = str(ci_latest.get("commit", "")) if ci_latest else ""
        od_commit = str(od_latest.get("commit", "")) if od_latest else ""
        ts_ci = ci_latest.get("timestamp", "") if ci_latest else ""
        ts_od = od_latest.get("timestamp", "") if od_latest else ""

        # Merged summary kept as separate aggregate field for reference
        ci_merged = _merge_summaries(ci_recs)
        od_merged = _merge_summaries(od_recs)

        # Determine mixed-commit status
        has_mixed_commits = (
            bool(ci_recs) and bool(od_recs)
            and ci_commit and od_commit
            and ci_commit != od_commit
        )
        if has_mixed_commits:
            pr_commit = ""
        elif ci_latest:
            pr_commit = ci_commit
        else:
            pr_commit = od_commit

        prs_data.append({
            "pr": pr_num,
            "commit": pr_commit,
            "ci": {
                "count": len(ci_recs),
                "total": ci_latest_summary.get("total", 0),
                "passed": ci_latest_summary.get("passed", 0),
                "failed": ci_latest_summary.get("failed", 0),
                "pass_rate": ci_latest_summary.get("pass_rate", 0.0),
                "latest": ts_ci,
                "commit": ci_commit,
            },
            "on_device": {
                "count": len(od_recs),
                "total": od_latest_summary.get("total", 0),
                "passed": od_latest_summary.get("passed", 0),
                "failed": od_latest_summary.get("failed", 0),
                "pass_rate": od_latest_summary.get("pass_rate", 0.0),
                "latest": ts_od,
                "commit": od_commit,
            },
            "has_mixed_commits": has_mixed_commits,
            "aggregate_summary": {
                "ci": ci_merged,
                "on_device": od_merged,
            },
        })

    # Per-release aggregates
    releases_data: list[dict] = []
    for rel, recs in sorted_releases:
        ci_recs = [r for r in recs if r.get("source") == "ci"]
        od_recs = [r for r in recs if r.get("source") == "on_device"]
        ci_latest_ts = max((r.get("timestamp", "") for r in ci_recs), default="") if ci_recs else ""
        od_latest_ts = max((r.get("timestamp", "") for r in od_recs), default="") if od_recs else ""
        ts_all = max(ci_latest_ts, od_latest_ts)
        releases_data.append({
            "release": rel,
            "ci_count": len(ci_recs),
            "on_device_count": len(od_recs),
            "latest": ts_all,
            "ci_latest": ci_latest_ts,
            "od_latest": od_latest_ts,
            "ci_summary": _merge_summaries(ci_recs),
            "od_summary": _merge_summaries(od_recs),
        })

    # Per-device aggregates
    devices_data: list[dict] = []
    dev_tier_ordered = {"reference": 0, "tracked": 1, "experimental": 2, "ci": 3}
    sorted_devices = sorted(
        device_map.items(),
        key=lambda x: (dev_tier_ordered.get(
            x[1][0].get("device", {}).get("tier", ""), 9
        ), x[0]),
    )
    for did, recs in sorted_devices:
        dev_info = recs[0].get("device", {})
        latest_rec = max(recs, key=lambda r: r.get("timestamp", ""))
        merge = _merge_summaries(recs)
        suites = sorted({_suite_name(r) for r in recs})
        # Recent failures per category
        failures_by_cat: dict[str, int] = {}
        for r in recs:
            for c in r.get("cases", []):
                cat = c.get("failure_category")
                if cat:
                    failures_by_cat[cat] = failures_by_cat.get(cat, 0) + 1

        suite_scope_map: dict[tuple[str, str, int | str | None, str], list[dict]] = {}
        for r in recs:
            scope_type, scope_value, scope_label = _scope_info(r)
            suite_scope_key = (_suite_name(r), scope_type, scope_value, scope_label)
            suite_scope_map.setdefault(suite_scope_key, []).append(r)

        suite_breakdown: list[dict] = []
        for (suite_name, scope_type, scope_value, scope_label), group_recs in suite_scope_map.items():
            latest_group_rec = max(group_recs, key=lambda r: r.get("timestamp", ""))
            latest_summary = latest_group_rec.get("summary", {})
            if not isinstance(latest_summary, dict):
                latest_summary = {}
            historical_summary = _merge_summaries(group_recs)
            all_source_paths = sorted(set(
                r.get("_source_relpath", "") for r in group_recs if r.get("_source_relpath")
            ))
            suite_breakdown.append({
                "suite": suite_name,
                "source": latest_group_rec.get("source", "unknown"),
                "scope_type": scope_type,
                "scope_value": scope_value,
                "scope_label": scope_label,
                "timestamp": latest_group_rec.get("timestamp", ""),
                "commit": latest_group_rec.get("commit", ""),
                "record_count": len(group_recs),
                "total": _safe_int(latest_summary.get("total")),
                "passed": _safe_int(latest_summary.get("passed")),
                "failed": _safe_int(latest_summary.get("failed")),
                "pass_rate": _safe_float(latest_summary.get("pass_rate")),
                "historical_total": historical_summary["total"],
                "historical_passed": historical_summary["passed"],
                "historical_failed": historical_summary["failed"],
                "historical_pass_rate": historical_summary["pass_rate"],
                "latest_source_path": latest_group_rec.get("_source_relpath", ""),
                "all_source_paths": all_source_paths,
            })
        suite_breakdown.sort(
            key=lambda row: (
                *_scope_sort_key(
                    str(row["scope_type"]),
                    row.get("scope_value"),
                    str(row["scope_label"]),
                ),
                -_safe_int(row.get("total")),
                str(row["suite"]),
            )
        )

        scope_map: dict[tuple[str, int | str | None, str], list[dict]] = {}
        for row in suite_breakdown:
            scope_key = (
                str(row["scope_type"]),
                row.get("scope_value"),
                str(row["scope_label"]),
            )
            scope_map.setdefault(scope_key, []).append(row)

        scope_breakdown: list[dict] = []
        for (scope_type, scope_value, scope_label), suite_rows in scope_map.items():
            latest_suite_row = max(suite_rows, key=lambda row: str(row.get("timestamp", "")))
            total = sum(_safe_int(row.get("total")) for row in suite_rows)
            passed = sum(_safe_int(row.get("passed")) for row in suite_rows)
            failed = sum(_safe_int(row.get("failed")) for row in suite_rows)
            pass_rate = (passed / total) if total > 0 else 0.0
            scope_breakdown.append({
                "scope_type": scope_type,
                "scope_value": scope_value,
                "scope_label": scope_label,
                "latest_timestamp": latest_suite_row.get("timestamp", ""),
                "latest_commit": latest_suite_row.get("commit", ""),
                "sources": sorted({str(row.get("source", "unknown")) for row in suite_rows}),
                "all_source_paths": sorted(set(
                    p for row in suite_rows for p in row.get("all_source_paths", [])
                )),
                "suite_rows": suite_rows,
                "suites": [str(row["suite"]) for row in suite_rows],
                "total": total,
                "passed": passed,
                "failed": failed,
                "pass_rate": round(pass_rate, 4),
            })
        scope_breakdown.sort(
            key=lambda row: _scope_sort_key(
                str(row["scope_type"]),
                row.get("scope_value"),
                str(row["scope_label"]),
            )
        )

        devices_data.append({
            "device_id": did,
            "label": dev_info.get("label", did),
            "tier": dev_info.get("tier", "unknown"),
            "source": latest_rec.get("source", "unknown"),
            "latest": latest_rec.get("timestamp", ""),
            "latest_commit": latest_rec.get("commit", ""),
            "suites": suites,
            "total": merge["total"],
            "passed": merge["passed"],
            "failed": merge["failed"],
            "pass_rate": merge["pass_rate"],
            "failures_by_category": failures_by_cat,
            "suite_breakdown": suite_breakdown,
            "scope_breakdown": scope_breakdown,
            "has_unscoped": any(str(row["scope_type"]) == "unscoped" for row in suite_breakdown),
        })

    return {
        "latest_by_source": latest_by_source,
        "history": sorted(evidence, key=lambda r: r.get("timestamp", ""), reverse=True),
        "prs": prs_data,
        "releases": releases_data,
        "devices": devices_data,
    }


def _merge_summaries(recs: list[dict]) -> dict:
    """Merge summary fields across multiple evidence records."""
    total = passed = failed = 0
    for r in recs:
        s = r.get("summary")
        if isinstance(s, dict):
            total += _safe_int(s.get("total"))
            passed += _safe_int(s.get("passed"))
            failed += _safe_int(s.get("failed"))
    pass_rate = (passed / total) if total > 0 else 0.0
    return {"total": total, "passed": passed, "failed": failed, "pass_rate": round(pass_rate, 4)}


# ---------------------------------------------------------------------------
# HTML generation
# ---------------------------------------------------------------------------

_CSS = """
* { box-sizing: border-box; margin: 0; padding: 0; }
body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; background: #0d1117; color: #c9d1d9; padding: 20px; }
a { color: #58a6ff; text-decoration: none; }
a:hover { text-decoration: underline; }
h1, h2, h3 { margin: 1em 0 0.5em; }
h1 { font-size: 1.6rem; border-bottom: 1px solid #30363d; padding-bottom: 0.3em; }
h2 { font-size: 1.3rem; }
h3 { font-size: 1.1rem; }
table { width: 100%; border-collapse: collapse; margin: 1em 0; }
th, td { padding: 8px 12px; text-align: left; border-bottom: 1px solid #21262d; }
th { background: #161b22; font-weight: 600; white-space: nowrap; }
tr:hover td { background: #1c2128; }
.badge { display: inline-block; padding: 2px 8px; border-radius: 12px; font-size: 0.8rem; font-weight: 600; white-space: nowrap; }
.pass { background: #1b3a2d; color: #3fb950; }
.warn { background: #3d2e00; color: #d29922; }
.fail { background: #3d1b1b; color: #f85149; }
.neutral { background: #21262d; color: #8b949e; }
.source-tag { display: inline-block; padding: 2px 8px; border-radius: 12px; font-size: 0.73rem; font-weight: 600; white-space: nowrap; }
.source-ci { background: #0d4194; color: #79c0ff; }
.source-od { background: #3d1b6e; color: #d2a8ff; }
.empty { color: #8b949e; font-style: italic; padding: 2em 0; }
.nav { margin: 1em 0; display: flex; gap: 12px; flex-wrap: wrap; }
.nav a { padding: 6px 16px; background: #21262d; border-radius: 6px; font-weight: 500; }
.nav a:hover { background: #30363d; }
.summary-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 16px; margin: 1em 0; }
.card { background: #161b22; border: 1px solid #30363d; border-radius: 8px; padding: 16px; }
.card .label { font-size: 0.8rem; color: #8b949e; }
.card .value { font-size: 1.4rem; font-weight: 700; margin-top: 4px; }
.card .meta { margin-top: 6px; font-size: 0.85rem; color: #c9d1d9; }
.card .scope-badge { display: inline-block; padding: 2px 8px; border-radius: 12px; font-size: 0.75rem; font-weight: 600; background: #1c2128; color: #8b949e; white-space: nowrap; }
.section { margin: 2em 0; }
.mixed-note { font-size: 0.8rem; color: #d29922; margin-top: 4px; }
.mixed-note code { font-size: 0.75rem; }
footer { margin-top: 3em; padding-top: 1em; border-top: 1px solid #30363d; font-size: 0.85rem; color: #8b949e; }

.device-note { margin: 0.8em 0 1.2em; color: #8b949e; font-size: 0.9rem; }
.warning-panel { margin: 1em 0; padding: 12px 14px; border: 1px solid #d29922; border-radius: 8px; background: rgba(210, 153, 34, 0.12); color: #f2cc60; }
.scope-chip { display: inline-block; padding: 2px 8px; border-radius: 12px; font-size: 0.73rem; font-weight: 600; white-space: nowrap; }
.scope-pr { background: #0d4194; color: #79c0ff; }
.scope-release { background: #1b4332; color: #7ee787; }
.scope-unscoped { background: #5a1e02; color: #ffa657; }
.table-note { margin-top: 4px; font-size: 0.8rem; color: #8b949e; }
.suite-name { font-weight: 500; }
.scope-breakdown { display: grid; grid-template-columns: repeat(auto-fit, minmax(260px, 1fr)); gap: 12px; margin: 1em 0 1.5em; }
.scope-card { background: #161b22; border: 1px solid #30363d; border-radius: 8px; padding: 14px; }
.scope-card.unscoped-card { border-color: #d29922; }
.scope-card-header { display: flex; gap: 10px; align-items: center; justify-content: space-between; }
.scope-card h4 { margin: 0; font-size: 1rem; word-break: break-word; }
.scope-meta { margin-top: 8px; color: #c9d1d9; font-size: 0.88rem; }
.suite-pills { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 10px; }
.suite-pill { display: inline-block; padding: 4px 8px; border-radius: 999px; background: #21262d; color: #c9d1d9; font-size: 0.8rem; }

/* Source evidence file links */
.source-link { font-size: 0.8rem; font-weight: 500; white-space: nowrap; }
.source-details { font-size: 0.8rem; }
.source-summary { cursor: pointer; color: #58a6ff; font-weight: 500; white-space: nowrap; }
.source-summary:hover { text-decoration: underline; }
.source-file-list { margin: 6px 0 0 20px; font-size: 0.78rem; }
.source-file-list li { margin: 3px 0; word-break: break-all; }
.evidence-cell { min-width: 110px; vertical-align: middle; }

/* Responsive: scrollable tables on narrow screens */
@media (max-width: 720px) {
  .pr-table-wrap { overflow-x: auto; -webkit-overflow-scrolling: touch; }
  table { font-size: 0.85rem; }
  th, td { padding: 6px 8px; }
  .summary-grid { grid-template-columns: 1fr; }
  .scope-breakdown { grid-template-columns: 1fr; }
  body { padding: 12px; }
  h1 { font-size: 1.3rem; }
  h2 { font-size: 1.1rem; }
  .source-tag { font-size: 0.68rem; padding: 1px 6px; }
  .scope-chip { font-size: 0.68rem; padding: 1px 6px; }
  .badge { font-size: 0.7rem; padding: 1px 6px; }
  .source-file-list { font-size: 0.72rem; }
}

@media (max-width: 480px) {
  table { font-size: 0.78rem; }
  th, td { padding: 4px 5px; }
  th { white-space: normal; }
  .badge { font-size: 0.65rem; padding: 1px 5px; }
  .source-tag { font-size: 0.63rem; padding: 1px 4px; }
  .scope-chip { font-size: 0.65rem; padding: 1px 4px; }
  .scope-card-header { align-items: flex-start; flex-direction: column; }
  .evidence-cell { min-width: 90px; }
}
"""


def _page(title: str, body: str, extra_head: str = "") -> str:
    return f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title} — Test Evidence Dashboard</title>
<style>
{_CSS}
</style>
{extra_head}
</head>
<body>
<div class="nav">
  <a href="index.html">Overview</a>
  <a href="prs.html">Pull Requests</a>
  <a href="devices.html">Devices</a>
  <a href="releases.html">Releases</a>
</div>
{body}
<footer>Generated by <code>build_test_dashboard.py</code></footer>
</body>
</html>"""


def _render_overview(data: dict) -> str:
    # Latest cards
    latest = data["latest_by_source"]
    cards = ""
    for src_key in ("ci", "on_device"):
        rec = latest.get(src_key)
        label = SOURCE_LABELS.get(src_key, src_key)
        if rec:
            s = rec.get("summary", {})
            scope = _evidence_scope(rec)
            device_label = rec.get("device", {}).get("label", "")
            cards += f"""<div class="card">
  <div class="label">{label} <span class="source-tag source-{"ci" if src_key=="ci" else "od"}">{src_key}</span></div>
  <div class="value">{_safe_int(s.get("passed"))}/{_safe_int(s.get("total"))} passed</div>
  <div class="meta">
    {_status_badge(_safe_float(s.get("pass_rate")), _safe_int(s.get("total")))} &nbsp;
    <span class="scope-badge">{scope}</span> &nbsp;
    {rec.get("suite","")}{" · " + device_label if device_label else ""} &nbsp;
    {_iso_short(rec.get("timestamp",""))}
  </div>
</div>"""
        else:
            cards += f"""<div class="card">
  <div class="label">{label}</div>
  <div class="value empty">No evidence</div>
</div>"""

    # Recent PRs
    pr_rows = ""
    for pr in data["prs"][:10]:
        ci = pr["ci"]
        od = pr["on_device"]
        mixed_note = ""
        commit_cell = f'<code>{_truncate_sha(pr["commit"])}</code>' if pr["commit"] else ""
        if pr["has_mixed_commits"]:
            mixed_note = (
                f'<div class="mixed-note">Mixed evidence commits · '
                f'CI <code>{_truncate_sha(ci["commit"])}</code> '
                f'On-device <code>{_truncate_sha(od["commit"])}</code></div>'
            )
        pr_rows += f"""<tr>
  <td><a href="prs.html#pr-{pr['pr']}">PR #{pr['pr']}</a></td>
  <td>{commit_cell}</td>
  <td>{_result_cell(ci)}</td>
  <td>{_result_cell(od)}</td>
  <td>{_iso_short(max(ci['latest'], od['latest']))}{mixed_note}</td>
</tr>"""
    if not pr_rows:
        pr_rows = '<tr><td colspan="5" class="empty">No PR evidence yet</td></tr>'

    # Recent releases
    rel_rows = ""
    for rel in data["releases"][:10]:
        rel_rows += f"""<tr>
  <td><a href="releases.html#release-{rel['release']}">{rel['release']}</a></td>
  <td>{_result_cell(rel['ci_summary'])}</td>
  <td>{_result_cell(rel['od_summary'])}</td>
  <td>{_iso_short(rel['latest'])}</td>
</tr>"""
    if not rel_rows:
        rel_rows = '<tr><td colspan="4" class="empty">No release evidence yet</td></tr>'

    # Device summary
    dev_rows = ""
    for dev in data["devices"]:
        dev_rows += f"""<tr>
  <td><a href="devices.html#device-{dev['device_id']}">{dev['label']}</a></td>
  <td>{dev['tier']}</td>
  <td>{', '.join(dev['suites'])}</td>
  <td>{_status_badge(dev['pass_rate'], dev['total'])} {dev['passed']}/{dev['total']}</td>
  <td>{_iso_short(dev['latest'])}</td>
</tr>"""
    if not dev_rows:
        dev_rows = '<tr><td colspan="5" class="empty">No device evidence yet</td></tr>'

    body = f"""<h1>Test Evidence Dashboard</h1>

<h2>Latest Results</h2>
<div class="summary-grid">{cards}</div>

<div class="section">
<h2>Recent Pull Requests</h2>
<div class="pr-table-wrap">
<table>
<thead><tr><th>PR</th><th>Commit</th><th>CI</th><th>On-device</th><th>Latest</th></tr></thead>
<tbody>{pr_rows}</tbody>
</table>
</div>
</div>

<div class="section">
<h2>Recent Releases</h2>
<div class="pr-table-wrap">
<table>
<thead><tr><th>Release</th><th>CI</th><th>On-device</th><th>Latest</th></tr></thead>
<tbody>{rel_rows}</tbody>
</table>
</div>
</div>

<div class="section">
<h2>Devices</h2>
<div class="pr-table-wrap">
<table>
<thead><tr><th>Device</th><th>Tier</th><th>Suites</th><th>Pass Rate</th><th>Latest</th></tr></thead>
<tbody>{dev_rows}</tbody>
</table>
</div>
</div>
"""
    return _page("Overview", body)


def _render_prs(data: dict) -> str:
    sections = ""
    for pr in data["prs"]:
        ci = pr["ci"]
        od = pr["on_device"]

        # Build header with commit info
        commit_part = ""
        if pr["has_mixed_commits"]:
            commit_part = f"""  <div class="mixed-note">Mixed evidence commits ·
    CI <code>{_truncate_sha(ci["commit"])}</code> ·
    On-device <code>{_truncate_sha(od["commit"])}</code></div>
  <div class="mixed-note">Evidence from different commits</div>"""
        elif pr["commit"]:
            commit_part = f"""  <div><code>{_truncate_sha(pr["commit"])}</code></div>"""

        sections += f"""<h2 id="pr-{pr['pr']}">PR #{pr['pr']}</h2>
{commit_part}
<div class="pr-table-wrap">
<table>
<thead><tr><th>Source</th><th>Runs</th><th>Passed</th><th>Failed</th><th>Total</th><th>Result</th><th>Latest</th></tr></thead>
<tbody>
<tr>
  <td><span class="source-tag source-ci">ci</span> {CI_LABEL}</td>
  <td>{ci['count']}</td>
  <td>{ci['passed']}</td>
  <td>{ci['failed']}</td>
  <td>{ci['total']}</td>
  <td>{_result_cell(ci)}</td>
  <td>{_iso_short(ci['latest'])}</td>
</tr>
<tr>
  <td><span class="source-tag source-od">on_device</span> {ON_DEVICE_LABEL}</td>
  <td>{od['count']}</td>
  <td>{od['passed']}</td>
  <td>{od['failed']}</td>
  <td>{od['total']}</td>
  <td>{_result_cell(od)}</td>
  <td>{_iso_short(od['latest'])}</td>
</tr>
</tbody>
</table>
</div>"""

    if not sections:
        sections = '<p class="empty">No PR evidence yet.</p>'

    return _page("Pull Requests", f"<h1>Pull Request Evidence</h1>{sections}")


def _render_devices(data: dict) -> str:
    sections = ""
    results_url_base = data.get("results_url_base", "")
    for dev in data["devices"]:
        # Failure breakdown
        fail_rows = ""
        if dev["failures_by_category"]:
            for cat, count in sorted(dev["failures_by_category"].items(), key=lambda x: -x[1]):
                fail_rows += f"<tr><td>{cat}</td><td>{count}</td></tr>\n"
        else:
            fail_rows = '<tr><td colspan="2" class="empty">No failures recorded</td></tr>'

        suite_rows = ""
        for row in dev["suite_breakdown"]:
            history_note = ""
            if row["record_count"] > 1:
                history_note = (
                    f'<div class="table-note">'
                    f'Latest of {row["record_count"]} records · '
                    f'historical merged {row["historical_passed"]}/{row["historical_total"]}'
                    f"</div>"
                )
            evidence_links = _source_links(
                results_url_base,
                str(row.get("latest_source_path", "")),
                [str(p) for p in row.get("all_source_paths", [])],
                row["record_count"],
            )
            suite_rows += f"""<tr>
  <td><div class="suite-name">{row['suite']}</div>{history_note}</td>
  <td><span class="source-tag source-{"ci" if row['source']=='ci' else 'od'}">{_source_label(str(row['source']))}</span></td>
  <td><span class="scope-chip scope-{row['scope_type']}">{row['scope_label']}</span></td>
  <td>{_iso_short(str(row['timestamp']))}</td>
  <td><code>{_truncate_sha(str(row['commit']))}</code></td>
  <td>{row['passed']}</td>
  <td>{row['failed']}</td>
  <td>{row['total']}</td>
  <td>{row['pass_rate']:.1%}</td>
  <td>{_status_badge(row['pass_rate'], row['total'])}</td>
  <td class="evidence-cell">{evidence_links}</td>
</tr>"""
        if not suite_rows:
            suite_rows = '<tr><td colspan="11" class="empty">No suite evidence available</td></tr>'

        scope_cards = ""
        for scope in dev["scope_breakdown"]:
            suite_pills = "".join(
                f'<span class="suite-pill">{suite_row["suite"]}</span>'
                for suite_row in scope["suite_rows"]
            )
            sources = ", ".join(_source_label(str(source)) for source in scope["sources"])
            warning_note = ""
            extra_class = ""
            if scope["scope_type"] == "unscoped":
                extra_class = " unscoped-card"
                warning_note = (
                    '<div class="table-note">'
                    'Unscoped evidence is a data-quality warning. '
                    'Backfill PR or release metadata if possible.'
                    '</div>'
                )
            scope_links = _source_links(
                results_url_base,
                str(scope.get("all_source_paths", [""])[0]) if scope.get("all_source_paths") else "",
                [str(p) for p in scope.get("all_source_paths", [])],
                len(scope["all_source_paths"]),
            )
            scope_cards += f"""<div class="scope-card{extra_class}">
  <div class="scope-card-header">
    <h4>{scope['scope_label']}</h4>
    {_status_badge(scope['pass_rate'], scope['total'])}
  </div>
  <div class="scope-meta">{scope['passed']}/{scope['total']} across {len(scope['suite_rows'])} suite(s)</div>
  <div class="scope-meta">Latest {_iso_short(str(scope['latest_timestamp']))} · Sources: {sources}</div>
  <div class="scope-meta">Latest commit <code>{_truncate_sha(str(scope['latest_commit']))}</code></div>
  <div class="scope-meta">{scope_links}</div>
  <div class="suite-pills">{suite_pills}</div>
  {warning_note}
</div>"""
        if not scope_cards:
            scope_cards = '<p class="empty">No scope breakdown available.</p>'

        unscoped_warning = ""
        if dev["has_unscoped"]:
            unscoped_warning = (
                '<div class="warning-panel">'
                'This device includes unscoped evidence. It is shown separately below so it does not silently blend into PR or release health signals.'
                '</div>'
            )

        sections += f"""<h2 id="device-{dev['device_id']}">{dev['label']} <code>{dev['device_id']}</code></h2>
<div class="pr-table-wrap">
<table>
<thead><tr><th>Property</th><th>Value</th></tr></thead>
<tbody>
<tr><td>Tier</td><td>{dev['tier']}</td></tr>
<tr><td>Source</td><td><span class="source-tag source-{"ci" if dev['source']=='ci' else 'od'}">{_source_label(dev['source'])}</span></td></tr>
<tr><td>Suites</td><td>{', '.join(dev['suites'])}</td></tr>
<tr><td>Merged Test Cases</td><td>{dev['total']} <span class="table-note">(total across all evidence, including historical)</span></td></tr>
<tr><td>Pass Rate (merged)</td><td>{_status_badge(dev['pass_rate'], dev['total'])} {dev['pass_rate']:.1%} ({dev['passed']}/{dev['total']}) <span class="table-note">(see suite/scope breakdown below)</span></td></tr>
<tr><td>Latest Run</td><td>{_iso_short(dev['latest'])}</td></tr>
<tr><td>Latest Commit</td><td><code>{_truncate_sha(dev['latest_commit'])}</code></td></tr>
</tbody>
</table>
</div>
<div class="device-note">The merged totals above aggregate all evidence for this device. Use the suite and scope breakdowns below to distinguish PR-scoped runs, release baselines, and unscoped evidence. Each suite row links to the source evidence JSON file(s) for drilldown.</div>
{unscoped_warning}

<h3>By suite</h3>
<div class="pr-table-wrap">
<table>
<thead><tr><th>Suite</th><th>Source</th><th>Scope</th><th>Latest</th><th>Commit</th><th>Passed</th><th>Failed</th><th>Total</th><th>Pass Rate</th><th>Status</th><th>Evidence</th></tr></thead>
<tbody>{suite_rows}</tbody>
</table>
</div>

<h3>By scope</h3>
<div class="scope-breakdown">{scope_cards}</div>

<h3>Failure Categories</h3>
<div class="pr-table-wrap">
<table>
<thead><tr><th>Category</th><th>Count</th></tr></thead>
<tbody>{fail_rows}</tbody>
</table>
</div>"""

    if not sections:
        sections = '<p class="empty">No device evidence yet.</p>'

    return _page("Devices", f"<h1>Device Evidence</h1>{sections}")


def _render_releases(data: dict) -> str:
    sections = ""
    for rel in data["releases"]:
        ci = rel["ci_summary"]
        od = rel["od_summary"]
        sections += f"""<h2 id="release-{rel['release']}">{rel['release']}</h2>
<div class="pr-table-wrap">
<table>
<thead><tr><th>Source</th><th>Runs</th><th>Passed</th><th>Failed</th><th>Total</th><th>Result</th><th>Latest</th></tr></thead>
<tbody>
<tr>
  <td><span class="source-tag source-ci">ci</span> {CI_LABEL}</td>
  <td>{rel['ci_count']}</td>
  <td>{ci['passed']}</td>
  <td>{ci['failed']}</td>
  <td>{ci['total']}</td>
  <td>{_result_cell(ci)}</td>
  <td>{_iso_short(rel['ci_latest']) if rel['ci_count'] > 0 else '—'}</td>
</tr>
<tr>
  <td><span class="source-tag source-od">on_device</span> {ON_DEVICE_LABEL}</td>
  <td>{rel['on_device_count']}</td>
  <td>{od['passed']}</td>
  <td>{od['failed']}</td>
  <td>{od['total']}</td>
  <td>{_result_cell(od)}</td>
  <td>{_iso_short(rel['od_latest']) if rel['on_device_count'] > 0 else '—'}</td>
</tr>
</tbody>
</table>
</div>"""

    if not sections:
        sections = '<p class="empty">No release evidence yet.</p>'

    return _page("Releases", f"<h1>Release Evidence</h1>{sections}")


# ---------------------------------------------------------------------------
# Data file generation
# ---------------------------------------------------------------------------

def _build_latest_json(aggregates: dict) -> dict:
    """Build ``latest.json`` — most recent CI + on-device record."""
    out: dict[str, dict | None] = {}
    for src_key in ("ci", "on_device"):
        rec = aggregates["latest_by_source"].get(src_key)
        if rec is None:
            out[src_key] = None
        else:
            out[src_key] = {
                k: rec[k] for k in PASSTHROUGH if k in rec
            } | {
                "summary": rec.get("summary"),
                "device": {
                    "id": _device_id(rec),
                    "label": rec.get("device", {}).get("label", ""),
                    "tier": rec.get("device", {}).get("tier", ""),
                },
            }
    return out  # type: ignore[return-value]


def _build_history_json(aggregates: dict) -> list[dict]:
    """Build ``history.json`` — all evidence, sorted newest-first."""
    out: list[dict] = []
    for rec in aggregates["history"]:
        out.append({
            k: rec[k] for k in PASSTHROUGH if k in rec
        } | {
            "summary": rec.get("summary"),
            "device_id": _device_id(rec),
            "device_label": rec.get("device", {}).get("label", ""),
            "device_tier": rec.get("device", {}).get("tier", ""),
        })
    return out


def _build_prs_json(aggregates: dict) -> list[dict]:
    return aggregates["prs"]


def _build_devices_json(aggregates: dict) -> list[dict]:
    return aggregates["devices"]


def _build_releases_json(aggregates: dict) -> list[dict]:
    return aggregates["releases"]


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def _parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Build static test-evidence dashboard from test-results branch.",
    )
    parser.add_argument(
        "--results-dir",
        type=str,
        required=True,
        help="Path to the results/ directory from a test-results checkout",
    )
    parser.add_argument(
        "--out-dir",
        type=str,
        default="_site",
        help="Output directory for generated dashboard (default: _site)",
    )
    parser.add_argument(
        "--results-url",
        type=str,
        default="",
        help="Base URL for source evidence files (e.g. https://github.com/owner/repo/blob/test-results/results). Auto-derived from evidence repo field if omitted.",
    )
    return parser.parse_args(argv)


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> None:
    args = _parse_args()
    results_dir = Path(args.results_dir)
    out_dir = Path(args.out_dir)
    data_dir = out_dir / "data"

    print(f"Results dir: {results_dir}")
    print(f"Output dir:  {out_dir}")

    # Load evidence
    evidence = _discover_results(results_dir)
    print(f"Loaded {len(evidence)} evidence record(s)")

    # Derive results URL base for source file links
    if args.results_url:
        results_url_base = args.results_url.rstrip("/")
    elif evidence:
        repo = evidence[0].get("repo", "NickMonrad/kernel-ai-assistant")
        results_url_base = f"https://github.com/{repo}/blob/test-results/results"
    else:
        results_url_base = ""

    # Aggregate
    aggregates = _build_aggregates(evidence)
    aggregates["results_url_base"] = results_url_base
    print(
        f"  {len(aggregates['prs'])} PR(s), "
        f"{len(aggregates['devices'])} device(s), "
        f"{len(aggregates['releases'])} release(s)"
    )

    # Build output directory structure
    data_dir.mkdir(parents=True, exist_ok=True)

    # Write HTML pages
    html_pages: dict[str, str] = {
        "index.html": _render_overview(aggregates),
        "prs.html": _render_prs(aggregates),
        "devices.html": _render_devices(aggregates),
        "releases.html": _render_releases(aggregates),
    }
    for name, content in html_pages.items():
        (out_dir / name).write_text(content)
        print(f"  {name} — {len(content)} bytes")

    # Write JSON data files
    json_data: dict[str, object] = {
        "latest.json": _build_latest_json(aggregates),
        "history.json": _build_history_json(aggregates),
        "prs.json": _build_prs_json(aggregates),
        "devices.json": _build_devices_json(aggregates),
        "releases.json": _build_releases_json(aggregates),
    }
    for name, obj in json_data.items():
        path = data_dir / name
        blob = json.dumps(obj, indent=2, default=str)
        path.write_text(blob)
        print(f"  data/{name} — {len(blob)} bytes")

    print(f"\nDashboard built in {out_dir.resolve()}")


if __name__ == "__main__":
    main()
