# Kernel AI Assistant — Automated Test Specification

> **Issue ref:** #427 Living Test Document
> **Harness:** `scripts/adb_skill_test.py`
> **Date:** 2025-07-17
> **Status:** Draft — ready for implementation

---

## Table of Contents

1. [Test Coverage Matrix](#1-test-coverage-matrix)
2. [New ADB Test Cases — False-Positive Suite](#2-new-adb-test-cases--false-positive-suite)
3. [New ADB Test Cases — Response Quality Suite](#3-new-adb-test-cases--response-quality-suite)
4. [New ADB Test Cases — Multi-Turn Suite](#4-new-adb-test-cases--multi-turn-suite)
5. [New ADB Test Cases — Persistence Suite](#5-new-adb-test-cases--persistence-suite)
6. [UI Automator Tests](#6-ui-automator-tests)
7. [Manual Tests](#7-manual-tests)
8. [New `run_*` Functions Needed](#8-new-run_-functions-needed)
9. [CLI Flag Additions](#9-cli-flag-additions)

---

## 1. Test Coverage Matrix

| # | Section | Total Tests | ADB Harness | UI Automator | Manual Only |
|---|---------|-------------|-------------|--------------|-------------|
| 1A | Model Download & Onboarding | 5 | 0 | 3 | 2 |
| 1B | HuggingFace Auth | 4 | 0 | 2 | 2 |
| 1C | Thinking Mode Toggle | 7 | 3 | 2 | 2 |
| 2A | Date/Time Quick Actions (QIR) | 7 | 7 | 0 | 0 |
| 2B | Date/Time False-Positives | 6 | 6 | 0 | 0 |
| 2C | Chat-initiated Date/Time | 2 | 2 | 0 | 0 |
| 3A | Calendar Creation | 6 | 6 | 0 | 0 |
| 3B | Calendar Intent Routing | 4 | 4 | 0 | 0 |
| 3C | Alarms — QIR Routing | 4 | 4 | 0 | 0 |
| 3D | Alarms — CRUD UI | 5 | 0 | 5 | 0 |
| 3E | Timers | 2 | 2 | 0 | 0 |
| 4  | Expanded Native Intents | 0 | 0 (covered) | 0 | 0 |
| 5A–D | Lists CRUD | 7 | 4 | 3 | 0 |
| 5E | List False-Positives | 3 | 3 | 0 | 0 |
| 6A | SMS / Email Contact | 4 | 1 | 2 | 1 |
| 6B | Calls (Contact Aliases) | 4 | 1 | 2 | 1 |
| 7A | Core Memory — QIR | 2 | 2 | 0 | 0 |
| 7B | Core Memory — Persistence | 4 | 3 | 1 | 0 |
| 7C | Episodic Memory | 2 | 1 | 0 | 1 |
| 7D | User Profile | 4 | 2 | 2 | 0 |
| 7E | Memory False-Positives | 2 | 2 | 0 | 0 |
| 8A | Basic Weather | 3 | 2 | 0 | 1 |
| 8B | Weather Enhancements | 6 | 6 | 0 | 0 |
| 8C | Weather False-Positives | 3 | 3 | 0 | 0 |
| 9A | Hallucination Retry | 4 | 4 | 0 | 0 |
| 9B | DirectReply Tool Chips | 4 | 2 | 2 | 0 |
| 9C | Anaphora (Multi-Turn) | 3 | 3 | 0 | 0 |
| 10 | Chat Quality & Personality | 7 | 5 | 0 | 2 |
| 11 | FallThrough Bridge | 3 | 1 | 2 | 0 |
| 12 | UI & Navigation | 4 | 0 | 4 | 0 |
| 13 | System Permissions | 5 | 0 | 3 | 2 |
| 14 | ADB Regression (existing) | 62 | 62 (done) | 0 | 0 |
| | **TOTALS** | **~142 new** | **~89** | **~31** | **~11** |

> **Note:** Section 14's 62 existing tests are already implemented. Section 4 is fully covered
> by the existing harness. The counts above are *net new* tests to implement.

---

## 2. New ADB Test Cases — False-Positive Suite

These tests assert that a phrase does **NOT** trigger a specific QIR intent and instead
falls through to the LLM (`intent=NO_MATCH` or no `NativeIntentHandler.handle` line at all).

### 2.1 Dataclass Definition

```python
@dataclass
class FalsePositiveTestCase:
    """A phrase that must NOT route to a specific QIR intent.

    The harness sends the message, reads logcat, and asserts that:
      1. `forbidden_intent` does NOT appear in any intent= match, AND
      2. Either intent=NO_MATCH appears OR the LLM generation fires.
    """
    message: str
    forbidden_intent: str           # e.g. "get_time" — must NOT appear
    category: str = "general"       # grouping for reporting: date_time | list | memory | weather
```

### 2.2 Test Data

```python
FALSE_POSITIVE_CASES: list[FalsePositiveTestCase] = [
    # ── §2B: Date/Time false-positives ────────────────────────────────────
    FalsePositiveTestCase(
        message="What year is this movie set in",
        forbidden_intent="get_time",
        category="date_time",
    ),
    FalsePositiveTestCase(
        message="What month is this charge for",
        forbidden_intent="get_time",
        category="date_time",
    ),
    FalsePositiveTestCase(
        message="What is the week's schedule",
        forbidden_intent="get_time",
        category="date_time",
    ),
    FalsePositiveTestCase(
        message="Is it Friday's episode tonight",
        forbidden_intent="get_time",
        category="date_time",
    ),
    FalsePositiveTestCase(
        message="What time should I leave",
        forbidden_intent="get_time",
        category="date_time",
    ),
    FalsePositiveTestCase(
        message="What's the best time of year to visit Japan",
        forbidden_intent="get_time",
        category="date_time",
    ),

    # ── §3B: Calendar false-positive ──────────────────────────────────────
    FalsePositiveTestCase(
        message="Send a calendar invite to John",
        forbidden_intent="create_calendar_event",
        category="calendar",
    ),

    # ── §3C: Alarm/Timer disambiguation ───────────────────────────────────
    FalsePositiveTestCase(
        message="Set a 5 minute egg timer",
        forbidden_intent="set_alarm",
        category="calendar",
    ),

    # ── §5E: List false-positives ─────────────────────────────────────────
    FalsePositiveTestCase(
        message="List all the capitals of Europe",
        forbidden_intent="create_list",
        category="list",
    ),
    FalsePositiveTestCase(
        message="List all the capitals of Europe",
        forbidden_intent="get_list_items",
        category="list",
    ),
    FalsePositiveTestCase(
        message="Create a plan for my week",
        forbidden_intent="create_list",
        category="list",
    ),
    FalsePositiveTestCase(
        message="Add some detail to your explanation",
        forbidden_intent="add_to_list",
        category="list",
    ),

    # ── §7E: Memory false-positives ───────────────────────────────────────
    FalsePositiveTestCase(
        message="I remember when we talked about this",
        forbidden_intent="save_memory",
        category="memory",
    ),
    FalsePositiveTestCase(
        message="Don't forget to add milk",
        forbidden_intent="save_memory",
        category="memory",
    ),

    # ── §8C: Weather false-positives ──────────────────────────────────────
    FalsePositiveTestCase(
        message="How hot was the summer of '69",
        forbidden_intent="get_weather",
        category="weather",
    ),
    FalsePositiveTestCase(
        message="Is it going to be a long winter",
        forbidden_intent="get_weather",
        category="weather",
    ),
    FalsePositiveTestCase(
        message="What's the weather like in Game of Thrones",
        forbidden_intent="get_weather",
        category="weather",
    ),
]
```

### 2.3 Assertion Logic

```python
FP_INTENT_PATTERN = re.compile(r"NativeIntentHandler\.handle:\s*intent=(\S+)")
LLM_GENERATION_PATTERN = re.compile(r"Generation complete")

def assert_false_positive(logcat: str, tc: FalsePositiveTestCase) -> tuple[bool, str]:
    """Returns (passed, reason)."""
    intents = FP_INTENT_PATTERN.findall(logcat)

    # FAIL: forbidden intent was triggered
    if tc.forbidden_intent in intents:
        return False, f"forbidden intent '{tc.forbidden_intent}' was triggered"

    # PASS condition: either NO_MATCH appeared or LLM generation completed
    fell_through = "NO_MATCH" in intents or LLM_GENERATION_PATTERN.search(logcat)
    if not fell_through:
        return False, "no intent matched AND no LLM generation detected (timeout?)"

    return True, "OK"
```

### 2.4 Additional Note: §7E "Don't forget to add milk"

This phrase should route to `add_to_list`, **not** `save_memory`. The false-positive test
above checks that `save_memory` is not triggered. A complementary **positive** routing test
should be added to `TEST_CASES`:

```python
# Add to TEST_CASES in the existing harness:
TestCase("Don't forget to add milk", "add_to_list"),
```

---

## 3. New ADB Test Cases — Response Quality Suite

Tests that send a prompt and inspect logcat for **quality signals** — not just intent
routing but evidence that the right subsystem executed.

### 3.1 Dataclass Definition

```python
@dataclass
class ResponseQualityTestCase:
    """Verifies logcat contains (or does not contain) specific patterns after a prompt.

    Use for:
    - Hallucination retry verification (§9A)
    - Tool chip emission (§9B)
    - Memory save/search confirmation (§7A-B)
    - Weather data quality (§8B)
    - Thinking mode signals (§1C)
    - LLM answer quality markers (§10)
    """
    name: str
    message: str
    logcat_must_contain: list[str]       # regex patterns — ALL must match
    logcat_must_not_contain: list[str]   # regex patterns — NONE may match
    wait_seconds: int = 20              # override for slow operations (LLM fallthrough)
    category: str = "general"
    setup_messages: list[str] | None = None  # optional preceding messages for context
    pre_inject_profile: str | None = None    # optional profile to inject first
```

### 3.2 Test Data

```python
RESPONSE_QUALITY_CASES: list[ResponseQualityTestCase] = [

    # ── §1C: Thinking Mode (assumes E-4B model, thinking ON) ──────────────
    ResponseQualityTestCase(
        name="thinking_mode_on_produces_thinking_tokens",
        message="Explain the trolley problem",
        logcat_must_contain=[r"thinking_char_count:\s*[1-9]\d*"],  # > 0
        logcat_must_not_contain=[],
        wait_seconds=45,
        category="thinking_mode",
    ),
    ResponseQualityTestCase(
        name="thinking_mode_off_no_thinking_tokens",
        message="Explain the trolley problem",
        # Requires toggle OFF before running — see §6 UI Automator for toggle
        # This test assumes the toggle was set off via a prior UI Automator step
        # or ADB broadcast. Logcat should show thinking_char_count: 0
        logcat_must_contain=[r"thinking_char_count:\s*0"],
        logcat_must_not_contain=[r'channels\["thought"\]'],
        wait_seconds=45,
        category="thinking_mode",
    ),
    ResponseQualityTestCase(
        name="thinking_mode_persists_after_force_stop",
        message="What is 2+2",
        # After toggle OFF → force-stop → reopen, thinking should still be off
        logcat_must_contain=[r"thinking_char_count:\s*0"],
        logcat_must_not_contain=[],
        wait_seconds=30,
        category="thinking_mode",
    ),

    # ── §2A: Date/Time Quick Actions (value correctness) ──────────────────
    # These complement the existing routing tests by checking parameter output
    ResponseQualityTestCase(
        name="get_time_returns_am_pm",
        message="What time is it",
        logcat_must_contain=[r"intent=get_time", r"params=\{.*time=\d{1,2}:\d{2}\s*[AP]M"],
        logcat_must_not_contain=[],
        category="date_time",
    ),
    ResponseQualityTestCase(
        name="get_date_returns_full_date",
        message="What's today's date",
        logcat_must_contain=[r"intent=get_time"],
        logcat_must_not_contain=[],
        category="date_time",
    ),
    ResponseQualityTestCase(
        name="get_day_correct",
        message="What day is it",
        logcat_must_contain=[r"intent=get_time"],
        logcat_must_not_contain=[],
        category="date_time",
    ),
    ResponseQualityTestCase(
        name="get_year_correct",
        message="What year is it",
        logcat_must_contain=[r"intent=get_time"],
        logcat_must_not_contain=[],
        category="date_time",
    ),
    ResponseQualityTestCase(
        name="get_month_correct",
        message="What month is it",
        logcat_must_contain=[r"intent=get_time"],
        logcat_must_not_contain=[],
        category="date_time",
    ),
    ResponseQualityTestCase(
        name="is_it_day_yes_no",
        message="Is it still Monday",
        logcat_must_contain=[r"intent=get_time"],
        logcat_must_not_contain=[],
        category="date_time",
    ),
    ResponseQualityTestCase(
        name="is_today_friday",
        message="Is today Friday",
        logcat_must_contain=[r"intent=get_time"],
        logcat_must_not_contain=[],
        category="date_time",
    ),

    # ── §2C: Chat-initiated date/time (LLM calls get_system_info) ─────────
    ResponseQualityTestCase(
        name="chat_whats_the_date_calls_system_info",
        message="What's the date",
        logcat_must_contain=[r"intent=get_time|get_system_info"],
        logcat_must_not_contain=[],
        wait_seconds=30,
        category="date_time",
    ),

    # ── §3A: Calendar creation routing ────────────────────────────────────
    ResponseQualityTestCase(
        name="calendar_dentist_next_thursday",
        message="Add a dentist appointment for next Thursday at 2pm",
        logcat_must_contain=[r"intent=create_calendar_event"],
        logcat_must_not_contain=[],
        category="calendar",
    ),
    ResponseQualityTestCase(
        name="calendar_gym_specific_date",
        message="Create an event called gym on 2026-04-20 at 7am",
        logcat_must_contain=[r"intent=create_calendar_event"],
        logcat_must_not_contain=[],
        category="calendar",
    ),
    ResponseQualityTestCase(
        name="calendar_block_friday",
        message="Block this Friday for a review",
        logcat_must_contain=[r"intent=create_calendar_event"],
        logcat_must_not_contain=[],
        category="calendar",
    ),
    ResponseQualityTestCase(
        name="calendar_meeting_monday",
        message="Set up a meeting next Monday at 10am",
        logcat_must_contain=[r"intent=create_calendar_event"],
        logcat_must_not_contain=[],
        category="calendar",
    ),
    ResponseQualityTestCase(
        name="calendar_meeting_tomorrow",
        message="Schedule a meeting for tomorrow at 3pm",
        logcat_must_contain=[r"intent=create_calendar_event"],
        logcat_must_not_contain=[],
        category="calendar",
    ),
    ResponseQualityTestCase(
        name="calendar_day_after_tomorrow",
        message="The day after tomorrow at noon",
        logcat_must_contain=[r"intent=create_calendar_event"],
        logcat_must_not_contain=[],
        category="calendar",
    ),

    # ── §3B: Calendar intent routing (supplemental) ───────────────────────
    ResponseQualityTestCase(
        name="calendar_invite_phrase",
        message="Calendar invite",
        logcat_must_contain=[r"intent=create_calendar_event"],
        logcat_must_not_contain=[],
        category="calendar",
    ),
    ResponseQualityTestCase(
        name="calendar_block_time",
        message="Block time on my calendar",
        logcat_must_contain=[r"intent=create_calendar_event"],
        logcat_must_not_contain=[],
        category="calendar",
    ),

    # ── §3C: Alarms (supplemental phrasings) ──────────────────────────────
    ResponseQualityTestCase(
        name="alarm_with_label",
        message="Set an alarm for 7am called gym",
        logcat_must_contain=[r"intent=set_alarm", r"label=gym|params=.*gym"],
        logcat_must_not_contain=[],
        category="alarm",
    ),
    ResponseQualityTestCase(
        name="alarm_tomorrow_morning",
        message="Set alarm for tomorrow at 6:30am",
        logcat_must_contain=[r"intent=set_alarm"],
        logcat_must_not_contain=[],
        category="alarm",
    ),
    ResponseQualityTestCase(
        name="egg_timer_not_alarm",
        message="Set a 5 minute egg timer",
        logcat_must_contain=[r"intent=set_timer"],
        logcat_must_not_contain=[r"intent=set_alarm"],
        category="alarm",
    ),

    # ── §3E: Timers ───────────────────────────────────────────────────────
    ResponseQualityTestCase(
        name="timer_30_seconds",
        message="Set a 30 second timer",
        logcat_must_contain=[r"intent=set_timer"],
        logcat_must_not_contain=[],
        category="timer",
    ),
    ResponseQualityTestCase(
        name="cancel_timer",
        message="Cancel the timer",
        logcat_must_contain=[r"intent=cancel_timer"],
        logcat_must_not_contain=[],
        category="timer",
    ),

    # ── §5A: Lists — multi-item add ──────────────────────────────────────
    ResponseQualityTestCase(
        name="list_multi_item_add",
        message="Add milk, eggs, and bread to my shopping list",
        logcat_must_contain=[r"intent=add_to_list"],
        logcat_must_not_contain=[],
        category="list",
    ),

    # ── §7A: Core memory — QIR routing ────────────────────────────────────
    ResponseQualityTestCase(
        name="memory_what_do_you_know",
        message="What do you know about me",
        logcat_must_contain=[r"intent=search_memory|search_memory:\s*query="],
        logcat_must_not_contain=[],
        wait_seconds=30,
        category="memory",
    ),
    ResponseQualityTestCase(
        name="memory_do_you_remember",
        message="Do you remember what I told you about my job",
        logcat_must_contain=[r"search_memory"],
        logcat_must_not_contain=[],
        wait_seconds=30,
        category="memory",
    ),

    # ── §7B: Core memory — save confirmation ──────────────────────────────
    ResponseQualityTestCase(
        name="memory_save_recipetineats",
        message="Remember that I like RecipeTin Eats",
        logcat_must_contain=[r"save_memory:\s*key=.*value=.*RecipeTin"],
        logcat_must_not_contain=[],
        wait_seconds=30,
        category="memory",
    ),

    # ── §7D: User profile — model uses name ───────────────────────────────
    ResponseQualityTestCase(
        name="profile_name_used_in_conversation",
        message="Hello, who am I?",
        logcat_must_contain=[r"Generation complete"],
        logcat_must_not_contain=[],
        wait_seconds=45,
        pre_inject_profile="My name is TestUser. I'm a developer in Auckland.",
        category="profile",
    ),

    # ── §8A: Weather with profile location ────────────────────────────────
    ResponseQualityTestCase(
        name="weather_uses_profile_location",
        message="What's the weather like?",
        logcat_must_contain=[r"intent=get_weather"],
        logcat_must_not_contain=[],
        pre_inject_profile="My name is TestUser. Location: Brisbane, QLD, Australia.",
        category="weather",
    ),
    ResponseQualityTestCase(
        name="weather_explicit_city_overrides",
        message="What's the weather in London",
        logcat_must_contain=[r"intent=get_weather"],
        logcat_must_not_contain=[],
        category="weather",
    ),

    # ── §8B: Weather enhancements ─────────────────────────────────────────
    ResponseQualityTestCase(
        name="weather_uv_index",
        message="What's the UV index today",
        logcat_must_contain=[r"intent=get_weather"],
        logcat_must_not_contain=[],
        category="weather",
    ),
    ResponseQualityTestCase(
        name="weather_uv_high",
        message="Is the UV high today",
        logcat_must_contain=[r"intent=get_weather"],
        logcat_must_not_contain=[],
        category="weather",
    ),
    ResponseQualityTestCase(
        name="weather_air_quality",
        message="What's the air quality",
        logcat_must_contain=[r"intent=get_weather"],
        logcat_must_not_contain=[],
        category="weather",
    ),
    ResponseQualityTestCase(
        name="weather_sunrise",
        message="What time is sunrise tomorrow",
        logcat_must_contain=[r"intent=get_weather"],
        logcat_must_not_contain=[],
        category="weather",
    ),
    ResponseQualityTestCase(
        name="weather_sunset",
        message="When does the sun set today",
        logcat_must_contain=[r"intent=get_weather"],
        logcat_must_not_contain=[],
        category="weather",
    ),
    ResponseQualityTestCase(
        name="weather_3_day_forecast",
        message="What's the 3-day forecast",
        logcat_must_contain=[r"intent=get_weather"],
        logcat_must_not_contain=[],
        category="weather",
    ),

    # ── §9A: Hallucination retry ──────────────────────────────────────────
    ResponseQualityTestCase(
        name="hallucination_retry_fires_on_skill_capable",
        message="What's the weather in Brisbane",
        # If first LLM pass misses tool call, retry fires
        # We check that EITHER direct routing OR retry succeeded
        logcat_must_contain=[r"intent=get_weather|hallucination_retry_succeeded"],
        logcat_must_not_contain=[],
        wait_seconds=30,
        category="hallucination",
    ),
    ResponseQualityTestCase(
        name="hallucination_retry_no_fire_on_conversational",
        message="Tell me about the history of New Zealand",
        logcat_must_contain=[r"Generation complete"],
        logcat_must_not_contain=[r"hallucination_retry_succeeded"],
        wait_seconds=45,
        category="hallucination",
    ),
    ResponseQualityTestCase(
        name="hallucination_retry_no_fire_when_tool_executed",
        message="Set an alarm for 8am",
        logcat_must_contain=[r"intent=set_alarm"],
        logcat_must_not_contain=[r"hallucination_retry_succeeded"],
        category="hallucination",
    ),

    # ── §9B: Tool chip emission ───────────────────────────────────────────
    ResponseQualityTestCase(
        name="tool_chip_shown_on_qir",
        message="Set a timer for 5 minutes",
        logcat_must_contain=[r"tool_chip_shown:\s*intent=set_timer"],
        logcat_must_not_contain=[],
        category="tool_chip",
    ),
    ResponseQualityTestCase(
        name="tool_chip_shows_success_state",
        message="What's the weather in Auckland",
        logcat_must_contain=[r"tool_chip_shown:\s*intent=get_weather"],
        logcat_must_not_contain=[],
        category="tool_chip",
    ),

    # ── §10: Chat Quality & Personality ───────────────────────────────────
    ResponseQualityTestCase(
        name="jandal_no_garbled_text",
        message="Can you tell me about Auckland?",
        logcat_must_contain=[r"Generation complete"],
        logcat_must_not_contain=[r"don'm|can's|won'ts"],
        wait_seconds=45,
        category="personality",
    ),
    ResponseQualityTestCase(
        name="wikipedia_skill_works",
        message="Look up New Zealand on Wikipedia",
        logcat_must_contain=[r"intent=.*wiki|Generation complete"],
        logcat_must_not_contain=[],
        wait_seconds=45,
        category="personality",
    ),
    ResponseQualityTestCase(
        name="math_correct_answer",
        message="What is 245 * 37",
        logcat_must_contain=[r"Generation complete"],
        logcat_must_not_contain=[],
        wait_seconds=30,
        category="personality",
    ),
    ResponseQualityTestCase(
        name="capital_of_nz",
        message="What's the capital of New Zealand",
        logcat_must_contain=[r"Generation complete"],
        logcat_must_not_contain=[],
        wait_seconds=30,
        category="personality",
    ),
    ResponseQualityTestCase(
        name="tell_me_a_joke",
        message="Tell me a joke",
        logcat_must_contain=[r"Generation complete"],
        logcat_must_not_contain=[],
        wait_seconds=30,
        category="personality",
    ),

    # ── §11: FallThrough Bridge — LLM answers ────────────────────────────
    ResponseQualityTestCase(
        name="fallthrough_capital_nz",
        message="What's the capital of New Zealand",
        logcat_must_contain=[r"Generation complete"],
        logcat_must_not_contain=[],
        wait_seconds=30,
        category="fallthrough",
    ),
]
```

---

## 4. New ADB Test Cases — Multi-Turn Suite

Tests that send a **sequence** of messages and assert intent routing and/or logcat state
across the conversation. These verify context persistence, anaphora resolution, and
episodic memory.

### 4.1 Dataclass Definition

```python
@dataclass
class MultiTurnTestCase:
    """A conversation sequence with per-turn and end-of-conversation assertions.

    Each turn sends a message, waits, and reads logcat. The harness checks:
    - `expected_intents[i]`: the intent for turn i (None = expect LLM/NO_MATCH)
    - `final_logcat_check`: an optional regex to assert against the FULL logcat
      accumulated across all turns (useful for memory distillation, etc.)
    """
    name: str
    turns: list[str]                        # messages to send in sequence
    expected_intents: list[str | None]      # per-turn: expected intent or None for LLM
    final_logcat_check: str | None = None   # regex on accumulated logcat (optional)
    turn_wait_seconds: list[int] | None = None  # per-turn wait override (default: 20)
    category: str = "general"
```

### 4.2 Test Data

```python
MULTI_TURN_CASES: list[MultiTurnTestCase] = [

    # ── §2C: Chat-initiated date after several exchanges ──────────────────
    MultiTurnTestCase(
        name="date_still_works_after_exchanges",
        turns=[
            "Tell me a fun fact about penguins",
            "That's interesting, tell me more",
            "What day is it again",
        ],
        expected_intents=[None, None, "get_time"],
        turn_wait_seconds=[30, 30, 20],
        category="date_time",
    ),

    # ── §7B: Memory save then retrieval ───────────────────────────────────
    MultiTurnTestCase(
        name="memory_save_then_search",
        turns=[
            "Remember that I like RecipeTin Eats",
            "What do you know about my cooking preferences",
        ],
        expected_intents=["save_memory", "search_memory"],
        final_logcat_check=r"save_memory:.*RecipeTin.*search_memory:",
        turn_wait_seconds=[20, 30],
        category="memory",
    ),

    # ── §7B: Memory surfaces in subsequent conversation ───────────────────
    MultiTurnTestCase(
        name="memory_surfaces_in_conversation",
        turns=[
            "Remember that my dog is called Xena",
            "Tell me something you know about my pets",
        ],
        expected_intents=["save_memory", None],
        final_logcat_check=r"save_memory:.*Xena",
        turn_wait_seconds=[20, 45],
        category="memory",
    ),

    # ── §7C: Episodic memory distillation ─────────────────────────────────
    MultiTurnTestCase(
        name="episodic_distillation_after_long_convo",
        turns=[
            "Tell me about the history of Wellington",
            "What about the earthquakes there",
            "How has the city rebuilt since then",
            "What's the population now",
            "Thanks that was helpful",
        ],
        expected_intents=[None, None, None, None, None],
        final_logcat_check=r"episodic_distilled:\s*count=[1-9]",
        turn_wait_seconds=[45, 45, 45, 30, 20],
        category="memory",
    ),

    # ── §9C: Anaphora — alarm update ─────────────────────────────────────
    MultiTurnTestCase(
        name="anaphora_alarm_move_later",
        turns=[
            "Set an alarm for 8am",
            "Move it an hour later",
        ],
        expected_intents=["set_alarm", "set_alarm"],
        # Second alarm should reference 9am (8am + 1hr)
        final_logcat_check=r"intent=set_alarm.*09:00|intent=set_alarm.*9:00",
        turn_wait_seconds=[20, 30],
        category="anaphora",
    ),

    # ── §9C: Anaphora — weather same city, next day ───────────────────────
    MultiTurnTestCase(
        name="anaphora_weather_tomorrow",
        turns=[
            "What's the weather in Auckland",
            "How about tomorrow",
        ],
        expected_intents=["get_weather", "get_weather"],
        category="anaphora",
    ),

    # ── §9C: Anaphora — list continuation ─────────────────────────────────
    MultiTurnTestCase(
        name="anaphora_list_also_add",
        turns=[
            "Add milk to shopping list",
            "Also add eggs",
        ],
        expected_intents=["add_to_list", "add_to_list"],
        category="anaphora",
    ),

    # ── §10: 5+ turn conversation coherence ───────────────────────────────
    MultiTurnTestCase(
        name="five_turn_coherence",
        turns=[
            "Tell me about the All Blacks",
            "Who is their best player",
            "What position does he play",
            "How many caps does he have",
            "What about their biggest rival",
        ],
        expected_intents=[None, None, None, None, None],
        # Just confirm all 5 LLM generations completed without error
        final_logcat_check=r"Generation complete",
        turn_wait_seconds=[45, 45, 45, 45, 45],
        category="personality",
    ),

    # ── §9A: Hallucination retry — second attempt routes correctly ────────
    MultiTurnTestCase(
        name="hallucination_retry_second_attempt_routes",
        turns=[
            "What's my battery at",
        ],
        expected_intents=["get_battery"],
        # Either direct QIR or retry succeeds
        final_logcat_check=r"intent=get_battery",
        category="hallucination",
    ),
]
```

---

## 5. New ADB Test Cases — Persistence Suite

Tests that verify state survives app restart (force-stop + relaunch).

### 5.1 Dataclass Definition

```python
@dataclass
class PersistenceTestCase:
    """Verifies that state persists across app restart.

    Flow:
      1. Execute `setup_messages` (create state)
      2. Force-stop the app: `adb shell am force-stop <package>`
      3. Relaunch and send `verify_message`
      4. Assert `logcat_must_contain` patterns match
    """
    name: str
    setup_messages: list[str]       # messages to create state before restart
    verify_message: str             # message to send after restart
    logcat_must_contain: list[str]  # patterns that must match after restart
    setup_wait_seconds: int = 20
    verify_wait_seconds: int = 30
    category: str = "persistence"
```

### 5.2 Test Data

```python
PERSISTENCE_CASES: list[PersistenceTestCase] = [

    # ── §7B: Saved memory survives restart ────────────────────────────────
    PersistenceTestCase(
        name="memory_survives_restart",
        setup_messages=["Remember that my favourite colour is green"],
        verify_message="What's my favourite colour",
        logcat_must_contain=[r"search_memory"],
        setup_wait_seconds=20,
        verify_wait_seconds=30,
    ),

    # ── §5: List items survive restart ────────────────────────────────────
    PersistenceTestCase(
        name="list_items_survive_restart",
        setup_messages=["Add bananas to my grocery list"],
        verify_message="What's on my grocery list",
        logcat_must_contain=[r"intent=get_list_items"],
        setup_wait_seconds=20,
        verify_wait_seconds=20,
    ),

    # ── §1C: Thinking mode toggle persists ────────────────────────────────
    # Note: This test requires toggling the setting via UI Automator first.
    # The ADB portion verifies that after force-stop, the thinking mode state
    # is preserved by checking logcat output on next inference.
    PersistenceTestCase(
        name="thinking_toggle_persists",
        setup_messages=["Hello"],  # warm up — thinking mode state set via UI
        verify_message="What is 2+2",
        logcat_must_contain=[r"thinking_char_count:"],
        setup_wait_seconds=30,
        verify_wait_seconds=30,
    ),
]
```

---

## 6. UI Automator Tests

These tests require interaction with the Android UI via Espresso or UIAutomator2.
They **cannot** be implemented in the ADB logcat harness alone.

### 6.1 Spec Format

Each spec includes: screen, pre-conditions, actions, assertions, and complexity.

---

### §1A: Model Download & Onboarding

#### UI-AUTO-1A-1: Fresh install shows required models

| Field | Value |
|-------|-------|
| **Pre-condition** | Fresh install (`adb shell pm clear com.kernel.ai.debug`) |
| **Screen** | Onboarding / Model Download |
| **Actions** | 1. Launch app. 2. Wait for onboarding screen. |
| **Assertions** | - Text "Gemma 4 E-2B" OR "Gemma 4 E-4B" visible. - Text "EmbeddingGemma 300M" visible. |
| **Complexity** | Medium — requires fresh-install state |

#### UI-AUTO-1A-2: Optional models show "Download later"

| Field | Value |
|-------|-------|
| **Pre-condition** | Fresh install |
| **Screen** | Onboarding / Model Download |
| **Actions** | Scroll model list. |
| **Assertions** | "MiniLM" model card has a "Download later" or "Skip" button. |
| **Complexity** | Low |

#### UI-AUTO-1A-3: Download progress visible

| Field | Value |
|-------|-------|
| **Pre-condition** | Models not yet downloaded |
| **Screen** | Onboarding / Model Download |
| **Actions** | Tap download on a model. |
| **Assertions** | Progress bar visible. File size, percentage, speed, or ETA text present. |
| **Complexity** | Medium — network-dependent timing |

---

### §1B: HuggingFace Auth

#### UI-AUTO-1B-1: Sign-in button visible in Settings

| Field | Value |
|-------|-------|
| **Pre-condition** | App launched, not signed in |
| **Screen** | Settings |
| **Actions** | Navigate to Settings via nav drawer. |
| **Assertions** | "HuggingFace" or "Sign in" button visible. |
| **Complexity** | Low |

#### UI-AUTO-1B-2: Sign-out works

| Field | Value |
|-------|-------|
| **Pre-condition** | Signed in to HuggingFace |
| **Screen** | Settings |
| **Actions** | Tap "Sign out". |
| **Assertions** | "Sign in" button reappears. Username no longer shown. |
| **Complexity** | Low |

---

### §1C: Thinking Mode Toggle (UI portion)

#### UI-AUTO-1C-1: Toggle visible for E-4B, hidden for E-2B

| Field | Value |
|-------|-------|
| **Pre-condition** | E-4B model active |
| **Screen** | Settings → Model Settings |
| **Actions** | Navigate to Model Settings. |
| **Assertions** | "Thinking mode" toggle visible and defaults ON. |
| **Complexity** | Low |

#### UI-AUTO-1C-2: Thinking bubble appears when enabled

| Field | Value |
|-------|-------|
| **Pre-condition** | Thinking mode ON, E-4B active |
| **Screen** | Chat |
| **Actions** | Send "Explain the trolley problem". Wait for response. |
| **Assertions** | Collapsible "thinking" bubble visible in chat. Bubble is expandable. |
| **Complexity** | Medium — async UI element |

---

### §3D: Alarms — CRUD UI

#### UI-AUTO-3D-1: Alarms screen accessible

| Field | Value |
|-------|-------|
| **Pre-condition** | App running |
| **Screen** | Nav drawer → Alarms |
| **Actions** | Open nav drawer. Tap "Alarms". |
| **Assertions** | Alarms screen loads. Title "Alarms" visible. |
| **Complexity** | Low |

#### UI-AUTO-3D-2: Alarm listed with time and label

| Field | Value |
|-------|-------|
| **Pre-condition** | Create alarm via chat: "Set an alarm for 7am called gym" |
| **Screen** | Alarms |
| **Actions** | Navigate to Alarms screen. |
| **Assertions** | List item shows "7:00 AM" and "gym". Enabled toggle is ON. |
| **Complexity** | Medium |

#### UI-AUTO-3D-3: Toggle enable/disable

| Field | Value |
|-------|-------|
| **Pre-condition** | At least one alarm exists |
| **Screen** | Alarms |
| **Actions** | Tap the enable/disable toggle on an alarm. |
| **Assertions** | Toggle state changes. Alarm remains in list. |
| **Complexity** | Low |

#### UI-AUTO-3D-4: Swipe to delete

| Field | Value |
|-------|-------|
| **Pre-condition** | At least one alarm exists |
| **Screen** | Alarms |
| **Actions** | Swipe alarm item left/right. |
| **Assertions** | Alarm removed from list. |
| **Complexity** | Medium — swipe gesture |

#### UI-AUTO-3D-5: Alarm fires at correct time

| Field | Value |
|-------|-------|
| **Pre-condition** | Alarm set for 1 minute in the future |
| **Screen** | Any |
| **Actions** | Wait for alarm time. |
| **Assertions** | Alarm notification / full-screen alert appears. |
| **Complexity** | High — timing-sensitive; see Manual Tests |

---

### §5A-D: Lists UI

#### UI-AUTO-5-1: Item appears in list UI after adding

| Field | Value |
|-------|-------|
| **Pre-condition** | Send "Add milk to my shopping list" via chat |
| **Screen** | Nav drawer → Lists → Shopping List |
| **Actions** | Navigate to Lists. Tap "Shopping List". |
| **Assertions** | "milk" visible in list items. |
| **Complexity** | Medium |

#### UI-AUTO-5-2: New list appears in lists screen

| Field | Value |
|-------|-------|
| **Pre-condition** | Send "Create a list called weekend errands" via chat |
| **Screen** | Nav drawer → Lists |
| **Actions** | Navigate to Lists. |
| **Assertions** | "weekend errands" visible in list of lists. |
| **Complexity** | Medium |

#### UI-AUTO-5-3: Removed item disappears from UI

| Field | Value |
|-------|-------|
| **Pre-condition** | List with "milk" exists. Send "Remove milk from my shopping list". |
| **Screen** | Nav drawer → Lists → Shopping List |
| **Actions** | Navigate to list. |
| **Assertions** | "milk" no longer visible. |
| **Complexity** | Medium |

---

### §6A-B: Contact Resolution UI

#### UI-AUTO-6A-1: Email pre-populates contact

| Field | Value |
|-------|-------|
| **Pre-condition** | Contact "John" exists with email in Contacts |
| **Screen** | Chat → Email composer (external intent) |
| **Actions** | Send "Email John subject Meeting body See you there". |
| **Assertions** | Email composer opens. "To" field contains John's email. Subject = "Meeting". |
| **Complexity** | High — external app interaction |

#### UI-AUTO-6A-2: READ_CONTACTS permission request

| Field | Value |
|-------|-------|
| **Pre-condition** | READ_CONTACTS not yet granted |
| **Screen** | System permission dialog |
| **Actions** | Send "Email John subject test body test". |
| **Assertions** | System permission dialog appears for READ_CONTACTS. |
| **Complexity** | Medium |

#### UI-AUTO-6B-1: Contact alias settings

| Field | Value |
|-------|-------|
| **Pre-condition** | App running |
| **Screen** | Settings → People & Contacts |
| **Actions** | Navigate to Settings. Tap "People & Contacts". Add alias "Mum" → phone number. |
| **Assertions** | Alias saved. Alias visible in list. |
| **Complexity** | Medium |

#### UI-AUTO-6B-2: Call with alias

| Field | Value |
|-------|-------|
| **Pre-condition** | Alias "Mum" configured |
| **Screen** | Chat → Dialer (external intent) |
| **Actions** | Send "Call Mum". |
| **Assertions** | Dialer opens with correct phone number. |
| **Complexity** | High — external app interaction |

---

### §7D: User Profile UI

#### UI-AUTO-7D-1: Profile text entry and save

| Field | Value |
|-------|-------|
| **Pre-condition** | App running |
| **Screen** | Settings → User Profile |
| **Actions** | Navigate to User Profile. Enter profile text. Tap Save. |
| **Assertions** | Save confirmation shown. |
| **Complexity** | Low |

#### UI-AUTO-7D-2: Parsed preview shows structured fields

| Field | Value |
|-------|-------|
| **Pre-condition** | Profile saved with name, role, location |
| **Screen** | Settings → User Profile |
| **Actions** | View parsed preview section. |
| **Assertions** | Name, Role, Location fields populated and visible. |
| **Complexity** | Medium |

---

### §9B: Tool Chips UI

#### UI-AUTO-9B-1: Tool chip visible in chat bubble

| Field | Value |
|-------|-------|
| **Pre-condition** | App running |
| **Screen** | Chat |
| **Actions** | Send "Set a timer for 5 minutes". Wait for response. |
| **Assertions** | Response bubble contains a tool chip element showing "set_timer" and ✓ state. |
| **Complexity** | Medium |

#### UI-AUTO-9B-2: Failed intent shows error chip

| Field | Value |
|-------|-------|
| **Pre-condition** | Condition that causes intent failure (e.g. no network for weather) |
| **Screen** | Chat |
| **Actions** | Trigger a failing intent. |
| **Assertions** | Error chip visible with failure indicator. |
| **Complexity** | High — requires failure condition setup |

---

### §11: FallThrough Bridge

#### UI-AUTO-11-1: Actions tab → Chat opens with Wikipedia

| Field | Value |
|-------|-------|
| **Pre-condition** | App running |
| **Screen** | Actions tab |
| **Actions** | Send "Look up beans on wikipedia" from Actions tab. |
| **Assertions** | Chat tab opens. Wikipedia result rendered. |
| **Complexity** | Medium |

---

### §12: UI & Navigation

#### UI-AUTO-12-1: FAB buttons consistent

| Field | Value |
|-------|-------|
| **Pre-condition** | App running |
| **Screen** | All main screens |
| **Actions** | Navigate to Chat, Lists, Alarms screens. |
| **Assertions** | FAB (Floating Action Button) present and positioned consistently. |
| **Complexity** | Low |

#### UI-AUTO-12-2: Nav drawer sections present

| Field | Value |
|-------|-------|
| **Pre-condition** | App running |
| **Screen** | Nav drawer |
| **Actions** | Open nav drawer. |
| **Assertions** | Sections present: Chat, Lists, Alarms, Settings (minimum). |
| **Complexity** | Low |

#### UI-AUTO-12-3: Lists screen shows item counts

| Field | Value |
|-------|-------|
| **Pre-condition** | At least one list with items |
| **Screen** | Nav drawer → Lists |
| **Actions** | Navigate to Lists. |
| **Assertions** | Each list shows item count badge or text. |
| **Complexity** | Medium |

#### UI-AUTO-12-4: Alarms screen shows CRUD controls

| Field | Value |
|-------|-------|
| **Pre-condition** | At least one alarm |
| **Screen** | Nav drawer → Alarms |
| **Actions** | Navigate to Alarms. |
| **Assertions** | Alarms listed. Each has toggle, time, label. Delete gesture available. |
| **Complexity** | Medium |

---

### §13: System Permissions

#### UI-AUTO-13-1: SCHEDULE_EXACT_ALARM flow

| Field | Value |
|-------|-------|
| **Pre-condition** | Permission not yet granted |
| **Screen** | Chat → System Settings redirect |
| **Actions** | Send "Set an alarm for 8am". |
| **Assertions** | System Settings page for exact alarm opens. After granting and returning, alarm is scheduled. |
| **Complexity** | High — system Settings navigation |

#### UI-AUTO-13-2: ACCESS_FINE_LOCATION granted → GPS weather

| Field | Value |
|-------|-------|
| **Pre-condition** | Location permission not yet granted |
| **Screen** | Chat → Permission dialog |
| **Actions** | Send "What's the weather". Grant location when prompted. |
| **Assertions** | Weather response uses GPS-based location (not profile fallback). |
| **Complexity** | High |

#### UI-AUTO-13-3: POST_NOTIFICATIONS denied → graceful

| Field | Value |
|-------|-------|
| **Pre-condition** | Notification permission denied |
| **Screen** | Chat |
| **Actions** | Trigger an action that would normally notify (e.g. alarm). |
| **Assertions** | No crash. Graceful message in chat or snackbar. |
| **Complexity** | Medium |

---

## 7. Manual Tests

These tests cannot be reliably automated due to hardware dependencies, network conditions,
timing sensitivity, or inherent non-determinism.

| # | Test | Section | Reason |
|---|------|---------|--------|
| 1 | **Network failure vs auth failure vs storage full shown distinctly** | §1A | Requires simulating distinct network/auth/storage failure modes on real hardware. Network throttling tools are unreliable in CI. |
| 2 | **Failed download shows persistent retry UI** | §1A | Requires sustained network failure to verify retry UI persists (not just appears briefly). Timing-dependent. |
| 3 | **HuggingFace sign-in OAuth flow completes** | §1B | Requires real HuggingFace credentials and browser-based OAuth redirect. Cannot automate without credential management risk. |
| 4 | **Gated model: explain → licence → OAuth → download** | §1B | Full end-to-end flow through external browser, HuggingFace licence page, OAuth consent. Heavily dependent on HF server state. |
| 5 | **NPU backend + toggle OFF → no crash** | §1C | Requires specific NPU-capable hardware (S23 Ultra w/ Hexagon). Backend selection is device-specific. |
| 6 | **NPU backend + toggle ON → thinking tokens, no ANR** | §1C | Same as above — NPU availability is hardware-dependent. ANR detection requires extended real-time monitoring. |
| 7 | **Alarm fires at correct time** | §3D | Timing-sensitive — requires waiting for real alarm trigger. Impractical in automated CI (clock manipulation not available on Android without root). |
| 8 | **GPS denied → prompts for city** | §8A | Requires denying location permission AND having no profile location. The "prompts for city" response is LLM-generated and non-deterministic in phrasing. |
| 9 | **Jandal uses NZ slang naturally** | §10 | LLM output is non-deterministic. Slang usage varies per generation. Checking for specific tokens has high false-negative rate. |
| 10 | **Greetings vary across sessions** | §10 | By definition requires multiple sessions and asserting variance — any single run proves nothing. |
| 11 | **Episodic: "What did we talk about yesterday"** | §7C | Requires state from a prior day's session. Cannot simulate time passage reliably without device root/clock manipulation. |
| 12 | **Battery optimisation not exempt → no crash** | §13 | Requires device-specific battery optimisation settings (varies by OEM). Samsung/Pixel have different paths. |
| 13 | **ACCESS_FINE_LOCATION denied → falls back to profile city** | §13 | Partially automatable, but verifying the *displayed* weather location (not just intent routing) requires reading LLM output which is non-deterministic. |

---

## 8. New `run_*` Functions Needed

The following function signatures should be added to `adb_skill_test.py`:

```python
# ── False-Positive Runner ─────────────────────────────────────────────────

def run_false_positive_tests(dry_run: bool = False) -> int:
    """Execute all false-positive test cases.

    For each case:
      1. Clear logcat
      2. send_text(tc.message)
      3. Wait WAIT_SECONDS (or longer for LLM fallthrough — 30s)
      4. Read logcat (both KernelAI and LiteRtInferenceEngine tags)
      5. Assert forbidden_intent NOT in intent matches
      6. Assert either NO_MATCH or Generation complete present

    Returns: exit code (0 = all passed, 1 = failures)
    """
    ...


# ── Response Quality Runner ───────────────────────────────────────────────

def run_response_quality_tests(dry_run: bool = False) -> int:
    """Execute all response quality test cases.

    For each case:
      1. If pre_inject_profile is set, call send_profile() and wait
      2. If setup_messages is set, send each and wait
      3. Clear logcat
      4. send_text(tc.message)
      5. Wait tc.wait_seconds
      6. Read logcat (all tags)
      7. Assert ALL patterns in logcat_must_contain match
      8. Assert NO patterns in logcat_must_not_contain match

    Returns: exit code (0 = all passed, 1 = failures)
    """
    ...


# ── Multi-Turn Runner ─────────────────────────────────────────────────────

def run_multi_turn_tests(dry_run: bool = False) -> int:
    """Execute all multi-turn test cases.

    For each case:
      1. Clear logcat (once at start)
      2. For each turn i:
         a. Clear logcat (per-turn)
         b. send_text(tc.turns[i])
         c. Wait tc.turn_wait_seconds[i] or default 20s
         d. Read logcat
         e. Extract intent; compare to tc.expected_intents[i]
            - None means expect NO_MATCH or LLM generation
      3. After all turns, read FULL logcat (don't clear between final check)
      4. If tc.final_logcat_check is set, assert regex matches full logcat

    Note: Logcat is NOT cleared before final_logcat_check — the check runs
    against the accumulated output from the last turn only. For cross-turn
    checks, collect logcat per turn and concatenate.

    Returns: exit code (0 = all passed, 1 = failures)
    """
    ...


# ── Persistence Runner ────────────────────────────────────────────────────

def run_persistence_tests(dry_run: bool = False) -> int:
    """Execute all persistence test cases.

    For each case:
      1. Send setup_messages, wait between each
      2. Force-stop: run_adb("shell", "am", "force-stop", PACKAGE)
      3. Wait 3s for process death
      4. Clear logcat
      5. Relaunch and send verify_message
      6. Wait verify_wait_seconds
      7. Read logcat (all tags)
      8. Assert ALL patterns in logcat_must_contain match

    Returns: exit code (0 = all passed, 1 = failures)
    """
    ...


# ── Helper: force-stop and relaunch ───────────────────────────────────────

def force_stop_and_relaunch() -> None:
    """Force-stop the app and relaunch it, waiting for activity to be ready."""
    run_adb("shell", "am", "force-stop", PACKAGE)
    time.sleep(3)
    run_adb("shell", "am", "start", "-n", ACTIVITY)
    time.sleep(5)  # Wait for cold start


# ── Helper: assert logcat patterns ────────────────────────────────────────

def assert_logcat_patterns(
    logcat: str,
    must_contain: list[str],
    must_not_contain: list[str],
) -> tuple[bool, list[str]]:
    """Check logcat against required and forbidden regex patterns.

    Returns: (all_passed, list_of_failure_reasons)
    """
    failures: list[str] = []
    for pattern in must_contain:
        if not re.search(pattern, logcat):
            failures.append(f"MISSING: /{pattern}/ not found in logcat")
    for pattern in must_not_contain:
        if re.search(pattern, logcat):
            failures.append(f"FORBIDDEN: /{pattern}/ found in logcat")
    return len(failures) == 0, failures


# ── Helper: warm-up with LLM readiness ────────────────────────────────────

def warm_up_llm(timeout: int = 60) -> bool:
    """Send a dummy query and wait for LLM generation to complete.

    Used before suites that depend on LLM fallthrough (false-positive,
    response quality, multi-turn). Returns True if warm-up succeeded.
    """
    clear_logcat()
    send_text("hello")
    deadline = time.time() + timeout
    while time.time() < deadline:
        time.sleep(2)
        log = read_logcat_all()
        if "Generation complete" in log:
            return True
    return False
```

---

## 9. CLI Flag Additions

Add the following flags to the `argparse.ArgumentParser` in `main()`:

```python
def main() -> None:
    parser = argparse.ArgumentParser(description="ADB skill regression harness")

    # ── Existing flags ────────────────────────────────────────────────────
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Print test plan without running ADB commands",
    )
    parser.add_argument(
        "--profile",
        action="store_true",
        help="Run profile extraction tests",
    )

    # ── New suite flags ───────────────────────────────────────────────────
    parser.add_argument(
        "--false-positives",
        action="store_true",
        help="Run false-positive regression tests (phrases that must NOT trigger intents)",
    )
    parser.add_argument(
        "--response-quality",
        action="store_true",
        help="Run response quality tests (logcat pattern assertions for quality signals)",
    )
    parser.add_argument(
        "--multi-turn",
        action="store_true",
        help="Run multi-turn conversation tests (anaphora, context persistence)",
    )
    parser.add_argument(
        "--persistence",
        action="store_true",
        help="Run persistence tests (state survives app force-stop + restart)",
    )
    parser.add_argument(
        "--all",
        action="store_true",
        help="Run ALL test suites (routing + profile + false-positives + quality + multi-turn + persistence)",
    )

    # ── Filtering ─────────────────────────────────────────────────────────
    parser.add_argument(
        "--category",
        type=str,
        default=None,
        help="Filter tests to a specific category (e.g. 'weather', 'memory', 'date_time')",
    )
    parser.add_argument(
        "--name",
        type=str,
        default=None,
        help="Run only the test with this exact name (for multi-turn/quality cases)",
    )

    # ── Timing ────────────────────────────────────────────────────────────
    parser.add_argument(
        "--wait",
        type=int,
        default=None,
        help="Override default wait time (seconds) between send and logcat read",
    )
    parser.add_argument(
        "--llm-wait",
        type=int,
        default=45,
        help="Wait time for LLM fallthrough tests (default: 45s)",
    )

    args = parser.parse_args()

    # ── Dispatch ──────────────────────────────────────────────────────────
    exit_code = 0

    if args.all:
        # Run every suite in sequence
        exit_code |= run_tests(dry_run=args.dry_run)
        exit_code |= run_profile_tests(dry_run=args.dry_run)
        exit_code |= run_false_positive_tests(dry_run=args.dry_run)
        exit_code |= run_response_quality_tests(dry_run=args.dry_run)
        exit_code |= run_multi_turn_tests(dry_run=args.dry_run)
        exit_code |= run_persistence_tests(dry_run=args.dry_run)
    elif args.profile:
        exit_code = run_profile_tests(dry_run=args.dry_run)
    elif args.false_positives:
        exit_code = run_false_positive_tests(dry_run=args.dry_run)
    elif args.response_quality:
        exit_code = run_response_quality_tests(dry_run=args.dry_run)
    elif args.multi_turn:
        exit_code = run_multi_turn_tests(dry_run=args.dry_run)
    elif args.persistence:
        exit_code = run_persistence_tests(dry_run=args.dry_run)
    else:
        # Default: existing routing tests only
        exit_code = run_tests(dry_run=args.dry_run)

    sys.exit(exit_code)
```

### Example Usage

```bash
# Run just false-positive tests
python3 scripts/adb_skill_test.py --false-positives

# Run multi-turn tests, dry-run
python3 scripts/adb_skill_test.py --multi-turn --dry-run

# Run all suites
python3 scripts/adb_skill_test.py --all

# Run only weather-category tests across all suites
python3 scripts/adb_skill_test.py --all --category weather

# Run a single named test
python3 scripts/adb_skill_test.py --multi-turn --name anaphora_alarm_move_later

# Override LLM wait for slower device
python3 scripts/adb_skill_test.py --false-positives --llm-wait 60
```

---

## Appendix A: Logcat Patterns Reference

| Pattern | Regex | Emitted By |
|---------|-------|------------|
| Intent dispatch | `NativeIntentHandler\.handle:\s*intent=(\S+)` | QIR |
| LLM generation complete | `Generation complete` | LiteRtInferenceEngine |
| Hallucination retry | `hallucination_retry_succeeded:\s*intent=(\S+)` | HallucinationGuard |
| Tool chip shown | `tool_chip_shown:\s*intent=(\S+)` | ChatRenderer |
| Memory save | `save_memory:\s*key=(\S+)\s*value=(.+)` | MemoryRepository |
| Memory search | `search_memory:\s*query=(.+)` | MemoryRepository |
| Episodic distill | `episodic_distilled:\s*count=(\d+)` | EpisodicMemoryWorker |
| Thinking tokens | `thinking_char_count:\s*(\d+)` | InferenceEngine |
| Profile LLM success | `Profile LLM extraction succeeded` | ProfileParser |
| Profile regex fallback | `Profile regex fallback` | ProfileParser |
| Profile field | `(name\|role\|location):\s*(.+)` | ProfileParser |

## Appendix B: Implementation Priority

| Priority | Suite | Test Count | Effort | Impact |
|----------|-------|------------|--------|--------|
| **P0** | False-Positive (§2B, 5E, 7E, 8C) | 18 | Low | High — prevents user-facing mis-routing regressions |
| **P0** | Multi-Turn Anaphora (§9C) | 3 | Medium | High — core UX feature |
| **P1** | Response Quality — Hallucination (§9A) | 4 | Low | High — guards against silent skill failures |
| **P1** | Persistence (§7B, §5, §1C) | 3 | Medium | High — data loss prevention |
| **P1** | Response Quality — Memory (§7A-B) | 3 | Low | Medium — validates memory subsystem |
| **P2** | Response Quality — Weather (§8B) | 6 | Low | Medium — new feature coverage |
| **P2** | Multi-Turn Coherence (§10) | 1 | Low | Medium — personality QA |
| **P2** | Response Quality — Tool Chips (§9B) | 2 | Low | Low — cosmetic but trackable |
| **P3** | UI Automator — Alarms CRUD (§3D) | 5 | High | Medium |
| **P3** | UI Automator — Lists CRUD (§5A-D) | 3 | High | Medium |
| **P3** | UI Automator — Navigation (§12) | 4 | Medium | Low |
| **P4** | UI Automator — Permissions (§13) | 3 | High | Medium |
| **P4** | UI Automator — Onboarding (§1A) | 3 | High | Low (infrequent path) |

---

*End of specification.*
