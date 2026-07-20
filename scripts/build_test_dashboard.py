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
import html
import json
import re
import sys
from pathlib import Path, PurePosixPath

from summarise_test_evidence_metrics import (  # type: ignore[import-untyped]
    DEVICE_REGISTRY,
    discover_evidence,
    summarise,
    validate_record,
)

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

HERE = Path(__file__).resolve().parent


CI_LABEL = "CI / static runner"
ON_DEVICE_LABEL = "On-device / physical"

SOURCE_LABELS = {"ci": CI_LABEL, "on_device": ON_DEVICE_LABEL}


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

    valid, issues = validate_record(raw, [])
    if not valid:
        print(
            f"[WARN] {path}: schema validation failed: {'; '.join(issues)}",
            file=sys.stderr,
        )
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
    """Extract and canonicalise the device id using the public registry aliases."""
    device = rec.get("device")
    if not isinstance(device, dict):
        return "unknown"
    raw_id = str(device.get("id", "unknown"))
    reference = DEVICE_REGISTRY.get(raw_id.lower())
    return str(reference.get("id", raw_id)) if reference else raw_id


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

def _artifact_href(results_url_base: str, source_path: str, artifact_path: str) -> str:
    """Resolve an artifact path relative to its evidence record directory."""
    source_parent = PurePosixPath(source_path).parent if source_path else PurePosixPath()
    relative_path = source_parent / PurePosixPath(artifact_path)
    if results_url_base:
        return f"{results_url_base.rstrip('/')}/{relative_path.as_posix()}"
    return relative_path.as_posix()

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

