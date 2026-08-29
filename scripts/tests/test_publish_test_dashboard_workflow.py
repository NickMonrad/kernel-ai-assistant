#!/usr/bin/env python3
"""Regression tests for the test-results dashboard workflow contract."""

from __future__ import annotations

import unittest
from pathlib import Path


WORKFLOW = (
    Path(__file__).resolve().parents[2]
    / ".github"
    / "workflows"
    / "publish-test-dashboard.yml"
)


class DashboardWorkflowContractTest(unittest.TestCase):
    def test_unavailable_store_deploys_error_site_then_fails(self) -> None:
        workflow = WORKFLOW.read_text()

        self.assertIn("ref: test-results", workflow)
        self.assertIn("id: checkout_test_results", workflow)
        self.assertIn("continue-on-error: true", workflow)
        self.assertIn("id: evidence_store", workflow)
        self.assertIn("state=unavailable", workflow)
        self.assertIn("--evidence-store-state", workflow)
        self.assertIn("Evidence unavailable", workflow)
        self.assertIn("Fail visibly when evidence is unavailable", workflow)
        self.assertIn("exit 1", workflow)
        self.assertNotIn("mkdir -p test-results/results", workflow)
        self.assertLess(
            workflow.index("Deploy to Pages"),
            workflow.index("Fail visibly when evidence is unavailable"),
        )


if __name__ == "__main__":
    unittest.main()
