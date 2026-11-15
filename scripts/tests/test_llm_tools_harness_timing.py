#!/usr/bin/env python3
"""Host regressions for llm_tools marker waits and elapsed-time evidence."""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
SCRIPT_DIR = HERE.parent
sys.path.insert(0, str(SCRIPT_DIR))

from adb_harness.models import LLMToolsResult  # noqa: E402
from adb_harness.reporting import save_llm_tools_report  # noqa: E402
from adb_harness.runners import run_llm_tools  # noqa: E402


def _run_case_with_mock_device(case_name: str):
    marker_names = (
        "route",
        "native_tool",
        "legacy_tool",
        "skill_result",
        "message_saved",
        "tool_chip",
        "tool_sequence",
    )
    markers = dict.fromkeys(marker_names)
    with (
        patch("adb_harness.runners.os.path.isfile", return_value=True),
        patch("adb_harness.runners.logcat_start"),
        patch("adb_harness.runners.run_adb"),
        patch("adb_harness.runners.dismiss_notifications"),
        patch("adb_harness.runners.clear_logcat"),
        patch("adb_harness.runners.send_text"),
        patch(
            "adb_harness.runners._keep_foreground_until_inference_starts"
        ) as keep_foreground,
        patch("adb_harness.runners.cleanup_clock_alerts", return_value=True),
        patch("adb_harness.runners._clear_conversation"),
        patch("adb_harness.runners.logcat_wait", return_value=""),
        patch("adb_harness.runners.begin_local_llm_tools_capture", return_value=False),
        patch(
            "adb_harness.runners._poll_for_all_markers",
            return_value=(markers, ""),
        ) as poll_markers,
        patch(
            "adb_harness.runners.save_llm_tools_report",
            return_value=Path("report.json"),
        ) as save_report,
        patch("adb_harness.runners.time.sleep"),
        patch(
            "adb_harness.runners.time.monotonic",
            side_effect=[10.0, 12.0, 42.0, 45.0],
        ),
    ):
        exit_code = run_llm_tools(case_ids=[case_name])
    return exit_code, keep_foreground, poll_markers, save_report


class LLMToolsHarnessTimingTest(unittest.TestCase):
    def test_discovery_chain_gets_long_post_start_wait_and_real_elapsed_times(self) -> None:
        exit_code, keep_foreground, poll_markers, save_report = _run_case_with_mock_device(
            "run_intent_get_stopwatch_status_after_skill_load"
        )

        self.assertEqual(1, exit_code)
        self.assertEqual(360, poll_markers.call_args.kwargs["timeout"])
        self.assertEqual({"timeout": 120.0}, keep_foreground.call_args_list[-1].kwargs)
        report_results = save_report.call_args.args[0]
        self.assertEqual(30.0, report_results[0].elapsed_seconds)
        self.assertEqual(35.0, save_report.call_args.kwargs["elapsed"])

    def test_non_discovery_case_keeps_default_post_start_wait(self) -> None:
        _, _, poll_markers, _ = _run_case_with_mock_device("get_system_info_natural")

        self.assertEqual(120.0, poll_markers.call_args.kwargs["timeout"])

    def test_report_serializes_measured_suite_and_case_elapsed_seconds(self) -> None:
        result = LLMToolsResult(
            index=1,
            name="timed_case",
            message="status?",
            expected_top_level_tool="run_intent",
            actual_top_level_tool=None,
            actual_nested_intent=None,
            route_marker=None,
            native_tool_marker=None,
            legacy_tool_marker=None,
            skill_result_marker=None,
            message_saved_marker=None,
            retry_seen=False,
            slot_fill_seen=False,
            chip_text=None,
            reply_text=None,
            passed=False,
            failures=["timed out"],
            elapsed_seconds=12.3,
        )

        with tempfile.TemporaryDirectory() as temp_dir:
            report_dir = Path(temp_dir)
            with patch("adb_harness.reporting.REPORTS_DIR", report_dir):
                report_path = save_llm_tools_report(
                    [result],
                    elapsed=35.0,
                    run_ts="test-run",
                )
            report = json.loads(report_path.read_text(encoding="utf-8"))

        self.assertEqual(35.0, report["elapsed_seconds"])
        self.assertEqual(12.3, report["results"][0]["elapsed_seconds"])


if __name__ == "__main__":
    unittest.main()
