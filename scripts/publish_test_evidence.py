#!/usr/bin/env python3
"""Publish normalised test evidence to a dedicated branch.

Publishes already-normalised evidence outputs (JSON, CSV, Markdown) to a
dedicated ``test-results`` branch without polluting ``main``.

Supports:
- CI evidence directories (from :issue:`1130`)
- On-device evidence files (from :issue:`1116` normaliser)
- PR-scoped and release-scoped results
- Dry-run mode

Validation:
- JSON input validated against ``test_evidence.schema.json``
- Evidence ``source`` must match ``--source``
- Evidence ``commit`` must match ``--commit`` (unless ``--allow-commit-mismatch``)
- Output paths are constrained under ``results/{pr,release}/``
"""

from __future__ import annotations

import argparse
import os
import json
import re
import shutil
import subprocess
import sys
import tempfile
import textwrap
from pathlib import Path

# ── Constants ──────────────────────────────────────────────────────────────────

HERE = Path(__file__).resolve().parent
REPO = "NickMonrad/kernel-ai-assistant"
# Auto-derive the remote URL from the current checkout, falling back to HTTPS.
# CI uses the HTTPS form with GITHUB_TOKEN auth; local checkouts may use SSH.
_DEFAULT_REMOTE_URL: str | None = None

VALID_SOURCES = frozenset({"ci", "on_device"})

# Default target branch name
DEFAULT_TARGET_BRANCH = "test-results"

# Only publish these file extensions
ALLOWED_EXTENSIONS = frozenset({".json", ".csv", ".md"})

BLOCKED_EXTENSIONS = frozenset({
    ".log", ".txt~", ".bak", ".tmp", ".dump", ".hprof",
})


def _validate_path_segment(value: str, label: str) -> None:
    """Validate a CLI value used as a path segment.

    Rejects empty strings, path separators, traversal components,
    null bytes, and characters that are unsafe in filesystem paths.
    """
    if not value:
        print(f"ERROR: {label} must not be empty", file=sys.stderr)
        sys.exit(1)
    if "/" in value or "\\" in value:
        print(f"ERROR: {label} must not contain path separators: {value!r}", file=sys.stderr)
        sys.exit(1)
    if "\x00" in value:
        print(f"ERROR: {label} must not contain null bytes", file=sys.stderr)
        sys.exit(1)
    if value in (".", ".."):
        print(f"ERROR: {label} must not be '.' or '..': {value!r}", file=sys.stderr)
        sys.exit(1)
    if not re.match(r"^[a-zA-Z0-9][a-zA-Z0-9._-]*$", value):
        print(
            f"ERROR: {label} contains invalid characters: {value!r}. "
            f"Allowed: alphanumeric, dots, hyphens, underscores (must start with alphanumeric).",
            file=sys.stderr,
        )
        sys.exit(1)


# ── Remote URL resolution ──────────────────────────────────────────────────────


def _get_remote_url() -> str:
    """Return the origin remote URL from the current checkout."""
    global _DEFAULT_REMOTE_URL
    if _DEFAULT_REMOTE_URL is not None:
        return _DEFAULT_REMOTE_URL
    try:
        result = subprocess.run(
            ["git", "remote", "get-url", "origin"],
            capture_output=True, text=True, check=True,
            cwd=HERE,
        )
        _DEFAULT_REMOTE_URL = result.stdout.strip()
    except (subprocess.CalledProcessError, FileNotFoundError):
        _DEFAULT_REMOTE_URL = f"https://github.com/{REPO}.git"
    return _DEFAULT_REMOTE_URL

def _detect_current_pr_number() -> int | None:
    """Detect the current GitHub PR number for the active branch.

    Uses ``gh pr view`` on the repository checkout. Returns None when:
    - ``gh`` is not installed
    - The current branch has no open PR
    - The command times out or fails for any other reason
    """
    try:
        result = subprocess.run(
            ["gh", "pr", "view", "--json", "number", "--jq", ".number"],
            capture_output=True, text=True, timeout=5,
            cwd=HERE,
        )
        if result.returncode == 0:
            text = result.stdout.strip()
            if text:
                return int(text)
    except (ValueError, subprocess.TimeoutExpired, FileNotFoundError):
        pass


