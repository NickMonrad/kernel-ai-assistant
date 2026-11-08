#!/usr/bin/env python3
"""Tests for generate_permission_flow_evidence.py."""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT_DIR = HERE.parent
sys.path.insert(0, str(SCRIPT_DIR))

import generate_permission_flow_evidence as permission_evidence


class PermissionFlowEvidenceTest(unittest.TestCase):
    def test_parse_junit_xml_pass_fail_and_skip(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            xml = Path(tmp) / "TEST-permission.xml"
            xml.write_text(
                """
<testsuite tests="3">
  <testcase classname="PermissionFlowContextualSmokeTest" name="handsFreeCalling_revokedShowsContextualSurface" time="1.25" />
  <testcase classname="PermissionFlowContextualSmokeTest" name="handsFreeCalling_permanentDenialNavigatesToAppPermissions" time="0.50">
    <failure message="Expected text not visible: Open App Permissions" />
  </testcase>
  <testcase classname="PermissionFlowContextualSmokeTest" name="dndSpecialAccess_settingsRoundTripShowsBlockedRepair" time="0.10">
    <skipped message="S21 already has DND policy access" />
  </testcase>
</testsuite>
""".strip(),
            )

            results = permission_evidence.parse_test_results([xml])

        self.assertTrue(results["handsFreeCalling_revokedShowsContextualSurface"]["passed"])
        self.assertFalse(results["handsFreeCalling_permanentDenialNavigatesToAppPermissions"]["passed"])
        self.assertEqual(
            results["dndSpecialAccess_settingsRoundTripShowsBlockedRepair"]["failure_category"],
            "device_environment_error",
        )
        self.assertIn(
            "skipped:",
            results["dndSpecialAccess_settingsRoundTripShowsBlockedRepair"]["failures"][0],
        )

    def test_schema_evidence_strips_local_metadata(self) -> None:
        case_def = permission_evidence.PERMISSION_FLOW_CASES[0]
        case = permission_evidence.build_case(case_def, {"passed": True, "failures": [], "time": 0.2})
        normalised = {
            "schema_version": "1.0",
            "source": "on_device",
            "suite": "permission_flows",
            "timestamp": "2026-06-20T00:00:00Z",
            "repo": "NickMonrad/kernel-ai-assistant",
            "branch": "feature/1157-permission-flow-harness",
            "commit": "a" * 40,
            "pr": 1157,
            "release": None,
            "run_id": "on_device-2026-06-20T00:00:00Z-s21-exynos",
            "device": {"id": "s21-exynos", "execution": "physical"},
            "model": {"name": "not_applicable", "runtime": "not_applicable", "backend": "not_applicable"},
            "summary": {"total": 1, "passed": 1, "failed": 0, "pass_rate": 1.0},
            "cases": [case],
        }

        schema = permission_evidence.to_schema_evidence(normalised)
        schema_case = schema["cases"][0]

        self.assertNotIn("category", schema_case)
        self.assertNotIn("screen", schema_case)
        self.assertNotIn("assertion", schema_case)
        self.assertNotIn("duration_seconds", schema_case)
        self.assertIsNone(schema_case["failure_category"])

    def test_schema_validates_permission_flow_evidence(self) -> None:
        """Generated on_device evidence must validate against the schema."""
        try:
            import jsonschema  # noqa: F401
        except ImportError:
            self.skipTest("jsonschema not installed")

        case_def = permission_evidence.PERMISSION_FLOW_CASES[0]
        case = permission_evidence.build_case(case_def, {"passed": True, "failures": [], "time": 0.2})
        normalised = {
            "schema_version": "1.0",
            "source": "on_device",
            "suite": "permission_flows",
            "timestamp": "2026-06-20T00:00:00Z",
            "repo": "NickMonrad/kernel-ai-assistant",
            "branch": "feature/1157-permission-flow-harness",
            "commit": "a" * 40,
            "pr": 1157,
            "release": None,
            "run_id": "on_device-2026-06-20T00:00:00Z-s21-exynos",
            "device": permission_evidence.resolve_device("s21-exynos"),
            "model": {"name": "not_applicable", "runtime": "not_applicable", "backend": "not_applicable"},
            "summary": {"total": 1, "passed": 1, "failed": 0, "pass_rate": 1.0},
            "cases": [case],
            "artifact_refs": [],
        }

        errors = permission_evidence.validate_against_schema(normalised)
        self.assertEqual([], errors, f"Schema validation failed: {errors}")


if __name__ == "__main__":
    unittest.main()
