#!/usr/bin/env python3
"""Tests for run_permission_scenarios.py."""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from dataclasses import asdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT_DIR = HERE.parent
sys.path.insert(0, str(SCRIPT_DIR))

import run_permission_scenarios as permission_runner


class PermissionScenarioRunnerTest(unittest.TestCase):
    def test_select_scenarios_returns_requested_order(self) -> None:
        selected = permission_runner.select_scenarios(
            [
                "weather_location_denied",
                "hey_jandal_preflight",
            ]
        )

        self.assertEqual(
            ["weather_location_denied", "hey_jandal_preflight"],
            [scenario["id"] for scenario in selected],
        )

    def test_select_scenarios_rejects_unknown_ids(self) -> None:
        with self.assertRaises(permission_runner.RunnerError):
            permission_runner.select_scenarios(["missing_scenario"])

    def test_to_evidence_projects_rich_report_into_schema_shape(self) -> None:
        scenario = permission_runner.ScenarioResult(
            schema_version="1.0",
            source="on_device",
            suite="permission_scenarios",
            scenario_id="weather_location_denied",
            scenario_title="Weather request with location denied uses fallback UX",
            timestamp="2026-06-29T00:00:00Z",
            repo="NickMonrad/kernel-ai-assistant",
            branch="feature/test",
            commit="a" * 40,
            pr=1330,
            device={"id": "s21-exynos", "execution": "physical"},
            functional_result="pass",
            ux_result="warning",
            step_count=3,
            tap_count=2,
            settings_hops=0,
            back_presses=0,
            duration_seconds=12.5,
            manual_intervention_required=False,
            steps=[
                permission_runner.StepTrace(
                    index=1,
                    id="launch_weather_query",
                    action="launch_quick_action",
                    expected="Weather permission dialog appears",
                    actual="Quick action launched",
                    result="pass",
                    duration_ms=1200,
                    screenshot="screenshots/01-weather.png",
                )
            ],
            artifacts={"raw_json": "result.json"},
            ux_warnings=["steps 9 > 8"],
        )
        run_result = permission_runner.RunResult(
            schema_version="1.0",
            source="on_device",
            suite="permission_scenarios",
            timestamp="2026-06-29T00:00:00Z",
            repo="NickMonrad/kernel-ai-assistant",
            branch="feature/test",
            commit="a" * 40,
            pr=1330,
            run_id="on_device-2026-06-29T00-00-00Z-s21-exynos",
            device={"id": "s21-exynos", "execution": "physical"},
            thresholds=dict(permission_runner.DEFAULT_UX_THRESHOLDS),
            summary={"total": 1},
            scenarios=[scenario],
            artifacts={"raw_json": "result.json", "evidence": "evidence.json", "logcat": "logcat.txt", "screenshots_dir": "screenshots"},
        )

        evidence = permission_runner.to_evidence(run_result)

        self.assertEqual("permission_scenarios", evidence["suite"])
        self.assertEqual(1, evidence["summary"]["passed"])
        self.assertEqual(1, len(evidence["cases"]))
        self.assertEqual("weather_location_denied", evidence["cases"][0]["name"])
        self.assertEqual([], evidence["cases"][0]["failures"])
        self.assertEqual("not_applicable", evidence["model"]["name"])
        self.assertEqual("permission_scenario_runner", evidence["model"]["runtime"])
        self.assertEqual("adb", evidence["model"]["backend"])

    def test_write_summary_includes_artifacts_and_table(self) -> None:
        scenario = permission_runner.ScenarioResult(
            schema_version="1.0",
            source="on_device",
            suite="permission_scenarios",
            scenario_id="hey_jandal_preflight",
            scenario_title="Hey Jandal preflight checks default assistant setup",
            timestamp="2026-06-29T00:00:00Z",
            repo="NickMonrad/kernel-ai-assistant",
            branch="feature/test",
            commit="b" * 40,
            pr=1330,
            device={"id": "s21-exynos", "execution": "physical", "label": "S21", "manufacturer": "Samsung", "model": "SM-G991B"},
            functional_result="blocked",
            ux_result="not_assessed",
            step_count=2,
            tap_count=1,
            settings_hops=0,
            back_presses=0,
            duration_seconds=5.1,
            manual_intervention_required=False,
            steps=[
                permission_runner.StepTrace(
                    index=1,
                    id="launch_app",
                    action="launch_main",
                    expected="Jandal opens on the main screen",
                    actual="MainActivity launched",
                    result="pass",
                    duration_ms=700,
                ),
                permission_runner.StepTrace(
                    index=2,
                    id="open_voice_settings",
                    action="tap_visible",
                    expected="Voice settings opens",
                    actual="Target not visible",
                    result="blocked",
                    duration_ms=1800,
                    screenshot="screenshots/02-open-voice.png",
                ),
            ],
            artifacts={"raw_json": "result.json", "summary": "summary.md", "evidence": "evidence.json", "logcat": "logcat.txt", "screenshots": ["screenshots/02-open-voice.png"]},
            blocked_reason="Target not visible",
        )
        run_result = permission_runner.RunResult(
            schema_version="1.0",
            source="on_device",
            suite="permission_scenarios",
            timestamp="2026-06-29T00:00:00Z",
            repo="NickMonrad/kernel-ai-assistant",
            branch="feature/test",
            commit="b" * 40,
            pr=1330,
            run_id="on_device-2026-06-29T00-00-00Z-s21-exynos",
            device={"id": "s21-exynos", "label": "S21", "manufacturer": "Samsung", "model": "SM-G991B", "execution": "physical"},
            thresholds=dict(permission_runner.DEFAULT_UX_THRESHOLDS),
            summary={"total": 1},
            scenarios=[scenario],
            artifacts={"raw_json": "result.json", "evidence": "evidence.json", "logcat": "logcat.txt", "screenshots_dir": "screenshots"},
        )

        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "summary.md"
            markdown = permission_runner.write_summary(run_result, path)
            written = path.read_text(encoding="utf-8")

        self.assertIn("| Scenario | Functional | UX | Steps |", markdown)
        self.assertIn("Hey Jandal preflight checks default assistant setup", markdown)
        self.assertIn("Schema-compatible evidence", markdown)
        self.assertEqual(markdown, written)

    def test_build_run_result_counts_functional_and_ux_outcomes(self) -> None:
        scenarios = [
            permission_runner.ScenarioResult(
                schema_version="1.0",
                source="on_device",
                suite="permission_scenarios",
                scenario_id="a",
                scenario_title="A",
                timestamp="2026-06-29T00:00:00Z",
                repo="NickMonrad/kernel-ai-assistant",
                branch="feature/test",
                commit="c" * 40,
                pr=None,
                device={"id": "s21-exynos", "execution": "physical"},
                functional_result="pass",
                ux_result="pass",
                step_count=1,
                tap_count=0,
                settings_hops=0,
                back_presses=0,
                duration_seconds=1.0,
                manual_intervention_required=False,
                steps=[],
                artifacts={},
            ),
            permission_runner.ScenarioResult(
                schema_version="1.0",
                source="on_device",
                suite="permission_scenarios",
                scenario_id="b",
                scenario_title="B",
                timestamp="2026-06-29T00:00:00Z",
                repo="NickMonrad/kernel-ai-assistant",
                branch="feature/test",
                commit="c" * 40,
                pr=None,
                device={"id": "s21-exynos", "execution": "physical"},
                functional_result="blocked",
                ux_result="not_assessed",
                step_count=1,
                tap_count=0,
                settings_hops=0,
                back_presses=0,
                duration_seconds=1.0,
                manual_intervention_required=False,
                steps=[],
                artifacts={},
                blocked_reason="device state",
            ),
        ]

        run_result = permission_runner.build_run_result(
            timestamp="2026-06-29T00:00:00Z",
            run_id="on_device-2026-06-29T00-00-00Z-s21-exynos",
            branch="feature/test",
            commit="c" * 40,
            pr=None,
            device={"id": "s21-exynos", "execution": "physical"},
            thresholds=dict(permission_runner.DEFAULT_UX_THRESHOLDS),
            scenarios=scenarios,
        )

        self.assertEqual({"pass": 1, "blocked": 1}, run_result.summary["functional"])
        self.assertEqual({"pass": 1, "not_assessed": 1}, run_result.summary["ux"])


