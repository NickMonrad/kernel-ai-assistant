#!/usr/bin/env python3
"""Tests for publish_launch_validation_evidence.py.

Covers the ``--dry-run`` flag: verifies it prints the plan and exits before
running the normaliser, copying raw JSON, generating Markdown, or publishing.
"""

from __future__ import annotations

import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT_DIR = HERE.parent
REPO_DIR = SCRIPT_DIR.parent
FIXTURE = SCRIPT_DIR / "testdata" / "fixtures" / "llm_tools_sample_raw.json"
LAUNCH_SCRIPT = SCRIPT_DIR / "publish_launch_validation_evidence.py"


class DryRunFlagTest(unittest.TestCase):
    """Verify that ``--dry-run`` prints the plan and exits without side effects."""

    def setUp(self) -> None:
        self._tmpdir = tempfile.mkdtemp(prefix="dryrun_test_")
        self._tmp = Path(self._tmpdir)

    def tearDown(self) -> None:
        # Clean up temp dir
        import shutil
        shutil.rmtree(self._tmpdir, ignore_errors=True)

    def _run_dry_run(self, extra_args: list[str] | None = None) -> subprocess.CompletedProcess:
        """Run the launch validation script with ``--dry-run`` and return the result."""
        cmd = [
            sys.executable, str(LAUNCH_SCRIPT),
            "--dry-run",
            "--input", str(FIXTURE),
            "--serial", "R5CR605B71K",
            "--out-dir", self._tmpdir,
            "--commit", "abcdef1234567890abcdef1234567890abcdef12",
            "--branch", "test-branch",
            "--pr", "9999",
        ]
        if extra_args:
            cmd.extend(extra_args)
        return subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            cwd=str(REPO_DIR),
        )

    def test_dry_run_returns_zero(self) -> None:
        """``--dry-run`` should exit 0."""
        result = self._run_dry_run()
        self.assertEqual(result.returncode, 0, msg=result.stderr)

    def test_dry_run_prints_plan(self) -> None:
        """``--dry-run`` should print the dry-run plan."""
        result = self._run_dry_run()
        self.assertIn("[DRY RUN]", result.stdout)
        self.assertIn("SKIP: --dry-run flag set", result.stdout)

    def test_dry_run_does_not_create_evidence_files(self) -> None:
        """``--dry-run`` should not create any evidence output files."""
        self._run_dry_run()
        # No evidence JSON
        ev_jsons = list(self._tmp.glob("*_evidence.json"))
        self.assertEqual(ev_jsons, [], f"Unexpected evidence JSON: {ev_jsons}")
        # No CSV
        csvs = list(self._tmp.glob("*_cases.csv"))
        self.assertEqual(csvs, [], f"Unexpected CSV: {csvs}")
        # No summary MD
        summaries = list(self._tmp.glob("*_summary.md"))
        self.assertEqual(summaries, [], f"Unexpected summary: {summaries}")
        # No launch validation summary
        lv_md = self._tmp / "launch-validation-summary.md"
        self.assertFalse(lv_md.exists(), "launch-validation-summary.md should not exist")
        # No raw/ subdirectory
        raw_dir = self._tmp / "raw"
        self.assertFalse(raw_dir.exists(), "raw/ subdirectory should not exist")

    def test_dry_run_does_not_fail_on_unknown_suite(self) -> None:
        """``--dry-run`` should work even with warnings (unknown suite type)."""
        # Create a fixture with an unknown suite
        import json
        weird = self._tmp / "weird_suite.json"
        weird.write_text(json.dumps({"suite": "unknown_suite", "results": [], "status": "passed"}))
        cmd = [
            sys.executable, str(LAUNCH_SCRIPT),
            "--dry-run",
            "--input", str(weird),
            "--serial", "R5CR605B71K",
            "--out-dir", self._tmp,
            "--commit", "abcdef1234567890abcdef1234567890abcdef12",
            "--branch", "test-branch",
            "--pr", "9999",
        ]
        result = subprocess.run(cmd, capture_output=True, text=True, cwd=str(REPO_DIR))
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        self.assertIn("SKIP: --dry-run flag set", result.stdout)


if __name__ == "__main__":
    unittest.main()
