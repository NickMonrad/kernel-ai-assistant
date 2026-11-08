#!/usr/bin/env python3
"""Documentation drift checker for PRs.

Warns when a PR changes behaviour-sensitive code but does not update relevant docs
and does not provide a ``docs-not-needed`` rationale.

This is a **warning signal**, not a hard merge blocker. Always exits 0.

Usage::

    python3 scripts/check_docs_drift.py \\
        --base-ref origin/main \\
        --head-ref HEAD \\
        [--pr-body "PR body text"] \\
        [--pr-body-file /tmp/pr-body.txt]

"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path
from typing import Dict, List, Tuple

# ---------------------------------------------------------------------------
# Area definitions — trigger paths and expected docs
# ---------------------------------------------------------------------------

Area = Dict[str, object]

AREAS: List[Area] = [
    {
        "name": "User-facing / UX",
        "triggers": [
            "app/src/**",
            "feature/**",
            "core/ui/**",
            "navigation/**",
            "settings/**",
            "wallpaper/**",
            "theme/**",
        ],
        "docs": [
            "docs/ROADMAP.md",
            "docs/SPECIFICATION.md",
            "docs/UX_PATTERNS.md",
        ],
    },
    {
        "name": "Permissions",
        "triggers": [
            "core/permissions/**",
            "**/AndroidManifest.xml",
            "**/permission/**",
            "**/permissions/**",
            "**/AndroidManifest*.xml",
        ],
        "docs": [
            "docs/SPECIFICATION.md",
            "docs/UX_PATTERNS.md",
            ".docs/agents/review-gates-permissions.md",
        ],
    },
    {
        "name": "Voice / STT / TTS / wake-word",
        "triggers": [
            "core/voice/**",
        ],
        "docs": [
            "docs/SPECIFICATION.md",
            ".docs/agents/review-gates-voice.md",
        ],
    },
    {
        "name": "LiteRT / model / model availability",
        "triggers": [
            "core/inference/**",
            "core/model-availability/**",
        ],
        "docs": [
            "docs/SPECIFICATION.md",
            ".docs/agents/review-gates-litert.md",
            "docs/model-availability-ux-patterns.md",
        ],
    },
    {
        "name": "Test harness / evidence process",
        "triggers": [
            "scripts/adb_*",
            "scripts/*evidence*",
            "scripts/tests/**",
            ".github/workflows/*test*",
            ".github/workflows/*evidence*",
            "docs/testing/**",
        ],
        "docs": [
            "docs/automated-testing.md",
            ".docs/agents/test-evidence-workflow.md",
            ".docs/agents/evidence-manifest.md",
            ".docs/agents/review-gates-test-harness.md",
            "docs/testing/",
        ],
    },
    {
        "name": "Architecture / spec-relevant code",
        "triggers": [
            "build.gradle.kts",
            "settings.gradle.kts",
            "gradle/**",
            "core/wasm/**",
            "core/skills/**",
            "core/memory/**",
            "core/di/**",
            "database/**",
            "schema/**",
        ],
        "docs": [
            "docs/SPECIFICATION.md",
            ".omp/AGENTS.md",
            ".docs/agents/skill-reference.md",
        ],
    },
    {
        "name": "ROADMAP-relevant feature status",
        "triggers": [
            "docs/ROADMAP.md",
        ],
        "docs": [
            "docs/ROADMAP.md",
        ],
    },
]


def _match_parts(pattern: List[str], target: List[str], pi: int = 0, ti: int = 0) -> bool:
    """Recursive glob-part matcher with ``**`` support."""
    if pi == len(pattern) and ti == len(target):
        return True
    if pi >= len(pattern):
        return False
    if pattern[pi] == "**":
        # ** at end matches everything remaining
        if pi == len(pattern) - 1:
            return True
        # ** can match zero or more segments
        for skip in range(len(target) - ti + 1):
            if _match_parts(pattern, target, pi + 1, ti + skip):
                return True
        return False
    if ti >= len(target):
        return False
    if _part_match(pattern[pi], target[ti]):
        return _match_parts(pattern, target, pi + 1, ti + 1)
    return False


def _part_match(pattern: str, target: str) -> bool:
    """Match a single path part against a glob fragment (``*`` wildcards only)."""
    regex = "^" + re.escape(pattern).replace("\\*", "[^/]*") + "$"
    return bool(re.match(regex, target))


def _glob_match(pattern: str, path: str) -> bool:
    """Simple glob matching — supports ``**``, ``*``, and single-directory patterns."""
    parts = pattern.split("/")
    path_parts = path.split("/")
    return _match_parts(parts, path_parts)

def relevant_docs_exist(changed_files: List[str], affected_areas: List[Area]) -> bool:
    """Check whether at least one expected doc across all affected areas was changed.

    Returns ``True`` if any doc that belongs to any affected area was modified.
    If no doc at all was updated, the warning should fire.
    """
    for area in affected_areas:
        area_docs: List[str] = area["docs"]
        area_docs_list = area_docs if isinstance(area_docs, list) else [area_docs]
        for doc_pattern in area_docs_list:
            if doc_pattern.endswith("/"):
                # Directory prefix check
                if any(f.startswith(doc_pattern.rstrip("/")) for f in changed_files):
                    return True
            elif doc_pattern in changed_files:
                return True
            elif _glob_match(doc_pattern, doc_pattern):
                if any(_glob_match(doc_pattern, f) for f in changed_files):
                    return True
    return False


def classify_changed_files(changed_files: List[str]) -> List[Area]:
    """Classify a list of changed file paths into affected areas.

    Returns a deduplicated list of area dicts that had at least one matching trigger path.
    """
    matched: List[Area] = []
    seen_names: set = set()

    for area in AREAS:
        name: str = area["name"]
        triggers: List[str] = area["triggers"]
        triggers_list = triggers if isinstance(triggers, list) else [triggers]

        for trigger in triggers_list:
            for f in changed_files:
                if _glob_match(trigger, f):
                    if name not in seen_names:
                        seen_names.add(name)
                        matched.append(area)
                    break
            if name in seen_names:
                break

    return matched

PR_RATIONALE_PATTERNS = [
    re.compile(r"docs[-\s_]not[-\s_]needed\s*:[ \t]*\S", re.IGNORECASE),
    re.compile(r"docs not needed\s*:[ \t]*\S", re.IGNORECASE),
    re.compile(r"documentation not needed\s*:[ \t]*\S", re.IGNORECASE),
]


def has_rationale_in_pr_body(pr_body: str | None) -> bool:
    """Check whether the PR body contains a docs-not-needed rationale.

    Iterates over individual lines to ensure rationale is on the same line
    as the label — a blank ``Docs not needed:`` field does not count.
    """
    if not pr_body:
        return False
    for line in pr_body.splitlines():
        for pattern in PR_RATIONALE_PATTERNS:
            if pattern.search(line):
                return True
    return False


def get_changed_files(base_ref: str, head_ref: str) -> List[str]:
    """Get list of changed file paths between two git refs."""
    try:
        result = subprocess.run(
            ["git", "diff", "--name-only", base_ref, head_ref],
            capture_output=True,
            text=True,
            check=True,
            cwd=Path(__file__).resolve().parent.parent,
        )
        return [f.strip() for f in result.stdout.splitlines() if f.strip()]
    except subprocess.CalledProcessError as e:
        print(f"::warning::Failed to list changed files: {e}", file=sys.stderr)
        return []


def build_warning(
    affected_areas: List[Area],
) -> str:
    """Build a structured warning message."""
    lines = [
        "## ⚠️ Documentation Drift Warning",
        "",
        "This PR changes behaviour-sensitive files but no related docs update or",
        "docs-not-needed rationale was found.",
        "",
        "### Detected areas",
        "",
    ]
    for area in affected_areas:
        lines.append(f"- **{area['name']}**")

    lines.extend(
        [
            "",
            "### Consider updating",
            "",
        ]
    )

    all_docs: List[str] = []
    seen_docs: set = set()
    for area in affected_areas:
        for doc_ref in area["docs"]:
            if doc_ref not in seen_docs:
                seen_docs.add(doc_ref)
                all_docs.append(doc_ref)

    for doc_ref in all_docs:
        lines.append(f"- `{doc_ref}`")

    lines.extend(
        [
            "",
            "### How to dismiss",
            "",
            "1. Update the relevant docs listed above, or",
            "2. Add a short rationale in the PR body (under the `## Documentation` section):",
            "",
            "   ```markdown",
            "   ## Documentation",
            "   Docs not needed: <brief explanation>",
            "   ```",
            "",
            "*This is a warning signal, not a hard merge blocker.*",
        ]
    )

    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Check for documentation drift on behaviour-sensitive changes."
    )
    parser.add_argument(
        "--base-ref",
        required=True,
        help="Base git ref (e.g., origin/main)",
    )
    parser.add_argument(
        "--head-ref",
        required=True,
        help="Head git ref (e.g., HEAD)",
    )
    parser.add_argument(
        "--pr-body",
        default=None,
        help="PR body text (to check for docs-not-needed rationale)",
    )
    parser.add_argument(
        "--pr-body-file",
        default=None,
        type=Path,
        help="File containing PR body text",
    )
    parser.add_argument(
        "--changed-files",
        nargs="*",
        default=None,
        help="Override changed files list (for testing)",
    )

    args = parser.parse_args()

    # Resolve PR body
    pr_body: str | None = args.pr_body
    if args.pr_body_file and pr_body is None:
        try:
            pr_body = args.pr_body_file.read_text(encoding="utf-8")
        except OSError as e:
            print(f"::warning::Could not read PR body file: {e}", file=sys.stderr)
            pr_body = None

    # Get changed files
    if args.changed_files is not None:
        changed_files = args.changed_files
    else:
        changed_files = get_changed_files(args.base_ref, args.head_ref)

    if not changed_files:
        print("No changed files detected — skipping docs drift check.")
        sys.exit(0)

    # Classify
    affected_areas = classify_changed_files(changed_files)

    if not affected_areas:
        print("No behaviour-sensitive files detected — docs drift check skipped.")
        sys.exit(0)

    # Check for docs updates
    docs_updated = relevant_docs_exist(changed_files, affected_areas)

    # Check for rationale
    has_rationale = has_rationale_in_pr_body(pr_body)

    if docs_updated or has_rationale:
        if docs_updated:
            print("Relevant documentation updated — docs drift check passed.")
        if has_rationale:
            print(f"PR body includes docs-not-needed rationale — docs drift check passed.")
        sys.exit(0)

    # Emit warning
    warning = build_warning(affected_areas)
    print(warning)
    # Write to GITHUB_STEP_SUMMARY if available
    import os
    step_summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if step_summary:
        try:
            with open(step_summary, "a", encoding="utf-8") as f:
                f.write(warning + "\n")
        except OSError:
            pass
    # Print GitHub Actions notice
    print("::notice title=Documentation Drift Warning::Behaviour-sensitive files changed without docs update or rationale.")

    sys.exit(0)


if __name__ == "__main__":
    main()
