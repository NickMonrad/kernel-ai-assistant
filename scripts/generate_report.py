#!/usr/bin/env python3
"""Generate a standalone HTML report from a Jandal skill test JSON report."""

from __future__ import annotations

import argparse
import html
import json
import sys
import webbrowser
from datetime import datetime, timezone
from pathlib import Path


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _e(s: object) -> str:
    """HTML-escape a value, converting None to empty string."""
    return html.escape(str(s) if s is not None else "")


def _trunc(s: str | None, n: int = 60) -> tuple[str, str]:
    """Return (display, full) where display is truncated to n chars."""
    if not s:
        return "", ""
    return (s[:n] + "…") if len(s) > n else s, s


def _badge_style(failed: int, total: int) -> str:
    if failed == 0:
        return "badge-green"
    ratio = failed / max(total, 1)
    return "badge-amber" if ratio < 0.05 else "badge-red"


def _pass_rate(passed: int, total: int) -> str:
    if total == 0:
        return "0.0"
    return f"{passed / total * 100:.1f}"


# ---------------------------------------------------------------------------
# HTML template pieces
# ---------------------------------------------------------------------------

CSS = """
* { box-sizing: border-box; margin: 0; padding: 0; }
body {
    font-family: 'Segoe UI', system-ui, sans-serif;
    background: #0f1117;
    color: #e2e8f0;
    padding: 24px;
    line-height: 1.5;
}
h1 { font-size: 1.6rem; font-weight: 700; color: #f8fafc; }
h2 { font-size: 1.1rem; font-weight: 600; color: #94a3b8; margin-bottom: 8px; }
.header { margin-bottom: 24px; }
.meta { font-size: 0.85rem; color: #94a3b8; margin-top: 6px; }
.badge {
    display: inline-block;
    padding: 10px 22px;
    border-radius: 8px;
    font-size: 1.4rem;
    font-weight: 700;
    margin-top: 12px;
}
.badge-green  { background: #14532d; color: #86efac; border: 1px solid #16a34a; }
.badge-amber  { background: #431407; color: #fdba74; border: 1px solid #ea580c; }
.badge-red    { background: #450a0a; color: #fca5a5; border: 1px solid #dc2626; }
.summary-grid {
    display: flex; gap: 12px; flex-wrap: wrap;
    margin-top: 16px; margin-bottom: 24px;
}
.stat {
    background: #1e2330;
    border: 1px solid #2d3548;
    border-radius: 8px;
    padding: 12px 20px;
    min-width: 100px;
    text-align: center;
}
.stat-num { font-size: 1.8rem; font-weight: 700; }
.stat-label { font-size: 0.75rem; color: #94a3b8; text-transform: uppercase; letter-spacing: 0.05em; }
.green { color: #4ade80; }
.red   { color: #f87171; }
.amber { color: #fb923c; }
.grey  { color: #94a3b8; }

/* Filter buttons */
.filters { display: flex; gap: 8px; margin-bottom: 14px; flex-wrap: wrap; }
.filter-btn {
    padding: 6px 16px;
    border-radius: 6px;
    border: 1px solid #2d3548;
    background: #1e2330;
    color: #e2e8f0;
    cursor: pointer;
    font-size: 0.85rem;
    transition: background 0.15s;
}
.filter-btn:hover { background: #2d3548; }
.filter-btn.active { background: #3b82f6; border-color: #2563eb; color: #fff; }

/* Table */
table {
    width: 100%;
    border-collapse: collapse;
    font-size: 0.82rem;
}
th {
    background: #1e2330;
    padding: 10px 12px;
    text-align: left;
    color: #94a3b8;
    font-weight: 600;
    border-bottom: 2px solid #2d3548;
    position: sticky; top: 0; z-index: 1;
    white-space: nowrap;
}
td { padding: 8px 12px; border-bottom: 1px solid #1a1f2e; vertical-align: top; }
tr.row-pass  td { background: #0c1f0f; }
tr.row-fail  td { background: #1f0a0a; }
tr.row-fail td.col-expect, tr.row-fail td.col-actual { background: #2a0e0e; font-weight: 600; }
tr.row-xfail td { background: #141520; color: #64748b; }
tr:hover td { filter: brightness(1.1); }

/* Status badge */
.status-pill {
    display: inline-block;
    padding: 2px 8px;
    border-radius: 99px;
    font-size: 0.75rem;
    font-weight: 700;
}
.pill-pass  { background: #14532d; color: #86efac; }
.pill-fail  { background: #450a0a; color: #fca5a5; }
.pill-xfail { background: #1e1b4b; color: #a5b4fc; }

/* Phase group header */
.phase-header {
    background: #1a2035;
    padding: 8px 12px;
    font-weight: 600;
    color: #7c93c3;
    font-size: 0.82rem;
    letter-spacing: 0.04em;
    text-transform: uppercase;
    border-bottom: 1px solid #2d3548;
    cursor: pointer;
    user-select: none;
}
.phase-header:hover { background: #202840; }
.phase-toggle { float: right; }

/* Param failures */
.param-fail { color: #f87171; font-size: 0.78rem; margin-top: 3px; }
.diff-icon { margin-left: 4px; }

/* Footer */
footer { margin-top: 32px; font-size: 0.78rem; color: #475569; text-align: center; }
"""

