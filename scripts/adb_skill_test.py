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
import json
import os
import re
import shlex
import subprocess
import sys
import threading
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

ADB = os.path.expanduser("~/Android/Sdk/platform-tools/adb")
PACKAGE = "com.kernel.ai.debug"
ACTIVITY = f"{PACKAGE}/com.kernel.ai.MainActivity"
SETTINGS_ACTIVITY = f"{PACKAGE}/com.kernel.ai.MainActivity"  # Settings reached via in-app nav
LOGCAT_TAG = "KernelAI"
INTENT_PATTERN = re.compile(r"NativeIntentHandler\.handle:\s*intent=(\S+)\s+params=(\{[^}]*\})")
INTENT_NAME_PATTERN = re.compile(r"NativeIntentHandler\.handle:\s*intent=(\S+)")
DIRECTREPLY_PATTERN = re.compile(r"DirectReply:\s*(.+)")
PROFILE_LLM_PATTERN = re.compile(r"Profile LLM extraction succeeded")
PROFILE_FALLBACK_PATTERN = re.compile(r"Profile regex fallback")
PROFILE_YAML_PATTERN = re.compile(r"name:\s*(.+)")
THINKING_PATTERN = re.compile(r"Thinking tokens:\s*(\d+)")
WAIT_SECONDS = 20
PROFILE_WAIT_SECONDS = 45  # LLM extraction needs more time than QIR
REPORTS_DIR = Path(__file__).parent / "test-reports"


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
    xfail: bool = False  # True = intent not yet implemented; failure is expected
    expect_reply_contains: str | None = None  # if set, verify DirectReply logcat contains this (best-effort)
    expect_params: dict[str, str] | None = None  # if set, assert these key=value pairs appear in extracted params


@dataclass
class TestResult:
    """Structured outcome of a single test case — written to the JSON report."""
    index: int
    message: str
    expect_intent: str
    actual_intent: str | None
    expect_params: dict[str, str] | None
    actual_params: dict[str, str]
    intent_passed: bool
    params_passed: bool  # True when no expect_params set (no assertion)
    param_failures: list[str]  # human-readable descriptions of param mismatches
    xfail: bool
    reply_warn: str | None


