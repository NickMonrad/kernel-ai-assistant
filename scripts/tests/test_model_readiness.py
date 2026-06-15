#!/usr/bin/env python3
"""Tests for model_readiness.py — model readiness preflight.

Covers:
- Evidence record serialisation
- Logcat marker detection (_find_markers)
- Serial resolution from env vars
- Preflight state machine with mocked ADB functions
"""

from __future__ import annotations

import os
import sys
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

import itertools

HERE = Path(__file__).resolve().parent
SCRIPT_DIR = HERE.parent
sys.path.insert(0, str(SCRIPT_DIR))

from adb_harness.model_readiness import (
    ModelReadinessEvidence,
    _find_markers,
    _Marker,
    _MARKERS,
    preflight_model_readiness,
)
from adb_harness.device import build_adb_cmd


# ═══════════════════════════════════════════════════════════════════════
# Serial resolution tests (build_adb_cmd)
# ═══════════════════════════════════════════════════════════════════════


class BuildAdbCmdTest(unittest.TestCase):
    """Serial resolution from env vars is dynamic."""

    def setUp(self) -> None:
        self._saved_android = os.environ.pop("ANDROID_SERIAL", None)
        self._saved_adb = os.environ.pop("ADB_SERIAL", None)

    def tearDown(self) -> None:
        for key, val in [("ANDROID_SERIAL", self._saved_android),
                         ("ADB_SERIAL", self._saved_adb)]:
            if val is not None:
                os.environ[key] = val
            else:
                os.environ.pop(key, None)

    def test_no_serial_when_no_env_var(self) -> None:
        """No -s flag when neither env var is set."""
        cmd = build_adb_cmd("shell", "echo", "hello")
        self.assertNotIn("-s", cmd)
        self.assertEqual(cmd[-3:], ["shell", "echo", "hello"])

    def test_serial_from_android_serial(self) -> None:
        """ANDROID_SERIAL produces `-s <serial>` in the command."""
        os.environ["ANDROID_SERIAL"] = "R5CR605B71K"
        cmd = build_adb_cmd("logcat", "-c")
        self.assertIn("-s", cmd)
        s_idx = cmd.index("-s")
        self.assertEqual(cmd[s_idx + 1], "R5CR605B71K")

    def test_serial_from_adb_serial(self) -> None:
        """ADB_SERIAL produces `-s <serial>` when ANDROID_SERIAL is absent."""
        os.environ["ADB_SERIAL"] = "FOOBAR123"
        cmd = build_adb_cmd("shell", "ls")
        self.assertIn("-s", cmd)
        s_idx = cmd.index("-s")
        self.assertEqual(cmd[s_idx + 1], "FOOBAR123")

    def test_android_serial_takes_precedence(self) -> None:
        """ANDROID_SERIAL is preferred over ADB_SERIAL when both are set."""
        os.environ["ANDROID_SERIAL"] = "R5CR605B71K"
        os.environ["ADB_SERIAL"] = "OTHER_DEVICE"
        cmd = build_adb_cmd("version")
        s_idx = cmd.index("-s")
        self.assertEqual(cmd[s_idx + 1], "R5CR605B71K")

    def test_set_env_before_call(self) -> None:
        """Setting ANDROID_SERIAL between calls changes the target."""
        cmd1 = build_adb_cmd("version")
        self.assertNotIn("-s", cmd1)
        os.environ["ANDROID_SERIAL"] = "DYNAMIC"
        cmd2 = build_adb_cmd("version")
        self.assertIn("-s", cmd2)
        s_idx = cmd2.index("-s")
        self.assertEqual(cmd2[s_idx + 1], "DYNAMIC")


# ═══════════════════════════════════════════════════════════════════════
# Evidence record tests
# ═══════════════════════════════════════════════════════════════════════


