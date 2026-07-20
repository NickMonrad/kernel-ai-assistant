#!/usr/bin/env python3
"""Tests for metrics integration in build_test_dashboard.py.

Covers the metrics block in generated dashboard data, old evidence tolerance,
failure bucket and stuck-mode rendering, and artifact path preservation.
"""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path
HERE = Path(__file__).resolve().parent
SCRIPT_DIR = HERE.parent
sys.path.insert(0, str(SCRIPT_DIR))

from build_test_dashboard import (
    _build_aggregates,
    _build_json_data,
    _build_metrics_json,
    _render_metrics_section,
    _render_wake_metrics_section,
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
            "label": "S21",
            "model": "SM-G991B",
            "manufacturer": "Samsung",
            "soc": "Exynos 2100",
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
                "chip_present": True,
                "skill_result_present": True,
                "message_saved": True,
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
                "chip_present": True,
                "skill_result_present": True,
                "message_saved": True,
                "retry_seen": False,
                "slot_fill_seen": False,
                "failure_category": "wrong_tool",
                "failures": ["expected tool_b, got tool_c"],
                "artifact_refs": ["screenshots/fail_case.png"],
            },
        ],
        "artifact_refs": [],
        "_source_relpath": "pr/1224/on_device/evidence.json",
    }
    base.update(overrides)
    return base


