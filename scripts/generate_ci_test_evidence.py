#!/usr/bin/env python3
"""Generate CI test evidence for kernel-ai-assistant.

Runs Python-level CI checks (compile, schema validation) and accepts
external check results via ``--report`` flags.  Produces normalised
evidence JSON, CSV, Markdown, and (when $GITHUB_STEP_SUMMARY is set)
a GitHub Actions job summary.

External check results (e.g. ``gradle_build=0``) are supplied via one or
more ``--report name=exit_code`` flags.  The script also runs its own
internal checks (Python compile, fixture schema validation) automatically.

Usage:

    python3 scripts/generate_ci_test_evidence.py \\
        --out-dir /tmp/ci-evidence \\
        --commit ${{ github.sha }} \\
        --branch ${{ github.ref_name }} \\
        --pr ${{ github.event.pull_request.number }} \\
        --report gradle_build=0 \\
        --report gradle_lint=0 \\
        --report unit_tests=1
"""

from __future__ import annotations

import argparse
import csv
import datetime
import json
import os
import re
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = "NickMonrad/kernel-ai-assistant"
SCHEMA_VERSION = "1.0"
DEVICE_ID = "ubuntu-latest"

# ── Internal check runners ──────────────────────────────────────────────


def _run_py_compile() -> tuple[bool, list[str]]:
    """``python3 -m py_compile`` every .py under ``scripts/``."""
    errors: list[str] = []
    for f in sorted(HERE.glob("**/*.py")):
        result = subprocess.run(
            [sys.executable, "-m", "py_compile", str(f)],
            capture_output=True, text=True,
        )
        if result.returncode != 0:
            stderr = (result.stderr or "").strip()
            rel = f.relative_to(HERE) if HERE in f.parents else f.name
            errors.append(f"{rel}: {stderr}" if stderr else f"{rel}: compile error")
    return (len(errors) == 0, errors)


def _run_schema_validation() -> tuple[bool, list[str]]:
    """Validate evidence fixtures against the schema (basic structural check).

    Without ``jsonschema`` this is a pragmatic subset: required-fields
    presence and type-surface checks on ``*_evidence.json`` fixtures.
    """
    schema_path = HERE / "testdata" / "test_evidence.schema.json"
    fixture_dir = HERE / "testdata" / "fixtures"
    if not schema_path.exists():
        return (False, [f"Schema file not found: {schema_path}"])
    if not fixture_dir.is_dir():
        return (False, [f"Fixture directory not found: {fixture_dir}"])

    try:
        schema = json.loads(schema_path.read_text())
    except (json.JSONDecodeError, OSError) as e:
        return (False, [f"Cannot load schema: {e}"])

    required = set(schema.get("required", []))
    errors: list[str] = []

    for f in sorted(fixture_dir.glob("*.json")):
        if "_evidence" not in f.name:
            continue
        try:
            data = json.loads(f.read_text())
        except (json.JSONDecodeError, OSError) as e:
            errors.append(f"{f.name}: cannot parse — {e}")
            continue
        missing = required - set(data.keys())
        if missing:
            errors.append(f"{f.name}: missing required fields {sorted(missing)}")
        # Type-surface: summary must have total/passed/failed
        s = data.get("summary", {})
        for field in ("total", "passed", "failed"):
            if not isinstance(s.get(field), int):
                errors.append(f"{f.name}: summary.{field} is not int")
        # device.android_api must be int or null
        dev = data.get("device", {})
        api = dev.get("android_api")
        if api is not None and not isinstance(api, int):
            errors.append(f"{f.name}: device.android_api must be int or null, got {type(api).__name__}")
        # source must be ci or on_device
        source = data.get("source")
        if source not in ("ci", "on_device"):
            errors.append(f"{f.name}: source must be ci|on_device, got {source!r}")

    return (len(errors) == 0, errors)


# ── Report parsing ──────────────────────────────────────────────────────


def parse_reports(flags: list[str]) -> dict[str, int | None]:
    """Parse ``--report name=exit_code`` flags.

    Returns ``{name: exit_code}`` where ``exit_code`` is an int or
    ``None`` (meaning skipped / no result).
    """
    results: dict[str, int | None] = {}
    for f in flags:
        m = re.match(r"^(\w[\w-]*)=(-?\d+|skipped)$", f)
        if not m:
            print(f"Warning: malformed --report '{f}', skipping", file=sys.stderr)
            continue
        name = m.group(1)
        val = m.group(2)
        results[name] = None if val == "skipped" else int(val)
    return results


# ── Device resolution ───────────────────────────────────────────────────


