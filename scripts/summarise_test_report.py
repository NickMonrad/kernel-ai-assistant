#!/usr/bin/env python3
"""Normalise an llm_tools harness JSON report into the standardised evidence schema.

Reads a raw report produced by save_llm_tools_report(), resolves device metadata
from the device registry, and produces three output files:
  - normalised JSON matching scripts/testdata/test_evidence.schema.json
  - CSV with one row per case
  - Markdown single-run summary

Usage:
  summarise_test_report.py \\
      --input path/to/raw_llm_tools.json \\
      --source on_device|ci \\
      --device-id s23-ultra \\
      --model-name "Gemma E4B" \\
      --model-runtime LiteRT \\
      --model-backend GPU \\
      --commit <40-char-sha> \\
      --branch feature/xxx \\
      --pr 1234 \\
      --out-dir path/to/output \\
      [--default-expected-mode success|direct_reply]
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import sys
from pathlib import Path

SCHEMA_VERSION = "1.0"
REPO = "NickMonrad/kernel-ai-assistant"
HERE = Path(__file__).resolve().parent
DEVICES_PATH = HERE / "testdata" / "devices.yaml"


# ── Device registry ──────────────────────────────────────────────────────────

def load_devices() -> dict:
    """Load device registry from YAML.  Returns {device_id: {…}}."""
    import yaml
    with open(DEVICES_PATH) as f:
        data = yaml.safe_load(f)
    return data.get("devices", {})


def resolve_device(device_id: str, devices: dict) -> dict:
    """Look up a device by ID; exit with clear error on unknown ID."""
    if device_id not in devices:
        known = sorted(devices.keys())
        print(
            f"Error: unknown --device-id '{device_id}'.\n"
            f"Valid IDs: {', '.join(known)}",
            file=sys.stderr,
        )
        sys.exit(1)
    return devices[device_id]


def build_device_obj(device_id: str, devices: dict) -> dict:
    """Build the device object for the normalised report."""
    dev = resolve_device(device_id, devices)
    return {
        "id": device_id,
        "serial": dev.get("serial"),  # optional, not in YAML yet
        "label": dev["label"],
        "manufacturer": dev["manufacturer"],
        "model": dev["model"],
        "soc": dev["soc"],
        "tier": dev["tier"],
        "android_api": dev.get("android_api"),
        "execution": dev["execution"],
    }


# ── Field derivation ─────────────────────────────────────────────────────────

_RESULT_MODE_RE = re.compile(r"\bmode=(\S+)")


def parse_actual_result_mode(skill_result_marker: str | None) -> str:
    """Derive actual_result_mode from skill_result_marker.

    Returns ``"success"``, ``"direct_reply"``, or ``"unknown"``.
    """
    if not skill_result_marker:
        return "unknown"
    m = _RESULT_MODE_RE.search(skill_result_marker)
    if m and m.group(1) in ("success", "direct_reply"):
        return m.group(1)
    return "unknown"


# ── Failure classification ───────────────────────────────────────────────────

def classify_failure(
    r: dict,
    expected_mode: str,
    actual_mode: str,
) -> str:
    """Classify a failed raw result into a standard failure category.

    Priority order (first match wins):

    1. ``retry_seen`` → ``retry_seen``
    2. ``slot_fill_seen`` → ``slot_fill_seen``
    3. No native **or** legacy tool marker → ``model_tool_generation_miss``
    4. ``actual_tool != expected_tool`` → ``wrong_tool``
    5. Tool was called but ``chip_text`` is null → ``missing_marker``
    6. Tool was called but ``skill_result_marker`` is null → ``missing_marker``
    7. ``actual_result_mode != expected_result_mode`` → ``wrong_result_mode``
    8. Timeout in failures text → ``timeout``
    9. Device/ADB in failures text → ``device_environment_error``
    10. Fallback → ``harness_error``
    """
    if r.get("retry_seen"):
        return "retry_seen"

    if r.get("slot_fill_seen"):
        return "slot_fill_seen"

    has_tool = bool(r.get("native_tool_marker") or r.get("legacy_tool_marker"))
    if not has_tool:
        return "model_tool_generation_miss"

    actual_tool = r.get("actual_top_level_tool")
    expected_tool = r.get("expected_top_level_tool")
    if actual_tool != expected_tool:
        return "wrong_tool"

    # Tool called but expected markers absent
    if r.get("chip_text") is None:
        return "missing_marker"
    if r.get("skill_result_marker") is None:
        return "missing_marker"

    if actual_mode != expected_mode:
        return "wrong_result_mode"

    for f in r.get("failures", []):
        fl = f.lower()
        if "timeout" in fl:
            return "timeout"
        if any(t in fl for t in ("adb", "device", "connection", "app crash")):
            return "device_environment_error"

    return "harness_error"


def normalise_case(r: dict, expected_mode: str) -> dict:
    """Normalise one raw result dict into a schema-compliant case dict."""
    passed = r["passed"]
    actual_mode = parse_actual_result_mode(r.get("skill_result_marker"))

    failure_category: str | None = None
    failures: list[str] = []
    if not passed:
        failure_category = classify_failure(r, expected_mode, actual_mode)
        failures = r.get("failures") or []

    return {
        "name": r["name"],
        "passed": passed,
        "expected_tool": r.get("expected_top_level_tool"),
        "actual_tool": r.get("actual_top_level_tool"),
        "expected_result_mode": expected_mode,
        "actual_result_mode": actual_mode,
        "chip_present": r.get("chip_text") is not None,
        "skill_result_present": r.get("skill_result_marker") is not None,
        "message_saved": r.get("message_saved_marker") is not None,
        "retry_seen": bool(r.get("retry_seen")),
        "slot_fill_seen": bool(r.get("slot_fill_seen")),
        "failure_category": failure_category,
        "failures": failures,
    }


# ── Invariant validation ─────────────────────────────────────────────────────

def validate_invariants(normalised: dict) -> None:
    """Check semantic invariants from schema §7.  Exits on violation."""
    errors: list[str] = []
    cases = normalised["cases"]
    s = normalised["summary"]

    total_cases = len(cases)
    if s["total"] != total_cases:
        errors.append(
            f"summary.total ({s['total']}) != len(cases) ({total_cases})"
        )
    if s["total"] != s["passed"] + s["failed"]:
        errors.append(
            f"summary.total ({s['total']}) != passed ({s['passed']}) "
            f"+ failed ({s['failed']})"
        )
    expected_rate = s["passed"] / s["total"] if s["total"] > 0 else 0.0
    if abs(s["pass_rate"] - expected_rate) > 0.001:
        errors.append(
            f"summary.pass_rate ({s['pass_rate']}) != expected ({expected_rate})"
        )

    for c in cases:
        if c["passed"]:
            if c["failure_category"] is not None:
                errors.append(
                    f"case '{c['name']}': passed but "
                    f"failure_category={c['failure_category']}"
                )
            if c["failures"]:
                errors.append(
                    f"case '{c['name']}': passed but non-empty failures"
                )
        else:
            if c["failure_category"] is None:
                errors.append(
                    f"case '{c['name']}': failed but failure_category is null"
                )
            if not c["failures"]:
                errors.append(
                    f"case '{c['name']}': failed but empty failures"
                )

        if c["actual_tool"] is None:
            if c["chip_present"]:
                errors.append(
                    f"case '{c['name']}': actual_tool=null but chip_present=true"
                )
            if c["skill_result_present"]:
                errors.append(
                    f"case '{c['name']}': actual_tool=null "
                    "but skill_result_present=true"
                )

    if errors:
        for e in errors:
            print(f"  [INVARIANT ERROR] {e}", file=sys.stderr)
        sys.exit(1)


# ── Output writers ───────────────────────────────────────────────────────────

def build_run_id(source: str, timestamp: str, device_id: str) -> str:
    """<source>-<ISO-timestamp>-<device-id>, with colons replaced."""
    ts = timestamp.replace(":", "-")
    return f"{source}-{ts}-{device_id}"


def write_csv(cases: list[dict], path: Path) -> None:
    """Write one CSV row per case."""
    if not cases:
        path.write_text("")
        return
    fieldnames = list(cases[0].keys())
    with open(path, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fieldnames)
        w.writeheader()
        w.writerows(cases)


def write_markdown(summary: dict, cases: list[dict], path: Path) -> None:
    """Write a compact single-run Markdown summary."""
    lines = [
        "## Test Run Summary",
        "",
        f"| Metric | Value |",
        f"|--------|-------|",
        f"| Total | {summary['total']} |",
        f"| Passed | {summary['passed']} |",
        f"| Failed | {summary['failed']} |",
        f"| Pass rate | {summary['pass_rate']:.1%} |",
        "",
    ]
    if cases:
        lines.append("| Case | Result | Expected Tool | Actual Tool | Failure Category |")
        lines.append("|------|--------|---------------|-------------|------------------|")
        for c in cases:
            icon = "✅" if c["passed"] else "❌"
            et = c["expected_tool"] or "—"
            at = c["actual_tool"] or "—"
            fc = c["failure_category"] or "—"
            lines.append(f"| {c['name']} | {icon} | {et} | {at} | {fc} |")
    path.write_text("\n".join(lines) + "\n")


# ── Main ─────────────────────────────────────────────────────────────────────

def main() -> None:
    parser = argparse.ArgumentParser(
        description="Normalise an llm_tools harness report to the evidence schema."
    )
    parser.add_argument("--input", required=True,
                        help="Path to raw *llm_tools.json report")
    parser.add_argument("--source", required=True,
                        choices=["on_device", "ci"],
                        help="Source type")
    parser.add_argument("--suite", default="llm_tools",
                        help="Test suite name (default: llm_tools)")
    parser.add_argument("--pr", type=int, default=None,
                        help="PR number (omit for non-PR runs)")
    parser.add_argument("--commit", required=True,
                        help="Full 40-character commit SHA")
    parser.add_argument("--branch", required=True,
                        help="Git branch")
    parser.add_argument("--release", default=None,
                        help="Release tag (optional)")
    parser.add_argument("--device-id", required=True,
                        help="Device registry key (see scripts/testdata/devices.yaml)")
    parser.add_argument("--model-name", required=True,
                        help="Model name, e.g. 'Gemma E4B'")
    parser.add_argument("--model-runtime", required=True,
                        help="Inference runtime, e.g. 'LiteRT'")
    parser.add_argument("--model-backend", required=True,
                        help="Hardware backend, e.g. 'GPU'")
    parser.add_argument("--default-expected-mode", default=None,
                        choices=["success", "direct_reply"],
                        help="Fallback expected_result_mode when not in raw report. "
                             "Required if raw results lack per-case expected_result_mode.")
    parser.add_argument("--out-dir", required=True,
                        help="Output directory for normalised files")

    args = parser.parse_args()

    # ── Load inputs ──────────────────────────────────────────────────────
    input_path = Path(args.input)
    try:
        with open(input_path) as f:
            raw = json.load(f)
    except FileNotFoundError:
        print(f"Error: input file not found: {input_path}", file=sys.stderr)
        sys.exit(1)
    except json.JSONDecodeError as e:
        print(f"Error: invalid JSON in {input_path}: {e}", file=sys.stderr)
        sys.exit(1)

    devices = load_devices()
    device_obj = build_device_obj(args.device_id, devices)

    # ── Source / device consistency ───────────────────────────────────────
    if args.source == "ci":
        if device_obj["execution"] != "github_hosted_runner":
            print(
                f"Error: source=ci conflicts with device '{args.device_id}' "
                f"(execution={device_obj['execution']})",
                file=sys.stderr,
            )
            sys.exit(1)
        # CI → all model fields null
        model_obj: dict = {
            "name": None,
            "runtime": None,
            "backend": None,
        }
    else:
        if device_obj["execution"] != "physical":
            print(
                f"Error: source=on_device conflicts with device '{args.device_id}' "
                f"(execution={device_obj['execution']})",
                file=sys.stderr,
            )
            sys.exit(1)
        model_obj = {
            "name": args.model_name,
            "runtime": args.model_runtime,
            "backend": args.model_backend,
        }

    # ── Timestamp ─────────────────────────────────────────────────────────
    timestamp = raw.get("timestamp", "")
    if timestamp and not timestamp.endswith("Z"):
        timestamp += "Z"

    # ── Normalise cases ───────────────────────────────────────────────────
    raw_results = raw.get("results", [])
    cases: list[dict] = []

    for r in raw_results:
        # future: raw report may carry per-case expected_result_mode
        expected_mode: str | None = r.get("expected_result_mode")
        if expected_mode is None:
            expected_mode = args.default_expected_mode
        if expected_mode is None:
            print(
                f"Error: cannot derive expected_result_mode for case "
                f"'{r.get('name', '<unknown>')}'. "
                f"Add per-case expected_result_mode to raw report or pass "
                f"--default-expected-mode.",
                file=sys.stderr,
            )
            sys.exit(1)

        nc = normalise_case(r, expected_mode)
        cases.append(nc)

    # ── Summary ───────────────────────────────────────────────────────────
    passed = sum(1 for c in cases if c["passed"])
    failed = sum(1 for c in cases if not c["passed"])
    total = len(cases)
    pass_rate = passed / total if total > 0 else 0.0
    summary = {
        "total": total,
        "passed": passed,
        "failed": failed,
        "pass_rate": round(pass_rate, 4),
    }

    # ── Assemble normalised report ────────────────────────────────────────
    normalised = {
        "schema_version": SCHEMA_VERSION,
        "source": args.source,
        "suite": args.suite,
        "timestamp": timestamp,
        "repo": REPO,
        "branch": args.branch,
        "commit": args.commit,
        "pr": args.pr,
        "release": args.release if args.release else None,
        "run_id": build_run_id(args.source, timestamp, args.device_id),
        "device": device_obj,
        "model": model_obj,
        "summary": summary,
        "cases": cases,
    }

    # ── Validate invariants ───────────────────────────────────────────────
    validate_invariants(normalised)

    # ── Write outputs ─────────────────────────────────────────────────────
    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    # Derive a clean stem from the input filename
    stem = input_path.stem
    for suffix in ("_llm_tools", "_partial", ".json"):
        stem = stem.replace(suffix, "")

    json_path = out_dir / f"{stem}_evidence.json"
    with open(json_path, "w") as f:
        json.dump(normalised, f, indent=2)
    print(f"Normalised JSON: {json_path}")

    csv_path = out_dir / f"{stem}_cases.csv"
    write_csv(cases, csv_path)
    print(f"Case CSV:       {csv_path}")

    md_path = out_dir / f"{stem}_summary.md"
    write_markdown(summary, cases, md_path)
    print(f"Summary MD:     {md_path}")

    # ── Print quick summary to stdout ─────────────────────────────────────
    print(
        f"\n{'=' * 48}\n"
        f"  {summary['total']} cases  |  "
        f"{summary['passed']} passed  |  "
        f"{summary['failed']} failed  |  "
        f"{summary['pass_rate']:.1%}\n"
        f"{'=' * 48}"
    )


if __name__ == "__main__":
    main()
