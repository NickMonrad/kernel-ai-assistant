#!/usr/bin/env python3
"""
ADB Skill Regression Harness — CLI entry point.

Delegates to the ``adb_harness`` package modules for all logic.
"""

from __future__ import annotations

import argparse
import os
import sys

from adb_harness.config import (
    ADB,
    ACTIVITY,
    PACKAGE,
    WAIT_SECONDS,
    PROFILE_WAIT_SECONDS,
    REPORTS_DIR,
    LLM_TOOLS_ROUTE_PATTERN,
    LLM_TOOLS_NATIVE_TOOL_PATTERN,
    LLM_TOOLS_LEGACY_TOOL_PATTERN,
    LLM_TOOLS_SKILL_RESULT_PATTERN,
    LLM_TOOLS_MESSAGE_SAVED_PATTERN,
    LLM_TOOLS_RETRY_PATTERN,
    LLM_TOOLS_SLOT_FILL_PATTERN,
)
from adb_harness.selectors import _parse_arg_list
from adb_harness.runners import run_tests, run_llm_tools, run_profile_tests


def main() -> None:
    parser = argparse.ArgumentParser(
        description="ADB Skill Regression Test — end-to-end intent routing verification.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=f"""
Examples:
  # Full run (all phases, all devices)
  python3 %(prog)s

  # Single phase
  python3 %(prog)s --phases alarm_timer

  # Composable selectors
  python3 %(prog)s --tags deterministic_core --exclude-tags destructive
  python3 %(prog)s --phases slot_fill --categories slot_fill
  python3 %(prog)s --case slot_fill_timer_invalid_reply

  # Dry-run with selectors
  python3 %(prog)s --dry-run --tags safe_smoke

  # Profile test
  python3 %(prog)s --profile

Device: {PACKAGE}@{ADB}
Timeout env vars: ADB_WAIT_SECONDS={WAIT_SECONDS} ADB_PROFILE_WAIT_SECONDS={PROFILE_WAIT_SECONDS}
Reports dir: {REPORTS_DIR}
""",
    )

    parser.add_argument("--dry-run", action="store_true", help="Show selected tests without running them")
    parser.add_argument("--profile", action="store_true", help="Run profile extraction tests")
    parser.add_argument("--post-pr", action="store_true", help="Post results as PR comment")
    parser.add_argument(
        "--phases",
        metavar="PHASES",
        default=None,
        help="Comma-separated phase names or 1-based numbers (e.g. alarm_timer,3,weather)",
    )
    parser.add_argument(
        "--start-phase",
        metavar="PHASE",
        default=None,
        help="Resume from a named/numbered phase (skips earlier phases)",
    )
    parser.add_argument(
        "--categories",
        metavar="CATS",
        default=None,
        help="Filter by comma-separated categories (deterministic, slot_fill, recovery, ...)",
    )
    parser.add_argument(
        "--tags",
        metavar="TAGS",
        default=None,
        help="Include tests matching any of these tags (comma-separated)",
    )
    parser.add_argument(
        "--exclude-tags",
        metavar="TAGS",
        default=None,
        help="Exclude tests matching any of these tags (comma-separated)",
    )
    parser.add_argument(
        "--case",
        metavar="IDS",
        default=None,
        help="Run only specific test case IDs (comma-separated)",
    )
    parser.add_argument(
        "--model-readiness",
        action="store_true",
        help="Run model readiness preflight before tests (handles download, HF sign-in, engine init)",
    )
    parser.add_argument(
        "--serial",
        metavar="SERIAL",
        default=None,
        help="Device serial (overrides ANDROID_SERIAL env var)",
    )
    parser.add_argument(
        "--unlock-pin",
        metavar="PIN",
        default=None,
        help="Device unlock PIN for model readiness preflight",
    )
    parser.add_argument(
        "--timeout-download",
        type=float,
        default=None,
        help="Max seconds to wait for model download (model readiness preflight)",
    )
    parser.add_argument(
        "--timeout-engine",
        type=float,
        default=None,
        help="Max seconds to wait for engine init after download (model readiness preflight)",
    )
    args = parser.parse_args()

    # Set ANDROID_SERIAL early so all ADB calls use the selected device
    if args.serial:
        os.environ["ANDROID_SERIAL"] = args.serial

    if args.start_phase and args.phases:
        parser.error("--start-phase and --phases are mutually exclusive. Use one or the other.")

    phases_list = [p.strip() for p in args.phases.split(",")] if args.phases else None
    categories_list = _parse_arg_list(args.categories)
    tags_list = _parse_arg_list(args.tags)
    exclude_tags_list = _parse_arg_list(args.exclude_tags)
    case_ids_list = _parse_arg_list(args.case)

    if args.profile:
        sys.exit(run_profile_tests(dry_run=args.dry_run))
    elif phases_list == ["llm_tools"]:
        sys.exit(run_llm_tools(dry_run=args.dry_run, case_ids=case_ids_list))
    else:
        sys.exit(run_tests(
            dry_run=args.dry_run,
            post_pr=args.post_pr,
            start_phase=args.start_phase,
            phases=phases_list,
            categories=categories_list,
            tags=tags_list,
            exclude_tags=exclude_tags_list,
            case_ids=case_ids_list,
            model_readiness=args.model_readiness,
            serial=args.serial,
            unlock_pin=args.unlock_pin,
            timeout_download=args.timeout_download,
            timeout_engine=args.timeout_engine,
        ))


if __name__ == "__main__":
    main()
