#!/usr/bin/env python3
"""Static test-evidence dashboard builder.

Reads normalised test evidence from a local checkout of the ``test-results``
branch and generates a static HTML/CSS dashboard + JSON data files into ``_site/``.

Usage::

    python3 scripts/build_test_dashboard.py --results-dir ../test-results/results

Output::

    _site/
      index.html        (overview — latest CI + on-device, recent PRs/releases)
      prs.html          (per-PR tables)
      devices.html      (per-device tables)
      releases.html     (per-release tables)
      data/
        latest.json
        history.json
        prs.json
        devices.json
        releases.json
"""

from __future__ import annotations

import argparse
import json
import os
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
    any structural or required-field violation (warning is printed)."""
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
    if not results_dir.is_dir():
        return []
    evidence: list[dict] = []
    for fpath in sorted(results_dir.rglob("*.json")):
        parsed = _load_evidence(fpath)
        if parsed is not None:
            evidence.append(parsed)
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
    dev = rec.get("device")
    if isinstance(dev, dict):
        return str(dev.get("id", "unknown"))
    return "unknown"


def _suite_name(rec: dict) -> str:
    return str(rec.get("suite", "unknown"))


def _source_label(source: str) -> str:
    return SOURCE_LABELS.get(source, source)


def _status_badge(pass_rate: float) -> str:
    if pass_rate >= 1.0:
        return '<span class="badge pass">PASS</span>'
    if pass_rate >= 0.8:
        return '<span class="badge warn">WARN</span>'
    return '<span class="badge fail">FAIL</span>'


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

    # Per-PR aggregates
    prs_data: list[dict] = []
    for pr_num, recs in sorted_prs:
        ci_recs = [r for r in recs if r.get("source") == "ci"]
        od_recs = [r for r in recs if r.get("source") == "on_device"]
        ci_summary = _merge_summaries(ci_recs)
        od_summary = _merge_summaries(od_recs)
        ts_ci = max((r.get("timestamp", "") for r in ci_recs), default="")
        ts_od = max((r.get("timestamp", "") for r in od_recs), default="")
        latest_commit = max(
            (r.get("commit", "") for r in recs if r.get("commit")),
            default="",
        )
        prs_data.append({
            "pr": pr_num,
            "commit": latest_commit,
            "ci": {"count": len(ci_recs), **ci_summary, "latest": ts_ci},
            "on_device": {"count": len(od_recs), **od_summary, "latest": ts_od},
        })

    # Per-release aggregates
    releases_data: list[dict] = []
    for rel, recs in sorted_releases:
        ci_recs = [r for r in recs if r.get("source") == "ci"]
        od_recs = [r for r in recs if r.get("source") == "on_device"]
        ts_all = max((r.get("timestamp", "") for r in recs), default="")
        releases_data.append({
            "release": rel,
            "ci_count": len(ci_recs),
            "on_device_count": len(od_recs),
            "latest": ts_all,
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
.badge { display: inline-block; padding: 2px 8px; border-radius: 12px; font-size: 0.8rem; font-weight: 600; }
.pass { background: #1b3a2d; color: #3fb950; }
.warn { background: #3d2e00; color: #d29922; }
.fail { background: #3d1b1b; color: #f85149; }
.source-tag { display: inline-block; padding: 2px 8px; border-radius: 12px; font-size: 0.75rem; font-weight: 600; }
.source-ci { background: #0d4194; color: #79c0ff; }
.source-od { background: #3d1b6e; color: #d2a8ff; }
.empty { color: #8b949e; font-style: italic; padding: 2em 0; }
.nav { margin: 1em 0; display: flex; gap: 12px; flex-wrap: wrap; }
.nav a { padding: 6px 16px; background: #21262d; border-radius: 6px; font-weight: 500; }
.nav a:hover { background: #30363d; }
.summary-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: 16px; margin: 1em 0; }
.card { background: #161b22; border: 1px solid #30363d; border-radius: 8px; padding: 16px; }
.card .label { font-size: 0.8rem; color: #8b949e; }
.card .value { font-size: 1.4rem; font-weight: 700; margin-top: 4px; }
.section { margin: 2em 0; }
footer { margin-top: 3em; padding-top: 1em; border-top: 1px solid #30363d; font-size: 0.85rem; color: #8b949e; }
"""


