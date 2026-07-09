#!/usr/bin/env python3
"""Tests for isolated warmup recovery path (#1372).

Covers warmup preflight ordering and logcat-stream recovery in
``_isolated_warmup()``:

- Stream healthy → oracle healthy → warmup succeeds (0)
- Stream unhealthy → logcat_restart succeeds → re-verify healthy → 0
- Stream unhealthy → restart succeeds → re-verify fails → _STREAM_UNHEALTHY (43)
- Stream unhealthy → restart fails → _STREAM_UNHEALTHY (43)
- Stream healthy → oracle unhealthy → _ORACLE_UNHEALTHY (42)
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

HERE = Path(__file__).resolve().parent
SCRIPT_DIR = HERE.parent
sys.path.insert(0, str(SCRIPT_DIR))

# ── Helper: run _isolated_warmup with configurable mocks ──

_WARMED_LOGCAT = (
    "NativeIntentHandler.handle: intent=get_time\n"
    "Ready:\n"
)


def _warmup_with(
    *,
    stream_healthy: bool = True,
    restart_succeeds: bool = True,
    oracle_healthy: bool = True,
) -> int:
    """Run ``_isolated_warmup()`` with mocked ADB/harness dependencies.

    Args:
        stream_healthy: return value from ``check_logcat_stream``
        restart_succeeds: return value from ``logcat_restart``
        oracle_healthy: return value from ``check_oracle``

    Returns the exit code from ``_isolated_warmup()``.
    """
    stream_mock = MagicMock()
    if stream_healthy:
        stream_mock.return_value = True
    elif restart_succeeds:
        # Dead stream → restart → re-verify passes
        stream_mock.side_effect = [False, True]
    else:
        # Dead stream → restart fails → re-verify never called
        stream_mock.return_value = False

    with \
        patch("os.path.isfile", return_value=True), \
        patch("time.sleep"), \
        patch("adb_harness.runners.logcat_start"), \
        patch("adb_harness.runners.clear_logcat"), \
        patch("adb_harness.runners.read_logcat", return_value=_WARMED_LOGCAT), \
        patch("adb_harness.runners.run_adb", return_value=""), \
        patch("adb_harness.runners.start_keepalive"), \
        patch("adb_harness.runners.stop_keepalive"), \
        patch("adb_harness.runners.setup_contact_alias_fixture"), \
        patch("adb_harness.runners.setup_contact_family_fixture", return_value=True), \
        patch("adb_harness.runners.check_email_fixture", return_value=False), \
        patch("adb_harness.runners.cleanup_clock_alerts", return_value=True), \
        patch("adb_harness.runners.check_logcat_stream", stream_mock), \
        patch("adb_harness.runners.logcat_restart", return_value=restart_succeeds), \
        patch("adb_harness.runners.check_oracle", return_value=oracle_healthy):
        from adb_harness.runners import _isolated_warmup
        return _isolated_warmup()


# ── Test cases ──


class WarmupHealthyTest(unittest.TestCase):
    """Normal healthy warmup — no recovery needed."""

    def test_stream_and_oracle_healthy_returns_0(self) -> None:
        """Stream healthy + oracle healthy → warmup succeeds (0)."""
        rc = _warmup_with(stream_healthy=True, oracle_healthy=True)
        self.assertEqual(rc, 0)

    def test_missing_email_account_is_persisted_for_isolated_cases(self) -> None:
        """Isolated warmup records email fixture absence for later classification."""
        self.assertEqual(_warmup_with(), 0)

        from adb_harness.runners import _isolated_known_missing

        self.assertEqual(
            _isolated_known_missing,
            frozenset(["contacts:email_contact_seed"]),
        )


class WarmupStreamRecoveryTest(unittest.TestCase):
    """Stream unhealthy → logcat_restart recovery paths."""

    def test_restart_success_and_reverify_healthy_returns_0(self) -> None:
        """Dead stream → restart ok → re-verify ok → warmup succeeds (0)."""
        rc = _warmup_with(
            stream_healthy=False,
            restart_succeeds=True,
            oracle_healthy=True,
        )
        self.assertEqual(rc, 0)

    def test_restart_success_but_reverify_fails_returns_43(self) -> None:
        """Dead stream → restart ok → re-verify fails → _STREAM_UNHEALTHY (43)."""
        # check_logcat_stream: False (dead), then False (re-verify fails)
        stream_mock = MagicMock(side_effect=[False, False])

        with \
            patch("os.path.isfile", return_value=True), \
            patch("time.sleep"), \
            patch("adb_harness.runners.logcat_start"), \
            patch("adb_harness.runners.clear_logcat"), \
            patch("adb_harness.runners.read_logcat", return_value=_WARMED_LOGCAT), \
            patch("adb_harness.runners.start_keepalive"), \
            patch("adb_harness.runners.stop_keepalive"), \
            patch("adb_harness.runners.setup_contact_alias_fixture"), \
            patch("adb_harness.runners.setup_contact_family_fixture", return_value=True), \
            patch("adb_harness.runners.check_email_fixture", return_value=False), \
            patch("adb_harness.runners.cleanup_clock_alerts", return_value=True), \
            patch("adb_harness.runners.check_logcat_stream", stream_mock), \
            patch("adb_harness.runners.logcat_restart", return_value=True), \
            patch("adb_harness.runners.check_oracle", return_value=True):
            from adb_harness.runners import _isolated_warmup
            rc = _isolated_warmup()

        self.assertEqual(rc, 43)

    def test_restart_fails_returns_43(self) -> None:
        """Dead stream → restart fails → _STREAM_UNHEALTHY (43)."""
        rc = _warmup_with(
            stream_healthy=False,
            restart_succeeds=False,
        )
        self.assertEqual(rc, 43)


class WarmupOracleFailTest(unittest.TestCase):
    """Stream healthy but oracle fails — no contaminated evidence emitted."""

    def test_oracle_unhealthy_after_stream_healthy_returns_42(self) -> None:
        """Stream healthy → oracle unhealthy → _ORACLE_UNHEALTHY (42)."""
        rc = _warmup_with(stream_healthy=True, oracle_healthy=False)
        self.assertEqual(rc, 42)

    def test_oracle_unhealthy_message_says_logcat_stream_healthy(self) -> None:
        """Oracle failure prints ``logcat stream was healthy`` diagnostic.

        Verifies the diagnostic message distinguishes real app issues
        from logcat-stream contamination.
        """
        stream_mock = MagicMock(return_value=True)
        oracle_mock = MagicMock(return_value=False)

        with \
            patch("os.path.isfile", return_value=True), \
            patch("time.sleep"), \
            patch("adb_harness.runners.logcat_start"), \
            patch("adb_harness.runners.clear_logcat"), \
            patch("adb_harness.runners.read_logcat", return_value=_WARMED_LOGCAT), \
            patch("adb_harness.runners.start_keepalive"), \
            patch("adb_harness.runners.stop_keepalive"), \
            patch("adb_harness.runners.setup_contact_alias_fixture"), \
            patch("adb_harness.runners.setup_contact_family_fixture", return_value=True), \
            patch("adb_harness.runners.check_email_fixture", return_value=False), \
            patch("adb_harness.runners.cleanup_clock_alerts", return_value=True), \
            patch("adb_harness.runners.check_logcat_stream", stream_mock), \
            patch("adb_harness.runners.logcat_restart", return_value=True), \
            patch("adb_harness.runners.check_oracle", oracle_mock), \
            patch("builtins.print") as mock_print:
            from adb_harness.runners import _isolated_warmup
            _isolated_warmup()

        # Assert that the oracle-failure message includes
        # "logcat stream was healthy" — real app issue, not contamination
        printed = [
            call[0][0] for call in mock_print.call_args_list
            if call[0] and "logcat stream was healthy" in str(call[0][0])
        ]
        self.assertGreaterEqual(
            len(printed), 1,
            "Expected oracle failure message to mention logcat stream was healthy"
        )


if __name__ == "__main__":
    unittest.main()