def _resolve_device() -> dict:
    """Build the device object for the CI runner.

    Reads the device registry for ubuntu-latest if available; otherwise
    falls back to hard-coded defaults.
    """
    fallback = {
        "id": DEVICE_ID,
        "serial": None,
        "label": "ubuntu-latest",
        "manufacturer": "GitHub",
        "model": "Actions runner",
        "soc": "x86_64",
        "tier": "ci",
        "android_api": None,
        "execution": "github_hosted_runner",
    }
    devices_path = HERE / "testdata" / "devices.yaml"
    if not devices_path.exists():
        return fallback

    try:
        from summarise_test_report import _yaml_load  # type: ignore[import-untyped]
    except ImportError:
        return fallback

    try:
        source = devices_path.read_text()
        data = _yaml_load(source)
        devices = data.get("devices", {})
        dev = devices.get(DEVICE_ID, {})
    except Exception:
        return fallback

    return {
        "id": DEVICE_ID,
        "serial": None,
        "label": dev.get("label", fallback["label"]),
        "manufacturer": dev.get("manufacturer", fallback["manufacturer"]),
        "model": dev.get("model", fallback["model"]),
        "soc": dev.get("soc", fallback["soc"]),
        "tier": "ci",
        "android_api": None,
        "execution": "github_hosted_runner",
    }


# ── Case construction ──────────────────────────────────────────────────


def _build_case(name: str, passed: bool, failures: list[str] | None = None) -> dict:
    """Build a schema-compliant case dict for a CI check."""
    return {
        "name": name,
        "passed": passed,
        "expected_tool": None,
        "actual_tool": None,
        "expected_result_mode": "success",
        "actual_result_mode": "success" if passed else "failed",
        "chip_present": False,
        "skill_result_present": False,
        "message_saved": False,
        "retry_seen": False,
        "slot_fill_seen": False,
        "failure_category": None if passed else "harness_error",
        "failures": failures or [],
    }


# ── Output writers ──────────────────────────────────────────────────────


def _write_json(normalised: dict, path: Path) -> None:
    path.write_text(json.dumps(normalised, indent=2) + "\n")
    print(f"Evidence JSON: {path}")


def _write_csv(normalised: dict, path: Path) -> None:
    cases = normalised.get("cases", [])
    if not cases:
        path.write_text("")
        print(f"Case CSV:      {path} (empty)")
        return
    rows: list[dict] = []
    for c in cases:
        rows.append({
            "source": normalised["source"],
            "suite": normalised["suite"],
            "timestamp": normalised["timestamp"],
            "commit": normalised["commit"],
            "device_id": normalised["device"]["id"],
            "case": c["name"],
            "passed": c["passed"],
            "expected_result_mode": c["expected_result_mode"],
            "actual_result_mode": c["actual_result_mode"],
            "failure_category": c["failure_category"] or "",
            "failures": "; ".join(c["failures"]) if c["failures"] else "",
        })
    fieldnames = list(rows[0].keys())
    with open(path, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fieldnames)
        w.writeheader()
        w.writerows(rows)
    print(f"Case CSV:      {path}")


def _write_markdown(normalised: dict, path: Path) -> None:
    """Write a CI-run Markdown summary with metadata and case table."""
    s = normalised["summary"]
    cases = normalised.get("cases", [])
    dev = normalised["device"]
    lines = [
        "## CI Test Evidence Summary",
        "",
        "**No physical-device results were included in this report.**",
        "",
        "### Run metadata",
        "",
        "| Field | Value |",
        "|-------|-------|",
        f"| Source | {normalised['source']} |",
        f"| Commit | `{normalised['commit'][:10]}` |",
        f"| Branch | {normalised['branch']} |",
        f"| Suite | {normalised['suite']} |",
        f"| PR | {normalised['pr'] or '—'} |",
        f"| Timestamp | {normalised['timestamp']} |",
        f"| Run ID | `{normalised['run_id']}` |",
        "",
        "### Environment",
        "",
        f"| Field | Value |",
        f"|-------|-------|",
        f"| Device | {dev['id']} |",
        f"| Label | {dev['label']} |",
        f"| SoC | {dev['soc']} |",
        f"| Tier | {dev['tier']} |",
        "",
        "### Results",
        "",
        f"| Metric | Value |",
        f"|--------|-------|",
        f"| Total | {s['total']} |",
        f"| Passed | {s['passed']} |",
        f"| Failed | {s['failed']} |",
        f"| Pass rate | {s['pass_rate']:.1%} |",
        "",
    ]
    if cases:
        lines.append("| Case | Result | Failure Category |")
        lines.append("|------|--------|------------------|")
        for c in cases:
            icon = "✅" if c["passed"] else "❌"
            fc = c["failure_category"] or "—"
            lines.append(f"| {c['name']} | {icon} | {fc} |")
    path.write_text("\n".join(lines) + "\n")
    print(f"Summary MD:    {path}")


