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
import shlex
import subprocess
import sys
import time
from dataclasses import dataclass

ADB = os.path.expanduser("~/Android/Sdk/platform-tools/adb")
PACKAGE = "com.kernel.ai.debug"
ACTIVITY = f"{PACKAGE}/com.kernel.ai.MainActivity"
SETTINGS_ACTIVITY = f"{PACKAGE}/com.kernel.ai.MainActivity"  # Settings reached via in-app nav
LOGCAT_TAG = "KernelAI"
INTENT_PATTERN = re.compile(r"NativeIntentHandler\.handle:\s*intent=(\S+)")
PROFILE_LLM_PATTERN = re.compile(r"Profile LLM extraction succeeded")
PROFILE_FALLBACK_PATTERN = re.compile(r"Profile regex fallback")
PROFILE_YAML_PATTERN = re.compile(r"name:\s*(.+)")
THINKING_PATTERN = re.compile(r"Thinking tokens:\s*(\d+)")
WAIT_SECONDS = 20
PROFILE_WAIT_SECONDS = 45  # LLM extraction needs more time than QIR


# ── Profile test cases ────────────────────────────────────────────────────────

NICK_509_PROFILE = """my name is Nick
I am a Principal Technologist at LAB3. High technical literacy.
Location: Brisbane, QLD, Australia. originally from nz
Family: Married with three children (ages 1 (Lachlan), 5 (Freyja), and 10(Emilie)) and a Hungarian Vizsla dog named Xena.
Technical Environment
Systems: CachyOS (Main PC), Bazzite OS (ROG Ally). Windows 11 (Homelab/ Plex Server, other Docker, *arr stack)
Hardware: Main PC: AMD Ryzen 5700X3D | Radeon RX 9070 XT | 32GB RAM, HomeLab: Ryzen 5600 | GTX 1060 | 32GB RAM
Network: Static IP; avoids dynamic DNS dependencies.
Homelab: Extensive Docker environment (Plex, *arr stack, Nginx). Currently migrating from Google services to self-hosted alternatives like Plexamp, Nextcloud.
Local AI: Uses llama.cpp, and OpenCode. Prioritizes local-first compute and privacy.
Smart Home: Advanced Home Assistant user (YAML focus). Integrates Fox ESS solar/battery and multi-zone climate control.
Gaming: PC-centric (e.g., Cyberpunk 2077, The Witcher 3). Also has a PS5 and plays ARPGs like Ghost of Yotei.
Cooking: Strong preference for RecipeTin Eats (Nagi's recipes) for meal planning.
Tone: Prefers concise, technically precise, and actionable information.
AI Instruction Hook: Assume expert-level knowledge of Linux, containerization, and AI hardware optimization.
Prioritize local-first suggestions over cloud dependencies. When providing recipes, default to RecipeTin Eats. Do not try to inject meal planning advice unless asked."""

SIMPLE_PROFILE = "My name is Alex. I'm a software engineer based in Sydney, Australia. I prefer concise answers. Never use bullet points unless I ask. I use a Pixel 8 and run Ubuntu."

MINIMAL_PROFILE = "Sam here, I'm a designer in London. Keep it brief."


@dataclass
class ProfileTestCase:
    name: str
    profile_text: str
    # Fields to assert are present in the parsed YAML (checked against logcat output)
    expect_name: str | None = None
    expect_role_contains: str | None = None
    expect_location_contains: str | None = None


@dataclass
class TestCase:
    message: str
    expect_intent: str


