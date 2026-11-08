#!/usr/bin/env python3
"""Generate a starter monthly quality scorecard.

The input is intentionally simple JSON so the monthly review can be filled from
existing PR summaries, dashboard metrics, and manual notes without requiring a
new GitHub mining pipeline.
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from datetime import date
from pathlib import Path
from typing import Any

UNKNOWN = "TBD"


def _safe_list(value: Any) -> list[Any]:
    return value if isinstance(value, list) else []


def _safe_dict(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def _count_prs_by_type(prs: list[Any]) -> Counter[str]:
    counts: Counter[str] = Counter()
    for pr in prs:
        if not isinstance(pr, dict):
            continue
        pr_type = str(pr.get("type") or "other").lower()
        if pr_type in {"feature", "fix", "docs", "process", "test", "tooling"}:
            counts[pr_type] += 1
        else:
            counts["other"] += 1
    return counts


def _count_manual_testing_fit(prs: list[Any]) -> Counter[str]:
    counts: Counter[str] = Counter()
    for pr in prs:
        if not isinstance(pr, dict):
            continue
        fit = str(pr.get("manual_testing_fit") or "unknown").lower()
        if fit in {"appropriate", "missing", "overused", "not_applicable"}:
            counts[fit] += 1
        else:
            counts["unknown"] += 1
    return counts


def _ratio(numerator: int, denominator: int) -> str:
    if denominator <= 0:
        return UNKNOWN
    return f"{numerator / denominator:.2f}"


def _table(rows: list[list[Any]]) -> str:
    return "\n".join("| " + " | ".join(str(cell) for cell in row) + " |" for row in rows)


def _bullet_list(items: list[Any], fallback: str = "- ") -> str:
    bullets = [f"- {item}" for item in items if str(item).strip()]
    return "\n".join(bullets) if bullets else fallback


def _metric_value(metrics: dict[str, Any], path: str, default: Any = UNKNOWN) -> Any:
    cur: Any = metrics
    for part in path.split("."):
        if not isinstance(cur, dict) or part not in cur:
            return default
        cur = cur[part]
    return cur


def build_scorecard(data: dict[str, Any]) -> str:
    month = str(data.get("month") or date.today().strftime("%Y-%m"))
    review_date = str(data.get("review_date") or date.today().isoformat())
    scope = str(data.get("scope") or "TBD")
    reviewers = str(data.get("reviewers") or "TBD")

    prs = _safe_list(data.get("prs"))
    pr_counts = _count_prs_by_type(prs)
    fix_count = pr_counts.get("fix", 0)
    feature_count = pr_counts.get("feature", 0)
    docs_process_test = pr_counts.get("docs", 0) + pr_counts.get("process", 0) + pr_counts.get("test", 0) + pr_counts.get("tooling", 0)
    manual_fit = _count_manual_testing_fit(prs)

    metrics = _safe_dict(data.get("metrics"))
    validity = _safe_dict(metrics.get("validity"))
    issue_buckets = _safe_dict(validity.get("issue_buckets"))
    failure_buckets = _safe_dict(metrics.get("failure_buckets"))
    retry_timeout_harness = _safe_dict(metrics.get("retry_timeout_harness"))
    stuck_mode = _safe_list(metrics.get("stuck_mode"))
    artifacts = _safe_list(metrics.get("artifacts"))

    follow_up_fixes = _safe_list(data.get("follow_up_fixes"))
    review_blockers = _safe_dict(data.get("review_blockers"))
    docs_drift = _safe_dict(data.get("docs_drift"))
    high_risk = _safe_dict(data.get("high_risk_evidence"))
    actions = _safe_list(data.get("actions"))
    observations = _safe_list(data.get("observations"))
    limitations = _safe_list(data.get("limitations"))

    invalid_records = _metric_value(validity, "invalid_records", 0)
    valid_records = _metric_value(validity, "valid_records", 0)

    action_rows = []
    for item in actions:
        if isinstance(item, dict):
            action_rows.append([
                item.get("action", UNKNOWN),
                item.get("category", UNKNOWN),
                item.get("why", UNKNOWN),
                item.get("owner", UNKNOWN),
                item.get("target", UNKNOWN),
                item.get("issue", UNKNOWN),
            ])
    if not action_rows:
        action_rows = [["", "", "", "", "", ""]]

    failure_rows = [[bucket, count, UNKNOWN, UNKNOWN] for bucket, count in failure_buckets.items()]
    if not failure_rows:
        failure_rows = [["None", 0, "", ""]]

    issue_rows = [[issue, count, UNKNOWN] for issue, count in issue_buckets.items()]
    if not issue_rows:
        issue_rows = [["None", 0, ""]]

    stuck_rows = []
    for item in stuck_mode:
        if isinstance(item, dict):
            expected_tools = item.get("different_expected_tools") or item.get("expected_tools") or []
            if isinstance(expected_tools, list):
                expected = ", ".join(str(tool) for tool in expected_tools)
            else:
                expected = str(expected_tools)
            stuck_rows.append([item.get("actual_tool", UNKNOWN), expected or UNKNOWN, UNKNOWN])
    if not stuck_rows:
        stuck_rows = [["None", "", ""]]

    blocker_rows = []
    for category in [
        "missing_test",
        "missing_docs",
        "ci_failure",
        "device_specific_regression",
        "ux_regression",
        "evidence_mismatch",
        "scope_creep",
    ]:
        blocker_rows.append([category, review_blockers.get(category, 0), UNKNOWN, UNKNOWN])

    manual_rows = []
    for pr in prs:
        if isinstance(pr, dict):
            manual_rows.append([
                pr.get("number", UNKNOWN),
                pr.get("risk", UNKNOWN),
                pr.get("manual_testing", UNKNOWN),
                pr.get("manual_testing_fit", UNKNOWN),
                pr.get("notes", ""),
            ])
    if not manual_rows:
        manual_rows = [["#", "Low/Medium/High", "Yes/No/N/A", "Appropriate / Missing / Overused", ""]]

    return f"""# Jandal AI Monthly Quality Scorecard — {month}

