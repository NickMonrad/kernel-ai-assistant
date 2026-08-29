#!/usr/bin/env python3
"""Focused coverage for golden-journey release readiness."""

from __future__ import annotations

import copy
import json
import sys
import tempfile
import unittest
from pathlib import Path

from jsonschema import Draft7Validator

HERE = Path(__file__).resolve().parent
SCRIPT_DIR = HERE.parent
sys.path.insert(0, str(SCRIPT_DIR))

from build_test_dashboard import (  # noqa: E402
    _build_aggregates,
    _build_json_data,
    _render_devices,
    _render_overview,
    _render_release_readiness,
    _render_unavailable_page,
    _write_unavailable_site,
)
from publish_test_evidence import (  # noqa: E402
    _build_output_paths,
    _parse_args,
    _validate_evidence_file,
)


FIXTURE = SCRIPT_DIR / "testdata" / "fixtures" / "golden_journeys" / "example.json"
EXPECTED_JOURNEY_NAMES = {
    1: "Fresh install to first useful action",
    2: "Upgrade over an existing install",
    3: "Local chat and generation lifecycle",
    4: "Memory",
    5: "Lists and notes",
    6: "Alarm and timer reliability",
    7: "Weather and location fallback",
    8: "Push-to-talk and spoken response",
    9: "Permission revocation and repair",
    10: "Hey Jandal lifecycle",
}

class GoldenJourneyDashboardTests(unittest.TestCase):
    def setUp(self) -> None:
        self.record = json.loads(FIXTURE.read_text())

    def test_synthetic_fixture_matches_canonical_schema(self) -> None:
        schema_path = SCRIPT_DIR / "testdata" / "test_evidence.schema.json"
        schema = json.loads(schema_path.read_text())
        errors = list(Draft7Validator(schema).iter_errors(self.record))
        self.assertFalse(
            errors,
            "\n".join(error.message for error in errors),
        )

    def test_release_readiness_keeps_semantic_statuses_separate(self) -> None:
        aggregates = _build_aggregates([self.record])

        self.assertIsNone(aggregates["latest_by_source"]["on_device"])
        golden = aggregates["golden_journeys"]
        self.assertEqual(golden["status_counts"], {
            "proven": 2,
            "partial": 3,
            "blocked": 1,
            "manual_remaining": 4,
        })
        self.assertEqual(golden["devices"][0]["status"], "blocked")
        self.assertEqual(aggregates["devices"][0]["total"], 0)

        overview = _render_overview(aggregates, "https://example.test/results")
        self.assertLess(overview.index("Release readiness"), overview.index("Latest Generic Results"))
        self.assertIn("Additional validation required", overview)
        self.assertIn("Manual checks (1)", overview)
        self.assertIn("Historical aggregate", overview)
        self.assertNotIn("class=\"badge fail\"", overview)

    def test_fixture_preserves_authoritative_journey_names(self) -> None:
        names = {case["journey"]: case["name"] for case in self.record["cases"]}
        self.assertEqual(names, EXPECTED_JOURNEY_NAMES)
        self.assertEqual(self.record["commit"], "0" * 40)
        self.assertTrue(self.record["run_id"].startswith("synthetic-"))

    def test_unavailable_store_is_distinct_from_empty_store(self) -> None:
        empty = _render_release_readiness({})
        self.assertIn("No release-candidate golden-journey evidence published yet", empty)

        unavailable = _render_release_readiness({"evidence_store_state": "unavailable"})
        self.assertIn("Evidence unavailable", unavailable)
        self.assertIn("Release readiness cannot be determined", unavailable)
        self.assertNotIn("No release-candidate golden-journey evidence published yet", unavailable)

        page = _render_unavailable_page("Checkout failed")
        self.assertIn("Evidence unavailable", page)
        self.assertIn("Checkout failed", page)
        self.assertIn("Release readiness cannot be determined", page)

        with tempfile.TemporaryDirectory() as directory:
            _write_unavailable_site(Path(directory), "Checkout failed")
            self.assertIn("Evidence unavailable", (Path(directory) / "index.html").read_text())
            self.assertEqual(
                json.loads((Path(directory) / "data" / "evidence-store.json").read_text())["state"],
                "unavailable",
            )

    def test_device_page_labels_registry_identity_and_current_golden_status(self) -> None:
        aggregates = _build_aggregates([self.record])
        page = _render_devices(aggregates)

        self.assertIn("S21 <span class=\"device-id\"><code>s21-exynos</code>", page)
        self.assertIn("Current golden-journey evidence", page)
        self.assertIn("Blocked", page)
        self.assertIn("Historical pass rate", page)

    def test_golden_export_is_additive(self) -> None:
        aggregates = _build_aggregates([self.record])
        json_data = _build_json_data(aggregates)

        self.assertIn("golden_journeys.json", json_data)
        exported = json_data["golden_journeys.json"]
        self.assertEqual(exported["commit"], self.record["commit"])
        self.assertEqual(len(exported["devices"]), 2)

    def test_publisher_uses_run_id_to_avoid_single_file_collisions(self) -> None:
        args = _parse_args([
            "--input", str(FIXTURE), "--source", "on_device",
            "--release", self.record["release"], "--commit", self.record["commit"],
        ])
        output = _build_output_paths([FIXTURE], args, self.record)

        self.assertEqual(
            output[FIXTURE],
            "results/release/synthetic-0.0.0/on_device/__synthetic-golden-journey-example.json",
        )

    def test_publisher_accepts_complete_golden_record(self) -> None:
        args = _parse_args([
            "--input", str(FIXTURE), "--source", "on_device",
            "--release", self.record["release"], "--commit", self.record["commit"],
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

        self.assertEqual(devices["s21-exynos"]["status"], "blocked")
        self.assertEqual(devices["s23-ultra"]["status"], "not_tested")
        self.assertIn("S23 Ultra", _render_release_readiness(aggregates))


if __name__ == "__main__":
    unittest.main()