TEST_CASES: list[TestCase] = [
    # Alarm
    TestCase("set an alarm for 7am", "set_alarm"),
    TestCase("wake me up at 6:30", "set_alarm"),
    TestCase("remind me tomorrow at 9", "set_alarm"),
    TestCase("cancel my 7am alarm", "cancel_alarm"),
    TestCase("turn off all my alarms", "cancel_alarm"),
    # Timer
    TestCase("set a timer for 10 minutes", "set_timer"),
    TestCase("start a 5 minute timer", "set_timer"),
    TestCase("cancel the timer", "cancel_timer"),
    TestCase("stop the timer", "cancel_timer"),
    # Weather
    TestCase("what's the weather in Auckland", "get_weather"),
    TestCase("will it rain today", "get_weather"),
    TestCase("how hot is it outside", "get_weather"),
    # Lists
    TestCase("add milk to my shopping list", "add_to_list"),
    TestCase("put eggs on the grocery list", "add_to_list"),
    TestCase("create a list called groceries", "create_list"),
    TestCase("show my todo list", "get_list_items"),
    TestCase("what's on my shopping list", "get_list_items"),
    TestCase("remove milk from my shopping list", "remove_from_list"),
    TestCase("delete eggs from the grocery list", "remove_from_list"),
    # Calendar
    TestCase("create a meeting for tomorrow at 2pm", "create_calendar_event"),
    TestCase("schedule a dentist appointment Friday at 10", "create_calendar_event"),
    # Time / date
    TestCase("what time is it", "get_time"),
    TestCase("what's today's date", "get_time"),
    # Battery
    TestCase("what's my battery level", "get_battery"),
    TestCase("how much battery do I have", "get_battery"),
    # System info
    TestCase("how much storage do I have left", "get_system_info"),
    TestCase("what's my RAM usage", "get_system_info"),
    # Memory
    TestCase("save that we're meeting Tuesday", "save_memory"),
    TestCase("remember that I prefer dark mode", "save_memory"),
    # Calls
    TestCase("call voicemail", "make_call"),
    TestCase("call my voicemail", "make_call"),
    # SMS
    TestCase("text myself a reminder to buy groceries", "send_sms"),
    TestCase("send a message to myself saying call the plumber", "send_sms"),
    # Email
    TestCase("send an email to John about the project update", "send_email"),
    TestCase("email Sarah the meeting notes", "send_email"),
    # Navigation
    TestCase("navigate to the airport", "navigate_to"),
    TestCase("give me directions to Westfield", "navigate_to"),
    # Nearby
    TestCase("find a coffee shop near me", "find_nearby"),
    TestCase("what restaurants are nearby", "find_nearby"),
    # Apps
    TestCase("open Spotify", "open_app"),
    TestCase("launch Google Maps", "open_app"),
    # Media — generic
    TestCase("play some jazz music", "play_media"),
    TestCase("play a song by Fleetwood Mac", "play_media"),
    # Media — platform-specific
    TestCase("play Stranger Things on Netflix", "play_netflix"),
    TestCase("play Inception on Plex", "play_plex"),
    TestCase("play Taylor Swift on Spotify", "play_spotify"),
    TestCase("search YouTube for cat videos", "play_youtube"),
    TestCase("play my workout playlist", "play_media_playlist"),
    TestCase("play the album Dark Side of the Moon", "play_media_album"),
    # Volume
    TestCase("turn the volume up", "set_volume"),
    TestCase("set volume to 50 percent", "set_volume"),
    # Toggles — wifi / bt / brightness / hotspot / airplane / DND
    TestCase("turn off wifi", "toggle_wifi"),
    TestCase("enable bluetooth", "toggle_bluetooth"),
    TestCase("increase brightness", "set_brightness"),
    TestCase("turn on hotspot", "toggle_hotspot"),
    TestCase("enable airplane mode", "toggle_airplane_mode"),
    TestCase("enable do not disturb", "toggle_dnd_on"),
    TestCase("turn off do not disturb", "toggle_dnd_off"),
    # Flashlight
    TestCase("turn on the torch", "toggle_flashlight_on"),
    TestCase("turn off the flashlight", "toggle_flashlight_off"),
    # Smart home
    TestCase("turn on the living room lights", "smart_home_on"),
    TestCase("switch off the bedroom lamp", "smart_home_off"),
]


