#!/usr/bin/env python3
"""Deterministic validation of intent_phrases.json phrase vectors.

Checks:
- All expected intents are present
- Every intent has >= 3 phrases (minimum for meaningful embedding)
- No duplicate phrases across intents
- All phrase-returning intents have at least one phrase
- Each phrase is non-empty
- Core intents listed in the review checklist are present

This is a lightweight static check (no MiniLM model, no device).
Exit code 0 = all checks pass.
"""

import json
import sys
from pathlib import Path

ASSET_PATH = Path(__file__).resolve().parent.parent \
    / "app" / "src" / "main" / "assets" / "intent_phrases.json"

# Intents declared as new or changed in PR #1309
# These must exist with >= 3 phrases.
NEW_OR_CHANGED_INTENTS = {
    "add_reminder",
    "next_track",
    "pause_media",
    "play_netflix",
    "play_plex",
    "play_plexamp",
    "play_spotify",
    "play_youtube",
    "play_youtube_music",
    "previous_track",
    "stop_media",
}

# Core regression intents — must still be present and healthy
CORE_REGRESSION_INTENTS = {
    "add_to_list",
    "create_calendar_event",
    "get_weather",
    "open_app",
    "set_alarm",
    "set_timer",
    "toggle_flashlight_on",
    "toggle_flashlight_off",
    "set_volume",
    "send_sms",
    "send_email",
    "make_call",
    "get_time",
    "get_date",
    "get_battery",
    "find_nearby",
    "navigate_to",
    "smart_home_on",
    "smart_home_off",
    "toggle_wifi",
    "toggle_bluetooth",
    "toggle_dnd_on",
    "toggle_dnd_off",
    "toggle_hotspot",
    "toggle_airplane_mode",
    "play_media",
    "play_media_album",
    "play_media_playlist",
}

MIN_PHRASES_PER_INTENT = 3


def main() -> int:
    errors: list[str] = []

    if not ASSET_PATH.is_file():
        print(f"ERROR: Asset file not found: {ASSET_PATH}")
        return 1

    with open(ASSET_PATH) as f:
        data = json.load(f)

    intents = data.get("intents", {})

    if not intents:
        print("ERROR: 'intents' object is empty or missing")
        return 1

    phrase_counts: dict[str, int] = {}
    all_phrases: dict[str, set[str]] = {}
    all_phrase_texts: set[str] = set()
    duplicate_phrases: list[tuple[str, str, str]] = []  # phrase, intent_a, intent_b

    for name, info in intents.items():
        phrases = info.get("phrases", [])
        phrase_counts[name] = len(phrases)
        all_phrases[name] = set(phrases)

        for p in phrases:
            if not p.strip():
                errors.append(f"  [{name}] Empty phrase found")
            if p in all_phrase_texts:
                # Find which other intent it's in
                for other, ps in all_phrases.items():
                    if other != name and p in ps:
                        duplicate_phrases.append((p, other, name))
                        break
            else:
                all_phrase_texts.add(p)

    # ── Check 1: New/changed intents present ──
    print(f"\n{'='*60}")
    print("  PR #1309 — Targeted intents (new/changed)")
    print(f"{'='*60}")
    for intent in sorted(NEW_OR_CHANGED_INTENTS):
        if intent in intents:
            count = phrase_counts[intent]
            ok = count >= MIN_PHRASES_PER_INTENT
            status = "✅" if ok else "⚠️  BELOW MINIMUM"
            print(f"  {status} {intent}: {count} phrases")
            if not ok:
                errors.append(f"  [{intent}] Only {count} phrases (min {MIN_PHRASES_PER_INTENT})")
        else:
            print(f"  ❌ {intent}: MISSING")
            errors.append(f"  [{intent}] Not found in intent_phrases.json")
    print()

    # ── Check 2: Core regression intents present ──
    print(f"{'='*60}")
    print("  Core regression intents")
    print(f"{'='*60}")
    for intent in sorted(CORE_REGRESSION_INTENTS):
        if intent in intents:
            count = phrase_counts[intent]
            ok = count >= MIN_PHRASES_PER_INTENT
            status = "✅" if ok else "⚠️  BELOW MINIMUM"
            print(f"  {status} {intent}: {count} phrases")
        else:
            print(f"  ❌ {intent}: MISSING")
            errors.append(f"  [{intent}] Core regression intent missing")
    print()

    # ── Check 3: All intents have minimum phrases ──
    under_min = [n for n, c in phrase_counts.items() if c < MIN_PHRASES_PER_INTENT]
    if under_min:
        print(f"⚠️  Intents below minimum ({MIN_PHRASES_PER_INTENT} phrases):")
        for n in sorted(under_min):
            print(f"     {n}: {phrase_counts[n]}")
            errors.append(f"  [{n}] Below min phrases: {phrase_counts[n]}")
        print()
    else:
        print(f"✅ All {len(intents)} intents meet minimum phrase count\n")

    # ── Check 4: Duplicate phrases across intents ──
    if duplicate_phrases:
        print(f"⚠️  Duplicate phrases across intents ({len(duplicate_phrases)}):")
        for phrase, intent_a, intent_b in sorted(duplicate_phrases):
            print(f"     '{phrase}' in [{intent_a}] and [{intent_b}]")
            errors.append(f"  Duplicate phrase '{phrase}' in [{intent_a}] and [{intent_b}]")
        print()
    else:
        print("✅ No duplicate phrases across intents\n")

    # ── Summary ──
    print(f"{'='*60}")
    print(f"  Total intents: {len(intents)}")
    print(f"  Total phrases: {sum(phrase_counts.values())}")
    print(f"  Mean phrases/intent: {sum(phrase_counts.values())/len(intents):.1f}")

    if errors:
        print(f"\n  ❌ {len(errors)} error(s) found:\n")
        for e in errors:
            print(f"    - {e}")
        return 1
    else:
        print("\n  ✅ All checks passed!")
        return 0


if __name__ == "__main__":
    sys.exit(main())