PHASES: list[tuple[str, list[TestCase]]] = [
    ("alarm_timer", [
        # set_alarm
        TestCase("set an alarm for 11pm", "set_alarm"),
        TestCase("wake me up at 11:30", "set_alarm"),
        TestCase("remind me tomorrow at 9", "set_alarm"),
        TestCase("alarm 11:30pm", "set_alarm"),
        TestCase("can you wake me at 11:30", "set_alarm"),
        TestCase("I need an alarm for 11 tonight", "set_alarm"),
        # cancel_alarm
        TestCase("cancel my 11pm alarm", "cancel_alarm"),
        TestCase("turn off all my alarms", "cancel_alarm"),
        TestCase("delete my alarm", "cancel_alarm"),
        TestCase("get rid of all alarms", "cancel_alarm"),
        # set_timer
        TestCase("set a timer for 2 hours", "set_timer"),
        TestCase("start a 2 hour timer", "set_timer"),
        TestCase("timer 2 hours", "set_timer"),
        TestCase("start a 3 hour timer", "set_timer"),
        TestCase("countdown 2 hours", "set_timer"),
        # cancel_timer
        TestCase("cancel the timer", "cancel_timer"),
        TestCase("stop the timer", "cancel_timer"),
        TestCase("turn off the timer", "cancel_timer"),
        TestCase("dismiss the timer", "cancel_timer"),
        # list_timers (#525)
        TestCase("what timers do I have", "list_timers", expect_reply_contains=r"."),
        TestCase("show my timers", "list_timers"),
        TestCase("how many timers are running", "list_timers"),
        TestCase("list timers", "list_timers", expect_reply_contains=r"."),
        # cancel_timer_named (#525)
        TestCase("cancel the pasta timer", "cancel_timer_named"),
        TestCase("cancel the 10 minute timer", "cancel_timer_named"),
        TestCase("stop the egg timer", "cancel_timer_named"),
        TestCase("dismiss the laundry timer", "cancel_timer_named"),
        # get_timer_remaining (#525)
        TestCase("how long left on my timer", "get_timer_remaining"),
        TestCase("how much time is left on the pasta timer", "get_timer_remaining"),
        TestCase("how long until the timer goes off", "get_timer_remaining"),
    ]),
    ("weather", [
        TestCase("what's the weather in Auckland", "get_weather"),
        TestCase("will it rain today", "get_weather"),
        TestCase("how hot is it outside", "get_weather"),
        TestCase("do I need an umbrella today", "get_weather"),
        TestCase("what's it like outside", "get_weather"),
        TestCase("is it gonna rain tomorrow", "get_weather"),
        TestCase("temperature in Wellington", "get_weather"),
    ]),
    ("media", [
        # play_media — generic
        TestCase("play some jazz music", "play_media"),
        TestCase("play a song by Fleetwood Mac", "play_media"),
        TestCase("play something chill", "play_media"),
        TestCase("play Abbey Road by The Beatles", "play_media"),
        # platform-specific
        TestCase("play Stranger Things on Netflix", "play_netflix"),
        TestCase("watch The Witcher on Netflix", "play_netflix"),
        TestCase("play Inception on Plex", "play_plex"),
        TestCase("play Taylor Swift on Spotify", "play_spotify"),
        TestCase("put on my Discover Weekly on Spotify", "play_spotify"),
        TestCase("play Bohemian Rhapsody on Plexamp", "play_plexamp"),
        TestCase("listen to Fleetwood Mac on Plexamp", "play_plexamp"),
        TestCase("play some jazz on Plexamp", "play_plexamp"),
        TestCase("play Taylor Swift on YouTube Music", "play_youtube_music"),
        TestCase("listen to my liked songs on YouTube Music", "play_youtube_music"),
        TestCase("search YouTube for cat videos", "play_youtube"),
        TestCase("play my workout playlist", "play_media_playlist"),
        TestCase("put on the road trip playlist", "play_media_playlist"),
        TestCase("play the album Dark Side of the Moon", "play_media_album"),
        # volume
        TestCase("turn the volume up", "set_volume"),
        TestCase("set volume to 50 percent", "set_volume"),
        TestCase("louder", "set_volume"),
        TestCase("mute", "set_volume"),
        # pause_media (#521)
        TestCase("pause the music", "pause_media"),
        TestCase("pause playback", "pause_media"),
        TestCase("hold on", "pause_media"),
        # stop_media (#521)
        TestCase("stop playing", "stop_media"),
        TestCase("stop playback", "stop_media"),
        TestCase("stop the audio", "stop_media"),
        # next_track (#521)
        TestCase("skip this song", "next_track"),
        TestCase("next track", "next_track"),
        TestCase("play the next one", "next_track"),
        TestCase("next song", "next_track"),
        TestCase("skip", "next_track"),
        # previous_track (#521)
        TestCase("previous song", "previous_track"),
        TestCase("last song", "previous_track"),
        TestCase("go back a song", "previous_track"),
        TestCase("play the previous track", "previous_track"),
    ]),
    ("lists", [
        # add_to_list
        TestCase("add milk to my shopping list", "add_to_list",
                 expect_params={"item": "milk", "list_name": "shopping"}),
        TestCase("put eggs on the grocery list", "add_to_list",
                 expect_params={"item": "eggs", "list_name": "grocery"}),
        TestCase("add bread and butter to my shopping list", "add_to_list"),
        TestCase("chuck milk on the list", "add_to_list"),
        TestCase("stick bananas on the shopping list", "add_to_list"),
        TestCase("add tomatoes to the grocery list", "add_to_list"),
        TestCase("pop coffee on my list", "add_to_list"),
        TestCase("put sunscreen on the holiday list", "add_to_list"),
        # get_list_items
        TestCase("show my todo list", "get_list_items",
                 expect_params={"list_name": "todo"}),
        TestCase("what's on my shopping list", "get_list_items",
                 expect_params={"list_name": "shopping"}),
        TestCase("read me my grocery list", "get_list_items"),
        TestCase("what's on my grocery list", "get_list_items"),
        TestCase("show me the shopping list", "get_list_items"),
        TestCase("read out my holiday list", "get_list_items"),
        TestCase("what do I need to get", "get_list_items"),
        # remove_from_list
        TestCase("remove milk from my shopping list", "remove_from_list",
                 expect_params={"item": "milk", "list_name": "shopping"}),
        TestCase("delete eggs from the grocery list", "remove_from_list",
                 expect_params={"item": "eggs", "list_name": "grocery"}),
        TestCase("take milk off the shopping list", "remove_from_list"),
        TestCase("cross milk off the shopping list", "remove_from_list"),
        TestCase("I've got bread, take it off the list", "remove_from_list"),
        TestCase("strike eggs off my grocery list", "remove_from_list"),
        # create_list
        TestCase("create a list called groceries", "create_list",
                 expect_params={"list_name": "groceries"}),
        TestCase("make a new list called holiday packing", "create_list",
                 expect_params={"list_name": "holiday packing"}),
        TestCase("make me a list for camping", "create_list"),
        TestCase("create a new list called work tasks", "create_list"),
        # bulk_add_to_list (#529 — LLM-routed, xfail until verified)
        TestCase("save all those ingredients to my shopping list", "bulk_add_to_list", xfail=True),
        TestCase("add eggs, milk, and bread to the shopping list", "bulk_add_to_list", xfail=True),
        TestCase("put tortilla chips, beef mince, and kidney beans on my grocery list", "bulk_add_to_list", xfail=True),
        TestCase("add these items to my list: apples, bananas, oranges", "bulk_add_to_list", xfail=True),
    ]),
    ("smart_home", [
        TestCase("turn on the living room lights", "smart_home_on"),
        TestCase("lights on", "smart_home_on"),
        TestCase("turn on the heater", "smart_home_on"),
        TestCase("switch off the bedroom lamp", "smart_home_off"),
        TestCase("kill the lights", "smart_home_off"),
    ]),
    ("memory", [
        TestCase("save that we're meeting Tuesday", "save_memory"),
        TestCase("remember that I prefer dark mode", "save_memory"),
        TestCase("note to self call the dentist Monday", "save_memory"),
        TestCase("don't forget I parked on level 3", "save_memory"),
    ]),
    ("navigation", [
        # navigate_to
        TestCase("navigate to the airport", "navigate_to"),
        TestCase("give me directions to Westfield", "navigate_to"),
        TestCase("take me to the airport", "navigate_to"),
        TestCase("directions home", "navigate_to"),
        # open_app
        TestCase("open Spotify", "open_app"),
        TestCase("launch Google Maps", "open_app"),
        # make_call
        TestCase("call voicemail", "make_call"),
        TestCase("call my voicemail", "make_call"),
        TestCase("ring mum", "make_call"),
        TestCase("give Sarah a call", "make_call"),
        # Contact alias resolution (fixture: 'zippy' → Voicemail / 121)
        TestCase("call zippy", "make_call"),
        TestCase("ring zippy", "make_call"),
        # send_sms
        TestCase("text myself a reminder to buy groceries", "send_sms"),
        TestCase("send a message to myself saying call the plumber", "send_sms"),
        TestCase("text John saying I'll be 10 minutes late", "send_sms"),
        TestCase("message mum that I'm on my way", "send_sms"),
    ]),
    ("system", [
        # get_time — DirectReply assertions
        TestCase("what time is it", "get_time", expect_reply_contains=r"\d+:\d+"),
        TestCase("what's today's date", "get_time", expect_reply_contains=r"202[4-9]|20[3-9]\d"),
        # get_battery — DirectReply assertion on first case
        TestCase("what's my battery level", "get_battery", expect_reply_contains=r"\d+%"),
        TestCase("how much battery do I have", "get_battery"),
        TestCase("battery", "get_battery"),
        TestCase("am I running low on battery", "get_battery"),
        # get_system_info
        TestCase("how much storage do I have left", "get_system_info"),
        TestCase("what's my RAM usage", "get_system_info"),
        TestCase("how much space is left on my phone", "get_system_info"),
        # toggles — wifi / bluetooth / brightness / hotspot / airplane / DND
        TestCase("turn off wifi", "toggle_wifi"),
        TestCase("wifi off", "toggle_wifi"),
        TestCase("enable bluetooth", "toggle_bluetooth"),
        TestCase("bluetooth on", "toggle_bluetooth"),
        TestCase("increase brightness", "set_brightness"),
        TestCase("dim the screen", "set_brightness"),
        TestCase("turn on hotspot", "toggle_hotspot"),
        TestCase("hotspot on", "toggle_hotspot"),
        TestCase("enable airplane mode", "toggle_airplane_mode"),
        TestCase("flight mode on", "toggle_airplane_mode"),
        TestCase("enable do not disturb", "toggle_dnd_on"),
        TestCase("DND on", "toggle_dnd_on"),
        TestCase("turn off do not disturb", "toggle_dnd_off"),
        TestCase("disable do not disturb", "toggle_dnd_off"),
        # flashlight
        TestCase("turn on the torch", "toggle_flashlight_on"),
        TestCase("torch", "toggle_flashlight_on"),
        TestCase("turn off the flashlight", "toggle_flashlight_off"),
        TestCase("torch off", "toggle_flashlight_off"),
    ]),
    ("misc", [
        # calendar
        TestCase("create a meeting for tomorrow at 2pm", "create_calendar_event"),
        TestCase("schedule a dentist appointment Friday at 10", "create_calendar_event"),
        TestCase("book a dentist appointment for next Thursday at 2pm", "create_calendar_event"),
        TestCase("add a meeting to my calendar for Friday at 3pm", "create_calendar_event"),
        # email
        TestCase("send an email to John about the project update", "send_email"),
        TestCase("email Sarah the meeting notes", "send_email"),
        # nearby
        TestCase("find a coffee shop near me", "find_nearby"),
        TestCase("what restaurants are nearby", "find_nearby"),
        TestCase("where's the nearest ATM", "find_nearby"),
        TestCase("is there a petrol station nearby", "find_nearby"),
        # play_podcast (#524)
        TestCase("play the Joe Rogan podcast", "play_podcast"),
        TestCase("play the latest episode of Serial", "play_podcast"),
        TestCase("put on the Daily podcast", "play_podcast"),
        TestCase("play the news podcast", "play_podcast"),
        # podcast_skip_forward (#524)
        TestCase("skip forward 2 minutes", "podcast_skip_forward"),
        TestCase("skip ahead 5 minutes", "podcast_skip_forward"),
        TestCase("skip the intro", "podcast_skip_forward"),
        TestCase("forward 30 seconds", "podcast_skip_forward"),
        # podcast_skip_back (#524)
        TestCase("go back 30 seconds", "podcast_skip_back"),
        TestCase("rewind 10 seconds", "podcast_skip_back"),
        TestCase("back 15 seconds", "podcast_skip_back"),
        TestCase("I missed that, go back", "podcast_skip_back", xfail=True),
        # podcast_speed (#524)
        TestCase("play at 1.5x speed", "podcast_speed"),
        TestCase("set playback speed to 2x", "podcast_speed"),
        TestCase("normal speed", "podcast_speed"),
        TestCase("slow down the podcast", "podcast_speed"),
    ]),
]