def run_adb(*args: str) -> str:
    """Run an ADB command and return stdout. Prints stderr on non-zero exit."""
    result = subprocess.run(
        [ADB, *args],
        capture_output=True,
        text=True,
        timeout=30,
    )
    if result.returncode != 0 and result.stderr:
        print(f"\n  [adb warn] {result.stderr.strip()}", file=sys.stderr)
    return result.stdout


def clear_logcat() -> None:
    run_adb("logcat", "-c")


def read_logcat() -> str:
    return run_adb("logcat", "-d", "-s", f"{LOGCAT_TAG}:D")


def read_logcat_all() -> str:
    """Read KernelAI and LiteRtInferenceEngine tags (needed for warm-up and profile tests)."""
    return run_adb("logcat", "-d", "-s", f"{LOGCAT_TAG}:D", "-s", "LiteRtInferenceEngine:I")


def send_text(text: str) -> None:
    """Deliver chat_input extra via onNewIntent — navigates to chat from any screen."""
    run_adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    time.sleep(0.3)
    run_adb(
        "shell",
        "am",
        "start",
        "-n",
        ACTIVITY,
        "--es",
        "chat_input",
        shlex.quote(text),
    )


def extract_intent(logcat_output: str) -> str | None:
    """Extract the first intent= value from logcat output (logcat is cleared before each test)."""
    matches = INTENT_PATTERN.findall(logcat_output)
    return matches[0] if matches else None


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

    # Warm up: send a dummy query to trigger model load, wait for NativeIntentHandler to fire
    print("  [init] Warming up model (this takes ~30s on first run) ...", end=" ", flush=True)
    run_adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    run_adb("shell", "am", "start", "-n", ACTIVITY)
    time.sleep(3)
    clear_logcat()
    run_adb("shell", "am", "start", "-n", ACTIVITY, "--es", "chat_input", shlex.quote("what time is it"))
    # Poll logcat until NativeIntentHandler fires (model loaded + QIR dispatched) or 60s timeout
    deadline = time.time() + 60
    warmed = False
    while time.time() < deadline:
        time.sleep(2)
        log = read_logcat()
        if "NativeIntentHandler.handle" in log:
            warmed = True
            break
    print("ready" if warmed else "timeout (proceeding anyway)")
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


PROFILE_TEST_CASES: list[ProfileTestCase] = [
    ProfileTestCase(
        name="nick_509_full",
        profile_text=NICK_509_PROFILE,
        expect_name="Nick",
        expect_role_contains="Technologist",
        expect_location_contains="Brisbane",
    ),
    ProfileTestCase(
        name="simple_alex",
        profile_text=SIMPLE_PROFILE,
        expect_name="Alex",
        expect_role_contains="engineer",
        expect_location_contains="Sydney",
    ),
    ProfileTestCase(
        name="minimal_sam",
        profile_text=MINIMAL_PROFILE,
        expect_name="Sam",
        # "designer in London" — location embedded in role phrase; parser needs explicit
        # "based in"/"located in"/etc. pattern. Name extraction is the key assertion here.
    ),
]


def send_profile(profile_text: str) -> None:
    """Deliver profile_text extra via onNewIntent — triggers UserProfileRepository.save()."""
    run_adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    time.sleep(0.3)
    # Split multi-line profile into a single escaped string passed as extra
    # ADB intent extras cannot contain newlines; replace with \n literal that Kotlin will handle
    single_line = profile_text.replace("\n", "\\n")
    run_adb(
        "shell",
        "am",
        "start",
        "-n",
        ACTIVITY,
        "--es",
        "profile_text",
        shlex.quote(single_line),
    )


