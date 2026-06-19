#!/usr/bin/env python3
"""Publish launch validation evidence from ADB harness raw JSON output.

Chains: raw harness JSON → normalise → bundle → publish → dashboard trigger.

Usage:
  # Publish a specific harness report with full evidence bundle:
  python3 scripts/publish_launch_validation_evidence.py \\
    --input scripts/test-reports/2026-06-18T12-00-00Z_skills.json \\
    --device-id s21-exynos \\
    --model-name "Gemma 4 E-2B" \\
    --model-runtime LiteRT \\
    --model-backend GPU \\
    --serial R5CR605B71K \\
    --pr 1292

  # Publish the most recent skills/llm_tools report in test-reports/:
  python3 scripts/publish_launch_validation_evidence.py --latest --source on_device

  # Publish S23U targeted smoke evidence (when run):
  # NOTE: S23U uses the reference model (Gemma 4 E-4B), not E-2B.
  #       Broad/full suites are NOT permitted on the daily driver.
  python3 scripts/publish_launch_validation_evidence.py \\
    --input path/to/s23u-skills.json \\
    --device-id s23-ultra \\
    --model-name "Gemma 4 E-4B" \\
    --model-runtime LiteRT \\
    --model-backend GPU \\
    --serial <S23U_SERIAL> \\
    --pr <PR_NUM> \\
    --suite skills-targeted

  # After a harness run finishes, auto-publish:
  python3 scripts/adb_skill_test.py [..args..] && \\
  python3 scripts/publish_launch_validation_evidence.py --latest --source on_device --pr <PR_NUM>

Output bundle (in docs/test-triage/evidence/{date}/{device-id}/):
  - Raw harness JSON (copied from source)
  - Normalised evidence JSON (*_evidence.json)
  - Case CSV (*_cases.csv)
  - Markdown summary (*_summary.md, from normaliser)
  - Launch validation summary (launch-validation-summary.md, enriched Markdown)

The full bundle is published to the test-results branch via
scripts/publish_test_evidence.py --input-dir <out_dir>.
"""

from __future__ import annotations

import argparse
import json
import shutil
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO_DIR = HERE.parent  # repo root
REPORTS_DIR = HERE / "test-reports"

# Device registry key → serial mapping (injected from known runs)
# The primary source of truth is testdata/devices.yaml; serial is appended here
# as a convenience for scripts that have the serial.
KNOWN_SERIALS: dict[str, str] = {
    "s21-exynos": "R5CR605B71K",
    "s23-ultra": "",  # unknown, user must provide --serial
}

# Process exit codes to check for in the harness
HARNESS_DEGRADED_EXIT = 2  # some tests failed (harness common path)


def _check_git_available() -> None:
    """Ensure git is available."""
    try:
        subprocess.run(["git", "--version"], capture_output=True, check=True)
    except (subprocess.FileNotFoundError, subprocess.CalledProcessError):
        print("Error: git not available.", file=sys.stderr)
        sys.exit(1)


def _git(*args: str, check: bool = True) -> subprocess.CompletedProcess:
    """Run git in the repo root directory."""
    result = subprocess.run(
        ["git", *args],
        capture_output=True,
        text=True,
        cwd=str(REPO_DIR),
        check=check,
    )
    return result


def _get_current_sha() -> str:
    """Return the full 40-char SHA of HEAD."""
    result = _git("rev-parse", "HEAD")
    return result.stdout.strip()


def _get_current_branch() -> str:
    """Return the current branch name."""
    result = _git("rev-parse", "--abbrev-ref", "HEAD")
    return result.stdout.strip()


def _detect_latest_report(report_type: str | None = None) -> Path | None:
    """Find the most recent complete report in test-reports/.

    Args:
        report_type: 'skills', 'llm_tools', or None for either.
        When report_type is None, tries skills first then llm_tools.

    Returns:
        Path to the most recent JSON report, or None.
    """
    if not REPORTS_DIR.exists():
        return None

    candidates: list[Path] = []
    for f in sorted(REPORTS_DIR.iterdir(), reverse=True):
        if f.suffix != ".json":
            continue
        if "_partial" in f.stem:
            continue  # skip in-progress reports
        name = f.stem
        if report_type:
            if name.endswith(f"_{report_type}"):
                candidates.append(f)
        else:
            if name.endswith("_skills") or name.endswith("_llm_tools"):
                candidates.append(f)

    return candidates[0] if candidates else None


