#!/usr/bin/env python3
"""Tests for publish_permission_scenario_report.py."""

from __future__ import annotations

import argparse
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

HERE = Path(__file__).resolve().parent
SCRIPT_DIR = HERE.parent
sys.path.insert(0, str(SCRIPT_DIR))

import publish_permission_scenario_report as publisher


def sample_result(commit: str = "a" * 40, pr: int | None = None) -> dict:
    return {
        "schema_version": "1.0",
        "source": "on_device",
        "suite": "permission_scenarios",
        "timestamp": "2026-06-30T08:48:39Z",
        "repo": "NickMonrad/kernel-ai-assistant",
        "branch": "feature/1344-permission-report-publisher",
        "commit": commit,
        "pr": pr,
        "run_id": "on_device-2026-06-30T08-48-39Z-s21-exynos",
        "device": {
            "id": "s21-exynos",
            "serial": "R5CR605B71K",
            "label": "S21",
            "manufacturer": "Samsung",
            "model": "SM-G991B",
            "soc": "Exynos 2100",
            "tier": "tracked",
            "android_api": 35,
            "execution": "physical",
        },
        "thresholds": {
            "max_steps": 8,
            "max_user_taps": 5,
            "max_settings_hops": 1,
            "max_duration_seconds": 30,
            "max_back_presses": 2,
            "fail_on_manual_intervention": True,
        },
        "summary": {
            "total": 2,
            "functional": {"pass": 1, "blocked": 1},
            "ux": {"pass": 1, "not_assessed": 1},
            "example_report": "summary.md",
        },
        "scenarios": [
            {
                "schema_version": "1.0",
                "source": "on_device",
                "suite": "permission_scenarios",
                "scenario_id": "weather_location_denied",
                "scenario_title": "Weather request with location denied uses fallback UX",
                "timestamp": "2026-06-30T08:48:39Z",
                "repo": "NickMonrad/kernel-ai-assistant",
                "branch": "feature/1344-permission-report-publisher",
                "commit": commit,
                "pr": pr,
                "device": {"id": "s21-exynos", "execution": "physical", "serial": "R5CR605B71K"},
                "functional_result": "pass",
                "ux_result": "pass",
                "step_count": 3,
                "tap_count": 2,
                "settings_hops": 0,
                "back_presses": 0,
                "duration_seconds": 12.5,
                "manual_intervention_required": False,
                "steps": [
                    {
                        "index": 1,
                        "id": "launch",
                        "action": "launch_main",
                        "expected": "app visible",
                        "actual": "app visible",
                        "result": "pass",
                        "duration_ms": 1200,
                        "screenshot": "screenshots/01-weather.png",
                        "screenshot_error": None,
                        "debug": {},
                    }
                ],
                "artifacts": {},
                "ux_warnings": [],
                "blocked_reason": None,
            },
            {
                "schema_version": "1.0",
                "source": "on_device",
                "suite": "permission_scenarios",
                "scenario_id": "mic_denied_enable_hey_jandal",
                "scenario_title": "Enable Hey Jandal with microphone denied",
                "timestamp": "2026-06-30T08:48:39Z",
                "repo": "NickMonrad/kernel-ai-assistant",
                "branch": "feature/1344-permission-report-publisher",
                "commit": commit,
                "pr": pr,
                "device": {"id": "s21-exynos", "execution": "physical", "serial": "R5CR605B71K"},
                "functional_result": "blocked",
                "ux_result": "not_assessed",
                "step_count": 4,
                "tap_count": 1,
                "settings_hops": 1,
                "back_presses": 0,
                "duration_seconds": 8.2,
                "manual_intervention_required": False,
                "steps": [
                    {
                        "index": 1,
                        "id": "open_voice",
                        "action": "tap_visible",
                        "expected": "Voice settings opens",
                        "actual": "Set Jandal as default assistant first for reliable background mic access",
                        "result": "blocked",
                        "duration_ms": 400,
                        "screenshot": "screenshots/02-mic.png",
                        "screenshot_error": None,
                        "debug": {},
                    }
                ],
                "artifacts": {},
                "ux_warnings": [],
                "blocked_reason": "Set Jandal as default assistant first for reliable background mic access",
            },
        ],
        "artifacts": {
            "raw_json": "result.json",
            "summary": "summary.md",
            "logcat": "logcat.txt",
            "evidence": "evidence.json",
            "screenshots_dir": "screenshots",
        },
    }