def extract_profile_result(logcat_output: str) -> dict[str, str | None]:
    """Parse logcat for profile extraction result (LLM or regex) and key fields.

    Logcat lines look like:
      D KernelAI: Profile regex fallback:
      D KernelAI: name: Sam
      D KernelAI: role: designer
    """
    used_llm = bool(PROFILE_LLM_PATTERN.search(logcat_output))
    used_fallback = bool(PROFILE_FALLBACK_PATTERN.search(logcat_output))

    # Anchor to the KernelAI tag prefix so we don't accidentally match other log lines
    name_match = re.search(r"KernelAI: name:\s*(.+)", logcat_output)
    role_match = re.search(r"KernelAI: role:\s*(.+)", logcat_output)
    location_match = re.search(r"KernelAI: location:\s*(.+)", logcat_output)

    return {
        "method": "llm" if used_llm else ("regex" if used_fallback else None),
        "name": name_match.group(1).strip() if name_match else None,
        "role": role_match.group(1).strip() if role_match else None,
        "location": location_match.group(1).strip() if location_match else None,
    }


def run_profile_tests(dry_run: bool = False) -> int:
    """Execute all profile extraction test cases. Returns non-zero on failures."""
    if dry_run:
        print("=" * 70)
        print("  PROFILE EXTRACTION TEST — DRY RUN (no device interaction)")
        print("=" * 70)
        print()
        for i, tc in enumerate(PROFILE_TEST_CASES, 1):
            print(f"  [{i}] {tc.name}")
            print(f"       profile: {tc.profile_text[:60].replace(chr(10), ' ')}...")
            print(f"       expect_name={tc.expect_name}, "
                  f"role_contains={tc.expect_role_contains}, "
                  f"location_contains={tc.expect_location_contains}")
        print()
        print(f"  Total: {len(PROFILE_TEST_CASES)} profile test cases")
        print("=" * 70)
        return 0

    if not os.path.isfile(ADB):
        print(f"ERROR: ADB not found at {ADB}", file=sys.stderr)
        return 2

    print("=" * 70)
    print("  PROFILE EXTRACTION TEST")
    print("=" * 70)
    print()

    # Warm up: send a chat message and wait for the inference engine to load
    print("  Warming up engine ...", end=" ", flush=True)
    clear_logcat()
    send_text("hello")
    warm_start = time.time()
    warmed = False
    while time.time() - warm_start < 60:
        time.sleep(2)
        log = read_logcat_all()
        if "Generation complete" in log:
            warmed = True
            break
    print("ready" if warmed else "timeout (proceeding anyway)")
    print()

    results: list[tuple[ProfileTestCase, dict[str, str | None], bool]] = []

    for i, tc in enumerate(PROFILE_TEST_CASES, 1):
        print(f"  [{i}/{len(PROFILE_TEST_CASES)}] {tc.name} ...", end=" ", flush=True)
        clear_logcat()
        send_profile(tc.profile_text)
        time.sleep(PROFILE_WAIT_SECONDS)
        logcat = read_logcat_all()
        extracted = extract_profile_result(logcat)

        passed = True
        if tc.expect_name and extracted["name"] != tc.expect_name:
            passed = False
        if tc.expect_role_contains and (
            not extracted["role"] or tc.expect_role_contains.lower() not in extracted["role"].lower()
        ):
            passed = False
        if tc.expect_location_contains and (
            not extracted["location"]
            or tc.expect_location_contains.lower() not in extracted["location"].lower()
        ):
            passed = False

        results.append((tc, extracted, passed))
        method_tag = f"[{extracted['method'] or 'NO_LOG'}]"
        print("✓" if passed else "✗", method_tag)

    # Summary
    print()
    print("-" * 70)
    failures = 0
    for tc, extracted, passed in results:
        icon = "  ✓" if passed else "  ✗"
        print(f"  {icon}  {tc.name}")
        print(f"       method={extracted['method']}, "
              f"name={extracted['name']!r}, "
              f"role={extracted['role']!r}, "
              f"location={extracted['location']!r}")
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
    parser.add_argument(
        "--profile",
        action="store_true",
        help="Run profile extraction tests instead of skill routing tests",
    )
    args = parser.parse_args()
    if args.profile:
        sys.exit(run_profile_tests(dry_run=args.dry_run))
    else:
        sys.exit(run_tests(dry_run=args.dry_run))


if __name__ == "__main__":
    main()