class ModelReadinessEvidenceTest(unittest.TestCase):
    """Evidence record serialisation."""

    def test_default_evidence_fields(self) -> None:
        """Default evidence has expected required_model and model_file."""
        e = ModelReadinessEvidence(device_serial="test_serial")
        self.assertEqual(e.device_serial, "test_serial")
        self.assertEqual(e.required_model, "Gemma 4 E-2B")
        self.assertEqual(e.model_file, "gemma-4-E2B-it.litertlm")

    def test_to_dict_contains_all_fields(self) -> None:
        """to_dict() returns a dict with all expected keys."""
        e = ModelReadinessEvidence(
            device_serial="test_serial",
            initial_state="Preparing",
            hf_signin_shown=True,
            hf_signin_clicked=True,
            download_triggered=True,
            readiness_wait_seconds=123.4,
            final_state="Ready",
            failure_bucket=None,
            logcat_markers={"engine_ready": True},
        )
        d = e.to_dict()
        self.assertEqual(d["device_serial"], "test_serial")
        self.assertEqual(d["initial_state"], "Preparing")
        self.assertEqual(d["hf_signin_shown"], True)
        self.assertEqual(d["hf_signin_clicked"], True)
        self.assertEqual(d["download_triggered"], True)
        self.assertEqual(d["readiness_wait_seconds"], 123.4)
        self.assertEqual(d["final_state"], "Ready")
        self.assertIsNone(d["failure_bucket"])
        self.assertEqual(d["logcat_markers"]["engine_ready"], True)

    def test_to_dict_rounds_readiness_wait(self) -> None:
        """readiness_wait_seconds is rounded to 1 decimal."""
        e = ModelReadinessEvidence(
            device_serial="s",
            readiness_wait_seconds=45.6789,
        )
        self.assertEqual(e.to_dict()["readiness_wait_seconds"], 45.7)

    def test_to_dict_failure_bucket_present(self) -> None:
        """failure_bucket is serialised when set."""
        e = ModelReadinessEvidence(
            device_serial="s",
            failure_bucket="MODEL_NOT_READY",
        )
        self.assertEqual(e.to_dict()["failure_bucket"], "MODEL_NOT_READY")


# ═══════════════════════════════════════════════════════════════════════
# Marker detection tests
# ═══════════════════════════════════════════════════════════════════════


class FindMarkersTest(unittest.TestCase):
    """Logcat marker detection (_find_markers)."""

    def setUp(self) -> None:
        self.test_markers = [
            _Marker("download_completed", r"Download succeeded:"),
            _Marker("engine_ready", r"Engine ready — backend:"),
            _Marker("hf_signin", r"Sign in to HuggingFace"),
        ]

    def test_find_marker_single_match(self) -> None:
        """Single matching marker is found."""
        text = "Some log output\nDownload succeeded: /path/to/model\nmore logs"
        result = _find_markers(text, self.test_markers)
        self.assertTrue(result["download_completed"])
        self.assertFalse(result["engine_ready"])
        self.assertFalse(result["hf_signin"])

    def test_find_marker_multiple_match(self) -> None:
        """Multiple markers can be found in the same text."""
        text = (
            "Engine ready — backend: GPU\n"
            "Download succeeded: /path\n"
            "Sign in to HuggingFace"
        )
        result = _find_markers(text, self.test_markers)
        self.assertTrue(result["download_completed"])
        self.assertTrue(result["engine_ready"])
        self.assertTrue(result["hf_signin"])

    def test_find_marker_no_match(self) -> None:
        """No markers found in unrelated text."""
        text = "Some unrelated logcat output"
        result = _find_markers(text, self.test_markers)
        for v in result.values():
            self.assertFalse(v)

    def test_find_marker_empty_text(self) -> None:
        """Empty text produces no matches."""
        result = _find_markers("", self.test_markers)
        for v in result.values():
            self.assertFalse(v)

    def test_find_marker_case_sensitive_by_default(self) -> None:
        """Default _Marker patterns are case-sensitive."""
        text = "engine ready — backend: gpu"
        result = _find_markers(text, self.test_markers)
        self.assertFalse(result["engine_ready"])

    def test_matches_actual_markers_auto_queue(self) -> None:
        """_MARKERS[auto_queue_seen] matches the app's actual logcat output."""
        text = "Auto-queuing required model: Gemma 4 E-2B"
        result = _find_markers(text, _MARKERS)
        self.assertTrue(result.get("auto_queue_seen"))

    def test_matches_actual_markers_downloaded(self) -> None:
        """_MARKERS[download_completed] matches the app's actual log output."""
        text = "Refreshed state for Gemma 4 E-2B: Downloaded(/path/to/model)"
        result = _find_markers(text, _MARKERS)
        self.assertTrue(result.get("download_completed"))

    def test_matches_actual_markers_engine_ready(self) -> None:
        """_MARKERS[engine_ready] matches the app's actual log output."""
        text = "Engine ready — backend: GPU, maxTokens: 2048"
        result = _find_markers(text, _MARKERS)
        self.assertTrue(result.get("engine_ready"))

    def test_matches_actual_markers_download_failed(self) -> None:
        """_MARKERS[download_failed] matches the app's actual log output."""
        text = "Download failed for Gemma 4 E-2B: Network error"
        result = _find_markers(text, _MARKERS)
        self.assertTrue(result.get("download_failed"))

    def test_matches_actual_markers_gated_no_token(self) -> None:
        """_MARKERS[hf_token_missing] matches the app's actual log output."""
        text = "EmbeddingGemma 300M is gated but no HF token available"
        result = _find_markers(text, _MARKERS)
        self.assertTrue(result.get("hf_token_missing"))

    def test_matches_actual_markers_hf_signin_approved(self) -> None:
        """_MARKERS[hf_signin_approved] matches the app's actual log output."""
        text = "Set gemma_4_e2b → APPROVED"
        result = _find_markers(text, _MARKERS)
        self.assertTrue(result.get("hf_signin_approved"))


