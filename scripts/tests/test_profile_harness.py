#!/usr/bin/env python3
"""Tests for profile extraction harness reliability fixes.

Covers:
- run_profile_tests imports and uses extract_profile_result successfully
- parser failures become structured harness failures instead of Python crashes
"""

from __future__ import annotations

import sys
import unittest
from contextlib import ExitStack
from pathlib import Path
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
SCRIPT_DIR = HERE.parent
sys.path.insert(0, str(SCRIPT_DIR))

from adb_harness import device  # noqa: E402
from adb_harness.models import ProfileTestCase  # noqa: E402
import adb_harness.runners as runners  # noqa: E402



class ProfileParserTest(unittest.TestCase):
    def test_extract_profile_result_parses_simple_lines(self) -> None:
        """Simplified lines containing just the log message (no logcat prefix)."""
        logcat = (
            "Profile LLM extraction succeeded\n"
            "name: Nick\n"
            "role: Developer\n"
            "location: Wellington\n"
        )

        parsed = device.extract_profile_result(logcat)

        self.assertEqual(
            {
                "method": "llm",
                "name": "Nick",
                "role": "Developer",
                "location": "Wellington",
            },
            parsed,
        )

    def test_extract_profile_result_parses_logcat_brief_lines(self) -> None:
        """Real logcat -v brief format as produced by adb logcat -v brief."""
        logcat = (
            "D/KernelAI(12345): Profile LLM extraction succeeded\n"
            "D/KernelAI(12345): name: Nick\n"
            "D/KernelAI(12345): role: Developer\n"
            "D/KernelAI(12345): location: Wellington\n"
        )

        parsed = device.extract_profile_result(logcat)

        self.assertEqual(
            {
                "method": "llm",
                "name": "Nick",
                "role": "Developer",
                "location": "Wellington",
            },
            parsed,
        )

    def test_extract_profile_result_parses_logcat_brief_fallback(self) -> None:
        """Regex fallback path with logcat -v brief lines."""
        logcat = (
            "D/KernelAI(12345): Profile LLM extraction skipped — engine not ready\n"
            "D/KernelAI(12345): Profile regex fallback\n"
            "D/KernelAI(12345): name: Alex\n"
            "D/KernelAI(12345): role: software engineer\n"
            "D/KernelAI(12345): location: Sydney, Australia\n"
        )

        parsed = device.extract_profile_result(logcat)

        self.assertEqual(
            {
                "method": "regex",
                "name": "Alex",
                "role": "software engineer",
                "location": "Sydney, Australia",
            },
            parsed,
        )

    def test_extract_profile_result_handles_missing_fields(self) -> None:
        """Partial output returns None for missing fields, not crash."""
        logcat = "Profile regex fallback\nname: Sam\n"
        parsed = device.extract_profile_result(logcat)
        self.assertEqual("regex", parsed["method"])
        self.assertEqual("Sam", parsed["name"])
        self.assertIsNone(parsed["role"])
        self.assertIsNone(parsed["location"])



class ProfileHarnessTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.script = SCRIPT_DIR / "adb_skill_test.py"
        if not cls.script.is_file():
            raise FileNotFoundError(f"Test script not found: {cls.script}")

    def _patch_common(self, cases: list[ProfileTestCase]) -> ExitStack:
        stack = ExitStack()
        stack.enter_context(patch("adb_harness.runners.os.path.isfile", return_value=True))
        stack.enter_context(patch.object(runners, "PROFILE_TEST_CASES", cases))
        stack.enter_context(patch.object(runners, "logcat_start"))
        stack.enter_context(patch.object(runners, "clear_logcat"))
        stack.enter_context(patch.object(runners, "send_text"))
        stack.enter_context(patch.object(runners, "send_profile"))
        stack.enter_context(patch.object(runners, "_reset_app_process"))
        stack.enter_context(patch.object(runners, "analyse_results"))
        stack.enter_context(patch.object(runners.time, "sleep", return_value=None))
        stack.enter_context(
            patch.object(
                runners,
                "read_logcat_all",
                side_effect=["Generation complete", "KernelAI: name: Nick"],
            )
        )
        return stack

    def test_run_profile_tests_saves_successful_result(self) -> None:
        cases = [
            ProfileTestCase(
                name="simple_profile",
                profile_text="Name: Nick",
                expect_name="Nick",
            )
        ]
        with self._patch_common(cases) as stack:
            save_report = stack.enter_context(
                patch.object(runners, "save_report", return_value=Path("/tmp/profile.json"))
            )
            stack.enter_context(
                patch.object(
                    runners,
                    "extract_profile_result",
                    return_value={
                        "method": "regex",
                        "name": "Nick",
                        "role": None,
                        "location": None,
                    },
                )
            )

            rc = runners.run_profile_tests()

        self.assertEqual(0, rc)
        saved_results = save_report.call_args.args[0]
        self.assertEqual(1, len(saved_results))
        result = saved_results[0]
        self.assertEqual("regex", result.actual_intent)
        self.assertEqual("pass", result.status)
        self.assertIsNone(result.log_check_warn)

    def test_run_profile_tests_reports_parser_failure_without_crashing(self) -> None:
        cases = [
            ProfileTestCase(
                name="broken_profile",
                profile_text="Name: Nick",
                expect_name="Nick",
            )
        ]
        with self._patch_common(cases) as stack:
            save_report = stack.enter_context(
                patch.object(runners, "save_report", return_value=Path("/tmp/profile.json"))
            )
            stack.enter_context(
                patch.object(
                    runners,
                    "extract_profile_result",
                    side_effect=RuntimeError("boom"),
                )
            )

            rc = runners.run_profile_tests()

        self.assertEqual(1, rc)
        saved_results = save_report.call_args.args[0]
        self.assertEqual(1, len(saved_results))
        result = saved_results[0]
        self.assertEqual("HARNESS_ERROR", result.actual_intent)
        self.assertEqual("fail", result.status)
        self.assertIn("profile extraction parse error: boom", result.log_check_warn or "")


if __name__ == "__main__":
    unittest.main()