# Flat list built from phases — preserves backward compatibility with any code
# that iterates TEST_CASES directly (dry-run, summary table, etc.)
TEST_CASES: list[TestCase] = [tc for _, tcs in PHASES for tc in tcs]


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


# ---------------------------------------------------------------------------
# Screen keepalive
# ---------------------------------------------------------------------------

_keepalive_stop = threading.Event()


def _keepalive_worker() -> None:
    """Send KEYCODE_WAKEUP every 25 s to prevent screen sleep during test runs."""
    while not _keepalive_stop.wait(25):
        run_adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")


def start_keepalive() -> threading.Thread:
    _keepalive_stop.clear()
    t = threading.Thread(target=_keepalive_worker, daemon=True, name="screen-keepalive")
    t.start()
    return t


def stop_keepalive() -> None:
    _keepalive_stop.set()


def send_text(text: str) -> None:
    """Deliver chat_input extra via onNewIntent — navigates to chat from any screen."""
    run_adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    time.sleep(0.3)
    # --activity-clear-top ensures our activity is at the top of its task so
    # onNewIntent always fires, even when external apps (Calendar, Clock, Maps)
    # were opened by previous tests and are covering the screen.
    run_adb(
        "shell",
        "am",
        "start",
        "--activity-clear-top",
        "--activity-single-top",
        "-n",
        ACTIVITY,
        "--es",
        "chat_input",
        shlex.quote(text),
    )


