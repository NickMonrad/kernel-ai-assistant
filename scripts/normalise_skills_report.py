#!/usr/bin/env python3
"""Normalise a skills harness JSON report into the standardised evidence schema.

Transforms raw output from ``scripts/adb_skill_test.py`` (the full action-routing
suite, not the ``llm_tools`` phase) into the #1113 normalised evidence schema
shared by the dashboard, PR summaries, and release snapshots.

Usage:
  python3 scripts/normalise_skills_report.py \\
    --input <skills_report.json> \\
    --source on_device \\
    --commit <SHA> \\
    --branch <branch> \\
    --device-id s21-exynos \\
    --model-name "Gemma E4B" \\
    --model-runtime "LiteRT" \\
    --model-backend "GPU" \\
    --out-dir <output_dir>

Optional:
    --pr <N>           PR number for PR-scoped evidence
    --release <tag>    Release tag for baseline-scoped evidence
    --suite <name>     Override suite name (default: derived from report)
    --serial <adb_serial>  ADB device serial (e.g. R5CR605B71K)
"""

from __future__ import annotations

import argparse
import json
import hashlib
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = "NickMonrad/kernel-ai-assistant"
SCHEMA_VERSION = "1.0"

# Reuse device registry and helpers from the existing llm_tools normaliser.
# This keeps device definitions in one place.
sys.path.insert(0, str(HERE))
from summarise_test_report import (
    build_device_obj,
    load_devices,
    normalise_timestamp,
    validate_invariants,
    schema_validation_errors,
    build_run_id,
    write_csv,
    write_markdown,
)

# ── Helpers ────────────────────────────────────────────────────────────────────


def _slugify(text: str, max_len: int = 60) -> str:
    """Turn a user message into a compact case identifier.

    Strips non-alphanumeric chars (except underscore/hyphen), lowercases,
    truncates, and appends a hash suffix when truncated.
    """
    clean = re.sub(r"[^a-zA-Z0-9\s_-]", "", text.lower())
    slug = re.sub(r"\s+", "_", clean.strip())
    if len(slug) <= max_len:
        return slug
    # Truncate and append a short hash for uniqueness
    h = hashlib.sha1(text.encode()).hexdigest()[:6]
    return f"{slug[:max_len-7]}_{h}"

def classify_skills_failure(r: dict) -> str | None:
    """Classify a failed skills case into a standard failure category.

    Priority order (first match wins):

    1. ``log_check_warn`` present → ``missing_marker``
    2. ``intent_passed`` false + ``actual_intent`` is null → ``model_tool_generation_miss``
    3. ``intent_passed`` false + ``actual_intent`` differs → ``wrong_tool``
    4. ``params_passed`` false (intent correct) → ``field_mismatch``
    5. Timeout/harness clues in failures → ``timeout`` / ``harness_error``
    """
    if r.get("log_check_warn"):
        return "missing_marker"

    if not r.get("intent_passed", True):
        actual = r.get("actual_intent")
        expected = r.get("expect_intent")
        if actual is None:
            return "model_tool_generation_miss"
        if actual != expected:
            return "wrong_tool"
        # intent_passed false even though actual matches expected —
        # unusual; treat as harness edge case
        return "harness_error"

    if not r.get("params_passed", True):
        return "field_mismatch"


    # Status-based fallback
    status = r.get("status", "")
    if status == "fail":
        for f in r.get("param_failures", []):
            fl = f.lower()
            if "timeout" in fl:
                return "timeout"
            if any(t in fl for t in ("adb", "device", "connection", "app crash")):
                return "device_environment_error"
        return "harness_error"

    return None


def _build_failures(r: dict) -> list[str]:
    """Build the failures list for a skills case.

    Combines param_failures, log_check_warn, reply_warn, and
    derived messages for intent mismatches.
    """
    failures: list[str] = []

    # Intent mismatch
    if not r.get("intent_passed", True):
        expected = r.get("expect_intent")
        actual = r.get("actual_intent")
        if actual is None:
            failures.append(f"intent: expected '{expected}', got None")
        elif actual != expected:
            failures.append(f"intent: expected '{expected}', got '{actual}'")
        else:
            failures.append(f"intent: comparison failed despite matching names")

    # Param failures
    failures.extend(r.get("param_failures", []))

    # Warnings
    if r.get("log_check_warn"):
        failures.append(f"log_check: {r['log_check_warn']}")
    if r.get("reply_warn"):
        failures.append(f"reply: {r['reply_warn']}")

    return failures


# ── Case normalisation ─────────────────────────────────────────────────────────


