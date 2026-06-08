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
LLM_TOOLS_ROUTE_PATTERN = re.compile(r"llm_tools_route:\s*(.+)")
LLM_TOOLS_NATIVE_TOOL_PATTERN = re.compile(r"llm_tools_native_tool:\s*(.+)")
LLM_TOOLS_LEGACY_TOOL_PATTERN = re.compile(r"llm_tools_legacy_tool:\s*(.+)")
LLM_TOOLS_SKILL_RESULT_PATTERN = re.compile(r"llm_tools_skill_result:\s*(.+)")
LLM_TOOLS_MESSAGE_SAVED_PATTERN = re.compile(r"llm_tools_message_toolcall_saved:\s*(.+)")
LLM_TOOLS_RETRY_PATTERN = re.compile(r"raw_tool_call_retry_succeeded|hallucination_retry_succeeded")
LLM_TOOLS_SLOT_FILL_PATTERN = re.compile(r"NeedsSlot|ConfirmationFastPath:")
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
    expect_intent: str = ""  # empty = expect no intent dispatch
    xfail: bool = False  # True = intent not yet implemented; failure is expected
    expect_reply_contains: str | None = None  # if set, verify DirectReply logcat contains this (best-effort)
    expect_params: dict[str, str] | None = None  # if set, assert these key=value pairs appear in extracted params
    # Slot-fill test fields: if set, `message` is sent via quick_action_input (triggers NeedsSlot),
    # then `slot_reply` is sent via slot_reply_input to ActionsViewModel.onSlotReply(), and the
    # intent is verified after. (Pre-#589: slot_reply was delivered via chat_input — now uses
    # the dedicated slot_reply_input extra so it stays in the Actions tab.)
    slot_reply: str | None = None
    # New: orchestrator-aware fields
    # If set, verify logcat contains this substring (best-effort). Use for orchestrator
    # AskConfirmation/AskSlot/AskClarification paths that don't dispatch a NativeIntentHandler.
    expect_log_contains: str | None = None
    # If set, after the initial message is sent (and an AskConfirmation is expected),
    # send this reply as a chat_input to confirm and trigger execution.
    confirm_reply: str | None = None

@dataclass
class LLMToolsTestCase:
    """Test case for the llm_tools harness phase."""
    name: str
    message: str
    expected_top_level_tool: str  # e.g. "run_intent", "get_weather"
    expected_nested_intent: str | None = None  # e.g. "set_timer", "add_reminder"
    expected_fields: dict[str, str] | None = None  # semantic field assertions
    expected_result_mode: str = "unknown"  # "direct_reply" | "llm_wrapped_success" | "unknown"
    expect_no_regex_match: bool = True
    expect_no_classifier_match: bool = True
    expect_no_slot_fill: bool = True
    expect_no_retry: bool = True


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
    log_check_warn: str | None
    phase: str = ""

@dataclass
class LLMToolsResult:
    """Structured outcome of a single llm_tools test case."""
    index: int
    name: str
    message: str
    expected_top_level_tool: str
    actual_top_level_tool: str | None
    actual_nested_intent: str | None
    route_marker: str | None
    native_tool_marker: str | None
    legacy_tool_marker: str | None
    skill_result_marker: str | None
    message_saved_marker: str | None
    retry_seen: bool
    slot_fill_seen: bool
    chip_text: str | None
    reply_text: str | None
    passed: bool
    failures: list[str]
    phase: str = "llm_tools"


# Semantic routing guidelines for deterministic test cases:
#   - "note to self <memo>" => save_memory (memo/note capture). Until a dedicated
#     notes skill exists, keep under save_memory. If a notes skill is added, move
#     there, not to add_reminder.
#   - "remember that <durable fact/preference>" => save_memory.
#   - "remember/remind me to <task> at/on <time/date>" => add_reminder.
#   - "wake me / alarm <time>" => set_alarm.
#   - Avoid standalone anaphora ("those ingredients", "that", "it") in single-turn
#     golden cases — no prior context to resolve them.
#   - Avoid context-dependent media commands ("hold on", "normal speed") unless the
#     suite explicitly sets up media context first.
#   - "text myself" requires expect_params to confirm contact resolution works.
#