def _build_aggregates(evidence: list[dict], metrics: dict | None = None) -> dict:
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
        recorded_info = recs[0].get("device", {})
        reference_info = DEVICE_REGISTRY.get(did.lower(), {})
        dev_info = {**recorded_info, **reference_info}
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
            "latest_summary": latest_rec.get("summary", {}),
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

    # Compute metrics via the optional summariser module
    # (only when not already provided — caller may pass metrics that include
    #  invalid evidence from discover_evidence())
    if metrics is None and evidence:
        metrics_records = [
            (Path(r.get("_source_relpath", "unknown.json")), r, [])
            for r in evidence
        ]
        metrics = summarise(metrics_records)

    return {
        "latest_by_source": latest_by_source,
        "history": sorted(evidence, key=lambda r: r.get("timestamp", ""), reverse=True),
        "prs": prs_data,
        "releases": releases_data,
        "devices": devices_data,
        "metrics": metrics,
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




def _render_metrics_section(data: dict, results_url_base: str = "") -> str:
    """Render a compact reviewer-focused metrics section for the overview."""
    metrics = data.get("metrics")
    if not metrics or not isinstance(metrics, dict):
        return ""

    validity = metrics.get("validity", {})
    valid_count = _safe_int(validity.get("valid_records"))
    invalid_count = _safe_int(validity.get("invalid_records"))
    has_invalid = invalid_count > 0
    issue_buckets = validity.get("issue_buckets", {})

    # ── Validity card ──
    if has_invalid:
        validity_html = f"""<div class="card">
  <div class="label">Evidence Validity</div>
  <div class="value fail">{valid_count} valid / {invalid_count} invalid</div>
  <div class="meta">
    <span class="badge warn">ISSUES</span>
  </div>
</div>"""
    elif valid_count > 0:
        validity_html = f"""<div class="card">
  <div class="label">Evidence Validity</div>
  <div class="value pass">{valid_count} valid / 0 invalid</div>
</div>"""
    else:
        validity_html = ""

    # ── Failure buckets card ──
    fb = metrics.get("failure_buckets", {})
    if fb:
        fb_rows = "".join(
            f"<tr><td>{cat}</td><td>{cnt}</td></tr>\n"
            for cat, cnt in fb.items()
        )
        failure_html = f"""<div class="card">
  <div class="label">Failure Buckets</div>
  <table>
  <thead><tr><th>Category</th><th>Count</th></tr></thead>
  <tbody>{fb_rows}</tbody>
  </table>
</div>"""
    else:
        failure_html = f"""<div class="card">
  <div class="label">Failure Buckets</div>
  <div class="value pass">No failures</div>
</div>"""

    # ── Device context card ──
    by_device = metrics.get("by_device", {})
    if by_device:
        dev_rows = "".join(
            f"<tr>"
            f"<td><code>{did}</code></td>"
            f"<td>{d.get('label') or '—'}</td>"
            f"<td>{d.get('tier') or '—'}</td>"
            f"<td>{d.get('android_api') or '—'}</td>"
            f"<td>{d.get('source') or '—'}</td>"
            f"<td>{d.get('passed', 0)}/{d.get('total', 0)}</td>"
            f"</tr>\n"
            for did, d in sorted(by_device.items())
        )
        device_html = f"""<div class="card">
  <div class="label">Device Context</div>
  <table>
  <thead><tr><th>Device</th><th>Label</th><th>Tier</th><th>API</th><th>Source</th><th>Pass/Fail</th></tr></thead>
  <tbody>{dev_rows}</tbody>
  </table>
</div>"""
    else:
        device_html = ""

    # ── Stuck-mode warning ──
    stuck_html = ""
    stuck_mode = metrics.get("stuck_mode", [])
    if stuck_mode:
        stuck_items = "".join(
            f"<li>Actual tool <code>{item['actual_tool']}</code> appeared for "
            f"{item['expected_tool_count']} different expected tools "
            f"({', '.join(f'<code>{e}</code>' for e in item['different_expected_tools'])})</li>\n"
            for item in stuck_mode
        )
        stuck_html = f"""<div class="warning-panel">
  <strong>⚠️ Stuck-mode / cascade suspects</strong>
  <p style="margin-top:6px;font-size:0.88rem">
    The same wrong actual tool was observed across multiple different expected tools.
    This may indicate the model is stuck on a particular tool or intent.
    This is a suspect signal, not a definitive diagnosis.</p>
  <ul style="margin:8px 0 0 20px;font-size:0.85rem">{stuck_items}</ul>
</div>"""

    # ── Artifact links ──
    artifacts = metrics.get("artifacts", [])
    if artifacts:
        art_rows = ""
        for artifact in artifacts[:20]:
            artifact_path = str(artifact.get("path") or "")
            source_path = str(artifact.get("source_path") or "")
            href = _artifact_href(results_url_base, source_path, artifact_path)
            path_html = f'<a href="{html.escape(href)}"><code>{html.escape(artifact_path)}</code></a>'
            art_rows += (
                "<tr>"
                f"<td>{html.escape(str(artifact.get('suite') or '—'))}</td>"
                f"<td>{html.escape(str(artifact.get('device_id') or '—'))}</td>"
                f"<td>{html.escape(str(artifact.get('case') or '—'))}</td>"
                f"<td><code>{html.escape(str(artifact.get('field') or '—'))}</code></td>"
                f"<td>{path_html}</td>"
                "</tr>\n"
            )
        art_note = ""
        if len(artifacts) > 20:
            art_note = f'<div class="table-note">Showing first 20 of {len(artifacts)} artifacts</div>'
        artifact_html = f"""<div class="card">
  <div class="label">Artifacts</div>
  <table>
  <thead><tr><th>Suite</th><th>Device</th><th>Case</th><th>Type</th><th>Path</th></tr></thead>
  <tbody>{art_rows}</tbody>
  </table>
  {art_note}
</div>"""
    else:
        artifact_html = ""

    # ── Validity issue buckets (if invalid evidence exists) ──
    issue_rows = ""
    if has_invalid and issue_buckets:
        issue_rows = "".join(
            f"<tr><td><code>{issue}</code></td><td>{cnt}</td></tr>\n"
            for issue, cnt in sorted(issue_buckets.items())
        )
        issue_rows = f"""<div class="card">
  <div class="label">Validity Issue Buckets</div>
  <table>
  <thead><tr><th>Issue</th><th>Count</th></tr></thead>
  <tbody>{issue_rows}</tbody>
  </table>
</div>"""

    cards = "".join(filter(None, [validity_html, issue_rows, failure_html, stuck_html]))
    device_artifacts = "".join(filter(None, [device_html, artifact_html]))

    body = f"""<div class="section">
<h2>Reviewer Metrics</h2>
<div class="summary-grid">{cards}</div>
{device_artifacts}
</div>"""
    return body
def _render_wake_metrics_section(data: dict) -> str:
    """Render wake reliability counts, gates, classifications, and clock-safe timelines."""
    metrics = data.get("metrics")
    wake = metrics.get("wake_reliability") if isinstance(metrics, dict) else None
    if not isinstance(wake, dict):
        return ""
    overall = wake.get("overall", {})
    if _safe_int(overall.get("attempts")) == 0:
        return ""

    def esc(value: object) -> str:
        return html.escape(str(value))

    release = wake.get("release_gate", {})
    completion = wake.get("completion", {})
    release_records = _safe_int(release.get("records"))
    release_ok = release.get("latest_successful") is True
    if release_records == 0:
        release_class = "neutral"
        release_label = "NO RELEASE-GATE EVIDENCE"
    else:
        release_class = "pass" if release_ok else "fail"
        release_label = "LATEST PASS" if release_ok else "LATEST NOT RELEASE-READY"

    def bucket_rows(raw: object) -> str:
        if not isinstance(raw, dict):
            return ""
        return "".join(
            "<tr>"
            f"<td><code>{esc(key)}</code></td>"
            f"<td>{_safe_int(value.get('attempts'))}</td>"
            f"<td>{_safe_int(value.get('valid'))}</td>"
            f"<td>{_safe_int(value.get('passed'))}</td>"
            f"<td>{_safe_int(value.get('failed'))}</td>"
            f"<td>{_safe_int(value.get('invalid'))}</td>"
            f"<td>{_safe_float(value.get('pass_rate')):.1%}</td>"
            "</tr>"
            for key, value in raw.items()
            if isinstance(value, dict)
        )

    classification_rows = "".join(
        f"<tr><td><code>{esc(name)}</code></td><td>{_safe_int(count)}</td></tr>"
        for name, count in wake.get("failure_classifications", {}).items()
    ) or '<tr><td colspan="2">No classified failures</td></tr>'
    invalid_rows = "".join(
        f"<tr><td><code>{esc(name)}</code></td><td>{_safe_int(count)}</td></tr>"
        for name, count in wake.get("invalid_reasons", {}).items()
    ) or '<tr><td colspan="2">No invalid attempts</td></tr>'

    condition_rows = "".join(
        "<tr>"
        f"<td>{esc(item.get('device_id', '—'))}</td>"
        f"<td>{_safe_int(item.get('idle_seconds'))}</td>"
        f"<td><code>{esc(item.get('trial_type', '—'))}</code></td>"
        f"<td>{_safe_int(item.get('completed_positions'))}/{_safe_int(item.get('required_positions'))}</td>"
        f"<td>{_safe_int(item.get('attempted_positions'))}</td>"
        f"<td>{_safe_int(item.get('retry_attempts'))}</td>"
        f"<td>{_safe_int(item.get('valid_attempts'))}</td>"
        f"<td>{_safe_int(item.get('passed_attempts'))}</td>"
        f"<td>{_safe_int(item.get('failed_attempts'))}</td>"
        f"<td>{_safe_int(item.get('invalid_attempts'))}</td>"
        f"<td>{_safe_int(item.get('duplicate_valid_positions'))}</td>"
        f"<td>{_safe_int(item.get('missing_positions'))}</td>"
        "</tr>"
        for item in wake.get("completion_by_condition", [])
        if isinstance(item, dict)
    ) or '<tr><td colspan="12">No matrix condition data recorded</td></tr>'
    timing = wake.get("timing", {})
    timing = timing if isinstance(timing, dict) else {}
    timing_aggregate_rows = "".join(
        "<tr>"
        f"<td>{esc(item.get('device_id', '—'))}</td>"
        f"<td><code>{esc(item.get('metric', '—'))}</code></td>"
        f"<td>{_safe_int(item.get('sample_count'))}</td>"
        f"<td>{_safe_int(item.get('min_ms'))}</td>"
        f"<td>{_safe_int(item.get('p50_ms'))}</td>"
        f"<td>{_safe_int(item.get('p95_ms'))}</td>"
        f"<td>{_safe_int(item.get('max_ms'))}</td>"
        f"<td><code>{esc(item.get('clock_domain', '—'))}</code></td>"
        "</tr>"
        for item in timing.get("aggregates", [])
        if isinstance(item, dict)
    ) or '<tr><td colspan="8">No same-domain latency samples recorded</td></tr>'
    timing_sample_rows = "".join(
        "<tr>"
        f"<td>{esc(item.get('device_id', '—'))}</td>"
        f"<td>{esc(item.get('trial_id', '—'))}</td>"
        f"<td>{_safe_int(item.get('idle_seconds'))}</td>"
        f"<td><code>{esc(item.get('trial_type', '—'))}</code></td>"
        f"<td><code>{esc(item.get('metric', '—'))}</code></td>"
        f"<td>{_safe_int(item.get('duration_ms'))}</td>"
        f"<td><code>{esc(item.get('clock_domain', '—'))}</code></td>"
        "</tr>"
        for item in timing.get("samples", [])
        if isinstance(item, dict)
    ) or '<tr><td colspan="7">No same-domain latency samples recorded</td></tr>'

    attempt_rows: list[str] = []
    timeline_rows: list[str] = []
    for record in data.get("history", []):
        if record.get("suite") != "wake_word_acoustic_reliability":
            continue
        extension = record.get("wake_reliability", {})
        run_kind = extension.get("run_kind", "unknown") if isinstance(extension, dict) else "unknown"
        gate_mode = extension.get("gate_mode", "unknown") if isinstance(extension, dict) else "unknown"
        device_id = _device_id(record)
        for case in record.get("cases", []):
            if not isinstance(case, dict):
                continue
            environment = case.get("environment_summary", {})
            stable = environment.get("stable") if isinstance(environment, dict) else None
            classification = case.get("failure_classification") or case.get("invalid_reason") or "—"
            attempt_rows.append(
                "<tr>"
                f"<td>{esc(device_id)}</td><td>{esc(run_kind)}</td><td>{esc(gate_mode)}</td>"
                f"<td>{esc(case.get('required_position_id', case.get('name', '—')))}</td>"
                f"<td>{esc(case.get('attempt', '—'))}</td><td>{esc(case.get('status', '—'))}</td>"
                f"<td>{esc(classification)}</td><td>{esc(stable if stable is not None else '—')}</td>"
                "</tr>"
            )
            for timing_name, default_clock in (
                ("source_timing", "source_device_elapsed_realtime"),
                ("target_timing", "target_device_elapsed_realtime"),
            ):
                timing = case.get(timing_name)
                if not isinstance(timing, dict):
                    continue
                clock = timing.get("clock_domain", default_clock)
                for milestone, value in timing.items():
                    if milestone == "clock_domain" or not milestone.endswith("_monotonic_ms"):
                        continue
                    timeline_rows.append(
                        "<tr>"
                        f"<td>{esc(case.get('name', '—'))}</td><td>{esc(timing_name)}</td>"
                        f"<td>{esc(milestone)}</td><td>{esc(value)}</td><td>{esc(clock)}</td>"
                        "</tr>"
                    )

    return f"""<div class="section">
<h2>Acoustic Wake Reliability</h2>
<div class="summary-grid">
  <div class="card"><div class="label">Valid attempt outcomes (all modes)</div>
    <div class="value">{_safe_int(overall.get('attempts'))}</div>
    <div class="meta">{_safe_int(overall.get('valid'))} valid · {_safe_int(overall.get('invalid'))} invalid · {_safe_float(overall.get('pass_rate')):.1%} valid-attempt pass rate</div>
  </div>
  <div class="card"><div class="label">Latest release-gate record</div>
    <div class="value {release_class}">{release_label}</div>
    <div class="meta">Latest {_iso_short(str(release.get('latest_timestamp', '')))} · historical release-gate records: {_safe_int(release.get('successful'))} successful, {_safe_int(release.get('failed'))} failed · {_safe_int(release.get('provenance_unverified'))} provenance-unverified · {_safe_int(release.get('feasibility_only'))} feasibility-only</div>
  </div>
  <div class="card"><div class="label">Aggregate valid-matrix coverage</div>
    <div class="value">{_safe_int(completion.get('completed'))}/{_safe_int(completion.get('total_required'))}</div>
    <div class="meta">Across all schema-valid records · {_safe_int(completion.get('missing'))} required positions missing · {_safe_int(completion.get('duplicate_valid_positions'))} duplicate valid outcomes · {_safe_int(wake.get('off_matrix_attempts'))} off-matrix attempts</div>
  </div>
</div>
<h3>Valid Matrix Coverage by Condition</h3><div class="pr-table-wrap"><table>
<thead><tr><th>Device</th><th>Idle (s)</th><th>Trial type</th><th>Completed / required</th><th>Attempted positions</th><th>Retry attempts</th><th>Valid attempts</th><th>Passed</th><th>Failed</th><th>Invalid</th><th>Duplicate valid</th><th>Missing</th></tr></thead>
<tbody>{condition_rows}</tbody></table></div>
<h3>Same-domain Event Latency</h3><div class="pr-table-wrap"><table>
<thead><tr><th>Device</th><th>Metric</th><th>Samples</th><th>Min ms</th><th>P50 ms</th><th>P95 ms</th><th>Max ms</th><th>Clock domain</th></tr></thead>
<tbody>{timing_aggregate_rows}</tbody></table></div>
<details><summary>Per-attempt event latency</summary><div class="pr-table-wrap"><table>
<thead><tr><th>Device</th><th>Trial</th><th>Idle (s)</th><th>Trial type</th><th>Metric</th><th>Duration ms</th><th>Clock domain</th></tr></thead>
<tbody>{timing_sample_rows}</tbody></table></div></details>
<h3>By Device</h3><div class="pr-table-wrap"><table>
<thead><tr><th>Device</th><th>Attempts</th><th>Valid</th><th>Passed</th><th>Failed</th><th>Invalid</th><th>Valid pass rate</th></tr></thead>
<tbody>{bucket_rows(wake.get('by_device'))}</tbody></table></div>
<h3>By Run Kind</h3><div class="pr-table-wrap"><table>
<thead><tr><th>Run kind</th><th>Attempts</th><th>Valid</th><th>Passed</th><th>Failed</th><th>Invalid</th><th>Valid pass rate</th></tr></thead>
<tbody>{bucket_rows(wake.get('by_run_kind'))}</tbody></table></div>
<h3>By Gate Mode</h3><div class="pr-table-wrap"><table>
<thead><tr><th>Gate mode</th><th>Attempts</th><th>Valid</th><th>Passed</th><th>Failed</th><th>Invalid</th><th>Valid pass rate</th></tr></thead>
<tbody>{bucket_rows(wake.get('by_gate_mode'))}</tbody></table></div>
<div class="summary-grid">
  <div class="card"><div class="label">Failure Classifications</div><table><tbody>{classification_rows}</tbody></table></div>
  <div class="card"><div class="label">Invalid Reasons</div><table><tbody>{invalid_rows}</tbody></table></div>
</div>
<h3>Attempt Drill-down</h3><div class="pr-table-wrap"><table>
<thead><tr><th>Device</th><th>Run</th><th>Gate</th><th>Position</th><th>Attempt</th><th>Status</th><th>Classification</th><th>Environment stable</th></tr></thead>
<tbody>{''.join(attempt_rows)}</tbody></table></div>
<details><summary>Clock-domain event timeline</summary>
<p class="table-note">Times remain in their source or target device elapsed-realtime domain; no cross-device subtraction is performed.</p>
<div class="pr-table-wrap"><table><thead><tr><th>Attempt</th><th>Timing source</th><th>Milestone</th><th>Monotonic ms</th><th>Clock domain</th></tr></thead>
<tbody>{''.join(timeline_rows) or '<tr><td colspan="5">No timing milestones recorded</td></tr>'}</tbody></table></div></details>
</div>"""




def _render_overview(data: dict, results_url_base: str) -> str:
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
        latest_summary = dev.get("latest_summary", {})
        dev_rows += f"""<tr>
  <td><a href="devices.html#device-{dev['device_id']}">{dev['label']}</a></td>
  <td>{dev['tier']}</td>
  <td>{', '.join(dev['suites'])}</td>
  <td>{_result_cell(latest_summary)}<div class="table-note">{_iso_short(dev['latest'])}</div></td>
  <td>{_status_badge(dev['pass_rate'], dev['total'])} {dev['passed']}/{dev['total']}</td>
</tr>"""
    if not dev_rows:
        dev_rows = '<tr><td colspan="5" class="empty">No device evidence yet</td></tr>'

    metrics_section = _render_metrics_section(data, results_url_base)
    wake_metrics_section = _render_wake_metrics_section(data)

    body = f"""<h1>Test Evidence Dashboard</h1>

<h2>Latest Results</h2>
<div class="summary-grid">{cards}</div>

{metrics_section}
{wake_metrics_section}

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
<thead><tr><th>Device</th><th>Tier</th><th>Suites</th><th>Latest run</th><th>Historical aggregate</th></tr></thead>
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


def _build_metrics_json(aggregates: dict) -> dict | None:
    """Build ``metrics.json`` — evidence metrics summary block."""
    return aggregates.get("metrics")


def _build_json_data(aggregates: dict) -> dict[str, object]:
    """Build all JSON data dicts from aggregates for export."""
    metrics_data = _build_metrics_json(aggregates)
    json_data: dict[str, object] = {
        "latest.json": _build_latest_json(aggregates),
        "history.json": _build_history_json(aggregates),
        "prs.json": _build_prs_json(aggregates),
        "devices.json": _build_devices_json(aggregates),
        "releases.json": _build_releases_json(aggregates),
    }
    if metrics_data is not None:
        json_data["metrics.json"] = metrics_data
    return json_data


def _write_json_files(json_data: dict[str, object], data_dir: Path) -> None:
    """Write JSON data dicts to individual files in `data_dir`."""
    for name, obj in json_data.items():
        path = data_dir / name
        blob = json.dumps(obj, indent=2, default=str)
        path.write_text(blob)
        print(f"  data/{name} — {len(blob)} bytes")


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

    # Compute metrics via discover_evidence (preserves invalid/malformed records
    # for validity reporting — unlike _discover_results which filters them out)
    metrics: dict | None = None
    metrics_records = discover_evidence(results_dir)
    if metrics_records:
        metrics = summarise(metrics_records)
        valid = metrics.get("validity", {}).get("valid_records", 0)
        invalid = metrics.get("validity", {}).get("invalid_records", 0)
        if invalid:
            print(f"Metrics: {valid} valid / {invalid} invalid record(s)")
        else:
            print(f"Metrics: {valid} valid record(s)")

    # Derive results URL base for source file links
    if args.results_url:
        results_url_base = args.results_url.rstrip("/")
    elif evidence:
        repo = evidence[0].get("repo", "NickMonrad/kernel-ai-assistant")
        results_url_base = f"https://github.com/{repo}/blob/test-results/results"
    else:
        results_url_base = ""

    aggregates = _build_aggregates(evidence, metrics=metrics)
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
        "index.html": _render_overview(aggregates, args.results_url),
        "prs.html": _render_prs(aggregates),
        "devices.html": _render_devices(aggregates),
        "releases.html": _render_releases(aggregates),
    }
    for name, content in html_pages.items():
        (out_dir / name).write_text(content)
        print(f"  {name} — {len(content)} bytes")

    # Write JSON data files
    json_data = _build_json_data(aggregates)
    _write_json_files(json_data, data_dir)

    print(f"\nDashboard built in {out_dir.resolve()}")


if __name__ == "__main__":
    main()
