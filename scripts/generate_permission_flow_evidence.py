#!/usr/bin/env python3
"""Generate normalised #1113-compatible evidence for permission-flow tests.

Connected mode parses JUnit XML from PermissionFlowContextualSmokeTest. Standalone
mode emits planned entries marked not-run, so PRs can show the suite shape before
S21 execution is available.
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

HERE = Path(__file__).resolve().parent
REPO = "NickMonrad/kernel-ai-assistant"
SCHEMA_VERSION = "1.0"

PERMISSION_FLOW_CASES: list[dict[str, Any]] = [
    {
        "name": "handsFreeCalling_revokedShowsContextualSurface",
        "category": "runtime_permission",
        "screen": "Actions contextual hands-free calling dialog",
        "assertion": "CALL_PHONE revoked shows Open dialer / Allow hands-free / Not now without direct-call success",
    },
    {
        "name": "handsFreeCalling_permanentDenialNavigatesToAppPermissions",
        "category": "manual_repair",
        "screen": "Actions repair dialog -> Settings / App Permissions",
        "assertion": "Permanent CALL_PHONE denial exposes Open App Permissions and navigates to the internal permissions screen",
    },
    {
        "name": "dndSpecialAccess_settingsRoundTripShowsBlockedRepair",
        "category": "special_access_repair",
        "screen": "Actions DND dialog -> Android DND access settings -> blocked repair dialog",
        "assertion": "DND settings round-trip without grant re-checks state and does not record a successful DND action",
    },
]

SCHEMA_FAILURE_CATEGORIES = {
    "harness_error",
    "missing_marker",
    "model_tool_generation_miss",
    "wrong_tool",
    "wrong_result_mode",
    "field_mismatch",
    "conversational_fallback",
    "retry_seen",
    "slot_fill_seen",
    "device_environment_error",
    "timeout",
}

FAILURE_CATEGORY_PATTERNS: list[tuple[re.Pattern[str], str]] = [
    (re.compile(r"timed?[- ]?out|TimeoutException", re.IGNORECASE), "timeout"),
    (re.compile(r"device.*unavailable|adb|no such device|already has DND", re.IGNORECASE), "device_environment_error"),
]


def load_devices() -> dict[str, dict[str, Any]]:
    from summarise_test_report import load_devices as load_devices_impl

    return load_devices_impl()


def resolve_device(device_id: str) -> dict[str, Any]:
    from summarise_test_report import build_device_obj

    return build_device_obj(device_id, load_devices())


def find_test_result_xmls(results_dir: Path) -> list[Path]:
    if not results_dir.exists():
        print(f"WARNING: results directory not found: {results_dir}", file=sys.stderr)
        return []
    xml_files = list(results_dir.rglob("TEST-*.xml"))
    return xml_files or list(results_dir.rglob("*.xml"))


def categorize_failure(message: str) -> str:
    for pattern, category in FAILURE_CATEGORY_PATTERNS:
        if pattern.search(message):
            return category
    return "harness_error"


def parse_test_results(xml_paths: list[Path]) -> dict[str, dict[str, Any]]:
    results: dict[str, dict[str, Any]] = {}
    for xml_path in xml_paths:
        try:
            root = ET.parse(xml_path).getroot()
        except ET.ParseError as exc:
            print(f"WARNING: could not parse {xml_path}: {exc}", file=sys.stderr)
            continue
        for testcase in root.iter("testcase"):
            name = testcase.get("name", "")
            if not name:
                continue
            try:
                time_sec = float(testcase.get("time", "0"))
            except ValueError:
                time_sec = 0.0
            skipped_el = testcase.find("skipped")
            failure_el = testcase.find("failure")
            error_el = testcase.find("error")
            if skipped_el is not None:
                message = skipped_el.get("message", "") or skipped_el.text or "skipped"
                results[name] = {
                    "passed": False,
                    "failures": [f"skipped: {message.strip()}"],
                    "time": time_sec,
                    "failure_category": "device_environment_error",
                }
            elif failure_el is not None:
                message = failure_el.get("message", "") or failure_el.text or "failure"
                results[name] = {
                    "passed": False,
                    "failures": [message.strip()],
                    "time": time_sec,
                    "failure_category": categorize_failure(message),
                }
            elif error_el is not None:
                message = error_el.get("message", "") or error_el.text or "error"
                results[name] = {
                    "passed": False,
                    "failures": [message.strip()],
                    "time": time_sec,
                    "failure_category": "harness_error",
                }
            else:
                results[name] = {
                    "passed": True,
                    "failures": [],
                    "time": time_sec,
                    "failure_category": None,
                }
    return results


def build_case(case_def: dict[str, Any], result: dict[str, Any] | None) -> dict[str, Any]:
    passed = bool(result and result.get("passed"))
    failures = result.get("failures", []) if result else ["not run"]
    failure_category = None if passed else (result.get("failure_category") if result else "harness_error")
    case = {
        "name": case_def["name"],
        "passed": passed,
        "category": case_def["category"],
        "screen": case_def["screen"],
        "assertion": case_def["assertion"],
        "expected_tool": None,
        "actual_tool": None,
        "expected_result_mode": "success",
        "actual_result_mode": "success" if passed else "unknown",
        "chip_present": False,
        "skill_result_present": False,
        "message_saved": False,
        "retry_seen": False,
        "slot_fill_seen": False,
        "failure_category": failure_category if failure_category in SCHEMA_FAILURE_CATEGORIES else "harness_error",
        "failures": failures,
    }
    if result and "time" in result:
        case["duration_seconds"] = round(float(result["time"]), 3)
    return case


def to_schema_evidence(normalised: dict[str, Any]) -> dict[str, Any]:
    published = json.loads(json.dumps(normalised))
    schema_cases = []
    for case in published.get("cases", []):
        schema_case = dict(case)
        for local_key in ("category", "screen", "assertion", "duration_seconds"):
            schema_case.pop(local_key, None)
        if schema_case.get("passed"):
            schema_case["failure_category"] = None
        elif schema_case.get("failure_category") not in SCHEMA_FAILURE_CATEGORIES:
            schema_case["failure_category"] = "harness_error"
        schema_cases.append(schema_case)
    published["cases"] = schema_cases
    return published


def validate_against_schema(normalised: dict[str, Any]) -> list[str]:
    schema_path = HERE / "testdata" / "test_evidence.schema.json"
    schema_data = to_schema_evidence(normalised)
    from jsonschema import Draft7Validator, FormatChecker

    schema = json.loads(schema_path.read_text(encoding="utf-8"))
    validator = Draft7Validator(schema, format_checker=FormatChecker())
    return [
        f"schema: {'/'.join(str(p) for p in err.path)}: {err.message}"
        for err in validator.iter_errors(schema_data)
    ]


def write_json(normalised: dict[str, Any], path: Path) -> None:
    path.write_text(json.dumps(to_schema_evidence(normalised), indent=2) + "\n")
    print(f"Evidence JSON: {path}")


def write_csv(normalised: dict[str, Any], path: Path) -> None:
    fieldnames = ["name", "passed", "category", "screen", "assertion", "failure_category", "failures", "duration_seconds"]
    with open(path, "w", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames, extrasaction="ignore")
        writer.writeheader()
        for case in normalised["cases"]:
            row = dict(case)
            row["failures"] = "; ".join(case.get("failures", []))
            writer.writerow(row)
    print(f"Case CSV:      {path}")


def write_markdown(normalised: dict[str, Any], path: Path) -> None:
    summary = normalised["summary"]
    device = normalised["device"]
    lines = [
        f"# Permission Flows — PR #{normalised.get('pr', '?')}",
        "",
        f"**Suite:** `{normalised['suite']}`",
        f"**Source:** `{normalised['source']}`",
        f"**Device:** {device.get('label', '?')} ({device.get('id', '?')})",
        f"**Branch:** `{normalised['branch']}`",
        f"**Commit:** `{normalised['commit'][:12]}`",
        f"**Date:** {normalised['timestamp']}",
        "",
        "## Summary",
        "",
        "| Total | Passed | Failed | Pass rate |",
        "|-------|--------|--------|-----------|",
        f"| {summary['total']} | {summary['passed']} | {summary['failed']} | {summary['pass_rate']:.1%} |",
        "",
        "## Cases",
        "",
        "| Test | Category | Status | Assertion | Details |",
        "|------|----------|--------|-----------|---------|",
    ]
    for case in normalised["cases"]:
        status = "PASS" if case["passed"] else "FAIL"
        detail = "" if case["passed"] else (case.get("failure_category") or "")
        if not case["passed"] and case.get("failures"):
            detail = f"{detail}: {case['failures'][0]}" if detail else case["failures"][0]
        lines.append(
            f"| `{case['name']}` | {case['category']} | {status} | {case['assertion']} | {detail} |"
        )
    lines.extend([
        "",
        "## OS/OEM limitations",
        "",
        "- DND notification-policy access toggling is OEM-specific. The connected test verifies settings launch/return and blocked-state repair without claiming stable automatic grant/revoke.",
        "- Runtime CALL_PHONE state is reset with `pm revoke` and permission flags where Android allows shell control.",
        "",
        "_Generated by `scripts/generate_permission_flow_evidence.py`_",
        "",
    ])
    path.write_text("\n".join(lines))
    print(f"Summary MD:    {path}")


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Generate normalised permission-flow test evidence")
    parser.add_argument("--source", choices=["on_device", "ci"], default="on_device")
    parser.add_argument("--suite", default="permission_flows")
    parser.add_argument("--pr", type=int, required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--branch", required=True)
    parser.add_argument("--device-id", default="s21-exynos")
    parser.add_argument("--results-dir", type=Path, default=None)
    parser.add_argument("--out-dir", type=Path, default=None)
    parser.add_argument("--adb-serial", default=None)
    args = parser.parse_args(argv)
    if args.out_dir is None:
        args.out_dir = Path(f"scripts/test-reports/normalised/pr-{args.pr}")
    return args


def main(argv: list[str] | None = None) -> None:
    args = parse_args(argv)
    args.out_dir.mkdir(parents=True, exist_ok=True)
    device = resolve_device(args.device_id)
    if args.adb_serial:
        device["serial"] = args.adb_serial

    test_results: dict[str, dict[str, Any]] = {}
    if args.results_dir:
        xmls = find_test_result_xmls(args.results_dir)
        test_results = parse_test_results(xmls)
        print(f"Parsed {len(test_results)} test results from {len(xmls)} XML files")

    cases = [build_case(case_def, test_results.get(case_def["name"])) for case_def in PERMISSION_FLOW_CASES]
    passed = sum(1 for case in cases if case["passed"])
    total = len(cases)
    failed = total - passed
    pass_rate = round(passed / total, 4) if total else 0.0
    timestamp = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    normalised = {
        "schema_version": SCHEMA_VERSION,
        "source": args.source,
        "suite": args.suite,
        "timestamp": timestamp,
        "repo": REPO,
        "branch": args.branch,
        "commit": args.commit,
        "pr": args.pr,
        "release": None,
        "run_id": f"{args.source}-{timestamp}-{args.device_id}",
        "device": device,
        "model": {"name": "not_applicable", "runtime": "not_applicable", "backend": "not_applicable"},
        "summary": {"total": total, "passed": passed, "failed": failed, "pass_rate": pass_rate},
        "cases": cases,
        "artifact_refs": [],
    }

    errors = validate_against_schema(normalised)
    if errors:
        print(f"VALIDATION ERRORS ({len(errors)}):", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        sys.exit(1)

    base = args.out_dir / f"{timestamp}_{args.device_id}_{args.suite}"
    write_json(normalised, base.with_suffix(".json"))
    write_csv(normalised, base.with_suffix(".csv"))
    write_markdown(normalised, base.with_suffix(".md"))
    print(f"\nOutput directory: {args.out_dir}")
    print(f"Summary: {passed}/{total} passed ({pass_rate:.1%})")


if __name__ == "__main__":
    main()