def _page(title: str, body: str, extra_head: str = "") -> str:
    return f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title} — Test Evidence Dashboard</title>
<style>{_CSS}</style>
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
            pr = _pr_label(rec.get("pr"))
            cards += f"""<div class="card">
  <div class="label">{label} <span class="source-tag source-{"ci" if src_key=="ci" else "od"}">{src_key}</span></div>
  <div class="value">{_safe_int(s.get("passed"))}/{_safe_int(s.get("total"))} passed</div>
  <div style="margin-top:6px;font-size:0.85rem;">
    {_status_badge(_safe_float(s.get("pass_rate")))} &nbsp;
    {rec.get("suite","")} &nbsp;
    {pr} &nbsp;
    {_iso_short(rec.get("timestamp",""))}
  </div>
</div>"""
        else:
            cards += f"""<div class="card">
  <div class="label">{label}</div>
  <div class="value empty">No evidence yet</div>
</div>"""

    # Recent PRs
    pr_rows = ""
    for pr in data["prs"][:10]:
        ci = pr["ci"]
        od = pr["on_device"]
        pr_rows += f"""<tr>
  <td><a href="prs.html#pr-{pr['pr']}">PR #{pr['pr']}</a></td>
  <td><code>{_truncate_sha(pr['commit'])}</code></td>
  <td>{_status_badge(ci['pass_rate'])} {ci['passed']}/{ci['total']}</td>
  <td>{_status_badge(od['pass_rate'])} {od['passed']}/{od['total']}</td>
  <td>{_iso_short(ci['latest'] or od['latest'])}</td>
</tr>"""
    if not pr_rows:
        pr_rows = '<tr><td colspan="5" class="empty">No PR evidence yet</td></tr>'

    # Recent releases
    rel_rows = ""
    for rel in data["releases"][:10]:
        rel_rows += f"""<tr>
  <td><a href="releases.html#release-{rel['release']}">{rel['release']}</a></td>
  <td>{_status_badge(rel['ci_summary']['pass_rate'])} {rel['ci_summary']['passed']}/{rel['ci_summary']['total']}</td>
  <td>{_status_badge(rel['od_summary']['pass_rate'])} {rel['od_summary']['passed']}/{rel['od_summary']['total']}</td>
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
  <td>{_status_badge(dev['pass_rate'])} {dev['passed']}/{dev['total']}</td>
  <td>{_iso_short(dev['latest'])}</td>
</tr>"""
    if not dev_rows:
        dev_rows = '<tr><td colspan="5" class="empty">No device evidence yet</td></tr>'

    body = f"""<h1>Test Evidence Dashboard</h1>

<h2>Latest Results</h2>
<div class="summary-grid">{cards}</div>

<div class="section">
<h2>Recent Pull Requests</h2>
<table>
<thead><tr><th>PR</th><th>Commit</th><th>CI</th><th>On-device</th><th>Latest</th></tr></thead>
<tbody>{pr_rows}</tbody>
</table>
</div>

<div class="section">
<h2>Recent Releases</h2>
<table>
<thead><tr><th>Release</th><th>CI</th><th>On-device</th><th>Latest</th></tr></thead>
<tbody>{rel_rows}</tbody>
</table>
</div>

<div class="section">
<h2>Devices</h2>
<table>
<thead><tr><th>Device</th><th>Tier</th><th>Suites</th><th>Pass Rate</th><th>Latest</th></tr></thead>
<tbody>{dev_rows}</tbody>
</table>
</div>
"""
    return _page("Overview", body)


