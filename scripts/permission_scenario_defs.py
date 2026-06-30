"""Declarative scenario definitions for scripts/run_permission_scenarios.py."""

from __future__ import annotations

DEFAULT_UX_THRESHOLDS = {
    "max_steps": 8,
    "max_user_taps": 5,
    "max_settings_hops": 1,
    "max_duration_seconds": 30,
    "max_back_presses": 2,
    "fail_on_manual_intervention": True,
}

DEFAULT_ASSISTANT_BLOCKER = (
    "Jandal is not configured as the Android default assistant; configure it manually before "
    "running Hey Jandal voice scenarios."
)
WAKE_WORD_MODEL_BLOCKER = (
    "Wake word model is not available on this build; Hey Jandal voice scenarios cannot run yet."
)
HEY_JANDAL_LABEL = 'Listen for "Hey Jandal"'

SCENARIOS: list[dict[str, object]] = [
    {
        "id": "hey_jandal_preflight",
        "title": "Hey Jandal preflight checks default assistant setup",
        "capability": "wake_word",
        "tags": ["permissions", "microphone", "wake_word", "preflight", "voice"],
        "steps": [
            {
                "id": "launch_app",
                "action": "launch_main",
                "expected": "Kernel AI app returns to foreground",
                "screenshot": True,
            },
            {
                "id": "open_settings",
                "action": "tap_visible",
                "target": {"content_desc": "Settings"},
                "expected": "Settings screen opens",
                "expected_visible": ["Settings"],
                "screenshot": True,
            },
            {
                "id": "open_voice_settings",
                "action": "tap_visible",
                "target": {"text": "Voice"},
                "expected": "Voice settings opens",
                "expected_visible": ["Hey Jandal", HEY_JANDAL_LABEL],
                "expected_any_visible": [
                    "Jandal is your default assistant",
                    "Set Jandal as default assistant",
                ],
                "blocked_if_visible": {
                    "texts": ["Wake word model not yet available"],
                    "reason": WAKE_WORD_MODEL_BLOCKER,
                },
                "screenshot": True,
            },
            {
                "id": "check_default_assistant_ready",
                "action": "check_default_assistant_ready",
                "expected": "Jandal default-assistant prerequisite is satisfied",
                "screenshot": True,
            },
        ],
    },
    {
        "id": "hey_jandal_enable_mic_granted",
        "title": "Enable Hey Jandal with microphone already granted",
        "capability": "wake_word",
        "tags": ["permissions", "microphone", "wake_word", "voice"],
        "steps": [
            {
                "id": "grant_microphone",
                "action": "set_permission_state",
                "permission": "android.permission.RECORD_AUDIO",
                "state": "granted",
                "expected": "Microphone permission granted",
            },
            {
                "id": "launch_app",
                "action": "launch_main",
                "expected": "Kernel AI app returns to foreground",
                "screenshot": True,
            },
            {
                "id": "open_settings",
                "action": "tap_visible",
                "target": {"content_desc": "Settings"},
                "expected": "Settings screen opens",
                "expected_visible": ["Settings"],
            },
            {
                "id": "open_voice_settings",
                "action": "tap_visible",
                "target": {"text": "Voice"},
                "expected": "Voice settings opens",
                "expected_visible": ["Hey Jandal", HEY_JANDAL_LABEL],
                "blocked_if_visible": {
                    "texts": ["Wake word model not yet available"],
                    "reason": WAKE_WORD_MODEL_BLOCKER,
                },
                "screenshot": True,
            },
            {
                "id": "check_default_assistant_ready",
                "action": "check_default_assistant_ready",
                "expected": "Jandal default-assistant prerequisite is satisfied",
            },
            {
                "id": "reset_hey_jandal_toggle_off",
                "action": "set_toggle_state",
                "anchor_text": HEY_JANDAL_LABEL,
                "checked": False,
                "expected": "Hey Jandal toggle is reset to off before enablement",
                "expected_toggle_state": {"anchor_text": HEY_JANDAL_LABEL, "checked": False},
            },
            {
                "id": "enable_hey_jandal_toggle",
                "action": "set_toggle_state",
                "anchor_text": HEY_JANDAL_LABEL,
                "checked": True,
                "expected": "Hey Jandal toggle is enabled",
                "expected_toggle_state": {"anchor_text": HEY_JANDAL_LABEL, "checked": True},
                "screenshot": True,
            },
        ],
    },
    {
        "id": "hey_jandal_enable_mic_denied",
        "title": "Enable Hey Jandal with microphone promptable denied",
        "capability": "wake_word",
        "tags": ["permissions", "microphone", "wake_word", "voice"],
        "steps": [
            {
                "id": "grant_microphone_for_toggle_reset",
                "action": "set_permission_state",
                "permission": "android.permission.RECORD_AUDIO",
                "state": "granted",
                "expected": "Microphone permission granted so the Hey Jandal toggle can be reset safely",
            },
            {
                "id": "launch_app",
                "action": "launch_main",
                "expected": "Kernel AI app returns to foreground",
                "screenshot": True,
            },
            {
                "id": "open_settings",
                "action": "tap_visible",
                "target": {"content_desc": "Settings"},
                "expected": "Settings screen opens",
                "expected_visible": ["Settings"],
            },
            {
                "id": "open_voice_settings",
                "action": "tap_visible",
                "target": {"text": "Voice"},
                "expected": "Voice settings opens",
                "expected_visible": ["Hey Jandal", HEY_JANDAL_LABEL],
                "blocked_if_visible": {
                    "texts": ["Wake word model not yet available"],
                    "reason": WAKE_WORD_MODEL_BLOCKER,
                },
                "screenshot": True,
            },
            {
                "id": "check_default_assistant_ready",
                "action": "check_default_assistant_ready",
                "expected": "Jandal default-assistant prerequisite is satisfied",
            },
            {
                "id": "reset_hey_jandal_toggle_off",
                "action": "set_toggle_state",
                "anchor_text": HEY_JANDAL_LABEL,
                "checked": False,
                "expected": "Hey Jandal toggle is reset to off before microphone permission prompting",
                "expected_toggle_state": {"anchor_text": HEY_JANDAL_LABEL, "checked": False},
            },
            {
                "id": "reset_microphone_prompt_state",
                "action": "set_permission_state",
                "permission": "android.permission.RECORD_AUDIO",
                "state": "prompt",
                "expected": "Microphone permission reset to promptable denied state",
            },
            {
                "id": "relaunch_app_after_permission_reset",
                "action": "launch_main",
                "expected": "Kernel AI app returns to foreground after microphone permission reset",
            },
            {
                "id": "reopen_settings_after_permission_reset",
                "action": "tap_visible",
                "target": {"content_desc": "Settings"},
                "expected": "Settings screen reopens after microphone permission reset",
                "expected_visible": ["Settings"],
            },
            {
                "id": "reopen_voice_settings_after_permission_reset",
                "action": "tap_visible",
                "target": {"text": "Voice"},
                "expected": "Voice settings reopen after microphone permission reset",
                "expected_visible": ["Hey Jandal", HEY_JANDAL_LABEL],
                "blocked_if_visible": {
                    "texts": ["Wake word model not yet available"],
                    "reason": WAKE_WORD_MODEL_BLOCKER,
                },
            },
            {
                "id": "recheck_default_assistant_ready",
                "action": "check_default_assistant_ready",
                "expected": "Jandal default-assistant prerequisite remains satisfied after microphone permission reset",
            },
            {
                "id": "request_microphone_via_toggle",
                "action": "tap_toggle_for_text",
                "anchor_text": HEY_JANDAL_LABEL,
                "expected": "Android microphone permission prompt appears",
                "expected_any_visible": ["Allow", "While using the app", "Only this time", "Don't allow", "Deny"],
                "screenshot": True,
            },
            {
                "id": "deny_microphone_prompt",
                "action": "tap_visible",
                "target": {"any_text": ["Don't allow", "Deny", "No thanks", "Cancel"]},
                "expected": "Permission prompt is denied and app remains usable",
                "expected_visible": [HEY_JANDAL_LABEL],
                "expected_toggle_state": {"anchor_text": HEY_JANDAL_LABEL, "checked": False},
                "expected_not_visible": ["Microphone permission is blocked", "Microphone access was removed"],
                "screenshot": True,
            },
        ],
    },
    {
        "id": "hey_jandal_mic_revoked_resume",
        "title": "Hey Jandal enabled then microphone revoked externally",
        "capability": "wake_word",
        "tags": ["permissions", "microphone", "wake_word", "durability", "voice"],
        "steps": [
            {
                "id": "grant_microphone",
                "action": "set_permission_state",
                "permission": "android.permission.RECORD_AUDIO",
                "state": "granted",
                "expected": "Microphone permission granted",
            },
            {
                "id": "launch_app",
                "action": "launch_main",
                "expected": "Kernel AI app returns to foreground",
            },
            {
                "id": "open_settings",
                "action": "tap_visible",
                "target": {"content_desc": "Settings"},
                "expected": "Settings screen opens",
                "expected_visible": ["Settings"],
            },
            {
                "id": "open_voice_settings",
                "action": "tap_visible",
                "target": {"text": "Voice"},
                "expected": "Voice settings opens",
                "expected_visible": ["Hey Jandal", HEY_JANDAL_LABEL],
                "blocked_if_visible": {
                    "texts": ["Wake word model not yet available"],
                    "reason": WAKE_WORD_MODEL_BLOCKER,
                },
                "screenshot": True,
            },
            {
                "id": "check_default_assistant_ready",
                "action": "check_default_assistant_ready",
                "expected": "Jandal default-assistant prerequisite is satisfied",
            },
            {
                "id": "enable_hey_jandal_toggle",
                "action": "set_toggle_state",
                "anchor_text": HEY_JANDAL_LABEL,
                "checked": True,
                "expected": "Hey Jandal toggle is enabled",
                "expected_toggle_state": {"anchor_text": HEY_JANDAL_LABEL, "checked": True},
                "screenshot": True,
            },
            {
                "id": "revoke_microphone_externally",
                "action": "set_permission_state",
                "permission": "android.permission.RECORD_AUDIO",
                "state": "revoked",
                "expected": "Microphone permission revoked externally",
            },
            {
                "id": "background_app",
                "action": "press_home",
                "expected": "App is backgrounded",
            },
            {
                "id": "resume_existing_voice_task",
                "action": "resume_existing_app",
                "expected": "App returns to the previous Voice screen task without crashing",
                "expected_visible": ["Microphone access was removed", "Open Microphone permission settings"],
                "screenshot": True,
            },
        ],
    },
    {
        "id": "weather_location_denied",
        "title": "Weather request with location denied uses fallback UX",
        "capability": "weather_current_location",
        "tags": ["permissions", "location", "weather"],
        "steps": [
            {
                "id": "reset_location_prompt_state",
                "action": "set_permission_state",
                "permission": "android.permission.ACCESS_COARSE_LOCATION",
                "state": "prompt",
                "also_apply": ["android.permission.ACCESS_FINE_LOCATION"],
                "expected": "Location permissions reset to promptable denied state",
            },
            {
                "id": "launch_weather_query",
                "action": "launch_quick_action",
                "query": "what's the weather",
                "expected": "Weather permission dialog appears",
                "expected_visible": ["Use your location for local weather?", "Use my location", "Use a named location"],
                "screenshot": True,
            },
            {
                "id": "choose_named_location",
                "action": "tap_visible",
                "target": {"text": "Use a named location"},
                "expected": "Fallback guidance appears instead of forcing location access",
                "expected_visible": ['Type a place name in the quick command bar, like "weather in Tokyo".'],
                "screenshot": True,
            },
        ],
    },
]