PHASES: list[tuple[str, list[TestCase]]] = [
    ("alarm_timer", [
        # set_alarm
        TestCase("set an alarm for 11pm", "set_alarm"),
        TestCase("wake me up at 11:30", "set_alarm"),
        TestCase("set an alarm for tomorrow at 9am", "set_alarm"),
        TestCase("alarm 11:30pm", "set_alarm"),
        TestCase("can you wake me at 11:30", "set_alarm"),
        TestCase("I need an alarm for 11 tonight", "set_alarm"),
        # add_reminder — explicit future-task prompts, distinct from save_memory
        TestCase("remind me to call the dentist Monday", "add_reminder"),
        TestCase("remind me at 9am Monday to call the dentist", "add_reminder"),
        TestCase("remind me to pick up dry cleaning tomorrow evening", "add_reminder"),
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
        TestCase("hold on, pause the music", "pause_media"),
        TestCase("pause playback", "pause_media"),
        TestCase("hold on", "pause_media", xfail=True),  # standalone "hold on" requires active media context per semantic routing guidelines
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
        # "add bread and butter" is a multi-item request → bulk_add_to_list (xfail)
        TestCase("add bread and butter to my shopping list", "bulk_add_to_list", xfail=True),
        # Kiwi/Aus colloquial usage: "chuck X on the list" means add/put X on the list.
        TestCase("chuck milk on the list", "add_to_list",
                 expect_params={"item": "milk"}),
        TestCase("chuck eggs on the grocery list", "add_to_list",
                 expect_params={"item": "eggs", "list_name": "grocery"}),
        TestCase("pop coffee on my list", "add_to_list"),
        TestCase("put sunscreen on the holiday list", "add_to_list"),
        # get_list_items
        TestCase("show my todo list", "get_list_items",
                 expect_params={"list_name": "todo"}),
        TestCase("what's on my shopping list", "get_list_items",
                 expect_params={"list_name": "shopping"}),
        TestCase("what do I need to get from the shops", "get_list_items"),
        TestCase("what's on my grocery list", "get_list_items"),
        TestCase("show me the shopping list", "get_list_items"),
        TestCase("read out my holiday list", "get_list_items"),
        TestCase("read me my grocery list", "get_list_items"),
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
        TestCase("add eggs, milk, and bread to my shopping list", "bulk_add_to_list", xfail=True),
        TestCase("make me a list for camping", "create_list"),
        TestCase("create a new list called work tasks", "create_list"),
        # bulk_add_to_list (#529 — LLM-routed, xfail until verified)
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
        # save_memory — durable facts/preferences only, not future tasks
        # "remember that <durable fact/preference>" => save_memory
        # "note to self <memo>" => save_memory (until a dedicated notes skill exists)
        # "remind me to <task>" => add_reminder (not save_memory)
        TestCase("remember that I usually meet Sarah on Tuesdays", "save_memory"),
        TestCase("remember that I prefer dark mode", "save_memory"),
        # Memo/note capture — no alert implied. If a dedicated notes skill is added,
        # this should move from save_memory to that note/memo intent, not add_reminder.
        TestCase("note to self call the dentist Monday", "save_memory"),
        # Ephemeral memo capture — no alert implied.
        TestCase("remember that I parked on level 3", "save_memory"),
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
        TestCase("text myself a reminder to buy groceries", "send_sms",
                 expect_params={"contact": "myself", "message": "buy groceries"}),
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
        TestCase("set podcast playback to normal speed", "podcast_speed"),
        TestCase("slow down the podcast", "podcast_speed"),
    ]),
    ("slot_fill", [
        # set_alarm — bare query (no time) → slot asks for time → user provides time
        TestCase(
            "set an alarm",
            "set_alarm",
            slot_reply="7am",
            expect_params={"hours": "7", "minutes": "0"},
        ),
        # set_timer — bare query (no duration) → slot asks how long → user provides duration
        TestCase(
            "set a timer",
            "set_timer",
            slot_reply="5 minutes",
            expect_params={"duration_seconds": "300"},
        ),
        # open_app — bare query (no app name) → slot asks which app → user provides name
        TestCase(
            "open an app",
            "open_app",
            slot_reply="Spotify",
            expect_params={"app_name": "Spotify"},
        ),
        # navigate_to — bare query (no destination) → slot asks where → user provides destination
        TestCase(
            "navigate",
            "navigate_to",
            slot_reply="Auckland Airport",
            expect_params={"destination": "Auckland Airport"},
        ),
        # find_nearby — bare query (no query) → slot asks what → user provides query
        TestCase(
            "find nearby",
            "find_nearby",
            slot_reply="coffee",
            expect_params={"query": "coffee"},
        ),
        # send_sms — no contact → slot asks who → user provides contact
        TestCase(
            "send a message",
            "send_sms",
            slot_reply="Mum",
            expect_params={"contact": "Mum"},
        ),
        # send_email — no contact → slot asks who → user provides contact
        TestCase(
            "send an email",
            "send_email",
            slot_reply="Nick",
            expect_params={"contact": "Nick"},
        ),
        # add_to_list — no item → slot asks what → user provides item
        TestCase(
            "add to my list",
            "add_to_list",
            slot_reply="eggs",
            expect_params={"item": "eggs"},
        ),
    ]),
    ("orchestrator_recovery", [
        # ── Scenario 1: FallThrough → AskConfirmation (medium risk, date extracted) ──
        # __orchtest:<intent>:<real_input> forces orchestrator in debug builds
        TestCase(
            "__orchtest:create_calendar_event:schedule a dentist visit next Thursday at 2pm",
            expect_intent="",
            expect_log_contains="RecoveryOrchestrator.AskConfirmation: intent=create_calendar_event",
        ),
        # ── Scenario 4: Missing slots → AskSlot ──
        TestCase(
            "__orchtest:create_calendar_event:schedule a meeting",
            expect_intent="",
            expect_log_contains="RecoveryOrchestrator.AskSlot: intent=create_calendar_event",
        ),
        # ── Scenario 5: No extractor → NotActionable ──
        TestCase(
            "__orchtest:get_weather:what is the weather like",
            expect_intent="",
            expect_log_contains="RecoveryOrchestrator.NotActionable",
        ),
        # ── Scenario 6: High-risk intent → AskConfirmation ──
        TestCase(
            "__orchtest:send_sms:tell Sarah I am running late",
            expect_intent="",
            expect_log_contains="RecoveryOrchestrator.NotActionable",
            xfail=True,  # No send_sms extractor yet
        ),
        # ── Scenario 7: Unknown intent → NotActionable ──
        TestCase(
            "__orchtest:xyzzy_unknown:some nonsense input",
            expect_intent="",
            expect_log_contains="RecoveryOrchestrator.NotActionable",
        ),
        # ── Scenario 10: Intent with no contract → NotActionable ──
        TestCase(
            "__orchtest:play_youtube:play some music",
            expect_intent="",
            expect_log_contains="RecoveryOrchestrator.NotActionable",
        ),
        # ── Scenario 12: AskConfirmation for detailed calendar input ──
        TestCase(
            "__orchtest:create_calendar_event:book a meetup at noon tomorrow",
            expect_intent="",
            expect_log_contains="RecoveryOrchestrator.AskConfirmation: intent=create_calendar_event",
        ),
        # ── Scenario 9: AskConfirmation with set up verb ──
        TestCase(
            "__orchtest:create_calendar_event:set up a morning huddle for 9am Monday",
            expect_intent="",
            expect_log_contains="RecoveryOrchestrator.AskConfirmation: intent=create_calendar_event",
        ),
        # ── Scenario 3 (xfail): High-risk sms (no extractor) ──
        TestCase(
            "__orchtest:send_sms:ping mum that I am on my way",
            expect_intent="",
            expect_log_contains="RecoveryOrchestrator.NotActionable",
            xfail=True,
        ),
        TestCase(
            "__orchtest:save_memory:remember that I parked on level 3",
            expect_intent="",
            expect_log_contains="RecoveryOrchestrator.NotActionable",
            xfail=True,
        ),
    ]),
]
# Flat list built from phases — preserves backward compatibility with any code
# that iterates TEST_CASES directly (dry-run, summary table, etc.)
TEST_CASES: list[TestCase] = [tc for _, tcs in PHASES for tc in tcs]
#   - Wikipedia / system-info queries are read-only and low risk.
#   - Avoid ambiguous anaphora ("those", "that", "it") — no prior context.
#   - Avoid prompts that sound like reminders or tasks (those should
#     route to add_reminder/calendar, not save_memory).
#   - expected_fields only when the tool schema actually accepts them.
#     get_system_info takes no request parameters, so skip field asserts.
# ────────────────────────────────────────────────────────────────────────
LLM_TOOLS_CASES: list[LLMToolsTestCase] = [
    LLMToolsTestCase(
        name="query_wikipedia_natural",
        message="Look up the history of the Battle of Hastings on Wikipedia for me",
        expected_top_level_tool="query_wikipedia",
        expected_fields={"query": "Battle of Hastings"},
        expect_no_regex_match=True,
        expect_no_classifier_match=True,
        expected_result_mode="direct_reply",
    ),
    LLMToolsTestCase(
        name="save_memory_durable_fact",
        # Must NOT use "remember that...", "save that...", "note to self..." — all
        # trigger MiniLM's save_memory training phrases at confidence >=0.85 threshold.
        # Unusual phrasing forces fallthrough (bestGuess=save_memory, confidence <0.85).
        message="Here is a lasting fact I want you to know: my preferred dry cleaner is Star Dry Cleaning",
        expected_top_level_tool="save_memory",
        expected_fields={"content": "preferred dry cleaner"},
        expect_no_regex_match=True,
        expect_no_classifier_match=True,
        expected_result_mode="success",
    ),
    LLMToolsTestCase(
        name="get_system_info_natural",
        # Must avoid "battery" (triggers get_battery regex), "storage"/"ram"/"memory"
        # (triggers get_system_info regex), and all 32 MiniLM intents.
        message="Can you tell me the specs of this phone?",
        expected_top_level_tool="get_system_info",
        expected_fields=None,
        expect_no_regex_match=True,
        expect_no_classifier_match=True,
        expected_result_mode="direct_reply",
    ),
]



# ── LLM tools harness ─────────────────────────────────────────────────────────