class DashboardMetricsIntegrationTest(unittest.TestCase):
    """Tests for the metrics block in build_test_dashboard.py."""
    def test_device_latest_run_is_separate_from_historical_aggregate(self) -> None:
        old = _make_record(
            timestamp="2026-06-13T00:00:00Z",
            run_id="old-pass",
            summary={"total": 2, "passed": 2, "failed": 0, "pass_rate": 1.0},
        )
        latest = _make_record(
            timestamp="2026-06-14T00:00:00Z",
            run_id="latest-fail",
            summary={"total": 1, "passed": 0, "failed": 1, "pass_rate": 0.0},
        )

        data = _build_aggregates([old, latest])
        device = data["devices"][0]

        self.assertEqual(device["latest_summary"]["total"], 1)
        self.assertEqual(device["latest_summary"]["passed"], 0)
        self.assertEqual(device["total"], 3)
        self.assertEqual(device["passed"], 2)

    def test_metrics_block_present_in_aggregates(self) -> None:
        """metrics block exists when evidence is present."""
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
                    "chip_present": True,
                    "skill_result_present": True,
                    "message_saved": True,
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
        html = _render_metrics_section(agg)
        self.assertIsInstance(html, str)

    def test_invalid_evidence_validity_warnings(self) -> None:
        """Invalid evidence produces validity issues but does not crash."""
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
        agg = _build_aggregates([_make_record()])
        fb = agg["metrics"]["failure_buckets"]  # type: ignore[index]
        self.assertIn("wrong_tool", fb)
        self.assertEqual(fb["wrong_tool"], 1)
        # HTML rendering is sane
        html = _render_metrics_section(agg)
        self.assertIn("wrong_tool", html)

    def test_stuck_mode_with_two_expected_tools(self) -> None:
        """Stuck-mode suspect appears when same actual tool maps to multiple expected tools."""
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
                    "chip_present": True,
                    "skill_result_present": True,
                    "message_saved": True,
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
        agg = _build_aggregates([_make_record()])
        stuck = agg["metrics"]["stuck_mode"]  # type: ignore[index]
        self.assertEqual(len(stuck), 0)
        # HTML should not contain stuck-mode warning
        html = _render_metrics_section(agg)
        self.assertNotIn("stuck", html.lower())

    def test_artifact_paths_preserved(self) -> None:
        """Artifact paths appear in metrics when present in evidence."""
        agg = _build_aggregates([_make_record()])
        artifacts = agg["metrics"]["artifacts"]  # type: ignore[index]
        paths = {a["path"] for a in artifacts}
        self.assertIn("screenshots/fail_case.png", paths)
        # HTML should show the artifact table
        html = _render_metrics_section(agg)
        self.assertIn("screenshots/fail_case.png", html)
        linked_html = _render_metrics_section(
            agg,
            "https://nickmonrad.github.io/kernel-ai-assistant/test-results/results",
        )
        self.assertIn(
            'href="https://nickmonrad.github.io/kernel-ai-assistant/test-results/results/pr/1224/on_device/screenshots/fail_case.png"',
            linked_html,
        )

    def test_wake_metrics_render_counts_gates_timelines_and_artifacts(self) -> None:
        fixture_path = (
            SCRIPT_DIR
            / "testdata"
            / "fixtures"
            / "acoustic-wake-reliability"
            / "evidence-normalized-sample.json"
        )
        record = json.loads(fixture_path.read_text(encoding="utf-8"))
        record["_source_relpath"] = "pr/1408/on_device/evidence.json"

        aggregates = _build_aggregates([record])
        metrics = aggregates["metrics"]
        metrics["wake_reliability"]["completion_by_condition"] = [
            {
                "device_id": "s21-exynos",
                "idle_seconds": 5,
                "trial_type": "wake_only",
                "required_positions": 5,
                "attempted_positions": 5,
                "completed_positions": 5,
                "attempts": 6,
                "retry_attempts": 1,
                "valid_attempts": 5,
                "passed_attempts": 4,
                "failed_attempts": 1,
                "invalid_attempts": 1,
                "duplicate_valid_positions": 0,
                "missing_positions": 0,
            }
        ]
        metrics["wake_reliability"]["timing"] = {
            "aggregates": [
                {
                    "device_id": "s21-exynos",
                    "metric": "activation_to_callback_ms",
                    "clock_domain": "target_device_elapsed_realtime",
                    "sample_count": 4,
                    "min_ms": 42,
                    "p50_ms": 51,
                    "p95_ms": 61,
                    "max_ms": 61,
                }
            ],
            "samples": [],
        }
        aggregates["metrics"] = metrics
        
        wake_html = _render_wake_metrics_section(aggregates)
        metrics_html = _render_metrics_section(
            aggregates,
            "https://nickmonrad.github.io/kernel-ai-assistant/test-results/results",
        )


        self.assertIn("Acoustic Wake Reliability", wake_html)
        self.assertIn("2 valid · 1 invalid", wake_html)
        self.assertIn("Latest release-gate record", wake_html)
        self.assertIn("LATEST NOT RELEASE-READY", wake_html)
        self.assertIn("1/27", wake_html)
        self.assertIn("stt_readiness_failure", wake_html)
        self.assertIn("device_environment_error", wake_html)
        self.assertIn("source_device_elapsed_realtime", wake_html)
        self.assertIn("target_device_elapsed_realtime", wake_html)
        self.assertIn("Valid Matrix Coverage by Condition", wake_html)
        self.assertIn("5/5", wake_html)
        self.assertIn("Retry attempts", wake_html)
        self.assertIn("activation_to_callback_ms", wake_html)
        self.assertIn(">51<", wake_html)
        self.assertIn(
            'href="https://nickmonrad.github.io/kernel-ai-assistant/test-results/results/pr/1408/on_device/trials/trial-pass/attempt-1/target-events.json"',
            metrics_html,
        )

    # ------------------------------------------------------------------ #
    # Full dashboard build path tests
    # ------------------------------------------------------------------ #

    def test_full_dashboard_build_writes_all_json_files(self) -> None:
        """Full dashboard build writes all expected JSON data files."""
        import summarise_test_evidence_metrics as metrics_mod

        with tempfile.TemporaryDirectory() as tmp:
            results_dir = Path(tmp) / "results"
            out_dir = Path(tmp) / "out"
            results_dir.mkdir()

            # Write one valid evidence file
            (results_dir / "evidence.json").write_text(json.dumps(_make_record()))

            # Simulate main()'s build path
            from build_test_dashboard import _discover_results, _write_json_files

            evidence_list = _discover_results(results_dir)

            raw = metrics_mod.discover_evidence(results_dir)
            metrics = metrics_mod.summarise(raw) if raw else None

            aggregates = _build_aggregates(evidence_list, metrics=metrics)
            json_data = _build_json_data(aggregates)

            data_dir = out_dir / "data"
            data_dir.mkdir(parents=True)
            _write_json_files(json_data, data_dir)

            expected = ["latest.json", "history.json", "prs.json",
                        "devices.json", "releases.json", "metrics.json"]
            for name in expected:
                self.assertTrue((data_dir / name).exists(), f"Missing {name}")
                parsed = json.loads((data_dir / name).read_text())
                self.assertIsNotNone(parsed, f"{name} is null/empty")

    def test_metrics_json_in_build_json_data(self) -> None:
        """_build_json_data includes metrics.json when metrics block present."""
        rec = _make_record()
        aggregates = _build_aggregates([rec])
        json_data = _build_json_data(aggregates)
        self.assertIn("metrics.json", json_data)
        self.assertIsInstance(json_data["metrics.json"], dict)
        self.assertIn("validity", json_data["metrics.json"])  # type: ignore[operator]

    def test_malformed_evidence_invalid_records(self) -> None:
        """Malformed evidence file appears in metrics.validity.invalid_records."""
        import summarise_test_evidence_metrics as metrics_mod

        with tempfile.TemporaryDirectory() as tmp:
            results_dir = Path(tmp)
            (results_dir / "valid.json").write_text(json.dumps(_make_record()))
            # Missing required fields
            (results_dir / "invalid.json").write_text(json.dumps({"source": "ci"}))

            raw = metrics_mod.discover_evidence(results_dir)
            summary = metrics_mod.summarise(raw)

            self.assertGreaterEqual(summary["validity"]["invalid_records"], 1)
            self.assertEqual(summary["validity"]["valid_records"], 1)

    def test_malformed_evidence_issue_buckets(self) -> None:
        """Malformed evidence file contributes to validity issue_buckets."""
        import summarise_test_evidence_metrics as metrics_mod

        with tempfile.TemporaryDirectory() as tmp:
            results_dir = Path(tmp)
            (results_dir / "invalid.json").write_text(json.dumps({"source": "ci"}))

            raw = metrics_mod.discover_evidence(results_dir)
            summary = metrics_mod.summarise(raw)

            self.assertIn("issue_buckets", summary["validity"])
            self.assertGreater(len(summary["validity"]["issue_buckets"]), 0)
            # Should include missing-field issues
            all_issues = " ".join(summary["validity"]["issue_buckets"].keys())
            self.assertIn("missing:", all_issues)

    def test_valid_and_invalid_evidence_tolerance(self) -> None:
        """Dashboard generation succeeds with one valid + one invalid evidence file."""
        import summarise_test_evidence_metrics as metrics_mod
        from build_test_dashboard import _discover_results, _write_json_files

        with tempfile.TemporaryDirectory() as tmp:
            results_dir = Path(tmp) / "results"
            out_dir = Path(tmp) / "out"
            results_dir.mkdir()
            data_dir = out_dir / "data"
            data_dir.mkdir(parents=True)

            (results_dir / "valid.json").write_text(json.dumps(_make_record()))
            (results_dir / "invalid.json").write_text(json.dumps({"not": "evidence"}))

            evidence_list = _discover_results(results_dir)
            raw = metrics_mod.discover_evidence(results_dir)
            metrics = metrics_mod.summarise(raw) if raw else None
            aggregates = _build_aggregates(evidence_list, metrics=metrics)
            json_data = _build_json_data(aggregates)
            _write_json_files(json_data, data_dir)

            # metrics.json should show invalid evidence
            metrics_json = json.loads((data_dir / "metrics.json").read_text())
            self.assertGreaterEqual(metrics_json["validity"]["invalid_records"], 1)

            # Other JSON files should load without crashing
            for name in ("prs.json", "devices.json", "releases.json"):
                data = json.loads((data_dir / name).read_text())
                self.assertIsInstance(data, (list, dict))

    def test_invalid_evidence_does_not_crash_dashboard(self) -> None:
        """Dashboard generation handles all-invalid evidence gracefully."""
        import summarise_test_evidence_metrics as metrics_mod
        from build_test_dashboard import _discover_results, _write_json_files

        with tempfile.TemporaryDirectory() as tmp:
            results_dir = Path(tmp) / "results"
            out_dir = Path(tmp) / "out"
            results_dir.mkdir()
            data_dir = out_dir / "data"
            data_dir.mkdir(parents=True)

            # Only invalid files
            (results_dir / "bad.json").write_text("not json at all")
            (results_dir / "empty_obj.json").write_text("{}")

            evidence_list = _discover_results(results_dir)
            raw = metrics_mod.discover_evidence(results_dir)
            metrics = metrics_mod.summarise(raw) if raw else None
            aggregates = _build_aggregates(evidence_list, metrics=metrics)
            json_data = _build_json_data(aggregates)
            _write_json_files(json_data, data_dir)

            # metrics.json reports invalid records
            metrics_json = json.loads((data_dir / "metrics.json").read_text())
            self.assertGreaterEqual(metrics_json["validity"]["invalid_records"], 1)

            # Other JSON files should still be valid
            for name in ("latest.json", "history.json", "prs.json",
                        "devices.json", "releases.json"):
                data = json.loads((data_dir / name).read_text())
                self.assertIsNotNone(data)


    def test_dashboard_shows_invalid_acoustic_excluded_from_generic(self) -> None:
        """Finding 1: invalid acoustic attempts excluded from PR/device generic totals."""
        fixture_path = (
            SCRIPT_DIR / "testdata" / "fixtures" / "acoustic-wake-reliability"
            / "evidence-normalized-sample.json"
        )
        record = json.loads(fixture_path.read_text(encoding="utf-8"))
        record["_source_relpath"] = "evidence.json"
        record["pr"] = 1408

        aggregates = _build_aggregates([record])
        metrics = aggregates["metrics"]

        # Generic totals exclude the invalid attempt
        self.assertEqual(metrics["totals"]["total"], 2)
        self.assertEqual(metrics["totals"]["passed"], 1)
        self.assertEqual(metrics["totals"]["failed"], 1)

        # PR on-device status shows 1/2, not 1/3
        pr_data = aggregates["prs"][0]
        self.assertEqual(pr_data["pr"], 1408)
        self.assertEqual(pr_data["on_device"]["total"], 2)
        self.assertEqual(pr_data["on_device"]["passed"], 1)
        self.assertEqual(pr_data["on_device"]["failed"], 1)

        # Latest device summary shows 1/2
        device = aggregates["devices"][0]
        self.assertEqual(device["latest_summary"]["total"], 2)
        self.assertEqual(device["latest_summary"]["passed"], 1)
        self.assertEqual(device["latest_summary"]["failed"], 1)

        # Historical device aggregate also uses 1/2
        self.assertEqual(device["total"], 2)
        self.assertEqual(device["passed"], 1)
        self.assertEqual(device["failed"], 1)

        # Wake-specific rendering still shows three attempts with one invalid
        wake = metrics["wake_reliability"]["overall"]
        self.assertEqual(wake["attempts"], 3)
        self.assertEqual(wake["invalid"], 1)

    def test_wake_render_shows_producer_completion(self) -> None:
        """Finding 2: dashboard renders completion counts from producer, not 0/0."""
        fixture_path = (
            SCRIPT_DIR / "testdata" / "fixtures" / "acoustic-wake-reliability"
            / "evidence-normalized-sample.json"
        )
        record = json.loads(fixture_path.read_text(encoding="utf-8"))
        record["_source_relpath"] = "evidence.json"

        aggregates = _build_aggregates([record])
        metrics = aggregates["metrics"]
        completion = metrics["wake_reliability"]["completion"]
        wake_html = _render_wake_metrics_section(aggregates)

        # Completion values must be non-zero and correct (fixture has 1/27)
        self.assertGreater(completion["total_required"], 0)
        self.assertEqual(completion["total_required"], 27)
        self.assertEqual(completion["completed"], 1)
        self.assertEqual(completion["missing"], 26)
        # Dashboard renders the correct completion rather than 0/0
        self.assertIn("26 required positions missing", wake_html)

if __name__ == "__main__":
    unittest.main()