def sample_evidence(commit: str = "a" * 40, pr: int | None = None) -> dict:
    return {
        "schema_version": "1.0",
        "source": "on_device",
        "suite": "permission_scenarios",
        "timestamp": "2026-06-30T08:48:39Z",
        "repo": "NickMonrad/kernel-ai-assistant",
        "branch": "feature/1344-permission-report-publisher",
        "commit": commit,
        "pr": pr,
        "release": None,
        "run_id": "on_device-2026-06-30T08-48-39Z-s21-exynos",
        "device": {
            "id": "s21-exynos",
            "serial": None,
            "label": "S21",
            "manufacturer": "Samsung",
            "model": "SM-G991B",
            "soc": "Exynos 2100",
            "tier": "tracked",
            "android_api": 35,
            "execution": "physical",
        },
        "model": {
            "name": "not_applicable",
            "runtime": "permission_scenario_runner",
            "backend": "adb",
        },
        "summary": {"total": 1, "passed": 1, "failed": 0, "pass_rate": 1.0},
        "cases": [
            {
                "name": "weather_location_denied",
                "passed": True,
                "expected_tool": None,
                "actual_tool": None,
                "expected_result_mode": "success",
                "actual_result_mode": "success",
                "chip_present": False,
                "skill_result_present": False,
                "message_saved": False,
                "retry_seen": False,
                "slot_fill_seen": False,
                "failure_category": None,
                "failures": [],
            }
        ],
    }


class PublishPermissionScenarioReportTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmpdir = tempfile.TemporaryDirectory(prefix="publish_permission_")
        self.root = Path(self.tmpdir.name)
        self.report_dir = self.root / "2026-06-30T08-48-39Z"
        self.report_dir.mkdir(parents=True)
        self.screenshots_dir = self.report_dir / "screenshots"
        self.screenshots_dir.mkdir()
        (self.screenshots_dir / "01-weather.png").write_bytes(b"png")
        (self.screenshots_dir / "02-mic.png").write_bytes(b"png")
        (self.report_dir / "summary.md").write_text("# summary\n", encoding="utf-8")
        (self.report_dir / "result.json").write_text(json.dumps(sample_result()) + "\n", encoding="utf-8")
        (self.report_dir / "evidence.json").write_text(json.dumps(sample_evidence()) + "\n", encoding="utf-8")
        (self.report_dir / "logcat.txt").write_text(
            "KernelAI: ok\nHuggingFaceAuthManager: restored auth=true, user=nick@example.com\n",
            encoding="utf-8",
        )
        self.args = argparse.Namespace(
            report_dir=self.report_dir,
            pr=1344,
            commit="a" * 40,
            device_id="s21-exynos",
            target_branch="test-results",
            repo="NickMonrad/kernel-ai-assistant",
            repo_url=None,
            allow_stale_report=False,
            dry_run=True,
        )

    def tearDown(self) -> None:
        self.tmpdir.cleanup()

    def test_validate_report_dir_loads_optional_artifacts(self) -> None:
        bundle = publisher.validate_report_dir(self.report_dir)
        self.assertEqual(bundle.result["suite"], "permission_scenarios")
        self.assertEqual(bundle.evidence["summary"]["total"], 1)
        self.assertEqual(len(bundle.screenshots), 2)
        self.assertIsNotNone(bundle.logcat_path)

    def test_validate_report_dir_requires_core_files(self) -> None:
        (self.report_dir / "summary.md").unlink()
        with self.assertRaisesRegex(publisher.PublishError, "summary.md"):
            publisher.validate_report_dir(self.report_dir)

    def test_validate_report_dir_rejects_missing_referenced_screenshot(self) -> None:
        (self.screenshots_dir / "02-mic.png").unlink()
        with self.assertRaisesRegex(publisher.PublishError, "missing screenshot"):
            publisher.validate_report_dir(self.report_dir)

    def test_validate_report_metadata_rejects_report_commit_mismatch(self) -> None:
        bundle = publisher.validate_report_dir(self.report_dir)
        args = argparse.Namespace(**{**vars(self.args), "commit": "b" * 40})
        with self.assertRaisesRegex(publisher.PublishError, "Report commit"):
            publisher.validate_report_metadata(bundle, args, pr_head_sha="b" * 40)

    def test_validate_report_metadata_rejects_pr_head_mismatch(self) -> None:
        bundle = publisher.validate_report_dir(self.report_dir)
        with self.assertRaisesRegex(publisher.PublishError, "head SHA"):
            publisher.validate_report_metadata(bundle, self.args, pr_head_sha="b" * 40)

    def test_validate_report_metadata_allows_override_for_stale_report(self) -> None:
        bundle = publisher.validate_report_dir(self.report_dir)
        args = argparse.Namespace(**{**vars(self.args), "allow_stale_report": True, "commit": "b" * 40})
        publisher.validate_report_metadata(bundle, args, pr_head_sha="c" * 40)

    def test_ensure_evidence_matches_result_accepts_blocked_omission(self) -> None:
        bundle = publisher.validate_report_dir(self.report_dir)
        publisher.ensure_evidence_matches_result(bundle)

    def test_build_public_result_nulls_device_serial(self) -> None:
        bundle = publisher.validate_report_dir(self.report_dir)
        public_result = publisher.build_public_result(bundle, 1344)
        self.assertEqual(public_result["pr"], 1344)
        self.assertIsNone(public_result["device"]["serial"])
        self.assertTrue(all(scenario["device"]["serial"] is None for scenario in public_result["scenarios"]))
        self.assertTrue(all(scenario["pr"] == 1344 for scenario in public_result["scenarios"]))

    def test_build_published_paths_uses_results_and_artifacts_layout(self) -> None:
        bundle = publisher.validate_report_dir(self.report_dir)
        published = publisher.build_published_paths(bundle, 1344, "s21-exynos")
        self.assertEqual(
            published.evidence,
            "results/pr/1344/on_device/permissions/s21-exynos/2026-06-30T08-48-39Z/evidence.json",
        )
        self.assertEqual(
            published.result,
            "artifacts/pr/1344/permissions/s21-exynos/2026-06-30T08-48-39Z/result.json",
        )
        self.assertEqual(
            published.summary,
            "artifacts/pr/1344/permissions/s21-exynos/2026-06-30T08-48-39Z/summary.md",
        )
        self.assertEqual(
            published.screenshots_dir,
            "artifacts/pr/1344/permissions/s21-exynos/2026-06-30T08-48-39Z/screenshots",
        )

    def test_sanitize_logcat_redacts_user(self) -> None:
        text = publisher.sanitize_logcat_for_publish(self.report_dir / "logcat.txt")
        self.assertIn("user=<redacted>", text)
        self.assertNotIn("nick@example.com", text)

    def test_build_comment_body_keeps_blocked_reason_visible(self) -> None:
        bundle = publisher.validate_report_dir(self.report_dir)
        published = publisher.build_published_paths(bundle, 1344, "s21-exynos")
        body = publisher.build_comment_body(bundle, published, self.args)
        self.assertTrue(body.startswith(publisher.STICKY_MARKER))
        self.assertIn("Weather request with location denied uses fallback UX", body)
        self.assertIn("Enable Hey Jandal with microphone denied", body)
        self.assertIn("Set Jandal as default assistant first for reliable background mic access", body)
        self.assertIn("Blocked/skipped scenarios remain visible", body)
        self.assertIn("Schema-compatible evidence", body)

    def test_build_comment_body_uses_report_commit_when_stale_override_enabled(self) -> None:
        bundle = publisher.validate_report_dir(self.report_dir)
        published = publisher.build_published_paths(bundle, 1344, "s21-exynos")
        args = argparse.Namespace(**{**vars(self.args), "allow_stale_report": True, "commit": "b" * 40})
        body = publisher.build_comment_body(bundle, published, args)
        self.assertIn("`aaaaaaaaaaaa`", body)
        self.assertIn("requested head `bbbbbbbbbbbb`", body)

    def test_choose_comment_action(self) -> None:
        self.assertEqual(publisher.choose_comment_action(None), "create")
        self.assertEqual(publisher.choose_comment_action(42), "update")

    def test_prepare_publish_mapping_uses_public_result_and_optional_logcat(self) -> None:
        bundle = publisher.validate_report_dir(self.report_dir)
        published = publisher.build_published_paths(bundle, 1344, "s21-exynos")
        with tempfile.TemporaryDirectory(prefix="publish_map_") as scratch:
            mapping = publisher.prepare_publish_mapping(bundle, published, Path(scratch), 1344)
            public_result_path = next(path for path, dest in mapping.items() if dest == published.result)
            public_result = json.loads(public_result_path.read_text(encoding="utf-8"))
            public_evidence_path = next(path for path, dest in mapping.items() if dest == published.evidence)
            public_evidence = json.loads(public_evidence_path.read_text(encoding="utf-8"))
        self.assertEqual(public_result["pr"], 1344)
        self.assertIsNone(public_result["device"]["serial"])
        self.assertEqual(public_evidence["pr"], 1344)
        self.assertIn("artifacts/pr/1344/permissions/s21-exynos/2026-06-30T08-48-39Z/logcat-redacted.txt", mapping.values())

    def test_prepare_publish_mapping_excludes_missing_optional_artifacts(self) -> None:
        (self.report_dir / "logcat.txt").unlink()
        bundle = publisher.validate_report_dir(self.report_dir)
        published = publisher.build_published_paths(bundle, 1344, "s21-exynos")
        with tempfile.TemporaryDirectory(prefix="publish_map_") as scratch:
            mapping = publisher.prepare_publish_mapping(bundle, published, Path(scratch), 1344)
        self.assertNotIn("artifacts/pr/1344/permissions/s21-exynos/2026-06-30T08-48-39Z/logcat-redacted.txt", mapping.values())

    @mock.patch.object(publisher.GitHubClient, "find_sticky_comment_id", return_value=123)
    @mock.patch.object(publisher.GitHubClient, "get_pr_head_sha", return_value="a" * 40)
    @mock.patch.object(publisher.publish_helpers, "_publish_to_branch")
    @mock.patch.object(publisher.publish_helpers, "_check_git_available")
    def test_main_dry_run_skips_publish_and_uses_update_action(
        self,
        _check_git_available: mock.Mock,
        _publish_to_branch: mock.Mock,
        _get_pr_head_sha: mock.Mock,
        _find_comment: mock.Mock,
    ) -> None:
        rc = publisher.main(
            [
                "--report-dir",
                str(self.report_dir),
                "--pr",
                "1344",
                "--commit",
                "a" * 40,
                "--device-id",
                "s21-exynos",
                "--dry-run",
            ]
        )
        self.assertEqual(rc, 0)
        _publish_to_branch.assert_not_called()


if __name__ == "__main__":
    unittest.main()
