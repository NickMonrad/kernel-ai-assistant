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

    def test_hey_jandal_enable_scenarios_reset_toggle_before_enable_or_prompt(self) -> None:
        scenarios = {scenario["id"]: scenario for scenario in permission_runner.SCENARIOS}
        enable_steps = [step["id"] for step in scenarios["hey_jandal_enable_mic_granted"]["steps"]]
        denied_steps = [step["id"] for step in scenarios["hey_jandal_enable_mic_denied"]["steps"]]

        self.assertLess(
            enable_steps.index("reset_hey_jandal_toggle_off"),
            enable_steps.index("enable_hey_jandal_toggle"),
        )
        self.assertLess(
            denied_steps.index("reset_hey_jandal_toggle_off"),
            denied_steps.index("reset_microphone_prompt_state"),
        )
        self.assertLess(
            denied_steps.index("reset_microphone_prompt_state"),
            denied_steps.index("relaunch_app_after_permission_reset"),
        )
        self.assertLess(
            denied_steps.index("relaunch_app_after_permission_reset"),
            denied_steps.index("reopen_voice_settings_after_permission_reset"),
        )
        self.assertLess(
            denied_steps.index("reopen_voice_settings_after_permission_reset"),
            denied_steps.index("recheck_default_assistant_ready"),
        )
        self.assertLess(
            denied_steps.index("recheck_default_assistant_ready"),
            denied_steps.index("request_microphone_via_toggle"),
        )


    def test_hey_jandal_mic_revoked_reopen_voice_orders_grant_enable_revoke_reopen(self) -> None:
        scenarios = {scenario["id"]: scenario for scenario in permission_runner.SCENARIOS}
        steps = [step["id"] for step in scenarios["hey_jandal_mic_revoked_reopen_voice"]["steps"]]

        # Grant before enable
        self.assertLess(
            steps.index("grant_microphone"),
            steps.index("enable_hey_jandal_toggle"),
        )
        # Toggle reset before enable
        self.assertLess(
            steps.index("reset_hey_jandal_toggle_off"),
            steps.index("enable_hey_jandal_toggle"),
        )
        # Enable before background (revoke preparation)
        self.assertLess(
            steps.index("enable_hey_jandal_toggle"),
            steps.index("background_app_before_revoke"),
        )
        # Background before revoke
        self.assertLess(
            steps.index("background_app_before_revoke"),
            steps.index("revoke_microphone_externally"),
        )
        # Revoke before relaunch
        self.assertLess(
            steps.index("revoke_microphone_externally"),
            steps.index("relaunch_and_assert_durability"),
        )
        # Durability dialog assertion is the final step
        self.assertEqual(
            steps[-1],
            "relaunch_and_assert_durability",
            "Durability dialog assertion should be the final step of the revoked/reopen scenario",
        )
        # No monkey/LeakCanary-prone actions in this scenario
        self.assertNotIn("launch_app", steps[len(steps)-1])

    def test_hey_jandal_mic_revoked_reopen_voice_asserts_durability_dialog_text(self) -> None:
        scenarios = {scenario["id"]: scenario for scenario in permission_runner.SCENARIOS}
        last_step = scenarios["hey_jandal_mic_revoked_reopen_voice"]["steps"][-1]
        visible = last_step.get("expected_visible", [])
        self.assertIn("Microphone access was removed", visible)
        self.assertIn("Open Microphone permission settings", visible)

    def test_hey_jandal_mic_revoked_reopen_voice_has_no_monkey_or_leakcanary_resume(self) -> None:
        """Verifies the scenario uses deterministic re-entry, never monkey/LeakCanary-prone resume."""
        scenarios = {scenario["id"]: scenario for scenario in permission_runner.SCENARIOS}
        steps = scenarios["hey_jandal_mic_revoked_reopen_voice"]["steps"]
        for step in steps:
            action = step.get("action", "")
            action_text = step.get("expected", "")
            self.assertNotIn("monkey", action, f"Step {step['id']} uses monkey: {action}")
            self.assertNotIn(
                "LeakCanary",
                action_text,
                f"Step {step['id']} references LeakCanary-prone path: {action_text}",
            )
    def test_apply_expectations_prioritizes_blocked_marker_before_missing_expected_text(self) -> None:
        runner = self._runner({
            "cmd role get-role-holders android.app.role.ASSISTANT": "",
            "settings get secure voice_interaction_service": "",
            "settings get secure assistant": "",
        })

        def wait_for_any_text(texts: list[str], timeout_seconds: float, exact: bool = True) -> bool:
            return "Wake word model not yet available" in texts

        runner._wait_for_any_text = wait_for_any_text  # type: ignore[method-assign]
        runner._wait_for_text = lambda text, timeout_seconds: False  # type: ignore[method-assign]

        with self.assertRaisesRegex(permission_runner.ScenarioBlocked, "Wake word model"):
            runner._apply_expectations(
                {
                    "expected_visible": ['Listen for "Hey Jandal"'],
                    "blocked_if_visible": {
                        "texts": ["Wake word model not yet available"],
                        "reason": "Wake word model is not available on this build; Hey Jandal voice scenarios cannot run yet.",
                    },
                }
            )

    def test_find_switch_for_anchor_accepts_compose_checkable_view(self) -> None:
        runner = self._runner({
            "cmd role get-role-holders android.app.role.ASSISTANT": "",
            "settings get secure voice_interaction_service": "",
            "settings get secure assistant": "",
        })
        anchor = permission_runner.UiNode(
            text='Listen for "Hey Jandal"',
            content_desc="",
            resource_id="",
            class_name="android.widget.TextView",
            bounds=(168, 647, 693, 719),
            clickable=False,
            enabled=True,
            checked=False,
            package="com.kernel.ai.debug",
        )
        compose_toggle = permission_runner.UiNode(
            text="",
            content_desc="",
            resource_id="",
            class_name="android.view.View",
            bounds=(876, 647, 1032, 791),
            clickable=True,
            enabled=True,
            checked=True,
            package="com.kernel.ai.debug",
        )
        runner.ui.dump_nodes = lambda timeout=15.0: [anchor, compose_toggle]  # type: ignore[method-assign]

        switch = runner._find_switch_for_anchor(anchor, timeout_seconds=0.1)

        self.assertEqual("android.view.View", switch.class_name)
        self.assertTrue(switch.clickable)
        self.assertTrue(switch.checked)