Review date: {review_date}  
Reviewer(s): {reviewers}  
Scope: {scope}  
Related epic: #1219

---

## 1. Executive summary

### Overall health

| Area | Status | Notes |
|---|---|---|
| Delivery mix | TBD | {len(prs)} merged/opened PR record(s) included |
| Follow-up churn | TBD | {len(follow_up_fixes)} follow-up fix item(s) recorded |
| Evidence quality | TBD | {valid_records} valid / {invalid_records} invalid evidence record(s) |
| Harness/dashboard signal | TBD | {len(failure_buckets)} failure bucket(s), {len(stuck_mode)} stuck-mode suspect(s) |
| Device/manual testing fit | TBD | {manual_fit.get('appropriate', 0)} appropriate / {manual_fit.get('missing', 0)} missing / {manual_fit.get('overused', 0)} overused |
| Docs drift | TBD | {docs_drift.get('warnings', UNKNOWN)} warning(s) |

### Key observations

{_bullet_list(observations)}

### Top actions for next month

| Priority | Action | Category | Owner | Issue/PR |
|---|---|---|---|---|
| P1 | TBD | TBD | TBD | TBD |
| P2 | TBD | TBD | TBD | TBD |
| P3 | TBD | TBD | TBD | TBD |

---

## 2. Delivery mix

| Metric | Value | Notes |
|---|---:|---|
| Merged PRs | {len(prs)} | Based on input PR records |
| Feature PRs | {feature_count} | |
| Fix PRs | {fix_count} | |
| Docs/process/test-only PRs | {docs_process_test} | Docs + process + test + tooling |
| Fix vs feature ratio | {_ratio(fix_count, feature_count)} | TBD when feature count is zero |

Notes:

- TBD

---

## 3. Follow-up churn

| Metric | Value | Notes |
|---|---:|---|
| PRs requiring follow-up fixes within 7 days | {len(follow_up_fixes)} | |
| Reopened/reverted work | {data.get('reopened_or_reverted', UNKNOWN)} | |
| Repeat review blockers | {sum(int(v) for v in review_blockers.values() if isinstance(v, int))} | |

### Review blocker categories

| Category | Count | Example PRs | Action |
|---|---:|---|---|
{_table(blocker_rows)}

Notes:

- TBD

---

## 4. Evidence quality

| Metric | Value | Notes |
|---|---:|---|
| Invalid evidence records | {invalid_records} | From dashboard metrics when supplied |
| Missing evidence manifests | {data.get('missing_evidence_manifests', UNKNOWN)} | |
| High-risk PRs with appropriate evidence | {high_risk.get('with_appropriate_evidence', UNKNOWN)} | |
| High-risk PRs missing appropriate evidence | {high_risk.get('missing_appropriate_evidence', UNKNOWN)} | |
| Evidence waivers with rationale | {data.get('waivers_with_rationale', UNKNOWN)} | |
| Evidence waivers without rationale | {data.get('waivers_without_rationale', UNKNOWN)} | |
| Docs drift warnings | {docs_drift.get('warnings', UNKNOWN)} | |

