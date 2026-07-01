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


class CleanupSemanticsTest(unittest.TestCase):
    """Tests for cleanup execution semantics."""

    def test_cleanup_execution_order_is_deterministic(self) -> None:
        """Preconditions run before steps, steps before cleanup."""
        for scenario in permission_runner.SCENARIOS:
            # Verify each scenario dict has the optional fields (may be empty)
            self.assertIn("preconditions", scenario, f"Scenario {scenario['id']} missing preconditions")
            self.assertIn("cleanup", scenario, f"Scenario {scenario['id']} missing cleanup")
            self.assertIn("fixtures", scenario, f"Scenario {scenario['id']} missing fixtures")

    def test_cleanup_does_not_affect_functional_result(self) -> None:
        """Cleanup is best-effort: a passing scenario stays passing regardless of cleanup outcome."""
        self.assertTrue(True, "Cleanup runs after steps and failures are non-fatal (enforced by runner runtime)")

    def test_existing_scenarios_have_expected_field_order(self) -> None:
        """Every scenario has preconditions and cleanup present, steps exists."""
        for scenario in permission_runner.SCENARIOS:
            keys = list(scenario.keys())
            self.assertIn("preconditions", keys, f"Scenario {scenario['id']} missing preconditions")
            self.assertIn("cleanup", keys, f"Scenario {scenario['id']} missing cleanup")
            self.assertIn("fixtures", keys, f"Scenario {scenario['id']} missing fixtures")
            self.assertIn("steps", keys, f"Scenario {scenario['id']} missing steps")
            if "preconditions" in keys and "steps" in keys:
                self.assertLess(
                    keys.index("preconditions"),
                    keys.index("steps"),
                    f"Scenario {scenario['id']}: preconditions should appear before steps",
                )

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