class ScenarioValidationTest(unittest.TestCase):
    """Tests for scenario definition validation."""

    def test_valid_scenarios_all_pass(self) -> None:
        errors = permission_runner.validate_scenario_definitions(permission_runner.SCENARIOS)
        self.assertEqual([], errors, f"Existing scenarios should pass validation:\n" + "\n".join(errors))

    def test_missing_id_fails(self) -> None:
        errors = permission_runner.validate_scenario_definitions([{"title": "no id", "capability": "x", "tags": [], "steps": []}])
        self.assertTrue(any("missing required field 'id'" in e for e in errors))

    def test_missing_title_fails(self) -> None:
        errors = permission_runner.validate_scenario_definitions([{"id": "no_title", "capability": "x", "tags": [], "steps": []}])
        self.assertTrue(any("missing required field 'title'" in e for e in errors))

    def test_duplicate_scenario_id_fails(self) -> None:
        scenario = {"id": "dup", "title": "t", "capability": "x", "tags": [], "steps": []}
        errors = permission_runner.validate_scenario_definitions([scenario, scenario])
        self.assertTrue(any("Duplicate scenario ID: 'dup'" in e for e in errors))

    def test_missing_step_action_fails(self) -> None:
        errors = permission_runner.validate_scenario_definitions([
            {"id": "bad_step", "title": "t", "capability": "x", "tags": [], "steps": [{"id": "s1"}]}
        ])
        self.assertTrue(any("missing required field 'action'" in e for e in errors))

    def test_missing_step_expected_fails(self) -> None:
        errors = permission_runner.validate_scenario_definitions([
            {"id": "no_expected", "title": "t", "capability": "x", "tags": [], "steps": [
                {"id": "s1", "action": "press_home"}
            ]}
        ])
        self.assertTrue(any("missing required field 'expected'" in e for e in errors))

    def test_duplicate_step_id_fails(self) -> None:
        errors = permission_runner.validate_scenario_definitions([
            {"id": "dup_steps", "title": "t", "capability": "x", "tags": [], "steps": [
                {"id": "s1", "action": "press_home", "expected": "ok"},
                {"id": "s1", "action": "press_back", "expected": "ok"},
            ]}
        ])
        self.assertTrue(any("duplicate step ID" in e for e in errors))

    def test_unsupported_action_fails(self) -> None:
        errors = permission_runner.validate_scenario_definitions([
            {"id": "bad_action", "title": "t", "capability": "x", "tags": [], "steps": [
                {"id": "s1", "action": "fly_to_moon", "expected": "fail"}
            ]}
        ])
        self.assertTrue(any("unsupported action" in e for e in errors))

    def test_unsupported_permission_state_fails(self) -> None:
        errors = permission_runner.validate_scenario_definitions([
            {"id": "bad_state", "title": "t", "capability": "x", "tags": [], "steps": [
                {"id": "s1", "action": "set_permission_state", "permission": "android.permission.RECORD_AUDIO", "state": "super_granted", "expected": "bad"}
            ]}
        ])
        self.assertTrue(any("unsupported permission state" in e for e in errors))

    def test_missing_permission_field_fails(self) -> None:
        errors = permission_runner.validate_scenario_definitions([
            {"id": "no_perm", "title": "t", "capability": "x", "tags": [], "steps": [
                {"id": "s1", "action": "set_permission_state", "state": "granted", "expected": "bad"}
            ]}
        ])
        self.assertTrue(any("requires 'permission' field" in e for e in errors))

    def test_set_toggle_state_missing_checked_fails(self) -> None:
        errors = permission_runner.validate_scenario_definitions([
            {"id": "no_checked", "title": "t", "capability": "x", "tags": [], "steps": [
                {"id": "s1", "action": "set_toggle_state", "anchor_text": "hey", "expected": "bad"}
            ]}
        ])
        self.assertTrue(any("requires 'checked' field" in e for e in errors))

    def test_valid_preconditions_and_cleanup_pass(self) -> None:
        scenario = {
            "id": "with_blocks", "title": "t", "capability": "x", "tags": [],
            "preconditions": [
                {"id": "pc1", "action": "set_permission_state", "permission": "android.permission.RECORD_AUDIO", "state": "granted", "expected": "grant"},
            ],
            "cleanup": [
                {"id": "cl1", "action": "press_home", "expected": "home"},
            ],
            "steps": [
                {"id": "s1", "action": "press_back", "expected": "back"},
            ],
        }
        errors = permission_runner.validate_scenario_definitions([scenario])
        self.assertEqual([], errors)

    def test_dry_run_plan_includes_scenario_metadata(self) -> None:
        scenarios = [
            {"id": "test_s1", "title": "Test One", "capability": "wake_word", "tags": ["voice"], "preconditions": [], "cleanup": [], "fixtures": {}, "steps": [
                {"id": "s1", "action": "set_permission_state", "permission": "android.permission.RECORD_AUDIO", "state": "granted", "expected": "g"},
                {"id": "s2", "action": "press_home", "expected": "h", "screenshot": True},
            ]},
        ]
        plan = permission_runner.build_dry_run_plan(scenarios)
        self.assertEqual(1, len(plan))
        entry = plan[0]
        self.assertEqual("test_s1", entry["id"])
        self.assertEqual("Test One", entry["title"])
        self.assertEqual("wake_word", entry["capability"])
        self.assertEqual(["voice"], entry["tags"])
        self.assertEqual(0, entry["precondition_count"])
        self.assertEqual(2, entry["step_count"])
        self.assertEqual(0, entry["cleanup_count"])
        self.assertEqual(1, entry["screenshot_count"])
        self.assertEqual(["android.permission.RECORD_AUDIO"], entry["permissions_touched"])




