#!/usr/bin/env python3
"""Publish a local permission scenario report bundle to test-results and a sticky PR comment.

This script is intentionally separate from ``run_permission_scenarios.py``.
Local scenario execution remains side-effect free; publication is an explicit follow-up step.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.parse import quote

import publish_test_evidence as publish_helpers

HERE = Path(__file__).resolve().parent
REPO = "NickMonrad/kernel-ai-assistant"
STICKY_MARKER = "<!-- jandal-permission-scenario-evidence -->"
LOGCAT_REDACTION_RE = re.compile(r"(HuggingFaceAuthManager: restored auth=[^,]+, user=)(\S+)")
MAX_PUBLISHED_LOGCAT_LINES = 200
MAX_PUBLISHED_LOGCAT_BYTES = 64 * 1024


class PublishError(RuntimeError):
    """Base error for validation or publish failures."""


@dataclass(slots=True)
class ReportBundle:
    report_dir: Path
    result_path: Path
    evidence_path: Path
    summary_path: Path
    logcat_path: Path | None
    screenshots_dir: Path | None
    result: dict[str, Any]
    evidence: dict[str, Any]
    screenshots: list[Path]


@dataclass(slots=True)
class PublishedPaths:
    results_base: str
    artifacts_base: str
    evidence: str
    result: str
    summary: str
    logcat: str | None
    screenshots_dir: str | None
    screenshots: list[str]


class GitHubClient:
    def __init__(self, repo: str) -> None:
        self.repo = repo

    def _run(self, *args: str, input_text: str | None = None) -> subprocess.CompletedProcess[str]:
        cmd = ["gh", *args]
        try:
            return subprocess.run(
                cmd,
                input=input_text,
                capture_output=True,
                text=True,
                check=True,
            )
        except FileNotFoundError as exc:
            raise PublishError("gh CLI is not installed or not on PATH") from exc
        except subprocess.CalledProcessError as exc:
            stderr = exc.stderr.strip() if exc.stderr else ""
            raise PublishError(f"gh command failed: {' '.join(cmd)}\n{stderr}".rstrip()) from exc

    def get_pr_head_sha(self, pr: int) -> str:
        result = self._run(
            "pr",
            "view",
            str(pr),
            "--repo",
            self.repo,
            "--json",
            "headRefOid",
            "--jq",
            ".headRefOid",
        )
        head = result.stdout.strip()
        if not head:
            raise PublishError(f"Could not resolve PR #{pr} head SHA")
        return head

    def find_sticky_comment_id(self, pr: int, marker: str) -> int | None:
        result = self._run(
            "api",
            f"repos/{self.repo}/issues/{pr}/comments?per_page=100",
            "--jq",
            f"[.[] | select(.body | startswith(\"{marker}\"))][0].id // empty",
        )
        raw = result.stdout.strip()
        return int(raw) if raw else None

    def upsert_comment(self, pr: int, marker: str, body: str) -> str:
        comment_id = self.find_sticky_comment_id(pr, marker)
        payload = json.dumps({"body": body})
        if comment_id is None:
            self._run(
                "api",
                f"repos/{self.repo}/issues/{pr}/comments",
                "-X",
                "POST",
                "--input",
                "-",
                input_text=payload,
            )
            return "created"
        self._run(
            "api",
            f"repos/{self.repo}/issues/comments/{comment_id}",
            "-X",
            "PATCH",
            "--input",
            "-",
            input_text=payload,
        )
        return "updated"


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Publish an existing permission scenario report to test-results and a sticky PR comment."
    )
    parser.add_argument("--report-dir", type=Path, required=True, help="Local report directory from run_permission_scenarios.py")
    parser.add_argument("--pr", type=int, required=True, help="Pull request number to publish against")
    parser.add_argument("--commit", required=True, help="Expected 40-character head SHA for the report and PR")
    parser.add_argument("--device-id", required=True, help="Expected device ID from devices.yaml and result.json")
    parser.add_argument("--target-branch", default=publish_helpers.DEFAULT_TARGET_BRANCH, help="Target branch for published artifacts")
    parser.add_argument("--repo", default=REPO, help="GitHub repo in owner/name form")
    parser.add_argument("--repo-url", default=None, help="Explicit git remote URL for test-results push")
    parser.add_argument("--allow-stale-report", action="store_true", help="Override report/PR head staleness checks for recovery-only use")
    parser.add_argument("--dry-run", action="store_true", help="Print the publish plan and sticky comment without pushing or posting")
    args = parser.parse_args(argv)
    if args.target_branch.lower() in {"main", "master"}:
        parser.error("--target-branch must not be a default branch")
    if len(args.commit) != 40:
        parser.error("--commit must be a full 40-character SHA")
    if "../" in args.target_branch or args.target_branch.startswith("/"):
        parser.error("--target-branch must be a plain branch name")
    publish_helpers._validate_path_segment(args.device_id, "--device-id")
    return args


def load_json(path: Path) -> dict[str, Any]:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise PublishError(f"Required file not found: {path}") from exc
    except json.JSONDecodeError as exc:
        raise PublishError(f"Invalid JSON in {path}: {exc}") from exc
    if not isinstance(data, dict):
        raise PublishError(f"Expected JSON object in {path}, got {type(data).__name__}")
    return data


def validate_report_dir(report_dir: Path) -> ReportBundle:
    if not report_dir.is_dir():
        raise PublishError(f"Report directory not found: {report_dir}")
    result_path = report_dir / "result.json"
    evidence_path = report_dir / "evidence.json"
    summary_path = report_dir / "summary.md"
    logcat_path = report_dir / "logcat.txt"
    screenshots_dir = report_dir / "screenshots"
    missing = [
        str(path.name)
        for path in (result_path, evidence_path, summary_path)
        if not path.is_file()
    ]
    if missing:
        raise PublishError(f"Report directory missing required file(s): {', '.join(missing)}")
    result = load_json(result_path)
    evidence = load_json(evidence_path)
    screenshots: list[Path] = []
    if screenshots_dir.is_dir():
        screenshots_dir = screenshots_dir
    else:
        screenshots_dir = None
    referenced_screenshots = sorted({
        str(step.get("screenshot"))
        for scenario in result.get("scenarios", [])
        for step in scenario.get("steps", [])
        if step.get("screenshot")
    })
    if referenced_screenshots and screenshots_dir is None:
        raise PublishError("Report references screenshots but screenshots/ directory is missing")
    if screenshots_dir is not None:
        missing_shots = [
            relpath for relpath in referenced_screenshots if not (report_dir / relpath).is_file()
        ]
        if missing_shots:
            raise PublishError(
                "Report references missing screenshot artifact(s): " + ", ".join(missing_shots)
            )
        screenshots = [report_dir / relpath for relpath in referenced_screenshots]
    if not logcat_path.is_file():
        logcat_path = None
    return ReportBundle(
        report_dir=report_dir,
        result_path=result_path,
        evidence_path=evidence_path,
        summary_path=summary_path,
        logcat_path=logcat_path,
        screenshots_dir=screenshots_dir,
        result=result,
        evidence=evidence,
        screenshots=screenshots,
    )


def validate_report_metadata(bundle: ReportBundle, args: argparse.Namespace, pr_head_sha: str | None) -> None:
    result = bundle.result
    evidence = bundle.evidence
    if result.get("source") != "on_device":
        raise PublishError(f"result.json source must be 'on_device', got {result.get('source')!r}")
    if evidence.get("source") != "on_device":
        raise PublishError(f"evidence.json source must be 'on_device', got {evidence.get('source')!r}")
    if result.get("suite") != "permission_scenarios":
        raise PublishError(f"result.json suite must be 'permission_scenarios', got {result.get('suite')!r}")
    if evidence.get("suite") != "permission_scenarios":
        raise PublishError(f"evidence.json suite must be 'permission_scenarios', got {evidence.get('suite')!r}")
    if result.get("device", {}).get("execution") != "physical":
        raise PublishError("result.json device.execution must be 'physical'")
    if evidence.get("device", {}).get("execution") != "physical":
        raise PublishError("evidence.json device.execution must be 'physical'")
    if result.get("device", {}).get("id") != args.device_id:
        raise PublishError(
            f"result.json device.id {result.get('device', {}).get('id')!r} != --device-id {args.device_id!r}"
        )
    if evidence.get("device", {}).get("id") != args.device_id:
        raise PublishError(
            f"evidence.json device.id {evidence.get('device', {}).get('id')!r} != --device-id {args.device_id!r}"
        )
    result_commit = result.get("commit")
    evidence_commit = evidence.get("commit")
    if not result_commit or not evidence_commit:
        raise PublishError("Both result.json and evidence.json must include commit metadata")
    if result_commit != evidence_commit:
        raise PublishError(f"Report commit mismatch inside bundle: result.json={result_commit} evidence.json={evidence_commit}")
    report_pr = result.get("pr")
    evidence_pr = evidence.get("pr")
    if report_pr is not None and report_pr != args.pr and not args.allow_stale_report:
        raise PublishError(f"result.json pr {report_pr!r} != --pr {args.pr}. Use --allow-stale-report to override.")
    if evidence_pr is not None and evidence_pr != args.pr and not args.allow_stale_report:
        raise PublishError(f"evidence.json pr {evidence_pr!r} != --pr {args.pr}. Use --allow-stale-report to override.")
    if not args.allow_stale_report and result_commit != args.commit:
        raise PublishError(
            f"Report commit {result_commit} != --commit {args.commit}. Use --allow-stale-report to override."
        )
    if pr_head_sha and not args.allow_stale_report and pr_head_sha != args.commit:
        raise PublishError(
            f"PR #{args.pr} head SHA {pr_head_sha} != --commit {args.commit}. Use --allow-stale-report to override."
        )


def ensure_schema_compatible_evidence(bundle: ReportBundle) -> None:
    schema = publish_helpers._load_schema(HERE / "testdata" / "test_evidence.schema.json")
    errors = publish_helpers._validate_evidence(bundle.evidence, schema)
    if errors:
        joined = "\n".join(f"  - {error}" for error in errors)
        raise PublishError(f"evidence.json failed schema validation:\n{joined}")


def ensure_no_serial_values(payload: Any, label: str, path: str = "$") -> None:
    if isinstance(payload, dict):
        for key, value in payload.items():
            current = f"{path}.{key}"
            if key == "serial" and value is not None:
                raise PublishError(f"{label} contains non-null serial at {current}")
            ensure_no_serial_values(value, label, current)
    elif isinstance(payload, list):
        for index, value in enumerate(payload):
            ensure_no_serial_values(value, label, f"{path}[{index}]")


def ensure_evidence_matches_result(bundle: ReportBundle) -> None:
    scenarios = bundle.result.get("scenarios", [])
    pass_fail = [scenario for scenario in scenarios if scenario.get("functional_result") in {"pass", "fail"}]
    evidence_cases = bundle.evidence.get("cases", [])
    scenario_ids = [scenario.get("scenario_id") for scenario in pass_fail]
    evidence_ids = [case.get("name") for case in evidence_cases]
    if scenario_ids != evidence_ids:
        raise PublishError(
            "evidence.json cases do not match pass/fail scenarios from result.json: "
            f"{scenario_ids!r} != {evidence_ids!r}"
        )


def sanitize_logcat_for_publish(logcat_path: Path) -> str:
    text = logcat_path.read_text(encoding="utf-8")
    redacted = LOGCAT_REDACTION_RE.sub(r"\1<redacted>", text)
    lines = redacted.splitlines()
    if len(lines) > MAX_PUBLISHED_LOGCAT_LINES:
        lines = lines[-MAX_PUBLISHED_LOGCAT_LINES:]
        lines.insert(0, f"[truncated to last {MAX_PUBLISHED_LOGCAT_LINES} lines for publication]")
    sanitized = "\n".join(lines).strip() + "\n"
    encoded = sanitized.encode("utf-8")
    if len(encoded) > MAX_PUBLISHED_LOGCAT_BYTES:
        sanitized = encoded[-MAX_PUBLISHED_LOGCAT_BYTES:].decode("utf-8", errors="ignore")
        sanitized = (
            f"[truncated to last {MAX_PUBLISHED_LOGCAT_BYTES} bytes for publication]\n{sanitized.lstrip()}"
        )
    return sanitized


def bundle_commit(bundle: ReportBundle) -> str:
    commit = bundle.result.get("commit") or bundle.evidence.get("commit")
    if not isinstance(commit, str) or not commit:
        raise PublishError("Report commit missing from result.json/evidence.json")
    return commit


def build_public_result(bundle: ReportBundle, pr: int) -> dict[str, Any]:
    public_result = json.loads(json.dumps(bundle.result))
    public_result["pr"] = pr
    public_result.setdefault("device", {})["serial"] = None
    for scenario in public_result.get("scenarios", []):
        if isinstance(scenario, dict):
            scenario["pr"] = pr
            scenario.setdefault("device", {})["serial"] = None
    ensure_no_serial_values(public_result, "public result.json")
    return public_result


def build_public_evidence(bundle: ReportBundle, pr: int) -> dict[str, Any]:
    public_evidence = json.loads(json.dumps(bundle.evidence))
    public_evidence["pr"] = pr
    ensure_no_serial_values(public_evidence, "public evidence.json")
    return public_evidence


def timestamp_slug(bundle: ReportBundle) -> str:
    timestamp = bundle.result.get("timestamp") or bundle.evidence.get("timestamp")
    if not isinstance(timestamp, str) or not timestamp:
        raise PublishError("Report timestamp missing from result.json/evidence.json")
    return timestamp.replace(":", "-")


def build_published_paths(bundle: ReportBundle, pr: int, device_id: str) -> PublishedPaths:
    stamp = timestamp_slug(bundle)
    results_base = f"results/pr/{pr}/on_device/permissions/{device_id}/{stamp}"
    artifacts_base = f"artifacts/pr/{pr}/permissions/{device_id}/{stamp}"
    screenshots_dir = f"{artifacts_base}/screenshots" if bundle.screenshots else None
    return PublishedPaths(
        results_base=results_base,
        artifacts_base=artifacts_base,
        evidence=f"{results_base}/evidence.json",
        result=f"{artifacts_base}/result.json",
        summary=f"{artifacts_base}/summary.md",
        logcat=f"{artifacts_base}/logcat-redacted.txt" if bundle.logcat_path else None,
        screenshots_dir=screenshots_dir,
        screenshots=[f"{artifacts_base}/screenshots/{shot.name}" for shot in bundle.screenshots],
    )


def prepare_publish_mapping(bundle: ReportBundle, published: PublishedPaths, scratch_dir: Path, pr: int) -> dict[Path, str]:
    public_result_path = scratch_dir / "result.json"
    public_result_path.write_text(json.dumps(build_public_result(bundle, pr), indent=2) + "\n", encoding="utf-8")
    public_evidence_path = scratch_dir / "evidence.json"
    public_evidence_path.write_text(json.dumps(build_public_evidence(bundle, pr), indent=2) + "\n", encoding="utf-8")
    mapping: dict[Path, str] = {
        public_evidence_path: published.evidence,
        bundle.summary_path: published.summary,
        public_result_path: published.result,
    }
    if bundle.logcat_path is not None and published.logcat is not None:
        sanitized_path = scratch_dir / "logcat-redacted.txt"
        sanitized_path.write_text(sanitize_logcat_for_publish(bundle.logcat_path), encoding="utf-8")
        mapping[sanitized_path] = published.logcat
    for shot, dest in zip(bundle.screenshots, published.screenshots):
        mapping[shot] = dest
    return mapping


def quote_path(value: str) -> str:
    return quote(value, safe="/")


def blob_url(repo: str, branch: str, relpath: str) -> str:
    return f"https://github.com/{repo}/blob/{quote(branch, safe='')}/{quote_path(relpath)}"


def tree_url(repo: str, branch: str, relpath: str) -> str:
    return f"https://github.com/{repo}/tree/{quote(branch, safe='')}/{quote_path(relpath)}"


def build_comment_body(bundle: ReportBundle, published: PublishedPaths, args: argparse.Namespace) -> str:
    result = bundle.result
    scenarios = result.get("scenarios", [])
    report_commit = bundle_commit(bundle)
    lines = [
        STICKY_MARKER,
        "",
        "## Permission scenario evidence",
        "",
        f"**Source:** `{result.get('source', 'unknown')}`  ",
        f"**Device:** {result.get('device', {}).get('label', 'Unknown')} (`{args.device_id}`)  ",
        f"**Commit:** `{report_commit[:12]}`  ",
        f"**Report timestamp:** `{result.get('timestamp', 'unknown')}`",
        "",
        "| Scenario | Functional | UX | Steps | Taps | Settings | Back | Duration | Blocked reason |",
        "|---|---|---|---:|---:|---:|---:|---:|---|",
    ]
    blocked_lines: list[str] = []
    for scenario in scenarios:
        title = scenario.get("scenario_title") or scenario.get("scenario_id") or "unknown"
        blocked_reason = scenario.get("blocked_reason") or "—"
        lines.append(
            "| {title} | {functional} | {ux} | {steps} | {taps} | {settings} | {back} | {duration:.1f}s | {blocked} |".format(
                title=str(title).replace("|", "\\|"),
                functional=scenario.get("functional_result", "unknown"),
                ux=scenario.get("ux_result", "unknown"),
                steps=scenario.get("step_count", 0),
                taps=scenario.get("tap_count", 0),
                settings=scenario.get("settings_hops", 0),
                back=scenario.get("back_presses", 0),
                duration=float(scenario.get("duration_seconds", 0.0)),
                blocked=str(blocked_reason).replace("|", "\\|") if blocked_reason else "—",
            )
        )
        if scenario.get("functional_result") in {"blocked", "skipped"} and scenario.get("blocked_reason"):
            blocked_lines.append(f"- `{scenario.get('scenario_id', 'unknown')}` — {scenario['blocked_reason']}")
    lines.extend([
        "",
        "### Published artifacts",
        "",
        f"- [Schema-compatible evidence]({blob_url(args.repo, args.target_branch, published.evidence)})",
        f"- [Rich summary]({blob_url(args.repo, args.target_branch, published.summary)})",
        f"- [Public raw result JSON]({blob_url(args.repo, args.target_branch, published.result)})",
    ])
    if published.logcat:
        lines.append(f"- [Focused redacted logcat]({blob_url(args.repo, args.target_branch, published.logcat)})")
    else:
        lines.append("- Focused redacted logcat: unavailable")
    if published.screenshots_dir:
        lines.append(f"- [Screenshots]({tree_url(args.repo, args.target_branch, published.screenshots_dir)})")
    else:
        lines.append("- Screenshots: unavailable")
    if args.allow_stale_report and report_commit != args.commit:
        lines.extend(["", f"> Override used: report commit `{report_commit[:12]}` published against requested head `{args.commit[:12]}`."])
    if blocked_lines:
        lines.extend(["", "### Blocked scenarios", "", *blocked_lines])
    lines.extend([
        "",
        "> This comment is updated in place when the permission scenario publisher is re-run for the same PR.",
        "> Blocked/skipped scenarios remain visible here, but only pass/fail cases are projected into schema evidence.",
    ])
    return "\n".join(lines).rstrip() + "\n"


def choose_comment_action(existing_comment_id: int | None) -> str:
    return "update" if existing_comment_id is not None else "create"


def commit_message(pr: int, commit: str, device_id: str) -> str:
    return f"test-results: add permission evidence for PR #{pr} {device_id} @ {commit[:12]}"


def print_publish_plan(mapping: dict[Path, str], comment_body: str, action: str, args: argparse.Namespace) -> None:
    print(f"PR:             #{args.pr}")
    print(f"Expected head:  {args.commit}")
    print(f"Device:         {args.device_id}")
    print(f"Target branch:  {args.target_branch}")
    print(f"Dry run:        {args.dry_run}")
    print(f"Sticky comment: {action}")
    print(f"Stale override: {args.allow_stale_report}")
    print("")
    for local, dest in sorted(mapping.items(), key=lambda item: item[1]):
        print(f"  {local} -> {dest}")
    print("\n--- Sticky comment preview ---\n")
    print(comment_body)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    bundle = validate_report_dir(args.report_dir)
    ensure_schema_compatible_evidence(bundle)
    ensure_no_serial_values(bundle.evidence, "evidence.json")
    ensure_evidence_matches_result(bundle)
    github = GitHubClient(args.repo)
    pr_head_sha = github.get_pr_head_sha(args.pr)
    validate_report_metadata(bundle, args, pr_head_sha)
    existing_comment_id = github.find_sticky_comment_id(args.pr, STICKY_MARKER)
    published = build_published_paths(bundle, args.pr, args.device_id)
    with tempfile.TemporaryDirectory(prefix="permission-publish-") as scratch:
        mapping = prepare_publish_mapping(bundle, published, Path(scratch), args.pr)
        comment_body = build_comment_body(bundle, published, args)
        action = choose_comment_action(existing_comment_id)
        print_publish_plan(mapping, comment_body, action, args)
        publish_helpers._check_git_available()
        if args.dry_run:
            return 0
        repo_url = args.repo_url or publish_helpers._get_remote_url()
        publish_helpers._publish_to_branch(
            mapping=mapping,
            target_branch=args.target_branch,
            commit_msg=commit_message(args.pr, bundle_commit(bundle), args.device_id),
            repo_url=repo_url,
            dry_run=False,
        )
        comment_action = github.upsert_comment(args.pr, STICKY_MARKER, comment_body)
        print(f"Sticky comment {comment_action} on PR #{args.pr}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except PublishError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1)
