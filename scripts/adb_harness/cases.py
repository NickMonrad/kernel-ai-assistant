"""
ADB Skill Harness — test case definitions.

PHASES, LLM_TOOLS_CASES, PROFILE_TEST_CASES, and TEST_CASES with
metadata annotations applied.
"""

from __future__ import annotations

from adb_harness.models import ProfileTestCase, TestCase, LLMToolsTestCase
from adb_harness.selectors import annotate_phases


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


PHASES: list[tuple[str, list[TestCase]]] = [
    ("alarm_timer", [
        # set_alarm
        TestCase("set an alarm for 11pm", "set_alarm",
                 tags=["deterministic_core", "safe_smoke", "s21_usb_safe", "s23u_tcp_safe"]),
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
        TestCase("set a timer for 2 hours", "set_timer",
                 tags=["deterministic_core", "safe_smoke", "s21_usb_safe", "s23u_tcp_safe"]),
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
        TestCase("hold on", "pause_media", xfail=True,
                 tags=["ambiguous", "fixture_required", "media_context"],
                 category="ambiguous",
                 xfail_reason="context_missing: standalone 'hold on' needs active media context per semantic routing guidelines"),
        # stop_media (#521)
        TestCase("stop playing", "stop_media"),
        TestCase("stop playback", "stop_media"),
        TestCase("stop the audio", "stop_media"),
        # next_track (#521)
        TestCase("skip this song", "next_track"),
        TestCase("next track", "next_track"),
        TestCase("play the next one", "next_track"),
        TestCase("next song", "next_track"),
        TestCase("skip", "next_track",
                 tags=["ambiguous", "media_context"],
                 category="ambiguous"),
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
        TestCase("add bread and butter to my shopping list", "bulk_add_to_list", xfail=True,
                 xfail_reason="unsupported_feature: multi-item add_to_list not yet implemented (QIR extractor gap)",
                 id="add_bread_and_butter_v1"),
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
        TestCase("add eggs, milk, and bread to my shopping list", "bulk_add_to_list", xfail=True,
                 xfail_reason="unsupported_feature: multi-item add_to_list not yet implemented (QIR extractor gap)"),
        TestCase("make me a list for camping", "create_list"),
        TestCase("create a new list called work tasks", "create_list"),
        # bulk_add_to_list (#529 — LLM-routed, xfail until verified)
        TestCase("add eggs, milk, and bread to the shopping list", "bulk_add_to_list", xfail=True,
                 xfail_reason="unsupported_feature: multi-item add_to_list not yet implemented (QIR extractor gap)"),
        TestCase("put tortilla chips, beef mince, and kidney beans on my grocery list", "bulk_add_to_list", xfail=True,
                 xfail_reason="unsupported_feature: multi-item add_to_list not yet implemented (QIR extractor gap)"),
        TestCase("add these items to my list: apples, bananas, oranges", "bulk_add_to_list", xfail=True,
                 xfail_reason="unsupported_feature: multi-item add_to_list not yet implemented (QIR extractor gap)"),
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
        TestCase("remember that I prefer dark mode", "save_memory",
                 tags=["deterministic_core", "safe_smoke", "s21_usb_safe", "s23u_tcp_safe"]),
        # Memo/note capture — no alert implied. If a dedicated notes skill is added,
        # this should move from save_memory to that note/memo intent, not add_reminder.
        TestCase("note to self call the dentist Monday", "save_memory",
                 tags=["ambiguous"], category="ambiguous"),
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
        TestCase("open Spotify", "open_app",
                 category="fixture",
                 tags=["fixture_required"],
                 fixture="apps:spotify_installed"),
        TestCase("launch Google Maps", "open_app",
                 category="fixture",
                 tags=["fixture_required"],
                 fixture="apps:google_maps_installed"),
        # make_call
        # make_call
        TestCase("call voicemail", "make_call"),
        TestCase("call my voicemail", "make_call"),
        TestCase("ring mum", "make_call",
                 category="fixture",
                 tags=["fixture_required", "contact_fixture_required"],
                 fixture="contacts:family_seed"),
        TestCase("give Sarah a call", "make_call",
                 category="fixture",
                 tags=["fixture_required", "contact_fixture_required"],
                 fixture="contacts:family_seed"),
        # Contact alias resolution (fixture: 'zippy' → Voicemail / 121)
        TestCase("call zippy", "make_call",
                 category="fixture",
                 tags=["fixture_required", "contact_fixture_required"],
                 fixture="contacts:zippy_alias"),
        TestCase("ring zippy", "make_call",
                 category="fixture",
                 tags=["fixture_required", "contact_fixture_required"],
                 fixture="contacts:zippy_alias"),
        # send_sms
        TestCase("text myself a reminder to buy groceries", "send_sms",
                 expect_params={"contact": "myself", "message": "buy groceries"}),
        TestCase("send a message to myself saying call the plumber", "send_sms",
                 category="fixture",
                 tags=["fixture_required"],
                 fixture="contacts:self_number_known"),
        TestCase("text John saying I'll be 10 minutes late", "send_sms",
                 category="fixture",
                 tags=["fixture_required", "contact_fixture_required"],
                 fixture="contacts:family_seed"),
        TestCase("message mum that I'm on my way", "send_sms",
                 category="fixture",
                 tags=["fixture_required", "contact_fixture_required"],
                 fixture="contacts:family_seed"),
    ]),
    ("system", [
        # get_time — DirectReply assertions
        TestCase("what time is it", "get_time", expect_reply_contains=r"\d+:\d+",
                 tags=["safe_smoke", "s21_usb_safe", "s23u_tcp_safe"]),
        TestCase("what's today's date", "get_time", expect_reply_contains=r"202[4-9]|20[3-9]\d"),
        # get_battery — DirectReply assertion on first case
        TestCase("what's my battery level", "get_battery", expect_reply_contains=r"\d+%",
                 tags=["safe_smoke", "s21_usb_safe", "s23u_tcp_safe"]),
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
        TestCase("send an email to John about the project update", "send_email",
                 category="fixture",
                 tags=["fixture_required", "contact_fixture_required"],
                 fixture="contacts:email_contact_seed"),
        TestCase("email Sarah the meeting notes", "send_email",
                 category="fixture",
                 tags=["fixture_required", "contact_fixture_required"],
                 fixture="contacts:email_contact_seed"),
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
        TestCase("I missed that, go back", "podcast_skip_back", xfail=True,
                 xfail_reason="context_missing: 'go back' requires active podcast playback context",
                 id="i_missed_that_go_back_v1"),
        TestCase("back 15 seconds", "podcast_skip_back"),
        # podcast_speed (#524)
        TestCase("play at 1.5x speed", "podcast_speed"),
        TestCase("set playback speed to 2x", "podcast_speed"),
        TestCase("set podcast playback to normal speed", "podcast_speed"),
        TestCase("slow down the podcast", "podcast_speed"),
        # date_diff — deterministic countdown routing (issue #1227)
        TestCase("how many weeks until 31 October", "get_date_diff",
                 tags=["deterministic_core", "safe_smoke", "s21_usb_safe", "s23u_tcp_safe"],
                 expect_reply_contains=r"weeks? from now|days? from now"),
        TestCase("how many weeks until the 31 October", "get_date_diff",
                 tags=["deterministic_core", "safe_smoke", "s21_usb_safe", "s23u_tcp_safe"],
                 expect_reply_contains=r"weeks? from now|days? from now"),
    ]),
    ("slot_fill", [
        # ── Positive slot-fill: bare query → NeedsSlot → valid reply → dispatch ──
        # Alarm: slot-fill stores `time=7am` as the canonical public marker.
        # NativeIntentHandler internally resolves to hours/minutes, but the log marker
        # shows the raw param `time=7am` because the slot-fill merges the user's reply
        # text directly. This is the intended canonical representation for the harness.
        TestCase(
            "set an alarm",
            "set_alarm",
            slot_reply="7am",
            expect_params={"time": "7am"},
            expect_initial_log_contains="NeedsSlot",
            tags=["slot_fill", "safe_smoke", "s21_usb_safe", "s23u_tcp_safe"],
        ),
        TestCase(
            "set a timer",
            "set_timer",
            slot_reply="5 minutes",
            expect_params={"duration_seconds": "300"},
            expect_initial_log_contains="NeedsSlot",
            tags=["slot_fill", "safe_smoke", "s21_usb_safe", "s23u_tcp_safe"],
        ),
        TestCase(
            "open an app",
            "open_app",
            slot_reply="Spotify",
            expect_params={"app_name": "Spotify"},
            expect_initial_log_contains="NeedsSlot",
            tags=["slot_fill", "fixture_required"],
            fixture="apps:spotify_installed",
        ),
        TestCase(
            "navigate",
            "navigate_to",
            slot_reply="Auckland Airport",
            expect_params={"destination": "Auckland Airport"},
            expect_initial_log_contains="NeedsSlot",
            tags=["slot_fill", "fixture_required", "location_context"],
            fixture="location:maps_or_gps_required",
        ),
        TestCase(
            "find nearby",
            "find_nearby",
            slot_reply="coffee",
            expect_params={"query": "coffee"},
            expect_initial_log_contains="NeedsSlot",
            tags=["slot_fill", "fixture_required", "location_context"],
            fixture="location:nearby_or_gps_required",
        ),
        TestCase(
            "send a message",
            "send_sms",
            slot_reply="Mum",
            expect_params={"contact": "Mum"},
            expect_initial_log_contains="NeedsSlot",
            tags=["slot_fill", "fixture_required", "contact_fixture_required", "ambiguous"],
            fixture="contacts:family_seed",
        ),
        TestCase(
            "send an email",
            "send_email",
            slot_reply="Nick",
            expect_params={"contact": "Nick"},
            expect_initial_log_contains="NeedsSlot",
            tags=["slot_fill", "fixture_required", "contact_fixture_required", "ambiguous"],
            fixture="contacts:email_contact_seed",
        ),
        # ── Single-slot positive: bare query → NeedsSlot → item reply → NeedsSlot → list_name reply → dispatch ──
        # add_to_list requires TWO slots (item + list_name); the first reply ("eggs") fills item,
        # the second reply ("groceries") fills list_name (canonicalized to "shopping list") and triggers dispatch.
        TestCase(
            "add to my list",
            "add_to_list",
            slot_replies=["eggs", "groceries"],
            expect_params={"item": "eggs", "list_name": "shopping list"},
            expect_initial_log_contains="NeedsSlot",
            tags=["slot_fill"],
        ),
        # ── Full dispatch: both item and list_name present in query, no slot-fill needed ──
        TestCase(
            "add eggs to my shopping list",
            "add_to_list",
            expect_params={"item": "eggs", "list_name": "shopping"},
            tags=["slot_fill", "deterministic"],
        ),

        # ── Multi-slot positive: bare query → NeedsSlot → multiple replies → dispatch ──
        TestCase(
            "add to my list",
            "add_to_list",
            slot_replies=["eggs", "shopping list"],
            expect_params={"item": "eggs", "list_name": "shopping list"},
            expect_initial_log_contains="NeedsSlot",
            tags=["slot_fill", "multi_slot"],
            id="slot_fill_add_to_list_multi",
        ),
        TestCase(
            "send a message",
            "send_sms",
            slot_replies=["Mum", "on my way"],
            expect_params={"contact": "Mum", "message": "on my way"},
            expect_initial_log_contains="NeedsSlot",
            tags=["slot_fill", "multi_slot", "fixture_required", "contact_fixture_required"],
            fixture="contacts:family_seed",
            id="slot_fill_send_sms_multi",
        ),
        TestCase(
            "send an email",
            "send_email",
            slot_replies=["Nick", "meeting", "see you at 2"],
            expect_params={"contact": "Nick", "subject": "meeting", "body": "see you at 2"},
            expect_initial_log_contains="NeedsSlot",
            tags=["slot_fill", "multi_slot", "fixture_required", "contact_fixture_required"],
            fixture="contacts:email_contact_seed",
            id="slot_fill_send_email_multi",
        ),
        # ── Negative slot-fill: invalid slot value → should NOT dispatch ──
        TestCase(
            "set a timer",
            expect_intent="",
            slot_reply="donuts",
            expect_initial_log_contains="NeedsSlot",
            category="negative",
            tags=["slot_fill", "slot_fill_invalid_answer"],
            id="slot_fill_timer_invalid_reply",
        ),
        TestCase(
            "set an alarm",
            expect_intent="",
            slot_reply="later",
            expect_initial_log_contains="NeedsSlot",
            category="negative",
            tags=["slot_fill", "slot_fill_invalid_answer"],
            id="slot_fill_alarm_invalid_reply",
        ),
    ]),
    ("orchestrator_recovery", [
        # ── Scenario 1: FallThrough → AskConfirmation (medium risk, date extracted) ──
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
        # ── Scenario 6: High-risk intent → AskConfirmation (xfail, no extractor) ──
        TestCase(
            "__orchtest:send_sms:tell Sarah I am running late",
            expect_intent="",
            expect_log_contains="RecoveryOrchestrator.NotActionable",
            xfail=True,
            xfail_reason="missing_extractor: No send_sms orchestrator extractor implemented yet",
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
            xfail_reason="missing_extractor: No send_sms orchestrator extractor implemented yet",
        ),
        # ── Scenario (xfail): High-risk save_memory (no extractor) ──
        TestCase(
            "__orchtest:save_memory:remember that I parked on level 3",
            expect_intent="",
            expect_log_contains="RecoveryOrchestrator.NotActionable",
            xfail=True,
            xfail_reason="missing_extractor: No save_memory orchestrator extractor implemented yet",
        ),
    ]),
]