class FixtureMergeTest(unittest.TestCase):
    """Tests for fixture merging semantics."""

    def test_merge_uses_global_fixtures_when_no_overrides(self) -> None:
        sc = {"fixtures": {}}
        merged = permission_runner.merge_fixtures(sc)
        self.assertEqual(permission_runner.FIXTURES, merged)

    def test_merge_overrides_global_with_scenario_specific(self) -> None:
        sc = {"fixtures": {"weather_named_location": "Oslo"}}
        merged = permission_runner.merge_fixtures(sc)
        self.assertEqual("Oslo", merged["weather_named_location"])
        # Unrelated global key still present
        self.assertEqual(10, merged["short_timer_seconds"])

    def test_merge_preserves_unrelated_global_fixtures(self) -> None:
        sc = {"fixtures": {"short_timer_seconds": 42}}
        merged = permission_runner.merge_fixtures(sc)
        self.assertEqual(42, merged["short_timer_seconds"])
        self.assertEqual("Tokyo", merged["weather_named_location"])

    def test_dry_run_includes_merged_fixtures(self) -> None:
        scenarios = [
            {"id": "fixture_dry", "title": "T", "capability": "x", "tags": [],
             "preconditions": [], "cleanup": [], "fixtures": {"weather_named_location": "Berlin"},
             "steps": [{"id": "s1", "action": "press_home", "expected": "h"}]},
        ]
        plan = permission_runner.build_dry_run_plan(scenarios)
        merged = plan[0]["fixtures"]
        self.assertEqual("Berlin", merged["weather_named_location"])
        self.assertEqual(10, merged["short_timer_seconds"])  # from global FIXTURES

class ScenarioExecutionOrderTest(unittest.TestCase):
    """Behavioral tests for precondition/cleanup execution order and semantics."""

    def _make_scenario(
        self,
        preconditions: list[dict] | None = None,
        steps: list[dict] | None = None,
        cleanup: list[dict] | None = None,
    ) -> dict:
        return {
            "id": "exec_order_test",
            "title": "Execution order test",
            "capability": "test",
            "tags": ["test"],
            "preconditions": preconditions or [],
            "cleanup": cleanup or [],
            "fixtures": {},
            "steps": steps or [{"id": "s1", "action": "press_home", "expected": "home"}],
        }

    def _fake_runner(self) -> permission_runner.ScenarioRunner:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        return permission_runner.ScenarioRunner(
            adb=_FakeAdb(),
            device={"id": "s21-exynos", "execution": "physical"},
            branch="feature/test",
            commit="d" * 40,
            pr=None,
            run_dir=Path(tmp.name),
            thresholds=dict(permission_runner.DEFAULT_UX_THRESHOLDS),
        )

    def test_preconditions_run_before_steps(self) -> None:
        sc = self._make_scenario(
            preconditions=[{"id": "pc1", "action": "press_back", "expected": "back step"}],
        )
        runner = self._fake_runner()
        result = runner.run_scenario(sc)

        # Precondition trace should appear before main step trace
        self.assertGreater(len(result.steps), 1)
        self.assertEqual("precondition", result.steps[0].phase)
        self.assertEqual("pc1", result.steps[0].id)
        self.assertEqual("main", result.steps[1].phase)
        self.assertEqual("s1", result.steps[1].id)

    def test_cleanup_runs_after_pass(self) -> None:
        sc = self._make_scenario(
            cleanup=[{"id": "cl1", "action": "press_back", "expected": "cleanup step"}],
        )
        runner = self._fake_runner()
        result = runner.run_scenario(sc)

        # Cleanup trace should be last
        self.assertEqual(2, len(result.steps))
        self.assertEqual("main", result.steps[0].phase)
        self.assertEqual("s1", result.steps[0].id)
        self.assertEqual("cleanup", result.steps[1].phase)
        self.assertEqual("cl1", result.steps[1].id)
        self.assertEqual("pass", result.functional_result)

    def test_cleanup_runs_after_fail(self) -> None:
        sc = self._make_scenario(
            steps=[{"id": "bad_step", "action": "launch_main", "expected": "will fail"}],
            cleanup=[{"id": "cl1", "action": "press_back", "expected": "cleanup after fail"}],
        )
        runner = self._fake_runner()
        result = runner.run_scenario(sc)

        # Main step failed, but cleanup still ran
        self.assertEqual(2, len(result.steps))
        self.assertEqual("main", result.steps[0].phase)
        self.assertEqual("fail", result.steps[0].result)
        self.assertEqual("fail", result.functional_result)
        self.assertEqual("cleanup", result.steps[1].phase)
        self.assertEqual("cl1", result.steps[1].id)
        self.assertEqual("pass", result.steps[1].result)

    def test_cleanup_runs_after_precondition_blocked(self) -> None:
        """Precondition failure -> blocked, cleanup still runs."""
        sc = self._make_scenario(
            preconditions=[{"id": "bad_pc", "action": "launch_main", "expected": "will block"}],
            cleanup=[{"id": "cl1", "action": "press_back", "expected": "cleanup after blocked"}],
        )
        runner = self._fake_runner()
        result = runner.run_scenario(sc)

        # Precondition blocked, but cleanup still ran
        self.assertEqual(2, len(result.steps))
        self.assertEqual("precondition", result.steps[0].phase)
        self.assertEqual("blocked", result.steps[0].result)
        self.assertEqual("blocked", result.functional_result)
        self.assertEqual("cleanup", result.steps[1].phase)
        self.assertEqual("cl1", result.steps[1].id)
        self.assertEqual("pass", result.steps[1].result)

    def test_cleanup_failure_does_not_affect_functional_result(self) -> None:
        sc = self._make_scenario(
            cleanup=[{"id": "bad_cl", "action": "launch_main", "expected": "cleanup will fail"}],
        )
        runner = self._fake_runner()
        result = runner.run_scenario(sc)

        # Main step passed, cleanup failed, but result is still pass
        self.assertEqual(2, len(result.steps))
        self.assertEqual("pass", result.steps[0].result)
        self.assertEqual("pass", result.functional_result)
        self.assertEqual("cleanup", result.steps[1].phase)
        self.assertEqual("error", result.steps[1].result)
        self.assertIn("Cleanup error", result.steps[1].actual)

    def test_precondition_failure_is_blocked_not_fail(self) -> None:
        """Precondition step failure (StepFailure) -> blocked, not fail."""
        sc = self._make_scenario(
            preconditions=[{"id": "bad_pc", "action": "launch_main", "expected": "will block"}],
        )
        runner = self._fake_runner()
        result = runner.run_scenario(sc)

        self.assertEqual("blocked", result.functional_result)
        self.assertIsNotNone(result.blocked_reason)
        # Main steps never ran because precondition blocked
        self.assertEqual(1, len(result.steps))
        self.assertEqual("precondition", result.steps[0].phase)

    def test_cleanup_trace_recorded_distinctly(self) -> None:
        """Cleanup steps appear in the step list with phase='cleanup'."""
        sc = self._make_scenario(
            cleanup=[
                {"id": "cl1", "action": "press_back", "expected": "first cleanup"},
                {"id": "cl2", "action": "press_home", "expected": "second cleanup"},
            ],
        )
        runner = self._fake_runner()
        result = runner.run_scenario(sc)

        self.assertEqual(3, len(result.steps))
        self.assertEqual("main", result.steps[0].phase)
        self.assertEqual("cleanup", result.steps[1].phase)
        self.assertEqual("cl1", result.steps[1].id)
        self.assertEqual("cleanup", result.steps[2].phase)
        self.assertEqual("cl2", result.steps[2].id)


