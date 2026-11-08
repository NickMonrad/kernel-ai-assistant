#!/usr/bin/env python3
"""Tests for summarise_test_evidence_metrics.py."""

from __future__ import annotations

import copy

import json
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT_DIR = HERE.parent
sys.path.insert(0, str(SCRIPT_DIR))

import summarise_test_evidence_metrics as metrics

WAKE_FIXTURE = (
    SCRIPT_DIR
    / "testdata"
    / "fixtures"
    / "acoustic-wake-reliability"
    / "evidence-normalized-sample.json"
)


def evidence_record(**overrides):
    base = {
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
            "manufacturer": "Samsung",
            "model": "SM-G991B",
            "soc": "Exynos 2100",
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
                "chip_present": True,
                "skill_result_present": True,
                "message_saved": True,
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
                "chip_present": True,
                "skill_result_present": True,
                "message_saved": True,
                "failures": ["expected create_reminder"],
                "artifact_refs": ["screenshots/wrong-tool.png"],
            },
        ],
        "artifact_refs": [],
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
        record = evidence_record(artifact_refs=["logs/run.log"])
        summary = metrics.summarise([(Path("evidence.json"), record, [])])

        artifact_paths = {item["path"] for item in summary["artifacts"]}
        self.assertIn("logs/run.log", artifact_paths)
        self.assertIn("screenshots/wrong-tool.png", artifact_paths)
        self.assertEqual(
            {item["source_path"] for item in summary["artifacts"]},
            {"evidence.json"},
        )

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

    def test_device_alias_resolves_to_canonical_public_device(self) -> None:
        record = evidence_record(
            device={
                "id": "s21",
                "execution": "physical",
            }
        )

        summary = metrics.summarise([(Path("evidence.json"), record, [])])

        self.assertIn("s21-exynos", summary["by_device"])
        self.assertEqual(summary["by_device"]["s21-exynos"]["label"], "S21")
        self.assertEqual(summary["by_device"]["s21-exynos"]["android_api"], 35)

    def test_known_device_metadata_must_match_registry(self) -> None:
        record = evidence_record()
        record["device"]["model"] = "ambiguous-model"

        valid, issues = metrics.validate_record(record, [])

        self.assertFalse(valid)
        self.assertIn("device_registry_mismatch:model", issues)

    def test_unknown_device_id_is_invalid(self) -> None:
        record = evidence_record()
        record["device"]["id"] = "unregistered-phone"

        valid, issues = metrics.validate_record(record, [])

        self.assertFalse(valid)
        self.assertIn("device_unknown_id", issues)

    def test_unsupported_schema_version_is_invalid(self) -> None:
        record = evidence_record(schema_version="2.0")

        valid, issues = metrics.validate_record(record, [])

        self.assertFalse(valid)
        self.assertTrue(
            any(issue.startswith("schema:schema_version:") for issue in issues),
            issues,
        )

    def test_schema_1_1_is_restricted_to_wake_reliability_suite(self) -> None:
        record = evidence_record(schema_version="1.1")

        valid, issues = metrics.validate_record(record, [])

        self.assertFalse(valid)
        self.assertTrue(
            any(issue.startswith("schema:") for issue in issues),
            issues,
        )

    def test_artifact_path_traversal_is_invalid(self) -> None:
        record = evidence_record(artifact_refs=["../private.log"])

        valid, issues = metrics.validate_record(record, [])

        self.assertFalse(valid)
        self.assertTrue(
            any(issue.startswith("schema:artifact_refs.0:") for issue in issues),
            issues,
        )

    def test_artifact_references_remain_optional_for_schema_1_0(self) -> None:
        record = evidence_record()
        record.pop("artifact_refs", None)

        valid, issues = metrics.validate_record(record, [])

        self.assertTrue(valid, issues)

    def test_case_artifact_path_traversal_is_invalid_when_present(self) -> None:
        record = evidence_record()
        record["cases"][0]["artifact_refs"] = ["../private.log"]

        valid, issues = metrics.validate_record(record, [])

        self.assertFalse(valid)
        self.assertTrue(
            any("schema:cases.0:" in issue and "artifact_refs" in issue for issue in issues),
            issues,
        )

    def test_wake_fixture_reports_attempts_gates_and_completion(self) -> None:
        record = json.loads(WAKE_FIXTURE.read_text(encoding="utf-8"))

        summary = metrics.summarise([(WAKE_FIXTURE, record, [])])
        wake = summary["wake_reliability"]

        self.assertEqual(
            wake["overall"],
            {
                "attempts": 3,
                "passed": 1,
                "failed": 1,
                "invalid": 1,
                "valid": 2,
                "pass_rate": 0.5,
            },
        )
        self.assertEqual(wake["by_device"]["s21-exynos"]["attempts"], 3)
        self.assertEqual(wake["by_run_kind"]["regression_post_fix"]["failed"], 1)
        self.assertEqual(wake["by_gate_mode"]["release_gate"]["invalid"], 1)
        self.assertEqual(wake["failure_classifications"], {"stt_readiness_failure": 1})
        self.assertEqual(wake["invalid_reasons"], {"device_environment_error": 1})
        self.assertEqual(
            wake["completion"],
            {
                "total_required": 27,
                "completed": 1,
                "missing": 26,
                "duplicate_valid_positions": 0,
            },
        )
        self.assertEqual(wake["release_gate"]["failed"], 1)
        self.assertFalse(wake["release_gate"]["latest_successful"])
        self.assertEqual(wake["release_gate"]["provenance_unverified"], 0)
        self.assertEqual(wake["release_gate"]["feasibility_only"], 0)
        self.assertEqual(wake["off_matrix_attempts"], 2)

        artifact_paths = {item["path"] for item in summary["artifacts"]}
        self.assertIn("trials/trial-pass/attempt-1/target-events.json", artifact_paths)
        self.assertIn("trials/trial-fail/attempt-1/command-source-result.json", artifact_paths)

    def test_wake_metrics_group_conditions_and_same_clock_event_latency(self) -> None:
        record = json.loads(WAKE_FIXTURE.read_text(encoding="utf-8"))
        record["cases"][0]["target_timing"] = {
            "clock_domain": "target_device_elapsed_realtime",
            "events": [
                {"t": "STAGE3_READY", "m": 1000},
                {"t": "VERIFIED_ACTIVATION", "m": 1125},
                {"t": "WAKE_CALLBACK_INVOKED", "m": 1180},
            ],
        }

        wake = metrics.summarise([(WAKE_FIXTURE, record, [])])["wake_reliability"]
        conditions = {
            (item["idle_seconds"], item["trial_type"]): item
            for item in wake["completion_by_condition"]
            if item["device_id"] == "s21-exynos"
        }
        self.assertEqual(conditions[(10, "wake_only")]["required_positions"], 5)
        self.assertEqual(conditions[(10, "wake_only")]["missing_positions"], 5)
        self.assertEqual(conditions[(10, "wake_plus_command")]["attempted_positions"], 1)
        self.assertEqual(conditions[(10, "wake_plus_command")]["completed_positions"], 1)
        self.assertEqual(conditions[(10, "wake_plus_command")]["failed_attempts"], 1)
        self.assertEqual(conditions[(10, "wake_plus_command")]["retry_attempts"], 0)
        self.assertEqual(wake["off_matrix_attempts"], 2)

        samples = wake["timing"]["samples"]
        self.assertEqual(
            [(sample["metric"], sample["duration_ms"]) for sample in samples],
            [
                ("detector_ready_to_activation_ms", 125),
                ("activation_to_callback_ms", 55),
            ],
        )
        self.assertTrue(all(
            sample["clock_domain"] == "target_device_elapsed_realtime"
            for sample in samples
        ))

    def test_invalid_record_is_excluded_from_reliability_and_release_gate(self) -> None:
        record = json.loads(WAKE_FIXTURE.read_text(encoding="utf-8"))
        record["cases"][0]["artifact_refs"] = ["../private.log"]

        summary = metrics.summarise([(WAKE_FIXTURE, record, [])])

        self.assertEqual(summary["validity"]["invalid_records"], 1)
        self.assertEqual(summary["wake_reliability"]["overall"]["attempts"], 0)
        self.assertEqual(summary["wake_reliability"]["release_gate"]["records"], 0)
        self.assertEqual(summary["wake_reliability"]["completion"]["total_required"], 0)

    def test_matrix_completion_counts_positions_once_and_retries_separately(self) -> None:
        record = json.loads(WAKE_FIXTURE.read_text(encoding="utf-8"))
        retry = copy.deepcopy(record["cases"][1])
        retry.update({"attempt": 2, "name": "trial-fail-retry", "trial_id": "trial-fail-retry"})
        record["cases"].append(retry)
        record["summary"].update({
            "failed": 2,
            "pass_rate": 1 / 3,
            "total": 4,
            "total_attempts": 4,
            "valid": 3,
        })

        wake = metrics.summarise([(WAKE_FIXTURE, record, [])])["wake_reliability"]
        condition = next(
            item
            for item in wake["completion_by_condition"]
            if item["device_id"] == "s21-exynos"
            and item["idle_seconds"] == 10
            and item["trial_type"] == "wake_plus_command"
        )

        self.assertEqual(condition["attempts"], 2)
        self.assertEqual(condition["attempted_positions"], 1)
        # Duplicated position has 2 valid outcomes → not cleanly completed
        self.assertEqual(condition["completed_positions"], 0)
        self.assertEqual(condition["missing_positions"], 3)
        self.assertEqual(condition["retry_attempts"], 1)
        self.assertEqual(condition["duplicate_valid_positions"], 1)
        self.assertFalse(condition["complete"])

    def test_wake_timing_does_not_subtract_different_clock_domains(self) -> None:
        record = json.loads(WAKE_FIXTURE.read_text(encoding="utf-8"))
        record["cases"][0]["target_timing"] = {
            "clock_domain": "source_device_elapsed_realtime",
            "events": [
                {"t": "STAGE3_READY", "m": 1000},
                {"t": "VERIFIED_ACTIVATION", "m": 1100},
            ],
        }
        wake = metrics.summarise([(WAKE_FIXTURE, record, [])])["wake_reliability"]
        self.assertEqual(wake["timing"], {"samples": [], "aggregates": []})

    def test_wake_fixture_satisfies_schema(self) -> None:
        record = json.loads(WAKE_FIXTURE.read_text(encoding="utf-8"))

        valid, issues = metrics.validate_record(record, [])

        self.assertTrue(valid, issues)

    def test_invalid_acoustic_excluded_from_generic_totals(self) -> None:
        """Finding 1: invalid acoustic attempts must not contaminate generic totals."""
        record = json.loads(WAKE_FIXTURE.read_text(encoding="utf-8"))
        summary = metrics.summarise([(WAKE_FIXTURE, record, [])])

        # Generic totals must exclude the invalid attempt
        self.assertEqual(summary["totals"]["total"], 2)
        self.assertEqual(summary["totals"]["passed"], 1)
        self.assertEqual(summary["totals"]["failed"], 1)
        # Suite totals must also exclude the invalid attempt
        self.assertEqual(summary["by_suite"]["wake_word_acoustic_reliability"]["total"], 2)
        self.assertEqual(summary["by_suite"]["wake_word_acoustic_reliability"]["passed"], 1)
        self.assertEqual(summary["by_suite"]["wake_word_acoustic_reliability"]["failed"], 1)
        # Device totals must also exclude the invalid attempt
        self.assertEqual(summary["by_device"]["s21-exynos"]["total"], 2)
        self.assertEqual(summary["by_device"]["s21-exynos"]["passed"], 1)
        self.assertEqual(summary["by_device"]["s21-exynos"]["failed"], 1)
        # Wake-specific metrics preserve the invalid diagnostic
        wake = summary["wake_reliability"]
        self.assertEqual(wake["overall"]["attempts"], 3)
        self.assertEqual(wake["overall"]["valid"], 2)
        self.assertEqual(wake["overall"]["invalid"], 1)
        self.assertEqual(wake["overall"]["pass_rate"], 0.5)
        # Non-acoustic evidence must be unaffected
        normal = metrics.summarise([("ev.json", evidence_record(), [])])
        self.assertEqual(normal["totals"]["total"], 2)
        self.assertEqual(normal["totals"]["passed"], 1)
        self.assertEqual(normal["totals"]["failed"], 1)


if __name__ == "__main__":
    unittest.main()
