#!/usr/bin/env python3
"""
Audit open issues in a GitHub repository for metadata hygiene.

Checks:
  - Missing labels (no labels at all)
  - Missing milestone
  - Duplicate priority labels (e.g. priority:medium + priority:high)
  - Duplicate launch-status labels (e.g. launch:blocking + launch:post)
  - launch:blocking issues that may be stale (no activity in 60+ days, no recent PR)
  - Issues without a parent epic reference where one is expected
    (type:feature, type:bug, type:chore at size M+)

Output: human-readable summary grouped by category, or JSON with --json.

Usage:
  python3 scripts/audit_backlog.py
  python3 scripts/audit_backlog.py --owner NickMonrad --repo kernel-ai-assistant
  python3 scripts/audit_backlog.py --json
  python3 scripts/audit_backlog.py --max-pages 5

Requires: PyGithub (pip install PyGithub)
           or GITHUB_TOKEN env var for unauthenticated rate limits (60/hr).
"""

import argparse
import json
import os
import sys
from datetime import datetime, timezone, timedelta
from typing import Any

STALE_DAYS = 60

# Expected label categories and their valid values
PRIORITY_LABELS = {"priority:high", "priority:medium", "priority:low"}
LAUNCH_LABELS = {"launch:blocking", "launch:post", "launch:deferred"}
TYPE_LABELS = {"type:epic", "type:feature", "type:bug", "type:chore", "type:spike", "type:performance"}
SIZE_LABELS = {"size:XS", "size:S", "size:M", "size:L", "size:XL"}

# Heuristic: issues of these types at M+ size should have a parent epic
PARENT_EPIC_TYPES = {"type:feature", "type:bug", "type:chore"}
PARENT_EPIC_MIN_SIZE = {"size:M", "size:L", "size:XL"}


def get_github_client(token: str | None = None):
    """Get a PyGithub client using the provided token or from GITHUB_TOKEN env."""
    try:
        from github import Github

        gh = Github(token or os.environ.get("GITHUB_TOKEN"))
        # Check connectivity
        gh.get_user().login
        return gh
    except ImportError:
        print("ERROR: PyGithub not installed. Install with: pip install PyGithub", file=sys.stderr)
        sys.exit(1)
    except Exception as e:
        print(f"ERROR: GitHub authentication failed: {e}", file=sys.stderr)
        print("Set GITHUB_TOKEN in environment or use --token.", file=sys.stderr)
        sys.exit(1)


def fetch_issues(gh, owner: str, repo: str, max_pages: int = 10) -> list[Any]:
    """Fetch all open issues (excluding PRs) from the repository."""
    r = gh.get_repo(f"{owner}/{repo}")
    issues = []
    page = 0
    for issue in r.get_issues(state="open", sort="updated", direction="desc"):
        if issue.pull_request is not None:
            continue  # Skip PRs
        issues.append(issue)
        page += 1
        if page >= max_pages * 100:
            break
    return issues


def get_label_names(issue) -> list[str]:
    """Get list of label names for an issue."""
    return [label.name for label in issue.labels]


def find_duplicates(labels: list[str], valid_set: set[str]) -> list[str]:
    """Find labels from valid_set that appear in labels (should be exactly one)."""
    found = [l for l in labels if l in valid_set]
    return found


def has_parent_epic(body: str | None) -> bool:
    """Check if issue body references a parent epic (#NNN with epic)."""
    if not body:
        return False
    body_lower = body.lower()
    # Look for common parent epic markers
    markers = [
        "parent epic",
        "parent:",
        "epic:",
        "closes #",
        "part of #",
        "tracked by #",
        "child of #",
    ]
    return any(marker in body_lower for marker in markers)


def is_stale(issue, now: datetime) -> bool:
    """Check if an issue has been inactive for STALE_DAYS and no recent PR activity."""
    updated = issue.updated_at.replace(tzinfo=None) if issue.updated_at.tzinfo else issue.updated_at
    if (now - updated).days < STALE_DAYS:
        return False

    # Check for linked PRs via issue.get_pulls() or timeline
    try:
        # Try timeline events for cross-references
        for event in issue.get_timeline():
            if hasattr(event, "event") and event.event == "cross-referenced":
                ref = getattr(event, "source", None)
                if ref and getattr(ref, "type", None) == "issue":
                    pr_ref = ref.issue
                    if pr_ref and pr_ref.pull_request and pr_ref.state == "open":
                        return False
            # Limit iteration
    except Exception:
        pass

    return True


def check_parent_epic(issue, labels: list[str]) -> str | None:
    """Check if an M+ feature/bug/chore issue is missing a parent epic reference."""
    issue_types = set(labels) & TYPE_LABELS
    issue_sizes = set(labels) & SIZE_LABELS

    has_relevant_type = bool(issue_types & PARENT_EPIC_TYPES)
    has_relevant_size = bool(issue_sizes & PARENT_EPIC_MIN_SIZE)

    if has_relevant_type and has_relevant_size:
        if not has_parent_epic(issue.body):
            return f"#{issue.number} ({issue.title[:60]}) — {', '.join(issue_types & PARENT_EPIC_TYPES)}, {', '.join(issue_sizes & PARENT_EPIC_MIN_SIZE)} — no parent epic reference"
    return None


