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
