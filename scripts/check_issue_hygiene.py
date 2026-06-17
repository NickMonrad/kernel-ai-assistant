#!/usr/bin/env python3
"""Backlog hygiene checker for GitHub issues.

The checker is intentionally a warning signal by default. It helps agents and
maintainers spot issues that are not ready for implementation handoff without
turning lightweight triage into a hard merge gate.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Any, Iterable

TYPE_LABELS = {
    "type:epic",
    "type:feature",
    "type:bug",
    "type:chore",
    "type:spike",
    "type:performance",
}
SIZE_LABELS = {"size:XS", "size:S", "size:M", "size:L", "size:XL"}
PRIORITY_LABELS = {"priority:high", "priority:medium", "priority:low"}
LAUNCH_LABELS = {"launch:blocking", "launch:post", "launch:deferred"}

POLICY_LABELS = TYPE_LABELS | SIZE_LABELS | PRIORITY_LABELS | LAUNCH_LABELS

PARKED_MARKERS = (
    "intentionally parked",
    "parked without milestone",
    "milestone intentionally omitted",
    "no milestone required",
    "no milestone: parked",
)

PARENT_MARKERS = (
    "parent epic",
    "parent tracker",
    "parent / launch context",
    "workstream",
    "standalone issue",
    "standalone:",
)

TEST_MARKERS = (
    "## testing",
    "testing expectations",
    "test plan",
    "device validation",
    "manual test",
    "automated test",
    "s21",
    "s23",
    "evidence",
    "validation",
)


@dataclass(frozen=True)
class IssueViolation:
    """Validation result for one issue."""

    number: int
    title: str
    url: str
    messages: list[str]


def _issue_labels(issue: dict[str, Any]) -> set[str]:
    return {label.get("name", "") for label in issue.get("labels", []) if label.get("name")}


def _exactly_one_label(labels: set[str], allowed: set[str], category: str) -> str | None:
    matches = sorted(labels & allowed)
    if len(matches) == 1:
        return None
    if not matches:
        return f"missing exactly one `{category}` label"
    return f"has multiple `{category}` labels: {', '.join(matches)}"


def _has_acceptance_criteria(body: str) -> bool:
    lower = body.lower()
    return "acceptance criteria" in lower and ("- [ ]" in lower or "- [x]" in lower)


def _has_parent_or_standalone_marker(body: str, labels: set[str]) -> bool:
    if "type:epic" in labels:
        return True
    lower = body.lower()
    return any(marker in lower for marker in PARENT_MARKERS)


def _has_testing_expectations(body: str) -> bool:
    lower = body.lower()
    return any(marker in lower for marker in TEST_MARKERS)


def _is_milestone_intentionally_parked(text: str) -> bool:
    lower = text.lower()
    return any(marker in lower for marker in PARKED_MARKERS)


def _body_plus_comments(body: str, comment_bodies: Iterable[str] | None) -> str:
    comments = [comment for comment in comment_bodies or [] if comment]
    if not comments:
        return body
    return body + "\n\n" + "\n\n".join(comments)


def validate_issue(
    issue: dict[str, Any],
    comment_bodies: Iterable[str] | None = None,
) -> IssueViolation | None:
    """Return an IssueViolation when the issue does not satisfy the policy.

    The issue body remains the source of truth for implementation readiness.
    Comments are only used for the explicit no-milestone/parked rationale
    allowed by the backlog hygiene policy.
    """

    # GitHub's issues endpoint returns PRs as issue-shaped records.
    if issue.get("pull_request"):
        return None

    labels = _issue_labels(issue)
    body = issue.get("body") or ""
    body_and_comments = _body_plus_comments(body, comment_bodies)
    messages: list[str] = []

    for category, allowed in (
        ("type:*", TYPE_LABELS),
        ("size:*", SIZE_LABELS),
        ("priority:*", PRIORITY_LABELS),
        ("launch:*", LAUNCH_LABELS),
    ):
        result = _exactly_one_label(labels, allowed, category)
        if result:
            messages.append(result)

    domain_labels = sorted(labels - POLICY_LABELS)
    if not domain_labels:
        messages.append("missing at least one domain label such as `UX`, `voice`, `testing`, or `technical-debt`")

    if not issue.get("milestone") and not _is_milestone_intentionally_parked(body_and_comments):
        messages.append("missing milestone or explicit parked/no-milestone rationale")

    if not _has_parent_or_standalone_marker(body, labels):
        messages.append("missing parent epic/workstream reference or explicit standalone marker")

    if not _has_acceptance_criteria(body):
        messages.append("missing `Acceptance criteria` checklist")

    if not _has_testing_expectations(body):
        messages.append("missing testing/device validation expectations")

    if not messages:
        return None

    return IssueViolation(
        number=int(issue.get("number", 0)),
        title=str(issue.get("title", "<untitled>")),
        url=str(issue.get("html_url", "")),
        messages=messages,
    )


def _github_request(url: str, token: str | None) -> Any:
    headers = {
        "Accept": "application/vnd.github+json",
        "User-Agent": "jandal-backlog-hygiene-checker",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    if token:
        headers["Authorization"] = f"Bearer {token}"

    request = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"GitHub API request failed: {exc.code} {exc.reason}: {detail}") from exc


def fetch_issue(repo: str, issue_number: int, token: str | None) -> dict[str, Any]:
    url = f"https://api.github.com/repos/{repo}/issues/{issue_number}"
    return _github_request(url, token)


def fetch_issue_comments(repo: str, issue_number: int, token: str | None) -> list[str]:
    """Fetch issue comment bodies for no-milestone parked rationale checks."""

    comment_bodies: list[str] = []
    page = 1
    while True:
        url = (
            f"https://api.github.com/repos/{repo}/issues/{issue_number}/comments"
            f"?per_page=100&page={page}"
        )
        batch = _github_request(url, token)
        if not batch:
            break
        comment_bodies.extend(comment.get("body") or "" for comment in batch)
        page += 1
    return comment_bodies


def fetch_open_issues(repo: str, token: str | None) -> list[dict[str, Any]]:
    issues: list[dict[str, Any]] = []
    page = 1
    while True:
        url = f"https://api.github.com/repos/{repo}/issues?state=open&per_page=100&page={page}"
        batch = _github_request(url, token)
        if not batch:
            break
        issues.extend(batch)
        page += 1
    return issues


def _escape_annotation(text: str) -> str:
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def build_summary(violations: Iterable[IssueViolation]) -> str:
    items = list(violations)
    if not items:
        return "## Backlog hygiene\n\nAll checked issues satisfy the backlog hygiene policy."

    lines = [
        "## Backlog hygiene warnings",
        "",
        "These warnings do not block CI by default, but the issues below are not ready for clean agent handoff.",
        "",
    ]
    for violation in items:
        title = f"#{violation.number} — {violation.title}"
        if violation.url:
            title = f"[#{violation.number} — {violation.title}]({violation.url})"
        lines.append(f"### {title}")
        for message in violation.messages:
            lines.append(f"- {message}")
        lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def emit_github_annotations(violations: Iterable[IssueViolation]) -> None:
    for violation in violations:
        joined = "; ".join(violation.messages[:4])
        if len(violation.messages) > 4:
            joined += f"; +{len(violation.messages) - 4} more"
        print(
            f"::warning title=Backlog hygiene #{violation.number}::{_escape_annotation(joined)}"
        )


def _comment_bodies_for_issue(repo: str, issue: dict[str, Any], token: str | None) -> list[str]:
    """Fetch comments only when they are needed for no-milestone rationale."""

    if issue.get("pull_request") or issue.get("milestone"):
        return []
    issue_number = int(issue.get("number", 0))
    if issue_number <= 0:
        return []
    return fetch_issue_comments(repo, issue_number, token)


def main() -> None:
    parser = argparse.ArgumentParser(description="Check GitHub issue backlog hygiene metadata.")
    parser.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY"), help="Repository in owner/name form")
    parser.add_argument("--issue-number", type=int, default=None, help="Check a single issue number")
    parser.add_argument(
        "--fail-on-violations",
        action="store_true",
        help="Exit 1 when hygiene violations are found. Default is warning-only.",
    )
    parser.add_argument(
        "--token-env",
        default="GITHUB_TOKEN",
        help="Environment variable containing a GitHub token. Default: GITHUB_TOKEN",
    )
    args = parser.parse_args()

    if not args.repo:
        print("::error::Repository is required via --repo or GITHUB_REPOSITORY", file=sys.stderr)
        sys.exit(2)

    token = os.environ.get(args.token_env)
    issues = [fetch_issue(args.repo, args.issue_number, token)] if args.issue_number else fetch_open_issues(args.repo, token)
    violations: list[IssueViolation] = []
    for issue in issues:
        comment_bodies = _comment_bodies_for_issue(args.repo, issue, token)
        violation = validate_issue(issue, comment_bodies=comment_bodies)
        if violation:
            violations.append(violation)

    summary = build_summary(violations)
    print(summary)
    emit_github_annotations(violations)

    step_summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if step_summary:
        with open(step_summary, "a", encoding="utf-8") as handle:
            handle.write(summary)

    if violations and args.fail_on_violations:
        sys.exit(1)
    sys.exit(0)


if __name__ == "__main__":
    main()
