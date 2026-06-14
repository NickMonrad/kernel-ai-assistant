#!/usr/bin/env python3
"""Summarise normalised test evidence into reviewer-focused metrics.

This is an additive first cut for #1224. It reads one evidence JSON file or a
folder of evidence JSON files and emits compact JSON that can feed the static
PR evidence dashboard or GitHub step summaries.
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

REQUIRED_EVIDENCE_FIELDS = frozenset({
    "schema_version",
    "source",
    "suite",
    "timestamp",
    "repo",
    "branch",
    "commit",
    "pr",
    "release",
    "run_id",
    "device",
    "model",
    "summary",
    "cases",
})

ARTIFACT_FIELDS = (
    "screenshot_path",
    "screenshot",
    "video_path",
    "video",
    "log_path",
    "log",
)

TIMEOUT_BUCKETS = {"timeout", "case_timeout", "suite_timeout"}
HARNESS_BUCKETS = {"harness_error"}
DEVICE_ENV_BUCKETS = {"device_environment_error", "adb_error", "app_crash"}
STALE_SLOT_BUCKETS = {"stale_slot_carryover", "slot_stale_carryover", "stale_carryover"}


def _safe_int(value: Any, default: int = 0) -> int:
    if isinstance(value, bool):
        return int(value)
    if isinstance(value, int):
        return value
    if isinstance(value, float):
        return int(value)
    return default


def _safe_float(value: Any, default: float = 0.0) -> float:
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        return float(value)
    return default


def _load_json_file(path: Path) -> tuple[dict[str, Any] | None, list[str]]:
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError) as exc:
        return None, [f"json_load_error:{exc}"]
    if not isinstance(raw, dict):
        return None, ["not_json_object"]
    return raw, []


def discover_evidence(input_path: Path) -> list[tuple[Path, dict[str, Any] | None, list[str]]]:
    """Return evidence records and load errors from a file or directory."""
    if input_path.is_file():
        record, errors = _load_json_file(input_path)
        return [(input_path, record, errors)]
    if not input_path.exists():
        return [(input_path, None, ["path_not_found"])]

    records: list[tuple[Path, dict[str, Any] | None, list[str]]] = []
    for json_path in sorted(input_path.rglob("*.json")):
        record, errors = _load_json_file(json_path)
        records.append((json_path, record, errors))
    return records


def _device_id(record: dict[str, Any]) -> str:
    device = record.get("device")
    if isinstance(device, dict):
        return str(device.get("id") or "unknown")
    return "unknown"


def _device_context(record: dict[str, Any]) -> dict[str, Any]:
    device = record.get("device") if isinstance(record.get("device"), dict) else {}
    assert isinstance(device, dict)
    return {
        "id": str(device.get("id") or "unknown"),
        "label": device.get("label"),
        "model": device.get("model"),
        "tier": device.get("tier"),
        "android_api": device.get("android_api"),
        "execution": device.get("execution"),
        "source": record.get("source"),
    }


def _case_status(case: dict[str, Any]) -> str:
    if case.get("xfail") is True or case.get("expected_failure") is True:
        return "xfail"
    status = str(case.get("status") or "").lower()
    if status in {"xfail", "expected_failure"}:
        return "xfail"
    if case.get("passed") is True:
        return "passed"
    return "failed"


def _normalise_actual_tool(case: dict[str, Any]) -> str:
    actual = case.get("actual_tool")
    if actual is None or actual == "":
        return "<none>"
    return str(actual)


def _normalise_expected_tool(case: dict[str, Any]) -> str:
    expected = case.get("expected_tool")
    if expected is None or expected == "":
        return "<none>"
    return str(expected)


def _case_artifacts(case: dict[str, Any], record: dict[str, Any]) -> list[dict[str, str]]:
    artifacts: list[dict[str, str]] = []
    for source in (record, case):
        for field in ARTIFACT_FIELDS:
            value = source.get(field)
            if isinstance(value, str) and value:
                artifacts.append({"field": field, "path": value})
    return artifacts


def validate_record(record: dict[str, Any] | None, load_errors: list[str]) -> tuple[bool, list[str]]:
    """Return validity and issue buckets for a normalised evidence record."""
    issues = list(load_errors)
    if record is None:
        return False, issues or ["missing_record"]

    missing = sorted(REQUIRED_EVIDENCE_FIELDS - set(record))
    issues.extend(f"missing:{field}" for field in missing)

    summary = record.get("summary")
    cases = record.get("cases")
    if not isinstance(summary, dict):
        issues.append("summary_not_object")
        summary = {}
    if not isinstance(cases, list):
        issues.append("cases_not_list")
        cases = []

    total = _safe_int(summary.get("total"))
    passed = _safe_int(summary.get("passed"))
    failed = _safe_int(summary.get("failed"))
    if total != len(cases):
        issues.append("summary_total_mismatch")
    if passed + failed > total:
        issues.append("summary_counts_exceed_total")

    for idx, case in enumerate(cases):
        if not isinstance(case, dict):
            issues.append(f"case_{idx}:not_object")
            continue
        status = _case_status(case)
        failures = case.get("failures")
        has_failures = isinstance(failures, list) and bool(failures)
        has_category = bool(case.get("failure_category"))
        if status == "failed" and not (has_failures or has_category):
            issues.append(f"case_{idx}:failed_without_diagnostic")

    device = record.get("device")
    if not isinstance(device, dict):
        issues.append("device_not_object")
    else:
        if not device.get("id"):
            issues.append("device_missing_id")
        if not (device.get("label") or device.get("model") or device.get("execution")):
            issues.append("device_context_sparse")

    if not record.get("commit"):
        issues.append("missing_commit")
    if not record.get("run_id"):
        issues.append("missing_run_id")

    return not issues, issues


def _increment_status(bucket: dict[str, Any], status: str) -> None:
    bucket[status] = _safe_int(bucket.get(status)) + 1
    bucket["total"] = _safe_int(bucket.get("total")) + 1


def _empty_status_bucket() -> dict[str, Any]:
    return {"total": 0, "passed": 0, "failed": 0, "xfail": 0}


def summarise(records: list[tuple[Path, dict[str, Any] | None, list[str]]]) -> dict[str, Any]:
    totals = _empty_status_bucket()
    totals["records"] = len(records)

    validity = {
        "valid_records": 0,
        "invalid_records": 0,
        "issue_buckets": Counter(),
        "records": [],
    }
    by_suite: dict[str, dict[str, Any]] = defaultdict(_empty_status_bucket)
    by_device: dict[str, dict[str, Any]] = {}
    failure_buckets: Counter[str] = Counter()
    confusion_matrix: dict[str, Counter[str]] = defaultdict(Counter)
    result_mode_matrix: dict[str, Counter[str]] = defaultdict(Counter)
    retry_timeout_harness = Counter({
        "retry_seen": 0,
        "timeout": 0,
        "harness_error": 0,
        "device_environment_error": 0,
    })
    slot_fill = Counter({"slot_fill_seen": 0, "stale_carryover_suspect": 0})
    wrong_actual_to_expected: dict[str, set[str]] = defaultdict(set)
    artifacts: list[dict[str, Any]] = []

    for path, record, load_errors in records:
        is_valid, issues = validate_record(record, load_errors)
        relpath = str(path)
        validity_record = {"path": relpath, "valid": is_valid, "issues": issues}
        validity["records"].append(validity_record)
        if is_valid:
            validity["valid_records"] += 1
        else:
            validity["invalid_records"] += 1
            for issue in issues:
                validity["issue_buckets"][issue] += 1

        if record is None:
            continue

        suite = str(record.get("suite") or "unknown")
        device_id = _device_id(record)
        if device_id not in by_device:
            by_device[device_id] = {**_empty_status_bucket(), **_device_context(record)}

        cases = record.get("cases") if isinstance(record.get("cases"), list) else []
        for raw_case in cases:
            if not isinstance(raw_case, dict):
                continue
            status = _case_status(raw_case)
            _increment_status(totals, status)
            _increment_status(by_suite[suite], status)
            _increment_status(by_device[device_id], status)

            category = raw_case.get("failure_category")
            category_str = str(category) if category else "<none>"
            if status == "failed" and category_str != "<none>":
                failure_buckets[category_str] += 1

            expected_tool = _normalise_expected_tool(raw_case)
            actual_tool = _normalise_actual_tool(raw_case)
            confusion_matrix[expected_tool][actual_tool] += 1
            if status == "failed" and actual_tool != expected_tool:
                wrong_actual_to_expected[actual_tool].add(expected_tool)

            expected_mode = str(raw_case.get("expected_result_mode") or "<none>")
            actual_mode = str(raw_case.get("actual_result_mode") or "<none>")
            result_mode_matrix[expected_mode][actual_mode] += 1

            if raw_case.get("retry_seen") is True:
                retry_timeout_harness["retry_seen"] += 1
            if category_str in TIMEOUT_BUCKETS:
                retry_timeout_harness["timeout"] += 1
            if category_str in HARNESS_BUCKETS:
                retry_timeout_harness["harness_error"] += 1
            if category_str in DEVICE_ENV_BUCKETS:
                retry_timeout_harness["device_environment_error"] += 1

            if raw_case.get("slot_fill_seen") is True:
                slot_fill["slot_fill_seen"] += 1
            if category_str in STALE_SLOT_BUCKETS or raw_case.get("stale_carryover_seen") is True:
                slot_fill["stale_carryover_suspect"] += 1

            for artifact in _case_artifacts(raw_case, record):
                artifacts.append({
                    "record": relpath,
                    "suite": suite,
                    "device_id": device_id,
                    "case": raw_case.get("name"),
                    **artifact,
                })

    stuck_mode = []
    for actual_tool, expected_tools in sorted(wrong_actual_to_expected.items()):
        if len(expected_tools) >= 2:
            stuck_mode.append({
                "actual_tool": actual_tool,
                "different_expected_tools": sorted(expected_tools),
                "expected_tool_count": len(expected_tools),
            })

    return {
        "schema_version": "1224-first-cut-v1",
        "totals": dict(totals),
        "validity": {
            **validity,
            "issue_buckets": dict(validity["issue_buckets"]),
        },
        "by_suite": dict(sorted(by_suite.items())),
        "by_device": dict(sorted(by_device.items())),
        "failure_buckets": dict(failure_buckets.most_common()),
        "confusion_matrix": {k: dict(v) for k, v in sorted(confusion_matrix.items())},
        "result_mode_matrix": {k: dict(v) for k, v in sorted(result_mode_matrix.items())},
        "retry_timeout_harness": dict(retry_timeout_harness),
        "slot_fill": dict(slot_fill),
        "stuck_mode": stuck_mode,
        "artifacts": artifacts,
    }


def render_markdown(summary: dict[str, Any]) -> str:
    """Render a compact human-readable summary for PR comments or step summaries."""
    totals = summary["totals"]
    validity = summary["validity"]
    lines = [
        "# Test Evidence Metrics Summary",
        "",
        f"Records: {totals.get('records', 0)} · Cases: {totals.get('total', 0)} · "
        f"Passed: {totals.get('passed', 0)} · Failed: {totals.get('failed', 0)} · "
        f"XFail: {totals.get('xfail', 0)}",
        f"Validity: {validity.get('valid_records', 0)} valid / {validity.get('invalid_records', 0)} invalid",
        "",
    ]
    if summary.get("failure_buckets"):
        lines.extend(["## Failure buckets", ""])
        for bucket, count in summary["failure_buckets"].items():
            lines.append(f"- `{bucket}`: {count}")
        lines.append("")
    if summary.get("stuck_mode"):
        lines.extend(["## Stuck-mode suspects", ""])
        for item in summary["stuck_mode"]:
            expected = ", ".join(f"`{x}`" for x in item["different_expected_tools"])
            lines.append(f"- actual `{item['actual_tool']}` appeared for {expected}")
        lines.append("")
    if validity.get("issue_buckets"):
        lines.extend(["## Evidence validity issues", ""])
        for issue, count in validity["issue_buckets"].items():
            lines.append(f"- `{issue}`: {count}")
        lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def main() -> None:
    parser = argparse.ArgumentParser(description="Summarise normalised test evidence metrics.")
    parser.add_argument("input", type=Path, help="Evidence JSON file or directory containing evidence JSON files")
    parser.add_argument("--out", type=Path, help="Optional output path for summary JSON")
    parser.add_argument("--markdown", action="store_true", help="Print a compact markdown summary instead of JSON")
    args = parser.parse_args()

    summary = summarise(discover_evidence(args.input))
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    if args.markdown:
        print(render_markdown(summary), end="")
    else:
        print(json.dumps(summary, indent=2, sort_keys=True))


if __name__ == "__main__":
    try:
        main()
    except BrokenPipeError:
        sys.exit(0)
