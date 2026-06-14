#!/usr/bin/env python3
"""Tests for generate_quality_scorecard.py."""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT_DIR = HERE.parent
sys.path.insert(0, str(SCRIPT_DIR))

import generate_quality_scorecard as scorecard


class GenerateQualityScorecardTest(unittest.TestCase):
    def test_builds_scorecard_with_defaults(self) -> None:
        markdown = scorecard.build_scorecard({"month": "2026-06", "review_date": "2026-07-01"})

        self.assertIn("Jandal AI Monthly Quality Scorecard — 2026-06", markdown)
        self.assertIn("Review date: 2026-07-01", markdown)
        self.assertIn("Fix vs feature ratio", markdown)
        self.assertIn("Generated from partial input", markdown)

    def test_counts_delivery_mix_and_manual_testing_fit(self) -> None:
        data = {
            "month": "2026-06",
            "prs": [
                {"number": "#1", "type": "feature", "risk": "Medium", "manual_testing": "No", "manual_testing_fit": "not_applicable"},
                {"number": "#2", "type": "fix", "risk": "High", "manual_testing": "Yes", "manual_testing_fit": "appropriate"},
                {"number": "#3", "type": "docs", "risk": "Low", "manual_testing": "No", "manual_testing_fit": "not_applicable"},
            ],
        }
        markdown = scorecard.build_scorecard(data)

        self.assertIn("| Feature PRs | 1 |", markdown)
        self.assertIn("| Fix PRs | 1 |", markdown)
        self.assertIn("| Docs/process/test-only PRs | 1 |", markdown)
        self.assertIn("| Manual testing used where it added signal | 1 |", markdown)

    def test_includes_dashboard_metrics(self) -> None:
        data = {
            "month": "2026-06",
            "metrics": {
                "validity": {
                    "valid_records": 7,
                    "invalid_records": 2,
                    "issue_buckets": {"missing:commit": 2},
                },
                "failure_buckets": {"wrong_tool": 3},
                "retry_timeout_harness": {"timeout": 1, "retry_seen": 2, "harness_error": 0},
                "stuck_mode": [
                    {"actual_tool": "create_list_item", "different_expected_tools": ["set_alarm", "create_reminder"]}
                ],
                "artifacts": [{"path": "screenshots/a.png"}],
            },
        }
        markdown = scorecard.build_scorecard(data)

        self.assertIn("7 valid / 2 invalid", markdown)
        self.assertIn("| missing:commit | 2 |", markdown)
        self.assertIn("| wrong_tool | 3 |", markdown)
        self.assertIn("| create_list_item | set_alarm, create_reminder |", markdown)
        self.assertIn("| Artifact paths available | 1 |", markdown)

    def test_includes_docs_drift_and_high_risk_evidence(self) -> None:
        data = {
            "month": "2026-06",
            "docs_drift": {"warnings": 4, "resolved_by_docs": 2, "resolved_by_rationale": 1, "unresolved": 1},
            "high_risk_evidence": {"with_appropriate_evidence": 3, "missing_appropriate_evidence": 1},
        }
        markdown = scorecard.build_scorecard(data)

        self.assertIn("| Docs drift warnings | 4 |", markdown)
        self.assertIn("| Warnings resolved by docs update | 2 |", markdown)
        self.assertIn("| High-risk PRs with appropriate evidence | 3 |", markdown)
        self.assertIn("| High-risk PRs missing appropriate evidence | 1 |", markdown)

    def test_cli_writes_output_file(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            input_path = tmp_path / "input.json"
            output_path = tmp_path / "scorecard.md"
            input_path.write_text(json.dumps({"month": "2026-06", "prs": [{"type": "feature"}]}), encoding="utf-8")

            exit_code = scorecard.main(["--input", str(input_path), "--output", str(output_path)])

            self.assertEqual(exit_code, 0)
            self.assertTrue(output_path.exists())
            self.assertIn("2026-06", output_path.read_text(encoding="utf-8"))

    def test_cli_rejects_non_object_json(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            input_path = Path(tmp) / "input.json"
            input_path.write_text("[]", encoding="utf-8")

            exit_code = scorecard.main(["--input", str(input_path)])

            self.assertEqual(exit_code, 2)


if __name__ == "__main__":
    unittest.main()