def _check_pr_mismatches(
    cli_pr: int | None,
    evidence_prs: set[int | None],
    detected_pr: int | None,
) -> list[str]:
    """Check for PR number mismatches.

    Pure function with no side effects — testable directly. Returns a list of
    human-readable mismatch descriptions. An empty list means no problems.

    * Always compares CLI ``--pr`` against each evidence JSON ``pr`` field.
    * When ``detected_pr`` is provided (from ``gh pr view``), also checks
      CLI ``--pr`` against the live PR as an additional safety measure.
    * ``None`` evidence PRs (unset or null ``pr`` field) are treated as a
      mismatch when ``cli_pr`` is set — evidence without a PR number should
      not be published under a PR scope.
    * ``cli_pr is None`` means release-scoped — no PR validation needed.
    """
    if cli_pr is None:
        return []

    mismatches: list[str] = []

    # 1. Direct comparison: CLI --pr vs each evidence JSON pr field
    #    Including None — evidence without a pr field cannot go under PR scope
    if None in evidence_prs:
        mismatches.append(
            f"Evidence JSON pr field is null/missing but CLI --pr={cli_pr}. "
            f"All evidence files must have a 'pr' field matching the PR being published."
        )
    for evidence_pr in sorted(p for p in evidence_prs if p is not None):
        if evidence_pr != cli_pr:
            mismatches.append(
                f"Evidence JSON pr={evidence_pr} does not match CLI --pr={cli_pr}. "
                f"The evidence 'pr' field must match the PR number being published."
            )

    # 2. Additional local safety: detected PR from gh (when available)
    if detected_pr is not None and cli_pr != detected_pr:
        mismatches.append(
            f"CLI --pr={cli_pr} does not match current GitHub PR #{detected_pr}. "
            f"The --pr argument must be the actual Pull Request number, "
            f"not a related issue number from Closes #N."
        )

    return mismatches



def _validate_pr_number(cli_pr: int | None, evidence_prs: set[int | None], allow_mismatch: bool) -> None:
    """Validate PR number consistency.

    Checks that CLI ``--pr`` matches the evidence JSON ``pr`` field(s) from
    **all** files — this guardrail always runs, even when ``gh pr view`` is
    unavailable (e.g. GitHub Actions merge checkout).

    When ``gh`` is available *and* the current branch has an open PR, also
    checks against the detected PR number as an additional safety measure.

    Exits with a clear error on mismatch unless ``allow_mismatch`` is set.
    """
    if cli_pr is None:
        return

    detected = _detect_current_pr_number()
    mismatches = _check_pr_mismatches(cli_pr, evidence_prs, detected)

    if not mismatches:
        return

    if allow_mismatch:
        for msg in mismatches:
            print(f"WARNING: PR number mismatch (allowed by --allow-pr-mismatch): {msg}")
        return

    print("ERROR: PR number mismatch detected:", file=sys.stderr)
    for msg in mismatches:
        print(f"  {msg}", file=sys.stderr)
    print(
        "Use '--allow-pr-mismatch' only for recovery cases "
        "(e.g. re-publishing evidence after a PR is closed).",
        file=sys.stderr,
    )
    sys.exit(1)


# ── Schema validation (lightweight, no external deps) ──────────────────────────


def _load_schema(schema_path: Path) -> dict:
    """Load JSON Schema from file."""
    with schema_path.open("r") as f:
        return json.load(f)