# ── Phase-level metadata defaults (issue #1163) ─────────────────────────

annotate_phases(PHASES)

TEST_CASES: list[TestCase] = [tc for _, tcs in PHASES for tc in tcs]

LLM_TOOLS_CASES: list[LLMToolsTestCase] = [
    # ── NZ Memory-First Cases (#1074) ──────────────────────────────────────
    # Known NZ/Māori cultural terms are answered with a deterministic local reply
    # from the seeded NZ corpus — no tool call, no inference, no Wikipedia.
    LLMToolsTestCase(
        name="nz_wharepaku_memory_first",
        message="what is a wharepaku",
        expected_top_level_tool="no_tool_call",
        expect_no_regex_match=True,
        expect_no_classifier_match=True,
        expect_no_tool_call=True,
        expect_log_contains="Deterministic NZ context answered locally",
        expected_reply_contains=["toilet", "restroom", "bathroom"],
    ),
    LLMToolsTestCase(
        name="nz_chocka_memory_first",
        message="what is chocka",
        expected_top_level_tool="no_tool_call",
        expect_no_regex_match=True,
        expect_no_classifier_match=True,
        expect_no_tool_call=True,
        expect_log_contains="Deterministic NZ context answered locally",
        expected_reply_contains=["full", "packed", "chock-a-block"],
    ),
    LLMToolsTestCase(
        name="nz_taniwha_memory_first",
        message="tell me about taniwha",
        expected_top_level_tool="no_tool_call",
        expect_no_regex_match=True,
        expect_no_classifier_match=True,
        expect_no_tool_call=True,
        expect_log_contains="Deterministic NZ context answered locally",
        expected_reply_contains=["guardian", "kaitiaki", "water", "waterway", "mythology"],
    ),
    LLMToolsTestCase(
        name="nz_kumara_memory_first",
        message="what is kumara",
        expected_top_level_tool="no_tool_call",
        expect_no_regex_match=True,
        expect_no_classifier_match=True,
        expect_no_tool_call=True,
        expect_log_contains="Deterministic NZ context answered locally",
        expected_reply_contains=["sweet potato", "hāngī", "hangi", "Sunday roast", "Māori"],
    ),
    # ── Explicit Wikipedia Control (#1074) ─────────────────────────────────
    # Explicit Wikipedia requests must still reach query_wikipedia.
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
        name="explicit_wikipedia_with_wharepaku",
        # Explicit Wikipedia request with a known NZ term must still call
        # query_wikipedia, NOT be intercepted by NZ memory-first injection. (#1074)
        message="look up wharepaku on Wikipedia",
        expected_top_level_tool="query_wikipedia",
        expected_fields={"query": "wharepaku"},
        expect_no_regex_match=True,
        expect_no_classifier_match=True,
        expected_result_mode="direct_reply",
    ),
    LLMToolsTestCase(
        name="explicit_wikipedia_with_taniwha",
        message="look up taniwha on Wikipedia",
        expected_top_level_tool="query_wikipedia",
        expected_fields={"query": "taniwha"},
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
        message="Can you inspect this device and summarise its current system status?",
        expected_top_level_tool="get_system_info",
        expected_fields=None,
        expect_no_regex_match=True,
        expect_no_classifier_match=True,
        expected_result_mode="direct_reply",
    ),
]

# ── Selector helpers (issue #1163) ──────────────────────────────────────


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


