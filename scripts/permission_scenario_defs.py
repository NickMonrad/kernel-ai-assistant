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

SCENARIOS: list[dict[str, object]] = [
    {
        "id": "mic_denied_enable_hey_jandal",
        "title": "Enable Hey Jandal with microphone denied",
        "capability": "wake_word",
        "tags": ["permissions", "microphone", "wake_word"],
        "steps": [
            {
                "id": "reset_microphone_prompt_state",
                "action": "set_permission_state",
                "permission": "android.permission.RECORD_AUDIO",
                "state": "prompt",
                "expected": "Microphone permission reset to promptable denied state",
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
                "screenshot": True,
            },
            {
                "id": "open_voice_settings",
                "action": "tap_visible",
                "target": {"text": "Voice"},
                "expected": "Voice settings opens",
                "expected_visible": ["Hey Jandal", 'Listen for "Hey Jandal"'],
                "blocked_if_visible": {
                    "texts": ["Set Jandal as default assistant first for reliable background mic access"],
                    "reason": "S21 requires Jandal to be the default assistant before the Hey Jandal toggle becomes actionable.",
                },
                "screenshot": True,
            },
            {
                "id": "try_enable_hey_jandal",
                "action": "tap_visible",
                "target": {"text": 'Listen for "Hey Jandal"'},
                "expected": "Android microphone permission prompt appears",
                "expected_any_visible": ["Allow", "While using the app", "Only this time", "Don't allow", "Deny"],
                "screenshot": True,
            },
            {
                "id": "deny_microphone_prompt",
                "action": "tap_visible",
                "target": {"any_text": ["Don't allow", "Deny", "No thanks", "Cancel"]},
                "expected": "Permission prompt is denied and app remains usable",
                "expected_visible": ['Listen for "Hey Jandal"'],
                "expected_not_visible": ["Microphone permission is blocked"],
                "screenshot": True,
            },
        ],
    },
    {
        "id": "mic_revoke_while_hey_jandal_enabled",
        "title": "Revoke microphone while Hey Jandal is enabled",
        "capability": "wake_word",
        "tags": ["permissions", "microphone", "wake_word", "durability"],
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
                "expected_visible": ["Hey Jandal", 'Listen for "Hey Jandal"'],
                "blocked_if_visible": {
                    "texts": ["Set Jandal as default assistant first for reliable background mic access"],
                    "reason": "S21 requires Jandal to be the default assistant before the Hey Jandal toggle can be enabled for revoke/resume validation.",
                },
                "screenshot": True,
            },
            {
                "id": "enable_hey_jandal",
                "action": "tap_visible",
                "target": {"text": 'Listen for "Hey Jandal"'},
                "expected": "Hey Jandal remains visible after enabling",
                "expected_visible": ['Listen for "Hey Jandal"'],
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
                "id": "resume_app",
                "action": "launch_main",
                "expected": "Voice screen resumes and shows durable repair UX",
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