def _append_step_summary(normalised: dict) -> None:
    """Append a compact summary to $GITHUB_STEP_SUMMARY if set."""
    step_summary_str = os.environ.get("GITHUB_STEP_SUMMARY", "")
    if not step_summary_str:
        return
    step_summary = Path(step_summary_str)
    if not step_summary.parent.is_dir():
        return
    s = normalised["summary"]
    cases = normalised.get("cases", [])
    lines = [
        "---",
        "## CI Test Evidence — Summary",
        "",
        "**No physical-device results were included in this report.**",
        "",
        f"| Total | Passed | Failed | Pass rate |",
        f"|-------|--------|--------|-----------|",
        f"| {s['total']} | {s['passed']} | {s['failed']} | {s['pass_rate']:.1%} |",
        "",
    ]
    if cases:
        lines.append("| Case | Result |")
        lines.append("|------|--------|")
        for c in cases:
            icon = "✅" if c["passed"] else "❌"
            lines.append(f"| {c['name']} | {icon} |")
    with open(step_summary, "a") as f:
        f.write("\n".join(lines) + "\n")

# ── Validation helpers ─────────────────────────────────────────────────


def _validate_invariants(normalised: dict) -> list[str]:
    """Check semantic invariants (subset of summarise_test_report's)."""
    errors: list[str] = []
    cases = normalised["cases"]
    s = normalised["summary"]
    if s["total"] != len(cases):
        errors.append(f"summary.total ({s['total']}) != len(cases) ({len(cases)})")
    if s["total"] != s["passed"] + s["failed"]:
        errors.append(
            f"summary.total ({s['total']}) != passed ({s['passed']}) + failed ({s['failed']})"
        )
    for c in cases:
        if c["passed"] and c["failure_category"] is not None:
            errors.append(f"case '{c['name']}': passed but failure_category set")
        if not c["passed"] and c["failure_category"] is None:
            errors.append(f"case '{c['name']}': failed but failure_category is null")
    return errors


# ── Main ────────────────────────────────────────────────────────────────


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Generate CI test evidence for kernel-ai-assistant",
    )
    parser.add_argument("--out-dir", required=True, help="Output directory")
    parser.add_argument("--commit", required=True, help="Full 40-char commit SHA")
    parser.add_argument("--branch", required=True, help="Git branch")
    parser.add_argument("--pr", type=int, default=None, help="PR number")
    parser.add_argument(
        "--report", action="append", default=[],
        help="Check result: name=exit_code (repeatable)",
    )
    args = parser.parse_args()

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    # ── Timestamp & run ID ───────────────────────────────────────────────
    timestamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    run_id = f"ci-{timestamp.replace(':', '-')}-{DEVICE_ID}"

    # ── Device ───────────────────────────────────────────────────────────
    device_obj = _resolve_device()

    # ── Model ────────────────────────────────────────────────────────────
    model_obj: dict = {"name": None, "runtime": None, "backend": None}

    # ── Run internal checks ──────────────────────────────────────────────
    all_cases: list[dict] = []

    # Python compile
    py_ok, py_errors = _run_py_compile()
    all_cases.append(_build_case("python_compile_checks", py_ok, py_errors if py_errors else None))

    # Schema validation
    sv_ok, sv_errors = _run_schema_validation()
    all_cases.append(_build_case("fixture_schema_validation", sv_ok, sv_errors if sv_errors else None))

    # External reports
    external = parse_reports(args.report)
    for name, code in sorted(external.items()):
        if code is None:
            all_cases.append(_build_case(name, False, ["check not run (skipped)"]))
        else:
            all_cases.append(_build_case(name, code == 0, [f"exit code {code}"] if code != 0 else None))

    # ── Summary ──────────────────────────────────────────────────────────
    passed = sum(1 for c in all_cases if c["passed"])
    failed = sum(1 for c in all_cases if not c["passed"])
    total = len(all_cases)
    pass_rate = passed / total if total > 0 else 0.0
    summary = {
        "total": total,
        "passed": passed,
        "failed": failed,
        "pass_rate": round(pass_rate, 4),
    }

    # ── Assemble ─────────────────────────────────────────────────────────
    normalised = {
        "schema_version": SCHEMA_VERSION,
        "source": "ci",
        "suite": "ci",
        "timestamp": timestamp,
        "repo": REPO,
        "branch": args.branch,
        "commit": args.commit,
        "pr": args.pr,
        "release": None,
        "run_id": run_id,
        "device": device_obj,
        "model": model_obj,
        "summary": summary,
        "cases": all_cases,
    }

    # ── Validate ─────────────────────────────────────────────────────────
    iverrors = _validate_invariants(normalised)
    if iverrors:
        for e in iverrors:
            print(f"  [INVARIANT ERROR] {e}", file=sys.stderr)

    # ── Write outputs ────────────────────────────────────────────────────
    _write_json(normalised, out_dir / "ci_evidence.json")
    _write_csv(normalised, out_dir / "ci_cases.csv")
    _write_markdown(normalised, out_dir / "ci_summary.md")
    _append_step_summary(normalised)

    # ── Quick summary ────────────────────────────────────────────────────
    print(f"\n{'=' * 48}")
    print(f"  {total} checks  |  {passed} passed  |  {failed} failed  |  {pass_rate:.1%}")
    print(f"{'=' * 48}")


if __name__ == "__main__":
    main()