def _validate_evidence(data: dict, schema: dict) -> list[str]:
    """Validate a normalised evidence dict against the JSON Schema document.

    Returns a list of error messages (empty = valid).
    """
    errors: list[str] = []

    # Required top-level fields
    required = schema.get("required", [])
    for field in required:
        if field not in data:
            errors.append(f"missing required field '{field}'")

    # Property-level validation (covers type, enum, pattern, oneOf, etc.)
    properties = schema.get("properties", {})
    for prop_name, prop_schema in properties.items():
        if prop_name not in data:
            continue
        value = data[prop_name]
        _check_prop(f"schema.{prop_name}", value, prop_schema, errors)

    # allOf / conditional constraints (if/then)
    for cond_block in schema.get("allOf", []):
        if_block = cond_block.get("if", {})
        then_block = cond_block.get("then", {})
        if not if_block or not then_block:
            continue
        if _evaluate_if(data, if_block):
            then_props = then_block.get("properties", {})
            for prop_name, prop_schema in then_props.items():
                if prop_name in data:
                    _check_prop(f"schema.{prop_name}", data[prop_name], prop_schema, errors)

    # additionalProperties: false at root
    if not schema.get("additionalProperties", True):
        known = set(properties.keys())
        for key in data:
            if key not in known:
                errors.append(f"unexpected top-level field '{key}'")

    return errors


def _check_prop(path: str, value, prop_schema: dict, errors: list[str]) -> None:
    """Validate a single property value against its schema fragment."""
    prop_type = prop_schema.get("type")

    # Object type — recurse
    if prop_type == "object":
        if not isinstance(value, dict):
            errors.append(f"{path}: expected object, got {type(value).__name__}")
            return
        _check_object(path, value, prop_schema, errors)
        return

    # Array type — check items
    if prop_type == "array":
        if not isinstance(value, list):
            errors.append(f"{path}: expected array, got {type(value).__name__}")
            return
        items_schema = prop_schema.get("items", {})
        for i, item in enumerate(value):
            _check_prop(f"{path}[{i}]", item, items_schema, errors)
        return

    # oneOf (type union, e.g. integer | null, string | null)
    one_of = prop_schema.get("oneOf")
    if one_of is not None:
        for variant in one_of:
            local_errors: list[str] = []
            _check_prop(path, value, variant, local_errors)
            if not local_errors:
                break
        else:
            details = [v.get("type", "?") for v in one_of]
            errors.append(f"{path}: value does not match any oneOf variant {details}")
        return

    # Type check
    if prop_type is not None:
        python_type = {
            "string": str,
            "integer": int,
            "number": (int, float),
            "boolean": bool,
            "null": type(None),
        }.get(prop_type)
        if python_type is not None and not isinstance(value, python_type):
            errors.append(f"{path}: expected {prop_type}, got {type(value).__name__}")

    # Enum
    enum_vals = prop_schema.get("enum")
    if enum_vals is not None and value not in enum_vals:
        errors.append(f"{path}: '{value}' not in enum {enum_vals}")

    # Pattern (string)
    pattern = prop_schema.get("pattern")
    if pattern is not None and isinstance(value, str):
        if not re.match(pattern, value):
            errors.append(f"{path}: value '{value}' does not match pattern {pattern}")

    # Minimum / Maximum
    if isinstance(value, (int, float)):
        min_val = prop_schema.get("minimum")
        max_val = prop_schema.get("maximum")
        if min_val is not None and value < min_val:
            errors.append(f"{path}: {value} < minimum {min_val}")
        if max_val is not None and value > max_val:
            errors.append(f"{path}: {value} > maximum {max_val}")


def _check_object(path: str, obj: dict, prop_schema: dict, errors: list[str]) -> None:
    """Validate an object-typed property recursively."""
    obj_props = prop_schema.get("properties", {})
    obj_required = prop_schema.get("required", [])

    for field in obj_required:
        if field not in obj:
            errors.append(f"{path}: missing required field '{field}'")

    for field_name, field_schema in obj_props.items():
        if field_name in obj:
            _check_prop(f"{path}.{field_name}", obj[field_name], field_schema, errors)

    if not prop_schema.get("additionalProperties", True):
        known = set(obj_props.keys())
        # Include serial from device object's properties
        device_props = {k for k in obj_props.keys()}
        for key in obj:
            if key not in known and key not in device_props:
                errors.append(f"{path}: unexpected field '{key}'")