JS = """
const rows = document.querySelectorAll('tr[data-status]');
const btns = document.querySelectorAll('.filter-btn');

btns.forEach(btn => {
  btn.addEventListener('click', () => {
    btns.forEach(b => b.classList.remove('active'));
    btn.classList.add('active');
    const filter = btn.dataset.filter;
    rows.forEach(row => {
      if (filter === 'all' || row.dataset.status === filter) {
        row.style.display = '';
      } else {
        row.style.display = 'none';
      }
    });
    // Re-show phase headers only if they have visible children
    document.querySelectorAll('.phase-header').forEach(ph => {
      const section = ph.nextElementSibling;
      if (!section) return;
      const visibles = section.querySelectorAll('tr[data-status]');
      let anyVisible = false;
      visibles.forEach(r => { if (r.style.display !== 'none') anyVisible = true; });
      ph.style.display = anyVisible ? '' : 'none';
    });
  });
});

// Phase collapse toggle
document.querySelectorAll('.phase-header').forEach(ph => {
  ph.addEventListener('click', () => {
    const section = ph.nextElementSibling;
    if (!section) return;
    const hidden = section.style.display === 'none';
    section.style.display = hidden ? '' : 'none';
    ph.querySelector('.phase-toggle').textContent = hidden ? '▲' : '▼';
  });
});
"""


# ---------------------------------------------------------------------------
# Row builder
# ---------------------------------------------------------------------------

def _render_row(r: dict) -> str:
    status = r.get("status", "fail")
    css = {"pass": "row-pass", "fail": "row-fail", "xfail": "row-xfail"}.get(status, "row-fail")
    pill = {"pass": "pill-pass", "fail": "pill-fail", "xfail": "pill-xfail"}.get(status, "pill-fail")
    label = {"pass": "PASS", "fail": "FAIL", "xfail": "XFAIL"}.get(status, status.upper())

    idx = r.get("index", "")
    message = _e(r.get("message", ""))
    expect_intent = _e(r.get("expect_intent") or "—")
    actual_intent = _e(r.get("actual_intent") or "—")

    diff_icon = " ❌" if not r.get("intent_passed", True) else ""
    expect_params_raw = r.get("expect_params") or {}
    actual_params_raw = r.get("actual_params") or {}
    expect_params = _e(json.dumps(expect_params_raw) if expect_params_raw else "—")
    actual_params = _e(json.dumps(actual_params_raw) if actual_params_raw else "—")

    param_failures = r.get("param_failures") or []
    param_fail_html = ""
    if param_failures:
        items = "".join(f"<div>• {_e(p)}</div>" for p in param_failures)
        param_fail_html = f'<div class="param-fail">{items}</div>'

    reply_warn = r.get("reply_warn") or ""
    disp, full = _trunc(reply_warn, 60)
    reply_cell = (
        f'<span title="{_e(full)}">{_e(disp)}</span>' if disp else '<span class="grey">—</span>'
    )

    return (
        f'<tr class="{css}" data-status="{status}">'
        f'<td>{_e(idx)}</td>'
        f'<td>{message}</td>'
        f'<td class="col-expect">{expect_intent}</td>'
        f'<td class="col-actual">{actual_intent}<span class="diff-icon">{diff_icon}</span>'
        f'{param_fail_html}</td>'
        f'<td>{expect_params}</td>'
        f'<td>{actual_params}</td>'
        f'<td><span class="status-pill {pill}">{label}</span></td>'
        f'<td>{reply_cell}</td>'
        f'</tr>'
    )


# ---------------------------------------------------------------------------
# Main report builder
# ---------------------------------------------------------------------------

