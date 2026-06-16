#!/usr/bin/env python3
"""Tests for the backlog hygiene issue checker."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT_DIR = HERE.parent
sys.path.insert(0, str(SCRIPT_DIR))

import check_issue_hygiene as cih


def _issue(
    *,
    labels: list[str],
    body: str | None = None,
    milestone: dict[str, object] | None = None,
) -> dict[str, object]:
    resolved_milestone = milestone if milestone is not None else {"number": 3, "title": "Launch"}
    return {
        "number": 123,
        "title": "Test issue",
        "html_url": "https://github.com/NickMonrad/kernel-ai-assistant/issues/123",
        "labels": [{"name": label} for label in labels],
        "body": body
        or """Parent epic: #1

## Summary
Do the thing.

## Acceptance criteria
- [ ] Thing works.

## Testing
- [ ] Unit tests pass.
""",
        "milestone": resolved_milestone,
    }


def _issue_without_milestone(*, labels: list[str], body: str | None = None) -> dict[str, object]:
    issue = _issue(labels=labels, body=body)
    issue["milestone"] = None
    return issue


class ValidateIssueTest(unittest.TestCase):
    def test_valid_issue_passes(self) -> None:
        issue = _issue(
            labels=[
                "type:feature",
                "size:S",
                "priority:medium",
                "launch:post",
                "UX",
            ]
        )

        self.assertIsNone(cih.validate_issue(issue))

    def test_detects_missing_launch_and_domain_labels(self) -> None:
        issue = _issue(labels=["type:chore", "size:S", "priority:high"])

        violation = cih.validate_issue(issue)

        self.assertIsNotNone(violation)
        messages = "\n".join(violation.messages) if violation else ""
        self.assertIn("launch:*", messages)
        self.assertIn("domain label", messages)

    def test_detects_missing_milestone(self) -> None:
        issue = _issue_without_milestone(
            labels=["type:bug", "size:XS", "priority:high", "launch:blocking", "testing"]
        )

        violation = cih.validate_issue(issue)

        self.assertIsNotNone(violation)
        messages = "\n".join(violation.messages) if violation else ""
        self.assertIn("missing milestone", messages)

    def test_parked_issue_can_omit_milestone_from_body_rationale(self) -> None:
        issue = _issue_without_milestone(
            labels=["type:spike", "size:S", "priority:low", "launch:deferred", "research"],
            body="""Standalone issue.

Milestone intentionally omitted: intentionally parked until post-launch.

## Acceptance criteria
- [ ] Decision is captured.

## Testing
- [ ] No device testing required for research-only spike.
""",
        )

        self.assertIsNone(cih.validate_issue(issue))

    def test_parked_issue_can_omit_milestone_from_comment_rationale(self) -> None:
        issue = _issue_without_milestone(
            labels=["type:spike", "size:S", "priority:low", "launch:deferred", "research"],
            body="""Standalone issue.

## Acceptance criteria
- [ ] Decision is captured.

## Testing
- [ ] No device testing required for research-only spike.
""",
        )

        self.assertIsNone(
            cih.validate_issue(
                issue,
                comment_bodies=["Milestone intentionally omitted: parked until the post-launch review."],
            )
        )

    def test_comment_rationale_only_applies_to_milestone_check(self) -> None:
        issue = _issue_without_milestone(
            labels=["type:spike", "size:S", "priority:low", "launch:deferred", "research"],
            body="""Standalone issue.

## Summary
Needs decision.
""",
        )

        violation = cih.validate_issue(
            issue,
            comment_bodies=["Milestone intentionally omitted: parked until post-launch."],
        )

        self.assertIsNotNone(violation)
        messages = "\n".join(violation.messages) if violation else ""
        self.assertNotIn("missing milestone", messages)
        self.assertIn("Acceptance criteria", messages)
        self.assertIn("testing/device", messages)

    def test_epic_does_not_need_parent_marker(self) -> None:
        issue = _issue(
            labels=["type:epic", "size:XL", "priority:medium", "launch:post", "skills"],
            body="""## Summary
Parent tracker for a workstream.

## Acceptance criteria
- [ ] Children are listed.

## Testing
- [ ] Child issues define test evidence.
""",
        )

        self.assertIsNone(cih.validate_issue(issue))

    def test_pull_requests_are_skipped(self) -> None:
        issue = _issue(labels=[])
        issue["pull_request"] = {"url": "https://api.github.com/repos/x/y/pulls/1"}

        self.assertIsNone(cih.validate_issue(issue))


class SummaryTest(unittest.TestCase):
    def test_summary_for_clean_result(self) -> None:
        summary = cih.build_summary([])

        self.assertIn("All checked issues satisfy", summary)

    def test_summary_lists_violation(self) -> None:
        violation = cih.IssueViolation(
            number=1,
            title="Needs metadata",
            url="https://github.com/NickMonrad/kernel-ai-assistant/issues/1",
            messages=["missing label"],
        )

        summary = cih.build_summary([violation])

        self.assertIn("Needs metadata", summary)
        self.assertIn("missing label", summary)


if __name__ == "__main__":
    unittest.main()
