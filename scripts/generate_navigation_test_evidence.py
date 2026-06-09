#!/usr/bin/env python3
"""Generate normalised #1113-compatible test evidence for navigation/back-stack
regression tests on Android.

Supports two modes:

1. **Connected device test mode** (--results-dir):
   Parses JUnit XML test results from an ADB instrumentation run and maps each
   test case to a normalised evidence entry.

2. **Standalone mode** (default):
   Generates planned/expected evidence entries for the full navigation back-stack
   suite, marking each as ``passed: false`` with ``harness_error`` / ``no_run``
   to indicate the tests were not yet executed.

Usage (connected device):

::

    python3 scripts/generate_navigation_test_evidence.py \\
        --source on_device \\
        --suite navigation_backstack \\
        --pr 1154 \\
        --commit \"$(git rev-parse HEAD)\" \\
        --branch \"$(git branch --show-current)\" \\
        --device-id s23-ultra \\
        --results-dir app/build/outputs/androidTest-results/connected/ \\
        --out-dir scripts/test-reports/normalised/pr-1154

Usage (standalone / pre-run):

::

    python3 scripts/generate_navigation_test_evidence.py \\
        --source on_device \\
        --suite navigation_backstack \\
        --pr 1154 \\
        --commit \"$(git rev-parse HEAD)\" \\
        --branch \"$(git branch --show-current)\" \\
        --device-id s23-ultra \\
        --out-dir scripts/test-reports/normalised/pr-1154
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import re
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

HERE = Path(__file__).resolve().parent
REPO = "NickMonrad/kernel-ai-assistant"
SCHEMA_VERSION = "1.0"

NAVIGATION_CASES: list[dict[str, Any]] = [
    # ── Route matrix (combined test verifying all 16 Tools rows) ──
    {"name": "toolsRouteMatrix_allRowsNavigateAndBack", "category": "tools_row_navigation"},
    # ── Primary tab switching ──
    {"name": "tabs_chatsActionsTools_roundTrip", "category": "tab_switching"},
    {"name": "tools_childDestination_back_toTools", "category": "tab_switching"},
    {"name": "tools_childDestination_actions_thenTools", "category": "tab_switching"},
    {"name": "tools_childDestination_chats_thenTools", "category": "tab_switching"},
    {"name": "actions_tools_switching", "category": "tab_switching"},
    # ── Parameterised / transient route tests ──
    {"name": "actions_draftRoute_dismiss_back_toTools", "category": "parameterised_route"},
    {"name": "actions_draftRoute_chats_then_tools", "category": "parameterised_route"},
    # ── Drawer transition tests ──
    {"name": "drawer_opens_fromTools", "category": "drawer"},
    {"name": "drawer_navigatesFromTools_predictable_stack", "category": "drawer"},
    {"name": "drawer_navigatesFromChats", "category": "drawer"},
    # ── Duplicate-stack / repeated-tap tests ──
    {"name": "repeatedToolsTab_noDuplicateStacks", "category": "repeated_tap"},
    {"name": "repeatedTabSwitches_noStaleState", "category": "repeated_tap"},
    {"name": "reopenSameChildDestination_noDuplicateStack", "category": "repeated_tap"},
    {"name": "repeatedToolsRowTap_noDuplicateStacks", "category": "repeated_tap"},
    {"name": "toolsLearn_example_dismissActions_returnToTools", "category": "repeated_tap"},
    # ── Screenshot capture tests ──
    {"name": "captureScreenshot_toolsHub", "category": "screenshot"},
    {"name": "captureScreenshot_toolsLearnChild", "category": "screenshot"},
    {"name": "captureScreenshot_toolsChildDestination", "category": "screenshot"},
    {"name": "captureScreenshot_actionsDraftDismissedBackToTools", "category": "screenshot"},
    {"name": "captureScreenshot_drawerOpenFromTools", "category": "screenshot"},
    {"name": "captureScreenshot_afterTabSwitch", "category": "screenshot"},
]


# ── Device metadata resolvers ──────────────────────────────────────────────

def _load_devices() -> dict:
    """Load device metadata from scripts/testdata/devices.yaml."""
    import yaml  # type: ignore[import-untyped]
    path = HERE / "testdata" / "devices.yaml"
    if not path.exists():
        print(f"WARNING: devices.yaml not found at {path}", file=sys.stderr)
        return {}
    with open(path) as f:
        data = yaml.safe_load(f)
    return data.get("devices", {})


def _resolve_device(device_id: str) -> dict:
    """Build the device object for a known device ID from the registry.

    Falls back to a minimal object if the ID is not found.
    """
    devices = _load_devices()
    if device_id in devices:
        entry = devices[device_id]
        return {
            "id": device_id,
            "serial": None,  # filled from ADB later if available
            "label": entry.get("label", device_id),
            "manufacturer": entry.get("manufacturer", ""),
            "model": entry.get("model", ""),
            "soc": entry.get("soc", ""),
            "tier": entry.get("tier", "tracked"),
            "android_api": entry.get("android_api"),
            "execution": entry.get("execution", "physical"),
        }
    # Fallback
    return {
        "id": device_id,
        "serial": None,
        "label": device_id,
        "manufacturer": "Unknown",
        "model": "Unknown",
        "soc": "Unknown",
        "tier": "tracked",
        "android_api": None,
        "execution": "physical",
    }


# ── JUnit XML result parsing ──────────────────────────────────────────────

def _find_test_result_xmls(results_dir: Path) -> list[Path]:
    """Find all JUnit XML result files under results_dir.

    Connected Android tests produce files like:
      app/build/outputs/androidTest-results/connected/
        TEST-com.kernel.ai.navigation.NavigationBackStackRegressionTest.xml
    """
    if not results_dir.exists():
        print(f"WARNING: results directory not found: {results_dir}", file=sys.stderr)
        return []
    xml_files = list(results_dir.rglob("TEST-*.xml"))
    if not xml_files:
        # Also check for flat XML
        xml_files = list(results_dir.glob("*.xml"))
    return xml_files


def _parse_test_results(xml_paths: list[Path]) -> dict[str, dict[str, Any]]:
    """Parse JUnit XML test results and return a map of test_name -> result info.

    Returns dict like:
      {
        "toolsRow_learn_navigatesAndBack": {
          "passed": True,
          "failures": [],
          "time": 0.523,
        },
        "testFailed_toolsRow_missing": {
          "passed": False,
          "failures": ["expected destination not found"],
          "time": 1.234,
        },
      }
    """
    results: dict[str, dict[str, Any]] = {}
    for xml_path in xml_paths:
        try:
            tree = ET.parse(xml_path)
            root = tree.getroot()
            for testcase in root.iter("testcase"):
                name = testcase.get("name", "")
                # Extract the test method name from the full qualified name
                # Class may be in the 'classname' attribute
                classname = testcase.get("classname", "")
                # Use the short method name
                test_name = name
                time_str = testcase.get("time", "0")
                try:
                    time_sec = float(time_str)
                except ValueError:
                    time_sec = 0.0

                failure_el = testcase.find("failure")
                error_el = testcase.find("error")

                if failure_el is not None:
                    msg = failure_el.get("message", "") or failure_el.text or ""
                    results[test_name] = {
                        "passed": False,
                        "failures": [msg.strip()],
                        "time": time_sec,
                        "failure_category": _categorize_failure(msg),
                    }
                elif error_el is not None:
                    msg = error_el.get("message", "") or error_el.text or ""
                    results[test_name] = {
                        "passed": False,
                        "failures": [msg.strip()],
                        "time": time_sec,
                        "failure_category": "harness_error",
                    }
                else:
                    results[test_name] = {
                        "passed": True,
                        "failures": [],
                        "time": time_sec,
                        "failure_category": None,
                    }
        except ET.ParseError as e:
            print(f"WARNING: could not parse {xml_path}: {e}", file=sys.stderr)
        except Exception as e:
            print(f"WARNING: error processing {xml_path}: {e}", file=sys.stderr)
    return results


_FAILURE_CATEGORY_PATTERNS: list[tuple[re.Pattern, str]] = [
    (re.compile(r"timed?[- ]?out", re.IGNORECASE), "timeout"),
    (re.compile(r"TimeoutException", re.IGNORECASE), "timeout"),
    (re.compile(r"assertion.*failed|expected.*but was|expected.*true|expected.*false",
                re.IGNORECASE), "navigation_state_mismatch"),
    (re.compile(r"Device.*not found|No such device|adb|device.*unavailable",
                re.IGNORECASE), "device_unavailable"),
]
def _build_case(
    case_def: dict[str, Any],
    result: dict[str, Any] | None,
) -> dict[str, Any]:
    """Build a schema-compliant case dict.

    Args:
        case_def: Case definition from NAVIGATION_CASES
        result: Parsed test result, or None if the test was not run
    """
    name = case_def["name"]
    passed = result["passed"] if result else False
    failures = result.get("failures", []) if result else ["not run"]
    failure_category: str | None = result.get("failure_category") if result else ("no_run" if not result else None)
    # Normalise failure_category: null for passing, string for failing
    if passed:
        failure_category = None

    case: dict[str, Any] = {
        "name": name,
        "passed": passed,
        "expected_tool": None,
        "actual_tool": None,
        "expected_result_mode": "success",
        "actual_result_mode": "success" if passed else "unknown",
        "chip_present": False,
        "skill_result_present": False,
        "message_saved": False,
        "retry_seen": False,
        "slot_fill_seen": False,
        "failure_category": failure_category,
        "failures": failures,
    }
    return case

    passed = result["passed"] if result else False
    failures = result.get("failures", []) if result else ["not run"]
    failure_category = result.get("failure_category") if result else "no_run"
    time_sec = result.get("time") if result else None

    case: dict[str, Any] = {
        "name": name,
        "passed": passed,
        "category": category,
        "expected_tool": None,
        "actual_tool": None,
        "expected_result_mode": "success",
        "actual_result_mode": "success" if passed else "unknown",
        "chip_present": False,
        "skill_result_present": False,
        "message_saved": False,
        "retry_seen": False,
        "slot_fill_seen": False,
        "failure_category": failure_category,
        "failures": failures,
    }

    if time_sec is not None:
        case["duration_seconds"] = round(time_sec, 3)

    return case


# ── Output writers ─────────────────────────────────────────────────────────


def _write_json(normalised: dict, path: Path) -> None:
    """Write the normalised evidence as pretty-printed JSON."""
    path.write_text(json.dumps(normalised, indent=2) + "\n")
    print(f"Evidence JSON: {path}")


def _write_csv(normalised: dict, path: Path) -> None:
    """Write a case-level CSV report."""
    cases = normalised.get("cases", [])
    fieldnames = [
        "name", "passed", "category", "failure_category",
        "failures", "duration_seconds",
    ]
    with open(path, "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames, extrasaction="ignore")
        writer.writeheader()
        for case in cases:
            writer.writerow({
                "name": case["name"],
                "passed": str(case["passed"]),
                "category": case.get("category", ""),
                "failure_category": case.get("failure_category", ""),
                "failures": "; ".join(case.get("failures", [])),
                "duration_seconds": str(case.get("duration_seconds", "")),
            })
    print(f"Case CSV:      {path}")


def _write_markdown(normalised: dict, path: Path) -> None:
    """Write a human-readable Markdown summary."""
    summary = normalised.get("summary", {})
    device = normalised.get("device", {})
    cases = normalised.get("cases", [])

    lines: list[str] = [
        f"# Navigation Back-Stack Regression — PR #{normalised.get('pr', '?')}",
        "",
        f"**Suite:** `{normalised.get('suite', '')}`",
        f"**Source:** `{normalised.get('source', '')}`",
        f"**Device:** {device.get('label', '?')} ({device.get('id', '?')})",
        f"**Branch:** `{normalised.get('branch', '?')}`",
        f"**Commit:** `{normalised.get('commit', '?')[:12]}`",
        f"**Date:** {normalised.get('timestamp', '?')}",
        "",
        "## Summary",
        "",
        f"| Total | Passed | Failed | Pass rate |",
        "|-------|--------|--------|-----------|",
        f"| {summary.get('total', 0)} | {summary.get('passed', 0)} | {summary.get('failed', 0)} | {summary.get('pass_rate', 0.0):.1%} |",
        "",
    ]

    # Group by category
    cat_order = [
        "tools_row_navigation",
        "tab_switching",
        "parameterised_route",
        "drawer",
        "repeated_tap",
    ]
    cat_labels = {
        "tools_row_navigation": "Tools Row Navigation",
        "tab_switching": "Tab Switching",
        "parameterised_route": "Parameterised Routes",
        "drawer": "Drawer Transitions",
        "repeated_tap": "Repeated-Tap / Duplicate Stack",
    }

    by_category: dict[str, list[dict]] = {}
    for case in cases:
        cat = case.get("category", "other")
        by_category.setdefault(cat, []).append(case)

    for cat in cat_order:
        group = by_category.pop(cat, [])
        if not group:
            continue
        label = cat_labels.get(cat, cat.replace("_", " ").title())
        lines.append(f"## {label}")
        lines.append("")
        lines.append(f"| Test | Status | Details |")
        lines.append("|------|--------|---------|")
        for case in sorted(group, key=lambda c: c["name"]):
            status = "✅" if case["passed"] else "❌"
            detail = case.get("failure_category", "") if not case["passed"] else ""
            if not detail and case.get("failures"):
                detail = case["failures"][0][:80] if case["failures"] else ""
            lines.append(f"| {case['name']} | {status} | {detail} |")
        lines.append("")

    # Remaining categories
    for cat, group in by_category.items():
        lines.append(f"### {cat}")
        lines.append("")
        for case in group:
            icon = "✅" if case["passed"] else "❌"
            lines.append(f"- {icon} {case['name']}")
        lines.append("")

    lines.append("---")
    lines.append("")
    lines.append(f"_Generated by `scripts/generate_navigation_test_evidence.py`_")
    lines.append("")

    path.write_text("\n".join(lines))
    print(f"Summary MD:    {path}")


# ── Validation helpers ─────────────────────────────────────────────────────


def _validate_schema_compliance(normalised: dict) -> list[str]:
    """Check basic schema invariants for the normalised evidence shape."""
    errors: list[str] = []
    required_top = [
        "schema_version", "source", "suite", "timestamp",
        "repo", "branch", "commit", "pr", "release",
        "run_id", "device", "model", "summary", "cases",
    ]
    for field in required_top:
        if field not in normalised:
            errors.append(f"Missing top-level field: {field}")

    if "cases" in normalised:
        for i, case in enumerate(normalised["cases"]):
            for field in ("name", "passed", "failure_category", "failures"):
                if field not in case:
                    errors.append(f"cases[{i}]: missing '{field}'")

    if "summary" in normalised:
        s = normalised["summary"]
        for field in ("total", "passed", "failed", "pass_rate"):
            if field not in s:
                errors.append(f"summary: missing '{field}'")

        if s.get("total", 0) != s.get("passed", 0) + s.get("failed", 0):
            errors.append(
                f"summary: total ({s.get('total')}) != "
                f"passed ({s.get('passed')}) + failed ({s.get('failed')})"
            )

    # Validate pass_rate consistency
    total = normalised.get("summary", {}).get("total", 0)
    passed = normalised.get("summary", {}).get("passed", 0)
    pass_rate = normalised.get("summary", {}).get("pass_rate")
    if total > 0 and pass_rate is not None:
        expected = round(passed / total, 4)
        if abs(expected - pass_rate) > 0.001:
            errors.append(
                f"pass_rate mismatch: computed {expected}, stored {pass_rate}"
            )

    return errors


def _validate_against_schema(data: dict, schema_path: Path) -> list[str]:
    """Validate the evidence dict against the JSON Schema document.

    Uses jsonschema when available; falls back to basic structure checks.
    """
    errors: list[str] = []
    try:
        import jsonschema  # type: ignore[import-untyped]
        with open(schema_path) as f:
            schema = json.load(f)
        validator = jsonschema.Draft7Validator(schema)
        for ve in validator.iter_errors(data):
            errors.append(f"schema: {'/'.join(str(p) for p in ve.path)}: {ve.message}")
        return errors
    except ImportError:
        print("  (jsonschema not installed — skipping full schema validation)", file=sys.stderr)
        return _validate_schema_compliance(data)
    except Exception as e:
        print(f"  (schema validation error: {e})", file=sys.stderr)
        return _validate_schema_compliance(data)


# ── Main ───────────────────────────────────────────────────────────────────


def _parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    """Parse and validate CLI arguments."""
    parser = argparse.ArgumentParser(
        description="Generate normalised navigation back-stack test evidence",
    )
    parser.add_argument(
        "--source", choices=["on_device", "ci"], default="on_device",
        help="Evidence source (default: on_device)",
    )
    parser.add_argument(
        "--suite", default="navigation_backstack",
        help="Suite identifier (default: navigation_backstack)",
    )
    parser.add_argument("--pr", type=int, required=True, help="PR number")
    parser.add_argument(
        "--commit", required=True,
        help="Full commit SHA (40 hex chars)",
    )
    parser.add_argument(
        "--branch", required=True,
        help="Branch name",
    )
    parser.add_argument(
        "--device-id", default="s23-ultra",
        help="Device ID from devices.yaml (default: s23-ultra)",
    )
    parser.add_argument(
        "--results-dir", type=Path, default=None,
        help="Path to connected test result XMLs "
             "(e.g. app/build/outputs/androidTest-results/connected/)",
    )
    parser.add_argument(
        "--out-dir", type=Path, default=Path("scripts/test-reports/normalised/pr-1154"),
        help="Output directory for generated evidence files",
    )
    parser.add_argument(
        "--adb-serial", default=None,
        help="ADB device serial (for device metadata)",
    )
    return parser.parse_args(argv)


def main() -> None:
    args = _parse_args()
    out_dir = args.out_dir
    out_dir.mkdir(parents=True, exist_ok=True)

    # ── Resolve device metadata ──
    device = _resolve_device(args.device_id)
    if args.adb_serial:
        device["serial"] = args.adb_serial

    # ── Load test results (if provided) ──
    test_results: dict[str, dict[str, Any]] = {}
    if args.results_dir:
        xml_files = _find_test_result_xmls(args.results_dir)
        if xml_files:
            test_results = _parse_test_results(xml_files)
            print(f"Parsed {len(test_results)} test results from {len(xml_files)} XML files")
        else:
            print(f"WARNING: no test result XMLs found in {args.results_dir}", file=sys.stderr)

    # ── Build cases ──
    cases: list[dict[str, Any]] = []
    for case_def in NAVIGATION_CASES:
        name = case_def["name"]
        result = test_results.get(name)
        case = _build_case(case_def, result)
        cases.append(case)

    # ── Compute summary ──
    passed = sum(1 for c in cases if c["passed"])
    failed = sum(1 for c in cases if not c["passed"])
    total = len(cases)
    pass_rate = round(passed / total, 4) if total > 0 else 0.0

    timestamp = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    run_id = f"on_device-{timestamp}-{args.device_id}"

    normalised: dict[str, Any] = {
        "schema_version": SCHEMA_VERSION,
        "source": args.source,
        "suite": args.suite,
        "timestamp": timestamp,
        "repo": REPO,
        "branch": args.branch,
        "commit": args.commit,
        "pr": args.pr,
        "release": None,
        "run_id": run_id,
        "device": device,
        "model": {
            "name": None,
            "runtime": None,
            "backend": None,
        },
        "summary": {
            "total": total,
            "passed": passed,
            "failed": failed,
            "pass_rate": pass_rate,
        },
        "cases": cases,
    }

    # ── Validate ──
    schema_path = HERE / "testdata" / "test_evidence.schema.json"
    validation_errors = _validate_against_schema(normalised, schema_path)
    if validation_errors:
        print(f"VALIDATION ERRORS ({len(validation_errors)}):", file=sys.stderr)
        for err in validation_errors:
            print(f"  - {err}", file=sys.stderr)
        print("Continuing with output generation despite validation errors.", file=sys.stderr)

    # ── Write outputs ──
    suite_slug = args.suite
    device_slug = args.device_id
    base = out_dir / f"{timestamp}_{device_slug}_{suite_slug}"

    _write_json(normalised, base.with_name(f"{base.name}.json"))
    _write_csv(normalised, base.with_name(f"{base.name}.csv"))
    _write_markdown(normalised, base.with_name(f"{base.name}.md"))

    print(f"\nOutput directory: {out_dir}")
    print(f"Summary: {passed}/{total} passed ({pass_rate:.1%})")

    if validation_errors:
        sys.exit(1)


if __name__ == "__main__":
    main()
