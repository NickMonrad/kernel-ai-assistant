"""
ADB Skill Harness — reporting and analysis.

JSON/HTML report generation, result analysis, OOM detection,
and PR comment markdown builders.
"""

from __future__ import annotations

import json
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

from adb_harness.config import REPORTS_DIR
from adb_harness.models import TestResult, LLMToolsResult, derive_failure_bucket, derive_status

def save_llm_tools_report(
    results: list[LLMToolsResult],
    elapsed: float = 0.0,
    partial: bool = False,
    run_ts: str | None = None,
) -> Path:
    """Serialise llm_tools results to a JSON report.
    This is separate from save_report() because LLMToolsResult has a
    different schema (no intent_passed/params_passed/xfail fields).
    """
    REPORTS_DIR.mkdir(parents=True, exist_ok=True)
    ts = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H-%M-%SZ")
    if partial and run_ts:
        report_path = REPORTS_DIR / f"{run_ts}_llm_tools_partial.json"
        status = "in_progress"
    else:
        report_path = REPORTS_DIR / f"{ts}_llm_tools.json"
        status = "complete"
        if run_ts:
            partial_file = REPORTS_DIR / f"{run_ts}_llm_tools_partial.json"
            partial_file.unlink(missing_ok=True)
    total = len(results)
    passed = sum(1 for r in results if r.passed)
    failed = total - passed
    report = {
        "suite": "llm_tools",
        "status": status,
        "timestamp": ts,
        "elapsed_seconds": round(elapsed, 1),
        "summary": {
            "total": total,
            "passed": passed,
            "failed": failed,
        },
        "results": [
            {
                "index": r.index,
                "name": r.name,
                "message": r.message,
                "expected_top_level_tool": r.expected_top_level_tool,
                "actual_top_level_tool": r.actual_top_level_tool,
                "actual_nested_intent": r.actual_nested_intent,
                "route_marker": r.route_marker,
                "native_tool_marker": r.native_tool_marker,
                "legacy_tool_marker": r.legacy_tool_marker,
                "skill_result_marker": r.skill_result_marker,
                "message_saved_marker": r.message_saved_marker,
                "retry_seen": r.retry_seen,
                "slot_fill_seen": r.slot_fill_seen,
                "chip_text": r.chip_text,
                "reply_text": r.reply_text,
                "passed": r.passed,
                "failures": r.failures,
                "no_tool_call_requested": r.no_tool_call_requested,
                "log_contains_required": r.log_contains_required,
                "log_contains_match": r.log_contains_match,
                "expected_reply_terms": r.expected_reply_terms,
                "reply_terms_match": r.reply_terms_match,
            }
            for r in results
        ],
    }
    report_path.write_text(json.dumps(report, indent=2))
    return report_path

def save_report(
    results: list[TestResult],
    suite: str = "skills",
    elapsed: float = 0.0,
    partial: bool = False,
    run_ts: str | None = None,
) -> Path:
    """Serialise results to a JSON file in scripts/test-reports/ and return the path.

    When partial=True, writes/overwrites a fixed-name in-progress snapshot so that
    results are never lost if the run is aborted mid-way.  When partial=False (the
    final save), writes the completed timestamped report and deletes any partial file
    that was written during the same run.
    """
    REPORTS_DIR.mkdir(parents=True, exist_ok=True)
    ts = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H-%M-%SZ")

    if partial and run_ts:
        report_path = REPORTS_DIR / f"{run_ts}_{suite}_partial.json"
        status = "in_progress"
    else:
        report_path = REPORTS_DIR / f"{ts}_{suite}.json"
        status = "complete"
        # Remove the in-progress snapshot now that the full report is being written
        if run_ts:
            partial_file = REPORTS_DIR / f"{run_ts}_{suite}_partial.json"
            partial_file.unlink(missing_ok=True)

    prepared: list[TestResult] = []
    for r in results:
        if not r.status:
            r.status = derive_status(r)
        if r.failure_bucket is None:
            r.failure_bucket = derive_failure_bucket(r)
        prepared.append(r)

    total = len(prepared)
    passed = sum(1 for r in prepared if r.status == "pass")
    xfails = sum(1 for r in prepared if r.status == "xfail")
    xpasses = sum(1 for r in prepared if r.status == "xpass")
    failures = sum(1 for r in prepared if r.status == "fail")

    report = {
        "suite": suite,
        "status": status,
        "timestamp": ts,
        "elapsed_seconds": round(elapsed, 1),
        "summary": {
            "total": total,
            "passed": passed,
            "xfail": xfails,
            "xpass": xpasses,
            "failed": failures,
        },
        "results": [
            {
                "index": r.index,
                "case_id": r.case_id,
                "message": r.message,
                "category": r.category,
                "tags": r.tags,
                "fixture": r.fixture,
                "expect_intent": r.expect_intent,
                "actual_intent": r.actual_intent,
                "expect_params": r.expect_params,
                "actual_params": r.actual_params,
                "intent_passed": r.intent_passed,
                "params_passed": r.params_passed,
                "param_failures": r.param_failures,
                "xfail": r.xfail,
                "xfail_reason": r.xfail_reason,
                "reply_warn": r.reply_warn,
                "log_check_warn": r.log_check_warn,
                "first_turn_warn": r.first_turn_warn,
                "phase": r.phase,
                "status": r.status,
                "failure_bucket": r.failure_bucket,
            }
            for r in prepared
        ],
    }
    report_path.write_text(json.dumps(report, indent=2))

    # Auto-generate HTML report if generator script is present
    gen_script = Path(__file__).parent / "generate_report.py"
    if gen_script.exists():
        subprocess.run([sys.executable, str(gen_script), str(report_path)], check=False)

    return report_path