class _FakeAdb:
    def __init__(self, responses: dict[str, str]) -> None:
        self.responses = responses

    def shell(self, command: str, timeout: float = 30.0, check: bool = True) -> str:
        return self.responses.get(command, "")


class AssistantDetectionTest(unittest.TestCase):
    def _runner(self, responses: dict[str, str]) -> permission_runner.ScenarioRunner:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        return permission_runner.ScenarioRunner(
            adb=_FakeAdb(responses),
            device={"id": "s21-exynos", "execution": "physical"},
            branch="feature/test",
            commit="d" * 40,
            pr=None,
            run_dir=Path(tmp.name),
            thresholds=dict(permission_runner.DEFAULT_UX_THRESHOLDS),
        )

    def test_detect_default_assistant_ready_from_role_holder(self) -> None:
        runner = self._runner({
            "cmd role get-role-holders android.app.role.ASSISTANT": "com.kernel.ai.debug",
            "settings get secure voice_interaction_service": "",
            "settings get secure assistant": "",
        })
        configured, detail = runner._detect_default_assistant_state()
        self.assertTrue(configured)
        self.assertIn("com.kernel.ai.debug", detail)

    def test_detect_default_assistant_blocked_when_google_is_holder(self) -> None:
        runner = self._runner({
            "cmd role get-role-holders android.app.role.ASSISTANT": "com.google.android.googlequicksearchbox",
            "settings get secure voice_interaction_service": "com.google.android.googlequicksearchbox/com.google.android.voiceinteraction.GsaVoiceInteractionService",
            "settings get secure assistant": "com.google.android.googlequicksearchbox/com.google.android.voiceinteraction.GsaVoiceInteractionService",
        })
        with self.assertRaisesRegex(permission_runner.ScenarioBlocked, "default assistant"):
            runner._check_default_assistant_ready()


if __name__ == "__main__":
    unittest.main()
