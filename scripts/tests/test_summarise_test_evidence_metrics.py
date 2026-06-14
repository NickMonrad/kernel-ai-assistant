#!/usr/bin/env python3
"""Tests for summarise_test_evidence_metrics.py."""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT_DIR = HERE.parent
sys.path.insert(0, str(SCRIPT_DIR))

import summarise_test_evidence_metrics as metrics


def evidence_record(**overrides):
    base = {
        "schema_version": "1.0",
        "source": "on_device",
        "suite": "llm_tools",
        "timestamp": "2026-06-14T00:00:00Z",
        "repo": "NickMonrad/kernel-ai-assistant",
        "branch": "feature/example",
        "commit": "abcdef1234567890",
        "pr": 1224,
        "release": None,
        "run_id": "on-device-1",
        "device": {
            "id": "s21-exynos",
            "label": "S21",
            "model": "SM-G991B",
            "tier": "tracked",
            "android_api": 35,
            "execution": "physical",
        },
        "model": {"name": "Gemma E2B", "runtime": "LiteRT", "backend": "GPU"},
        "summary": {"total": 2, "passed": 1, "failed": 1, "pass_rate": 0.5},
        "cases": [
            {
                "name": "add_to_list",
                "passed": True,
                "expected_tool": "create_list_item",
                "actual_tool": "create_list_item",
                "expected_result_mode": "success",
                "actual_result_mode": "success",
                "retry_seen": False,
                "slot_fill_seen": False,
                "failure_category": None,
                "failures": [],
            },
            {
                "name": "wrong_tool",
                "passed": False,
                "expected_tool": "create_reminder",
                "actual_tool": "create_list_item",
                "expected_result_mode": "success",
                "actual_result_mode": "success",
                "retry_seen": True,
                "slot_fill_seen": False,
                "failure_category": "wrong_tool",
                "failures": ["expected create_reminder"],
                "screenshot_path": "screenshots/wrong-tool.png",
            },
        ],
    }
    base.update(overrides)
    return base


class EvidenceMetricsTest(unittest.TestCase):
    def test_validity_and_failure_buckets(self) -> None:
        record = evidence_record()
        summary = metrics.summarise([(Path("evidence.json"), record, [])])

        self.assertEqual(summary["validity"]["valid_records"], 1)
        self.assertEqual(summary["validity"]["invalid_records"], 0)
        self.assertEqual(summary["failure_buckets"], {"wrong_tool": 1})
        self.assertEqual(summary["totals"]["total"], 2)
        self.assertEqual(summary["totals"]["passed"], 1)
        self.assertEqual(summary["totals"]["failed"], 1)

    def test_device_context_and_suite_breakdown(self) -> None:
        record = evidence_record()
        summary = metrics.summarise([(Path("evidence.json"), record, [])])

        self.assertIn("llm_tools", summary["by_suite"])
        self.assertEqual(summary["by_suite"]["llm_tools"]["total"], 2)
        self.assertIn("s21-exynos", summary["by_device"])
        self.assertEqual(summary["by_device"]["s21-exynos"]["label"], "S21")
        self.assertEqual(summary["by_device"]["s21-exynos"]["android_api"], 35)

    def test_confusion_matrix_and_retry_counts(self) -> None:
        record = evidence_record()
        summary = metrics.summarise([(Path("evidence.json"), record, [])])

        self.assertEqual(summary["confusion_matrix"]["create_reminder"]["create_list_item"], 1)
        self.assertEqual(summary["result_mode_matrix"]["success"]["success"], 2)
        self.assertEqual(summary["retry_timeout_harness"]["retry_seen"], 1)

    def test_artifact_paths_are_collected(self) -> None:
        record = evidence_record(log_path="logs/run.log")
        summary = metrics.summarise([(Path("evidence.json"), record, [])])

        artifact_paths = {item["path"] for item in summary["artifacts"]}
        self.assertIn("logs/run.log", artifact_paths)
        self.assertIn("screenshots/wrong-tool.png", artifact_paths)

    def test_invalid_record_reports_issue_buckets(self) -> None:
        record = evidence_record()
        del record["cases"]
        summary = metrics.summarise([(Path("bad.json"), record, [])])

        self.assertEqual(summary["validity"]["valid_records"], 0)
        self.assertEqual(summary["validity"]["invalid_records"], 1)
        self.assertEqual(summary["validity"]["issue_buckets"]["missing:cases"], 1)
        self.assertEqual(summary["validity"]["issue_buckets"]["cases_not_list"], 1)

    def test_stuck_mode_detects_same_wrong_actual_tool(self) -> None:
        first = evidence_record()
        second = evidence_record(
            run_id="on-device-2",
            cases=[
                {
                    "name": "wrong_tool_again",
                    "passed": False,
                    "expected_tool": "set_alarm",
                    "actual_tool": "create_list_item",
                    "expected_result_mode": "success",
                    "actual_result_mode": "success",
                    "retry_seen": False,
                    "slot_fill_seen": False,
                    "failure_category": "wrong_tool",
                    "failures": ["expected set_alarm"],
                }
            ],
            summary={"total": 1, "passed": 0, "failed": 1, "pass_rate": 0.0},
        )
        summary = metrics.summarise([
            (Path("first.json"), first, []),
            (Path("second.json"), second, []),
        ])

        self.assertEqual(summary["stuck_mode"][0]["actual_tool"], "create_list_item")
        self.assertEqual(summary["stuck_mode"][0]["expected_tool_count"], 2)

    def test_discovers_json_files_from_directory(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "evidence.json"
            path.write_text("{\"not\": \"normalised\"}\n", encoding="utf-8")
            discovered = metrics.discover_evidence(Path(tmp))

        self.assertEqual(len(discovered), 1)
        self.assertEqual(discovered[0][0].name, "evidence.json")

    def test_markdown_mentions_failure_and_validity(self) -> None:
        summary = metrics.summarise([(Path("evidence.json"), evidence_record(), [])])
        markdown = metrics.render_markdown(summary)

        self.assertIn("Test Evidence Metrics Summary", markdown)
        self.assertIn("Failure buckets", markdown)
        self.assertIn("wrong_tool", markdown)
        self.assertIn("Validity: 1 valid / 0 invalid", markdown)


if __name__ == "__main__":
    unittest.main()