def _parse_tool_marker(marker: str | None) -> dict[str, str]:
    """Parse a key=value-style marker string into a dict.
    Also handles request=<json> by parsing the JSON and merging its
    top-level string keys into the result dict, so field assertions
    (e.g. expected_fields={"query": "Battle of Hastings"}) work
    against the native tool marker's JSON request payload.
    For legacy raw-text markers (raw=<|tool_call>call:<tool>{...}):
    extracts the tool name and merges JSON fields from the raw text
    so the same assertion logic works for both paths.
    """
    if not marker:
        return {}
    result: dict[str, str] = {}
    for kv in re.finditer(r"(\w+)=((?:(?!\s+\w+=).)+)", marker):
        result[kv.group(1)] = kv.group(2)
    # If there's a request=<json> field, merge its top-level string values
    if "request" in result:
        try:
            parsed = json.loads(result["request"])
            if isinstance(parsed, dict):
                for k, v in parsed.items():
                    if isinstance(v, str):
                        result[k] = v
        except (json.JSONDecodeError, TypeError):
            pass
    # Legacy raw-text marker: extract tool name and merge JSON from
    # the raw content (format: <|tool_call>call:<tool>{<json>})
    if "raw" in result and "tool" not in result:
        raw = result["raw"]
        # Extract tool name: call:<toolName>{
        tool_m = re.search(r"call:(\w+)\{", raw)
        if tool_m:
            result["tool"] = tool_m.group(1)
        # Extract key=value fields from Gemma-4 tool call format:
        #   <key>:<|"|><value><|"|>
        # JSON parsing won't work because keys are unquoted.
        for fv in re.finditer(r"(\w+):<\\?\|\\?\"\\?\|>(.+?)<\\?\|\\?\"\\?\|>", raw):
            result[fv.group(1)] = fv.group(2)
    return result

def _poll_for_all_markers(
    patterns: dict[str, re.Pattern[str]],
    timeout: float = 120,
    poll_interval: float = 2.0,
) -> tuple[dict[str, str | None], str]:
    """Poll logcat for multiple marker patterns simultaneously.
    Accumulates a single log buffer across all poll iterations and searches
    all patterns against it. Also taps the screen periodically to keep the
    app foregrounded (required by Android 15+ foreground service constraint).
    Returns (results, accumulated_log) where results maps each pattern key
    to the first match content (or None), and accumulated_log is the full
    accumulated snapshot for additional searches (retry, slot-fill, etc.).
    """
    deadline = time.time() + timeout
    accumulated = ""
    results: dict[str, str | None] = {k: None for k in patterns}
    while time.time() < deadline:
        time.sleep(poll_interval)
        accumulated += "\n" + read_logcat_all()
        # Search all unfound patterns against the full accumulated log
        for key, pat in patterns.items():
            if results[key] is not None:
                continue
            m = pat.search(accumulated)
            if m:
                results[key] = m.group(1).strip() if m.lastindex else m.group(0).strip()
        # Keep screen on and app foregrounded (Android 15+ foreground service)
        run_adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        time.sleep(0.1)
        run_adb("shell", "input", "tap", "500", "1000")
        # Early exit if all markers found
        if all(v is not None for v in results.values()):
            break
    return results, accumulated
def _clear_conversation() -> None:
    """Force-stop the app to clear conversation state and model caches."""
    run_adb("shell", "am", "force-stop", PACKAGE)
    time.sleep(1)