class _FakeAdb:
    """Fake ADB client that tracks commands and returns empty responses."""

    def __init__(self, responses: dict[str, str] | None = None) -> None:
        self.responses = responses or {}
        self.commands: list[str] = []

    def shell(self, command: str, timeout: float = 30.0, check: bool = True) -> str:
        self.commands.append(command)
        return self.responses.get(command, "")

    def run(self, *args: str, timeout: float = 30.0, check: bool = True, text: bool = True) -> object:
        # LogcatCapture.stop() may call run(); no error for tests
        self.commands.append(" ".join(args))

        class _FakeResult:
            stdout = ""
            stderr = ""
            returncode = 0

        return _FakeResult()


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



WEATHER_SCENARIO_IDS = frozenset({
    "weather_location_denied",
    "weather_location_granted",
    "weather_location_prompt_denied",
    "weather_location_blocked_or_permanently_denied",
    "weather_typed_city_without_location",
})


class WeatherScenarioTest(unittest.TestCase):
    """Tests for weather/location permission scenario definitions."""

    def test_all_weather_scenarios_have_required_tags(self) -> None:
        for scenario in permission_runner.SCENARIOS:
            if scenario["id"] in WEATHER_SCENARIO_IDS:
                tags = set(scenario.get("tags", []))
                self.assertIn("permissions", tags, f"{scenario['id']} missing 'permissions' tag")
                self.assertIn("location", tags, f"{scenario['id']} missing 'location' tag")
                self.assertIn("weather", tags, f"{scenario['id']} missing 'weather' tag")

    def test_weather_scenarios_have_weather_capability(self) -> None:
        for scenario in permission_runner.SCENARIOS:
            if scenario["id"] in WEATHER_SCENARIO_IDS:
                self.assertEqual(
                    "weather_current_location",
                    scenario.get("capability"),
                    f"{scenario['id']} should have capability weather_current_location",
                )

    def test_weather_scenarios_have_cleanup_for_location_permissions(self) -> None:
        """Weather scenarios that change location permission must restore it."""
        for scenario in permission_runner.SCENARIOS:
            if scenario["id"] in WEATHER_SCENARIO_IDS:
                cleanup = scenario.get("cleanup", [])
                self.assertTrue(
                    any("ACCESS_COARSE_LOCATION" in str(step) for step in cleanup),
                    f"{scenario['id']} cleanup should reference ACCESS_COARSE_LOCATION",
                )

    def test_weather_location_granted_has_all_expected_fields(self) -> None:
        sc = permission_runner.get_scenario_by_id("weather_location_granted")
        self.assertIsNotNone(sc)
        self.assertIn("preconditions", sc)
        self.assertIn("cleanup", sc)
        self.assertIn("fixtures", sc)
        self.assertEqual(2, len(sc["steps"]))
        # First step should grant location
        self.assertEqual("set_permission_state", sc["steps"][0]["action"])
        self.assertEqual("granted", sc["steps"][0]["state"])
        # Second step should launch weather
        self.assertEqual("launch_quick_action", sc["steps"][1]["action"])
        self.assertIn("expected_not_visible", sc["steps"][1])

    def test_weather_location_blocked_asserts_permission_dialog_appears(self) -> None:
        sc = permission_runner.get_scenario_by_id("weather_location_blocked_or_permanently_denied")
        self.assertIsNotNone(sc)
        for step in sc["steps"]:
            if step["action"] == "launch_quick_action":
                any_vis = step.get("expected_any_visible", [])
                # Must check for at least the blocked copy OR the standard dialog
                self.assertTrue(
                    any(text in " ".join(any_vis) for text in [
                        "Location permission is blocked",
                        "Use your location for local weather?",
                    ]),
                    "Blocked scenario should assert either blocked repair copy or standard permission dialog appears",
                )
                # Should NOT assert success copy
                self.assertNotIn("weather data", " ".join(any_vis).lower(),
                    "Blocked scenario should assert dialog copy, not success copy")

    def test_weather_typed_city_uses_fixture_value(self) -> None:
        sc = permission_runner.get_scenario_by_id("weather_typed_city_without_location")
        self.assertIsNotNone(sc)
        # The scenario definition should reference FIXTURES dict at definition time, not hard-code a city name
        with open(permission_runner.HERE / "permission_scenario_defs.py") as f:
            defs_source = f.read()
        self.assertIn('weather_named_location', defs_source,
            "weather_typed_city_without_location query should reference FIXTURES['weather_named_location']")
        # Verify the rendered query at import time resolves correctly
        query = str(sc["steps"][1].get("query", ""))
        self.assertIn("Tokyo", query,
            f"Rendered query should contain fixture value 'Tokyo', got: {query}")

    def test_weather_blocked_cleanup_restores_prompt_state(self) -> None:
        """blocked scenario cleanup should set location back to prompt."""
        sc = permission_runner.get_scenario_by_id("weather_location_blocked_or_permanently_denied")
        cleanup = sc.get("cleanup", [])
        self.assertTrue(
            any("prompt" in str(step) for step in cleanup),
            "blocked scenario cleanup should restore to prompt state",
        )