def analyse_results(results: list[TestResult]) -> None:
    """Print a pattern analysis section after the summary table."""
    prepared: list[TestResult] = []
    for r in results:
        if not r.status:
            r.status = derive_status(r)
        if r.failure_bucket is None:
            r.failure_bucket = derive_failure_bucket(r)
        prepared.append(r)

    failures = [r for r in prepared if r.status == "fail"]
    xpasses = [r for r in prepared if r.status == "xpass"]
    if not failures and not xpasses:
        print("\n  ✅ No failures to analyse.")
        return

    print("\n  FAILURE ANALYSIS")
    print("  " + "-" * 68)

    if failures:
        by_bucket: dict[str, list[TestResult]] = {}
        for r in failures:
            by_bucket.setdefault(r.failure_bucket or "unclassified", []).append(r)
        print(f"\n  Failure buckets ({len(failures)}):")
        for bucket, group in sorted(by_bucket.items(), key=lambda x: (-len(x[1]), x[0])):
            print(f"    {bucket}: {len(group)}")

        intent_failures = [r for r in failures if not r.intent_passed]
        param_failures = [r for r in failures if r.intent_passed and not r.params_passed]

        if intent_failures:
            by_actual: dict[str, list[TestResult]] = {}
            for r in intent_failures:
                key = r.actual_intent or "NO_MATCH"
                by_actual.setdefault(key, []).append(r)
            print(f"\n  Intent routing failures ({len(intent_failures)}):")
            for actual, group in sorted(by_actual.items(), key=lambda x: -len(x[1])):
                expected_intents = sorted({r.expect_intent for r in group})
                print(f"    → routed as {actual!r} instead of {expected_intents}:")
                for r in group[:5]:
                    bucket = f" [{r.failure_bucket}]" if r.failure_bucket else ""
                    print(f"       [{r.index:3d}] \"{r.message}\"{bucket}")

        if param_failures:
            print(f"\n  Param extraction failures ({len(param_failures)}):")
            for r in param_failures:
                bucket = f" [{r.failure_bucket}]" if r.failure_bucket else ""
                print(f"    [{r.index:3d}] \"{r.message}\"  (intent={r.expect_intent}){bucket}")
                for pf in r.param_failures:
                    print(f"           ✗ {pf}")

        by_intent: dict[str, list[TestResult]] = {}
        for r in prepared:
            by_intent.setdefault(r.expect_intent, []).append(r)
        hot = [
            (intent, grp)
            for intent, grp in by_intent.items()
            if len(grp) >= 2 and sum(1 for r in grp if r.status == "fail") / len(grp) >= 0.5
        ]
        if hot:
            print(f"\n  ⚠️  High-failure-rate intents (≥50% of cases failing):")
            for intent, grp in sorted(hot, key=lambda x: -len(x[1])):
                n_fail = sum(1 for r in grp if r.status == "fail")
                print(f"    {intent}: {n_fail}/{len(grp)} failing")

    if xpasses:
        print(f"\n  Unexpected passes ({len(xpasses)}):")
        for r in xpasses:
            reason = f" — {r.xfail_reason}" if r.xfail_reason else ""
            print(f"    [{r.index:3d}] \"{r.message}\"{reason}")

    print()


# Minimum consecutive same-actual-intent results required to trigger the OOM warning.

_OOM_RUN_THRESHOLD = 5


def check_oom_sanity(results: list[TestResult]) -> None:
    """Warn if a long consecutive run of tests all return the same actual intent while their
    *expected* intents differ — a strong signal that the model has hung or OOM'd and is
    returning a stuck response.

    Deliberately does NOT warn when the expected intents within the run are all the same
    (e.g. the weather phase where every test correctly maps to get_weather), because that
    is valid behaviour, not a stuck model.  Closes #563.
    """
    i = 0
    warned = False
    while i < len(results):
        run_actual = results[i].actual_intent
        j = i
        while j < len(results) and results[j].actual_intent == run_actual:
            j += 1
        run = results[i:j]
        if len(run) >= _OOM_RUN_THRESHOLD:
            expected_in_run = {r.expect_intent for r in run}
            # Only suspicious when expected intents VARY but actual is stuck on one value.
            if len(expected_in_run) > 1:
                if not warned:
                    print("\n  OOM / MODEL-HANG SANITY CHECK")
                    print("  " + "-" * 68)
                    warned = True
                label = run_actual if run_actual else "NO_MATCH"
                print(
                    f"\n  ⚠️  Possible OOM/hang: tests {run[0].index}–{run[-1].index} "
                    f"({len(run)} consecutive) all returned {label!r} "
                    f"but expected {len(expected_in_run)} distinct intents "
                    f"({', '.join(sorted(expected_in_run))})."
                )
                print("     Consider restarting the app and re-running this range.")
        i = j
    if warned:
        print()



