#!/usr/bin/env python3
"""Tests for clock-alert cleanup failure detection (#1274).

Covers:
- run_adb_checked returns (True, output) on success
- run_adb_checked returns (False, error) on non-zero exit
- run_adb_checked returns (False, "timeout") on timeout
- run_adb_checked returns (False, "adb not found") on FileNotFoundError
- cleanup_clock_alerts returns True when all commands succeed
- cleanup_clock_alerts returns False when required commands fail
- EXIT_CLEANUP_FAILED and runner exit handling
"""

from __future__ import annotations

import subprocess
import sys
import unittest
from pathlib import Path
from unittest.mock import MagicMock, PropertyMock, patch

HERE = Path(__file__).resolve().parent
SCRIPT_DIR = HERE.parent
sys.path.insert(0, str(SCRIPT_DIR))

from adb_harness.device import (
    run_adb_checked,
    cleanup_clock_alerts,
    PACKAGE,
)
from adb_harness.model_readiness import EXIT_CLEANUP_FAILED


class RunAdbCheckedTest(unittest.TestCase):
    """run_adb_checked() unit tests."""

    def _mock_subprocess_run(self, returncode: int = 0, stdout: str = "",
                              stderr: str = "") -> MagicMock:
        proc = MagicMock()
        proc.returncode = returncode
        proc.stdout = stdout
        proc.stderr = stderr
        return MagicMock(return_value=proc)

    @patch("adb_harness.device.subprocess.run")
    @patch("adb_harness.device.build_adb_cmd", return_value=["adb", "shell", "echo", "ok"])
    def test_success_returns_true(self, mock_build: MagicMock,
                                  mock_run: MagicMock) -> None:
        """run_adb_checked returns (True, stdout) on exit code 0."""
        mock_run.return_value = MagicMock(
            returncode=0, stdout="hello world\n", stderr=""
        )
        ok, out = run_adb_checked("shell", "echo", "hello world")
        self.assertTrue(ok)
        self.assertEqual(out, "hello world")

    @patch("adb_harness.device.subprocess.run")
    @patch("adb_harness.device.build_adb_cmd", return_value=["adb", "shell", "false"])
    def test_nonzero_exit_returns_false(self, mock_build: MagicMock,
                                        mock_run: MagicMock) -> None:
        """run_adb_checked returns (False, stderr) on non-zero exit."""
        mock_run.return_value = MagicMock(
            returncode=1, stdout="", stderr="error: command failed"
        )
        ok, err = run_adb_checked("shell", "false")
        self.assertFalse(ok)
        self.assertIn("command failed", err)

    @patch("adb_harness.device.subprocess.run")
    @patch("adb_harness.device.build_adb_cmd", return_value=["adb", "shell", "foo"])
    def test_nonzero_exit_without_stderr(self, mock_build: MagicMock,
                                         mock_run: MagicMock) -> None:
        """run_adb_checked returns (False, "exit code N") when stderr is empty."""
        mock_run.return_value = MagicMock(
            returncode=2, stdout="unexpected output", stderr=""
        )
        ok, err = run_adb_checked("shell", "foo")
        self.assertFalse(ok)
        self.assertEqual(err, "unexpected output")

    @patch("adb_harness.device.subprocess.run", side_effect=subprocess.TimeoutExpired(
        cmd=["adb"], timeout=5.0, output="", stderr=""))
    @patch("adb_harness.device.build_adb_cmd", return_value=["adb", "shell", "sleep", "30"])
    def test_timeout_returns_false(self, mock_build: MagicMock,
                                   mock_run: MagicMock) -> None:
        """run_adb_checked returns (False, "timeout") on TimeoutExpired."""
        ok, err = run_adb_checked("shell", "sleep", "30", timeout=5.0)
        self.assertFalse(ok)
        self.assertEqual(err, "timeout")

    @patch("adb_harness.device.subprocess.run", side_effect=FileNotFoundError())
    @patch("adb_harness.device.build_adb_cmd", return_value=["adb", "shell", "cmd"])
    def test_file_not_found_returns_false(self, mock_build: MagicMock,
                                          mock_run: MagicMock) -> None:
        """run_adb_checked returns (False, "adb not found") on FileNotFoundError."""
        ok, err = run_adb_checked("shell", "cmd")
        self.assertFalse(ok)
        self.assertEqual(err, "adb not found")

    @patch("adb_harness.device.subprocess.run", side_effect=PermissionError("denied"))
    @patch("adb_harness.device.build_adb_cmd", return_value=["adb", "shell", "cmd"])
    def test_os_error_returns_false(self, mock_build: MagicMock,
                                    mock_run: MagicMock) -> None:
        """run_adb_checked returns (False, str) on OSError."""
        ok, err = run_adb_checked("shell", "cmd")
        self.assertFalse(ok)
        self.assertIn("denied", err)