class WeatherDryRunTest(unittest.TestCase):
    """Tests for dry-run output covering new weather scenarios."""

    def test_all_weather_scenarios_appear_in_dry_run(self) -> None:
        weather_ids = {"weather_location_denied", "weather_location_granted",
                       "weather_location_prompt_denied", "weather_location_blocked_or_permanently_denied",
                       "weather_typed_city_without_location"}
        scenarios = [s for s in permission_runner.SCENARIOS if s["id"] in weather_ids]
        plan = permission_runner.build_dry_run_plan(scenarios)
        plan_ids = {entry["id"] for entry in plan}
        self.assertEqual(weather_ids, plan_ids)

    def test_weather_dry_run_shows_location_permissions_touched(self) -> None:
        scenarios = [s for s in permission_runner.SCENARIOS if s["id"] in {
            "weather_location_granted", "weather_location_blocked_or_permanently_denied"}]
        plan = permission_runner.build_dry_run_plan(scenarios)
        all_perms = set()
        for entry in plan:
            all_perms.update(entry["permissions_touched"])
        self.assertIn("android.permission.ACCESS_COARSE_LOCATION", all_perms)
        self.assertIn("android.permission.ACCESS_FINE_LOCATION", all_perms)

    def test_weather_dry_run_includes_cleanup_count(self) -> None:
        scenarios = [s for s in permission_runner.SCENARIOS if s["id"] == "weather_location_granted"]
        plan = permission_runner.build_dry_run_plan(scenarios)
        self.assertEqual(1, len(plan))
        self.assertGreater(plan[0]["cleanup_count"], 0)

    def test_weather_dry_run_shows_screenshots(self) -> None:
        scenarios = [s for s in permission_runner.SCENARIOS if s["id"] == "weather_location_prompt_denied"]
        plan = permission_runner.build_dry_run_plan(scenarios)
        self.assertEqual(1, len(plan))
        self.assertGreater(plan[0]["screenshot_count"], 0)



CLOCK_SCENARIO_IDS = frozenset({
    "clock_timer_notifications_allowed",
    "clock_timer_notifications_denied",
    "clock_alarm_exact_alarm_allowed",
    "clock_alarm_schedule_exact_alarm_appop_denied",
})


DASHBOARD_SCENARIO_IDS = frozenset({
    "permissions_dashboard_opens",
    "permissions_dashboard_location_state_refresh",
    "permissions_dashboard_microphone_state_refresh",
    "permissions_dashboard_notification_state",
    "permissions_dashboard_repair_cta_opens_settings",
})

SPECIAL_ACCESS_SCENARIO_IDS = frozenset({
    "special_access_dashboard_state",
    "special_access_write_settings_repair_opens_settings",
    "special_access_dnd_repair_opens_settings",
})


class DashboardScenarioTest(unittest.TestCase):
    """Tests for App Permissions dashboard scenario definitions."""

    def test_all_dashboard_scenarios_validate(self) -> None:
        for sid in DASHBOARD_SCENARIO_IDS:
            sc = permission_runner.get_scenario_by_id(sid)
            errors = permission_runner.validate_scenario_definitions([sc])
            self.assertEqual([], errors, f"{sid} should pass validation:\n" + "\n".join(errors))

    def test_all_dashboard_scenarios_have_dashboard_tag(self) -> None:
        for sid in DASHBOARD_SCENARIO_IDS:
            sc = permission_runner.get_scenario_by_id(sid)
            tags = set(sc.get("tags", []))
            self.assertIn("dashboard", tags, f"{sid} missing 'dashboard' tag")

    def test_all_dashboard_scenarios_have_permissions_tag(self) -> None:
        for sid in DASHBOARD_SCENARIO_IDS:
            sc = permission_runner.get_scenario_by_id(sid)
            tags = set(sc.get("tags", []))
            self.assertIn("permissions", tags, f"{sid} missing 'permissions' tag")

    def test_state_refresh_scenarios_have_refresh_tag(self) -> None:
        for sid in ("permissions_dashboard_location_state_refresh", "permissions_dashboard_microphone_state_refresh"):
            sc = permission_runner.get_scenario_by_id(sid)
            tags = set(sc.get("tags", []))
            self.assertIn("refresh", tags, f"{sid} missing 'refresh' tag")

    def test_repair_cta_scenario_has_repair_tag(self) -> None:
        sc = permission_runner.get_scenario_by_id("permissions_dashboard_repair_cta_opens_settings")
        tags = set(sc.get("tags", []))
        self.assertIn("repair", tags, f"permissions_dashboard_repair_cta_opens_settings missing 'repair' tag")

    def test_scenarios_that_change_permissions_have_cleanup(self) -> None:
        for sid in DASHBOARD_SCENARIO_IDS - {"permissions_dashboard_opens"}:
            sc = permission_runner.get_scenario_by_id(sid)
            cleanup = sc.get("cleanup", [])
            self.assertTrue(len(cleanup) > 0, f"{sid} should have cleanup steps")

    def test_dashboard_opens_has_no_cleanup(self) -> None:
        sc = permission_runner.get_scenario_by_id("permissions_dashboard_opens")
        cleanup = sc.get("cleanup", [])
        self.assertEqual(0, len(cleanup), "permissions_dashboard_opens should have no cleanup (no permissions changed)")

    def test_repair_cta_scenario_uses_wait_for_package(self) -> None:
        sc = permission_runner.get_scenario_by_id("permissions_dashboard_repair_cta_opens_settings")
        steps = sc.get("steps", [])
        self.assertTrue(
            any(s.get("action") == "wait_for_package" for s in steps),
            "repair CTA scenario should use wait_for_package action",
        )

    def test_dashboard_scenarios_capability_is_empty(self) -> None:
        for sid in DASHBOARD_SCENARIO_IDS:
            sc = permission_runner.get_scenario_by_id(sid)
            self.assertEqual("", sc.get("capability", "MISSING"),
                f"{sid} capability should be empty string (not capability-gated)")