def _fmt_elapsed(seconds: float) -> str:
    m, s = int(seconds) // 60, int(seconds) % 60
    return f"{m}m {s:02d}s" if m else f"{s}s"



def build_comment_markdown(
    results: list[TestResult],
    elapsed: float,
    report_path: Path,
) -> str:
    """Build a GitHub-flavoured markdown PR comment summarising the run."""
    prepared: list[TestResult] = []
    for r in results:
        if not r.status:
            r.status = derive_status(r)
        if r.failure_bucket is None:
            r.failure_bucket = derive_failure_bucket(r)
        prepared.append(r)

    total = len(prepared)
    passed = sum(1 for r in prepared if r.status == "pass")
    xfails = sum(1 for r in prepared if r.status == "xfail")
    xpasses = sum(1 for r in prepared if r.status == "xpass")
    failed = sum(1 for r in prepared if r.status == "fail")
    pass_rate = passed / max(total - xfails, 1) * 100

    lines: list[str] = [
        "## 🧪 Jandal Skill Regression Results",
        "",
        "| | Count |",
        "|---|---|",
        f"| ✅ Passed | {passed} |",
        f"| ❌ Failed | {failed} |",
        f"| ⚠️ Expected failures | {xfails} |",
        f"| 🟡 Unexpected passes | {xpasses} |",
        f"| **Total** | **{total}** |",
        "",
        f"**Pass rate: {pass_rate:.1f}%** • Run time: {_fmt_elapsed(elapsed)}",
    ]

    failures = [r for r in prepared if r.status == "fail"]
    if failures:
        lines += [
            "",
            "### Failed tests",
            "| # | Input | Expected | Actual | Bucket |",
            "|---|---|---|---|---|",
        ]
        for r in failures[:10]:
            actual = r.actual_intent or "NO_MATCH"
            lines.append(
                f"| {r.index} | {r.message} | `{r.expect_intent}` | `{actual}` | `{r.failure_bucket or 'unclassified'}` |"
            )
        if len(failures) > 10:
            lines.append(f"| … | *and {len(failures) - 10} more* | | | |")

        from collections import Counter
        bucket_counts = Counter(r.failure_bucket or "unclassified" for r in failures)
        lines += [
            "",
            "### Failure buckets",
            "| Bucket | Count |",
            "|---|---|",
        ]
        for bucket, count in bucket_counts.most_common():
            lines.append(f"| `{bucket}` | {count} |")

    phases_present = [r.phase for r in prepared if r.phase]
    if phases_present:
        from collections import defaultdict
        phase_data: dict[str, dict[str, float]] = defaultdict(
            lambda: {"pass": 0, "fail": 0, "xfail": 0, "xpass": 0, "time": 0.0}
        )
        for r in prepared:
            key = r.phase or "unknown"
            phase_data[key][r.status] += 1

        lines += [
            "",
            "<details>",
            "<summary>Phase breakdown</summary>",
            "",
            "| Phase | Pass | Fail | XFail | XPass |",
            "|---|---|---|---|---|",
        ]
        for phase_name in dict.fromkeys(r.phase for r in prepared if r.phase):
            d = phase_data[phase_name]
            lines.append(
                f"| {phase_name} | {int(d['pass'])} | {int(d['fail'])} | {int(d['xfail'])} | {int(d['xpass'])} |"
            )
        lines += ["", "</details>"]

    dt = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    lines += [
        "",
        "---",
        f"*Report generated by [adb_skill_test.py](scripts/adb_skill_test.py) on {dt}*",
        f"*Full HTML report: `{report_path.name}`*",
    ]
    return "\n".join(lines)



def post_pr_comment(results: list[TestResult], elapsed: float, report_path: Path) -> None:
    """Post a markdown test summary to the open PR for the current branch."""
    body = build_comment_markdown(results, elapsed, report_path)
    result = subprocess.run(
        ["gh", "pr", "comment", "--body", body],
        capture_output=True, text=True, cwd=Path(__file__).parent.parent,
    )
    if result.returncode == 0:
        print("  [report] PR comment posted ✓")
    else:
        print(f"  [report] PR comment failed: {result.stderr.strip()}", file=sys.stderr)