DB_PATH = f"/data/data/{PACKAGE}/databases/kernel_db"
ALIAS_TEST_NAME = "zippy"       # test alias → resolves to Voicemail contact
ALIAS_DISPLAY_NAME = "Voicemail"  # must match a real contact on the device


VOICEMAIL_NUMBER = "121"         # provider voicemail shortcode


def setup_contact_alias_fixture() -> bool:
    """Insert test alias 'zippy' → Voicemail into Room contact_aliases table."""
    run_adb(
        "shell", "sqlite3", DB_PATH,
        f"INSERT OR REPLACE INTO contact_aliases (alias, displayName, contactId, phoneNumber) "
        f"VALUES ('{ALIAS_TEST_NAME}', '{ALIAS_DISPLAY_NAME}', '0', '{VOICEMAIL_NUMBER}');",
    )
    return True


def teardown_contact_alias_fixture() -> None:
    """Remove test alias inserted during setup."""
    run_adb(
        "shell", "sqlite3", DB_PATH,
        f"DELETE FROM contact_aliases WHERE alias='{ALIAS_TEST_NAME}';",
    )


def cleanup_side_effects() -> None:
    """Cancel any timers and alarms set during testing to avoid them firing on the device."""
    for msg in ("cancel the timer", "cancel all alarms"):
        send_text(msg)
        time.sleep(3)  # Brief pause — just enough for the intent to dispatch
    # Force-stop all clock apps to silence any ringing timers/alarms that have
    # already fired (send_text cancels pending ones; force-stop kills active alerts).
    for pkg in (
        "com.sec.android.app.clockpackage",  # Samsung Clock
        "com.android.deskclock",             # AOSP Clock
        "com.google.android.deskclock",      # Google Clock
    ):
        run_adb("shell", "am", "force-stop", pkg)