class DashboardDryRunTest(unittest.TestCase):
    """Tests for dry-run output covering dashboard scenarios."""

    def test_all_dashboard_scenarios_appear_in_dry_run(self) -> None:
        scenarios = [s for s in permission_runner.SCENARIOS if s["id"] in DASHBOARD_SCENARIO_IDS]
        plan = permission_runner.build_dry_run_plan(scenarios)
        plan_ids = {entry["id"] for entry in plan}
        self.assertEqual(DASHBOARD_SCENARIO_IDS, plan_ids)

    def test_dashboard_dry_run_shows_refresh_permissions(self) -> None:
        scenarios = [s for s in permission_runner.SCENARIOS if s["id"] in DASHBOARD_SCENARIO_IDS]
        plan = permission_runner.build_dry_run_plan(scenarios)
        all_perms = set()
        for entry in plan:
            all_perms.update(entry["permissions_touched"])
        self.assertIn("android.permission.ACCESS_COARSE_LOCATION", all_perms)
        self.assertIn("android.permission.RECORD_AUDIO", all_perms)
    def test_dashboard_dry_run_shows_screenshots(self) -> None:
        scenarios = [s for s in permission_runner.SCENARIOS if s["id"] in DASHBOARD_SCENARIO_IDS]
        plan = permission_runner.build_dry_run_plan(scenarios)
        total_screenshots = sum(entry["screenshot_count"] for entry in plan)
        self.assertGreater(total_screenshots, 0, "Dashboard scenarios should capture screenshots")

    def test_dashboard_dry_run_shows_cleanup_steps(self) -> None:
        scenarios = [s for s in permission_runner.SCENARIOS if s["id"] in {"permissions_dashboard_location_state_refresh", "permissions_dashboard_microphone_state_refresh", "permissions_dashboard_notification_state", "permissions_dashboard_repair_cta_opens_settings"}]
        plan = permission_runner.build_dry_run_plan(scenarios)
        total_cleanup = sum(entry["cleanup_count"] for entry in plan)
        self.assertGreater(total_cleanup, 0, "Dashboard scenarios that change permissions should have cleanup steps")


class SpecialAccessScenarioTest(unittest.TestCase):
    """Tests for special access permission scenario definitions."""

    def test_all_special_access_scenarios_validate(self) -> None:
        for sid in SPECIAL_ACCESS_SCENARIO_IDS:
            sc = permission_runner.get_scenario_by_id(sid)
            errors = permission_runner.validate_scenario_definitions([sc])
            self.assertEqual([], errors, f"{sid} should pass validation:\n" + "\n".join(errors))

    def test_all_special_access_scenarios_have_tags(self) -> None:
        for sid in SPECIAL_ACCESS_SCENARIO_IDS:
            sc = permission_runner.get_scenario_by_id(sid)
            tags = set(sc.get("tags", []))
            self.assertIn("permissions", tags, f"{sid} missing 'permissions' tag")
            self.assertIn("special_access", tags, f"{sid} missing 'special_access' tag")
            self.assertIn("dashboard", tags, f"{sid} missing 'dashboard' tag")

    def test_state_read_scenario_has_no_cleanup(self) -> None:
        sc = permission_runner.get_scenario_by_id("special_access_dashboard_state")
        cleanup = sc.get("cleanup", [])
        self.assertEqual(0, len(cleanup), "special_access_dashboard_state should have no cleanup (read-only)")

    def test_write_settings_repair_has_cleanup(self) -> None:
        sc = permission_runner.get_scenario_by_id("special_access_write_settings_repair_opens_settings")
        cleanup = sc.get("cleanup", [])
        self.assertTrue(len(cleanup) > 0, "write_settings repair should have cleanup to restore appops state")

    def test_dnd_repair_has_no_cleanup(self) -> None:
        """DND repair is read-only (no appops toggle available); cleanup not needed."""
        sc = permission_runner.get_scenario_by_id("special_access_dnd_repair_opens_settings")
        cleanup = sc.get("cleanup", [])
        self.assertEqual(0, len(cleanup), "DND repair should have no cleanup (no device state changed)")

    def test_repair_scenarios_use_row_specific_assertions(self) -> None:
        """Each repair scenario must assert a row-specific 'not granted' contentDescription."""
        for sid in ("special_access_write_settings_repair_opens_settings", "special_access_dnd_repair_opens_settings"):
            sc = permission_runner.get_scenario_by_id(sid)
            steps = sc.get("steps", [])
            found_row_specific = False
            for step in steps:
                ev = step.get("expected_visible", [])
                if any("not granted" in str(t) for t in ev):
                    found_row_specific = True
                    break
            self.assertTrue(found_row_specific, f"{sid} should use row-specific 'not granted' assertion")

    def test_repair_scenarios_use_blocked_if_visible(self) -> None:
        """Repair scenarios should use blocked_if_visible for the already-granted case."""
        for sid in ("special_access_write_settings_repair_opens_settings", "special_access_dnd_repair_opens_settings"):
            sc = permission_runner.get_scenario_by_id(sid)
            steps = sc.get("steps", [])
            found_block = any(
                step.get("blocked_if_visible") is not None
                for step in steps
            )
            self.assertTrue(found_block, f"{sid} should use blocked_if_visible to handle pre-granted state")

    def test_repair_scenarios_use_wait_for_package(self) -> None:
        for sid in ("special_access_write_settings_repair_opens_settings", "special_access_dnd_repair_opens_settings"):
            sc = permission_runner.get_scenario_by_id(sid)
            steps = sc.get("steps", [])
            self.assertTrue(
                any(s.get("action") == "wait_for_package" for s in steps),
                f"{sid} should use wait_for_package action",
            )

    def test_repair_scenarios_have_repair_tag(self) -> None:
        for sid in ("special_access_write_settings_repair_opens_settings", "special_access_dnd_repair_opens_settings"):
            sc = permission_runner.get_scenario_by_id(sid)
            tags = set(sc.get("tags", []))
            self.assertIn("repair", tags, f"{sid} missing 'repair' tag")

    def test_write_settings_scenario_has_write_settings_tag(self) -> None:
        sc = permission_runner.get_scenario_by_id("special_access_write_settings_repair_opens_settings")
        tags = set(sc.get("tags", []))
        self.assertIn("write_settings", tags,
            f"special_access_write_settings_repair_opens_settings missing 'write_settings' tag")

    def test_dnd_scenario_has_dnd_tag(self) -> None:
        sc = permission_runner.get_scenario_by_id("special_access_dnd_repair_opens_settings")
        tags = set(sc.get("tags", []))
        self.assertIn("dnd", tags,
            f"special_access_dnd_repair_opens_settings missing 'dnd' tag")

    def test_dashboard_state_scenario_has_state_text_assertions(self) -> None:
        """The state verification scenario should not check for specific grant state (read-only)."""
        sc = permission_runner.get_scenario_by_id("special_access_dashboard_state")
        steps = sc.get("steps", [])
        for step in steps:
            ev = step.get("expected_visible", [])
            # State scenario should only check row labels exist, not specific grant state
            for t in ev:
                self.assertNotIn(" granted", str(t),
                    f"special_access_dashboard_state should not assert specific grant state: {t}")
                self.assertNotIn(" not granted", str(t),
                    f"special_access_dashboard_state should not assert specific grant state: {t}")

    def test_all_special_access_scenarios_capability_is_empty(self) -> None:
        for sid in SPECIAL_ACCESS_SCENARIO_IDS:
            sc = permission_runner.get_scenario_by_id(sid)
            self.assertEqual("", sc.get("capability", "MISSING"),
                f"{sid} capability should be empty string (not capability-gated)")


