#!/usr/bin/env python3
"""Unit tests for email-account fixture detection and classification (#1377)."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
SCRIPT_DIR = HERE.parent
sys.path.insert(0, str(SCRIPT_DIR))

from adb_harness.device import check_email_fixture
from adb_harness.models import TestResult, derive_failure_bucket, derive_status
from adb_harness.runners import _known_missing_fixtures, _mark_missing_fixture_xfail


class EmailFixtureDetectionTest(unittest.TestCase):
    """`dumpsys account` plus deterministic-contact parsing is conservative."""

    def test_account_and_all_fixture_contacts_are_available(self) -> None:
        def adb_output(*args: str) -> str:
            if args == ("shell", "dumpsys", "account"):
                return "Accounts:\n  Account {name=test@example.com, type=com.google}\n"
            name = args[4].rsplit("/", 1)[-1]
            return f"Row: 0 display_name={name}\n"

        with patch("adb_harness.device.run_adb", side_effect=adb_output):
            self.assertTrue(check_email_fixture())

    def test_no_account_evidence_is_unavailable(self) -> None:
        """Empty or unsuccessful dumpsys output is unavailable, not an exception."""
        with patch("adb_harness.device.run_adb", return_value="Accounts: 0\n"):
            self.assertFalse(check_email_fixture())

    def test_missing_fixture_contact_is_unavailable(self) -> None:
        """Generic device accounts cannot substitute for a deterministic contact."""
        def adb_output(*args: str) -> str:
            if args == ("shell", "dumpsys", "account"):
                return "Account {name=test@example.com, type=com.google}\n"
            return ""

        with patch("adb_harness.device.run_adb", side_effect=adb_output):
            self.assertFalse(check_email_fixture())

    def test_partial_contact_match_is_not_fixture_evidence(self) -> None:
        """Contact-provider substring matches must not satisfy an exact fixture name."""
        def adb_output(*args: str) -> str:
            if args == ("shell", "dumpsys", "account"):
                return "Account {name=test@example.com, type=com.google}\n"
            return "Row: 0 display_name=John Smith\n"

        with patch("adb_harness.device.run_adb", side_effect=adb_output):
            self.assertFalse(check_email_fixture())


class EmailFixtureClassificationTest(unittest.TestCase):
    """Unavailable email accounts produce an xfail fixture bucket, never a pass."""

    def _email_result(self, *, actual_intent: str, intent_passed: bool) -> TestResult:
        return TestResult(
            index=1,
            message="send an email to John",
            expect_intent="send_email",
            actual_intent=actual_intent,
            expect_params=None,
            actual_params={},
            intent_passed=intent_passed,
            params_passed=True,
            param_failures=[],
            xfail=False,
            reply_warn=None,
            log_check_warn=None,
            fixture="contacts:email_contact_seed",
        )

    def test_missing_email_fixture_marks_wrong_tool_as_xfail(self) -> None:
        """Calendar fallthrough remains a failure, classified as unavailable fixture."""
        result = self._email_result(
            actual_intent="create_calendar_event",
            intent_passed=False,
        )
        missing = _known_missing_fixtures(True, False)

        _mark_missing_fixture_xfail(result, missing)

        self.assertTrue(result.xfail)
        self.assertEqual(result.xfail_reason, "fixture_missing: required fixture 'contacts:email_contact_seed' is unavailable")
        self.assertEqual(derive_status(result), "xfail")
        self.assertEqual(derive_failure_bucket(result, missing), "fixture_missing")

    def test_missing_email_fixture_does_not_downgrade_a_clean_pass(self) -> None:
        """A successful route stays a pass even when account availability is unknown."""
        result = self._email_result(actual_intent="send_email", intent_passed=True)
        missing = _known_missing_fixtures(True, False)

        _mark_missing_fixture_xfail(result, missing)

        self.assertFalse(result.xfail)
        self.assertEqual(derive_status(result), "pass")
        self.assertIsNone(derive_failure_bucket(result, missing))

    def test_other_missing_fixture_does_not_xfail_email_failure(self) -> None:
        """Fixture status is exact: family-contact absence cannot mask email routing."""
        result = self._email_result(
            actual_intent="create_calendar_event",
            intent_passed=False,
        )
        missing = _known_missing_fixtures(False, True)

        _mark_missing_fixture_xfail(result, missing)

        self.assertFalse(result.xfail)
        self.assertEqual(derive_status(result), "fail")
        self.assertEqual(derive_failure_bucket(result, missing), "wrong_tool")


if __name__ == "__main__":
    unittest.main()