def audit_repo(
    owner: str, repo: str, max_pages: int = 10, gh_token: str | None = None
) -> dict[str, Any]:
    """Run the full audit and return structured results."""
    gh = get_github_client(gh_token)
    issues = fetch_issues(gh, owner, repo, max_pages)
    now = datetime.now(timezone.utc).replace(tzinfo=None)

    results = {
        "meta": {
            "owner": owner,
            "repo": repo,
            "total_open_issues": len(issues),
            "audit_timestamp": now.isoformat(),
        },
        "missing_labels": [],
        "missing_milestone": [],
        "duplicate_priority": [],
        "duplicate_launch": [],
        "stale_launch_blocking": [],
        "missing_parent_epic": [],
    }

    for issue in issues:
        labels = get_label_names(issue)
        number = issue.number
        title = issue.title
        updated = issue.updated_at

        # -- Missing labels (no labels at all) --
        if not labels:
            results["missing_labels"].append(f"#{number} — {title[:80]}")
            continue

        # -- Missing milestone --
        if issue.milestone is None:
            results["missing_milestone"].append(f"#{number} — {title[:80]}")

        # -- Duplicate priority labels --
        priority_found = find_duplicates(labels, PRIORITY_LABELS)
        if len(priority_found) > 1:
            results["duplicate_priority"].append(
                f"#{number} — {title[:60]} — has: {', '.join(priority_found)}"
            )

        # -- Duplicate launch labels --
        launch_found = find_duplicates(labels, LAUNCH_LABELS)
        if len(launch_found) > 1:
            results["duplicate_launch"].append(
                f"#{number} — {title[:60]} — has: {', '.join(launch_found)}"
            )

        # -- Stale launch:blocking --
        if "launch:blocking" in labels:
            # Check for staleness
            updated_naive = updated.replace(tzinfo=None) if updated.tzinfo else updated
            days_since_update = (now - updated_naive).days
            stale_info = ""
            if days_since_update > STALE_DAYS:
                stale_info = f" (last updated {days_since_update}d ago)"
            else:
                stale_info = f" (last updated {days_since_update}d ago — not stale)"
            results["stale_launch_blocking"].append(
                f"#{number} — {title[:60]}{stale_info}"
            )

        # -- Missing parent epic for M+ feature/bug/chore --
        parent_result = check_parent_epic(issue, labels)
        if parent_result:
            results["missing_parent_epic"].append(parent_result)

    return results


def print_results(results: dict[str, Any]):
    """Print human-readable audit report."""
    meta = results["meta"]
    print("=" * 72)
    print(f"  Backlog Hygiene Audit: {meta['owner']}/{meta['repo']}")
    print(f"  {meta['total_open_issues']} open issues (excluding PRs)")
    print(f"  Audit timestamp: {meta['audit_timestamp']}")
    print("=" * 72)

    categories = [
        ("missing_labels", "🚫 Issues with no labels", False),
        ("missing_milestone", "📅 Issues with no milestone", False),
        ("duplicate_priority", "⚠️  Duplicate priority labels", False),
        ("duplicate_launch", "⚠️  Duplicate launch-status labels", False),
        ("stale_launch_blocking", "🔍 launch:blocking issues (stale check)", False),
        ("missing_parent_epic", "🔗 M+ issues without parent epic reference", False),
    ]

    any_findings = False
    for key, header, _ in categories:
        items = results.get(key, [])
        if not items:
            continue
        any_findings = True
        print(f"\n{header}:")
        print("-" * len(header))
        stale_count = 0
        for item in items:
            # Mark stale launch blockers
            if key == "stale_launch_blocking" and "stale" in item.lower():
                print(f"  ⏳ {item}")
                stale_count += 1
            elif key == "stale_launch_blocking":
                print(f"  ✅ {item}")
            else:
                print(f"  • {item}")
        if stale_count and stale_count < len(items):
            print(f"\n  ({stale_count}/{len(items)} may be stale candidates)")
        print(f"  Total: {len(items)}")

    if not any_findings:
        print("\n✨ No hygiene issues found — backlog is clean!")

    print()
    print("Summary:")
    print(f"  No labels:       {len(results['missing_labels'])}")
    print(f"  No milestone:    {len(results['missing_milestone'])}")
    print(f"  Dup priority:    {len(results['duplicate_priority'])}")
    print(f"  Dup launch:      {len(results['duplicate_launch'])}")
    print(f"  Launch blocking: {len(results['stale_launch_blocking'])}")
    print(f"  No parent epic:  {len(results['missing_parent_epic'])}")
    print()


def main():
    parser = argparse.ArgumentParser(
        description="Audit open issue metadata for backlog hygiene."
    )
    parser.add_argument("--owner", default="NickMonrad", help="GitHub owner/org")
    parser.add_argument("--repo", default="kernel-ai-assistant", help="Repository name")
    parser.add_argument(
        "--token",
        default=None,
        help="GitHub token (falls back to GITHUB_TOKEN env)",
    )
    parser.add_argument(
        "--max-pages",
        type=int,
        default=10,
        help="Max pages of issues to fetch (100 per page)",
    )
    parser.add_argument(
        "--json",
        action="store_true",
        help="Output as JSON instead of human-readable",
    )
    args = parser.parse_args()

    results = audit_repo(
        owner=args.owner,
        repo=args.repo,
        max_pages=args.max_pages,
        gh_token=args.token,
    )

    if args.json:
        print(json.dumps(results, indent=2, default=str))
    else:
        print_results(results)

    # Exit non-zero if any issues found
    total_issues = (
        len(results["missing_labels"])
        + len(results["missing_milestone"])
        + len(results["duplicate_priority"])
        + len(results["duplicate_launch"])
        + len(results["missing_parent_epic"])
    )
    sys.exit(0 if total_issues == 0 else 1)


if __name__ == "__main__":
    main()
