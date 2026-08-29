#!/usr/bin/env python3
"""Focused coverage for golden-journey release readiness."""

from __future__ import annotations

import copy
import json
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT_DIR = HERE.parent
sys.path.insert(0, str(SCRIPT_DIR))

from build_test_dashboard import (  # noqa: E402
    _build_aggregates,
    _build_json_data,
    _render_devices,
    _render_overview,
    _render_release_readiness,
)
from publish_test_evidence import (  # noqa: E402
    _build_output_paths,
    _parse_args,
    _validate_evidence_file,
)


FIXTURE = SCRIPT_DIR / "testdata" / "fixtures" / "golden_journeys" / "current-s21.json"


class GoldenJourneyDashboardTests(unittest.TestCase):
    def setUp(self) -> None:
        self.record = json.loads(FIXTURE.read_text())

    def test_release_readiness_keeps_semantic_statuses_separate(self) -> None:
        aggregates = _build_aggregates([self.record])

        self.assertIsNone(aggregates["latest_by_source"]["on_device"])
        golden = aggregates["golden_journeys"]
        self.assertEqual(golden["status_counts"], {
            "proven": 0,
            "partial": 5,
            "blocked": 0,
            "manual_remaining": 5,
        })
        self.assertEqual(golden["devices"][0]["status"], "partial")
        self.assertEqual(aggregates["devices"][0]["total"], 0)

        overview = _render_overview(aggregates, "https://example.test/results")
        self.assertLess(overview.index("Release readiness"), overview.index("Latest Generic Results"))
        self.assertIn("Additional validation required", overview)
        self.assertIn("Manual checks (5)", overview)
        self.assertIn("Historical aggregate", overview)
        self.assertNotIn("class=\"badge fail\"", overview)

    def test_device_page_labels_registry_identity_and_current_golden_status(self) -> None:
        aggregates = _build_aggregates([self.record])
        page = _render_devices(aggregates)

        self.assertIn("S21 <span class=\"device-id\"><code>s21-exynos</code>", page)
        self.assertIn("Current golden-journey evidence", page)
        self.assertIn("Partial evidence", page)
        self.assertIn("Historical pass rate", page)

    def test_golden_export_is_additive(self) -> None:
        aggregates = _build_aggregates([self.record])
        json_data = _build_json_data(aggregates)

        self.assertIn("golden_journeys.json", json_data)
        exported = json_data["golden_journeys.json"]
        self.assertEqual(exported["commit"], self.record["commit"])
        self.assertEqual(len(exported["devices"]), 1)

    def test_publisher_uses_run_id_to_avoid_single_file_collisions(self) -> None:
        args = _parse_args([
            "--input", str(FIXTURE), "--source", "on_device",
            "--release", "v0.1.0", "--commit", self.record["commit"],
        ])
        output = _build_output_paths([FIXTURE], args, self.record)

        self.assertEqual(
            output[FIXTURE],
            "results/release/v0.1.0/on_device/__golden-journeys-8fba674c-s21.json",
        )

    def test_publisher_accepts_complete_golden_record(self) -> None:
        args = _parse_args([
            "--input", str(FIXTURE), "--source", "on_device",
            "--release", "v0.1.0", "--commit", self.record["commit"],
        ])

        validated = _validate_evidence_file(FIXTURE, args)
        self.assertEqual(validated["suite"], "golden_journeys")
        self.assertEqual(len(validated["cases"]), 10)

    def test_multiple_declared_devices_have_independent_status(self) -> None:
        record = copy.deepcopy(self.record)
        record["golden_journeys"]["devices"].append({
            "id": "s23-ultra", "evidence_type": "human_observation", "required": True,
        })
        aggregates = _build_aggregates([record])
        devices = {device["id"]: device for device in aggregates["golden_journeys"]["devices"]}

        self.assertEqual(devices["s21-exynos"]["status"], "partial")
        self.assertEqual(devices["s23-ultra"]["status"], "not_tested")
        self.assertIn("S23 Ultra", _render_release_readiness(aggregates))


if __name__ == "__main__":
    unittest.main()
