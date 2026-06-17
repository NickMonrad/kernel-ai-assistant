#!/usr/bin/env python3
"""Tests for false-positive / negative-routing assertion logic (#1272).

Covers:
- TestCase constructor with forbidden_intents, allowed_intents, expect_llm_fallthrough
- TestResult false-positive fields
- derive_status for false-positive tests (pass, fail, indeterminate)
- derive_failure_bucket for false-positive tests
- Selection of false-positive cases via --categories false_positive
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT_DIR = HERE.parent
sys.path.insert(0, str(SCRIPT_DIR))

from adb_harness.models import (
    TestCase,
    TestResult,
    derive_status,
    derive_failure_bucket,
)
from adb_harness.selectors import _select_tests
from adb_harness.cases import PHASES


class FalsePositiveTestCaseModelTest(unittest.TestCase):
    """TestCase construction with false-positive metadata."""

    def test_forbidden_intents_default(self) -> None:
        """TestCase without forbidden_intents defaults to empty list."""
        tc = TestCase("test message", "get_time")
        self.assertEqual(tc.forbidden_intents, [])

    def test_forbidden_intents_set(self) -> None:
        """TestCase with forbidden_intents stores them."""
        tc = TestCase("What year is this movie set in",
                      forbidden_intents=["get_time"],
                      expect_llm_fallthrough=True)
        self.assertEqual(tc.forbidden_intents, ["get_time"])
        self.assertTrue(tc.expect_llm_fallthrough)

    def test_allowed_intents(self) -> None:
        """TestCase with allowed_intents stores them."""
        tc = TestCase("Set a 5 minute egg timer",
                      forbidden_intents=["set_alarm"],
                      allowed_intents=["set_timer"],
                      expect_llm_fallthrough=True)
        self.assertEqual(tc.allowed_intents, ["set_timer"])

    def test_multiple_forbidden_intents(self) -> None:
        """TestCase can have multiple forbidden intents."""
        tc = TestCase("List all the capitals of Europe",
                      forbidden_intents=["create_list", "get_list_items"])
        self.assertEqual(len(tc.forbidden_intents), 2)

    def test_slug_id_still_generated(self) -> None:
        """Auto-generated slug id still works with new fields."""
        tc = TestCase("What's the weather like in Game of Thrones",
                      forbidden_intents=["get_weather"])
        self.assertEqual(tc.id, "whats_the_weather_like_in_game_of_thrones")


class FalsePositiveStatusDeriveTest(unittest.TestCase):
    """derive_status logic for false-positive results."""

    def test_false_positive_pass(self) -> None:
        """False-positive test: no forbidden intent + fallthrough → pass."""
        r = TestResult(
            index=1,
            message="What year is this movie set in",
            expect_intent="",
            actual_intent=None,
            expect_params=None,
            actual_params={},
            intent_passed=True,
            params_passed=True,
            param_failures=[],
            xfail=False,
            reply_warn=None,
            log_check_warn=None,
            forbidden_intents=["get_time"],
            forbidden_intent_triggered=False,
            fallthrough_observed=True,
        )
        self.assertEqual(derive_status(r), "pass")

    def test_false_positive_forbidden_fired(self) -> None:
        """False-positive test: forbidden intent triggered → fail."""
        r = TestResult(
            index=1,
            message="What year is this movie set in",
            expect_intent="",
            actual_intent="get_time",
            expect_params=None,
            actual_params={},
            intent_passed=False,
            params_passed=True,
            param_failures=[],
            xfail=False,
            reply_warn=None,
            log_check_warn="forbidden intent 'get_time' was triggered",
            forbidden_intents=["get_time"],
            forbidden_intent_triggered=True,
            forbidden_intent_observed=["get_time"],
            fallthrough_observed=False,
        )
        self.assertEqual(derive_status(r), "fail")

    def test_false_positive_indeterminate(self) -> None:
        """False-positive test: no forbidden but also no fallthrough → indeterminate."""
        r = TestResult(
            index=1,
            message="What year is this movie set in",
            expect_intent="",
            actual_intent=None,
            expect_params=None,
            actual_params={},
            intent_passed=True,
            params_passed=True,
            param_failures=[],
            xfail=False,
            reply_warn=None,
            log_check_warn="no fallthrough/LLM generation evidence",
            forbidden_intents=["get_time"],
            forbidden_intent_triggered=False,
            expect_llm_fallthrough=True,
            fallthrough_observed=False,
        )
        self.assertEqual(derive_status(r), "indeterminate")

    def test_false_positive_xfail_forbidden_fired(self) -> None:
        """False-positive xfail test: forbidden intent triggered → xfail."""
        r = TestResult(
            index=1,
            message="What year is this movie set in",
            expect_intent="",
            actual_intent="get_time",
            expect_params=None,
            actual_params={},
            intent_passed=False,
            params_passed=True,
            param_failures=[],
            xfail=True,
            reply_warn=None,
            log_check_warn="forbidden intent 'get_time' was triggered",
            forbidden_intents=["get_time"],
            forbidden_intent_triggered=True,
            forbidden_intent_observed=["get_time"],
            fallthrough_observed=False,
        )
        self.assertEqual(derive_status(r), "xfail")

    def test_false_positive_xpass(self) -> None:
        """False-positive xfail test: pass when expected to fail → xpass."""
        r = TestResult(
            index=1,
            message="What year is this movie set in",
            expect_intent="",
            actual_intent=None,
            expect_params=None,
            actual_params={},
            intent_passed=True,
            params_passed=True,
            param_failures=[],
            xfail=True,
            reply_warn=None,
            log_check_warn=None,
            forbidden_intents=["get_time"],
            forbidden_intent_triggered=False,
            expect_llm_fallthrough=True,
            fallthrough_observed=True,
        )
        self.assertEqual(derive_status(r), "xpass")

    # ── New oracle semantics (review fixes) ─────────────────────────────

    def test_allowed_intent_safe_route_pass(self) -> None:
        """Allowed safe native route observed → pass (no fallthrough needed)."""
        r = TestResult(
            index=1,
            message="Set a 5 minute egg timer",
            expect_intent="",
            actual_intent="set_timer",
            expect_params=None,
            actual_params={},
            intent_passed=True,
            params_passed=True,
            param_failures=[],
            xfail=False,
            reply_warn=None,
            log_check_warn=None,
            forbidden_intents=["set_alarm"],
            allowed_intent_observed="set_timer",
            forbidden_intent_triggered=False,
            fallthrough_observed=False,
        )
        self.assertEqual(derive_status(r), "pass")

    def test_forbidden_wins_over_allowed(self) -> None:
        """Both forbidden and allowed observed → forbidden wins (fail)."""
        r = TestResult(
            index=1,
            message="Set a 5 minute egg timer",
            expect_intent="",
            actual_intent="set_alarm",
            expect_params=None,
            actual_params={},
            intent_passed=False,
            params_passed=True,
            param_failures=[],
            xfail=False,
            reply_warn=None,
            log_check_warn="forbidden intent 'set_alarm' was triggered",
            forbidden_intents=["set_alarm"],
            allowed_intent_observed="set_timer",
            forbidden_intent_triggered=True,
            forbidden_intent_observed=["set_alarm"],
            fallthrough_observed=False,
        )
        self.assertEqual(derive_status(r), "fail")

    def test_no_fallthrough_not_required(self) -> None:
        """No forbidden, expect_llm_fallthrough=False → pass even without fallthrough."""
        r = TestResult(
            index=1,
            message="What year is this movie set in",
            expect_intent="",
            actual_intent=None,
            expect_params=None,
            actual_params={},
            intent_passed=True,
            params_passed=True,
            param_failures=[],
            xfail=False,
            reply_warn=None,
            log_check_warn=None,
            forbidden_intents=["get_time"],
            forbidden_intent_triggered=False,
            expect_llm_fallthrough=False,
            fallthrough_observed=False,
        )
        self.assertEqual(derive_status(r), "pass")

    def test_fallthrough_required_not_observed(self) -> None:
        """expect_llm_fallthrough without fallthrough evidence → indeterminate."""
        r = TestResult(
            index=1,
            message="What year is this movie set in",
            expect_intent="",
            actual_intent=None,
            expect_params=None,
            actual_params={},
            intent_passed=True,
            params_passed=True,
            param_failures=[],
            xfail=False,
            reply_warn=None,
            log_check_warn="no fallthrough/LLM generation evidence",
            forbidden_intents=["get_time"],
            forbidden_intent_triggered=False,
            expect_llm_fallthrough=True,
            fallthrough_observed=False,
        )
        self.assertEqual(derive_status(r), "indeterminate")

    def test_fallthrough_required_and_observed(self) -> None:
        """expect_llm_fallthrough with fallthrough evidence → pass."""
        r = TestResult(
            index=1,
            message="What year is this movie set in",
            expect_intent="",
            actual_intent=None,
            expect_params=None,
            actual_params={},
            intent_passed=True,
            params_passed=True,
            param_failures=[],
            xfail=False,
            reply_warn=None,
            log_check_warn=None,
            forbidden_intents=["get_time"],
            forbidden_intent_triggered=False,
            expect_llm_fallthrough=True,
            fallthrough_observed=True,
        )
        self.assertEqual(derive_status(r), "pass")

    def test_normal_test_unaffected(self) -> None:
        """Normal (non-false-positive) tests still work the same way."""
        r = TestResult(
            index=1,
            message="set an alarm for 11pm",
            expect_intent="set_alarm",
            actual_intent="set_alarm",
            expect_params=None,
            actual_params={},
            intent_passed=True,
            params_passed=True,
            param_failures=[],
            xfail=False,
            reply_warn=None,
            log_check_warn=None,
        )
        self.assertEqual(derive_status(r), "pass")

    def test_normal_test_fail_unaffected(self) -> None:
        """Normal test failure still works."""
        r = TestResult(
            index=1,
            message="set an alarm for 11pm",
            expect_intent="set_alarm",
            actual_intent="get_time",
            expect_params=None,
            actual_params={},
            intent_passed=False,
            params_passed=True,
            param_failures=[],
            xfail=False,
            reply_warn=None,
            log_check_warn=None,
        )
        self.assertEqual(derive_status(r), "fail")


class FalsePositiveFailureBucketTest(unittest.TestCase):
    """derive_failure_bucket for false-positive results."""

    def test_forbidden_intent_fired_bucket(self) -> None:
        """forbidden_intent_triggered → 'forbidden_intent_fired' bucket."""
        r = TestResult(
            index=1,
            message="What year is this movie set in",
            expect_intent="",
            actual_intent="get_time",
            expect_params=None,
            actual_params={},
            intent_passed=False,
            params_passed=False,
            param_failures=[],
            xfail=False,
            reply_warn=None,
            log_check_warn="forbidden intent 'get_time' was triggered",
            forbidden_intents=["get_time"],
            forbidden_intent_triggered=True,
            forbidden_intent_observed=["get_time"],
            fallthrough_observed=False,
        )
        self.assertEqual(derive_failure_bucket(r), "forbidden_intent_fired")

    def test_no_fallthrough_bucket(self) -> None:
        """No forbidden intent triggered + no fallthrough → 'false_positive_no_fallthrough' bucket."""
        r = TestResult(
            index=1,
            message="What year is this movie set in",
            expect_intent="",
            actual_intent=None,
            expect_params=None,
            actual_params={},
            intent_passed=True,
            params_passed=True,
            param_failures=[],
            xfail=False,
            reply_warn=None,
            log_check_warn="no fallthrough/LLM generation evidence",
            forbidden_intents=["get_time"],
            forbidden_intent_triggered=False,
            fallthrough_observed=False,
        )
        self.assertEqual(derive_failure_bucket(r), "false_positive_no_fallthrough")

    def test_clean_false_positive_pass_no_bucket(self) -> None:
        """Clean false-positive pass → None bucket."""
        r = TestResult(
            index=1,
            message="What year is this movie set in",
            expect_intent="",
            actual_intent=None,
            expect_params=None,
            actual_params={},
            intent_passed=True,
            params_passed=True,
            param_failures=[],
            xfail=False,
            reply_warn=None,
            log_check_warn=None,
            forbidden_intents=["get_time"],
            forbidden_intent_triggered=False,
            fallthrough_observed=True,
        )
        self.assertIsNone(derive_failure_bucket(r))


class FalsePositiveSelectorTest(unittest.TestCase):
    """Selection of false-positive cases via the existing selector model."""

    def test_category_filter_selects_false_positives(self) -> None:
        """--categories false_positive selects only the false_positives phase."""
        selected = _select_tests(
            PHASES,
            categories=["false_positive"],
        )
        self.assertGreater(len(selected), 0)
        # All selected cases should be from false_positives phase
        phase_names = {PHASES[idx][0] for idx, _, _ in selected}
        self.assertEqual(phase_names, {"false_positives"})

    def test_phase_filter_selects_false_positives(self) -> None:
        """--phases false_positives selects only the false_positives phase."""
        selected = _select_tests(
            PHASES,
            phase_filter=["false_positives"],
        )
        self.assertGreater(len(selected), 0)
        phase_names = {PHASES[idx][0] for idx, _, _ in selected}
        self.assertEqual(phase_names, {"false_positives"})

    def test_tag_filter_selects_false_positives(self) -> None:
        """--tags false_positive selects false-positive cases."""
        selected = _select_tests(
            PHASES,
            tags=["false_positive"],
        )
        self.assertGreater(len(selected), 0)
        for _, _, tc in selected:
            self.assertIn("false_positive", tc.tags)

    def test_false_positive_has_correct_forbidden_intents(self) -> None:
        """Each selected false-positive case has at least one forbidden_intent."""
        selected = _select_tests(
            PHASES,
            phase_filter=["false_positives"],
        )
        for _, _, tc in selected:
            self.assertTrue(
                len(tc.forbidden_intents) >= 1,
                f"TestCase '{tc.id}' in false_positives phase has no forbidden_intents"
            )


if __name__ == "__main__":
    unittest.main()
