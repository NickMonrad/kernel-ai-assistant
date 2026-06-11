"""
ADB Skill Harness — selector helpers for composable test filtering.

Provides ``_select_tests()`` used by both ``--dry-run`` and real execution,
and ``annotate_phases()`` that applies phase-level metadata defaults.
"""

from __future__ import annotations

from dataclasses import field

from adb_harness.models import TestCase


def _parse_arg_list(val: str | None) -> list[str] | None:
    """Parse a comma-separated CLI argument into a list, or return None."""
    if val is None:
        return None
    return [v.strip() for v in val.split(",") if v.strip()]


def _select_tests(
    phases: list[tuple[str, list[TestCase]]],
    phase_filter: list[str] | None = None,
    categories: list[str] | None = None,
    tags: list[str] | None = None,
    exclude_tags: list[str] | None = None,
    case_ids: list[str] | None = None,
) -> list[tuple[int, int, TestCase]]:
    """Filter *phases* by name and metadata selectors.

    Returns a list of ``(phase_idx, case_idx, test_case)`` tuples matching
    all active filters (logical AND across filter groups).
    """
    phase_names = [name for name, _ in phases]
    result: list[tuple[int, int, TestCase]] = []

    for phase_idx, (phase_name, cases) in enumerate(phases):
        if phase_filter is not None and phase_name not in phase_filter:
            continue
        for case_idx, tc in enumerate(cases):
            if categories is not None and tc.category not in categories:
                continue
            if tags is not None and not any(t in tc.tags for t in tags):
                continue
            if exclude_tags is not None and any(t in tc.tags for t in exclude_tags):
                continue
            if case_ids is not None and tc.id not in case_ids:
                continue
            result.append((phase_idx, case_idx, tc))

    return result


def annotate_phases(phases: list[tuple[str, list[TestCase]]]) -> None:
    """Apply default category/tag metadata to test cases based on phase and intent.

    Individual TestCase declarations can still override any field — this
    catches the common case so 200+ hand-edits aren't needed.
    """
    _PHASE_DEFAULTS: dict[str, dict | None] = {
        "alarm_timer": {"tags": ["deterministic_core"]},
        "weather": {"tags": ["deterministic_core"]},
        "media": None,
        "lists": {"tags": ["deterministic_core"]},
        "smart_home": {"tags": ["deterministic_core"]},
        "memory": {"tags": ["deterministic_core"]},
        "navigation": None,
        "system": None,
        "misc": None,
        "slot_fill": {"category": "slot_fill", "tags": ["slot_fill"]},
        "orchestrator_recovery": {"category": "recovery", "tags": ["orchestrator_recovery"]},
    }
    _INTENT_OVERRIDES: dict[str, dict] = {
        "pause_media":    {"category": "fixture", "tags": ["fixture_required", "media_context"]},
        "stop_media":     {"category": "fixture", "tags": ["fixture_required", "media_context"]},
        "next_track":     {"category": "fixture", "tags": ["fixture_required", "media_context"]},
        "previous_track": {"category": "fixture", "tags": ["fixture_required", "media_context"]},
        "podcast_skip_forward": {"category": "fixture", "tags": ["fixture_required", "media_context"]},
        "podcast_skip_back":    {"category": "fixture", "tags": ["fixture_required", "media_context"]},
        "podcast_speed":        {"category": "fixture", "tags": ["fixture_required", "media_context"]},
        "set_volume":     {"category": "device_state", "tags": ["device_state"]},
        "toggle_wifi":    {"category": "device_state", "tags": ["device_state", "destructive"]},
        "toggle_hotspot": {"category": "device_state", "tags": ["device_state", "destructive"]},
        "toggle_airplane_mode": {"category": "device_state", "tags": ["device_state", "destructive"]},
        "toggle_dnd_on":  {"category": "device_state", "tags": ["device_state"]},
        "toggle_dnd_off": {"category": "device_state", "tags": ["device_state"]},
        "set_brightness": {"category": "device_state", "tags": ["device_state"]},
        "toggle_flashlight_on":  {"category": "device_state", "tags": ["device_state"]},
        "toggle_flashlight_off": {"category": "device_state", "tags": ["device_state"]},
        "get_weather": {"category": "fixture", "tags": ["fixture_required", "location_context"]},
        "find_nearby": {"category": "fixture", "tags": ["fixture_required", "location_context"], "fixture": "location:nearby_or_gps_required"},
        "navigate_to": {"category": "fixture", "tags": ["fixture_required", "location_context"], "fixture": "location:maps_or_gps_required"},
    }

    for phase_name, phase_cases in phases:
        defaults = _PHASE_DEFAULTS.get(phase_name)
        for tc in phase_cases:
            intent_ov = _INTENT_OVERRIDES.get(tc.expect_intent) or {}
            if defaults:
                for key, val in defaults.items():
                    current = getattr(tc, key, None)
                    if key == "tags" and (current == [] or not current):
                        setattr(tc, key, val)
                    elif key == "category" and (current == "deterministic" or current is None):
                        setattr(tc, key, val)
                    elif current is None:
                        setattr(tc, key, val)
            if intent_ov:
                for key, val in intent_ov.items():
                    if key == "tags":
                        existing = list(tc.tags)
                        for t in val:
                            if t not in existing:
                                existing.append(t)
                        setattr(tc, key, existing)
                    else:
                        setattr(tc, key, val)