def _load_json(path: Path) -> dict:
    """Load and parse a JSON file, exiting on error."""
    try:
        with open(path) as f:
            return json.load(f)
    except FileNotFoundError:
        print(f"Error: file not found: {path}", file=sys.stderr)
        sys.exit(1)
    except json.JSONDecodeError as e:
        print(f"Error: invalid JSON in {path}: {e}", file=sys.stderr)
        sys.exit(1)


def _resolve_device_id(serial: str | None) -> str | None:
    """Look up device registry key from serial number."""
    if not serial:
        return None
    for device_id, known_serial in KNOWN_SERIALS.items():
        if serial == known_serial:
            return device_id
    return None  # unknown serial


def _build_launch_markdown(
    evidence_path: Path,
    raw_report_path: Path,
    normalised: dict,
    device_id: str,
    serial: str,
    build_sha: str,
    branch: str,
    excluded: int | None = None,
    not_reached: int | None = None,
    suite_context: str | None = None,
) -> str:
    """Build an evidence-summary Markdown document with full accounting.

    Enriches the standard normalised summary with:
      - full suite sizing (total, excluded, in-scope)
      - reached vs not-reached phases
      - pass/fail/xfail/skipped/excluded breakdown
      - model degradation notes
      - device/build metadata
    """
    s = normalised["summary"]
    cases = normalised.get("cases", [])
    dev = normalised["device"]
    md = normalised["model"]

    # Phase breakdown from normalised cases (now carrying phase from raw harness)
    phases: dict[str, dict[str, int]] = {}
    for c in cases:
        phase = c.get("phase") or "unknown"
        if phase not in phases:
            phases[phase] = {"pass": 0, "fail": 0, "xfail": 0, "total": 0}
        if c["passed"]:
            phases[phase]["pass"] += 1
        else:
            phases[phase]["fail"] += 1
        phases[phase]["total"] += 1

    # Phase order (to reproduce the evidence table):
    PHASE_ORDER = [
        "alarm_timer", "weather", "media", "lists", "smart_home",
        "memory", "navigation", "system", "misc", "slot_fill",
        "orchestrator_recovery", "false_positives",
    ]

    total = s["total"]
    passed = s["passed"]
    failed = s["failed"]

    # Handle not_reached / excluded — honest about unknowns
    not_reached_str: str
    if not_reached is not None:
        not_reached_str = str(not_reached)
    else:
        not_reached_str = "not provided by source evidence"

    excluded_str: str
    if excluded is not None:
        excluded_str = str(excluded)
    else:
        excluded_str = "not provided by source evidence"

    reached_str: str
    if not_reached is not None and not_reached > 0:
        reached_str = f"{total} (not-reached phases: {not_reached})"
    else:
        reached_str = f"{total}"

    lines = [
        f"# Launch Validation Evidence",
        f"",
        f"Device: **{dev['label']}** ({dev['model']})  ",
        f"Serial: `{serial or dev.get('serial', 'unknown')}`  ",
        f"Build: `{build_sha[:10]}` on `{branch}`  ",
        f"Timestamp: {normalised['timestamp']}",
        f"",
    ]

    if suite_context:
        lines.extend([
            f"> **Suite context:** {suite_context}",
            f"",
        ])

    lines.extend([
        f"## Suite Sizing",
        f"",
        f"| Level | Count |",
        f"|-------|-------|",
        f"| Full suite (all test phases) | See raw report |",
        f"| Excluded (destructive, device_state) | {excluded_str} |",
        f"| **In-scope (selected by harness)** | **{total}** |",
        f"| Reached (phases that completed) | {reached_str} |",
        f"| Not reached (timeout / not started) | {not_reached_str} |",
        f"",
        f"## Pass/Fail Summary",
        f"",
        f"| Metric | Value |",
        f"|--------|-------|",
        f"| Total | {total} |",
        f"| Passed | {passed} |",
        f"| Failed | {failed} |",
        f"| Pass rate | {s['pass_rate']:.1%} |",
        f"",
    ])

    # Phase breakdown table
    if phases:
        lines.extend([
            f"## Phase Breakdown",
            f"",
            f"| Phase | Total | Pass | Fail |",
            f"|-------|-------|------|------|",
        ])
        for phase_name in PHASE_ORDER:
            if phase_name in phases:
                p = phases[phase_name]
                lines.append(
                    f"| {phase_name} | {p['total']} | {p['pass']} | {p['fail']} |"
                )
        # Any phases not in the standard order
        for phase_name in sorted(phases):
            if phase_name not in PHASE_ORDER:
                p = phases[phase_name]
                lines.append(
                    f"| {phase_name} | {p['total']} | {p['pass']} | {p['fail']} |"
                )
        lines.append(f"| **Total** | **{total}** | **{passed}** | **{failed}** |")
        lines.append(f"")

    if md.get("name"):
        lines.extend([
            f"## Model",
            f"",
            f"| Field | Value |",
            f"|-------|-------|",
            f"| Name | {md['name']} |",
            f"| Runtime | {md['runtime']} |",
            f"| Backend | {md['backend']} |",
            f"",
        ])

    # Evidence references
    lines.extend([
        f"## Evidence Files",
        f"",
        f"| Type | Path |",
        f"|------|------|",
        f"| Raw harness JSON | `{raw_report_path}` (in `raw/` subdirectory) |",
        f"| Normalised evidence JSON | `{evidence_path}` |",
        f"| Case CSV | `{evidence_path.parent / ('*_cases.csv')}` |",
        f"| Normalised summary | `{evidence_path.parent / ('*_summary.md')}` |",
        f"",
    ])

    lines.append(f"*Report generated by `scripts/publish_launch_validation_evidence.py`*  ")
    lines.append(f"*Normalised using `scripts/normalise_skills_report.py`*  ")
    lines.append(f"")

    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Publish launch validation evidence from ADB harness output.",
    )
    # Input source: either explicit --input or --latest
    input_group = parser.add_mutually_exclusive_group(required=True)
    input_group.add_argument("--input", help="Path to raw harness JSON report")
    input_group.add_argument(
        "--latest", action="store_true",
        help="Use the most recent complete report in scripts/test-reports/",
    )

    parser.add_argument(
        "--source", default="on_device",
        choices=["on_device", "ci"],
        help="Evidence source type (default: on_device)",
    )
    parser.add_argument(
        "--device-id", default=None,
        help="Device registry key (see scripts/testdata/devices.yaml). "
             "Auto-detected from serial if omitted.",
    )
    parser.add_argument(
        "--model-name", default="Gemma 4 E-2B",
        help="Model name (default: Gemma 4 E-2B)",
    )
    parser.add_argument(
        "--model-runtime", default="LiteRT",
        help="Inference runtime (default: LiteRT)",
    )
    parser.add_argument(
        "--model-backend", default="GPU",
        help="Hardware backend (default: GPU)",
    )
    parser.add_argument(
        "--serial", default=None,
        help="ADB device serial (auto-detects device-id if known)",
    )
    parser.add_argument(
        "--pr", type=int, default=None,
        help="PR number for PR-scoped evidence",
    )
    parser.add_argument(
        "--suite", default=None,
        help="Suite name override (default: 'skills' or 'llm_tools' from report)",
    )
    parser.add_argument(
        "--commit", default=None,
        help="Commit SHA (default: auto-detect from HEAD)",
    )
    parser.add_argument(
        "--branch", default=None,
        help="Branch name (default: auto-detect from HEAD)",
    )
    parser.add_argument(
        "--release", default=None,
        help="Release tag (optional, for release-scoped evidence)",
    )
    parser.add_argument(
        "--out-dir", default=None,
        help="Output directory for intermediate normalised files "
             "(default: <repo_root>/docs/test-triage/evidence/<device>/<timestamp>)",
    )
    parser.add_argument(
        "--dry-run", action="store_true",
        help="Show what would be done without publishing",
    )
    parser.add_argument(
        "--excluded", type=int, default=None,
        help="Number of excluded cases (destructive, device_state, etc.)",
    )
    parser.add_argument(
        "--not-reached", type=int, default=None,
        help="Number of cases not reached due to timeout or resume boundary",
    )
    parser.add_argument(
        "--suite-context", default=None,
        help="Short phrase describing suite context, e.g. 'partial (resumed at navigation)', "
             "'targeted smoke (3 phases)', 'timeout-affected after ~46 cases'",
    )

    args = parser.parse_args()

    # ── Resolve input file ─────────────────────────────────────────────
    if args.latest:
        raw_path = _detect_latest_report()
        if raw_path is None:
            print("Error: no complete report found in scripts/test-reports/", file=sys.stderr)
            print("Run the harness first, or pass --input <path>.", file=sys.stderr)
            sys.exit(1)
        print(f"Using latest report: {raw_path}")
    else:
        raw_path = Path(args.input)
        if not raw_path.exists():
            print(f"Error: input file not found: {raw_path}", file=sys.stderr)
            sys.exit(1)

    # ── Determine report type ──────────────────────────────────────────
    raw = _load_json(raw_path)
    raw_suite = raw.get("suite", "")
    if raw_suite in ("skills",):
        normaliser = "normalise_skills_report.py"
        is_skills = True
    elif raw_suite in ("llm_tools",):
        normaliser = "summarise_test_report.py"
        is_skills = False
    else:
        print(f"Warning: unknown suite '{raw_suite}'. Treating as skills.", file=sys.stderr)
        normaliser = "normalise_skills_report.py"
        is_skills = True

    # ── Auto-detect commit and branch ──────────────────────────────────
    build_sha = args.commit or _get_current_sha()
    branch = args.branch or _get_current_branch()

    # ── Resolve device ─────────────────────────────────────────────────
    serial = args.serial
    device_id = args.device_id
    if device_id is None and serial:
        device_id = _resolve_device_id(serial)
        if device_id is None:
            print(
                f"Warning: serial '{serial}' not in known device list.",
                file=sys.stderr,
            )
            print("Use --device-id to specify a device registry key.", file=sys.stderr)
            sys.exit(1)

    if device_id is None:
        print("Error: could not determine device-id.", file=sys.stderr)
        print("Provide --serial (with a known serial) or --device-id.", file=sys.stderr)
        sys.exit(1)

    # ── Build suite name ───────────────────────────────────────────────
    suite = args.suite or raw_suite

    # ── Build output directory ─────────────────────────────────────────
    ts_normal = raw.get("timestamp", datetime.now(timezone.utc).strftime("%Y-%m-%dT%H-%M-%SZ"))
    ts_date = ts_normal[:10] if "T" in ts_normal else "unknown-date"
    if args.out_dir:
        out_dir = Path(args.out_dir)
    else:
        out_dir = HERE.parent / "docs" / "test-triage" / "evidence" / ts_date / device_id
    out_dir.mkdir(parents=True, exist_ok=True)

    # ── Print plan ─────────────────────────────────────────────────────
    print(f"\n{'=' * 56}")
    print(f"  Raw report:   {raw_path}")
    print(f"  Normaliser:   scripts/{normaliser}")
    print(f"  Device:       {device_id} (serial: {serial or 'unknown'})")
    print(f"  Build:        {build_sha[:10]} on {branch}")
    print(f"  Suite:        {suite}")
    if args.pr:
        print(f"  PR:           #{args.pr}")
    print(f"  Output dir:   {out_dir}")
    print(f"{'=' * 56}\n")

    if args.dry_run:
        print("[DRY RUN] Would run:")
        normaliser_path = HERE / normaliser
        print(f"  python3 {normaliser_path} \\")
        print(f"    --input {raw_path} \\")
        print(f"    --source {args.source} \\")
        print(f"    --device-id {device_id} \\")
        print(f"    --model-name '{args.model_name}' \\")
        print(f"    --model-runtime '{args.model_runtime}' \\")
        print(f"    --model-backend '{args.model_backend}' \\")
        print(f"    --commit {build_sha} \\")
        print(f"    --branch {branch} \\")
        print(f"    --out-dir {out_dir} \\")
        if args.pr:
            print(f"    --pr {args.pr} \\")
        if serial:
            print(f"    --serial {serial}")
        if args.release:
            print(f"    --release {args.release}")
        if suite != raw_suite:
            print(f"    --suite {suite}")
        print(f"\n  Then publish bundle via scripts/publish_test_evidence.py \\")
        print(f"    --input-dir {out_dir} \\")
        print(f"    --source {args.source} \\")
        print(f"    --commit {build_sha} \\")
        if args.pr:
            print(f"    --pr {args.pr}")
        if args.release:
            print(f"    --release {args.release}")
        print(f"\n  Then publish bundle via scripts/publish_test_evidence.py \\")
        print(f"    --input-dir {out_dir} \\")
        print(f"\n  Bundle will contain:")
        print(f"    - raw/ (subdirectory with raw harness JSON)")
        print(f"    - *_evidence.json (normalised)")
        print(f"    - *_cases.csv (spreadsheet)")
        print(f"    - *_summary.md (normalised summary)")
        print(f"    - launch-validation-summary.md (enriched Markdown)")
        print(f"  SKIP: --dry-run flag set")

    # ── Step 1: Normalise ──────────────────────────────────────────────
    normaliser_path = HERE / normaliser
    norm_cmd = [
        sys.executable, str(normaliser_path),
        "--input", str(raw_path),
        "--source", args.source,
        "--device-id", device_id,
        "--model-name", args.model_name,
        "--model-runtime", args.model_runtime,
        "--model-backend", args.model_backend,
        "--commit", build_sha,
        "--branch", branch,
        "--out-dir", str(out_dir),
    ]
    if args.pr:
        norm_cmd.extend(["--pr", str(args.pr)])
    if serial:
        norm_cmd.extend(["--serial", serial])
    if args.release:
        norm_cmd.extend(["--release", args.release])
    if suite != raw_suite:
        norm_cmd.extend(["--suite", suite])

    print(f"  ► Normalising report…")
    result = subprocess.run(norm_cmd, capture_output=False)
    if result.returncode != 0:
        print(f"  ✗ Normalisation failed (exit {result.returncode})", file=sys.stderr)
        sys.exit(result.returncode)
    print(f"  ✓ Normalisation complete\n")

    # ── Step 1.5: Copy raw JSON into evidence bundle (in raw/ subdir) ──
    raw_subdir = out_dir / "raw"
    raw_subdir.mkdir(parents=True, exist_ok=True)
    raw_in_bundle = raw_subdir / raw_path.name
    if not raw_in_bundle.exists():
        shutil.copy2(str(raw_path), str(raw_in_bundle))
        print(f"  ✓ Raw JSON copied to bundle: {raw_in_bundle}")
    else:
        print(f"  ✓ Raw JSON already in bundle: {raw_in_bundle}")
    # ── Find the normalised JSON output ────────────────────────────────
    norm_jsons = sorted(out_dir.glob("*_evidence.json")) + sorted(out_dir.glob("*_skills_evidence.json"))
    if not norm_jsons:
        print(f"Error: normalised JSON not found in {out_dir}", file=sys.stderr)
        sys.exit(1)
    evidence_json = norm_jsons[-1]

    # ── Step 2: Generate launch validation markdown ────────────────────
    normalised = _load_json(evidence_json)
    md = _build_launch_markdown(
        evidence_path=evidence_json,
        raw_report_path=raw_in_bundle,
        normalised=normalised,
        device_id=device_id,
        serial=serial or "",
        build_sha=build_sha,
        branch=branch,
        excluded=args.excluded,
        not_reached=args.not_reached,
        suite_context=args.suite_context,
    )
    md_path = out_dir / "launch-validation-summary.md"
    md_path.write_text(md)
    print(f"  ✓ Launch validation summary: {md_path}\n")
    _check_git_available()
    publish_script = HERE / "publish_test_evidence.py"

    publish_cmd = [
        sys.executable, str(publish_script),
        "--input-dir", str(out_dir),
        "--source", args.source,
        "--commit", build_sha,
    ]
    if args.pr:
        publish_cmd.extend(["--pr", str(args.pr)])
    if args.release:
        publish_cmd.extend(["--release", args.release])

    print(f"  ► Publishing evidence to test-results branch…")
    pub_result = subprocess.run(publish_cmd, capture_output=False)
    if pub_result.returncode != 0:
        print(f"  ⚠ Publish returned exit {pub_result.returncode}.", file=sys.stderr)
        print(f"  This may be due to test-results branch not existing yet,", file=sys.stderr)
        print(f"  or no changes to commit. The normalised evidence files", file=sys.stderr)
        print(f"  are still available locally.", file=sys.stderr)
    s = normalised.get("summary", {})
    bundle_files = sorted(out_dir.iterdir())
    print(f"{'=' * 56}")
    print(f"  Evidence publication complete:")
    print(f"  • {s.get('total', 0)} cases | {s.get('passed', 0)} passed | {s.get('failed', 0)} failed")
    print(f"  • Bundle location: {out_dir}/")
    for bf in bundle_files:
        if bf.is_dir():
            print(f"    - {bf.name}/")
        elif bf.is_file():
            print(f"    - {bf.name}")
    if pub_result.returncode == 0:
        print(f"  • Published to test-results branch ✓")
    print(f"{'=' * 56}")
    # ── Dashboard rebuild trigger note ─────────────────────────────────
    if args.pr:
        print(f"\n  To trigger dashboard rebuild:")
        print(f"  gh api repos/NickMonrad/kernel-ai-assistant/dispatches \\")
        print(f"    -f event_type=test-evidence-published \\")
        print(f"    -f client_payload[source]={args.source} \\")
        print(f"    -f client_payload[pr]={args.pr} \\")
        print(f"    -f client_payload[commit]={build_sha}")


if __name__ == "__main__":
    main()