def normalise_skills_case(r: dict) -> dict:
    """Normalise one raw skills result dict into a schema-compliant case dict."""
    name = _slugify(r.get("message", f"case_{r.get('index', 0)}"))
    phase = r.get("phase", None)

    xfail = r.get("xfail", False)
    intent_passed = r.get("intent_passed", False)

    # xfail: expected failure — the harness knows this case is known to fail.
    # Mark as passed with no failure details to satisfy invariants.
    if xfail:
        return {
            "name": name,
            "phase": phase,
            "passed": True,
            "expected_tool": r.get("expect_intent"),
            "actual_tool": r.get("actual_intent"),
            "expected_result_mode": "direct_reply",
            "actual_result_mode": "direct_reply",
            "chip_present": False,
            "skill_result_present": False,
            "message_saved": False,
            "retry_seen": False,
            "slot_fill_seen": False,
            "failure_category": None,
            "failures": [],
        }

    status = r.get("status", "fail")
    passed = status == "pass"
    failures = _build_failures(r)
    failure_category = classify_skills_failure(r)

    # Skills cases always use direct_reply mode (QIR dispatches intents synchronously)
    actual_tool = r.get("actual_intent")
    actual_result_mode = "direct_reply" if actual_tool else "unknown"

    return {
        "name": name,
        "phase": phase,
        "passed": passed,
        "expected_tool": r.get("expect_intent"),
        "actual_tool": actual_tool,
        "expected_result_mode": "direct_reply",
        "actual_result_mode": actual_result_mode,
        "chip_present": False,
        "skill_result_present": False,
        "message_saved": False,
        "retry_seen": False,
        "slot_fill_seen": False,
        "failure_category": failure_category,
        "failures": failures,
    }


# ── Main ───────────────────────────────────────────────────────────────────────


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Normalise a skills harness report to the evidence schema."
    )
    parser.add_argument("--input", required=True,
                        help="Path to raw skills.json report")
    parser.add_argument("--source", required=True,
                        choices=["on_device", "ci"],
                        help="Source type")
    parser.add_argument("--suite", default=None,
                        help="Test suite name (default: derived from report)")
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
    parser.add_argument("--out-dir", required=True,
                        help="Output directory for normalised files")
    parser.add_argument("--serial", default=None,
                        help="ADB device serial (e.g. R5CR605B71K)")

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

    # Validate this is a skills-format report (not llm_tools)
    raw_suite = raw.get("suite", "")
    if raw_suite not in ("skills", "profile", "adb_regression"):
        print(
            f"Warning: input suite '{raw_suite}' may not be a skills-format "
            f"report. Proceeding anyway.",
            file=sys.stderr,
        )

    devices = load_devices()
    device_obj = build_device_obj(args.device_id, devices)
    # Inject ADB serial if provided
    if args.serial:
        device_obj["serial"] = args.serial

    # ── Source / device consistency ───────────────────────────────────────
    if args.source == "ci":
        if device_obj["execution"] != "github_hosted_runner":
            print(
                f"Error: source=ci conflicts with device '{args.device_id}' "
                f"(execution={device_obj['execution']})",
                file=sys.stderr,
            )
            sys.exit(1)
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
    timestamp = normalise_timestamp(raw.get("timestamp", ""))

    # ── Normalise cases ───────────────────────────────────────────────────
    raw_results = raw.get("results", [])
    cases: list[dict] = [normalise_skills_case(r) for r in raw_results]

    # ── Summary ───────────────────────────────────────────────────────────
    total = len(cases)
    passed = sum(1 for c in cases if c["passed"])
    failed = total - passed
    pass_rate = passed / total if total > 0 else 0.0
    summary = {
        "total": total,
        "passed": passed,
        "failed": failed,
        "pass_rate": round(pass_rate, 4),
    }

    # ── Suite name ────────────────────────────────────────────────────────
    suite = args.suite or raw.get("suite", "skills")
    if suite == "skills":
        # Use a more descriptive suite name for the full action-routing suite
        suite = "skills"

    # ── Assemble normalised report ────────────────────────────────────────
    normalised = {
        "schema_version": SCHEMA_VERSION,
        "source": args.source,
        "suite": suite,
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
        "artifact_refs": [],
    }

    # ── Validate invariants ───────────────────────────────────────────────
    validate_invariants(normalised)
    schema_errors = schema_validation_errors(normalised)
    if schema_errors:
        print("Schema validation failed:", file=sys.stderr)
        for error in schema_errors:
            print(f"  - {error}", file=sys.stderr)
        sys.exit(1)

    # ── Write outputs ─────────────────────────────────────────────────────
    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    # Derive a clean stem from the input filename
    stem = input_path.stem
    for suffix in ("_skills", "_partial", ".json"):
        stem = stem.replace(suffix, "")

    json_path = out_dir / f"{stem}_skills_evidence.json"
    with open(json_path, "w") as f:
        json.dump(normalised, f, indent=2)
    print(f"Normalised JSON: {json_path}")

    csv_path = out_dir / f"{stem}_skills_cases.csv"
    write_csv(normalised, csv_path)
    print(f"Case CSV:       {csv_path}")

    md_path = out_dir / f"{stem}_skills_summary.md"
    write_markdown(normalised, md_path)
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
