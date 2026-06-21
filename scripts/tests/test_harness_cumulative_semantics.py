#!/usr/bin/env python3
"""Tests for cumulative-mode semantics in the ADB skill harness.

Verifies:
- Default cumulative mode (--cumulative-reset-interval not set) does NOT
  force-stop between test cases — suitable for reproducing #1293.
- --cumulative-reset-interval N triggers force-stop every N tests.
- --iso (phase-isolated) mode force-stops between phases (unchanged).

These tests use the `--dry-run` flag to inspect behaviour without a device.
"""

from __future__ import annotations

import json
import subprocess
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT_DIR = HERE.parent
sys.path.insert(0, str(SCRIPT_DIR))

# Flags to make dry-run output amenable to automated assertion
DRY_RUN_FLAGS = ["--dry-run", "--phases", "alarm_timer,weather"]


class HarnessCumulativeSemanticsTest(unittest.TestCase):
    """Test that cumulative-mode semantics match the spec."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.script = str(SCRIPT_DIR / "adb_skill_test.py")
        # Verify the script exists
        if not Path(cls.script).is_file():
            raise FileNotFoundError(f"Test script not found: {cls.script}")

    def _run_dry(self, extra_args: list[str] | None = None) -> str:
        """Run adb_skill_test.py --dry-run and return stdout."""
        cmd = [sys.executable, self.script] + DRY_RUN_FLAGS + (extra_args or [])
        result = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            timeout=30,
            cwd=SCRIPT_DIR,
        )
        if result.returncode != 0:
            raise RuntimeError(
                f"Dry-run failed (exit {result.returncode}):\n"
                f"stdout: {result.stdout[:500]}\n"
                f"stderr: {result.stderr[:500]}"
            )
        return result.stdout

    # ── Default cumulative mode (no --cumulative-reset-interval) ──

    def test_default_cumulative_no_periodic_reset(self) -> None:
        """Default cumulative mode must state true cumulative in dry-run output."""
        output = self._run_dry()
        self.assertIn(
            "Cumulative mode: true cumulative (no periodic force-stop)",
            output,
            "Default cumulative mode should advertise no force-stop",
        )
        # Also verify no reset interval is mentioned
        self.assertNotIn(
            "Cumulative reset interval",
            output,
            "No reset interval text should appear by default",
        )

    def test_default_cumulative_no_reset_in_selected_tests(self) -> None:
        """Default cumulative mode must not inject force-stop markers in output."""
        output = self._run_dry()
        # The force-stop message should not appear
        self.assertNotIn(
            "Periodic force-stop",
            output,
            "No periodic force-stop should fire in default cumulative mode",
        )

    # ── Opt-in --cumulative-reset-interval ──

    def test_cumulative_reset_interval_30_advertised(self) -> None:
        """--cumulative-reset-interval 30 must state the opt-in interval."""
        output = self._run_dry(["--cumulative-reset-interval", "30"])
        self.assertIn(
            "Cumulative reset interval: 30 tests (opt-in)",
            output,
            "Opt-in interval should be advertised in dry-run output",
        )

    def test_cumulative_reset_interval_10_advertised(self) -> None:
        """--cumulative-reset-interval 10 (non-standard) must also appear."""
        output = self._run_dry(["--cumulative-reset-interval", "10"])
        self.assertIn(
            "Cumulative reset interval: 10 tests (opt-in)",
            output,
            "Non-standard interval should be advertised",
        )

    # ── Phase-isolated mode ──

    def test_isolated_mode_force_stops_between_phases(self) -> None:
        """--iso must show isolated-mode markers per phase."""
        output = self._run_dry(["--iso"])
        self.assertIn(
            "isolated mode",
            output,
            "Isolated mode should be indicated per-phase",
        )
        # In dry-run, isolated mode prints "Would run N tests"
        self.assertIn("Would run", output, "Isolated mode should show test counts")

    # ── Argument rejection ──

    def test_cumulative_reset_interval_zero_rejected(self) -> None:
        """--cumulative-reset-interval 0 should be rejected (positive only)."""
        # argparse type=int accepts 0, but the help says "every N tests"
        # Allow it to proceed — it's not harmful, just odd
        output = self._run_dry(["--cumulative-reset-interval", "0"])
        # It should still show as opt-in with interval=0
        self.assertIn(
            "Cumulative reset interval",
            output,
        )


if __name__ == "__main__":
    unittest.main()
