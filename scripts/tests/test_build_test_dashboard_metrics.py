#!/usr/bin/env python3
"""Tests for metrics integration in build_test_dashboard.py.

Covers the metrics block in generated dashboard data, old evidence tolerance,
failure bucket and stuck-mode rendering, and artifact path preservation.
"""

from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT_DIR = HERE.parent
sys.path.insert(0, str(SCRIPT_DIR))

from build_test_dashboard import (
    _build_aggregates,
    _build_metrics_json,
    _METRICS_AVAILABLE,
    _render_metrics_section,
)


def _make_record(**overrides: object) -> dict:
    """Create a minimal valid evidence record."""
    base: dict = {
        "schema_version": "1.0",
        "source": "on_device",
        "suite": "llm_tools",
        "timestamp": "2026-06-14T00:00:00Z",
        "repo": "NickMonrad/kernel-ai-assistant",
        "branch": "feature/example",
        "commit": "abcdef1234567890abcdef1234567890abcdef12",
        "pr": 1224,
        "release": None,
        "run_id": "on-device-1",
        "device": {
            "id": "s21-exynos",
            "label": "S21 Exynos",
            "model": "SM-G991B",
            "tier": "tracked",
            "android_api": 35,
            "execution": "physical",
        },
        "model": {"name": "Gemma E2B", "runtime": "LiteRT", "backend": "GPU"},
        "summary": {"total": 2, "passed": 1, "failed": 1, "pass_rate": 0.5},
        "cases": [
            {
                "name": "pass_case",
                "passed": True,
                "expected_tool": "tool_a",
                "actual_tool": "tool_a",
                "expected_result_mode": "success",
                "actual_result_mode": "success",
                "retry_seen": False,
                "slot_fill_seen": False,
                "failure_category": None,
                "failures": [],
            },
            {
                "name": "fail_case",
                "passed": False,
                "expected_tool": "tool_b",
                "actual_tool": "tool_c",
                "expected_result_mode": "success",
                "actual_result_mode": "success",
                "retry_seen": False,
                "slot_fill_seen": False,
                "failure_category": "wrong_tool",
                "failures": ["expected tool_b, got tool_c"],
                "screenshot_path": "screenshots/fail_case.png",
            },
        ],
        "_source_relpath": "pr/1224/on_device/evidence.json",
    }
    base.update(overrides)
    return base