def extract_intent(logcat_output: str) -> tuple[str | None, dict[str, str]]:
    """Extract the intent name and params from logcat output.

    Returns (intent_name, params_dict). params_dict is empty if not found.
    The log line format is:
        NativeIntentHandler.handle: intent=<name> params={key=value, ...}
    """
    m = INTENT_PATTERN.search(logcat_output)
    if m:
        intent_name = m.group(1)
        raw_params = m.group(2)
        # Kotlin's Map.toString() produces {key1=value1, key2=value2}
        params: dict[str, str] = {}
        for kv in re.finditer(r"(\w+)=([^,}]+)", raw_params):
            params[kv.group(1)] = kv.group(2).strip()
        return intent_name, params
    # Fallback: intent name only (older log format without params)
    m2 = INTENT_NAME_PATTERN.search(logcat_output)
    return (m2.group(1) if m2 else None), {}


def extract_reply(logcat_output: str) -> str | None:
    """Extract the first DirectReply content from logcat output."""
    m = DIRECTREPLY_PATTERN.search(logcat_output)
    return m.group(1).strip() if m else None


def check_params(
    expect: dict[str, str] | None,
    actual: dict[str, str],
) -> tuple[bool, list[str]]:
    """Check expected params against actual. Returns (passed, failure_descriptions)."""
    if not expect:
        return True, []
    failures = []
    for k, v in expect.items():
        actual_v = actual.get(k)
        # Partial match: expected value just needs to appear in actual (handles list_name="shopping" vs "shopping list")
        if actual_v is None:
            failures.append(f"{k}: expected {v!r} but key missing")
        elif v.lower() not in actual_v.lower() and actual_v.lower() not in v.lower():
            failures.append(f"{k}: expected {v!r} got {actual_v!r}")
    return len(failures) == 0, failures