def run_llm_tools(dry_run: bool = False) -> int:
    """Execute the llm_tools harness phase. Returns non-zero on failures.

    Requires runtime marker emission in the app code (ChatViewModel,
    NativeIntentHandler, and the tool-call path must log
    ``llm_tools_route``, ``llm_tools_native_tool``, ``llm_tools_legacy_tool``,
    ``llm_tools_skill_result``, and ``llm_tools_message_toolcall_saved``).
    Without these markers the harness will fail every case.

    This runner is separate from run_tests() because it has different data models,
    observability requirements, and state management (conversation isolation per case).
    """
    if dry_run:
        print("=" * 70)
        print("  LLM TOOLS E2E — DRY RUN (no device interaction)")
        print("=" * 70)
        print()
        for i, tc in enumerate(LLM_TOOLS_CASES, 1):
            print(f"  [{i:2d}] {tc.name}: \"{tc.message}\"")
            print(f"       expected → {tc.expected_top_level_tool}"
                  f"{f' (nested: {tc.expected_nested_intent})' if tc.expected_nested_intent else ''}")
            if tc.expected_fields:
                print(f"       fields   → {tc.expected_fields}")
            print(f"       expect: no_regex_match={tc.expect_no_regex_match}"
                  f" no_classifier={tc.expect_no_classifier_match}"
                  f" no_slot_fill={tc.expect_no_slot_fill}"
                  f" no_retry={tc.expect_no_retry}")
        print()
        print(f"  Total: {len(LLM_TOOLS_CASES)} test cases")
        print("=" * 70)
        return 0

    if not os.path.isfile(ADB):
        print(f"ERROR: ADB not found at {ADB}", file=sys.stderr)
        return 1

    print("=" * 70)
    print("  LLM TOOLS E2E TEST")
    print("=" * 70)
    # Start host-side logcat streaming (required for all read_logcat_all() calls below)
    logcat_start()

    # Preflight: prove model stack ready and MiniLM ready.
    # Dismiss any notification overlays first (Samsung Calendar, etc.)
    dismiss_notifications()
    # Force-stop to ensure clean process state before warmup
    run_adb("shell", "am", "force-stop", PACKAGE)
    time.sleep(1)
    run_adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    run_adb("shell", "am", "start", "-n", ACTIVITY)
    time.sleep(3)
    clear_logcat()

    # Warmup probe: send a deterministic query so the model stack initializes.
    # Keep app in foreground until routing completes (Android 15+ constraint).
    # _keep_foreground_until_inference_starts() already proves the model stack is ready
    # by detecting OrchTest: or InferenceGenerationService markers.
    run_adb("shell", "am", "start", "-n", ACTIVITY, "--es", "chat_input", shlex.quote("what time is it"))
    _keep_foreground_until_inference_starts()

    # MiniLM readiness check: send a prompt that exercises MiniLM, wait for classifier result.
    # _keep_foreground_until_inference_starts() already proves routing works.
    print("  [preflight] Proving MiniLM ready ...", end=" ", flush=True)
    clear_logcat()
    run_adb("shell", "am", "force-stop", PACKAGE)
    time.sleep(1)
    run_adb("shell", "am", "start", "-n", ACTIVITY, "--es", "chat_input", shlex.quote("what time is it"))
    _keep_foreground_until_inference_starts()
    print("ready")
    # Pre-run cleanup
    print("  [preflight] Cleaning up timers/alarms ...", end=" ", flush=True)
    for pkg in ("com.sec.android.app.clockpackage", "com.android.deskclock", "com.google.android.deskclock"):
        run_adb("shell", "am", "force-stop", pkg)
    cleanup_side_effects()
    print("done")
    clear_logcat()
    time.sleep(WAIT_SECONDS)
    clear_logcat()
    time.sleep(1)
    print()

    # Run each golden prompt in isolation
    results: list[LLMToolsResult] = []
    total = len(LLM_TOOLS_CASES)
    failures = 0

    for idx, tc in enumerate(LLM_TOOLS_CASES, 1):
        print(f"  [{idx:2d}/{total}] {tc.name}: \"{tc.message}\" ...", end=" ", flush=True)

        # Isolate: force-stop, dismiss overlays, then send prompt
        _clear_conversation()
        # Dismiss any notification overlays (Samsung Calendar, etc.)
        run_adb("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(0.3)
        run_adb("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(0.3)
        clear_logcat()
        time.sleep(0.5)

        # Send the prompt (foreground keepalive handled by _poll_for_all_markers)
        send_text(tc.message, wait_for_inference=False)
        # Poll for all markers simultaneously from a single accumulated buffer,
        # avoiding the log-draining bug where sequential _poll_for_marker calls
        # consume markers that arrived together.
        patterns = {
            "route": LLM_TOOLS_ROUTE_PATTERN,
            "native_tool": LLM_TOOLS_NATIVE_TOOL_PATTERN,
            "legacy_tool": LLM_TOOLS_LEGACY_TOOL_PATTERN,
            "skill_result": LLM_TOOLS_SKILL_RESULT_PATTERN,
            "message_saved": LLM_TOOLS_MESSAGE_SAVED_PATTERN,
        }
        markers, final_log = _poll_for_all_markers(patterns, timeout=120)
        route_marker = markers["route"]
        native_tool = markers["native_tool"]
        legacy_tool = markers["legacy_tool"]
        skill_result = markers["skill_result"]
        message_saved = markers["message_saved"]
        # Check for slot-fill and retry markers (from the same accumulated log)
        retry_seen = bool(LLM_TOOLS_RETRY_PATTERN.search(final_log))
        slot_fill_seen = bool(LLM_TOOLS_SLOT_FILL_PATTERN.search(final_log))
        # Extract tool info from markers
        native_data = _parse_tool_marker(native_tool)
        legacy_data = _parse_tool_marker(legacy_tool)
        actual_top_level = native_data.get("tool") or legacy_data.get("tool")
        actual_nested = native_data.get("nested_intent") or legacy_data.get("nested_intent")

        # Extract chip text from logcat (stable diagnostic logging from ChatViewModel)
        chip_match = re.search(r"tool_chip_visible:\s*(\S+)", final_log)
        chip_text = chip_match.group(1) if chip_match else None
        if not chip_text:
            failures_list.append("No tool_chip_visible marker found")

        # Extract reply
        reply_text = extract_reply(final_log)

        # Build assertion failures
        failures_list: list[str] = []

        # Tool name check
        if actual_top_level != tc.expected_top_level_tool:
            failures_list.append(
                f"tool name: expected {tc.expected_top_level_tool!r}, got {actual_top_level!r}"
            )

        # Nested intent check
        if tc.expected_nested_intent and actual_nested != tc.expected_nested_intent:
            failures_list.append(
                f"nested intent: expected {tc.expected_nested_intent!r}, got {actual_nested!r}"
            )

        # Field checks
        if tc.expected_fields:
            merged_data = {**native_data, **legacy_data}
            for k, v in tc.expected_fields.items():
                actual_v = merged_data.get(k)
                if actual_v is None:
                    failures_list.append(f"field {k!r}: missing")
                elif v.lower() not in actual_v.lower() and actual_v.lower() not in v.lower():
                    failures_list.append(f"field {k!r}: expected {v!r}, got {actual_v!r}")

        # Negative checks
        if tc.expect_no_regex_match and "NativeIntentHandler.handle" in final_log:
            # Check if it appeared before the tool-call marker
            regex_pos = final_log.find("NativeIntentHandler.handle")
            tool_positions = [p for p in (
                final_log.find("llm_tools_native_tool"),
                final_log.find("llm_tools_legacy_tool"),
            ) if p != -1]
            tool_pos = min(tool_positions) if tool_positions else -1
            if tool_pos == -1 or regex_pos < tool_pos:
                failures_list.append("QIR regex matched before Gemma tool-call")

        if tc.expect_no_classifier_match and "ClassifierMatch" in final_log:
            failures_list.append("ClassifierMatch before Gemma generation")

        if tc.expect_no_slot_fill and slot_fill_seen:
            failures_list.append("Slot-fill path triggered before Gemma")

        if tc.expect_no_retry and retry_seen:
            failures_list.append("Model retry observed (raw_tool_call_retry_succeeded / hallucination_retry_succeeded)")

        # Positive checks
        if not route_marker:
            failures_list.append("No route-decision marker found")

        if not (native_tool or legacy_tool):
            failures_list.append("No native-tool or legacy-tool marker found")

        if not message_saved:
            failures_list.append("No ChatMessage.toolCall persistence marker found")
        if (native_tool or legacy_tool) and not skill_result:
            failures_list.append("No skill_result marker found")

        # Result mode check
        if tc.expected_result_mode != "unknown":
            skill_data = _parse_tool_marker(skill_result)
            mode = skill_data.get("mode", "unknown")
            if mode != tc.expected_result_mode:
                failures_list.append(f"result mode: expected {tc.expected_result_mode!r}, got {mode!r}")

        passed = len(failures_list) == 0
        result = LLMToolsResult(
            index=idx,
            name=tc.name,
            message=tc.message,
            expected_top_level_tool=tc.expected_top_level_tool,
            actual_top_level_tool=actual_top_level,
            actual_nested_intent=actual_nested,
            route_marker=route_marker,
            native_tool_marker=native_tool,
            legacy_tool_marker=legacy_tool,
            skill_result_marker=skill_result,
            message_saved_marker=message_saved,
            retry_seen=retry_seen,
            slot_fill_seen=slot_fill_seen,
            chip_text=chip_text,
            reply_text=reply_text,
            passed=passed,
            failures=failures_list,
        )
        results.append(result)

        if passed:
            print("✓")
        else:
            failures += 1
            print(f"✗ ({'; '.join(failures_list)})")

        # Brief pause between cases
        time.sleep(2)

    # Summary
    print()
    print("-" * 70)
    print(f"  {'#':>3}  {'RESULT':>6}  {'EXPECTED':<24}  {'ACTUAL':<24}  {'NAME':<30}")
    print("-" * 70)
    for r in results:
        icon = "  ✓" if r.passed else "  ✗"
        actual = r.actual_top_level_tool or "NO_MATCH"
        nested = f" (nested: {r.actual_nested_intent})" if r.actual_nested_intent else ""
        print(f"  {r.index:3d}  {icon:>6}  {r.expected_top_level_tool:<24}  {actual + nested:<24}  \"{r.message}\"")
    print("-" * 70)
    print(f"  PASSED: {total - failures}/{total}  FAILED: {failures}/{total}")
    print("=" * 70)

    # Save report
    run_ts = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H-%M-%SZ")
    report_path = save_llm_tools_report(results, elapsed=0, partial=False, run_ts=run_ts)
    print(f"  Report saved → {report_path}")

    return 1 if failures > 0 else 0
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


# ===================================================================
# Host-side logcat streaming (#1102)
#
# Long-running logcat process on the host side avoids the S21's 5MB logcat
# buffer rotation problem. Instead of polling `adb logcat -d` (which dumps
# the ring buffer and loses early entries in long runs), we keep a persistent
# `adb logcat` subprocess whose stdout is continuously buffered on the host.
#
# Callers use `logcat_snapshot()` to atomically drain the accumulated output
# since the last snapshot (or since the stream started).
# ===================================================================

import subprocess
import atexit
import threading

_logcat_proc: subprocess.Popen | None = None
_logcat_buffer: list[str] = []
_logcat_lock = threading.Lock()


def _logcat_reader() -> None:
    """Read lines from the persistent logcat subprocess and buffer them."""
    global _logcat_proc, _logcat_buffer
    assert _logcat_proc is not None
    assert _logcat_proc.stdout is not None
    for line in _logcat_proc.stdout:
        with _logcat_lock:
            _logcat_buffer.append(line.rstrip("\n"))


def logcat_start() -> None:
    """Start the persistent logcat stream. Safe to call multiple times (idempotent).
    Filters to KernelAI:D and LiteRtInferenceEngine:I so profile warmup and
    orchestration tests both work from the same stream."""
    global _logcat_proc
    if _logcat_proc is not None:
        return
    # Clear device-side buffer first, then start streaming
    run_adb("logcat", "-c")
    _logcat_proc = subprocess.Popen(
        [ADB, "logcat", "-s", f"{LOGCAT_TAG}:D", "LiteRtInferenceEngine:I", "-v", "brief"],
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
        universal_newlines=True, bufsize=1,
    )
    reader = threading.Thread(target=_logcat_reader, daemon=True)
    reader.start()
    # Brief pause to let the stream establish
    time.sleep(0.5)
    # Clear again after stream starts to flush any initial noise
    run_adb("logcat", "-c")


def logcat_stop() -> None:
    """Stop the persistent logcat stream and drain remaining output."""
    global _logcat_proc, _logcat_buffer
    if _logcat_proc is not None:
        _logcat_proc.terminate()
        _logcat_proc.wait(timeout=5)
        _logcat_proc = None
    with _logcat_lock:
        _logcat_buffer.clear()


def logcat_snapshot() -> str:
    """Atomically drain the accumulated logcat buffer and return it as a string."""
    with _logcat_lock:
        result = "\n".join(_logcat_buffer)
        _logcat_buffer.clear()
    return result


def logcat_wait(expected: str, timeout: float = WAIT_SECONDS) -> str:
    """Poll the logcat buffer until [expected] appears, or timeout.
    Returns accumulated snapshot — evidence isn't lost on timeout."""
    deadline = time.time() + timeout
    seen = set()
    accumulated: list[str] = []
    while time.time() < deadline:
        snapshot = logcat_snapshot()
        if not snapshot:
            time.sleep(0.5)
            continue
        for line in snapshot.split("\n"):
            line = line.strip()
            if line and line not in seen:
                seen.add(line)
                accumulated.append(line)
        combined = "\n".join(accumulated)
        if expected in combined:
            return combined
        time.sleep(0.5)
    return "\n".join(accumulated)


atexit.register(logcat_stop)


# ---------------------------------------------------------------------------
# Backward-compatible wrappers — existing callers continue to work unchanged.
# Uses streaming buffer instead of `adb logcat -d` to avoid S21's 5MB logcat
# buffer rotation issue in long runs (#1102).
# ---------------------------------------------------------------------------

def clear_logcat() -> None:
    """Clear the logcat buffer. Drains the streaming buffer and clears the device ring buffer."""
    logcat_snapshot()  # Drain any accumulated output
    run_adb("logcat", "-c")


def read_logcat() -> str:
    """Return accumulated KernelAI logcat output since last clear."""
    return logcat_snapshot()


def read_logcat_all() -> str:
    """Return accumulated logcat (KernelAI + LiteRtInferenceEngine) since last clear.
    Note: with streaming, this returns the same snapshot as read_logcat() since
    the stream is filtered by tag. For LiteRtInferenceEngine logs, callers that
    specifically need a wider tag filter should use logcat_snapshot() directly
    after starting a stream with the appropriate filter."""
    return logcat_snapshot()


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


def _keep_foreground_until_inference_starts() -> None:
    """Keep the app in the foreground by tapping the screen periodically.
    On Android 15+, InferenceGenerationService.startForegroundService() must be
    called within ~5 seconds of the app becoming foreground. This function keeps
    the activity visible until the inference service starts (detected via
    InferenceGenerationService log or NativeIntentHandler route marker).
    Taps every 2 seconds for up to 120 seconds.
    """
    deadline = time.time() + 120
    while time.time() < deadline:
        log = read_logcat_all()
        if "InferenceGenerationService" in log or "InferenceLoadingService" in log or "initEngineWhenReady" in log or "llm_tools_route:" in log or "OrchTest:" in log:
            break
        run_adb("shell", "input", "tap", "500", "1000")
        time.sleep(2)


def send_text(text: str, wait_for_inference: bool = True) -> None:
    """Deliver chat_input extra via onNewIntent — navigates to chat from any screen.

    On Android 15+, InferenceGenerationService.startForegroundService() must be called
    within ~5 seconds of the app becoming foreground. After sending the prompt we keep
    the activity visible (touch screen periodically) so the service start remains valid
    until inference completes (typically 30-60s for Gemma-4 E-4B).
    """
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
    if wait_for_inference:
        _keep_foreground_until_inference_starts()


def send_quick_action(text: str) -> None:
    """Deliver quick_action_input extra — navigates to Actions tab and calls executeAction().

    Used to drive slot-fill tests: bare queries (e.g. "set an alarm") route through
    ActionsViewModel → QIR → NeedsSlot → navigate to Chat with slot prompt.
    """
    run_adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    time.sleep(0.3)
    run_adb(
        "shell",
        "am",
        "start",
        "--activity-clear-top",
        "--activity-single-top",
        "-n",
        ACTIVITY,
        "--es",
        "quick_action_input",
        shlex.quote(text),
    )


def send_slot_reply(text: str) -> None:
    """Deliver slot_reply_input extra via onNewIntent → ActionsViewModel.onSlotReply().

    Used for the second turn of a slot-fill test: after send_quick_action triggers NeedsSlot
    and the ModalBottomSheet is shown, this delivers the user's answer directly to the
    ActionsViewModel without navigating away from the Actions tab.
    """
    run_adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    time.sleep(0.3)
    run_adb(
        "shell",
        "am",
        "start",
        "--activity-clear-top",
        "--activity-single-top",
        "-n",
        ACTIVITY,
        "--es",
        "slot_reply_input",
        shlex.quote(text),
    )



ALIAS_TEST_NAME = "zippy"       # test alias → resolves to Voicemail contact
ALIAS_DISPLAY_NAME = "Voicemail"  # must match a real contact on the device

DB_PATH = f"/data/data/{PACKAGE}/databases/kernel_ai.db"


VOICEMAIL_NUMBER = "121"         # provider voicemail shortcode
def setup_contact_alias_fixture() -> bool:
    """Insert test alias 'zippy' → Voicemail into Room contact_aliases table."""
    try:
        run_adb(
            "shell", "sqlite3", DB_PATH,
            f"INSERT OR REPLACE INTO contact_aliases (alias, displayName, contactId, phoneNumber) "
            f"VALUES ('{ALIAS_TEST_NAME}', '{ALIAS_DISPLAY_NAME}', '0', '{VOICEMAIL_NUMBER}');",
        )
        return True
    except Exception:
        return False


def teardown_contact_alias_fixture() -> None:
    """Remove test alias inserted during setup."""
    run_adb(
        "shell", "sqlite3", DB_PATH,
        f"DELETE FROM contact_aliases WHERE alias='{ALIAS_TEST_NAME}';",
    )


def dismiss_notifications() -> None:
    """Dismiss any notification popups or alerts that may block the app from being in the foreground.
    On Android 15+ Samsung devices, notification popups (e.g. Calendar alerts) can cover
    the activity and prevent startForegroundService() from succeeding. This function
    presses the back button to dismiss overlays, then brings the app to the foreground.
    """
    # Press back to dismiss any overlay/notification popup
    run_adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(0.5)
    run_adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(0.5)
    # Bring app to foreground
    run_adb("shell", "am", "start", "-n", ACTIVITY)
    time.sleep(1)


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


def save_llm_tools_report(
    results: list[LLMToolsResult],
    elapsed: float = 0.0,
    partial: bool = False,
    run_ts: str | None = None,
) -> Path:
    """Serialise llm_tools results to a JSON report.
    This is separate from save_report() because LLMToolsResult has a
    different schema (no intent_passed/params_passed/xfail fields).
    """
    REPORTS_DIR.mkdir(parents=True, exist_ok=True)
    ts = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H-%M-%SZ")
    if partial and run_ts:
        report_path = REPORTS_DIR / f"{run_ts}_llm_tools_partial.json"
        status = "in_progress"
    else:
        report_path = REPORTS_DIR / f"{ts}_llm_tools.json"
        status = "complete"
        if run_ts:
            partial_file = REPORTS_DIR / f"{run_ts}_llm_tools_partial.json"
            partial_file.unlink(missing_ok=True)
    total = len(results)
    passed = sum(1 for r in results if r.passed)
    failed = total - passed
    report = {
        "suite": "llm_tools",
        "status": status,
        "timestamp": ts,
        "elapsed_seconds": round(elapsed, 1),
        "summary": {
            "total": total,
            "passed": passed,
            "failed": failed,
        },
        "results": [
            {
                "index": r.index,
                "name": r.name,
                "message": r.message,
                "expected_top_level_tool": r.expected_top_level_tool,
                "actual_top_level_tool": r.actual_top_level_tool,
                "actual_nested_intent": r.actual_nested_intent,
                "route_marker": r.route_marker,
                "native_tool_marker": r.native_tool_marker,
                "legacy_tool_marker": r.legacy_tool_marker,
                "skill_result_marker": r.skill_result_marker,
                "message_saved_marker": r.message_saved_marker,
                "retry_seen": r.retry_seen,
                "slot_fill_seen": r.slot_fill_seen,
                "chip_text": r.chip_text,
                "reply_text": r.reply_text,
                "passed": r.passed,
                "failures": r.failures,
            }
            for r in results
        ],
    }
    report_path.write_text(json.dumps(report, indent=2))
    return report_path
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
    passed = sum(1 for r in results if r.intent_passed and r.params_passed and not r.xfail and not r.log_check_warn)
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
                "log_check_warn": r.log_check_warn,
                "phase": r.phase,
                "status": (
                    "xfail" if r.xfail and not r.intent_passed
                    else "pass" if r.intent_passed and r.params_passed and not r.log_check_warn
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


# Minimum consecutive same-actual-intent results required to trigger the OOM warning.
_OOM_RUN_THRESHOLD = 5


def check_oom_sanity(results: list[TestResult]) -> None:
    """Warn if a long consecutive run of tests all return the same actual intent while their
    *expected* intents differ — a strong signal that the model has hung or OOM'd and is
    returning a stuck response.

    Deliberately does NOT warn when the expected intents within the run are all the same
    (e.g. the weather phase where every test correctly maps to get_weather), because that
    is valid behaviour, not a stuck model.  Closes #563.
    """
    i = 0
    warned = False
    while i < len(results):
        run_actual = results[i].actual_intent
        j = i
        while j < len(results) and results[j].actual_intent == run_actual:
            j += 1
        run = results[i:j]
        if len(run) >= _OOM_RUN_THRESHOLD:
            expected_in_run = {r.expect_intent for r in run}
            # Only suspicious when expected intents VARY but actual is stuck on one value.
            if len(expected_in_run) > 1:
                if not warned:
                    print("\n  OOM / MODEL-HANG SANITY CHECK")
                    print("  " + "-" * 68)
                    warned = True
                label = run_actual if run_actual else "NO_MATCH"
                print(
                    f"\n  ⚠️  Possible OOM/hang: tests {run[0].index}–{run[-1].index} "
                    f"({len(run)} consecutive) all returned {label!r} "
                    f"but expected {len(expected_in_run)} distinct intents "
                    f"({', '.join(sorted(expected_in_run))})."
                )
                print("     Consider restarting the app and re-running this range.")
        i = j
    if warned:
        print()


def run_tests(dry_run: bool = False, post_pr: bool = False, start_phase: str | None = None, phases: list[str] | None = None) -> int:
    """Execute all test cases. Returns non-zero on failures."""
    # NB: logcat_start() is deliberately after the dry-run / ADB-existence checks below,
    # because it touches the device — --dry-run must be a pure no-op without a device.

    if dry_run:
        print("=" * 70)
        print("  ADB SKILL TEST — DRY RUN (no device interaction)")
        print("=" * 70)
        print()
        # Resolve which test cases to show — respects --phases filter.
        phase_names_dr = [name for name, _ in PHASES]
        selected_dr: set[int] | None = None
        if phases is not None:
            selected_dr = set()
            for token in phases:
                token = token.strip()
                if token.isdigit():
                    n = int(token)
                    if not (1 <= n <= len(PHASES)):
                        print(f"ERROR: --phases {token!r} out of range (1–{len(PHASES)}).", file=sys.stderr)
                        return 1
                    selected_dr.add(n - 1)
                else:
                    if token not in phase_names_dr:
                        print(f"ERROR: --phases {token!r} not recognised. Valid: {', '.join(phase_names_dr)}", file=sys.stderr)
                        return 1
                    selected_dr.add(phase_names_dr.index(token))
            selected_names_dr = [phase_names_dr[i] for i in sorted(selected_dr)]
            print(f"  ── Showing phases: {', '.join(selected_names_dr)} ──")
            print()
        dry_cases = [
            tc for phase_idx, (_, phase_cases) in enumerate(PHASES)
            for tc in phase_cases
            if selected_dr is None or phase_idx in selected_dr
        ]
        for i, tc in enumerate(dry_cases, 1):
            print(f"  [{i:2d}] \"{tc.message}\"")
            suffix_parts: list[str] = []
            if tc.expect_intent:
                suffix_parts.append(f"expect → {tc.expect_intent}")
            if tc.expect_reply_contains:
                suffix_parts.append(f"reply_contains={tc.expect_reply_contains!r}")
            if tc.expect_log_contains:
                suffix_parts.append(f"log_contains={tc.expect_log_contains!r}")
            if tc.slot_reply:
                suffix_parts.append(f"slot_reply={tc.slot_reply!r}")
            if tc.confirm_reply:
                suffix_parts.append(f"confirm_reply={tc.confirm_reply!r}")
            if suffix_parts:
                print(f"       {' | '.join(suffix_parts)}")
        print()
        print(f"  Total: {len(dry_cases)} test cases")
        print("=" * 70)
        return 0

    if not os.path.isfile(ADB):
        print(f"ERROR: ADB not found at {ADB}", file=sys.stderr)
        return 1

    # Start host-side logcat streaming (#1102) — avoids S21 buffer rotation failures.
    logcat_start()

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

    # Warm up: send a dummy query to trigger model load, wait for NativeIntentHandler to fire.
    # Cold starts (or post-OOM reloads) can take 90-120s; poll for 120s before giving up.
    print("  [init] Warming up model (this takes ~30s on first run) ...", end=" ", flush=True)
    run_adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    run_adb("shell", "am", "start", "-n", ACTIVITY)
    time.sleep(3)
    clear_logcat()
    run_adb("shell", "am", "start", "-n", ACTIVITY, "--es", "chat_input", shlex.quote("what time is it"))
    # Poll logcat until NativeIntentHandler fires (model loaded + QIR dispatched) or 120s timeout
    deadline = time.time() + 120
    warmed = False
    while time.time() < deadline:
        time.sleep(2)
        log = read_logcat()
        if "NativeIntentHandler.handle" in log:
            warmed = True
            break
    if not warmed:
        # Model may still be loading (e.g. post-OOM reload). Send a second probe and
        # wait an additional 30s before giving up entirely.
        print("no response yet — sending second warmup probe ...", end=" ", flush=True)
        clear_logcat()
        run_adb("shell", "am", "start", "-n", ACTIVITY, "--es", "chat_input", shlex.quote("what time is it"))
        deadline2 = time.time() + 30
        while time.time() < deadline2:
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

    # ── Orchestrator warmup: wait for MiniLM phrase vectors to be fully built ──
    # Clear logcat first so we don't match a stale "Ready:" from a previous app run.
    clear_logcat()
    print("  [init] Warming up MiniLM classifier (up to 120s) ...", end=" ", flush=True)
    deadline = time.time() + 120
    warmed_ml = False
    while time.time() < deadline:
        time.sleep(3)
        log = run_adb("logcat", "-d", "-s", "MiniLMIntentClassifier:*")
        if "Ready:" in log:
            warmed_ml = True
            break
    print("ready" if warmed_ml else "timeout (proceeding anyway)")

    # Flush any logcat residue from the cleanup intents before starting tests.
    time.sleep(WAIT_SECONDS)
    clear_logcat()
    time.sleep(1)
    print()

    phase_names = [name for name, _ in PHASES]

    # Resolve --phases: comma-separated list of phase names or 1-based numbers.
    # Builds a set of 0-based indices to include.
    selected_phase_indices: set[int] | None = None  # None = all phases
    if phases is not None:
        selected_phase_indices = set()
        for token in phases:
            token = token.strip()
            if token.isdigit():
                n = int(token)
                if not (1 <= n <= len(PHASES)):
                    print(
                        f"ERROR: --phases {token!r} out of range "
                        f"(1–{len(PHASES)}). Valid phases: {', '.join(f'{i+1}={n}' for i, (n, _) in enumerate(PHASES))}",
                        file=sys.stderr,
                    )
                    return 1
                selected_phase_indices.add(n - 1)
            else:
                if token not in phase_names:
                    print(
                        f"ERROR: --phases {token!r} not recognised. "
                        f"Valid phases: {', '.join(phase_names)}",
                        file=sys.stderr,
                    )
                    return 1
                selected_phase_indices.add(phase_names.index(token))
        selected_names = [phase_names[i] for i in sorted(selected_phase_indices)]
        print(f"  ── Running selected phases: {', '.join(selected_names)} ──")
        print()

    # Resolve --start-phase: accept a phase name or 1-based number.
    start_phase_idx = 0  # 0 = run all phases (0-based offset into PHASES)
    if start_phase is not None:
        if start_phase.isdigit():
            n = int(start_phase)
            if not (1 <= n <= len(PHASES)):
                print(
                    f"ERROR: --start-phase {start_phase!r} out of range "
                    f"(1–{len(PHASES)}; valid names: {', '.join(phase_names)})",
                    file=sys.stderr,
                )
                return 1
            start_phase_idx = n - 1
        else:
            if start_phase not in phase_names:
                print(
                    f"ERROR: --start-phase {start_phase!r} not found. "
                    f"Valid phases: {', '.join(phase_names)}",
                    file=sys.stderr,
                )
                return 1
            start_phase_idx = phase_names.index(start_phase)
        skipped = sum(len(cases) for _, cases in PHASES[:start_phase_idx])
        print(f"  ── Resuming from phase {start_phase_idx + 1}/{len(PHASES)}"
              f" ({PHASES[start_phase_idx][0]}) — skipping first {skipped} tests ──")
        print()


    run_ts = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H-%M-%SZ")
    suite_start = time.time()
    results: list[TestResult] = []
    # global_index counts only the tests that will actually run.
    # For a full run it starts at 1; for --start-phase it starts after skipped tests.
    global_index = sum(len(cases) for _, cases in PHASES[:start_phase_idx]) + 1
    total_tests = (
        sum(len(cases) for i, (_, cases) in enumerate(PHASES) if i in selected_phase_indices)
        if selected_phase_indices is not None
        else len(TEST_CASES)
    )

    for phase_num, (phase_name, phase_cases) in enumerate(PHASES, 1):
        if phase_num <= start_phase_idx:
            continue  # skip phases before the requested start
        if selected_phase_indices is not None and (phase_num - 1) not in selected_phase_indices:
            continue  # skip phases not in --phases selection

        phase_start = time.time()
        phase_results: list[TestResult] = []

        for tc in phase_cases:
            print(f"  [{global_index:3d}/{total_tests}] \"{tc.message}\" ...", end=" ", flush=True)

            clear_logcat()
            time.sleep(0.5)  # Brief pause to ensure logcat clear is flushed before sending
            first_turn_warn: str | None = None

            if tc.slot_reply is not None:
                # Slot-fill test: two-turn flow
                # Turn 1: bare query via quick_action_input → NeedsSlot → ModalBottomSheet
                send_quick_action(tc.message)
                time.sleep(WAIT_SECONDS)
                # Turn 2: slot reply via slot_reply_input → ActionsViewModel.onSlotReply() → intent fires
                clear_logcat()
                time.sleep(0.5)
                send_slot_reply(tc.slot_reply)
            elif tc.confirm_reply is not None:
                # Confirmation test: two-turn flow (orchestrator AskConfirmation → user confirms)
                # Turn 1: query via chat_input → ChatViewModel → OrchTest override → orchestrator AskConfirmation
                clear_logcat()
                time.sleep(0.5)
                send_text(tc.message)
                time.sleep(WAIT_SECONDS)
                if tc.expect_log_contains is not None:
                    logcat1 = read_logcat()
                    log1_found = tc.expect_log_contains in logcat1
                    if not log1_found:
                        first_turn_warn = f"AskConfirmation not found (expected {tc.expect_log_contains!r})"
                # Turn 2: confirmation reply via chat_input → pending confirmation → skill executes
                clear_logcat()
                time.sleep(0.5)
                send_text(tc.confirm_reply)
            else:
                send_text(tc.message)

            # Early-exit wait: poll for expected signal instead of fixed WAIT_SECONDS (#1102)
            signal = tc.expect_log_contains or tc.expect_intent
            logcat = logcat_wait(signal, WAIT_SECONDS) if signal else (time.sleep(WAIT_SECONDS) or read_logcat())
            actual_intent, actual_params = extract_intent(logcat)
            intent_passed = (actual_intent or "") == tc.expect_intent
            params_ok, param_failures = check_params(tc.expect_params, actual_params)


            # DirectReply verification — best-effort, warn but don't fail the test
            reply_warn: str | None = None
            if intent_passed and tc.expect_reply_contains is not None:
                reply_text = extract_reply(logcat)
                if reply_text is None:
                    reply_warn = "no DirectReply logged"
                elif not re.search(tc.expect_reply_contains, reply_text):
                    reply_warn = f"reply {reply_text!r} didn't match {tc.expect_reply_contains!r}"
            # Logcat content check (for orchestrator paths that don't fire NativeIntentHandler)
            log_check_warn: str | None = None
            if tc.expect_log_contains is not None:
                if tc.expect_log_contains not in logcat:
                    log_check_warn = f"expected log '{tc.expect_log_contains}' not found"
            # Merge first-turn warning (e.g. AskConfirmation not found before confirm_reply)
            # into the final result so phase_results has exactly one entry per test.
            if first_turn_warn is not None:
                if log_check_warn is not None:
                    log_check_warn = first_turn_warn + "; " + log_check_warn
                else:
                    log_check_warn = first_turn_warn
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
                log_check_warn=log_check_warn,
                phase=phase_name,
            )
            phase_results.append(result)
            results.append(result)
            global_index += 1

            # Determine pass/fail display
            warnings = []
            if reply_warn: warnings.append(f"reply warn: {reply_warn}")
            if log_check_warn: warnings.append(log_check_warn)
            warn_suffix = f" [{'; '.join(warnings)}]" if warnings else ""

            if intent_passed and not log_check_warn:
                print("✓" + warn_suffix)
            elif tc.xfail:
                print("✗ (xfail — not yet implemented)")
            elif not intent_passed:
                print(f"✗ (got {actual_intent or 'NO_MATCH'})" + warn_suffix)
            else:
                print(f"✗ (params: {'; '.join(param_failures)})")

            # Hang up after call tests so they don't stay open
            if tc.expect_intent == "make_call":
                time.sleep(2)
                run_adb("shell", "input", "keyevent", "KEYCODE_ENDCALL")

        # ── Phase summary ──────────────────────────────────────────────────
        phase_elapsed = time.time() - phase_start
        n_pass  = sum(1 for r in phase_results if r.intent_passed and r.params_passed and not r.xfail and not r.log_check_warn)
        n_xfail = sum(1 for r in phase_results if r.xfail and not r.intent_passed)
        n_fail  = sum(1 for r in phase_results if not r.xfail and (not r.intent_passed or not r.params_passed or r.log_check_warn is not None))
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
    check_oom_sanity(results)
    report_path = save_report(
        results,
        suite="skills",
        elapsed=time.time() - suite_start,
        partial=False,
        run_ts=run_ts,
    )
    print(f"  Report saved → {report_path}")
    print()

    if post_pr:
        post_pr_comment(results, time.time() - suite_start, report_path)

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

    logcat_start()

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


def _fmt_elapsed(seconds: float) -> str:
    m, s = int(seconds) // 60, int(seconds) % 60
    return f"{m}m {s:02d}s" if m else f"{s}s"


def build_comment_markdown(
    results: list[TestResult],
    elapsed: float,
    report_path: Path,
) -> str:
    """Build a GitHub-flavoured markdown PR comment summarising the run."""
    total = len(results)
    passed = sum(1 for r in results if r.intent_passed and r.params_passed and not r.xfail)
    xfails = sum(1 for r in results if r.xfail and not r.intent_passed)
    failed = total - passed - xfails
    pass_rate = passed / max(total - xfails, 1) * 100

    lines: list[str] = [
        "## 🧪 Jandal Skill Regression Results",
        "",
        "| | Count |",
        "|---|---|",
        f"| ✅ Passed | {passed} |",
        f"| ❌ Failed | {failed} |",
        f"| ⚠️ Expected failures | {xfails} |",
        f"| **Total** | **{total}** |",
        "",
        f"**Pass rate: {pass_rate:.1f}%** • Run time: {_fmt_elapsed(elapsed)}",
    ]

    # Failed tests table (max 10 rows)
    failures = [
        r for r in results
        if not r.xfail and (not r.intent_passed or not r.params_passed)
    ]
    if failures:
        lines += [
            "",
            "### Failed tests",
            "| # | Input | Expected | Actual |",
            "|---|---|---|---|",
        ]
        for r in failures[:10]:
            actual = r.actual_intent or "NO_MATCH"
            lines.append(f"| {r.index} | {r.message} | `{r.expect_intent}` | `{actual}` |")
        if len(failures) > 10:
            lines.append(f"| … | *and {len(failures) - 10} more* | | |")

    # Phase breakdown (only when phase info is populated)
    phases_present = [r.phase for r in results if r.phase]
    if phases_present:
        from collections import defaultdict
        phase_data: dict[str, dict[str, float]] = defaultdict(
            lambda: {"pass": 0, "fail": 0, "xfail": 0, "time": 0.0}
        )
        for r in results:
            key = r.phase or "unknown"
            if r.xfail and not r.intent_passed:
                phase_data[key]["xfail"] += 1
            elif r.intent_passed and r.params_passed:
                phase_data[key]["pass"] += 1
            else:
                phase_data[key]["fail"] += 1

        lines += [
            "",
            "<details>",
            "<summary>Phase breakdown</summary>",
            "",
            "| Phase | Pass | Fail | XFail |",
            "|---|---|---|---|",
        ]
        for phase_name in dict.fromkeys(r.phase for r in results if r.phase):
            d = phase_data[phase_name]
            lines.append(
                f"| {phase_name} | {int(d['pass'])} | {int(d['fail'])} | {int(d['xfail'])} |"
            )
        lines += ["", "</details>"]

    dt = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    lines += [
        "",
        "---",
        f"*Report generated by [adb_skill_test.py](scripts/adb_skill_test.py) on {dt}*",
        f"*Full HTML report: `{report_path.name}`*",
    ]
    return "\n".join(lines)


def post_pr_comment(results: list[TestResult], elapsed: float, report_path: Path) -> None:
    """Post a markdown test summary to the open PR for the current branch."""
    body = build_comment_markdown(results, elapsed, report_path)
    result = subprocess.run(
        ["gh", "pr", "comment", "--body", body],
        capture_output=True, text=True, cwd=Path(__file__).parent.parent,
    )
    if result.returncode == 0:
        print("  [report] PR comment posted ✓")
    else:
        print(f"  [report] PR comment failed: {result.stderr.strip()}", file=sys.stderr)


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
    parser.add_argument(
        "--post-pr",
        action="store_true",
        help="Post a markdown test summary comment to the open PR for the current branch after the run.",
    )
    parser.add_argument(
        "--start-phase",
        metavar="PHASE",
        default=None,
        help=(
            "Skip all phases before PHASE and start testing from there. "
            "Accepts a phase name (e.g. 'system') or 1-based number (e.g. '8'). "
            f"Phases: {', '.join(f'{i+1}={n}' for i, (n, _) in enumerate(PHASES))}."
        ),
    )
    parser.add_argument(
        "--phases",
        metavar="PHASES",
        default=None,
        help=(
            "Run only the specified phases (comma-separated names or 1-based numbers). "
            "e.g. --phases weather  or  --phases 1,3,8  or  --phases alarm_timer,media. "
            "Also accepts 'llm_tools' for the LLM tool-call generation harness. "
            f"Mutually exclusive with --start-phase. "
            f"Phases: {', '.join(f'{i+1}={n}' for i, (n, _) in enumerate(PHASES))} + llm_tools."
        ),
    )
    args = parser.parse_args()

    if args.start_phase and args.phases:
        parser.error("--start-phase and --phases are mutually exclusive. Use one or the other.")

    phases_list = [p.strip() for p in args.phases.split(",")] if args.phases else None

    if args.profile:
        sys.exit(run_profile_tests(dry_run=args.dry_run))
    elif phases_list == ["llm_tools"]:
        sys.exit(run_llm_tools(dry_run=args.dry_run))
    else:
        sys.exit(run_tests(dry_run=args.dry_run, post_pr=args.post_pr, start_phase=args.start_phase, phases=phases_list))


if __name__ == "__main__":
    main()
