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
    def test_missing_store_fails_before_pages_deployment(self) -> None:
        workflow = WORKFLOW.read_text()

        self.assertIn("ref: test-results", workflow)
        self.assertNotIn("continue-on-error: true", workflow)
        self.assertIn("if [ ! -d test-results/results ]; then", workflow)
        self.assertIn("refusing to build an empty dashboard", workflow)
        self.assertIn("exit 1", workflow)
        self.assertNotIn("mkdir -p test-results/results", workflow)


if __name__ == "__main__":
    unittest.main()