def save_report(
    results: list[TestResult],
    suite: str = "skills",
    elapsed: float = 0.0,
    partial: bool = False,
    run_ts: str | None = None,
) -> Path:
    """Serialise results to a JSON file in scripts/test-reports/ and return the path.

    When partial=True, writes/overwrites a fixed-name in-progress snapshot so that
    results are never lost if the run is aborted mid-way.  When partial=False (the
    final save), writes the completed timestamped report and deletes any partial file
    that was written during the same run.
    """
    REPORTS_DIR.mkdir(parents=True, exist_ok=True)
    ts = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H-%M-%SZ")

    if partial and run_ts:
        report_path = REPORTS_DIR / f"{run_ts}_{suite}_partial.json"
        status = "in_progress"
    else:
        report_path = REPORTS_DIR / f"{ts}_{suite}.json"
        status = "complete"
        # Remove the in-progress snapshot now that the full report is being written
        if run_ts:
            partial_file = REPORTS_DIR / f"{run_ts}_{suite}_partial.json"
            partial_file.unlink(missing_ok=True)

    total = len(results)
    passed = sum(1 for r in results if r.intent_passed and r.params_passed and not r.xfail)
    xfails = sum(1 for r in results if r.xfail and not r.intent_passed)
    failures = total - passed - xfails

    report = {
        "suite": suite,
        "status": status,
        "timestamp": ts,
        "elapsed_seconds": round(elapsed, 1),
        "summary": {
            "total": total,
            "passed": passed,
            "xfail": xfails,
            "failed": failures,
        },
        "results": [
            {
                "index": r.index,
                "message": r.message,
                "expect_intent": r.expect_intent,
                "actual_intent": r.actual_intent,
                "expect_params": r.expect_params,
                "actual_params": r.actual_params,
                "intent_passed": r.intent_passed,
                "params_passed": r.params_passed,
                "param_failures": r.param_failures,
                "xfail": r.xfail,
                "reply_warn": r.reply_warn,
                "status": (
                    "xfail" if r.xfail and not r.intent_passed
                    else "pass" if r.intent_passed and r.params_passed
                    else "fail"
                ),
            }
            for r in results
        ],
    }
    report_path.write_text(json.dumps(report, indent=2))

    # Auto-generate HTML report if generator script is present
    gen_script = Path(__file__).parent / "generate_report.py"
    if gen_script.exists():
        subprocess.run([sys.executable, str(gen_script), str(report_path)], check=False)

    return report_path


def analyse_results(results: list[TestResult]) -> None:
    """Print a pattern analysis section after the summary table."""
    failures = [r for r in results if not r.xfail and (not r.intent_passed or not r.params_passed)]
    if not failures:
        print("\n  ✅ No failures to analyse.")
        return

    print("\n  FAILURE ANALYSIS")
    print("  " + "-" * 68)

    # Group intent routing failures by actual (mis-routed) intent
    intent_failures = [r for r in failures if not r.intent_passed]
    param_failures  = [r for r in failures if r.intent_passed and not r.params_passed]

    if intent_failures:
        by_actual: dict[str, list[TestResult]] = {}
        for r in intent_failures:
            key = r.actual_intent or "NO_MATCH"
            by_actual.setdefault(key, []).append(r)
        print(f"\n  Intent routing failures ({len(intent_failures)}):")
        for actual, group in sorted(by_actual.items(), key=lambda x: -len(x[1])):
            expected_intents = sorted({r.expect_intent for r in group})
            print(f"    → routed as {actual!r} instead of {expected_intents}:")
            for r in group:
                print(f"       [{r.index:3d}] \"{r.message}\"")

    if param_failures:
        print(f"\n  Param extraction failures ({len(param_failures)}):")
        for r in param_failures:
            print(f"    [{r.index:3d}] \"{r.message}\"  (intent={r.expect_intent})")
            for pf in r.param_failures:
                print(f"           ✗ {pf}")

    # Highlight intents with high failure rates
    by_intent: dict[str, list[TestResult]] = {}
    for r in results:
        by_intent.setdefault(r.expect_intent, []).append(r)
    hot = [
        (intent, grp)
        for intent, grp in by_intent.items()
        if len(grp) >= 2 and sum(1 for r in grp if not r.intent_passed and not r.xfail) / len(grp) >= 0.5
    ]
    if hot:
        print(f"\n  ⚠️  High-failure-rate intents (≥50% of cases failing):")
        for intent, grp in sorted(hot, key=lambda x: -len(x[1])):
            n_fail = sum(1 for r in grp if not r.intent_passed and not r.xfail)
            print(f"    {intent}: {n_fail}/{len(grp)} failing")

    print()


