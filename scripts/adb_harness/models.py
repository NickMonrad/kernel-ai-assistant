"""
ADB Skill Harness — data models (dataclasses).

TestCase, TestResult, ProfileTestCase, LLMToolsTestCase, LLMToolsResult.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field


# Helper used by TestCase.__post_init__
def _slugify(text: str, max_len: int = 50) -> str:
    """Create a short stable identifier from a test message."""
    slug = re.sub(r"[^\w\s]", "", text).strip().lower()
    slug = re.sub(r"\s+", "_", slug)
    slug = slug[:max_len].rstrip("_")
    return slug if slug else "unnamed"


@dataclass
class ProfileTestCase:
    """A single profile test case."""
    name: str
    profile_text: str
    expect_name: str | None = None
    expect_role_contains: str | None = None
    expect_location_contains: str | None = None


@dataclass
class TestCase:
    """A single intent-routing test case."""
    message: str
    expect_intent: str = ""
    xfail: bool = False
    expect_reply_contains: str | None = None
    expect_params: dict[str, str] | None = None
    slot_reply: str | None = None
    expect_log_contains: str | None = None
    confirm_reply: str | None = None
    # Audit metadata (issue #1163)
    id: str = ""
    category: str = "deterministic"
    tags: list[str] = field(default_factory=list)
    fixture: str | None = None
    xfail_reason: str | None = None
    expect_initial_log_contains: str | None = None

    def __post_init__(self) -> None:
        if not self.id:
            self.id = _slugify(self.message)
        if self.xfail and not self.xfail_reason:
            self.xfail_reason = "(no reason given)"


@dataclass
class LLMToolsTestCase:
    """Test case for the llm_tools harness phase."""
    name: str
    message: str
    expected_top_level_tool: str
    expected_nested_intent: str | None = None
    expected_fields: dict[str, str] | None = None
    expected_result_mode: str = "unknown"
    expect_no_regex_match: bool = True
    expect_no_classifier_match: bool = True
    expect_no_slot_fill: bool = True
    expect_no_retry: bool = True


@dataclass
class TestResult:
    """Structured outcome of a single test case."""
    index: int
    message: str
    expect_intent: str
    actual_intent: str | None
    expect_params: dict[str, str] | None
    actual_params: dict[str, str]
    intent_passed: bool
    params_passed: bool
    param_failures: list[str]
    xfail: bool
    reply_warn: str | None
    log_check_warn: str | None
    first_turn_warn: str | None = None
    phase: str = ""
    # Case metadata (issue #1163)
    case_id: str = ""
    category: str = "deterministic"
    tags: list[str] = field(default_factory=list)
    fixture: str | None = None
    xfail_reason: str | None = None
    status: str = ""
    failure_bucket: str | None = None


def is_clean_pass(r: TestResult) -> bool:
    """Return True when a result is a clean pass (no warnings, both checks pass)."""
    return r.intent_passed and r.params_passed and not r.log_check_warn


def derive_status(r: TestResult) -> str:
    """Derive a lightweight status string from a TestResult."""
    if r.xfail and is_clean_pass(r):
        return "xpass"
    if is_clean_pass(r):
        return "pass"
    if r.xfail and not r.intent_passed:
        return "xfail"
    return "fail"


def derive_failure_bucket(r: TestResult) -> str | None:
    """Derive a lightweight failure bucket for a TestResult.

    Precedence (first match wins):
    1. clean pass → None
    2. xfail_reason has a leading <bucket>: prefix → that bucket
    3. first_turn_warn or log_check_warn mentioning missing slot prompt / NeedsSlot → slot_fill_missing
    4. tag slot_fill_invalid_answer → slot_fill_invalid_reply
    5. param_failures → field_mismatch
    6. category is 'ambiguous' or tag 'ambiguous' → stale_or_ambiguous_expectation
    7. tag media_context → media_context_missing
    8. tag location_context or fixture starts with 'location:' → location_or_permission_missing
    9. tag fixture_required / contact_fixture_required or fixture starts with 'contacts:' / 'apps:' → fixture_missing
    10. tag device_state → device_state_side_effect
    11. actual_intent present and differs from expect_intent → wrong_tool
    12. log_check_warn present → harness_or_logcat_issue
    13. actual_intent empty → regex_or_qir_miss
    14. fallback → harness_or_logcat_issue
    """
    if is_clean_pass(r):
        return None
    if r.xfail_reason and ":" in r.xfail_reason:
        bucket = r.xfail_reason.split(":", 1)[0].strip()
        if bucket:
            return bucket
    # slot_fill_missing
    if r.first_turn_warn and ("initial slot prompt" in r.first_turn_warn or "NeedsSlot" in r.first_turn_warn):
        return "slot_fill_missing"
    if r.log_check_warn and ("NeedsSlot" in r.log_check_warn or "initial slot prompt" in r.log_check_warn):
        return "slot_fill_missing"
    if "slot_fill_invalid_answer" in r.tags:
        return "slot_fill_invalid_reply"
    # field_mismatch
    if r.param_failures:
        return "field_mismatch"
    # stale_or_ambiguous_expectation
    if r.category == "ambiguous" or "ambiguous" in r.tags:
        return "stale_or_ambiguous_expectation"
    # media_context_missing
    if "media_context" in r.tags:
        return "media_context_missing"
    # location_or_permission_missing
    if "location_context" in r.tags or (r.fixture and r.fixture.startswith("location:")):
        return "location_or_permission_missing"
    # fixture_missing
    if ("fixture_required" in r.tags or "contact_fixture_required" in r.tags
            or (r.fixture and r.fixture.startswith("contacts:"))
            or (r.fixture and r.fixture.startswith("apps:"))):
        return "fixture_missing"
    # device_state_side_effect
    if "device_state" in r.tags:
        return "device_state_side_effect"
    # wrong_tool
    if r.actual_intent and r.expect_intent and r.actual_intent != r.expect_intent:
        return "wrong_tool"
    # harness_or_logcat_issue
    if r.log_check_warn:
        return "harness_or_logcat_issue"
    # regex_or_qir_miss
    if not r.actual_intent:
        return "regex_or_qir_miss"
    # fallback
    return "harness_or_logcat_issue"


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

