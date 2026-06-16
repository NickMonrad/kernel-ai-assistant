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
    slot_replies: list[str] | None = None
    expect_log_contains: str | None = None
    confirm_reply: str | None = None
    # False-positive / negative-routing metadata (issue #1272)
    forbidden_intents: list[str] = field(default_factory=list)
    allowed_intents: list[str] | None = None
    expect_llm_fallthrough: bool = False
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
        if self.slot_reply is not None and self.slot_replies is not None:
            import warnings
            warnings.warn(
                f"TestCase '{self.id}': both slot_reply and slot_replies set — "
                f"slot_replies takes precedence"
            )

    @property
    def effective_slot_replies(self) -> list[str] | None:
        """Return slot_replies if set, otherwise wrap slot_reply as a single-item list."""
        if self.slot_replies is not None:
            return self.slot_replies
        if self.slot_reply is not None:
            return [self.slot_reply]
        return None


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
    # When True, the model should NOT call any tool — answer from injected context.
    # Skips tool-name, marker-existence, and message-saved checks. (#1074)
    expect_no_tool_call: bool = False
    # When set, the accumulated logcat buffer must contain this substring. (#1074)
    expect_log_contains: str | None = None
    # When set, the final assistant reply must contain at least one of these
    # substrings (case-insensitive). Proves seeded context influenced the reply. (#1074)
    expected_reply_contains: list[str] | None = None

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
    expect_log_contains: str | None = None
    # False-positive / negative-routing evidence (issue #1272)
    forbidden_intents: list[str] = field(default_factory=list)
    forbidden_intent_triggered: bool = False
    forbidden_intent_observed: list[str] = field(default_factory=list)
    fallthrough_observed: bool = False


def is_clean_pass(r: TestResult) -> bool:
    """Return True when a result is a clean pass (no warnings, both checks pass)."""
    return r.intent_passed and r.params_passed and not r.log_check_warn


def _observed_expected_failure(r: TestResult) -> bool:
    """Return True when an xfail test observed its expected failure mode.

    An xfail test describes *what* it expects to happen, but that expectation
    encodes the failure mode — not the real/ideal success criteria.
    We distinguish by checking whether the result matches the failure pattern:

      1. expect_log_contains was set and was *found* in logcat
         (log_check_warn is None). This handles orchestrator recovery tests
         that assert a specific NotActionable / AskConfirmation log line.
      2. expect_intent="" AND no actual intent was observed (empty or None).
         This covers orchestrator not-actionable / no-dispatch paths where
         the test asserts no intent fires.
      3. intent_passed is False — the routing failure the xfail anticipates
         actually occurred.
      4. log_check_warn is present AND matches the expected failure reason.

    If none of these hold and the result is a clean pass, the xfail test
    unexpectedly succeeded in the real sense → xpass.
    """
    # Criterion 1: log-based assertion found its expected content
    if r.expect_log_contains and r.log_check_warn is None:
        return True
    # Criterion 2: no-intent assertion held
    if r.expect_intent == "" and not r.actual_intent:
        return True
    # Criterion 3: expected routing failure
    if not r.intent_passed:
        return True
    # Criterion 4: log_check_warn present (expected log not found) but
    # the test is xfail because of a *different* gap — still observed the
    # expected failure mode even if the specific log pattern was missed.
    if r.log_check_warn:
        return True
    return False


def derive_status(r: TestResult) -> str:
    """Derive a lightweight status string from a TestResult.

    Semantics:
      * xfail + observed expected failure mode → "xfail"
      * xfail + unexpectedly satisfies real success criteria → "xpass"
      * non-xfail clean pass → "pass"
      * non-xfail failure → "fail"
      * false-positive test with no forbidden intent but no fallthrough
        evidence either → "indeterminate"

    xpass does NOT affect the process exit code by default — it is
    informational, signalling that an expected failure may now be resolveable.
    Set XPASS_IS_FAILURE=1 environment variable to make xpass exit non-zero.
    """
    # False-positive tests: require fallthrough evidence for pass
    if r.forbidden_intents:
        if r.forbidden_intent_triggered:
            return "xfail" if r.xfail else "fail"
        if not r.fallthrough_observed:
            return "indeterminate"
        return "xpass" if r.xfail else "pass"
    # Existing logic for normal tests
    if r.xfail:
        if _observed_expected_failure(r):
            return "xfail"
        if is_clean_pass(r):
            return "xpass"
        return "xfail"
    if is_clean_pass(r):
        return "pass"
    return "fail"


def derive_failure_bucket(r: TestResult) -> str | None:
    """Derive a lightweight failure bucket for a TestResult.

    Precedence (first match wins):
    1. clean pass → None
    2. false-positive: forbidden intent fired → forbidden_intent_fired
    3. false-positive: no fallthrough evidence → false_positive_no_fallthrough
    4. xfail_reason has a leading <bucket>: prefix → that bucket
    5. first_turn_warn or log_check_warn mentioning missing slot prompt / NeedsSlot → slot_fill_missing
    6. tag slot_fill_invalid_answer → slot_fill_invalid_reply
    7. param_failures → field_mismatch
    8. category is 'ambiguous' or tag 'ambiguous' → stale_or_ambiguous_expectation
    9. tag media_context → media_context_missing
    10. tag location_context or fixture starts with 'location:' → location_or_permission_missing
    11. tag fixture_required / contact_fixture_required or fixture starts with 'contacts:' / 'apps:' → fixture_missing
    12. tag device_state → device_state_side_effect
    13. actual_intent present and differs from expect_intent → wrong_tool
    14. log_check_warn present → harness_or_logcat_issue
    15. actual_intent empty → regex_or_qir_miss
    16. fallback → harness_or_logcat_issue
    """
    if is_clean_pass(r):
        return None
    # False-positive buckets (high priority)
    if r.forbidden_intent_triggered:
        return "forbidden_intent_fired"
    if r.forbidden_intents and not r.fallthrough_observed:
        return "false_positive_no_fallthrough"
    # xfail_reason-based bucket extraction
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
    # #1074 additions
    no_tool_call_requested: bool = False
    log_contains_required: str | None = None
    log_contains_match: bool = False
    # Reply content assertion (#1074)
    expected_reply_terms: list[str] | None = None
    reply_terms_match: bool = False
