#!/usr/bin/env python3
"""
ADB Skill Regression Harness — end-to-end intent routing verification.

Sends natural-language prompts to Kernel AI via ADB, reads logcat for the
NativeIntentHandler dispatch line, and checks that the routed intent matches
the expected value.

Usage:
    python3 scripts/adb_skill_test.py            # run all tests
    python3 scripts/adb_skill_test.py --dry-run   # print test plan without ADB

Requires: ~/Android/Sdk/platform-tools/adb on PATH or at the configured path.
App must be installed as com.kernel.ai.debug.
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import time
from dataclasses import dataclass

ADB = os.path.expanduser("~/Android/Sdk/platform-tools/adb")
PACKAGE = "com.kernel.ai.debug"
ACTIVITY = f"{PACKAGE}/.MainActivity"
LOGCAT_TAG = "NativeIntentHandler"
INTENT_PATTERN = re.compile(r"NativeIntentHandler\.handle:\s*intent=(\S+)")
WAIT_SECONDS = 5


@dataclass
class TestCase:
    message: str
    expect_intent: str


TEST_CASES: list[TestCase] = [
    # Alarm
    TestCase("set an alarm for 7am", "set_alarm"),
    TestCase("wake me up at 6:30", "set_alarm"),
    TestCase("remind me tomorrow at 9", "set_alarm"),
    # Weather
    TestCase("what's the weather in Auckland", "get_weather"),
    TestCase("will it rain today", "get_weather"),
    TestCase("how hot is it outside", "get_weather"),
    # Lists
    TestCase("add milk to my shopping list", "add_to_list"),
    TestCase("create a list called groceries", "create_list"),
    TestCase("show my todo list", "get_list_items"),
    # Time / date
    TestCase("what time is it", "get_time"),
    TestCase("what's today's date", "get_time"),
    # Battery
    TestCase("what's my battery level", "get_battery"),
    TestCase("how much battery do I have", "get_battery"),
    # Memory
    TestCase("save that we're meeting Tuesday", "save_memory"),
    TestCase("remember that I prefer dark mode", "save_memory"),
    # Toggles
    TestCase("turn off wifi", "toggle_wifi"),
    TestCase("enable bluetooth", "toggle_bluetooth"),
    TestCase("increase brightness", "set_brightness"),
    # Flashlight
    TestCase("turn on the torch", "toggle_flashlight_on"),
    TestCase("turn off the flashlight", "toggle_flashlight_off"),
]


def run_adb(*args: str) -> str:
    """Run an ADB command and return stdout."""
    result = subprocess.run(
        [ADB, *args],
        capture_output=True,
        text=True,
        timeout=30,
    )
    return result.stdout


def clear_logcat() -> None:
    run_adb("logcat", "-c")


def read_logcat() -> str:
    return run_adb("logcat", "-d", "-s", f"{LOGCAT_TAG}:D")


def send_text(text: str) -> None:
    """Launch the app and broadcast a chat message via ADB."""
    # Wake the screen and unlock
    run_adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    time.sleep(0.3)
    # Launch the app
    run_adb(
        "shell",
        "am",
        "start",
        "-n",
        ACTIVITY,
        "--es",
        "chat_input",
        text,
    )


def extract_intent(logcat_output: str) -> str | None:
    """Extract the last intent= value from logcat output."""
    matches = INTENT_PATTERN.findall(logcat_output)
    return matches[-1] if matches else None


def run_tests(dry_run: bool = False) -> int:
    """Execute all test cases. Returns non-zero on failures."""
    results: list[tuple[TestCase, str | None, bool]] = []

    if dry_run:
        print("=" * 70)
        print("  ADB SKILL TEST — DRY RUN (no device interaction)")
        print("=" * 70)
        print()
        for i, tc in enumerate(TEST_CASES, 1):
            print(f"  [{i:2d}] \"{tc.message}\"")
            print(f"       expect → {tc.expect_intent}")
        print()
        print(f"  Total: {len(TEST_CASES)} test cases")
        print("=" * 70)
        return 0

    if not os.path.isfile(ADB):
        print(f"ERROR: ADB not found at {ADB}", file=sys.stderr)
        return 1

    print("=" * 70)
    print("  ADB SKILL REGRESSION TEST")
    print("=" * 70)
    print()

    for i, tc in enumerate(TEST_CASES, 1):
        print(f"  [{i:2d}/{len(TEST_CASES)}] \"{tc.message}\" ...", end=" ", flush=True)

        clear_logcat()
        send_text(tc.message)
        time.sleep(WAIT_SECONDS)
        logcat = read_logcat()
        actual = extract_intent(logcat)
        passed = actual == tc.expect_intent

        results.append((tc, actual, passed))
        print("✓" if passed else f"✗ (got {actual or 'NO_MATCH'})")

    # Summary table
    print()
    print("-" * 70)
    print(f"  {'#':>3}  {'RESULT':>6}  {'EXPECTED':<24}  {'ACTUAL':<24}")
    print("-" * 70)

    failures = 0
    for i, (tc, actual, passed) in enumerate(results, 1):
        icon = "  ✓" if passed else "  ✗"
        actual_str = actual or "NO_MATCH"
        print(f"  {i:3d}  {icon:>6}  {tc.expect_intent:<24}  {actual_str:<24}  \"{tc.message}\"")
        if not passed:
            failures += 1

    print("-" * 70)
    total = len(results)
    passed_count = total - failures
    print(f"  PASSED: {passed_count}/{total}  FAILED: {failures}/{total}")
    print("=" * 70)

    return 1 if failures > 0 else 0


def main() -> None:
    parser = argparse.ArgumentParser(description="ADB skill regression harness")
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Print test plan without running ADB commands",
    )
    args = parser.parse_args()
    sys.exit(run_tests(dry_run=args.dry_run))


if __name__ == "__main__":
    main()