class SpecialAccessDryRunTest(unittest.TestCase):
    """Tests for dry-run output covering special access scenarios."""

    def test_all_special_access_scenarios_appear_in_dry_run(self) -> None:
        scenarios = [s for s in permission_runner.SCENARIOS if s["id"] in SPECIAL_ACCESS_SCENARIO_IDS]
        plan = permission_runner.build_dry_run_plan(scenarios)
        plan_ids = {entry["id"] for entry in plan}
        self.assertEqual(SPECIAL_ACCESS_SCENARIO_IDS, plan_ids)

    def test_special_access_dry_run_shows_appops_permissions(self) -> None:
        scenarios = [s for s in permission_runner.SCENARIOS if s["id"] in SPECIAL_ACCESS_SCENARIO_IDS]
        plan = permission_runner.build_dry_run_plan(scenarios)
        all_perms = set()
        for entry in plan:
            all_perms.update(entry["permissions_touched"])
        self.assertIn("WRITE_SETTINGS", all_perms,
            "Write-settings repair uses set_appops; should appear in dry-run permissions")
        # DND repair is read-only — does not use set_appops, so no permissions touched is expected

    def test_special_access_dry_run_shows_screenshots(self) -> None:
        scenarios = [s for s in permission_runner.SCENARIOS if s["id"] in SPECIAL_ACCESS_SCENARIO_IDS]
        plan = permission_runner.build_dry_run_plan(scenarios)
        total_screenshots = sum(entry["screenshot_count"] for entry in plan)
        self.assertGreater(total_screenshots, 0, "Special access scenarios should capture screenshots")
    def test_special_access_dry_run_shows_cleanup_steps(self) -> None:
        scenarios = [s for s in permission_runner.SCENARIOS if s["id"] in {"special_access_write_settings_repair_opens_settings"}]
        plan = permission_runner.build_dry_run_plan(scenarios)
        total_cleanup = sum(entry["cleanup_count"] for entry in plan)
        self.assertGreater(total_cleanup, 0, "Write-settings repair changes appops state; should have cleanup steps")