# ═══════════════════════════════════════════════════════════════════════
# Fake clock for deterministic timeout tests
# ═══════════════════════════════════════════════════════════════════════


class _FakeClock:
    """Fake clock used in place of ``time.time`` / ``time.sleep``.

    ``sleep(N)`` advances the clock by N seconds so deadline-based loops
    terminate deterministically in unit tests.
    """

    def __init__(self, start: float = 0.0) -> None:
        self._now = start

    def time(self) -> float:
        return self._now

    def sleep(self, secs: float) -> None:
        self._now += secs


# ═══════════════════════════════════════════════════════════════════════
# Preflight state machine tests
# ═══════════════════════════════════════════════════════════════════════


class PreflightStateMachineTest(unittest.TestCase):
    """Preflight logic with mocked ADB.

    Uses ``_FakeClock`` for ``time.time`` / ``time.sleep`` so that
    polling loops with deadlines terminate deterministically.
    """

    def setUp(self) -> None:
        self._clock = _FakeClock()
        self.patches = [
            patch("adb_harness.model_readiness.logcat_start"),
            patch("adb_harness.model_readiness.clear_logcat"),
            patch("adb_harness.model_readiness.run_adb"),
            patch("adb_harness.model_readiness.time.time", side_effect=self._clock.time),
            patch("adb_harness.model_readiness.time.sleep", side_effect=self._clock.sleep),
            patch("adb_harness.model_readiness._uiautomator_has_text", return_value=False),
            patch("adb_harness.model_readiness._uiautomator_tap_text", return_value=False),
        ]
        for p in self.patches:
            p.start()

    def tearDown(self) -> None:
        for p in self.patches:
            p.stop()

    # ── Engine-already-ready (true fast path) ─────────────────────

    @patch("adb_harness.model_readiness._read_fresh_logcat")
    def test_engine_already_ready(self, mock_read: MagicMock) -> None:
        """Fast path: engine_ready marker in logcat → immediate success."""
        mock_read.return_value = "Engine ready — backend: GPU\nInit complete"
        evidence = preflight_model_readiness(verbose=False)
        self.assertEqual(evidence.initial_state, "Ready")
        self.assertEqual(evidence.final_state, "Ready")
        self.assertIsNone(evidence.failure_bucket)
        self.assertIn("engine_already_ready", evidence.logcat_markers)

    # ── Model-on-disk scenarios ───────────────────────────────────

    @patch("adb_harness.model_readiness._check_model_on_disk", return_value=True)
    @patch("adb_harness.model_readiness._read_fresh_logcat")
    def test_model_on_disk_with_engine(
        self, mock_read: MagicMock, mock_disk: MagicMock,
    ) -> None:
        """Model on disk + engine_ready marker → success."""
        mock_read.return_value = "Engine ready — backend: GPU"
        evidence = preflight_model_readiness(
            verbose=False, timeout_download=5.0, timeout_engine=5.0,
        )
        self.assertEqual(evidence.initial_state, "Downloaded")
        self.assertEqual(evidence.final_state, "Ready")
        self.assertIsNone(evidence.failure_bucket)
        self.assertIn("model_on_disk", evidence.logcat_markers)

    @patch("adb_harness.model_readiness._check_model_on_disk", return_value=True)
    @patch("adb_harness.model_readiness._read_fresh_logcat")
    def test_model_on_disk_without_engine(
        self, mock_read: MagicMock, mock_disk: MagicMock,
    ) -> None:
        """Model on disk but no engine_ready → ENGINE_NOT_READY."""
        mock_read.return_value = ""
        evidence = preflight_model_readiness(
            verbose=False, timeout_download=1.0, timeout_engine=0.5,
        )
        self.assertEqual(evidence.initial_state, "Downloaded")
        self.assertEqual(evidence.failure_bucket, "ENGINE_NOT_READY")
        self.assertIsNone(evidence.final_state)
        self.assertIn("model_on_disk", evidence.logcat_markers)

    # ── Timeout scenarios ─────────────────────────────────────────

    @patch("adb_harness.model_readiness._check_model_on_disk", return_value=False)
    @patch("adb_harness.model_readiness._read_fresh_logcat")
    def test_timeout_no_download(
        self, mock_read: MagicMock, mock_disk: MagicMock,
    ) -> None:
        """No download markers → MODEL_NOT_READY after timeout."""
        mock_read.return_value = ""
        evidence = preflight_model_readiness(
            verbose=False, timeout_download=10.0, timeout_engine=10.0,
        )
        self.assertEqual(evidence.failure_bucket, "MODEL_NOT_READY")

    @patch("adb_harness.model_readiness._check_model_on_disk", return_value=False)
    @patch("adb_harness.model_readiness._read_fresh_logcat")
    def test_download_started_but_timed_out(
        self, mock_read: MagicMock, mock_disk: MagicMock,
    ) -> None:
        """Enqueue seen but download never finishes → MODEL_DOWNLOAD_TIMEOUT."""
        mock_read.return_value = "Enqueuing download for Gemma 4 E-2B"
        evidence = preflight_model_readiness(
            verbose=False, timeout_download=10.0, timeout_engine=10.0,
        )
        self.assertEqual(evidence.failure_bucket, "MODEL_DOWNLOAD_TIMEOUT")
        self.assertTrue(evidence.download_triggered)

    @patch("adb_harness.model_readiness._read_fresh_logcat")
    def test_download_failed(self, mock_read: MagicMock) -> None:
        """Download failed marker → MODEL_DOWNLOAD_FAILED."""
        mock_read.return_value = "Download failed for Gemma 4 E-2B: Network error"
        evidence = preflight_model_readiness(
            verbose=False, timeout_download=1.0, timeout_engine=1.0,
        )
        self.assertEqual(evidence.failure_bucket, "MODEL_DOWNLOAD_FAILED")

    # ── Full success path (fresh download) ────────────────────────

    @patch("adb_harness.model_readiness._read_fresh_logcat")
    def test_full_success_path(self, mock_read: MagicMock) -> None:
        """Download + engine init full flow succeeds."""
        mock_read.side_effect = itertools.cycle([
            "",
            "Auto-queuing required model: Gemma 4 E-2B",
            "Refreshed state for Gemma 4 E-2B: Downloaded(/path/model)",
            "Engine ready — backend: GPU",
        ])
        evidence = preflight_model_readiness(
            verbose=False, timeout_download=30.0, timeout_engine=30.0,
        )
        self.assertEqual(evidence.final_state, "Ready")
        self.assertIsNone(evidence.failure_bucket)
        self.assertTrue(evidence.download_triggered)

    # ── HF sign-in ────────────────────────────────────────────────

    @patch("adb_harness.model_readiness._read_fresh_logcat")
    def test_hf_signin_needed_but_not_in_ui(
        self, mock_read: MagicMock,
    ) -> None:
        """hf_token_missing detected → ActionRequired state."""
        mock_read.return_value = "EmbeddingGemma 300M is gated but no HF token available"
        evidence = preflight_model_readiness(
            verbose=False, timeout_download=1.0, timeout_engine=1.0,
        )
        self.assertEqual(evidence.initial_state, "ActionRequired(SignInRequired)")


if __name__ == "__main__":
    unittest.main()
