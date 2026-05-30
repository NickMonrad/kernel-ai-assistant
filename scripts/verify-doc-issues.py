#!/usr/bin/env python3
"""
verify-doc-issues.py

Checks every issue reference in ROADMAP.md and README.md against the live
GitHub API and reports status mismatches.

Usage:
    python3 scripts/verify-doc-issues.py [--fix]

    --fix   Write corrections back to ROADMAP.md and README.md in-place.
            Always review the diff before committing.

Requires:
    gh CLI authenticated (gh auth status)
    Python 3.9+

Exit codes:
    0  No mismatches found
    1  Mismatches found (or --fix made changes)
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path

REPO = "NickMonrad/kernel-ai-assistant"
REPO_ROOT = Path(__file__).resolve().parent.parent

# Files to scan
DOC_FILES = [
    REPO_ROOT / "docs" / "ROADMAP.md",
    REPO_ROOT / "README.md",
    REPO_ROOT / "docs" / "SPECIFICATION.md",
]

# ── Patterns ──────────────────────────────────────────────────────────────────

# Matches any table row that starts with a linked issue reference.
# Cell splitting happens in parse_claims so status is found by content,
# not by column position (which varies across 3- and 4-column tables).
TABLE_ROW_RE = re.compile(
    r"^\|\s*\[#(?P<num>\d+)\]\([^)]+\)\s*\|(?P<rest>[^\n]+)$",
    re.MULTILINE,
)

# "Coming Soon" bullet references — no explicit status cell, but the issue is
# open by definition (it's in Coming Soon).  We track these separately.
COMING_SOON_RE = re.compile(
    r"^\s*-\s+.*\*\([^)]*#(?P<num>\d+)[^)]*\)\*",
    re.MULTILINE,
)

# Issues mentioned anywhere (for the ideas table and inline prose).
# These carry less rigid status claims; we report but don't auto-fix.
ANY_ISSUE_RE = re.compile(r"#(?P<num>\d+)")

# Status tokens we care about
DONE_RE    = re.compile(r"✅\s*Done", re.IGNORECASE)
PENDING_RE = re.compile(r"⬜\s*Pending", re.IGNORECASE)
CLOSED_RE  = re.compile(r"🔴\s*Closed", re.IGNORECASE)


def classify_claimed(status_text: str) -> str:
    """Return 'done', 'pending', or 'closed' from a status cell string."""
    if DONE_RE.search(status_text):
        return "done"
    if CLOSED_RE.search(status_text):
        return "closed"
    if PENDING_RE.search(status_text):
        return "pending"
    return "unknown"


# ── GitHub fetch ───────────────────────────────────────────────────────────────

@dataclass
class IssueState:
    number: int
    title: str
    state: str          # 'open' or 'closed'
    state_reason: str   # 'completed', 'not_planned', 'reopened', or ''
    is_pr: bool = False


def fetch_issues(numbers: list[int]) -> dict[int, IssueState]:
    """Batch-fetch issue/PR state via `gh api` GraphQL to minimise round trips."""
    if not numbers:
        return {}

    # GraphQL aliases — each alias is issue<N>
    fragments = []
    for n in numbers:
        fragments.append(
            f'issue{n}: issueOrPullRequest(number: {n}) {{\n'
            f'  ... on Issue {{ number title state stateReason }}\n'
            f'  ... on PullRequest {{ number title state merged }}\n'
            f'}}'
        )
    query = "query { repository(owner: \"NickMonrad\", name: \"kernel-ai-assistant\") {\n" + \
            "\n".join(fragments) + "\n}}"

    result = subprocess.run(
        ["gh", "api", "graphql", "-f", f"query={query}"],
        capture_output=True, text=True,
    )
    if result.returncode != 0:
        print(f"ERROR: gh api failed:\n{result.stderr}", file=sys.stderr)
        sys.exit(2)

    data = json.loads(result.stdout)
    repo_data = data["data"]["repository"]

    states: dict[int, IssueState] = {}
    for key, val in repo_data.items():
        if val is None:
            continue
        n = val["number"]
        if "merged" in val:
            # It's a PR
            state = "closed" if val["state"] in ("CLOSED", "MERGED") else "open"
            states[n] = IssueState(n, val["title"], state, "completed" if val.get("merged") else "not_planned", is_pr=True)
        else:
            state = "open" if val["state"] == "OPEN" else "closed"
            reason = (val.get("stateReason") or "").lower()
            states[n] = IssueState(n, val["title"], state, reason)
    return states


# ── Doc parsing ────────────────────────────────────────────────────────────────

@dataclass
class DocClaim:
    file: Path
    line: int
    issue: int
    claimed: str   # 'done', 'pending', 'closed', 'coming_soon', 'unknown'
    raw_status: str
    context: str   # the full line


def parse_claims(path: Path) -> list[DocClaim]:
    text = path.read_text()
    lines = text.splitlines()
    claims: list[DocClaim] = []
    # Every table row is checked independently — an issue can appear in multiple
    # tables (e.g. the phase table and the ideas table) and each row must be
    # correct. Coming Soon bullets skip issues already covered by a table row
    # in the same file to avoid double-reporting.
    table_issues: set[int] = set()

    for m in TABLE_ROW_RE.finditer(text):
        num = int(m.group("num"))
        # Split remaining cells, find the one that classifies as a known status.
        cells = [c.strip() for c in m.group("rest").split("|") if c.strip()]
        status_text = next((c for c in cells if classify_claimed(c) != "unknown"), None)
        if status_text is None:
            continue  # row has no recognisable status cell (e.g. header dividers)
        claimed = classify_claimed(status_text)
        line_num = text[: m.start()].count("\n") + 1
        table_issues.add(num)
        claims.append(DocClaim(path, line_num, num, claimed, status_text, lines[line_num - 1]))

    # Coming Soon bullets — claimed open by definition; skip if already in a table row
    for m in COMING_SOON_RE.finditer(text):
        num = int(m.group("num"))
        if num in table_issues:
            continue
        line_num = text[: m.start()].count("\n") + 1
        claims.append(DocClaim(path, line_num, num, "coming_soon", "Coming Soon", lines[line_num - 1]))

    return claims


# ── Mismatch logic ─────────────────────────────────────────────────────────────

@dataclass
class Mismatch:
    claim: DocClaim
    actual: IssueState
    note: str


def check(claim: DocClaim, actual: IssueState) -> Mismatch | None:
    c = claim.claimed

    if actual.is_pr:
        # PRs: if the doc says ✅ Done or pending, and the PR is merged — that's fine.
        # We don't flag PRs further.
        return None

    if c == "done":
        if actual.state == "open":
            return Mismatch(claim, actual, "claimed Done but issue is still OPEN")
    elif c == "pending":
        if actual.state == "closed":
            reason = actual.state_reason
            if reason == "not_planned":
                return Mismatch(claim, actual, "claimed Pending but issue is CLOSED (not_planned / won't fix)")
            else:
                return Mismatch(claim, actual, f"claimed Pending but issue is CLOSED ({reason or 'completed'})")
    elif c == "closed":
        if actual.state == "open":
            return Mismatch(claim, actual, "claimed Closed but issue is still OPEN")
    elif c == "coming_soon":
        if actual.state == "closed":
            return Mismatch(claim, actual, "listed in Coming Soon but issue is CLOSED")

    return None


# ── Fix helpers ────────────────────────────────────────────────────────────────

def suggested_status(actual: IssueState) -> str:
    """Return a replacement status cell text."""
    if actual.state == "closed":
        if actual.state_reason in ("completed", ""):
            return "✅ Done"
        else:
            return "🔴 Closed"
    return "⬜ Pending"


def apply_fixes(mismatches: list[Mismatch]) -> None:
    """Best-effort in-place fix of table rows. Does not touch Coming Soon bullets."""
    by_file: dict[Path, list[Mismatch]] = {}
    for mm in mismatches:
        if mm.claim.claimed == "coming_soon":
            continue  # skip — Coming Soon needs manual judgement
        by_file.setdefault(mm.claim.file, []).append(mm)

    for path, mms in by_file.items():
        text = path.read_text()
        for mm in mms:
            old_status = mm.claim.raw_status
            new_status = suggested_status(mm.actual)
            # Only replace the status cell on the exact issue's row
            issue_anchor = f"[#{mm.claim.issue}]"
            old_line = mm.claim.context
            if old_status in old_line:
                new_line = old_line.replace(old_status, new_status, 1)
                text = text.replace(old_line, new_line, 1)
                print(f"  FIXED  #{mm.claim.issue}  {old_status!r} → {new_status!r}  ({path.name}:{mm.claim.line})")
        path.write_text(text)


# ── Main ───────────────────────────────────────────────────────────────────────

def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--fix", action="store_true", help="Apply corrections in-place")
    parser.add_argument("--files", nargs="*", help="Override which files to scan")
    args = parser.parse_args()

    files = [Path(f) for f in args.files] if args.files else DOC_FILES

    all_claims: list[DocClaim] = []
    for f in files:
        if f.exists():
            all_claims.extend(parse_claims(f))
        else:
            print(f"WARNING: {f} not found, skipping", file=sys.stderr)

    # Deduplicate: only need one fetch per issue number
    issue_nums = sorted({c.issue for c in all_claims})
    print(f"Fetching state for {len(issue_nums)} referenced issues/PRs…")

    # GraphQL has a node limit; batch in chunks of 100
    states: dict[int, IssueState] = {}
    for i in range(0, len(issue_nums), 100):
        chunk = issue_nums[i : i + 100]
        states.update(fetch_issues(chunk))

    missing = [n for n in issue_nums if n not in states]
    if missing:
        print(f"WARNING: no GitHub data for #{', #'.join(map(str, missing))} (may be PRs or deleted)")

    mismatches: list[Mismatch] = []
    for claim in all_claims:
        actual = states.get(claim.issue)
        if actual is None:
            continue
        mm = check(claim, actual)
        if mm:
            mismatches.append(mm)

    if not mismatches:
        print("✅  All doc issue statuses match GitHub.")
        sys.exit(0)

    print(f"\n{'─'*72}")
    print(f"  {len(mismatches)} MISMATCH(ES) FOUND")
    print(f"{'─'*72}\n")
    for mm in mismatches:
        rel = mm.claim.file.relative_to(REPO_ROOT)
        print(f"  #{mm.claim.issue:5}  {mm.note}")
        print(f"         File : {rel}:{mm.claim.line}")
        print(f"         Doc  : {mm.claim.raw_status!r}")
        print(f"         GH   : {mm.actual.state}  ({mm.actual.state_reason or '—'})  \"{mm.actual.title}\"")
        print(f"         Fix  : {suggested_status(mm.actual)!r}")
        print()

    if args.fix:
        print("Applying fixes…")
        apply_fixes(mismatches)
        print("\nDone. Review with: git diff docs/ROADMAP.md README.md docs/SPECIFICATION.md")
    else:
        print("Run with --fix to apply corrections automatically.")

    sys.exit(1)


if __name__ == "__main__":
    main()