class CleanupClockAlertsTest(unittest.TestCase):
    """cleanup_clock_alerts() unit tests with mocked ADB."""

    def setUp(self) -> None:
        # Mock every run_adb_checked call to return success by default
        self._patcher = patch("adb_harness.device.run_adb_checked",
                              return_value=(True, ""))
        self._mock_checked = self._patcher.start()

    def tearDown(self) -> None:
        self._patcher.stop()

    def test_all_ok_returns_true(self) -> None:
        """cleanup_clock_alerts returns True when all commands succeed."""
        result = cleanup_clock_alerts(force_stop_last=True)
        self.assertTrue(result)

    def test_best_effort_stop_timer_alerts_fail_does_not_fail(self) -> None:
        """stop_timer_alerts best-effort: failure does NOT fail the overall result."""
        self._mock_checked.side_effect = [
            (False, "timeout"),   # stop_timer_alerts (best-effort)
            (True, ""),           # stop_alert
            (True, ""),           # dismiss_notifications
            (True, ""),           # force-stop pkg 1
            (True, ""),           # force-stop pkg 2
            (True, ""),           # force-stop pkg 3
            (True, ""),           # force-stop Jandal
        ]
        result = cleanup_clock_alerts(force_stop_last=True)
        self.assertTrue(result)

    def test_best_effort_stop_alert_fail_does_not_fail(self) -> None:
        """stop_alert best-effort: failure does NOT fail the overall result."""
        self._mock_checked.side_effect = [
            (True, ""),           # stop_timer_alerts
            (False, "exit code 1"),  # stop_alert (best-effort)
            (True, ""),           # dismiss_notifications
            (True, ""),           # force-stop pkg 1
            (True, ""),           # force-stop pkg 2
            (True, ""),           # force-stop pkg 3
            (True, ""),           # force-stop Jandal
        ]
        result = cleanup_clock_alerts(force_stop_last=True)
        self.assertTrue(result)

    def test_best_effort_dismiss_notifications_fail_does_not_fail(self) -> None:
        """dismiss_notifications best-effort: failure does NOT fail overall result."""
        self._mock_checked.side_effect = [
            (True, ""),           # stop_timer_alerts
            (True, ""),           # stop_alert
            (False, "timeout"),   # dismiss_notifications (best-effort)
            (True, ""),           # force-stop pkg 1
            (True, ""),           # force-stop pkg 2
            (True, ""),           # force-stop pkg 3
            (True, ""),           # force-stop Jandal
        ]
        result = cleanup_clock_alerts(force_stop_last=True)
        self.assertTrue(result)

    def test_force_stop_jandal_fail_returns_false(self) -> None:
        """Return False when the critical force-stop of Jandal fails."""
        self._mock_checked.side_effect = [
            (True, ""),           # stop_timer_alerts
            (True, ""),           # stop_alert
            (True, ""),           # dismiss_notifications
            (True, ""),           # force-stop pkg 1
            (True, ""),           # force-stop pkg 2
            (True, ""),           # force-stop pkg 3
            (False, "exit code 1"),  # force-stop Jandal (CRITICAL)
        ]
        result = cleanup_clock_alerts(force_stop_last=True)
        self.assertFalse(result)

    def test_force_stop_not_called_when_false(self) -> None:
        """When force_stop_last=False, the Jandal force-stop should be omitted."""
        self._mock_checked.side_effect = [
            (True, ""),           # stop_timer_alerts
            (True, ""),           # stop_alert
            (True, ""),           # dismiss_notifications
            (True, ""),           # force-stop pkg 1
            (True, ""),           # force-stop pkg 2
            (True, ""),           # force-stop pkg 3
        ]
        result = cleanup_clock_alerts(force_stop_last=False)
        self.assertTrue(result)
        # 6 calls, not 7 (no Jandal force-stop)
        self.assertEqual(self._mock_checked.call_count, 6)

    def test_third_party_fail_does_not_fail_total(self) -> None:
        """Force-stop failing for non-Jandal packages is best-effort — does not fail."""
        self._mock_checked.side_effect = [
            (True, ""),           # stop_timer_alerts
            (True, ""),           # stop_alert
            (True, ""),           # dismiss_notifications
            (False, "exit code 1"),  # force-stop pkg 1 (harmless)
            (True, ""),           # force-stop pkg 2
            (True, ""),           # force-stop pkg 3
            (True, ""),           # force-stop Jandal
        ]
        result = cleanup_clock_alerts(force_stop_last=True)
        self.assertTrue(result)

    def test_all_best_effort_fail_still_succeeds_when_jandal_stopped(self) -> None:
        """All best-effort steps fail, but Jandal force-stop succeeds → still True."""
        self._mock_checked.side_effect = [
            (False, "timeout"),    # stop_timer_alerts
            (False, "not found"),  # stop_alert
            (False, "unknown cmd"),# dismiss_notifications
            (False, "exit code 1"),# force-stop pkg 1
            (False, "exit code 1"),# force-stop pkg 2
            (False, "exit code 1"),# force-stop pkg 3
            (True, ""),            # force-stop Jandal (SUCCESS)
        ]
        result = cleanup_clock_alerts(force_stop_last=True)
        self.assertTrue(result)



class ExitCleanupFailedTest(unittest.TestCase):
    """EXIT_CLEANUP_FAILED constant verification."""

    def test_exit_code_value(self) -> None:
        """EXIT_CLEANUP_FAILED is 46."""
        self.assertEqual(EXIT_CLEANUP_FAILED, 46)

    def test_run_tests_imports_exit_code(self) -> None:
        """Verify runners imports EXIT_CLEANUP_FAILED from model_readiness."""
        from adb_harness.runners import EXIT_CLEANUP_FAILED as runners_exit
        self.assertEqual(runners_exit, 46)


if __name__ == "__main__":
    unittest.main()