### Evidence validity issue buckets

| Issue bucket | Count | Action |
|---|---:|---|
{_table(issue_rows)}

Notes:

- TBD

---

## 5. Harness and dashboard signal

| Metric | Value | Notes |
|---|---:|---|
| Failure buckets observed | {len(failure_buckets)} | |
| Flaky harness cases | {data.get('flaky_harness_cases', UNKNOWN)} | |
| Stuck-mode/cascade suspects | {len(stuck_mode)} | |
| Timeout/retry/harness errors | {retry_timeout_harness.get('timeout', 0)} timeout / {retry_timeout_harness.get('retry_seen', 0)} retry / {retry_timeout_harness.get('harness_error', 0)} harness | |
| Artifact paths available | {len(artifacts)} | |

### Failure buckets

| Failure bucket | Count | Example suites/devices | Action |
|---|---:|---|---|
{_table(failure_rows)}

### Stuck-mode suspects

| Actual tool | Expected tools affected | Action |
|---|---|---|
{_table(stuck_rows)}

Notes:

- TBD

---

## 6. Device and manual testing use

| Metric | Value | Notes |
|---|---:|---|
| Medium/high-risk PRs with S21 evidence where expected | {data.get('s21_expected_coverage', UNKNOWN)} | |
| Focused S23U comparisons | {data.get('s23u_focused_comparisons', UNKNOWN)} | |
| S21 vs S23U divergence cases | {data.get('device_divergence_cases', UNKNOWN)} | |
| Manual testing used where it added signal | {manual_fit.get('appropriate', 0)} | |
| Manual testing overused on low-risk work | {manual_fit.get('overused', 0)} | |

### Manual testing review

| PR | Risk tier | Manual testing performed? | Fit | Notes |
|---|---|---|---|---|
{_table(manual_rows)}

Notes:

- TBD

---

## 7. Docs drift

| Metric | Value | Notes |
|---|---:|---|
| Docs drift warnings | {docs_drift.get('warnings', UNKNOWN)} | |
| Warnings resolved by docs update | {docs_drift.get('resolved_by_docs', UNKNOWN)} | |
| Warnings resolved by rationale | {docs_drift.get('resolved_by_rationale', UNKNOWN)} | |
| Unresolved docs drift concerns | {docs_drift.get('unresolved', UNKNOWN)} | |

Notes:

- TBD

---

## 8. Follow-up actions

| Action | Category | Why | Owner | Target | Issue/PR |
|---|---|---|---|---|---|
{_table(action_rows)}

---

## 9. Previous action review

| Previous action | Status | Notes |
|---|---|---|
| TBD | Done / Carry forward / Superseded | |

---

## 10. Inputs used

| Input | Used? | Notes |
|---|---|---|
| Merged PR list | {'Yes' if prs else 'No'} | |
| PR bodies / evidence manifests | TBD | |
| `data/metrics.json` from dashboard | {'Yes' if metrics else 'No'} | |
| Docs Drift Check results | {'Yes' if docs_drift else 'No'} | |
| Review comments | {'Yes' if review_blockers else 'No'} | |
| Manual testing notes | {'Yes' if manual_rows and manual_rows[0][0] != '#' else 'No'} | |

---

## 11. Limitations

{_bullet_list(limitations, '- Generated from partial input; review manually before treating as final.')}
"""


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Generate a starter monthly quality scorecard markdown file.")
    parser.add_argument("--input", type=Path, help="Optional JSON input with PR/evidence/docs-drift summary data")
    parser.add_argument("--output", type=Path, help="Optional output markdown path")
    parser.add_argument("--month", help="Override month, e.g. 2026-06")
    args = parser.parse_args(argv)

    data: dict[str, Any] = {}
    if args.input:
        try:
            loaded = json.loads(args.input.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            print(f"error: failed to read input JSON: {exc}", file=sys.stderr)
            return 2
        if not isinstance(loaded, dict):
            print("error: input JSON must be an object", file=sys.stderr)
            return 2
        data = loaded

    if args.month:
        data["month"] = args.month

    markdown = build_scorecard(data)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(markdown, encoding="utf-8")
    else:
        print(markdown, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