def _evaluate_if(data: dict, if_block: dict) -> bool:
    """Evaluate an ``if`` condition block (simple property + const check)."""
    props = if_block.get("properties", {})
    for prop_name, cond in props.items():
        if prop_name not in data:
            return False
        const_val = cond.get("const")
        if const_val is not None and data[prop_name] != const_val:
            return False
        enum_vals = cond.get("enum")
        if enum_vals is not None and data[prop_name] not in enum_vals:
            return False
    return True


# ── CLI ─────────────────────────────────────────────────────────────────────────


def _parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    """Parse and validate CLI arguments."""
    parser = argparse.ArgumentParser(
        description="Publish normalised test evidence to a dedicated branch.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=textwrap.dedent("""\
            Examples:

              # Dry-run: CI evidence from artifact directory
              python3 scripts/publish_test_evidence.py --input-dir /tmp/ci-evidence \\
                  --source ci --pr 1131 --commit "$(git rev-parse HEAD)" --dry-run

              # Publish on-device evidence
              python3 scripts/publish_test_evidence.py --input ./result.json \\
                  --source on_device --pr 1131 --commit "$(git rev-parse HEAD)"

              # Release-scoped, on-device
              python3 scripts/publish_test_evidence.py --input ./result.json \\
                  --source on_device --release v0.8.0 --commit "$(git rev-parse HEAD)"
        """),
    )

    source_group = parser.add_mutually_exclusive_group(required=True)
    source_group.add_argument(
        "--input-dir",
        type=str,
        help="Directory containing normalised evidence files (JSON, CSV, MD)",
    )
    source_group.add_argument(
        "--input",
        type=str,
        help="Single normalised evidence JSON file",
    )

    parser.add_argument(
        "--source",
        required=True,
        choices=sorted(VALID_SOURCES),
        help="Evidence source type",
    )
    parser.add_argument(
        "--commit",
        required=True,
        help="Full 40-character commit SHA (the commit the evidence was produced from)",
    )
    parser.add_argument(
        "--pr",
        type=int,
        default=None,
        help="PR number (require for PR-scoped results)",
    )
    parser.add_argument(
        "--release",
        type=str,
        default=None,
        help="Release/tag name (for release-scoped results)",
    )
    parser.add_argument(
        "--target-branch",
        type=str,
        default=DEFAULT_TARGET_BRANCH,
        help=f"Target branch name (default: {DEFAULT_TARGET_BRANCH})",
    )
    parser.add_argument(
        "--repo-url",
        type=str,
        default=None,
        help="Remote URL (auto-detected from origin by default)",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Print what would be done without making changes",
    )
    parser.add_argument(
        "--allow-commit-mismatch",
        action="store_true",
        help="Skip commit SHA consistency check",
    )
    parser.add_argument(
        "--allow-pr-mismatch",
        action="store_true",
        help=(
            "Skip PR number consistency check with current GitHub PR. "
            "Only use this for recovery cases (e.g. re-publishing evidence "
            "after a PR is closed)."
        ),
    )

    args = parser.parse_args(argv)

    # Validate: at least one of --pr or --release
    if args.pr is None and args.release is None:
        parser.error("at least one of --pr or --release is required")
    if args.pr is not None and args.release is not None:
        parser.error("--pr and --release are mutually exclusive; provide only one")
    if args.target_branch.lower() in ("main", "master"):
        parser.error(
            f"target branch '{args.target_branch}' is a default branch; "
            f"use a dedicated branch like '{DEFAULT_TARGET_BRANCH}'"
        )

    # Validate release path segment safety
    if args.release is not None:
        _validate_path_segment(args.release, "--release")
    # Target branch traversal safety (allow / for nesting, block dangerous patterns)
    if "../" in args.target_branch or args.target_branch.startswith("/"):
        parser.error(
            f"target branch '{args.target_branch}' contains path traversal; "
            f"use a plain branch name like '{DEFAULT_TARGET_BRANCH}'"
        )
    if "\x00" in args.target_branch:
        parser.error("target branch must not contain null bytes")

    return args


# ── Input validation ───────────────────────────────────────────────────────────


def _collect_input_files(args: argparse.Namespace) -> list[Path]:
    """Resolve and validate the input path(s). Returns list of files to publish."""
    if args.input_dir:
        in_dir = Path(args.input_dir)
        if not in_dir.is_dir():
            print(f"ERROR: input directory not found: {in_dir}", file=sys.stderr)
            sys.exit(1)
        all_entries = sorted(in_dir.rglob("*"))
        if not all_entries:
            print(f"ERROR: input directory is empty: {in_dir}", file=sys.stderr)
            sys.exit(1)
        evidence_file = in_dir / "evidence.json"
        if not evidence_file.is_file() or evidence_file.is_symlink():
            print(f"ERROR: input directory must contain a regular evidence.json: {in_dir}", file=sys.stderr)
            sys.exit(1)
        allowed: list[Path] = []
        for entry in all_entries:
            if entry.is_symlink():
                print(f"ERROR: symlinks are not allowed in input directories: {entry}", file=sys.stderr)
                sys.exit(1)
            if not entry.is_file():
                continue
            if entry.suffix in BLOCKED_EXTENSIONS:
                print(
                    f"ERROR: blocked extension ({entry.suffix}): {entry.relative_to(in_dir)}",
                    file=sys.stderr,
                )
                sys.exit(1)
            if entry.suffix not in ALLOWED_EXTENSIONS:
                print(
                    f"ERROR: unsupported extension ({entry.suffix}): {entry.relative_to(in_dir)}",
                    file=sys.stderr,
                )
                sys.exit(1)
            allowed.append(entry)
        if not allowed:
            print(f"ERROR: no publishable files found in {in_dir}", file=sys.stderr)
            sys.exit(1)
        return allowed

    in_path = Path(args.input)
    if not in_path.is_file() or in_path.is_symlink():
        print(f"ERROR: input file not found or unsafe: {in_path}", file=sys.stderr)
        sys.exit(1)
    if in_path.suffix not in ALLOWED_EXTENSIONS:
        print(f"ERROR: input file must be .json, .csv, or .md: {in_path}", file=sys.stderr)
        sys.exit(1)
    return [in_path]


def _validate_evidence_file(path: Path, args: argparse.Namespace) -> dict:
    """Load and validate a normalised JSON evidence file.

    Validates: JSON parse, schema, source match, commit match.
    Returns the parsed dict.
    """
    if path.suffix != ".json":
        return {}  # Non-JSON files skip schema validation

    try:
        with path.open("r") as f:
            data = json.load(f)
    except (json.JSONDecodeError, OSError) as e:
        print(f"ERROR: invalid JSON in {path}: {e}", file=sys.stderr)
        sys.exit(1)

    if not isinstance(data, dict):
        print(f"ERROR: {path}: expected JSON object, got {type(data).__name__}", file=sys.stderr)
        sys.exit(1)

    # Schema validation
    schema_path = HERE / "testdata" / "test_evidence.schema.json"
    if schema_path.is_file():
        schema = _load_schema(schema_path)
        errors = _validate_evidence(data, schema)
        if errors:
            print(f"ERROR: {path} failed schema validation:", file=sys.stderr)
            for e in errors:
                print(f"  [SCHEMA ERROR] {e}", file=sys.stderr)
            sys.exit(1)
    else:
        print(f"WARNING: schema not found at {schema_path}, skipping schema validation")

    # Source consistency check
    if "source" in data and data["source"] != args.source:
        print(
            f"ERROR: evidence source is '{data['source']}' but --source is '{args.source}'",
            file=sys.stderr,
        )
        sys.exit(1)

    # Commit consistency check
    if not args.allow_commit_mismatch:
        evidence_commit = data.get("commit")
        if evidence_commit and evidence_commit != args.commit:
            print(
                f"ERROR: evidence commit '{evidence_commit}' != --commit '{args.commit}'. "
                f"Use --allow-commit-mismatch to override.",
                file=sys.stderr,
            )
            sys.exit(1)

    return data


def _build_output_paths(files: list[Path], args: argparse.Namespace, data: dict | None) -> dict[Path, str]:
    """Build a mapping of local file → output path relative to results root.

    Returns {local_file: dest_path_relative_to_results}.
    """
    # Determine the scope directory
    if args.pr is not None:
        scope = f"pr/{args.pr}"
    elif args.release is not None:
        scope = f"release/{args.release}"
    else:
        # Should not happen due to argparse validation
        print("ERROR: neither --pr nor --release provided", file=sys.stderr)
        sys.exit(1)

    source_dir = args.source
    base = f"results/{scope}/{source_dir}"

    mapping: dict[Path, str] = {}
    for f in files:
        if args.input_dir:
            input_root = Path(args.input_dir)
            try:
                relative = f.relative_to(input_root)
            except ValueError:
                print(f"ERROR: input file escapes input directory: {f}", file=sys.stderr)
                sys.exit(1)
            dest_name = relative.as_posix()
        else:
            # Single input file: derive a descriptive name from evidence metadata
            if data and args.source == "on_device":
                device_id = data.get("device", {}).get("id", "unknown")
                suite = data.get("suite", "unknown")
                dest_name = f"__{device_id}_{suite}{f.suffix}"
            elif data:
                suite = data.get("suite", f.stem)
                dest_name = f"__{suite}{f.suffix}"
            else:
                dest_name = f"__{f.name}"

        dest = f"{base}/{dest_name}"
        dest = os.path.normpath(dest)
        mapping[f] = dest

    return mapping


# ── Git operations ─────────────────────────────────────────────────────────────


def _check_git_available() -> None:
    """Ensure git is available and exit with error if not."""
    try:
        subprocess.run(["git", "--version"], capture_output=True, check=True)
    except (subprocess.CalledProcessError, FileNotFoundError):
        print("ERROR: git is not available", file=sys.stderr)
        sys.exit(1)


def _git(*args: str, cwd: str, check: bool = True) -> subprocess.CompletedProcess:
    """Run a git command, returning the CompletedProcess."""
    cmd = ["git", *args]
    try:
        return subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            check=check,
            cwd=cwd,
        )
    except subprocess.CalledProcessError as e:
        if check:
            print(f"ERROR: git command failed: {' '.join(cmd)}", file=sys.stderr)
            if e.stderr:
                print(e.stderr.strip(), file=sys.stderr)
            sys.exit(1)
        return e


def _publish_to_branch(
    mapping: dict[Path, str],
    target_branch: str,
    commit_msg: str,
    repo_url: str,
    dry_run: bool,
) -> None:
    """Publish the mapped files to the target branch.

    Creates a temporary directory, initialises git, fetches the target branch
    (or creates an orphan), copies files in, commits, and pushes.
    """
    if dry_run:
        print(f"[DRY-RUN] Would publish {len(mapping)} file(s) to branch '{target_branch}':")
        for local, dest in sorted(mapping.items(), key=lambda x: x[1]):
            print(f"  {local} → {dest}")
        print(f"[DRY-RUN] Commit message: {commit_msg}")
        print(f"[DRY-RUN] Would push to: {repo_url}")
        return

    tmpdir = Path(tempfile.mkdtemp(prefix="test-results-publish-"))
    try:
        _git("init", cwd=str(tmpdir))
        _git("config", "user.name", "kernel-ai-assistant CI", cwd=str(tmpdir))
        _git("config", "user.email", "ci@kernel-ai-assistant.dev", cwd=str(tmpdir))
        _git("remote", "add", "origin", repo_url, cwd=str(tmpdir))

        # Fetch target branch if it exists on remote
        fetch_result = _git(
            "fetch", "origin", target_branch,
            cwd=str(tmpdir), check=False,
        )

        if fetch_result.returncode == 0:
            _git("checkout", target_branch, cwd=str(tmpdir))
        else:
            # Create orphan branch (empty root)
            _git("checkout", "--orphan", target_branch, cwd=str(tmpdir))
            # Clean any staged files from orphan init
            _git("rm", "-rf", ".", cwd=str(tmpdir), check=False)

        # Copy files into appropriate directory structure
        for local, dest in mapping.items():
            full_dest = tmpdir / dest
            full_dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(str(local), str(full_dest))
            print(f"  {local.name} → {dest}")

        # Stage and commit
        _git("add", "-A", cwd=str(tmpdir))

        status = _git("status", "--porcelain", cwd=str(tmpdir))
        if not status.stdout.strip():
            print("Nothing new to publish (no changes since last commit)")
            return

        _git("commit", "-m", commit_msg, cwd=str(tmpdir))
        _git("push", "origin", target_branch, cwd=str(tmpdir))
        print(f"✅ Published {len(mapping)} file(s) to branch '{target_branch}'")

    finally:
        shutil.rmtree(str(tmpdir), ignore_errors=True)


# ── Main ───────────────────────────────────────────────────────────────────────


def main() -> None:
    """Entry point."""
    args = _parse_args()

    # Validate source
    if args.source not in VALID_SOURCES:
        print(f"ERROR: invalid source '{args.source}'. Valid: {sorted(VALID_SOURCES)}", file=sys.stderr)
        sys.exit(1)

    # Collect and validate input files
    files = _collect_input_files(args)

    if args.input_dir:
        evidence_files = [Path(args.input_dir) / "evidence.json"]
    else:
        evidence_files = [f for f in files if f.suffix == ".json"]
    data: dict | None = None
    evidence_prs: set[int | None] = set()
    for evidence_file in evidence_files:
        ev_data = _validate_evidence_file(evidence_file, args)
        evidence_prs.add(ev_data.get("pr") if ev_data else None)
        data = ev_data

    # Validate PR number consistency (guard against issue vs PR number confusion)
    _validate_pr_number(
        cli_pr=args.pr,
        evidence_prs=evidence_prs,
        allow_mismatch=args.allow_pr_mismatch,
    )

    # Build output path mapping
    mapping = _build_output_paths(files, args, data)

    # Path safety check: ensure all dest paths are within results/
    for local, dest in mapping.items():
        # Normalise and verify the leading component
        normalized = os.path.normpath(dest)
        if not normalized.startswith("results/") and normalized != "results":
            print(
                f"ERROR: output path '{dest}' escapes results/ tree (normalized: {normalized}). Aborting.",
                file=sys.stderr,
            )
            sys.exit(1)

    # Build commit message
    parts = []
    if args.pr is not None:
        parts.append(f"PR #{args.pr}")
    if args.release is not None:
        parts.append(f"release {args.release}")
    scope_str = " ".join(parts) if parts else "unknown"
    commit_msg = f"test-results: add {args.source} evidence for {scope_str} @ {args.commit[:12]}"

    # Resolve repo URL
    repo_url = args.repo_url if args.repo_url else _get_remote_url()

    # Print summary header
    scope_label = f"PR #{args.pr}" if args.pr else f"release {args.release}"
    print(f"Source:         {args.source}")
    print(f"Scope:          {scope_label}")
    print(f"Target branch:  {args.target_branch}")
    print(f"Files:          {len(files)}")
    print(f"Dry run:        {args.dry_run}")
    print()

    # Check git availability
    _check_git_available()

    # Publish
    _publish_to_branch(
        mapping=mapping,
        target_branch=args.target_branch,
        commit_msg=commit_msg,
        repo_url=repo_url,
        dry_run=args.dry_run,
    )


if __name__ == "__main__":
    main()