def generate_html(data: dict) -> str:
    summary = data.get("summary", {})
    total   = summary.get("total", 0)
    passed  = summary.get("passed", 0)
    failed  = summary.get("failed", 0)
    xfailed = summary.get("xfail", 0)
    suite   = _e(data.get("suite", "skills"))
    ts      = _e(data.get("timestamp", ""))
    pass_rate = _pass_rate(passed, total)
    badge_cls = _badge_style(failed, total)
    badge_txt = f"{pass_rate}% pass rate — {'✅ All good' if failed == 0 else f'⚠ {failed} failure(s)'}"
    generated = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

    results = data.get("results", [])

    # Sort: failures first, then xfail, then pass
    order = {"fail": 0, "xfail": 1, "pass": 2}
    sorted_results = sorted(results, key=lambda r: order.get(r.get("status", "fail"), 0))

    # Phase grouping
    has_phases = any("phase" in r for r in results)
    if has_phases:
        phases: dict[str, list[dict]] = {}
        for r in sorted_results:
            p = r.get("phase", "Ungrouped")
            phases.setdefault(p, []).append(r)
        rows_html = ""
        for phase_name, phase_rows in phases.items():
            ph_pass = sum(1 for r in phase_rows if r.get("status") == "pass")
            ph_fail = sum(1 for r in phase_rows if r.get("status") == "fail")
            ph_xfail = sum(1 for r in phase_rows if r.get("status") == "xfail")
            summary_txt = (
                f'{_e(phase_name)} &nbsp;·&nbsp; '
                f'<span class="green">{ph_pass}✓</span> '
                f'<span class="red">{ph_fail}✗</span> '
                f'<span class="grey">{ph_xfail} xfail</span>'
            )
            body_rows = "".join(_render_row(r) for r in phase_rows)
            rows_html += (
                f'<tr class="phase-header"><td colspan="8">{summary_txt}'
                f'<span class="phase-toggle">▲</span></td></tr>'
                f'<tbody class="phase-body">{body_rows}</tbody>'
            )
    else:
        rows_html = "".join(_render_row(r) for r in sorted_results)

    stat_blocks = "".join([
        f'<div class="stat"><div class="stat-num">{total}</div><div class="stat-label">Total</div></div>',
        f'<div class="stat"><div class="stat-num green">{passed}</div><div class="stat-label">Passed</div></div>',
        f'<div class="stat"><div class="stat-num red">{failed}</div><div class="stat-label">Failed</div></div>',
        f'<div class="stat"><div class="stat-num grey">{xfailed}</div><div class="stat-label">XFail</div></div>',
    ])

    return f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Jandal Skill Regression Report — {suite}</title>
<style>{CSS}</style>
</head>
<body>
<div class="header">
  <h1>🦎 Jandal Skill Regression Report</h1>
  <div class="meta">Suite: <strong>{suite}</strong> &nbsp;·&nbsp; Run: <strong>{ts}</strong></div>
  <div class="badge {badge_cls}">{badge_txt}</div>
</div>

<div class="summary-grid">{stat_blocks}</div>

<h2>Results</h2>
<div class="filters">
  <button class="filter-btn active" data-filter="all">All ({total})</button>
  <button class="filter-btn" data-filter="fail">Failed ({failed})</button>
  <button class="filter-btn" data-filter="pass">Passed ({passed})</button>
  <button class="filter-btn" data-filter="xfail">XFail ({xfailed})</button>
</div>

<table>
  <thead>
    <tr>
      <th>#</th>
      <th>Input</th>
      <th>Expected Intent</th>
      <th>Actual Intent</th>
      <th>Expected Params</th>
      <th>Actual Params</th>
      <th>Status</th>
      <th>Reply Warn</th>
    </tr>
  </thead>
  <tbody>
    {rows_html}
  </tbody>
</table>

<footer>Generated by Jandal test harness &nbsp;·&nbsp; {generated}</footer>

<script>{JS}</script>
</body>
</html>"""


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

def main() -> None:
    parser = argparse.ArgumentParser(description="Generate HTML report from Jandal JSON test report")
    parser.add_argument("input", help="Path to JSON report file")
    parser.add_argument("--open", action="store_true", help="Open the report in a browser after generating")
    args = parser.parse_args()

    input_path = Path(args.input)
    if not input_path.exists():
        print(f"ERROR: file not found: {input_path}", file=sys.stderr)
        sys.exit(1)

    data = json.loads(input_path.read_text())
    html_content = generate_html(data)

    out_path = input_path.with_suffix(".html")
    out_path.write_text(html_content, encoding="utf-8")
    print(f"Report written to: {out_path}")

    if args.open:
        webbrowser.open(out_path.as_uri())


if __name__ == "__main__":
    main()