def run_tests(dry_run: bool = False) -> int:
    """Execute all test cases. Returns non-zero on failures."""
    if dry_run:
        print("=" * 70)
        print("  ADB SKILL TEST — DRY RUN (no device interaction)")
        print("=" * 70)
        print()
        for i, tc in enumerate(TEST_CASES, 1):
            print(f"  [{i:2d}] \"{tc.message}\"")
            suffix = f" | reply_contains={tc.expect_reply_contains!r}" if tc.expect_reply_contains else ""
            print(f"       expect → {tc.expect_intent}{suffix}")
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

    # Keep screen awake for the duration of the test run (restored on exit).
    # svc stayon usb only works when actively charging; background keepalive thread
    # sends KEYCODE_WAKEUP every 25 s as the primary mechanism, with max timeout as fallback.
    run_adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    run_adb("shell", "svc", "power", "stayon", "usb")
    run_adb("shell", "settings", "put", "system", "screen_off_timeout", "2147483647")
    start_keepalive()

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

    # Pre-run cleanup: silence any already-fired timers first, then cancel pending ones
    print("  [init] Cleaning up timers/alarms ...", end=" ", flush=True)
    for pkg in (
        "com.sec.android.app.clockpackage",
        "com.android.deskclock",
        "com.google.android.deskclock",
    ):
        run_adb("shell", "am", "force-stop", pkg)
    cleanup_side_effects()
    print("done")

    # Insert contact alias fixture for alias resolution tests
    print("  [init] Setting up contact alias fixture ...", end=" ", flush=True)
    setup_contact_alias_fixture()
    print("done")

    # Flush any logcat residue from the cleanup intents before starting tests.
    time.sleep(WAIT_SECONDS)
    clear_logcat()
    time.sleep(1)
    print()

    run_ts = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H-%M-%SZ")
    suite_start = time.time()
    results: list[TestResult] = []
    global_index = 1
    total_tests = len(TEST_CASES)

    for phase_num, (phase_name, phase_cases) in enumerate(PHASES, 1):
        phase_start = time.time()
        phase_results: list[TestResult] = []

        print(f"  ── [phase {phase_num}/{len(PHASES)}] {phase_name} — {len(phase_cases)} tests ──")

        for tc in phase_cases:
            print(f"  [{global_index:3d}/{total_tests}] \"{tc.message}\" ...", end=" ", flush=True)

            clear_logcat()
            time.sleep(0.5)  # Brief pause to ensure logcat clear is flushed before sending
            send_text(tc.message)
            time.sleep(WAIT_SECONDS)
            logcat = read_logcat()
            actual_intent, actual_params = extract_intent(logcat)
            intent_passed = actual_intent == tc.expect_intent
            params_ok, param_failures = check_params(tc.expect_params, actual_params)
            overall_passed = intent_passed and params_ok

            # DirectReply verification — best-effort, warn but don't fail the test
            reply_warn: str | None = None
            if intent_passed and tc.expect_reply_contains is not None:
                reply_text = extract_reply(logcat)
                if reply_text is None:
                    reply_warn = "no DirectReply logged"
                elif not re.search(tc.expect_reply_contains, reply_text):
                    reply_warn = f"reply {reply_text!r} didn't match {tc.expect_reply_contains!r}"

            result = TestResult(
                index=global_index,
                message=tc.message,
                expect_intent=tc.expect_intent,
                actual_intent=actual_intent,
                expect_params=tc.expect_params,
                actual_params=actual_params,
                intent_passed=intent_passed,
                params_passed=params_ok,
                param_failures=param_failures,
                xfail=tc.xfail,
                reply_warn=reply_warn,
            )
            phase_results.append(result)
            results.append(result)
            global_index += 1

            if overall_passed:
                print("✓" + (f" [reply warn: {reply_warn}]" if reply_warn else ""))
            elif tc.xfail:
                print("✗ (xfail — not yet implemented)")
            elif not intent_passed:
                print(f"✗ (got {actual_intent or 'NO_MATCH'})")
            else:
                print(f"✗ (params: {'; '.join(param_failures)})")

            # Hang up after call tests so they don't stay open
            if tc.expect_intent == "make_call":
                time.sleep(2)
                run_adb("shell", "input", "keyevent", "KEYCODE_ENDCALL")

        # ── Phase summary ──────────────────────────────────────────────────
        phase_elapsed = time.time() - phase_start
        n_pass  = sum(1 for r in phase_results if r.intent_passed and r.params_passed and not r.xfail)
        n_xfail = sum(1 for r in phase_results if r.xfail and not r.intent_passed)
        n_fail  = sum(1 for r in phase_results if not r.xfail and (not r.intent_passed or not r.params_passed))
        print(
            f"  → {phase_name}: {n_pass} pass  {n_fail} fail  {n_xfail} xfail"
            f"  ({phase_elapsed:.1f}s)"
        )

        # OOM / model-reset sanity check (#554): warn if every test returned the same intent
        actual_intents = [r.actual_intent for r in phase_results if r.actual_intent]
        if len(actual_intents) > 1 and len(set(actual_intents)) == 1:
            print(
                f"  ⚠ WARNING: all {len(actual_intents)} tests in {phase_name}"
                f" returned '{actual_intents[0]}' — possible OOM/model reset"
            )

        # Incremental report save so results are never lost on abort
        save_report(
            results,
            suite="skills",
            elapsed=time.time() - suite_start,
            partial=True,
            run_ts=run_ts,
        )
        print()

    # Summary table
    print()
    print("-" * 70)
    print(f"  {'#':>3}  {'RESULT':>6}  {'EXPECTED':<24}  {'ACTUAL':<24}")
    print("-" * 70)

    failures = 0
    xfails = 0
    for r in results:
        if r.intent_passed and r.params_passed:
            icon = "  ✓"
        elif r.xfail:
            icon = "  ✗"
        else:
            icon = "  ✗"
        actual_str = r.actual_intent or "NO_MATCH"
        suffix = " (xfail)" if not r.intent_passed and r.xfail else ""
        if not r.intent_passed and not r.xfail:
            detail = actual_str
        elif not r.params_passed and not r.xfail:
            detail = f"{actual_str} [param fail]"
        else:
            detail = actual_str
        print(f"  {r.index:3d}  {icon:>6}  {r.expect_intent:<24}  {detail:<24}  \"{r.message}\"{suffix}")
        if not r.xfail and (not r.intent_passed or not r.params_passed):
            failures += 1
        elif r.xfail and not r.intent_passed:
            xfails += 1

    print("-" * 70)
    total = len(results)
    passed_count = total - failures - xfails
    print(f"  PASSED: {passed_count}/{total}  XFAIL: {xfails}/{total}  FAILED: {failures}/{total}")
    print("=" * 70)

    analyse_results(results)
    report_path = save_report(
        results,
        suite="skills",
        elapsed=time.time() - suite_start,
        partial=False,
        run_ts=run_ts,
    )
    print(f"  Report saved → {report_path}")
    print()

    # Post-run cleanup: cancel any timers/alarms set during testing
    print()
    print("  [cleanup] Cancelling timers/alarms ...", end=" ", flush=True)
    cleanup_side_effects()
    print("done")
    print("  [cleanup] Removing contact alias fixture ...", end=" ", flush=True)
    teardown_contact_alias_fixture()
    print("done")
    print("  [cleanup] Restoring screen-timeout behaviour ...", end=" ", flush=True)
    stop_keepalive()
    run_adb("shell", "svc", "power", "stayon", "false")
    run_adb("shell", "settings", "put", "system", "screen_off_timeout", "60000")  # restore 60s
    print("done")

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

    results: list[TestResult] = []

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

        results.append(TestResult(
            index=i,
            message=tc.name,
            expect_intent=tc.name,
            actual_intent=extracted["method"],
            expect_params=None,
            actual_params={k: v for k, v in extracted.items() if v is not None},
            intent_passed=passed,
            params_passed=True,
            param_failures=[],
            xfail=False,
            reply_warn=None,
        ))
        method_tag = f"[{extracted['method'] or 'NO_LOG'}]"
        print("✓" if passed else "✗", method_tag)

    # Summary
    print()
    print("-" * 70)
    failures = 0
    for r in results:
        icon = "  ✓" if r.intent_passed else "  ✗"
        print(f"  {icon}  {r.message}")
        print(f"       method={r.actual_intent}, "
              f"name={r.actual_params.get('name')!r}, "
              f"role={r.actual_params.get('role')!r}, "
              f"location={r.actual_params.get('location')!r}")
        if not r.intent_passed:
            failures += 1

    print("-" * 70)
    total = len(results)
    passed_count = total - failures
    print(f"  PASSED: {passed_count}/{total}  FAILED: {failures}/{total}")
    print("=" * 70)

    analyse_results(results)
    report_path = save_report(results, suite="profile")
    print(f"  Report saved → {report_path}")
    print()

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