def _render_prs(data: dict) -> str:
    sections = ""
    for pr in data["prs"]:
        ci = pr["ci"]
        od = pr["on_device"]
        sections += f"""<h2 id="pr-{pr['pr']}">PR #{pr['pr']} <code>{_truncate_sha(pr['commit'])}</code></h2>
<table>
<thead><tr><th>Source</th><th>Run Count</th><th>Passed</th><th>Failed</th><th>Total</th><th>Pass Rate</th><th>Latest</th></tr></thead>
<tbody>
<tr>
  <td><span class="source-tag source-ci">ci</span> {CI_LABEL}</td>
  <td>{ci['count']}</td>
  <td>{ci['passed']}</td>
  <td>{ci['failed']}</td>
  <td>{ci['total']}</td>
  <td>{_status_badge(ci['pass_rate'])} {ci['pass_rate']:.1%}</td>
  <td>{_iso_short(ci['latest'])}</td>
</tr>
<tr>
  <td><span class="source-tag source-od">on_device</span> {ON_DEVICE_LABEL}</td>
  <td>{od['count']}</td>
  <td>{od['passed']}</td>
  <td>{od['failed']}</td>
  <td>{od['total']}</td>
  <td>{_status_badge(od['pass_rate'])} {od['pass_rate']:.1%}</td>
  <td>{_iso_short(od['latest'])}</td>
</tr>
</tbody>
</table>"""

    if not sections:
        sections = '<p class="empty">No PR evidence yet.</p>'

    return _page("Pull Requests", f"<h1>Pull Request Evidence</h1>{sections}")


def _render_devices(data: dict) -> str:
    sections = ""
    for dev in data["devices"]:
        # Failure breakdown
        fail_rows = ""
        if dev["failures_by_category"]:
            for cat, count in sorted(dev["failures_by_category"].items(), key=lambda x: -x[1]):
                fail_rows += f"<tr><td>{cat}</td><td>{count}</td></tr>\n"
        else:
            fail_rows = '<tr><td colspan="2" class="empty">No failures recorded</td></tr>'

        sections += f"""<h2 id="device-{dev['device_id']}">{dev['label']} <code>{dev['device_id']}</code></h2>
<table>
<thead><tr><th>Property</th><th>Value</th></tr></thead>
<tbody>
<tr><td>Tier</td><td>{dev['tier']}</td></tr>
<tr><td>Source</td><td><span class="source-tag source-{"ci" if dev['source']=='ci' else 'od'}">{_source_label(dev['source'])}</span></td></tr>
<tr><td>Suites</td><td>{', '.join(dev['suites'])}</td></tr>
<tr><td>Total Runs</td><td>{dev['total']}</td></tr>
<tr><td>Pass Rate</td><td>{_status_badge(dev['pass_rate'])} {dev['pass_rate']:.1%} ({dev['passed']}/{dev['total']})</td></tr>
<tr><td>Latest Run</td><td>{_iso_short(dev['latest'])}</td></tr>
<tr><td>Latest Commit</td><td><code>{_truncate_sha(dev['latest_commit'])}</code></td></tr>
</tbody>
</table>

<h3>Failure Categories</h3>
<table>
<thead><tr><th>Category</th><th>Count</th></tr></thead>
<tbody>{fail_rows}</tbody>
</table>"""

    if not sections:
        sections = '<p class="empty">No device evidence yet.</p>'

    return _page("Devices", f"<h1>Device Evidence</h1>{sections}")


def _render_releases(data: dict) -> str:
    sections = ""
    for rel in data["releases"]:
        ci = rel["ci_summary"]
        od = rel["od_summary"]
        sections += f"""<h2 id="release-{rel['release']}">{rel['release']}</h2>
<table>
<thead><tr><th>Source</th><th>Runs</th><th>Passed</th><th>Failed</th><th>Total</th><th>Pass Rate</th><th>Latest</th></tr></thead>
<tbody>
<tr>
  <td><span class="source-tag source-ci">ci</span> {CI_LABEL}</td>
  <td>{rel['ci_count']}</td>
  <td>{ci['passed']}</td>
  <td>{ci['failed']}</td>
  <td>{ci['total']}</td>
  <td>{_status_badge(ci['pass_rate'])} {ci['pass_rate']:.1%}</td>
  <td>{_iso_short(rel['latest'])}</td>
</tr>
<tr>
  <td><span class="source-tag source-od">on_device</span> {ON_DEVICE_LABEL}</td>
  <td>{rel['on_device_count']}</td>
  <td>{od['passed']}</td>
  <td>{od['failed']}</td>
  <td>{od['total']}</td>
  <td>{_status_badge(od['pass_rate'])} {od['pass_rate']:.1%}</td>
  <td>{_iso_short(rel['latest'])}</td>
</tr>
</tbody>
</table>"""

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

    # Aggregate
    aggregates = _build_aggregates(evidence)
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