class DashboardMetricsIntegrationTest(unittest.TestCase):
    """Tests for the metrics block in build_test_dashboard.py."""

    def test_metrics_block_present_in_aggregates(self) -> None:
        """metrics block exists when evidence is present."""
        if not _METRICS_AVAILABLE:
            self.skipTest("Metrics module not available")
        agg = _build_aggregates([_make_record()])
        self.assertIn("metrics", agg)
        self.assertIsNotNone(agg["metrics"])

    def test_metrics_block_none_when_no_evidence(self) -> None:
        """metrics block is None when no evidence records exist."""
        agg = _build_aggregates([])
        self.assertIn("metrics", agg)
        self.assertIsNone(agg["metrics"])

    def test_metrics_json_builder(self) -> None:
        """_build_metrics_json returns the metrics dict from aggregates."""
        if not _METRICS_AVAILABLE:
            self.skipTest("Metrics module not available")
        agg = _build_aggregates([_make_record()])
        metrics = _build_metrics_json(agg)
        self.assertIsNotNone(metrics)
        self.assertIn("totals", metrics)  # type: ignore[operator]
        self.assertIn("validity", metrics)  # type: ignore[operator]

    def test_old_minimal_evidence_does_not_crash(self) -> None:
        """Dashboard generation tolerates old evidence with sparse fields."""
        old = {
            "schema_version": "1.0",
            "source": "on_device",
            "suite": "legacy_suite",
            "timestamp": "2025-01-01T00:00:00Z",
            "repo": "test/repo",
            "branch": "old",
            "commit": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            "pr": None,
            "release": None,
            "run_id": "legacy-run",
            "device": {"id": "d1"},
            "model": {"name": None, "runtime": None, "backend": None},
            "summary": {"total": 1, "passed": 1, "failed": 0, "pass_rate": 1.0},
            "cases": [
                {
                    "name": "legacy_case",
                    "passed": True,
                    "expected_tool": "tool",
                    "actual_tool": "tool",
                    "expected_result_mode": "success",
                    "actual_result_mode": "success",
                    "retry_seen": False,
                    "slot_fill_seen": False,
                    "failure_category": None,
                    "failures": [],
                }
            ],
            "_source_relpath": "legacy/evidence.json",
        }
        agg = _build_aggregates([old])
        self.assertIn("metrics", agg)
        # Rendering must not raise
        if _METRICS_AVAILABLE:
            html = _render_metrics_section(agg)
            self.assertIsInstance(html, str)

    def test_invalid_evidence_validity_warnings(self) -> None:
        """Invalid evidence produces validity issues but does not crash."""
        if not _METRICS_AVAILABLE:
            self.skipTest("Metrics module not available")
        broken = {
            "source": "on_device",
            "suite": "broken",
            "_source_relpath": "broken.json",
        }
        agg = _build_aggregates([broken])
        metrics = agg["metrics"]
        self.assertIsNotNone(metrics)
        self.assertGreater(metrics["validity"]["invalid_records"], 0)  # type: ignore[index]
        self.assertGreater(len(metrics["validity"]["issue_buckets"]), 0)  # type: ignore[index]
        # Rendering must not crash
        html = _render_metrics_section(agg)
        self.assertIn("ISSUES", html)

    def test_failure_buckets_in_metrics(self) -> None:
        """Failure buckets appear in metrics output."""
        if not _METRICS_AVAILABLE:
            self.skipTest("Metrics module not available")
        agg = _build_aggregates([_make_record()])
        fb = agg["metrics"]["failure_buckets"]  # type: ignore[index]
        self.assertIn("wrong_tool", fb)
        self.assertEqual(fb["wrong_tool"], 1)
        # HTML rendering is sane
        html = _render_metrics_section(agg)
        self.assertIn("wrong_tool", html)

    def test_stuck_mode_with_two_expected_tools(self) -> None:
        """Stuck-mode suspect appears when same actual tool maps to multiple expected tools."""
        if not _METRICS_AVAILABLE:
            self.skipTest("Metrics module not available")
        rec1 = _make_record(run_id="run-1", _source_relpath="run1.json")
        rec2 = _make_record(
            run_id="run-2",
            _source_relpath="run2.json",
            cases=[
                {
                    "name": "wrong_again",
                    "passed": False,
                    "expected_tool": "tool_d",
                    "actual_tool": "tool_c",
                    "expected_result_mode": "success",
                    "actual_result_mode": "success",
                    "retry_seen": False,
                    "slot_fill_seen": False,
                    "failure_category": "wrong_tool",
                    "failures": ["expected tool_d, got tool_c"],
                }
            ],
            summary={"total": 1, "passed": 0, "failed": 1, "pass_rate": 0.0},
        )
        agg = _build_aggregates([rec1, rec2])
        stuck = agg["metrics"]["stuck_mode"]  # type: ignore[index]
        self.assertEqual(len(stuck), 1)
        self.assertEqual(stuck[0]["actual_tool"], "tool_c")
        self.assertEqual(stuck[0]["expected_tool_count"], 2)
        # HTML must show the warning
        html = _render_metrics_section(agg)
        self.assertIn("Stuck-mode", html)
        self.assertIn("tool_c", html)

    def test_no_stuck_mode_for_single_wrong_tool(self) -> None:
        """No stuck-mode warning for a single wrong-tool case."""
        if not _METRICS_AVAILABLE:
            self.skipTest("Metrics module not available")
        agg = _build_aggregates([_make_record()])
        stuck = agg["metrics"]["stuck_mode"]  # type: ignore[index]
        self.assertEqual(len(stuck), 0)
        # HTML should not contain stuck-mode warning
        html = _render_metrics_section(agg)
        self.assertNotIn("stuck", html.lower())

    def test_artifact_paths_preserved(self) -> None:
        """Artifact paths appear in metrics when present in evidence."""
        if not _METRICS_AVAILABLE:
            self.skipTest("Metrics module not available")
        agg = _build_aggregates([_make_record()])
        artifacts = agg["metrics"]["artifacts"]  # type: ignore[index]
        paths = {a["path"] for a in artifacts}
        self.assertIn("screenshots/fail_case.png", paths)
        # HTML should show the artifact table
        html = _render_metrics_section(agg)
        self.assertIn("screenshots/fail_case.png", html)


if __name__ == "__main__":
    unittest.main()