class ClockScenarioTest(unittest.TestCase):
    """Tests for clock/timer/alarm permission scenario definitions."""

    def test_all_clock_scenarios_validate(self) -> None:
        for scenario in permission_runner.SCENARIOS:
            if scenario["id"] in CLOCK_SCENARIO_IDS:
                errors = permission_runner.validate_scenario_definitions([scenario])
                self.assertEqual([], errors, f"{scenario['id']} should pass validation:\n" + "\n".join(errors))

    def test_all_clock_scenarios_have_required_tags(self) -> None:
        for scenario in permission_runner.SCENARIOS:
            if scenario["id"] in CLOCK_SCENARIO_IDS:
                tags = set(scenario.get("tags", []))
                self.assertIn("permissions", tags, f"{scenario['id']} missing 'permissions' tag")
                self.assertIn("clock", tags, f"{scenario['id']} missing 'clock' tag")

    def test_clock_scenarios_have_jandal_alarms_timers_capability(self) -> None:
        for scenario in permission_runner.SCENARIOS:
            if scenario["id"] in CLOCK_SCENARIO_IDS:
                self.assertEqual(
                    "jandal_alarms_timers",
                    scenario.get("capability"),
                    f"{scenario['id']} should have capability 'jandal_alarms_timers'",
                )

    def test_clock_scenarios_reference_short_timer_fixture(self) -> None:
        sc = permission_runner.get_scenario_by_id("clock_timer_notifications_allowed")
        self.assertIsNotNone(sc)
        # The scenario definition should reference FIXTURES dict at definition time, not hard-code a value
        with open(permission_runner.HERE / "permission_scenario_defs.py") as f:
            defs_source = f.read()
        self.assertIn('short_timer_seconds', defs_source,
            "clock_timer_notifications_allowed query should reference FIXTURES['short_timer_seconds']")
        # Verify the rendered query at import time resolves correctly
        query = str(sc["steps"][1].get("query", ""))
        self.assertIn("10", query,
            f"Rendered query should contain fixture value '10' (short_timer_seconds), got: {query}")

    def test_clock_timer_denied_accepts_notification_blocked_messages(self) -> None:
        """denied scenario must accept notification-blocked message, not just success."""
        sc = permission_runner.get_scenario_by_id("clock_timer_notifications_denied")
        self.assertIsNotNone(sc)
        for step in sc["steps"]:
            if step["action"] == "launch_quick_action":
                any_vis = step.get("expected_any_visible_contains", [])
                combined = " ".join(any_vis).lower()
                self.assertIn("notification", combined,
                    f"denied scenario should accept notification-blocked messages, got: {any_vis}")

    def test_appop_denied_accepts_either_outcome(self) -> None:
        """Appop denied scenario must accept either success or blocked copy (app may succeed via USE_EXACT_ALARM)."""
        sc = permission_runner.get_scenario_by_id("clock_alarm_schedule_exact_alarm_appop_denied")
        self.assertIsNotNone(sc)
        for step in sc["steps"]:
            if step["action"] == "launch_quick_action":
                any_vis = step.get("expected_any_visible_contains", [])
                combined = " ".join(any_vis).lower()
                # Must accept either success ("Alarm set for") or blocked/error copy
                self.assertTrue(
                    "alarm set for" in combined or "exact alarm" in combined or "error" in combined,
                    f"appop denied scenario should accept success or blocked copy, got: {any_vis}",
                )

    def test_clock_timer_scenarios_have_notifications_tag(self) -> None:
        for sid in ("clock_timer_notifications_allowed", "clock_timer_notifications_denied"):
            sc = permission_runner.get_scenario_by_id(sid)
            tags = set(sc["tags"])
            self.assertIn("notifications", tags, f"{sid} missing 'notifications' tag")
            self.assertIn("timer", tags, f"{sid} missing 'timer' tag")

    def test_clock_alarm_scenarios_have_exact_alarm_tag(self) -> None:
        for sid in ("clock_alarm_exact_alarm_allowed", "clock_alarm_schedule_exact_alarm_appop_denied"):
            sc = permission_runner.get_scenario_by_id(sid)
            tags = set(sc["tags"])
            self.assertIn("notifications", tags, f"{sid} missing 'notifications' tag")
            self.assertIn("exact_alarm", tags, f"{sid} missing 'exact_alarm' tag")
            self.assertIn("alarm", tags, f"{sid} missing 'alarm' tag")

    def test_clock_scenarios_have_notifications_in_cleanup(self) -> None:
        """Clock scenarios that change POST_NOTIFICATIONS must restore it."""
        for sid in CLOCK_SCENARIO_IDS:
            sc = permission_runner.get_scenario_by_id(sid)
            cleanup = sc.get("cleanup", [])
            self.assertTrue(
                any("POST_NOTIFICATIONS" in str(cs) for cs in cleanup),
                f"{sid} cleanup should reference POST_NOTIFICATIONS",
            )
    def test_appop_denied_cleanup_restores_appops(self) -> None:
        sc = permission_runner.get_scenario_by_id("clock_alarm_schedule_exact_alarm_appop_denied")
        cleanup = sc.get("cleanup", [])
        self.assertTrue(
            any("SCHEDULE_EXACT_ALARM" in str(cs) for cs in cleanup),
            "appop denied scenario cleanup should restore SCHEDULE_EXACT_ALARM",
        )


class ClockDryRunTest(unittest.TestCase):
    """Tests for dry-run output covering new clock scenarios."""

    def test_all_clock_scenarios_appear_in_dry_run(self) -> None:
        scenarios = [s for s in permission_runner.SCENARIOS if s["id"] in CLOCK_SCENARIO_IDS]
        plan = permission_runner.build_dry_run_plan(scenarios)
        plan_ids = {entry["id"] for entry in plan}
        self.assertEqual(CLOCK_SCENARIO_IDS, plan_ids)

    def test_clock_dry_run_shows_notifications_permission(self) -> None:
        scenarios = [s for s in permission_runner.SCENARIOS if s["id"] in CLOCK_SCENARIO_IDS]
        plan = permission_runner.build_dry_run_plan(scenarios)
        all_perms = set()
        for entry in plan:
            all_perms.update(entry["permissions_touched"])
        self.assertIn("android.permission.POST_NOTIFICATIONS", all_perms)

    def test_clock_dry_run_includes_cleanup_count(self) -> None:
        scenarios = [s for s in permission_runner.SCENARIOS if s["id"] == "clock_timer_notifications_allowed"]
        plan = permission_runner.build_dry_run_plan(scenarios)
        self.assertEqual(1, len(plan))
        self.assertGreater(plan[0]["cleanup_count"], 0)

    def test_clock_dry_run_shows_screenshots(self) -> None:
        scenarios = [s for s in permission_runner.SCENARIOS if s["id"] == "clock_timer_notifications_denied"]
        plan = permission_runner.build_dry_run_plan(scenarios)
        self.assertEqual(1, len(plan))
        self.assertGreater(plan[0]["screenshot_count"], 0)

if __name__ == "__main__":
    unittest.main()
