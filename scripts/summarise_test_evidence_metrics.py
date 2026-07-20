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
from jsonschema import Draft7Validator, FormatChecker

from summarise_test_report import build_device_registry_index


SCHEMA_PATH = Path(__file__).resolve().parent / "testdata" / "test_evidence.schema.json"
EVIDENCE_SCHEMA = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
EVIDENCE_VALIDATOR = Draft7Validator(EVIDENCE_SCHEMA, format_checker=FormatChecker())


DEVICE_REGISTRY = build_device_registry_index()



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
    if not isinstance(device, dict):
        return "unknown"
    raw_id = str(device.get("id") or "unknown")
    reference = DEVICE_REGISTRY.get(raw_id.lower())
    return str(reference.get("id", raw_id)) if reference else raw_id


def _device_context(record: dict[str, Any]) -> dict[str, Any]:
    device = record.get("device") if isinstance(record.get("device"), dict) else {}
    assert isinstance(device, dict)
    reference = DEVICE_REGISTRY.get(str(device.get("id") or "").lower(), {})
    merged = {**device, **reference}
    return {
        "id": _device_id(record),
        "label": merged.get("label"),
        "model": merged.get("model"),
        "tier": merged.get("tier"),
        "android_api": merged.get("android_api"),
        "execution": merged.get("execution"),
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
        refs = source.get("artifact_refs")
        if isinstance(refs, list):
            for value in refs:
                if isinstance(value, str) and value:
                    artifacts.append({"field": "artifact_ref", "path": value})
    return artifacts


def _schema_issues(record: dict[str, Any]) -> list[str]:
    """Return deterministic, path-qualified full-schema diagnostics."""
    issues: list[str] = []
    for error in sorted(EVIDENCE_VALIDATOR.iter_errors(record), key=lambda item: list(item.path)):
        path = ".".join(str(part) for part in error.absolute_path) or "$"
        issues.append(f"schema:{path}: {error.message}")
    return issues


def validate_record(record: dict[str, Any] | None, load_errors: list[str]) -> tuple[bool, list[str]]:
    """Return validity and issue buckets for a normalised evidence record."""
    issues = list(load_errors)
    if record is None:
        return False, issues or ["missing_record"]

    schema_record = {key: value for key, value in record.items() if not key.startswith("_")}
    issues.extend(_schema_issues(schema_record))
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
    suite = str(record.get("suite") or "")
    is_wake = suite == "wake_word_acoustic_reliability"
    # Wake records: accept both new (total = valid) and old (total = len(cases)) formats
    if is_wake:
        if total != passed + failed and total != len(cases):
            issues.append("summary_total_mismatch")
    elif total != len(cases):
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
        if status == "failed":
            if is_wake:
                # Wake records use failure_classification or invalid_reason
                has_wake_diag = bool(case.get("failure_classification") or case.get("invalid_reason"))
                if not (has_failures or has_category or has_wake_diag):
                    issues.append(f"case_{idx}:failed_without_diagnostic")
            elif not (has_failures or has_category):
                issues.append(f"case_{idx}:failed_without_diagnostic")


    device = record.get("device")
    if not isinstance(device, dict):
        issues.append("device_not_object")
    else:
        if not device.get("id"):
            issues.append("device_missing_id")
        if not (device.get("label") or device.get("model") or device.get("execution")):
            issues.append("device_context_sparse")
        raw_device_id = str(device.get("id") or "")
        reference = DEVICE_REGISTRY.get(raw_device_id.lower())
        if reference is None:
            issues.append("device_unknown_id")
        else:
            for field in (
                "label",
                "manufacturer",
                "model",
                "soc",
                "tier",
                "android_api",
                "execution",
            ):
                if device.get(field) != reference.get(field):
                    issues.append(f"device_registry_mismatch:{field}")

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

def _empty_wake_bucket() -> dict[str, int | float]:
    return {
        "attempts": 0,
        "passed": 0,
        "failed": 0,
        "invalid": 0,
        "valid": 0,
        "pass_rate": 0.0,
    }


def _increment_wake_bucket(bucket: dict[str, int | float], status: str) -> None:
    bucket["attempts"] = int(bucket["attempts"]) + 1
    if status in {"passed", "failed", "invalid"}:
        bucket[status] = int(bucket[status]) + 1
    if status in {"passed", "failed"}:
        bucket["valid"] = int(bucket["valid"]) + 1


def _finalise_wake_bucket(bucket: dict[str, int | float]) -> dict[str, int | float]:
    valid = int(bucket["valid"])
    bucket["pass_rate"] = round(int(bucket["passed"]) / valid, 4) if valid else 0.0
    return bucket



def _wake_condition_key(device_id: str, idle_s: int, trial_type: str) -> tuple[str, int, str]:
    return device_id, idle_s, trial_type


def _wake_timing_samples(raw_case: dict[str, Any], device_id: str) -> list[dict[str, Any]]:
    target_timing = raw_case.get("target_timing")
    if not isinstance(target_timing, dict):
        return []
    if target_timing.get("clock_domain") != "target_device_elapsed_realtime":
        return []
    events = target_timing.get("events")
    if not isinstance(events, list):
        return []
    by_type = {
        str(event.get("t")): event
        for event in events
        if isinstance(event, dict) and isinstance(event.get("m"), int)
    }
    metrics = (
        ("detector_ready_to_activation_ms", "STAGE3_READY", "VERIFIED_ACTIVATION"),
        ("activation_to_callback_ms", "VERIFIED_ACTIVATION", "WAKE_CALLBACK_INVOKED"),
    )
    samples: list[dict[str, Any]] = []
    for metric, start_type, end_type in metrics:
        start = by_type.get(start_type)
        end = by_type.get(end_type)
        if start is None or end is None:
            continue
        duration_ms = int(end["m"]) - int(start["m"])
        if duration_ms < 0:
            continue
        samples.append({
            "device_id": device_id,
            "trial_id": raw_case.get("trial_id") or raw_case.get("name"),
            "idle_seconds": _safe_int(raw_case.get("idle_seconds")),
            "trial_type": str(raw_case.get("trial_type") or "unknown"),
            "metric": metric,
            "duration_ms": duration_ms,
            "clock_domain": "target_device_elapsed_realtime",
        })
    return samples


def _finalise_timing(samples: list[dict[str, Any]]) -> dict[str, Any]:
    grouped: dict[tuple[str, str], list[int]] = defaultdict(list)
    for sample in samples:
        grouped[(str(sample["device_id"]), str(sample["metric"]))].append(
            int(sample["duration_ms"])
        )
    aggregates = []
    for (device_id, metric), durations in sorted(grouped.items()):
        values = sorted(durations)
        aggregates.append({
            "device_id": device_id,
            "metric": metric,
            "clock_domain": "target_device_elapsed_realtime",
            "sample_count": len(values),
            "min_ms": values[0],
            "p50_ms": values[(len(values) - 1) // 2],
            "p95_ms": values[max(0, (len(values) * 95 + 99) // 100 - 1)],
            "max_ms": values[-1],
        })
    return {"samples": samples, "aggregates": aggregates}


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
    wake_overall = _empty_wake_bucket()
    wake_by_device: dict[str, dict[str, int | float]] = defaultdict(_empty_wake_bucket)
    wake_by_run_kind: dict[str, dict[str, int | float]] = defaultdict(_empty_wake_bucket)
    wake_by_gate_mode: dict[str, dict[str, int | float]] = defaultdict(_empty_wake_bucket)
    wake_failure_classifications: Counter[str] = Counter()
    wake_invalid_reasons: Counter[str] = Counter()
    wake_release_gate = Counter({
        "records": 0,
        "successful": 0,
        "failed": 0,
        "incomplete": 0,
        "provenance_unverified": 0,
        "feasibility_only": 0,
    })
    wake_completion = Counter({
        "total_required": 0,
        "completed": 0,
        "missing": 0,
        "duplicate_valid_positions": 0,
    })
    wake_conditions: dict[tuple[str, int, str], Counter[str]] = defaultdict(
        lambda: Counter({
            "required_positions": 0,
            "attempts": 0,
            "valid_attempts": 0,
            "passed_attempts": 0,
            "failed_attempts": 0,
            "invalid_attempts": 0,
        })
    )
    wake_latest_release: dict[str, Any] | None = None
    wake_attempted_positions: dict[tuple[str, int, str], set[tuple[str, str]]] = defaultdict(set)
    wake_valid_outcomes: dict[tuple[str, int, str], Counter[tuple[str, str]]] = defaultdict(Counter)
    wake_off_matrix_attempts = 0
    wake_timing_samples: list[dict[str, Any]] = []

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
        raw_wake = record.get("wake_reliability") if suite == "wake_word_acoustic_reliability" else None
        wake = raw_wake if is_valid and isinstance(raw_wake, dict) else None
        expected_counts: dict[str, Any] = {}
        if wake is not None:
            if wake.get("feasibility_only") is True:
                wake_release_gate["feasibility_only"] += 1
            if wake.get("gate_mode") == "release_gate":
                wake_release_gate["records"] += 1
                release_success = (
                    wake.get("release_gate_success") is True
                    and wake.get("complete") is True
                    and wake.get("complete_valid_matrix") is True
                    and wake.get("all_required_passed") is True
                    and wake.get("release_provenance_verified") is True
                    and wake.get("cleanup_verified") is True
                    and wake.get("feasibility_only") is not True
                )
                wake_release_gate["successful" if release_success else "failed"] += 1
                if wake.get("complete") is not True or wake.get("complete_valid_matrix") is not True:
                    wake_release_gate["incomplete"] += 1
                if wake.get("release_provenance_verified") is not True:
                    wake_release_gate["provenance_unverified"] += 1
                release_timestamp = str(record.get("timestamp") or "")
                if (
                    wake_latest_release is None
                    or release_timestamp >= wake_latest_release["latest_timestamp"]
                ):
                    wake_latest_release = {
                        "latest_successful": release_success,
                        "latest_timestamp": release_timestamp,
                        "latest_run_id": str(record.get("run_id") or ""),
                    }
            raw_expected_counts = wake.get("expected_valid_counts")
            if isinstance(raw_expected_counts, dict):
                expected_counts = raw_expected_counts
                for position_id, required_count in expected_counts.items():
                    parts = str(position_id).split(":")
                    if len(parts) != 3:
                        continue
                    try:
                        idle_s = int(parts[0])
                    except ValueError:
                        continue
                    condition = wake_conditions[
                        _wake_condition_key(device_id, idle_s, parts[1])
                    ]
                    condition["required_positions"] += _safe_int(required_count)
        for raw_case in cases:
            if not isinstance(raw_case, dict):
                continue
            status = _case_status(raw_case)
            # For wake_reliability suite, skip generic totals for explicitly
            # invalid attempts — environment/setup failures are diagnostic,
            # not product reliability measurements.
            if not (wake is not None and str(raw_case.get("status") or "").lower() == "invalid"):
                _increment_status(totals, status)
                _increment_status(by_suite[suite], status)
                _increment_status(by_device[device_id], status)
            if wake is not None:
                wake_status = str(raw_case.get("status") or ("passed" if raw_case.get("passed") else "failed"))
                run_kind = str(wake.get("run_kind") or "unknown")
                gate_mode = str(wake.get("gate_mode") or "unknown")
                for bucket in (
                    wake_overall,
                    wake_by_device[device_id],
                    wake_by_run_kind[run_kind],
                    wake_by_gate_mode[gate_mode],
                ):
                    _increment_wake_bucket(bucket, wake_status)
                classification = raw_case.get("failure_classification")
                if classification:
                    wake_failure_classifications[str(classification)] += 1
                invalid_reason = raw_case.get("invalid_reason")
                if invalid_reason:
                    wake_invalid_reasons[str(invalid_reason)] += 1

                position_id = raw_case.get("required_position_id")
                required_count = expected_counts.get(position_id) if isinstance(position_id, str) else None
                if isinstance(position_id, str) and _safe_int(required_count) > 0:
                    parts = position_id.split(":")
                    if len(parts) == 3:
                        try:
                            condition_key = _wake_condition_key(device_id, int(parts[0]), parts[1])
                        except ValueError:
                            condition_key = None
                        if condition_key is not None:
                            position_key = (relpath, position_id)
                            condition = wake_conditions[condition_key]
                            condition["attempts"] += 1
                            wake_attempted_positions[condition_key].add(position_key)
                            if wake_status == "invalid":
                                condition["invalid_attempts"] += 1
                            elif wake_status in {"passed", "failed"}:
                                condition["valid_attempts"] += 1
                                condition[f"{wake_status}_attempts"] += 1
                                wake_valid_outcomes[condition_key][position_key] += 1
                else:
                    wake_off_matrix_attempts += 1
                wake_timing_samples.extend(_wake_timing_samples(raw_case, device_id))

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
                    "source_path": relpath,
                })

    completion_by_condition = []
    for (condition_device, idle_s, trial_type), counts in sorted(wake_conditions.items()):
        condition_key = (condition_device, idle_s, trial_type)
        required = counts["required_positions"]
        attempted_positions = len(wake_attempted_positions[condition_key])
        valid_outcomes = wake_valid_outcomes[condition_key]
        completed = len(valid_outcomes)
        duplicates = sum(max(0, count - 1) for count in valid_outcomes.values())
        retries = max(0, counts["attempts"] - attempted_positions)
        wake_completion["total_required"] += required
        wake_completion["completed"] += completed
        wake_completion["missing"] += max(0, required - completed)
        wake_completion["duplicate_valid_positions"] += duplicates
        completion_by_condition.append({
            "device_id": condition_device,
            "idle_seconds": idle_s,
            "trial_type": trial_type,
            **dict(counts),
            "attempted_positions": attempted_positions,
            "completed_positions": completed,
            "retry_attempts": retries,
            "duplicate_valid_positions": duplicates,
            "missing_positions": max(0, required - completed),
            "complete": required > 0 and completed == required and duplicates == 0,
        })


    wake_metrics = {
        "overall": _finalise_wake_bucket(wake_overall),
        "by_device": {
            key: _finalise_wake_bucket(value)
            for key, value in sorted(wake_by_device.items())
        },
        "by_run_kind": {
            key: _finalise_wake_bucket(value)
            for key, value in sorted(wake_by_run_kind.items())
        },
        "by_gate_mode": {
            key: _finalise_wake_bucket(value)
            for key, value in sorted(wake_by_gate_mode.items())
        },
        "failure_classifications": dict(wake_failure_classifications.most_common()),
        "invalid_reasons": dict(wake_invalid_reasons.most_common()),
        "off_matrix_attempts": wake_off_matrix_attempts,
        "release_gate": {**dict(wake_release_gate), **(wake_latest_release or {})},
        "completion": dict(wake_completion),
        "completion_by_condition": completion_by_condition,
        "timing": _finalise_timing(wake_timing_samples),
    }

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
        "wake_reliability": wake_metrics,
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
